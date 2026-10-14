package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Observation
import kotlin.random.Random

/** A player (bot or bridge to a human/remote client). Must return one of [legalActions]. */
fun interface Agent {
    fun chooseAction(observation: Observation, legalActions: List<Action>): Action
}

/** Picks uniformly among the legal actions. Useful for tests and as the weakest opponent. */
class RandomAgent(private val random: Random) : Agent {
    override fun chooseAction(observation: Observation, legalActions: List<Action>): Action =
        legalActions.random(random)
}
