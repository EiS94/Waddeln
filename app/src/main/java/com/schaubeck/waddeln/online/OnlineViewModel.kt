package com.schaubeck.waddeln.online

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.ui.Texts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /** Local time at which the server decides for us (from [com.schaubeck.waddeln.protocol.GameView.timeLeftMs]). */
    private var deadline: Long? = null
    private var noticeJob: Job? = null
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
                message.game?.let { statsTracker.observe(it.observation) }
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

    /** Turns announcements, Schub, answers and partner hints into speech bubbles. */
    private fun detectSpeech(before: ServerMessage.Table?, now: ServerMessage.Table) {
        val old = before?.game ?: return
        val game = now.game ?: return
        val obs = game.observation
        val last = game.lastAction
        // Views are also sent for other reasons (e.g. connections); only a new state means a new action.
        if (last != null && last.action.isSpoken() && old.observation != obs) {
            Texts.speech(last.action)?.let { say(last.seat, it) }
        }
        val hint = game.partnerHint
        if (hint != null && old.partnerHint == null) say(hint.seat, Texts.partnerHint(hint.hint, obs.tricksWon[obs.team]))
    }

    private fun say(seat: Int, text: String) {
        speech = (speech + Speech(seat, text, SystemClock.elapsedRealtime())).takeLast(MAX_SPEECH)
    }

    private fun refreshGame() = update { it }

    private fun tableUi(table: ServerMessage.Table, connection: ConnectionState): TableUi? {
        val game = table.game ?: return null
        val me = table.yourSeat
        val obs = game.observation
        val over = obs.phase == Phase.ROUND_OVER || obs.phase == Phase.MATCH_OVER
        return TableUi(
            obs = obs,
            actions = game.legalActions,
            names = List(PLAYER_COUNT) { seat -> seatName(table, seat) },
            // The server sets the pace; it matches the slow speed.
            speed = GameSpeed.SLOW,
            notice = notice,
            speech = speech,
            canAskPartner = game.canAskPartner,
            partnerHint = game.partnerHint?.let { Texts.partnerHint(it.hint, obs.tricksWon[obs.team]) },
            absent = table.room.seats.indices.filter { table.room.seats[it].isHuman && !table.room.seats[it].connected }.toSet(),
            bots = table.room.seats.indices.filter { !table.room.seats[it].isHuman }.toSet(),
            waitingForOthers = over && game.ready[me],
            deadline = deadline,
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
