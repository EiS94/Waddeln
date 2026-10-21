package com.schaubeck.waddeln.rules

/**
 * A round before the deal: the dealer has shuffled [deck] (index 0 = top card) and the Abheber
 * still has to choose where to cut. Created by [Waddeln.prepareMatch] or [Waddeln.prepareNextRound].
 */
data class PendingRound(val scores: List<Int>, val dealer: Int, val roundNumber: Int, val deck: List<Card>) {
    init {
        require(deck.size == 32 && deck.toSet().size == 32) { "Deck must contain all 32 cards exactly once" }
    }

    val abheber: Int get() = abheberOf(dealer)

    /**
     * What the table shows while the Abheber cuts: scores, round and dealer, but nobody holds cards
     * yet. It reveals nothing of [deck] (its stock is a fixed placeholder card) and has no legal actions.
     */
    val preview: GameState
        get() = GameState(
            scores = scores,
            dealer = dealer,
            roundNumber = roundNumber,
            phase = Phase.ROUND_OVER,
            hands = List(PLAYER_COUNT) { emptyList() },
            stock = listOf(Card.ALL.first()),
            abheberKritten = 0,
            trickLeader = nextPlayer(dealer),
        )

    /** What the Abheber sees when cutting after [cut] cards. */
    fun reveal(cut: Int): CutReveal = cutReveal(deck, cut)

    /** The Abheber cuts after [cut] cards; the round is dealt. */
    fun deal(cut: Int): GameState = Waddeln.startRound(scores, dealer, roundNumber, Deal(deck, cut))
}
