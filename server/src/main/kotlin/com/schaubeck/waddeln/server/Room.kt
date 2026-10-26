package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.BotStrength
import com.schaubeck.waddeln.protocol.CutAnimation
import com.schaubeck.waddeln.protocol.CutView
import com.schaubeck.waddeln.protocol.GameView
import com.schaubeck.waddeln.protocol.RoomInfo
import com.schaubeck.waddeln.protocol.SeatAction
import com.schaubeck.waddeln.protocol.SeatHint
import com.schaubeck.waddeln.protocol.SeatSchadet
import com.schaubeck.waddeln.protocol.SeatInfo
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.CutReveal
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.PendingRound
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.BotTalk
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.PartnerHints
import com.schaubeck.waddeln.rules.agent.Schadet
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.agent.Schlagwechsel
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.partnerOf
import com.schaubeck.waddeln.rules.playersOf
import com.schaubeck.waddeln.rules.teamOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.random.Random

/** Pauses so humans can follow the bots (like the app's slow speed). */
data class Timing(
    val botDelayMs: Long = 1600,
    /** After a completed trick, so it can be seen and collected. */
    val trickPauseMs: Long = 2600,
    /** After a Schub was answered with Bleiben, so the notice can be read. */
    val schubPauseMs: Long = 1500,
    /** After a Schlag, Farbe or Schub was said, so the speech bubble can be read (with [botDelayMs] like the app's speechMs). */
    val speechPauseMs: Long = 1400,
    /** A human who has to decide gets this long; then the bot decides for them. Null = no limit. */
    val turnTimeoutMs: Long? = 60_000,
    /** Like [turnTimeoutMs] for the Abheber; then a random cut is made for them. */
    val cutTimeoutMs: Long? = 30_000,
)

/** How [abheber] cut the deck of the current round. */
private data class RoomCut(val abheber: Int, val cut: Int, val reveal: CutReveal)

/** A human in a room; [client] is null while disconnected. */
class Member(val playerId: String, var name: String, var client: Client?)

/**
 * The bot that has to act next, or null if nobody or a human has to act. Seats in [humanSeats]
 * are controlled by connected humans. A Schub is answered by a human of the asked team if there is
 * one; otherwise by the asked bot that comes next in playing order.
 */
fun nextBotActor(state: GameState, humanSeats: Set<Int>): Int? {
    val toMove = state.playerToMove ?: return null
    val pending = state.pendingSchub
    if (pending != null) {
        val answering = playersOf(pending.answeringTeam)
        if (answering.any { it in humanSeats }) return null
        return generateSequence(toMove, ::nextPlayer).first { teamOf(it) == pending.answeringTeam }
    }
    return toMove.takeIf { it !in humanSeats }
}

/**
 * A private room with four seats. Holds the authoritative [GameState]; every player only gets the
 * observation of their own seat. Seats without connected human are played by bots.
 *
 * Not thread-safe: all calls must come from the server's single game thread ([scope]).
 */
class Room(
    val code: String,
    private val scope: CoroutineScope,
    private val bots: BotProvider,
    private val timing: Timing,
    private val random: Random,
    private val clock: () -> Long,
) {
    private val seats = arrayOfNulls<Member>(PLAYER_COUNT)
    private var hostId: String? = null
    private var strength = BotStrength.HARD
    private val botNames = BOT_NAMES.shuffled(random).take(PLAYER_COUNT).toTypedArray()
    private var agents: List<Agent> = createAgents()
    private var state: GameState? = null
    private val ready = BooleanArray(PLAYER_COUNT)
    private var lastAction: SeatAction? = null

    /** The next round waits for the Abheber to cut; meanwhile [state] is its preview without cards. */
    private var pendingRound: PendingRound? = null

    /** The cut of the current round, shown to everybody (the Kritten only to the Abheber). */
    private var lastCut: RoomCut? = null

    /**
     * Seat that asked its partner during the open Schub. The answer and "Schadder?" are part of
     * the game state (everybody hears them, the bots take them into account).
     */
    private var question: Int? = null

    /** Seat that shows the last completed trick to the table; hidden again with the next action. */
    private var lastTrickShownBy: Int? = null
    private var botJob: Job? = null

    /** Runs out when a human takes too long; [timerState] is the state it was started for. */
    private var timeoutJob: Job? = null
    private var timerState: GameState? = null
    private var turnDeadline: Long? = null

    /** Start time (ms) of the running match, for the log. */
    private var matchStartedAt = 0L

    /** Time (ms) since when no human is connected, for cleanup; null while someone is connected. */
    var abandonedSince: Long? = null
        private set

    val members: List<Member> get() = seats.filterNotNull()
    val isEmpty: Boolean get() = members.isEmpty()
    val started: Boolean get() = state != null

    fun seatOf(playerId: String): Int? = seats.indexOfFirst { it?.playerId == playerId }.takeIf { it >= 0 }

    /** Seats a new member: before the match on the first free seat, afterwards on a seat played by a bot. */
    fun join(member: Member): String? {
        val seat = seats.indexOfFirst { it == null }
        if (seat < 0) return "Der Raum $code ist voll."
        seats[seat] = member
        if (hostId == null) hostId = member.playerId
        changed()
        return null
    }

    /** The member's connection changed (connected, reconnected or lost). */
    fun connectionChanged() = changed()

    /** Stops the bots; the room is no longer used. */
    fun close() {
        botJob?.cancel()
        botJob = null
        stopTimer()
    }

    fun takeSeat(playerId: String, seat: Int): String? {
        val from = seatOf(playerId) ?: return null
        if (started) return "Die Plätze sind nach dem Start fest."
        if (seat !in 0 until PLAYER_COUNT || seats[seat] != null) return "Dieser Platz ist besetzt."
        seats[seat] = seats[from]
        seats[from] = null
        changed()
        return null
    }

    /** Removes the member for good; their seat is played by a bot and is free for others. */
    fun leave(playerId: String) {
        val seat = seatOf(playerId) ?: return
        seats[seat] = null
        if (hostId == playerId) hostId = members.firstOrNull()?.playerId
        ready[seat] = false
        changed()
    }

    fun setStrength(playerId: String, newStrength: BotStrength): String? {
        if (playerId != hostId) return "Nur der Gastgeber kann die Bots einstellen."
        strength = newStrength
        agents = createAgents()
        changed()
        return null
    }

    fun setBotName(playerId: String, seat: Int, name: String): String? {
        if (playerId != hostId) return "Nur der Gastgeber kann die Bots umbenennen."
        if (seat !in 0 until PLAYER_COUNT || seats[seat] != null) return "Auf diesem Platz sitzt kein Bot."
        val clean = cleanName(name)
        if (clean.isEmpty()) return "Der Name darf nicht leer sein."
        botNames[seat] = clean
        changed()
        return null
    }

    fun start(playerId: String): String? {
        if (playerId != hostId) return "Nur der Gastgeber kann das Spiel starten."
        if (started) return null
        prepare(Waddeln.prepareMatch(random))
        logMatchStart()
        changed()
        return null
    }

    /** The Abheber cuts after [cut] cards; then the round is dealt. */
    fun cut(playerId: String, cut: Int): String? {
        val seat = seatOf(playerId) ?: return null
        val round = pendingRound ?: return null
        if (seat != round.abheber) return null
        if (cut !in Deal.CUT_RANGE) return "So kann nicht abgehoben werden."
        cut(round, cut)
        return null
    }

    fun act(playerId: String, action: Action): String? {
        val seat = seatOf(playerId) ?: return null
        val s = state ?: return "Das Spiel hat noch nicht begonnen."
        // Stale clicks (e.g. a card after the trick moved on) are simply ignored.
        if (!Waddeln.isLegal(s, seat, action)) return null
        apply(seat, action)
        return null
    }

    /**
     * During a Schub against the player's team: asks the partner how good their cards are.
     * A bot partner answers at once, a human one with [answerPartner].
     */
    fun askPartner(playerId: String): String? {
        val seat = seatOf(playerId) ?: return null
        val s = state ?: return null
        if (!canAskPartner(s, seat)) return null
        question = seat
        changed()
        return null
    }

    fun answerPartner(playerId: String, answer: PartnerHint): String? {
        val seat = seatOf(playerId) ?: return null
        val s = state ?: return null
        val asking = question ?: return null
        if (partnerOf(asking) != seat || !Waddeln.canGiveHint(s, seat)) return null
        state = Waddeln.giveHint(s, seat, answer)
        changed()
        return null
    }

    private fun canAskPartner(s: GameState, seat: Int): Boolean =
        question == null && Waddeln.canGiveHint(s, partnerOf(seat))

    /** A partner played by a bot (also after its human left) answers the question itself. */
    private fun botAnswersQuestion() {
        val s = state ?: return
        val partner = partnerOf(question ?: return)
        if (partner in humanSeats() || !Waddeln.canGiveHint(s, partner)) return
        state = Waddeln.giveHint(s, partner, PartnerHints.hintFor(s.observationFor(partner)))
    }

    /** The partner's answer during the open Schub, if given. */
    private fun currentHint(s: GameState): SeatHint? = s.hints.lastOrNull()
        ?.takeIf { s.pendingSchub != null && it.schub == s.schubs.lastIndex }
        ?.let { SeatHint(it.player, it.hint) }

    /** Before announcing the Schlag: the player after the dealer proposes a Schlagwechsel. */
    fun proposeSchlagwechsel(playerId: String): String? {
        val seat = seatOf(playerId) ?: return null
        val s = state ?: return null
        if (!Waddeln.canProposeSchlagwechsel(s, seat)) return null
        state = Waddeln.proposeSchlagwechsel(s, seat)
        changed(timing.speechPauseMs)
        return null
    }

    fun answerSchlagwechsel(playerId: String, accept: Boolean): String? {
        val seat = seatOf(playerId) ?: return null
        val s = state ?: return null
        if (Waddeln.schlagwechselAnswerer(s) != seat) return null
        state = Waddeln.answerSchlagwechsel(s, seat, accept)
        changed(timing.speechPauseMs)
        return null
    }

    private fun botAnswersSchlagwechsel(s: GameState, seat: Int) {
        state = Waddeln.answerSchlagwechsel(s, seat, Schlagwechsel.accepts(s.observationFor(seat)))
        changed(timing.speechPauseMs)
    }

    /** Before leading the first trick (with the Haubi or as a bluff): asks the partner "Schadder?". */
    fun askSchadet(playerId: String): String? {
        val seat = seatOf(playerId) ?: return null
        val s = state ?: return null
        if (!Waddeln.canAskSchadet(s, seat)) return null
        state = Waddeln.askSchadet(s, seat)
        changed()
        return null
    }

    fun answerSchadet(playerId: String, answer: SchadetAnswer): String? {
        val seat = seatOf(playerId) ?: return null
        val s = state ?: return null
        if (Waddeln.schadetAnswerer(s) != seat) return null
        state = Waddeln.answerSchadet(s, seat, answer)
        changed(timing.speechPauseMs)
        return null
    }

    private fun botAnswersSchadet(s: GameState, seat: Int) {
        state = Waddeln.answerSchadet(s, seat, Schadet.answerFor(s.observationFor(seat)))
        changed(timing.speechPauseMs)
    }

    /**
     * Lets the bot [actor] act, talking like [BotTalk]: before announcing the Schlag it may propose
     * a Schlagwechsel, before answering a Schub it asks its partner, before leading the Haubi it asks
     * "Schadder?" and then decides knowing the answer.
     */
    private fun botAct(s: GameState, actor: Int) {
        if (Waddeln.canProposeSchlagwechsel(s, actor) && Schlagwechsel.proposes(s.observationFor(actor))) {
            state = Waddeln.proposeSchlagwechsel(s, actor)
            changed(timing.speechPauseMs)
            return
        }
        if (question == null && Waddeln.canGiveHint(s, partnerOf(actor))) {
            question = actor
            changed(timing.speechPauseMs)
            return
        }
        val legal = Schadet.allowedActions(s, actor, Waddeln.legalActions(s, actor))
        val action = agents[actor].chooseAction(s.observationFor(actor), legal)
        if (BotTalk.talksSchadet(s, actor, action, random)) {
            state = Waddeln.askSchadet(s, actor)
            changed(timing.speechPauseMs)
            return
        }
        apply(actor, action)
    }

    /** The player to move shows ([show]) or hides the last completed trick; everybody sees it. */
    fun showLastTrick(playerId: String, show: Boolean): String? {
        val seat = seatOf(playerId) ?: return null
        val s = state ?: return null
        if (!Waddeln.canShowLastTrick(s, seat)) return null
        val shownBy = if (show) seat else null
        if (shownBy == lastTrickShownBy) return null
        lastTrickShownBy = shownBy
        broadcast()
        return null
    }

    /** Confirms the end of a round or match; the next one starts once all connected humans confirmed. */
    fun ready(playerId: String): String? {
        val seat = seatOf(playerId) ?: return null
        val phase = state?.phase ?: return null
        if (pendingRound != null || (phase != Phase.ROUND_OVER && phase != Phase.MATCH_OVER)) return null
        ready[seat] = true
        changed()
        return null
    }

    private fun createAgents(): List<Agent> = List(PLAYER_COUNT) { bots.create(strength) }

    private fun humanSeats(): Set<Int> = seats.indices.filter { seats[it]?.client != null }.toSet()

    private fun apply(seat: Int, action: Action) {
        val before = state!!
        val after = Waddeln.apply(before, seat, action)
        state = after
        lastAction = SeatAction(seat, action)
        lastTrickShownBy = null
        if (after.pendingSchub == null) question = null
        if (before.result == null) after.result?.let { logRoundEnd(after) }
        val pause = when {
            after.completedTricks.size > before.completedTricks.size -> timing.trickPauseMs
            action == Action.Bleiben -> timing.schubPauseMs
            action !is Action.PlayCard -> timing.speechPauseMs
            else -> 0
        }
        changed(pause)
    }

    /** Starts the next round or match if everyone connected is ready. */
    private fun advanceIfReady() {
        val s = state ?: return
        if (pendingRound != null || (s.phase != Phase.ROUND_OVER && s.phase != Phase.MATCH_OVER)) return
        val waiting = humanSeats()
        if (waiting.isEmpty() || waiting.any { !ready[it] }) return
        ready.fill(false)
        if (s.phase == Phase.MATCH_OVER) {
            prepare(Waddeln.prepareMatch(random))
            logMatchStart()
        } else {
            prepare(Waddeln.prepareNextRound(s, random))
        }
    }

    /** The dealer has shuffled; the table waits for the Abheber. */
    private fun prepare(round: PendingRound) {
        pendingRound = round
        state = round.preview
        lastCut = null
        lastAction = null
    }

    /** Cuts the pending [round] after [cut] cards and deals; the bots wait until everybody has seen the cut. */
    private fun cut(round: PendingRound, cut: Int) {
        val reveal = round.reveal(cut)
        state = round.deal(cut)
        pendingRound = null
        lastCut = RoomCut(round.abheber, cut, reveal)
        changed(CutAnimation.durationMs(reveal.kritten.size, reveal.geberKritten.size))
    }

    /** After every change: maybe advance, inform everybody, let the bots continue. */
    private fun changed(botPauseMs: Long = 0) {
        val anyConnected = members.any { it.client != null }
        abandonedSince = if (anyConnected) null else abandonedSince ?: clock()
        botAnswersQuestion()
        advanceIfReady()
        scheduleBots(botPauseMs)
        broadcast()
    }

    private fun scheduleBots(pauseMs: Long) {
        botJob?.cancel()
        botJob = null
        val s = state ?: return stopTimer()
        val humans = humanSeats()
        // Nobody watching: the game waits for the players to come back.
        if (humans.isEmpty()) return stopTimer()
        pendingRound?.let { round ->
            if (round.abheber in humans) return startTimer(s, timing.cutTimeoutMs)
            stopTimer()
            botJob = scope.launch {
                delay(pauseMs + timing.botDelayMs)
                botJob = null
                cut(pendingRound ?: return@launch, Deal.randomCut(random))
            }
            return
        }
        if (s.playerToMove == null) return stopTimer()
        // An open "Schadder?" or Schlagwechsel is answered first.
        val answerer = Waddeln.schadetAnswerer(s) ?: Waddeln.schlagwechselAnswerer(s)
        if (answerer != null) {
            if (answerer in humans) return startTimer(s)
            stopTimer()
            botJob = scope.launch {
                delay(pauseMs + timing.botDelayMs)
                botJob = null
                val current = state ?: return@launch
                botAnswers(current, answerer)
            }
            return
        }
        if (nextBotActor(s, humans) == null) return startTimer(s)
        stopTimer()
        botJob = scope.launch {
            delay(pauseMs + timing.botDelayMs)
            botJob = null
            val current = state ?: return@launch
            val actor = nextBotActor(current, humanSeats()) ?: return@launch
            botAct(current, actor)
        }
    }

    /** A human has to decide: after the timeout the bot decides for them (same timer while [s] is unchanged). */
    private fun startTimer(s: GameState, timeoutMs: Long? = timing.turnTimeoutMs) {
        val timeout = timeoutMs ?: return stopTimer()
        if (timerState === s && timeoutJob?.isActive == true) return
        stopTimer()
        timerState = s
        turnDeadline = clock() + timeout
        timeoutJob = scope.launch {
            delay(timeout)
            val current = state ?: return@launch
            if (current !== s) return@launch
            pendingRound?.let { round ->
                timeoutJob = null
                log.info("Room {}: time is up for {}, the bot cuts", code, seatName(round.abheber))
                return@launch cut(round, Deal.randomCut(random))
            }
            (Waddeln.schadetAnswerer(current) ?: Waddeln.schlagwechselAnswerer(current))?.let { seat ->
                timeoutJob = null
                log.info("Room {}: time is up for {}, the bot answers", code, seatName(seat))
                return@launch botAnswers(current, seat)
            }
            // Ignoring the humans gives the seat that is to move or to answer the Schub.
            val actor = nextBotActor(current, humanSeats = emptySet()) ?: return@launch
            timeoutJob = null
            log.info("Room {}: time is up for {}, the bot decides", code, seatName(actor))
            val legal = Schadet.allowedActions(current, actor, Waddeln.legalActions(current, actor))
            val action = agents[actor].chooseAction(current.observationFor(actor), legal)
            apply(actor, action)
        }
    }

    /** The bot answers the open "Schadder?" or Schlagwechsel of [seat], if it is still open. */
    private fun botAnswers(s: GameState, seat: Int) {
        when (seat) {
            Waddeln.schadetAnswerer(s) -> botAnswersSchadet(s, seat)
            Waddeln.schlagwechselAnswerer(s) -> botAnswersSchlagwechsel(s, seat)
        }
    }

    private fun stopTimer() {
        timeoutJob?.cancel()
        timeoutJob = null
        timerState = null
        turnDeadline = null
    }

    private fun seatName(seat: Int) = seats[seat]?.let { "'${it.name}'" } ?: "bot '${botNames[seat]}'"

    private fun seatList() = seats.indices.joinToString { seat ->
        seats[seat]?.let { m -> "'${m.name}'" + if (m.client == null) " (offline)" else "" } ?: "bot"
    }

    private fun logMatchStart() {
        matchStartedAt = clock()
        log.info("Room {}: match started, seats [{}], bots {}", code, seatList(), strength)
    }

    private fun logRoundEnd(s: GameState) {
        val result = s.result ?: return
        log.info(
            "Room {}: round {} won by team {} ({} points, {}), score {}:{}",
            code, s.roundNumber, result.winningTeam, result.points, result.reason, s.scores[0], s.scores[1],
        )
        if (s.phase == Phase.MATCH_OVER) {
            val minutes = (clock() - matchStartedAt) / 60_000
            log.info(
                "Room {}: match over after {} rounds and {} min, team {} wins {}:{}, seats [{}]",
                code, s.roundNumber, minutes, result.winningTeam, s.scores[0], s.scores[1], seatList(),
            )
        }
    }

    private fun broadcast() {
        val info = info()
        seats.forEachIndexed { seat, member ->
            member?.client?.send(ServerMessage.Table(info, seat, gameView(seat)))
        }
    }

    private fun info() = RoomInfo(
        code = code,
        seats = seats.mapIndexed { seat, m ->
            val botName = botNames[seat]
            if (m == null) {
                SeatInfo(botName = botName)
            } else {
                SeatInfo(m.name, connected = m.client != null, host = m.playerId == hostId, botName = botName)
            }
        },
        botStrength = strength,
        started = started,
    )

    private fun gameView(seat: Int): GameView? {
        val s = state ?: return null
        return GameView(
            observation = s.observationFor(seat),
            legalActions = Waddeln.legalActions(s, seat),
            lastAction = lastAction,
            ready = ready.toList(),
            timeLeftMs = turnDeadline?.let { (it - clock()).coerceAtLeast(0) },
            canAskPartner = canAskPartner(s, seat),
            partnerQuestion = question,
            partnerHint = currentHint(s),
            canAskSchadet = Waddeln.canAskSchadet(s, seat),
            canProposeSchlagwechsel = Waddeln.canProposeSchlagwechsel(s, seat),
            schadetQuestion = s.schadet?.asker,
            schadetAnswer = s.schadet?.let { talk -> talk.answer?.let { SeatSchadet(partnerOf(talk.asker), it) } },
            canShowLastTrick = Waddeln.canShowLastTrick(s, seat),
            lastTrickShownBy = lastTrickShownBy?.takeIf { Waddeln.canShowLastTrick(s, it) },
            cut = cutView(s, seat),
        )
    }

    private fun cutView(s: GameState, seat: Int): CutView? {
        pendingRound?.let { return CutView(it.abheber) }
        val c = lastCut?.takeIf { s.phase != Phase.ROUND_OVER && s.phase != Phase.MATCH_OVER } ?: return null
        return CutView(
            abheber = c.abheber,
            cut = c.cut,
            krittenCount = c.reveal.kritten.size,
            kritten = if (seat == c.abheber) c.reveal.kritten else emptyList(),
            bottomCard = c.reveal.bottomCard,
            geberKrittenCount = c.reveal.geberKritten.size,
            geberKritten = if (seat == s.dealer) c.reveal.geberKritten else emptyList(),
            geberCard = c.reveal.geberCard.takeIf { seat == s.dealer },
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(Room::class.java)

        /** Franconian first names; every bot of a room gets a different one, the host may rename them. */
        val BOT_NAMES = listOf(
            "Schorsch", "Kalle", "Gerdi", "Gustl", "Ernst", "Eddi", "Paul",
            "Werner", "Gusti", "Heidi", "Lisbeth", "Wanda", "Marga", "Resi", "Hilde",
        )
    }
}
