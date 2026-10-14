package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.BotStrength
import com.schaubeck.waddeln.protocol.GameView
import com.schaubeck.waddeln.protocol.RoomInfo
import com.schaubeck.waddeln.protocol.SeatAction
import com.schaubeck.waddeln.protocol.SeatInfo
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.observationFor
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
    /** A human who has to decide gets this long; then the bot decides for them. Null = no limit. */
    val turnTimeoutMs: Long? = 60_000,
)

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
        state = Waddeln.newMatch(random)
        logMatchStart()
        changed()
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

    /** Confirms the end of a round or match; the next one starts once all connected humans confirmed. */
    fun ready(playerId: String): String? {
        val seat = seatOf(playerId) ?: return null
        val phase = state?.phase ?: return null
        if (phase != Phase.ROUND_OVER && phase != Phase.MATCH_OVER) return null
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
        if (before.result == null) after.result?.let { logRoundEnd(after) }
        val pause = when {
            after.completedTricks.size > before.completedTricks.size -> timing.trickPauseMs
            action == Action.Bleiben -> timing.schubPauseMs
            else -> 0
        }
        changed(pause)
    }

    /** Starts the next round or match if everyone connected is ready. */
    private fun advanceIfReady() {
        val s = state ?: return
        if (s.phase != Phase.ROUND_OVER && s.phase != Phase.MATCH_OVER) return
        val waiting = humanSeats()
        if (waiting.isEmpty() || waiting.any { !ready[it] }) return
        ready.fill(false)
        lastAction = null
        if (s.phase == Phase.MATCH_OVER) {
            state = Waddeln.newMatch(random)
            logMatchStart()
        } else {
            state = Waddeln.startNextRound(s, Deal.random(random))
        }
    }

    /** After every change: maybe advance, inform everybody, let the bots continue. */
    private fun changed(botPauseMs: Long = 0) {
        val anyConnected = members.any { it.client != null }
        abandonedSince = if (anyConnected) null else abandonedSince ?: clock()
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
        if (humans.isEmpty() || s.playerToMove == null) return stopTimer()
        if (nextBotActor(s, humans) == null) return startTimer(s)
        stopTimer()
        botJob = scope.launch {
            delay(pauseMs + timing.botDelayMs)
            botJob = null
            val current = state ?: return@launch
            val actor = nextBotActor(current, humanSeats()) ?: return@launch
            val action = agents[actor].chooseAction(current.observationFor(actor), Waddeln.legalActions(current, actor))
            apply(actor, action)
        }
    }

    /** A human has to decide: after the timeout the bot decides for them (same timer while [s] is unchanged). */
    private fun startTimer(s: GameState) {
        val timeout = timing.turnTimeoutMs ?: return stopTimer()
        if (timerState === s && timeoutJob?.isActive == true) return
        stopTimer()
        timerState = s
        turnDeadline = clock() + timeout
        timeoutJob = scope.launch {
            delay(timeout)
            val current = state ?: return@launch
            if (current !== s) return@launch
            // Ignoring the humans gives the seat that is to move or to answer the Schub.
            val actor = nextBotActor(current, humanSeats = emptySet()) ?: return@launch
            timeoutJob = null
            log.info("Room {}: time is up for {}, the bot decides", code, seatName(actor))
            val action = agents[actor].chooseAction(current.observationFor(actor), Waddeln.legalActions(current, actor))
            apply(actor, action)
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
