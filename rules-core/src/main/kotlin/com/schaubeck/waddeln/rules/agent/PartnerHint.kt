package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import kotlinx.serialization.Serializable

/**
 * What a player may tell the partner during a Schub without revealing cards: a rough
 * estimate of the own hand, from "nothing" to "I make all tricks still needed".
 */
@Serializable
enum class PartnerHint { NOTHING, LITTLE, ONE_TRICK, TWO_TRICKS, ALL_TRICKS }

/**
 * Turns a hand into a [PartnerHint]. A plain heuristic (no ML): every card is worth the chance
 * that it takes a trick, depending on how many stronger trumps are still unseen; several trumps
 * together give extra control. "All tricks" needs an estimate close to the tricks still needed;
 * if the opponents need only one more trick, every trick still needed must be sure.
 */
object PartnerHints {

    fun hintFor(obs: Observation): PartnerHint {
        val needed = TRICKS_TO_WIN - obs.tricksWon[obs.team]
        val opponentsNeed = TRICKS_TO_WIN - obs.tricksWon[1 - obs.team]
        val e = estimatedTricks(obs)
        val all = if (opponentsNeed <= 1) sureTricks(obs) >= needed else e >= needed - ALL_TRICKS_MARGIN
        return when {
            e < NOTHING_BELOW -> PartnerHint.NOTHING
            e >= ONE_TRICK_FROM && all -> PartnerHint.ALL_TRICKS
            e < ONE_TRICK_FROM -> PartnerHint.LITTLE
            e < TWO_TRICKS_FROM -> PartnerHint.ONE_TRICK
            else -> PartnerHint.TWO_TRICKS
        }
    }

    /** Trumps in the hand of [obs] that no unseen card can beat. */
    fun sureTricks(obs: Observation): Int {
        val trump = obs.trump ?: return 0
        val unseen = unseen(obs)
        return obs.hand.count { card -> trump.isTrump(card) && unseen.none { trump.strength(it) > trump.strength(card) } }
    }

    private fun unseen(obs: Observation): List<Card> {
        val seen = (obs.hand + obs.currentTrick.map { it.card } + obs.completedTricks.flatMap { t -> t.plays.map { it.card } }).toSet()
        return Card.ALL.filter { it !in seen }
    }

    /** Rough number of tricks the hand of [obs] can take on its own (0 before the Haubi is fixed). */
    fun estimatedTricks(obs: Observation): Double {
        val trump = obs.trump ?: return 0.0
        val unseen = unseen(obs)
        val trumps = obs.hand.filter { trump.isTrump(it) }
        val trumpValue = trumps.sumOf { card ->
            val stronger = unseen.count { trump.strength(it) > trump.strength(card) }
            (1.0 - STRONGER_TRUMP_PENALTY * stronger).coerceAtLeast(MIN_TRUMP_VALUE)
        }
        val plainValue = obs.hand.filter { !trump.isTrump(it) }.sumOf { plainValue(it) }
        val control = when {
            trumps.size >= 4 -> 0.35
            trumps.size >= 3 -> 0.2
            else -> 0.0
        }
        return (trumpValue + plainValue + control).coerceAtMost(obs.hand.size.toDouble())
    }

    private fun plainValue(card: Card): Double = when (card.rank) {
        Rank.ASS -> 0.25
        Rank.KOENIG -> 0.1
        else -> 0.0
    }

    private const val STRONGER_TRUMP_PENALTY = 0.1
    private const val MIN_TRUMP_VALUE = 0.1
    private const val NOTHING_BELOW = 0.35
    private const val ONE_TRICK_FROM = 0.8
    private const val TWO_TRICKS_FROM = 1.6

    /** "All tricks" already when the estimate comes this close to the tricks still needed. */
    private const val ALL_TRICKS_MARGIN = 0.25
}
