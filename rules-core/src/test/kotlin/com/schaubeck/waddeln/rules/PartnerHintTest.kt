package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.HintTrick
import com.schaubeck.waddeln.rules.agent.PartnerAnswer
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
    fun `a trick our partner surely takes already counts`() {
        // Player 1 has nothing, but partner 3 leads with the Max: nobody can beat it.
        val obs = state.observationFor(1).copy(tricksWon = listOf(0, 2), currentTrick = listOf(Play(3, Card.MAX)))
        assertEquals(1.0, PartnerHints.ourChance(obs))
        assertEquals(PartnerAnswer(PartnerHint.ALL_TRICKS, HintTrick.AFTER), PartnerHints.answerFor(obs))
    }

    @Test
    fun `a trick the opponents surely take counts for them`() {
        val before = state.observationFor(2).copy(tricksWon = listOf(1, 1))
        assertEquals(PartnerHint.ALL_TRICKS, hint(2, before))
        // Opponent 1 plays the Max: their trick, then they need only one more and nothing of 2 is sure.
        val obs = before.copy(currentTrick = listOf(Play(1, Card.MAX)))
        assertEquals(0.0, PartnerHints.ourChance(obs))
        assertEquals(PartnerAnswer(PartnerHint.TWO_TRICKS, HintTrick.NOT), PartnerHints.answerFor(obs))
    }

    @Test
    fun `the Bello of the partner is probably ours if only one opponent can still hold the Max`() {
        // Opponent 3 led, partner 0 put the Bello on it; only opponent 1 (5 cards) may still have the Max.
        val obs = state.observationFor(2).copy(currentTrick = listOf(Play(3, c("H8")), Play(0, Card.BELLO)))
        assertEquals(19.0 / 24, PartnerHints.ourChance(obs), 1e-9) // 24 unknown cards, 5 of them with player 1
        assertEquals(HintTrick.AFTER, PartnerHints.trickFor(obs))
    }

    @Test
    fun `a Haubi-Schlag of the partner is probably lost with three stronger trumps out`() {
        // Partner 0 leads a Haubi-Schlag; both opponents may hold Max, Bello or the Haubi.
        val obs = state.observationFor(2).copy(currentTrick = listOf(Play(0, c("EO"))))
        assertTrue(PartnerHints.ourChance(obs) in 0.1..0.3)
        assertEquals(HintTrick.NOT, PartnerHints.trickFor(obs))
    }

    @Test
    fun `the answering player can take the trick with his own hand`() {
        // Opponent 1 leads the Haubi; player 2 still holds the Secher.
        val obs = state.observationFor(2).copy(currentTrick = listOf(Play(1, c("GO"))))
        assertEquals(HintTrick.WITH, PartnerHints.trickFor(obs))
    }

    @Test
    fun `no trick being played means nothing to say about it`() {
        assertEquals(HintTrick.NONE, PartnerHints.trickFor(state.observationFor(2)))
    }

    @Test
    fun `nothing to tell before the Haubi is fixed`() {
        val s = roundWith(hands)
        assertEquals(0.0, PartnerHints.estimatedTricks(s.observationFor(2)))
        assertEquals(PartnerHint.NOTHING, hint(2, s.observationFor(2)))
    }
}
