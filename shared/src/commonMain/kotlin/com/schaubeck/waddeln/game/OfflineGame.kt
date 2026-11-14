package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.CutReveal
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.PendingRound
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.RoundResult
import com.schaubeck.waddeln.rules.SchadetTalk
import com.schaubeck.waddeln.rules.Trick
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.Advice
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.BotTalk
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.PartnerHints
import com.schaubeck.waddeln.rules.agent.Schadet
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.agent.Schlagwechsel
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.TrickAdvice
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.partnerOf
import com.schaubeck.waddeln.rules.teamOf
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.random.Random

/** What a bot did in one step. */
sealed interface BotStep {
    val bot: Int

    /** As Abheber the bot cut after [cut] cards; the round is dealt. */
    data class Cut(override val bot: Int, val cut: Int, val reveal: CutReveal) : BotStep

    data class Acted(override val bot: Int, val action: Action) : BotStep

    /** Before leading the Haubi the bot asks its partner "Schadder?". */
    data class AskedSchadet(override val bot: Int) : BotStep

    data class AnsweredSchadet(override val bot: Int, val answer: SchadetAnswer) : BotStep

    /** Second in a trick the bot asks its partner "Was soll ich machen?". */
    data class AskedAdvice(override val bot: Int) : BotStep

    data class AnsweredAdvice(override val bot: Int, val answer: TrickAdvice) : BotStep

    /** Before announcing the Schlag the bot proposes a Schlagwechsel to the dealer. */
    data class ProposedSchlagwechsel(override val bot: Int) : BotStep

    data class AnsweredSchlagwechsel(override val bot: Int, val accept: Boolean) : BotStep

    /** Before answering a Schub the bot asked its partner, who answered with [hint] at once. */
    data class AskedPartner(override val bot: Int, val hint: PartnerHint) : BotStep
}

/**
 * A bot's step worked out by [OfflineGame.planBot] on the game as it was ([from]); [OfflineGame.commit]
 * applies it unless the game has moved on in the meantime.
 */
class BotMove internal constructor(
    val step: BotStep,
    internal val from: GameState,
    internal val to: GameState,
)

/** A match as it is kept across a restart of the app: the round waiting for the cut, or the dealt one. */
@Serializable
data class SavedMatch(val pending: PendingRound? = null, val state: GameState? = null)

/** Something that happened in the game, shown to the human as a message. */
sealed interface GameEvent {
    data class Acted(val player: Int, val action: Action) : GameEvent
    data class TrickWon(val trick: Trick) : GameEvent
    data class RoundEnded(val result: RoundResult) : GameEvent
}

/**
 * Offline match: one human at [HUMAN] against/with three bots. Holds the authoritative state;
 * timing (bot delays) is left to the caller so this class stays plain and testable.
 */
class OfflineGame(
    private val random: Random = Random.Default,
    /** The bot players per seat; may be replaced, e.g. once the ML model has been loaded. */
    @Volatile var bots: List<Agent> = List(PLAYER_COUNT) { RuleBasedAgent() },
) {
    /** "Was soll ich machen?" is played (setting); otherwise neither bots nor the human ask. */
    @Volatile var adviceEnabled: Boolean = true

    /**
     * The next round waits for the Abheber to cut; meanwhile [state] is its preview without cards.
     * [state] and [pending] are only changed on one thread, but [planBot] may read them from another.
     */
    @Volatile private var pending: PendingRound? = Waddeln.prepareMatch(random)

    @Volatile var state: GameState = pending!!.preview
        private set

    /** The Abheber who still has to cut, or null once the round is dealt. */
    val abheberToCut: Int? get() = pending?.abheber

    fun humanMustCut(): Boolean = abheberToCut == HUMAN

    /** "Schadder?" of the current round, if somebody asked. */
    val schadet: SchadetTalk? get() = state.schadet

    private val _events = mutableListOf<GameEvent>()
    val events: List<GameEvent> get() = _events

    fun humanActions(): List<Action> = Waddeln.legalActions(state, HUMAN)

    fun applyHuman(action: Action) = apply(HUMAN, action)

    /**
     * The bot that has to act next, or null if the game waits for the human (or a new round).
     * An open Schub against the human's team is always answered by the human; an open
     * "Schadder?", "Was soll ich machen?" or Schlagwechsel is answered by the asked player first.
     */
    fun botToAct(): Int? = botToAct(state, pending)

    private fun botToAct(state: GameState, pending: PendingRound?): Int? {
        pending?.abheber?.let { return it.takeIf { it != HUMAN } }
        (Waddeln.schadetAnswerer(state) ?: Waddeln.adviceAnswerer(state) ?: Waddeln.schlagwechselAnswerer(state))?.let { return it.takeIf { it != HUMAN } }
        val toMove = state.playerToMove ?: return null
        val pending = state.pendingSchub
        val actor = if (pending == null) {
            toMove
        } else {
            if (teamOf(HUMAN) == pending.answeringTeam) return null
            generateSequence(toMove, ::nextPlayer).first { teamOf(it) == pending.answeringTeam }
        }
        return actor.takeIf { it != HUMAN }
    }

    /**
     * Lets the next bot act and returns what it did. The bots talk like [BotTalk]: before announcing
     * the Schlag a bot may propose a Schlagwechsel, before answering a Schub it asks its partner,
     * before leading the Haubi it asks "Schadder?" and then decides knowing the answer, second in a trick
     * it sometimes asks "Was soll ich machen?" first.
     */
    fun stepBot(): BotStep = planBot().also { check(commit(it)) }.step

    /**
     * Works out the next bot's step without changing the game, so it can be thought about on another
     * thread; [commit] applies it. Reads the game once at the start.
     */
    fun planBot(): BotMove {
        val state = state
        val round = pending
        val bot = checkNotNull(botToAct(state, round)) { "No bot to act" }
        fun move(step: BotStep, to: GameState) = BotMove(step, state, to)
        if (round != null) {
            val cut = Deal.randomCut(random)
            return move(BotStep.Cut(bot, cut, round.reveal(cut)), round.deal(cut))
        }
        if (Waddeln.schadetAnswerer(state) == bot) {
            val answer = Schadet.answerFor(state.observationFor(bot))
            return move(BotStep.AnsweredSchadet(bot, answer), Waddeln.answerSchadet(state, bot, answer))
        }
        if (Waddeln.adviceAnswerer(state) == bot) {
            val answer = Advice.answerFor(state.observationFor(bot))
            return move(BotStep.AnsweredAdvice(bot, answer), Waddeln.answerAdvice(state, bot, answer))
        }
        if (Waddeln.schlagwechselAnswerer(state) == bot) {
            val accept = Schlagwechsel.accepts(state.observationFor(bot))
            return move(BotStep.AnsweredSchlagwechsel(bot, accept), Waddeln.answerSchlagwechsel(state, bot, accept))
        }
        if (Waddeln.canProposeSchlagwechsel(state, bot) && Schlagwechsel.proposes(state.observationFor(bot))) {
            return move(BotStep.ProposedSchlagwechsel(bot), Waddeln.proposeSchlagwechsel(state, bot))
        }
        // Only the opponents' bots answer a Schub (the human answers for their team), so the partner is a bot.
        val partner = partnerOf(bot)
        if (Waddeln.canGiveHint(state, partner)) {
            val hint = PartnerHints.hintFor(state.observationFor(partner))
            return move(BotStep.AskedPartner(bot, hint), Waddeln.giveHint(state, partner, hint))
        }
        if (adviceEnabled && Advice.asks(state, bot, random)) {
            return move(BotStep.AskedAdvice(bot), Waddeln.askAdvice(state, bot))
        }
        val legal = BotTalk.allowedActions(state, bot, Waddeln.legalActions(state, bot))
        val action = bots[bot].chooseAction(state.observationFor(bot), legal)
        if (BotTalk.talksSchadet(state, bot, action, random)) {
            return move(BotStep.AskedSchadet(bot), Waddeln.askSchadet(state, bot))
        }
        return move(BotStep.Acted(bot, action), Waddeln.apply(state, bot, action))
    }

    /**
     * Applies a step from [planBot]. False (and nothing changes) if the game is no longer the one it was
     * planned on, e.g. the human acted or a new match was started meanwhile.
     */
    fun commit(move: BotMove): Boolean {
        if (state !== move.from) return false
        if (move.step is BotStep.Cut) pending = null
        if (move.step is BotStep.Acted) record(move.from, move.to, move.step.bot, move.step.action) else state = move.to
        return true
    }

    /** The human may propose a Schlagwechsel before announcing the Schlag; the dealer bot answers in its next step. */
    fun humanCanProposeSchlagwechsel(): Boolean = Waddeln.canProposeSchlagwechsel(state, HUMAN)

    fun proposeSchlagwechsel() {
        if (humanCanProposeSchlagwechsel()) state = Waddeln.proposeSchlagwechsel(state, HUMAN)
    }

    /** True while the human as dealer has to answer the Schlagwechsel a bot proposed. */
    fun humanMustAnswerSchlagwechsel(): Boolean = Waddeln.schlagwechselAnswerer(state) == HUMAN

    fun answerSchlagwechsel(accept: Boolean) {
        if (humanMustAnswerSchlagwechsel()) state = Waddeln.answerSchlagwechsel(state, HUMAN, accept)
    }

    /** The human leads the first trick and may ask the partner "Schadder?" (with the Haubi or as a bluff). */
    fun humanCanAskSchadet(): Boolean = Waddeln.canAskSchadet(state, HUMAN)

    /** The human asks; the partner bot answers in its next step. */
    fun askSchadet() {
        if (humanCanAskSchadet()) state = Waddeln.askSchadet(state, HUMAN)
    }

    /** True while the partner bot waits for the human's answer to its "Schadder?". */
    fun humanMustAnswerSchadet(): Boolean = Waddeln.schadetAnswerer(state) == HUMAN

    fun answerSchadet(answer: SchadetAnswer) {
        if (humanMustAnswerSchadet()) state = Waddeln.answerSchadet(state, HUMAN, answer)
    }

    /** Second in a trick after an opponent led, the human may ask the partner "Was soll ich machen?". */
    fun humanCanAskAdvice(): Boolean = adviceEnabled && Waddeln.canAskAdvice(state, HUMAN)

    /** The human asks; the partner bot answers in its next step. */
    fun askAdvice() {
        if (humanCanAskAdvice()) state = Waddeln.askAdvice(state, HUMAN)
    }

    /** True while the partner bot waits for the human's answer to its "Was soll ich machen?". */
    fun humanMustAnswerAdvice(): Boolean = Waddeln.adviceAnswerer(state) == HUMAN

    fun answerAdvice(answer: TrickAdvice) {
        if (humanMustAnswerAdvice()) state = Waddeln.answerAdvice(state, HUMAN, answer)
    }

    /** During a Schub against the human's team the partner can be asked (once per Schub). */
    fun canAskPartner(): Boolean = Waddeln.canGiveHint(state, PARTNER)

    /**
     * The human asks the partner bot about their cards; it answers at once and everybody hears it.
     * Null if asking is not possible now.
     */
    fun askPartner(): PartnerHint? {
        if (!canAskPartner()) return null
        val hint = PartnerHints.hintFor(state.observationFor(PARTNER))
        state = Waddeln.giveHint(state, PARTNER, hint)
        return hint
    }

    /**
     * The human (or a bot in [stepBot]) as Abheber cuts after [cut] cards; the round is dealt.
     * Returns what the Abheber saw.
     */
    fun cut(cut: Int): CutReveal {
        val round = checkNotNull(pending) { "Nothing to cut" }
        val reveal = round.reveal(cut)
        state = round.deal(cut)
        pending = null
        return reveal
    }

    /** The dealer of the next round shuffles; it is dealt once the Abheber has cut. */
    fun nextRound() {
        if (pending != null) return
        prepare(Waddeln.prepareNextRound(state, random))
    }

    fun newMatch() = prepare(Waddeln.prepareMatch(random))

    /** The match to keep across a restart (see [restore]). */
    fun save(): SavedMatch = pending?.let { SavedMatch(pending = it) } ?: SavedMatch(state = state)

    /** Goes on with a match from [save]. False (and nothing changes) if [saved] holds no match. */
    fun restore(saved: SavedMatch): Boolean {
        val round = saved.pending
        val dealt = saved.state
        when {
            round != null -> prepare(round)
            dealt != null -> {
                pending = null
                state = dealt
                _events.clear()
            }
            else -> return false
        }
        return true
    }

    private fun prepare(round: PendingRound) {
        pending = round
        state = round.preview
        _events.clear()
    }

    private fun apply(player: Int, action: Action) = record(state, Waddeln.apply(state, player, action), player, action)

    private fun record(before: GameState, after: GameState, player: Int, action: Action) {
        state = after
        _events += GameEvent.Acted(player, action)
        if (state.completedTricks.size > before.completedTricks.size) {
            _events += GameEvent.TrickWon(state.completedTricks.last())
        }
        state.result?.takeIf { before.result == null }?.let { _events += GameEvent.RoundEnded(it) }
    }

    companion object {
        const val HUMAN = 0
        const val PARTNER = 2
    }
}
