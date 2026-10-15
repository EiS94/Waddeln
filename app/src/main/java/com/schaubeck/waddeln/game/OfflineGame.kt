package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.RoundResult
import com.schaubeck.waddeln.rules.SchadetTalk
import com.schaubeck.waddeln.rules.Trick
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.BotTalk
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.PartnerHints
import com.schaubeck.waddeln.rules.agent.Schadet
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.partnerOf
import com.schaubeck.waddeln.rules.teamOf
import kotlin.random.Random

/** What a bot did in one step. */
sealed interface BotStep {
    val bot: Int

    data class Acted(override val bot: Int, val action: Action) : BotStep

    /** Before leading the Haubi the bot asks its partner "Schadder?". */
    data class AskedSchadet(override val bot: Int) : BotStep

    data class AnsweredSchadet(override val bot: Int, val answer: SchadetAnswer) : BotStep

    /** Before answering a Schub the bot asked its partner, who answered with [hint] at once. */
    data class AskedPartner(override val bot: Int, val hint: PartnerHint) : BotStep
}

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
    var bots: List<Agent> = List(PLAYER_COUNT) { RuleBasedAgent() },
) {
    var state: GameState = Waddeln.newMatch(random)
        private set

    /** "Schadder?" of the current round, if somebody asked. */
    val schadet: SchadetTalk? get() = state.schadet

    private val _events = mutableListOf<GameEvent>()
    val events: List<GameEvent> get() = _events

    fun humanActions(): List<Action> = Waddeln.legalActions(state, HUMAN)

    fun applyHuman(action: Action) = apply(HUMAN, action)

    /**
     * The bot that has to act next, or null if the game waits for the human (or a new round).
     * An open Schub against the human's team is always answered by the human; an open
     * "Schadder?" is answered by the asked partner first.
     */
    fun botToAct(): Int? {
        Waddeln.schadetAnswerer(state)?.let { return it.takeIf { it != HUMAN } }
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
     * Lets the next bot act and returns what it did. The bots talk like [BotTalk]: before answering
     * a Schub a bot asks its partner, before leading the Haubi it asks "Schadder?" and then decides
     * knowing the answer.
     */
    fun stepBot(): BotStep {
        val bot = checkNotNull(botToAct()) { "No bot to act" }
        if (Waddeln.schadetAnswerer(state) == bot) {
            val answer = Schadet.answerFor(state.observationFor(bot))
            state = Waddeln.answerSchadet(state, bot, answer)
            return BotStep.AnsweredSchadet(bot, answer)
        }
        // Only the opponents' bots answer a Schub (the human answers for their team), so the partner is a bot.
        val partner = partnerOf(bot)
        if (Waddeln.canGiveHint(state, partner)) {
            val hint = PartnerHints.hintFor(state.observationFor(partner))
            state = Waddeln.giveHint(state, partner, hint)
            return BotStep.AskedPartner(bot, hint)
        }
        val legal = Schadet.allowedActions(state, bot, Waddeln.legalActions(state, bot))
        val action = bots[bot].chooseAction(state.observationFor(bot), legal)
        if (BotTalk.asksSchadet(state, bot, action)) {
            state = Waddeln.askSchadet(state, bot)
            return BotStep.AskedSchadet(bot)
        }
        apply(bot, action)
        return BotStep.Acted(bot, action)
    }

    /** The human leads the first trick with the Haubi and may ask the partner "Schadder?". */
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

    fun nextRound() {
        state = Waddeln.startNextRound(state, Deal.random(random))
        _events.clear()
    }

    fun newMatch() {
        state = Waddeln.newMatch(random)
        _events.clear()
    }

    private fun apply(player: Int, action: Action) {
        val before = state
        state = Waddeln.apply(state, player, action)
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
