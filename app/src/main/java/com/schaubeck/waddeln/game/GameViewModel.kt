package com.schaubeck.waddeln.game

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.schaubeck.waddeln.ai.BotFactory
import com.schaubeck.waddeln.protocol.CutAnimation
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.CutReveal
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.partnerOf
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.agent.TrickAdvice
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
import kotlin.random.Random

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
    private val sayings = Sayings(application.resources)
    private var settings = store.load()
    private val game = OfflineGame().apply { adviceEnabled = settings.trickAdvice }
    private val statsStore = StatsStore(application)
    private val statsTracker = StatsTracker { statsStore.add(StatsMode.OFFLINE, it) }
    private var notice: SchubNotice? = null
    private var speech = emptyList<Speech>()

    /** The partner's answer during the current Schub and the game value it was given at. */
    private var givenHint: Pair<Int, String>? = null

    /** The variant picked for the partner bot's answer to the human's "Schadder?". */
    private var schadetText: String? = null

    /** The variant picked for the partner bot's answer to the human's "Was soll ich machen?". */
    private var adviceText: String? = null
    private var matchStarted = false

    /** The human looks at the last completed trick (until they act). */
    private var showingLastTrick = false
    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<GameUiState> = _ui.asStateFlow()

    private var botJob: Job? = null

    /** The cut being shown; the game goes on once it has been seen ([cutJob] or [onCutSeen]). */
    private var cutShown: CutUi? = null
    private var cutJob: Job? = null

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

    /** As Abheber: cut after [cut] cards; the round is dealt and the cut shown. */
    fun onCut(cut: Int) {
        if (!game.humanMustCut() || cutShown != null || cut !in Deal.CUT_RANGE) return
        showCut(OfflineGame.HUMAN, cut, game.cut(cut))
    }

    /** The human has seen enough of the cut. */
    fun onCutSeen() {
        if (cutShown == null) return
        cutJob?.cancel()
        finishCut()
    }

    fun onHumanAction(action: Action) {
        if (cutShown != null || notice != null || action !in game.humanActions()) return
        val tricksBefore = game.state.completedTricks.size
        showingLastTrick = false
        game.applyHuman(action)
        say(OfflineGame.HUMAN, action)
        publish()
        // Give a trick finished by the human time to be shown and collected before bots go on.
        runBots(initialPauseMs = if (game.state.completedTricks.size > tricksBefore) settings.speed.trickPauseMs else 0)
    }

    /** Shows or hides the last completed trick (only while the human is to play a card). */
    fun onShowLastTrick(show: Boolean) {
        showingLastTrick = show && Waddeln.canShowLastTrick(game.state, OfflineGame.HUMAN)
        publish()
    }

    /** Before announcing the Schlag: propose a Schlagwechsel; the dealer bot answers in its next step. */
    fun onProposeSchlagwechsel() {
        if (!game.humanCanProposeSchlagwechsel()) return
        game.proposeSchlagwechsel()
        say(OfflineGame.HUMAN, sayings.schlagwechselQuestion())
        publish()
        runBots()
    }

    /** Answer as dealer to a bot's Schlagwechsel. */
    fun onAnswerSchlagwechsel(accept: Boolean) {
        if (!game.humanMustAnswerSchlagwechsel()) return
        game.answerSchlagwechsel(accept)
        say(OfflineGame.HUMAN, sayings.schlagwechselAnswer(accept))
        publish()
        runBots()
    }

    /** Before leading the first trick: ask the partner "Schadder?"; the partner bot answers in its next step. */
    fun onAskSchadet() {
        if (!game.humanCanAskSchadet()) return
        game.askSchadet()
        say(OfflineGame.HUMAN, sayings.schadetQuestion())
        publish()
        runBots()
    }

    /** Answer to the partner bot's "Schadder?". */
    fun onAnswerSchadet(answer: SchadetAnswer) {
        if (!game.humanMustAnswerSchadet()) return
        game.answerSchadet(answer)
        say(OfflineGame.HUMAN, sayings.schadetAnswer(answer))
        publish()
        runBots()
    }

    /** Second in a trick: ask the partner "Was soll ich machen?"; the partner bot answers in its next step. */
    fun onAskAdvice() {
        if (!game.humanCanAskAdvice()) return
        game.askAdvice()
        say(OfflineGame.HUMAN, sayings.adviceQuestion())
        publish()
        runBots()
    }

    /** Answer to the partner bot's "Was soll ich machen?". */
    fun onAnswerAdvice(answer: TrickAdvice) {
        if (!game.humanMustAnswerAdvice()) return
        game.answerAdvice(answer)
        say(OfflineGame.HUMAN, sayings.adviceAnswer(answer))
        publish()
        runBots()
    }

    /** During a Schub against us: the partner tells roughly how good their cards are. */
    fun onAskPartner() {
        if (partnerHint() != null) return
        val hint = game.askPartner() ?: return
        val s = game.state
        val text = sayings.partnerHint(hint, s.tricksWon[teamOf(OfflineGame.HUMAN)])
        givenHint = s.gameValue to text
        say(OfflineGame.HUMAN, sayings.partnerQuestion())
        say(OfflineGame.PARTNER, text, delayMs = ANSWER_DELAY_MS)
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
        game.adviceEnabled = newSettings.trickAdvice
        botFactory?.takeIf { difficultyChanged }?.let { game.bots = it.createBots(newSettings.difficulty) }
        publish()
        // The human may have switched off cutting themselves while it is their turn to cut.
        runBots()
    }

    /** "Nächstes Spiel" or, after the match, "Neues Match". */
    fun onContinue() {
        if (game.state.phase == Phase.MATCH_OVER) onNewMatch() else onNextRound()
    }

    fun onNextRound() {
        resetCut()
        speech = emptyList()
        givenHint = null
        schadetText = null
        adviceText = null
        game.nextRound()
        publish()
        runBots()
    }

    fun onNewMatch() {
        botJob?.cancel()
        resetCut()
        notice = null
        speech = emptyList()
        givenHint = null
        schadetText = null
        adviceText = null
        game.newMatch()
        publish()
        runBots()
    }

    /** Lets bots act one after another with pauses until the human is needed. */
    private fun runBots(initialPauseMs: Long = 0) {
        if (paused || botFactory == null || notice != null || cutShown != null || botJob?.isActive == true) return
        botJob = viewModelScope.launch {
            if (initialPauseMs > 0) delay(initialPauseMs)
            // Something said aloud stays readable before the next bot acts.
            var speechPause = 0L
            while ((game.botToAct() != null || cutsForHuman()) && notice == null) {
                delay(settings.speed.botDelayMs + speechPause)
                if (cutsForHuman()) {
                    val cut = Deal.randomCut(Random.Default)
                    showCut(OfflineGame.HUMAN, cut, game.cut(cut))
                    break
                }
                val bot = game.botToAct() ?: break // the human acted (e.g. Schub) in the meantime
                val tricksBefore = game.state.completedTricks.size
                val schubBefore = game.state.pendingSchub
                val spoken = when (val step = game.stepBot()) {
                    is BotStep.Cut -> {
                        // The game goes on once the cut has been shown.
                        showCut(bot, step.cut, step.reveal)
                        break
                    }
                    is BotStep.Acted -> {
                        say(bot, step.action)
                        step.action.isSpoken()
                    }
                    is BotStep.AskedSchadet -> {
                        say(bot, sayings.schadetQuestion())
                        true
                    }
                    is BotStep.AnsweredSchadet -> {
                        val text = sayings.schadetAnswer(step.answer)
                        if (game.schadet?.asker == OfflineGame.HUMAN) schadetText = text
                        say(bot, text)
                        true
                    }
                    is BotStep.AskedAdvice -> {
                        say(bot, sayings.adviceQuestion())
                        true
                    }
                    is BotStep.AnsweredAdvice -> {
                        val text = sayings.adviceAnswer(step.answer)
                        if (partnerOf(bot) == OfflineGame.HUMAN) adviceText = text
                        say(bot, text)
                        true
                    }
                    is BotStep.ProposedSchlagwechsel -> {
                        say(bot, sayings.schlagwechselQuestion())
                        true
                    }
                    is BotStep.AnsweredSchlagwechsel -> {
                        say(bot, sayings.schlagwechselAnswer(step.accept))
                        true
                    }
                    is BotStep.AskedPartner -> {
                        // The opponents talk aloud: question by the bot, answer by its partner right after.
                        say(bot, sayings.partnerQuestion())
                        val partner = partnerOf(bot)
                        say(partner, sayings.partnerHint(step.hint, game.state.tricksWon[teamOf(partner)]), delayMs = ANSWER_DELAY_MS)
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

    /** The human is the Abheber but lets the app cut for them. */
    private fun cutsForHuman(): Boolean = game.humanMustCut() && !settings.selfCut

    /** Shows how [abheber] cut; the Kritten only if the human took them. */
    private fun showCut(abheber: Int, cut: Int, reveal: CutReveal) {
        cutShown = CutUi(
            abheber = abheber,
            cut = cut,
            krittenCount = reveal.kritten.size,
            kritten = reveal.kritten.takeIf { abheber == OfflineGame.HUMAN }.orEmpty(),
            bottomCard = reveal.bottomCard,
            geberLooks = reveal.kritten.isNotEmpty(),
            geberKrittenCount = reveal.geberKritten.size,
            geberKritten = reveal.geberKritten.takeIf { game.state.dealer == OfflineGame.HUMAN }.orEmpty(),
            geberCard = reveal.geberCard.takeIf { game.state.dealer == OfflineGame.HUMAN },
        )
        publish()
        cutJob?.cancel()
        // Who looked under the rest as dealer closes the cut with "Weiter"; the game waits until then.
        if (game.state.dealer == OfflineGame.HUMAN && reveal.kritten.isNotEmpty()) return
        cutJob = viewModelScope.launch {
            delay(CutAnimation.durationMs(reveal.kritten.size, reveal.geberKritten.size))
            finishCut()
        }
    }

    /** After the cut: Abheber and dealer tell the table how many Kritten they took, then the game goes on. */
    private fun finishCut() {
        val shown = cutShown ?: return
        cutShown = null
        if (shown.krittenCount > 0) say(shown.abheber, sayings.kritten(shown.krittenCount))
        if (shown.geberKrittenCount > 0) say(shown.geber, sayings.kritten(shown.geberKrittenCount), delayMs = ANSWER_DELAY_MS)
        publish()
        runBots()
    }

    private fun resetCut() {
        cutJob?.cancel()
        cutShown = null
    }

    override fun onCleared() {
        botFactory?.close()
    }

    private fun say(seat: Int, action: Action) {
        // A Schub does not change the game value, so the state after it still has the value it raises.
        sayings.speech(action, game.state.gameValue)?.let { say(seat, it) }
    }

    private fun say(seat: Int, text: String, delayMs: Long = 0) {
        speech = (speech + Speech(seat, text, SystemClock.elapsedRealtime() + delayMs)).takeLast(MAX_SPEECH)
    }

    /** The partner's answer if it belongs to the Schub that is open right now. */
    private fun partnerHint(): String? {
        val s = game.state
        val (value, text) = givenHint ?: return null
        return text.takeIf { s.pendingSchub != null && s.gameValue == value }
    }

    private fun publish() {
        // The preview while the Abheber cuts is no round of its own.
        if (game.abheberToCut == null) statsTracker.observe(game.state.observationFor(OfflineGame.HUMAN))
        _ui.value = snapshot()
    }

    private fun snapshot() = GameUiState(
        table = TableUi(
            obs = game.state.observationFor(OfflineGame.HUMAN),
            actions = if (notice != null || cutShown != null) emptyList() else game.humanActions(),
            names = List(PLAYER_COUNT) { settings.names[it].ifBlank { store.defaultNames[it] } },
            speed = settings.speed,
            notice = notice,
            speech = speech,
            canAskPartner = notice == null && partnerHint() == null && game.canAskPartner(),
            partnerHint = partnerHint(),
            canAskSchadet = notice == null && game.humanCanAskSchadet(),
            waitingForSchadet = game.schadet?.let { it.asker == OfflineGame.HUMAN && it.answer == null } == true,
            schadetAnswer = schadetText?.takeIf { Waddeln.leadsFirstTrick(game.state, OfflineGame.HUMAN) },
            partnerAsksSchadet = game.humanMustAnswerSchadet(),
            canAskAdvice = notice == null && game.humanCanAskAdvice(),
            waitingForAdvice = Waddeln.adviceAnswerer(game.state) == OfflineGame.PARTNER,
            adviceAnswer = adviceText?.takeIf { Waddeln.adviceFor(game.state, OfflineGame.HUMAN) != null },
            partnerAsksAdvice = game.humanMustAnswerAdvice(),
            canProposeSchlagwechsel = notice == null && game.humanCanProposeSchlagwechsel(),
            waitingForSchlagwechsel = Waddeln.schlagwechselAnswerer(game.state).let { it != null && it != OfflineGame.HUMAN },
            schlagwechselAsksUs = game.humanMustAnswerSchlagwechsel(),
            canShowLastTrick = notice == null && Waddeln.canShowLastTrick(game.state, OfflineGame.HUMAN),
            lastTrickShownBy = OfflineGame.HUMAN.takeIf { showingLastTrick && Waddeln.canShowLastTrick(game.state, it) },
            cut = cutShown ?: game.abheberToCut?.let { CutUi(it, choosing = it == OfflineGame.HUMAN && settings.selfCut) },
        ),
        settings = settings,
        matchInProgress = matchStarted && game.state.phase != Phase.MATCH_OVER,
    )

    private companion object {
        const val MAX_SPEECH = 8
    }
}
