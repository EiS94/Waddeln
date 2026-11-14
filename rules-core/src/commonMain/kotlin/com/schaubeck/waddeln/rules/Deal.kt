package com.schaubeck.waddeln.rules

import kotlin.random.Random

/**
 * The chance input of a round: the shuffled [deck] (index 0 = top card) and the number of
 * cards the Abheber lifts off the top ([cut]). Keeping chance outside the engine makes
 * rounds reproducible, e.g. for JSON game protocols.
 */
data class Deal(val deck: List<Card>, val cut: Int) {
    init {
        require(deck.size == 32 && deck.toSet().size == 32) { "Deck must contain all 32 cards exactly once" }
        require(cut in CUT_RANGE) { "Cut must be in $CUT_RANGE, was $cut" }
    }

    companion object {
        /**
         * Both stacks hold at least this many cards. Since at most 3 Kritten can be taken,
         * the lifted stack always keeps a non-Kritte as visible bottom card.
         */
        const val MIN_CUT = 4

        /** Where the Abheber may cut: the number of cards lifted off the top. */
        val CUT_RANGE: IntRange = MIN_CUT..32 - MIN_CUT

        fun random(random: Random): Deal = Deal(Card.ALL.shuffled(random), randomCut(random))

        fun randomCut(random: Random): Int = random.nextInt(CUT_RANGE.first, CUT_RANGE.last + 1)
    }
}

/**
 * What is seen while cutting: the [kritten] the Abheber takes from the bottom of the lifted stack,
 * in the order taken, and the first other card, which stays as open [bottomCard].
 * If the Abheber took Kritten, the dealer then looks under the rest: he takes the [geberKritten]
 * lying at its bottom and sees the first other card, [geberCard] (only he does).
 */
data class CutReveal(
    val kritten: List<Card>,
    val bottomCard: Card,
    val geberKritten: List<Card> = emptyList(),
    /** Null if the dealer did not look (the Abheber took no Kritte). */
    val geberCard: Card? = null,
)

/** Cutting [deck] after [cut] cards (see [dealCards]). */
fun cutReveal(deck: List<Card>, cut: Int): CutReveal {
    require(cut in Deal.CUT_RANGE) { "Cut must be in ${Deal.CUT_RANGE}, was $cut" }
    val lifted = deck.subList(0, cut)
    val kritten = lifted.asReversed().takeWhile { it.isKritte }
    val bottomCard = lifted[lifted.size - 1 - kritten.size]
    if (kritten.isEmpty()) return CutReveal(kritten, bottomCard)
    val rest = deck.subList(cut, deck.size)
    val geberKritten = rest.asReversed().takeWhile { it.isKritte }
    return CutReveal(kritten, bottomCard, geberKritten, rest[rest.size - 1 - geberKritten.size])
}

/** Result of cutting and dealing. */
data class DealtCards(
    val hands: List<List<Card>>,
    /** The 12 undealt cards, top first; [stock].last() is the open bottom card. */
    val stock: List<Card>,
    /** Number of Kritten the Abheber had to take while cutting (0..3). */
    val abheberKritten: Int,
    /** Number of Kritten the dealer had to take from the bottom of the rest (0..2). */
    val geberKritten: Int = 0,
    /** The card the dealer saw under the rest (only he knows it); null if he did not look. */
    val geberCard: Card? = null,
) {
    val visibleBottomCard: Card get() = stock.last()
}

/**
 * Performs cutting and dealing for a round with the given [dealer].
 *
 * The Abheber (player before the dealer) lifts [Deal.cut] cards and takes Kritten from the bottom
 * of the lifted stack as long as they lie there. If he took any, the dealer takes the Kritten lying
 * at the bottom of the rest in the same way. The lifted stack is then put under the rest.
 * Dealing starts with the player after the dealer: first 3 cards each (Abheber and dealer get 3 − k),
 * then 2 cards each.
 */
fun dealCards(dealer: Int, deal: Deal): DealtCards {
    val abheber = abheberOf(dealer)
    val lifted = deal.deck.subList(0, deal.cut).toMutableList()
    val rest = deal.deck.subList(deal.cut, deal.deck.size).toMutableList()

    val taken = mutableListOf<Card>()
    while (lifted.last().isKritte) taken += lifted.removeAt(lifted.lastIndex)
    val geberTaken = mutableListOf<Card>()
    if (taken.isNotEmpty()) {
        while (rest.last().isKritte) geberTaken += rest.removeAt(rest.lastIndex)
    }
    val geberCard = rest.last().takeIf { taken.isNotEmpty() }

    val pile = ArrayDeque(rest + lifted)
    val hands = List(PLAYER_COUNT) { mutableListOf<Card>() }
    hands[abheber] += taken
    hands[dealer] += geberTaken

    val order = List(PLAYER_COUNT) { (dealer + 1 + it) % PLAYER_COUNT }
    for (round in listOf(3, 2)) {
        for (p in order) {
            val count = when {
                round != 3 -> round
                p == abheber -> 3 - taken.size
                p == dealer -> 3 - geberTaken.size
                else -> round
            }
            repeat(count) { hands[p] += pile.removeFirst() }
        }
    }
    return DealtCards(hands, pile.toList(), taken.size, geberTaken.size, geberCard)
}
