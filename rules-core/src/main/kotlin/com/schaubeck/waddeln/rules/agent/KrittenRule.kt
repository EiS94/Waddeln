package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Observation

/**
 * Kritten are saved for later: following in the first trick while no trump lies in it, no Kritte is
 * thrown in if another card can be played. People keep them to catch the opponents' high trumps.
 * The network did so in 6.7 % of these chances; taking that away made no difference to the result
 * (49.9 % ± 0.8 of 4000 matches against the network itself).
 */
object KrittenRule {

    /** [legalActions] without the Kritten when they should be saved, otherwise unchanged. */
    fun allowed(obs: Observation, legalActions: List<Action>): List<Action> {
        val trump = obs.trump ?: return legalActions
        if (obs.completedTricks.isNotEmpty() || obs.currentTrick.isEmpty()) return legalActions
        if (obs.currentTrick.any { trump.isTrump(it.card) }) return legalActions
        val saving = legalActions.filter { it !is Action.PlayCard || !it.card.isKritte }
        return if (saving.any { it is Action.PlayCard }) saving else legalActions
    }
}
