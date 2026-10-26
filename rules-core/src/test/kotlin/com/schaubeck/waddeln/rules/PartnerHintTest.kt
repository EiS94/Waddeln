package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.PartnerHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartnerHintTest {
    // Haubi Gras Ober. Player 0 holds the Max; Bello and Haubi lie in the stock.
    private val hands = listOf(
        "HK S8 S10 H9 E9",
        "SK SU H10 HU E10",
        "E7 SO HO S9 E8",
        "SA EA EK H8 G9",
    )
    private val state = playingWith(hands, Rank.OBER, Suit.GRAS)

    private fun hint(player: Int, obs: Observation = state.observationFor(player)) = PartnerHints.hintFor(obs)

    @Test
    fun `two Haubi-Schlaege and the Secher are two tricks, not all three`() {
        assertEquals(PartnerHint.TWO_TRICKS, hint(2))
    }

    @Test
    fun `two Haubi-Schlaege and the Secher are all when two tricks are missing`() {
        val obs = state.observationFor(2).copy(tricksWon = listOf(1, 0))
        assertEquals(PartnerHint.ALL_TRICKS, hint(2, obs))
    }

    @Test
    fun `when the opponents need only one trick, all tricks must be sure`() {
        // Max and Bello are still out, so the Secher and the Haubi-Schlaege can be beaten.
        val obs = state.observationFor(2).copy(tricksWon = listOf(1, 2))
        assertEquals(0, PartnerHints.sureTricks(obs))
        assertEquals(PartnerHint.TWO_TRICKS, hint(2, obs))
    }

    @Test
    fun `Max and Bello are all two missing tricks even if the opponents need only one`() {
        val s = playingWith(listOf("HK S7 S10 H9 E9", "SK SU H10 HU E10", "E7 SO HO S9 E8", "SA EA EK H8 G9"), Rank.OBER, Suit.GRAS)
        val obs = s.observationFor(0).copy(tricksWon = listOf(1, 2))
        assertEquals(2, PartnerHints.sureTricks(obs))
        assertEquals(PartnerHint.ALL_TRICKS, hint(0, obs))
    }

    @Test
    fun `Max, Bello and the Haubi promise all three tricks`() {
        val s = playingWith(listOf("HK S7 GO H9 E9", "SK SU H10 HU E10", "E7 SO HO S9 E8", "SA EA EK H8 G9"), Rank.OBER, Suit.GRAS)
        assertEquals(PartnerHint.ALL_TRICKS, hint(0, s.observationFor(0)))
    }

    @Test
    fun `a lone Max is one trick`() {
        assertEquals(PartnerHint.ONE_TRICK, hint(0))
    }

    @Test
    fun `plain low cards are nothing`() {
        assertEquals(PartnerHint.NOTHING, hint(1))
    }

    @Test
    fun `a single Haubi-Schlag is little`() {
        val s = playingWith(listOf("SO S8 S10 H9 E9", "SK SU H10 HU E10", "E7 HK HO S9 E8", "SA EA EK H8 G9"), Rank.OBER, Suit.GRAS)
        assertEquals(PartnerHint.LITTLE, hint(0, s.observationFor(0)))
    }

    @Test
    fun `Max and the Haubi are two tricks`() {
        val s = playingWith(listOf("HK GO S10 H9 E9", "SK SU H10 HU E10", "E7 SO HO S9 E8", "SA EA EK H8 G9"), Rank.OBER, Suit.GRAS)
        assertEquals(PartnerHint.TWO_TRICKS, hint(0, s.observationFor(0)))
    }

    @Test
    fun `when only one trick is missing a sure trick is all tricks`() {
        val obs = state.observationFor(0).copy(tricksWon = listOf(2, 0))
        assertEquals(PartnerHint.ALL_TRICKS, hint(0, obs))
    }

    @Test
    fun `played stronger trumps make a card stronger`() {
        val obs = state.observationFor(2)
        val afterKritten = obs.copy(currentTrick = listOf(Play(0, Card.MAX), Play(1, Card.BELLO)))
        assertTrue(PartnerHints.estimatedTricks(afterKritten) > PartnerHints.estimatedTricks(obs))
    }

    @Test
    fun `nothing to tell before the Haubi is fixed`() {
        val s = roundWith(hands)
        assertEquals(0.0, PartnerHints.estimatedTricks(s.observationFor(2)))
        assertEquals(PartnerHint.NOTHING, hint(2, s.observationFor(2)))
    }
}
