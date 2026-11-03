package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import kotlin.random.Random

/**
 * Deals the cards [Observation.player] cannot see to the other players, consistent with everything
 * they know: hand sizes, played cards, the open bottom card, the Kritten the Abheber and the dealer
 * had to take, the laid-out counts when gespannt meets gespannt and who could not follow the Haubi-Farbe
 * in the first trick. Table talk (Schub, hints, "Schadder?") is not used to weight the deals yet.
 */
object HandSampler {

    /** The current hands of all four players (the own one as observed), or null if dealing failed. */
    fun sample(obs: Observation, random: Random): List<List<Card>>? {
        val trump = obs.trump ?: return null
        val me = obs.player
        val playedBy = List(PLAYER_COUNT) { mutableListOf<Card>() }
        (obs.completedTricks.flatMap { it.plays } + obs.currentTrick).forEach { playedBy[it.player] += it.card }
        val pool = (Card.ALL - obs.hand.toSet() - playedBy.flatten().toSet() - obs.visibleBottomCard).shuffled(random).toMutableList()

        val hands = List(PLAYER_COUNT) { mutableListOf<Card>() }
        hands[me] += obs.hand
        val slots = IntArray(PLAYER_COUNT) { if (it == me) 0 else obs.handSizes[it] }
        val forbidden = List(PLAYER_COUNT) { mutableSetOf<Card>() }

        fun give(player: Int, card: Card) {
            hands[player] += card
            pool -= card
            slots[player]--
        }

        val laid = obs.laidOutCounts
        if (laid != null) {
            // Every Kritte and the Haubi still held lies on the table, so the counts say who holds how many of them.
            val special = pool.filter { it.isKritte || it == trump.haubi }
            var next = 0
            for (p in 0 until PLAYER_COUNT) {
                if (p == me) continue
                if (next + laid[p] > special.size || laid[p] > slots[p]) return null
                repeat(laid[p]) { give(p, special[next++]) }
                forbidden[p] += special
            }
        } else {
            for ((p, taken) in listOf(obs.abheber to obs.abheberKritten, obs.dealer to obs.geberKritten)) {
                if (p == me) continue
                val missing = taken - playedBy[p].count { it.isKritte } - hands[p].count { it.isKritte }
                val kritten = pool.filter { it.isKritte }
                if (missing > kritten.size || missing > slots[p]) return null
                kritten.take(missing.coerceAtLeast(0)).forEach { give(p, it) }
            }
        }

        // Under the Bekennen-Pflicht a card that is neither Haubi-Farbe nor Kritte means: no Haubi-Farbe left.
        val first = obs.completedTricks.firstOrNull()?.plays ?: obs.currentTrick
        if (first.isNotEmpty() && first[0].card == trump.haubi) {
            for (play in first.drop(1)) {
                if (play.card.isKritte) break
                if (!trump.isHaubiFarbe(play.card)) forbidden[play.player] += Card.ALL.filter { trump.isHaubiFarbe(it) }
            }
        }

        // The most restricted players first, so they still find enough cards.
        for (p in (0 until PLAYER_COUNT).sortedByDescending { forbidden[it].size }) {
            if (p == me) continue
            val allowed = pool.filter { it !in forbidden[p] }
            if (allowed.size < slots[p]) return null
            allowed.take(slots[p]).forEach { give(p, it) }
        }
        return hands
    }
}
