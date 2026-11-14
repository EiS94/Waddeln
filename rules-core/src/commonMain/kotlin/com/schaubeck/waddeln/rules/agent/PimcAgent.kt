package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.Play
import kotlin.random.Random

/**
 * Checks the card choices of [base] by calculating ahead (Perfect Information Monte Carlo): it deals
 * the unseen cards [samples] times consistent with the observation ([HandSampler]), solves the rest of
 * the game for each card with open hands ([DoubleDummy]) and keeps the share of deals the own team wins.
 * The card of [base] stays if it wins at most [margin] less often than the best one; otherwise the best
 * card is played (the cheapest of equally good ones). Schub, Bleiben/Gehen and announcing stay with [base].
 */
class PimcAgent(
    private val base: Agent,
    private val samples: Int = 60,
    private val margin: Double = 0.0,
    private val random: Random = Random.Default,
    /** Deals the unseen cards; [HandSampler] unless a test knows better. */
    private val sampler: (Observation, Random) -> List<List<Card>>? = HandSampler::sample,
    /**
     * Rates how likely deals are given what the others did (e.g. [com.schaubeck.waddeln.rules.ml.DealInference]).
     * With it, [candidates] deals are drawn and [samples] of them picked by weight.
     */
    private val weigh: ((Observation, List<List<List<Card>>>) -> DoubleArray)? = null,
    private val candidates: Int = 4 * samples,
) : Agent {

    override fun chooseAction(observation: Observation, legalActions: List<Action>): Action {
        val chosen = base.chooseAction(observation, legalActions)
        if (chosen !is Action.PlayCard) return chosen
        val trump = observation.trump ?: return chosen
        val cards = legalActions.filterIsInstance<Action.PlayCard>().map { it.card }
        if (cards.size < 2) return chosen
        val wins = winShares(observation, cards) ?: return chosen
        val best = wins.values.max()
        if (wins.getValue(chosen.card) >= best - margin) return chosen
        return Action.PlayCard(cards.filter { wins.getValue(it) == best }.minBy { DiscardRule.value(it, trump) })
    }

    /** Share of the sampled deals the own team wins after playing each of [cards]; null if no deal fits. */
    fun winShares(obs: Observation, cards: List<Card>): Map<Card, Double>? {
        val trump = obs.trump ?: return null
        val solver = DoubleDummy(trump, obs.team)
        val wins = IntArray(cards.size)
        val deals = deals(obs)
        if (deals.isEmpty()) return null
        for (hands in deals) {
            cards.forEachIndexed { i, card ->
                val after = hands.mapIndexed { p, h -> if (p == obs.player) h - card else h }
                if (solver.teamWins(after, obs.trickLeader, obs.currentTrick + Play(obs.player, card), obs.tricksWon, obs.completedTricks.size)) wins[i]++
            }
        }
        return cards.indices.associate { cards[it] to wins[it].toDouble() / deals.size }
    }

    private fun deals(obs: Observation): List<List<List<Card>>> {
        val weigh = weigh ?: return List(samples) { sampler(obs, random) }.filterNotNull()
        val drawn = List(candidates) { sampler(obs, random) }.filterNotNull()
        if (drawn.isEmpty()) return drawn
        val weights = weigh(obs, drawn)
        val total = weights.sum()
        return List(samples) {
            var r = random.nextDouble() * total
            drawn.indices.firstOrNull { i -> r -= weights[i]; r <= 0 }?.let { drawn[it] } ?: drawn.last()
        }
    }
}
