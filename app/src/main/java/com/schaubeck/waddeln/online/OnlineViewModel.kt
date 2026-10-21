package com.schaubeck.waddeln.online

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.schaubeck.waddeln.game.ANSWER_DELAY_MS
import com.schaubeck.waddeln.game.CutUi
import com.schaubeck.waddeln.game.GameSpeed
import com.schaubeck.waddeln.game.SchubNotice
import com.schaubeck.waddeln.game.SettingsStore
import com.schaubeck.waddeln.game.Speech
import com.schaubeck.waddeln.game.StatsMode
import com.schaubeck.waddeln.game.StatsStore
import com.schaubeck.waddeln.game.StatsTracker
import com.schaubeck.waddeln.game.TableUi
import com.schaubeck.waddeln.game.isSpoken
import com.schaubeck.waddeln.protocol.BotStrength
import com.schaubeck.waddeln.protocol.ClientMessage
import com.schaubeck.waddeln.protocol.CutAnimation
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.ui.Sayings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class ConnectionState { OFF, CONNECTING, CONNECTED, LOST }

data class OnlineUi(
    val connection: ConnectionState = ConnectionState.OFF,
    /** True once the server answered the hello (we know whether we are in a room). */
    val greeted: Boolean = false,
    /** The room we sit in, or null. */
    val table: ServerMessage.Table? = null,
    /** The running game as seen from our seat, or null before the start. */
    val game: TableUi? = null,
    /** Last error of the server or the connection, shown briefly. */
    val error: String? = null,
)

/** Online play: talks to the server and turns its messages into what the screens show. */
class OnlineViewModel(application: Application) : AndroidViewModel(application) {
    private val deviceId = SettingsStore(application).deviceId()
    private val client = OnlineClient(viewModelScope, ::onConnected, ::onMessage)
    private val statsStore = StatsStore(application)
    private val statsTracker = StatsTracker { statsStore.add(StatsMode.ONLINE, it) }
    private val _ui = MutableStateFlow(OnlineUi())
    val ui: StateFlow<OnlineUi> = _ui.asStateFlow()

    private var notice: SchubNotice? = null
    private var speech = emptyList<Speech>()

    /** The variant picked for the partner's answer during the open Schub (stays the same while shown). */
    private var hintText: String? = null

    /** The variant picked for the partner's answer to our "Schadder?". */
    private var schadetText: String? = null

    /** Local time at which the server decides for us (from [com.schaubeck.waddeln.protocol.GameView.timeLeftMs]). */
    private var deadline: Long? = null
    private var noticeJob: Job? = null

    /** The cut being shown after the server dealt; the table appears once it has been seen. */
    private var cutShown: CutUi? = null
    private var cutJob: Job? = null

    /** We already cut at random for the round that waits for us (see [selfCut]). */
    private var autoCutSent = false

    /** As Abheber we choose where to cut; otherwise the app cuts at random for us. */
    var selfCut = true
        set(value) {
            if (field == value) return
            field = value
            _ui.value.table?.let { autoCut(it) }
            refreshGame()
        }
    private var errorJob: Job? = null

    /** Connects (or reconnects) with the current nickname; the server seats us again if we were in a room. */
    fun connect(serverUrl: String, name: String) {
        if (_ui.value.connection == ConnectionState.OFF) update { it.copy(connection = ConnectionState.CONNECTING) }
        client.start(serverUrl.trim(), ClientMessage.Hello(deviceId, name.trim()))
    }

    /** Closes the connection unless we sit in a room (then the game goes on in the background). */
    fun disconnectIfIdle() {
        if (_ui.value.table != null) return
        client.stop()
        _ui.value = OnlineUi()
    }

    fun createRoom() = send(ClientMessage.CreateRoom)
    fun joinRoom(code: String) = send(ClientMessage.JoinRoom(code.trim().uppercase()))
    fun takeSeat(seat: Int) = send(ClientMessage.TakeSeat(seat))
    fun setBotStrength(strength: BotStrength) = send(ClientMessage.SetBotStrength(strength))
    fun setBotName(seat: Int, name: String) = send(ClientMessage.SetBotName(seat, name.trim()))
    fun start() = send(ClientMessage.StartMatch)
    fun act(action: Action) = send(ClientMessage.Act(action))
    fun askPartner() = send(ClientMessage.AskPartner)
    fun answerPartner(hint: PartnerHint) = send(ClientMessage.AnswerPartner(hint))
    fun proposeSchlagwechsel() = send(ClientMessage.ProposeSchlagwechsel)
    fun answerSchlagwechsel(accept: Boolean) = send(ClientMessage.AnswerSchlagwechsel(accept))
    fun askSchadet() = send(ClientMessage.AskSchadet)
    fun answerSchadet(answer: SchadetAnswer) = send(ClientMessage.AnswerSchadet(answer))
    fun showLastTrick(show: Boolean) = send(ClientMessage.ShowLastTrick(show))
    fun cut(cut: Int) = send(ClientMessage.Cut(cut))

    /** We have seen enough of the cut. */
    fun onCutSeen() {
        cutJob?.cancel()
        finishCut()
    }
    fun ready() = send(ClientMessage.Ready)
    fun leave() = send(ClientMessage.LeaveRoom)

    fun onNoticeConfirmed() {
        notice = null
        refreshGame()
    }

    override fun onCleared() = client.stop()

    private fun send(message: ClientMessage) {
        if (!client.send(message)) showError("Keine Verbindung zum Server.")
    }

    private fun onConnected(connected: Boolean) = update {
        when {
            connected -> it.copy(connection = ConnectionState.CONNECTED)
            it.connection == ConnectionState.CONNECTING -> {
                it.copy(connection = ConnectionState.LOST, error = "Server nicht erreichbar.")
            }
            else -> it.copy(connection = ConnectionState.LOST)
        }
    }

    private fun onMessage(message: ServerMessage) {
        when (message) {
            ServerMessage.NoRoom -> {
                notice = null
                statsTracker.reset()
                update { it.copy(greeted = true, table = null) }
            }

            is ServerMessage.Table -> {
                detectNotice(_ui.value.table, message)
                detectSpeech(_ui.value.table, message)
                detectCut(_ui.value.table, message)
                autoCut(message)
                // The table without cards while the Abheber cuts is no round of its own.
                message.game?.takeIf { it.cut?.cut != null || it.cut == null }?.let { statsTracker.observe(it.observation) }
                deadline = message.game?.timeLeftMs?.let { SystemClock.elapsedRealtime() + it }
                update { it.copy(greeted = true, table = message) }
            }

            is ServerMessage.Error -> showError(message.message)
        }
    }

    /** Someone else answered a Schub with "Bleiben": show it for a few seconds (the game goes on). */
    private fun detectNotice(before: ServerMessage.Table?, now: ServerMessage.Table) {
        val game = now.game ?: return
        val last = game.lastAction ?: return
        val pending = before?.game?.observation?.pendingSchub ?: return
        if (last.action != Action.Bleiben || last.seat == now.yourSeat) return
        notice = SchubNotice(pending.askingPlayer, last.seat, game.observation.gameValue)
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            notice = null
            refreshGame()
        }
    }

    /** Turns announcements, Schub, answers and the talk between partners into speech bubbles. */
    private fun detectSpeech(before: ServerMessage.Table?, now: ServerMessage.Table) {
        val game = now.game
        val hint = game?.partnerHint
        if (hint == null) hintText = null
        val old = before?.game ?: return
        game ?: return
        val obs = game.observation
        val last = game.lastAction
        // Views are also sent for other reasons (connections, table talk like asking the partner);
        // only a new last action means a new action. The same seat never says the same thing twice
        // in a row, since every spoken action is followed by another one or a new round.
        if (last != null && last.action.isSpoken() && last != old.lastAction) {
            Sayings.speech(last.action)?.let { say(last.seat, it) }
        }
        val asking = game.partnerQuestion
        val newQuestion = asking != null && old.partnerQuestion == null
        if (newQuestion) say(asking, Sayings.partnerQuestion())
        if (hint != null && old.partnerHint == null) {
            val text = Sayings.partnerHint(hint.hint, obs.tricksWon[teamOf(hint.seat)])
            hintText = text
            // A bot partner answers at once; the answer still follows the question.
            say(hint.seat, text, delayMs = if (newQuestion) ANSWER_DELAY_MS else 0)
        }
        val swap = obs.schlagwechsel
        val oldSwap = old.observation.schlagwechsel.takeIf { obs.roundNumber == old.observation.roundNumber }
        if (swap != null && oldSwap == null) say((obs.dealer + 1) % PLAYER_COUNT, Sayings.schlagwechselQuestion())
        val accepted = swap?.accepted
        if (accepted != null && oldSwap?.accepted == null) say(obs.dealer, Sayings.schlagwechselAnswer(accepted))
        val schadetAsker = game.schadetQuestion
        if (schadetAsker == null) schadetText = null
        if (schadetAsker != null && old.schadetQuestion == null) say(schadetAsker, Sayings.schadetQuestion())
        val schadet = game.schadetAnswer
        if (schadet != null && old.schadetAnswer == null) {
            val text = Sayings.schadetAnswer(schadet.answer)
            if (schadetAsker == now.yourSeat) schadetText = text
            say(schadet.seat, text)
        }
    }

    /** The Abheber has just cut: show it like the server lets the bots wait. */
    private fun detectCut(before: ServerMessage.Table?, now: ServerMessage.Table) {
        val cut = now.game?.cut ?: return
        val lifted = cut.cut ?: return
        if (before?.game?.cut?.let { it.cut == null } != true) return
        cutShown = CutUi(
            abheber = cut.abheber,
            cut = lifted,
            krittenCount = cut.krittenCount,
            kritten = cut.kritten,
            bottomCard = cut.bottomCard,
        )
        cutJob?.cancel()
        cutJob = viewModelScope.launch {
            delay(CutAnimation.durationMs(cut.krittenCount))
            finishCut()
        }
    }

    /** After the cut the Abheber tells the table how many Kritten they took. */
    private fun finishCut() {
        val shown = cutShown ?: return
        cutShown = null
        if (shown.krittenCount > 0) say(shown.abheber, Sayings.kritten(shown.krittenCount))
        refreshGame()
    }

    /** We are the Abheber but let the app cut at random. */
    private fun autoCut(table: ServerMessage.Table) {
        val waiting = table.game?.cut?.takeIf { it.cut == null && it.abheber == table.yourSeat }
        if (waiting == null) {
            autoCutSent = false
            return
        }
        if (selfCut || autoCutSent) return
        autoCutSent = true
        cut(Deal.randomCut(Random.Default))
    }

    private fun say(seat: Int, text: String, delayMs: Long = 0) {
        speech = (speech + Speech(seat, text, SystemClock.elapsedRealtime() + delayMs)).takeLast(MAX_SPEECH)
    }

    private fun refreshGame() = update { it }

    private fun tableUi(table: ServerMessage.Table, connection: ConnectionState): TableUi? {
        val game = table.game ?: return null
        val me = table.yourSeat
        val obs = game.observation
        val over = obs.phase == Phase.ROUND_OVER || obs.phase == Phase.MATCH_OVER
        val beforeFirstLead = obs.phase == Phase.PLAYING && obs.completedTricks.isEmpty() && obs.currentTrick.isEmpty()
        return TableUi(
            obs = obs,
            actions = if (cutShown != null) emptyList() else game.legalActions,
            names = List(PLAYER_COUNT) { seat -> seatName(table, seat) },
            // The server sets the pace; it matches the slow speed.
            speed = GameSpeed.SLOW,
            notice = notice,
            speech = speech,
            canAskPartner = game.canAskPartner,
            // Only our own partner's answer goes into the answer panel; the opponents' talk is just a bubble.
            partnerHint = hintText.takeIf { game.partnerHint?.let { teamOf(it.seat) == obs.team } == true },
            waitingForPartner = game.partnerQuestion == me && game.partnerHint == null,
            partnerAsksUs = game.partnerQuestion == (me + 2) % PLAYER_COUNT && game.partnerHint == null,
            canAskSchadet = game.canAskSchadet,
            waitingForSchadet = game.schadetQuestion == me && game.schadetAnswer == null && beforeFirstLead,
            schadetAnswer = schadetText.takeIf { game.schadetQuestion == me && beforeFirstLead && obs.playerToMove == me },
            partnerAsksSchadet = game.schadetQuestion == (me + 2) % PLAYER_COUNT && game.schadetAnswer == null && beforeFirstLead,
            canProposeSchlagwechsel = game.canProposeSchlagwechsel,
            waitingForSchlagwechsel = obs.schlagwechselAnswerer.let { it != null && it != me },
            schlagwechselAsksUs = obs.schlagwechselAnswerer == me,
            canShowLastTrick = game.canShowLastTrick,
            lastTrickShownBy = game.lastTrickShownBy,
            absent = table.room.seats.indices.filter { table.room.seats[it].isHuman && !table.room.seats[it].connected }.toSet(),
            bots = table.room.seats.indices.filter { !table.room.seats[it].isHuman }.toSet(),
            waitingForOthers = over && game.ready[me],
            deadline = deadline,
            cut = cutShown ?: game.cut?.takeIf { it.cut == null }?.let { CutUi(it.abheber, choosing = it.abheber == me && selfCut) },
            connectionProblem = if (connection == ConnectionState.LOST) "Verbindung unterbrochen – verbinde neu …" else null,
        )
    }

    private fun showError(text: String) {
        update { it.copy(error = text) }
        errorJob?.cancel()
        errorJob = viewModelScope.launch {
            delay(ERROR_MS)
            update { it.copy(error = null) }
        }
    }

    /** Applies [change] and derives the table view from the room and connection state. */
    private inline fun update(change: (OnlineUi) -> OnlineUi) {
        val next = change(_ui.value)
        _ui.value = next.copy(game = next.table?.let { tableUi(it, next.connection) })
    }

    companion object {
        const val NOTICE_MS = 4000L
        const val ERROR_MS = 4000L
        const val MAX_SPEECH = 8

        /**
         * Display name of a seat: the nickname, else the bot's name, else (older server)
         * the bot named by its position relative to us.
         */
        fun seatName(table: ServerMessage.Table, seat: Int): String {
            val info = table.room.seats[seat]
            info.name?.let { return it }
            info.botName?.let { return it }
            return when ((seat - table.yourSeat + PLAYER_COUNT) % PLAYER_COUNT) {
                1 -> "KI links"
                2 -> "KI Partner"
                3 -> "KI rechts"
                else -> "KI"
            }
        }
    }
}
