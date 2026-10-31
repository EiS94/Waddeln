package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.GESPANNT_POINTS
import com.schaubeck.waddeln.rules.MATCH_POINTS
import com.schaubeck.waddeln.rules.Observation

/**
 * Answering a Schub when going is never better than staying: going would make the opponents
 * gespannt, and losing the raised game would leave them gespannt as well without ending the match
 * (e.g. opponents on 7 say Schub from 2 to 3: going gives them 9, losing 10). Staying keeps the
 * chance to win the game, so every bot stays. Mirrored in training/waddeln/bots.py (`schub_must_stay`).
 */
object SchubRule {

    fun mustStay(obs: Observation): Boolean {
        val asking = obs.pendingSchub?.askingTeam ?: return false
        if (asking == obs.team) return false
        val afterGoing = obs.scores[asking] + obs.gameValue
        return afterGoing >= GESPANNT_POINTS && afterGoing + 1 < MATCH_POINTS
    }
}
