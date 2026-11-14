package com.schaubeck.waddeln.rules

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CutTest {

    /** Deck where the lifted stack (first [cut] cards) ends with [bottom], top to bottom. */
    private fun deckWithLiftedBottom(cut: Int, bottom: List<Card>): List<Card> {
        val others = Card.ALL - bottom.toSet()
        return others.take(cut - bottom.size) + bottom + others.drop(cut - bottom.size)
    }

    @Test
    fun `reveal shows the bottom card of the lifted stack`() {
        val reveal = cutReveal(deckWithLiftedBottom(10, listOf(c("GA"))), 10)
        assertEquals(emptyList(), reveal.kritten)
        assertEquals(c("GA"), reveal.bottomCard)
    }

    @Test
    fun `reveal lists the Kritten in the order they are taken`() {
        val deck = deckWithLiftedBottom(12, listOf(c("GA"), c("E7"), c("HK")))
        val reveal = cutReveal(deck, 12)
        assertEquals(listOf(c("HK"), c("E7")), reveal.kritten)
        assertEquals(c("GA"), reveal.bottomCard)
    }

    @Test
    fun `reveal matches the deal`() {
        repeat(300) { seed ->
            val deal = Deal.random(Random(seed))
            val dealer = seed % PLAYER_COUNT
            val dealt = dealCards(dealer, deal)
            val reveal = cutReveal(deal.deck, deal.cut)
            assertEquals(dealt.abheberKritten, reveal.kritten.size)
            assertTrue(dealt.hands[abheberOf(dealer)].containsAll(reveal.kritten))
            assertEquals(dealt.visibleBottomCard, reveal.bottomCard)
        }
    }

    @Test
    fun `cut must leave at least 4 cards on both stacks`() {
        assertEquals(4..28, Deal.CUT_RANGE)
        assertFailsWith<IllegalArgumentException> { cutReveal(Card.ALL, 3) }
        assertFailsWith<IllegalArgumentException> { cutReveal(Card.ALL, 29) }
        repeat(500) { assertTrue(Deal.randomCut(Random(it)) in Deal.CUT_RANGE) }
    }

    @Test
    fun `pending match deals the first round`() {
        val pending = Waddeln.prepareMatch(Random(3))
        val state = pending.deal(11)
        assertEquals(Waddeln.newMatch(pending.dealer, Deal(pending.deck, 11)), state)
        assertEquals(1, state.roundNumber)
        assertEquals(abheberOf(pending.dealer), pending.abheber)
    }

    @Test
    fun `pending next round moves the dealer on`() {
        var state = Waddeln.newMatch(firstDealer = 2, deal = Deal.random(Random(1)))
        state = state.copy(phase = Phase.ROUND_OVER, scores = listOf(4, 6))
        val pending = Waddeln.prepareNextRound(state, Random(2))
        assertEquals(3, pending.dealer)
        assertEquals(2, pending.roundNumber)
        assertEquals(Waddeln.startNextRound(state, Deal(pending.deck, 20)), pending.deal(20))
        assertFailsWith<IllegalArgumentException> { Waddeln.prepareNextRound(state.copy(phase = Phase.PLAYING), Random(2)) }
    }

    @Test
    fun `preview shows scores and dealer but no cards`() {
        val pending = Waddeln.prepareMatch(Random(5)).copy(scores = listOf(3, 9), roundNumber = 7)
        val obs = pending.preview.observationFor(pending.abheber)
        assertEquals(listOf(3, 9), obs.scores)
        assertEquals(pending.dealer, obs.dealer)
        assertEquals(7, obs.roundNumber)
        assertEquals(emptyList(), obs.hand)
        assertEquals(listOf(0, 0, 0, 0), obs.handSizes)
        assertEquals(null, obs.playerToMove)
        repeat(PLAYER_COUNT) { assertEquals(emptyList(), Waddeln.legalActions(pending.preview, it)) }
    }
}
