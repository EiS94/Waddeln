package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Rank
import kotlinx.serialization.Serializable

/** Answer to "Schadder?": would leading the Haubi (Bekennen-Pflicht) cost the partner a good card? */
@Serializable
enum class SchadetAnswer { NO, A_LITTLE, YES }

/**
 * "Schadder?": before leading the Haubi into the first trick, the player may ask the partner whether
 * having to follow with the Haubi-Farbe would hurt them.
 */
object Schadet {

    /** True if [player] is about to lead the first trick and holds the Haubi, so asking makes sense. */
    fun canAsk(state: GameState, player: Int): Boolean {
        val trump = state.trump ?: return false
        return state.phase == Phase.PLAYING && state.pendingSchub == null &&
            state.completedTricks.isEmpty() && state.currentTrick.isEmpty() &&
            state.playerToMove == player && trump.haubi in state.hands[player]
    }

    /** True if [action] leads the Haubi into the first trick (the move "Schadder?" is asked before). */
    fun leadsHaubi(state: GameState, player: Int, action: Action): Boolean =
        canAsk(state, player) && action == Action.PlayCard(state.trump!!.haubi)

    /**
     * The actions an asking bot chooses from after the partner said [answer]: on "yes" the Haubi
     * lead is dropped (if another card can be played).
     */
    fun actionsAfter(answer: SchadetAnswer, state: GameState, player: Int, legalActions: List<Action>): List<Action> {
        if (answer != SchadetAnswer.YES || !canAsk(state, player)) return legalActions
        val haubi = Action.PlayCard(state.trump!!.haubi)
        val others = legalActions.filter { it is Action.PlayCard && it != haubi }
        return others.ifEmpty { legalActions }
    }

    /**
     * The partner's answer from their hand. Without Haubi-Farbe or with three and more it does not
     * hurt. With one or two it depends on the lowest of them (the one they would give): high
     * (Ober and up) hurts, medium (Unter, 10) a little, low (7 to 9) not.
     */
    fun answerFor(obs: Observation): SchadetAnswer {
        val trump = obs.trump ?: return SchadetAnswer.NO
        val farbe = obs.hand.filter { trump.isHaubiFarbe(it) }
        if (farbe.isEmpty() || farbe.size >= 3) return SchadetAnswer.NO
        val given = farbe.minBy { it.rank }
        return when {
            given.rank >= Rank.OBER -> SchadetAnswer.YES
            given.rank >= Rank.TEN -> SchadetAnswer.A_LITTLE
            else -> SchadetAnswer.NO
        }
    }
}
