package com.schaubeck.waddeln.rules

/**
 * The trump configuration of one game, defined by the announced [schlag] and [farbe].
 *
 * Hierarchy (strongest first): Max, Bello, Secher, Haubi, Haubi-Schläge (all equal),
 * Haubi-Farbe (Ass down to 7). If Schlag + Farbe form a Kritte, that card is Haubi and Kritte
 * at once and keeps its Kritte rank; Haubi-Schläge and Haubi-Farbe stay trump as usual.
 */
data class Trump(val schlag: Rank, val farbe: Suit) {

    /** The Haubi card (may be a Kritte, which then keeps its Kritte rank). */
    val haubi: Card = Card(farbe, schlag)

    fun isTrump(card: Card): Boolean = strength(card) > NO_TRUMP

    fun isHaubiSchlag(card: Card): Boolean = card.rank == schlag && card != haubi && !card.isKritte

    fun isHaubiFarbe(card: Card): Boolean = card.suit == farbe && card != haubi && !card.isKritte

    /**
     * Trump strength of [card]; higher wins. Returns [NO_TRUMP] for non-trump cards.
     * All Haubi-Schläge share the same strength (ties go to the card played first).
     */
    fun strength(card: Card): Int = when {
        card == Card.MAX -> 100
        card == Card.BELLO -> 99
        card == Card.SECHER -> 98
        card == haubi -> 97
        isHaubiSchlag(card) -> 96
        isHaubiFarbe(card) -> 80 + card.rank.ordinal
        else -> NO_TRUMP
    }

    /** All trump cards, strongest first (Haubi-Schläge in suit order). */
    fun trumpCards(): List<Card> =
        Card.ALL.filter { isTrump(it) }.sortedByDescending { strength(it) }

    companion object {
        const val NO_TRUMP = -1
    }
}

/** Returns the index (into [cards]) of the card that wins a trick played in this order. */
fun trickWinnerIndex(cards: List<Card>, trump: Trump): Int {
    require(cards.isNotEmpty()) { "A trick needs at least one card" }
    var best = 0
    var bestStrength = trump.strength(cards[0])
    for (i in 1 until cards.size) {
        val s = trump.strength(cards[i])
        // Strictly greater: on equal strength (Haubi-Schläge) the earlier card wins.
        if (s > bestStrength) {
            best = i
            bestStrength = s
        }
    }
    if (bestStrength > Trump.NO_TRUMP) return best

    // No trump in the trick: the highest card of the led suit wins.
    val leadSuit = cards[0].suit
    best = 0
    for (i in 1 until cards.size) {
        val c = cards[i]
        if (c.suit == leadSuit && c.rank > cards[best].rank) best = i
    }
    return best
}
