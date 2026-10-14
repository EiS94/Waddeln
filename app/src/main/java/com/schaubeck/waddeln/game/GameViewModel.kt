package com.schaubeck.waddeln.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.schaubeck.waddeln.ai.BotFactory
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.observationFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Snapshot rendered by the UI. A [SchubNotice] that bots answered while the human only watched
 * must be confirmed before the offline game continues.
 */
data class GameUiState(
    val table: TableUi,
    val settings: Settings,
    /** True once anything happened in the current match (offer "Weiterspielen" in the menu). */
    val matchInProgress: Boolean,
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private var settings = store.load()
    private val game = OfflineGame()
    private val statsStore = StatsStore(application)
    private val statsTracker = StatsTracker { statsStore.add(StatsMode.OFFLINE, it) }
    private var notice: SchubNotice? = null
    private var matchStarted = false
    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<GameUiState> = _ui.asStateFlow()

    private var botJob: Job? = null

    /** Paused while the game screen is not shown (e.g. in the menu). */
    private var paused = true

    /** Loading the network takes a moment, so it happens off the main thread; bots start afterwards. */
    private var botFactory: BotFactory? = null

    init {
        viewModelScope.launch {
            val factory = withContext(Dispatchers.Default) { BotFactory(application) }
            botFactory = factory
            game.bots = factory.createBots(settings.difficulty)
            runBots()
        }
    }

    fun onGameShown() {
        paused = false
        matchStarted = true
        publish()
        runBots()
    }

    fun onGameHidden() {
        paused = true
        botJob?.cancel()
    }

    fun onHumanAction(action: Action) {
        if (notice != null || action !in game.humanActions()) return
        val tricksBefore = game.state.completedTricks.size
        game.applyHuman(action)
        publish()
        // Give a trick finished by the human time to be shown and collected before bots go on.
        runBots(initialPauseMs = if (game.state.completedTricks.size > tricksBefore) settings.speed.trickPauseMs else 0)
    }

    fun onNoticeConfirmed() {
        notice = null
        publish()
        runBots()
    }

    fun onSettingsChanged(newSettings: Settings) {
        val difficultyChanged = newSettings.difficulty != settings.difficulty
        settings = newSettings
        store.save(newSettings)
        botFactory?.takeIf { difficultyChanged }?.let { game.bots = it.createBots(newSettings.difficulty) }
        publish()
    }

    /** "Nächstes Spiel" or, after the match, "Neues Match". */
    fun onContinue() {
        if (game.state.phase == Phase.MATCH_OVER) onNewMatch() else onNextRound()
    }

    fun onNextRound() {
        game.nextRound()
        publish()
        runBots()
    }

    fun onNewMatch() {
        botJob?.cancel()
        notice = null
        game.newMatch()
        publish()
        runBots()
    }

    /** Lets bots act one after another with pauses until the human is needed. */
    private fun runBots(initialPauseMs: Long = 0) {
        if (paused || botFactory == null || notice != null || botJob?.isActive == true) return
        botJob = viewModelScope.launch {
            if (initialPauseMs > 0) delay(initialPauseMs)
            while (game.botToAct() != null && notice == null) {
                delay(settings.speed.botDelayMs)
                val bot = game.botToAct() ?: break // the human acted (e.g. Schub) in the meantime
                val tricksBefore = game.state.completedTricks.size
                val schubBefore = game.state.pendingSchub
                game.stepBot()
                val s = game.state
                if (schubBefore != null && s.pendingSchub == null && s.phase == Phase.PLAYING) {
                    notice = SchubNotice(schubBefore.askingPlayer, bot, s.gameValue)
                }
                publish()
                if (s.completedTricks.size > tricksBefore) delay(settings.speed.trickPauseMs)
            }
        }
    }

    override fun onCleared() {
        botFactory?.close()
    }

    private fun publish() {
        statsTracker.observe(game.state.observationFor(OfflineGame.HUMAN))
        _ui.value = snapshot()
    }

    private fun snapshot() = GameUiState(
        table = TableUi(
            obs = game.state.observationFor(OfflineGame.HUMAN),
            actions = if (notice != null) emptyList() else game.humanActions(),
            names = List(PLAYER_COUNT) { settings.name(it) },
            speed = settings.speed,
            notice = notice,
        ),
        settings = settings,
        matchInProgress = matchStarted && game.state.phase != Phase.MATCH_OVER,
    )
}
