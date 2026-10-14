package com.schaubeck.waddeln.game

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.schaubeck.waddeln.ai.BotFactory
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.agent.Schadet
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.ui.Sayings
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
    private var speech = emptyList<Speech>()

    /** The partner's answer during the current Schub and the game value it was given at. */
    private var givenHint: Pair<Int, String>? = null

    /** The variant picked for the partner bot's answer to the human's "Schadder?". */
    private var schadetText: String? = null
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
        say(OfflineGame.HUMAN, action)
        publish()
        // Give a trick finished by the human time to be shown and collected before bots go on.
        runBots(initialPauseMs = if (game.state.completedTricks.size > tricksBefore) settings.speed.trickPauseMs else 0)
    }

    /** Before leading the Haubi: ask the partner "Schadder?"; the partner bot answers in its next step. */
    fun onAskSchadet() {
        if (!game.humanCanAskSchadet()) return
        game.askSchadet()
        publish()
        runBots()
    }

    /** Answer to the partner bot's "Schadder?". */
    fun onAnswerSchadet(answer: SchadetAnswer) {
        if (!game.humanMustAnswerSchadet()) return
        game.answerSchadet(answer)
        say(OfflineGame.HUMAN, Sayings.schadetAnswer(answer))
        publish()
        runBots()
    }

    /** During a Schub against us: the partner tells roughly how good their cards are. */
    fun onAskPartner() {
        if (partnerHint() != null) return
        val hint = game.partnerHint() ?: return
        val s = game.state
        val text = Sayings.partnerHint(hint, s.tricksWon[teamOf(OfflineGame.HUMAN)])
        givenHint = s.gameValue to text
        say(OfflineGame.PARTNER, text)
        publish()
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
        speech = emptyList()
        givenHint = null
        schadetText = null
        game.nextRound()
        publish()
        runBots()
    }

    fun onNewMatch() {
        botJob?.cancel()
        notice = null
        speech = emptyList()
        givenHint = null
        schadetText = null
        game.newMatch()
        publish()
        runBots()
    }

    /** Lets bots act one after another with pauses until the human is needed. */
    private fun runBots(initialPauseMs: Long = 0) {
        if (paused || botFactory == null || notice != null || botJob?.isActive == true) return
        botJob = viewModelScope.launch {
            if (initialPauseMs > 0) delay(initialPauseMs)
            // Something said aloud stays readable before the next bot acts.
            var speechPause = 0L
            while (game.botToAct() != null && notice == null) {
                delay(settings.speed.botDelayMs + speechPause)
                val bot = game.botToAct() ?: break // the human acted (e.g. Schub) in the meantime
                val tricksBefore = game.state.completedTricks.size
                val schubBefore = game.state.pendingSchub
                val spoken = when (val step = game.stepBot()) {
                    is BotStep.Acted -> {
                        say(bot, step.action)
                        step.action.isSpoken()
                    }
                    is BotStep.AskedSchadet -> {
                        say(bot, Sayings.schadetQuestion())
                        true
                    }
                    is BotStep.AnsweredSchadet -> {
                        val text = Sayings.schadetAnswer(step.answer)
                        if (game.schadet?.asker == OfflineGame.HUMAN) schadetText = text
                        say(bot, text)
                        true
                    }
                }
                speechPause = if (spoken) (settings.speed.speechMs - settings.speed.botDelayMs).coerceAtLeast(0) else 0
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

    private fun say(seat: Int, action: Action) {
        Sayings.speech(action)?.let { say(seat, it) }
    }

    private fun say(seat: Int, text: String) {
        speech = (speech + Speech(seat, text, SystemClock.elapsedRealtime())).takeLast(MAX_SPEECH)
    }

    /** The partner's answer if it belongs to the Schub that is open right now. */
    private fun partnerHint(): String? {
        val s = game.state
        val (value, text) = givenHint ?: return null
        return text.takeIf { s.pendingSchub != null && s.gameValue == value }
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
            speech = speech,
            canAskPartner = notice == null && partnerHint() == null && game.partnerHint() != null,
            partnerHint = partnerHint(),
            canAskSchadet = notice == null && game.humanCanAskSchadet(),
            waitingForSchadet = game.schadet?.let { it.asker == OfflineGame.HUMAN && it.answer == null } == true,
            schadetAnswer = schadetText?.takeIf { Schadet.canAsk(game.state, OfflineGame.HUMAN) },
            partnerAsksSchadet = game.humanMustAnswerSchadet(),
        ),
        settings = settings,
        matchInProgress = matchStarted && game.state.phase != Phase.MATCH_OVER,
    )

    private companion object {
        const val MAX_SPEECH = 8
    }
}
