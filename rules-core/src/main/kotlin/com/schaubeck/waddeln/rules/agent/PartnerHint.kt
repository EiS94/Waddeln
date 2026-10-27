package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.rules.trickWinnerIndex
import kotlinx.serialization.Serializable

/**
 * What a player may tell the partner during a Schub without revealing cards: a rough
 * estimate of the own hand, from "nothing" to "I make all tricks still needed".
 */
@Serializable
enum class PartnerHint { NOTHING, LITTLE, ONE_TRICK, TWO_TRICKS, ALL_TRICKS }

/**
 * How the trick being played counts in a [PartnerHint]:
 * [WITH] I can still take it myself, so it is part of my own count;
 * [AFTER] our team probably has it already, so one trick less is needed (also with an empty hand);
 * [NOT] the opponents probably have it, so they need one less;
 * [NONE] no trick is being played, or it is unclear.
 */
enum class HintTrick { NONE, WITH, AFTER, NOT }

/**
 * Turns a hand into a [PartnerHint]. A plain heuristic (no ML): every card is worth the chance
 * that it takes a trick, depending on how many stronger trumps are still unseen; several trumps
 * together give extra control. "All tricks" needs an estimate close to the tricks still needed;
 * if the opponents need only one more trick, every trick still needed must be sure.
 * A trick being played that one team probably takes already counts for that team ([trickFor]).
 */
object PartnerHints {

    fun hintFor(obs: Observation): PartnerHint {
        val trick = trickFor(obs)
        val needed = TRICKS_TO_WIN - obs.tricksWon[obs.team] - (if (trick == HintTrick.AFTER) 1 else 0)
        val opponentsNeed = TRICKS_TO_WIN - obs.tricksWon[1 - obs.team] - (if (trick == HintTrick.NOT) 1 else 0)
        if (needed <= 0) return PartnerHint.ALL_TRICKS
        if (opponentsNeed <= 0) return PartnerHint.NOTHING
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

    /**
     * How [Observation.player] sees the trick being played: [HintTrick.WITH] if the opponents lead it
     * but he is still to play and can beat it; otherwise [HintTrick.AFTER] or [HintTrick.NOT] if our
     * team takes it with a chance of at least [LIKELY] or at most 1 − [LIKELY] ([ourChance]).
     */
    fun trickFor(obs: Observation): HintTrick {
        val trump = obs.trump ?: return HintTrick.NONE
        val trick = obs.currentTrick
        if (trick.isEmpty()) return HintTrick.NONE
        val cards = trick.map { it.card }
        val winner = teamOf(trick[trickWinnerIndex(cards, trump)].player)
        val toPlay = trick.none { it.player == obs.player }
        if (winner != obs.team && toPlay && obs.hand.any { beats(cards, it, trump) }) return HintTrick.WITH
        val chance = ourChance(obs)
        return when {
            chance >= LIKELY -> HintTrick.AFTER
            chance <= 1 - LIKELY -> HintTrick.NOT
            else -> HintTrick.NONE
        }
    }

    /**
     * Chance that our team takes the trick being played, as far as the others still to play decide it:
     * leading, that no opponent still to play holds a card that beats it; behind, that the partner (if
     * still to play) holds one. Unseen cards are spread evenly over the other hands and the hidden stock.
     */
    fun ourChance(obs: Observation): Double {
        val trump = obs.trump ?: return 0.5
        val trick = obs.currentTrick
        if (trick.isEmpty()) return 0.5
        val cards = trick.map { it.card }
        val winner = teamOf(trick[trickWinnerIndex(cards, trump)].player)
        val unknown = unseen(obs) - obs.visibleBottomCard
        val beaters = unknown.count { beats(cards, it, trump) }
        val others = (0 until PLAYER_COUNT).filter { p -> p != obs.player && trick.none { it.player == p } && teamOf(p) != winner }
        val slots = others.sumOf { obs.handSizes[it] }
        val noneThere = chanceNoneAmong(unknown.size, beaters, slots)
        return if (winner == obs.team) noneThere else 1.0 - noneThere
    }

    /** Chance that none of [special] cards among [total] unknown ones is in [drawn] of them. */
    private fun chanceNoneAmong(total: Int, special: Int, drawn: Int): Double {
        var p = 1.0
        for (i in 0 until drawn) {
            if (total - i <= 0) break
            p *= (total - special - i).coerceAtLeast(0).toDouble() / (total - i)
        }
        return p
    }

    private fun beats(cards: List<Card>, card: Card, trump: Trump): Boolean = trickWinnerIndex(cards + card, trump) == cards.size

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

    /** From this chance on, the trick being played counts as ours (or, below 1 − it, as theirs). */
    private const val LIKELY = 0.6

    private const val STRONGER_TRUMP_PENALTY = 0.1
    private const val MIN_TRUMP_VALUE = 0.1
    private const val NOTHING_BELOW = 0.35
    private const val ONE_TRICK_FROM = 0.8
    private const val TWO_TRICKS_FROM = 1.6

    /** "All tricks" already when the estimate comes this close to the tricks still needed. */
    private const val ALL_TRICKS_MARGIN = 0.25
}
