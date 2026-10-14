package com.schaubeck.waddeln.rules

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DealTest {

    /** Deck where the lifted stack (first [cut] cards) ends with [bottom], top to bottom. */
    private fun deckWithLiftedBottom(cut: Int, bottom: List<Card>): List<Card> {
        val others = Card.ALL - bottom.toSet()
        return others.take(cut - bottom.size) + bottom + others.drop(cut - bottom.size)
    }

    @Test
    fun `every player gets 5 cards and 12 remain`() {
        repeat(200) { seed ->
            val dealt = dealCards(dealer = seed % 4, deal = Deal.random(Random(seed)))
            dealt.hands.forEach { assertEquals(HAND_SIZE, it.size) }
            assertEquals(12, dealt.stock.size)
            assertEquals(Card.ALL.toSet(), (dealt.hands.flatten() + dealt.stock).toSet())
            assertFalse(dealt.visibleBottomCard.isKritte)
        }
    }

    @Test
    fun `no Kritte at lifted bottom means nothing is taken`() {
        val deck = deckWithLiftedBottom(10, listOf(c("GA")))
        val dealt = dealCards(dealer = 0, deal = Deal(deck, 10))
        assertEquals(0, dealt.abheberKritten)
        assertEquals(c("GA"), dealt.visibleBottomCard)
    }

    @Test
    fun `Abheber must take stacked Kritten from the bottom`() {
        for (k in 1..3) {
            val kritten = Card.KRITTEN.take(k)
            val deck = deckWithLiftedBottom(10, listOf(c("GA")) + kritten)
            val dealer = 1
            val dealt = dealCards(dealer, Deal(deck, 10))
            assertEquals(k, dealt.abheberKritten)
            assertTrue(dealt.hands[abheberOf(dealer)].containsAll(kritten))
            assertEquals(c("GA"), dealt.visibleBottomCard)
        }
    }

    @Test
    fun `Kritte above a non-Kritte is not taken`() {
        val deck = deckWithLiftedBottom(10, listOf(c("HK"), c("GA")))
        val dealt = dealCards(dealer = 0, deal = Deal(deck, 10))
        assertEquals(0, dealt.abheberKritten)
        assertEquals(c("GA"), dealt.visibleBottomCard)
    }

    @Test
    fun `dealing order is 3 then 2 starting after the dealer`() {
        val deck = Card.ALL
        val cut = 4 // lifted stack S7..S10 ends with S10 (no Kritte); pile = deck[4..] + deck[0..4]
        val dealt = dealCards(dealer = 3, deal = Deal(deck, cut))
        val pile = deck.drop(cut) + deck.take(cut)
        assertEquals(pile.slice(0..2) + pile.slice(12..13), dealt.hands[0])
        assertEquals(pile.slice(3..5) + pile.slice(14..15), dealt.hands[1])
        assertEquals(pile.slice(6..8) + pile.slice(16..17), dealt.hands[2])
        assertEquals(pile.slice(9..11) + pile.slice(18..19), dealt.hands[3])
        assertEquals(pile.drop(20), dealt.stock)
    }

    @Test
    fun `Abheber with Kritten gets fewer cards in the first pass`() {
        val deck = deckWithLiftedBottom(10, listOf(c("GA"), c("S7"), c("E7")))
        val dealt = dealCards(dealer = 0, deal = Deal(deck, 10))
        val abheber = abheberOf(0)
        assertEquals(3, abheber)
        assertEquals(listOf(c("E7"), c("S7")), dealt.hands[abheber].take(2))
        assertEquals(HAND_SIZE, dealt.hands[abheber].size)
    }

    @Test
    fun `invalid deals are rejected`() {
        assertFailsWith<IllegalArgumentException> { Deal(Card.ALL, 3) }
        assertFailsWith<IllegalArgumentException> { Deal(Card.ALL, 29) }
        assertFailsWith<IllegalArgumentException> { Deal(Card.ALL.drop(1) + Card.ALL[1], 10) }
    }

    @Test
    fun `test helper produces the requested hands`() {
        val hands = listOf("HK S7 GO G9 SA", "E7 HA H9 EA EK", "SO SU S8 S9 S10", "G7 G8 GU HO EO").map(::cards)
        val dealt = dealCards(3, dealFor(3, hands))
        assertEquals(hands, dealt.hands)
        // Abheber (2) holding Kritten taken while cutting
        val hands2 = listOf("SO SU S8 S9 S10", "E7 HA H9 EA EK", "HK S7 GO G9 SA", "G7 G8 GU HO EO").map(::cards)
        val dealt2 = dealCards(3, dealFor(3, hands2, abheberKritten = 2))
        assertEquals(hands2, dealt2.hands)
        assertEquals(2, dealt2.abheberKritten)
    }
}
