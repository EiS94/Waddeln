package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Schadet
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchadetTest {
    /** Haubi Gras Ober; player 0 leads and holds it. Player 2 (partner) gets [partnerHand]. */
    private fun state(partnerHand: String) = playingWith(
        listOf("GO S8 S10 H9 E9", "SK SU H10 HU E10", partnerHand, "SA EA EK H8 S9"),
        Rank.OBER, Suit.GRAS,
    )

    private fun answer(partnerHand: String) = Schadet.answerFor(state(partnerHand).observationFor(2))

    @Test
    fun `no Haubi-Farbe or three and more do not hurt`() {
        assertEquals(SchadetAnswer.NO, answer("E7 SO HO E8 H7"))
        assertEquals(SchadetAnswer.NO, answer("GA GK G9 E8 H7"))
    }

    @Test
    fun `one or two high Haubi-Farbe cards hurt`() {
        assertEquals(SchadetAnswer.YES, answer("GA E8 H7 HA EO"))
        assertEquals(SchadetAnswer.YES, answer("GA GK E8 H7 HA"))
    }

    @Test
    fun `medium cards hurt a little, low ones not`() {
        assertEquals(SchadetAnswer.A_LITTLE, answer("GU GA E8 H7 HA"))
        assertEquals(SchadetAnswer.NO, answer("G8 G9 E8 H7 HA"))
        assertEquals(SchadetAnswer.NO, answer("G7 GA E8 H7 HA"), "the low card is given, the Ass kept")
    }

    @Test
    fun `only the player leading the first trick with the Haubi may ask`() {
        val s = state("E7 SO HO E8 H7")
        assertTrue(Schadet.canAsk(s, 0))
        assertFalse(Schadet.canAsk(s, 2))
        assertTrue(Schadet.leadsHaubi(s, 0, Action.PlayCard(c("GO"))))
        assertFalse(Schadet.leadsHaubi(s, 0, Action.PlayCard(c("S8"))))
        assertFalse(Schadet.canAsk(s.play("S8"), 1))
    }

    @Test
    fun `on yes the asking bot does not lead the Haubi`() {
        val s = state("GA E8 H7 HA EO")
        val legal = Waddeln.legalActions(s, 0)
        val after = Schadet.actionsAfter(SchadetAnswer.YES, s, 0, legal)
        assertFalse(Action.PlayCard(c("GO")) in after)
        assertTrue(after.isNotEmpty() && after.all { it is Action.PlayCard })
        assertEquals(legal, Schadet.actionsAfter(SchadetAnswer.A_LITTLE, s, 0, legal))
    }
}
