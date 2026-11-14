package com.schaubeck.waddeln.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardsTest {
    @Test
    fun `deck has 32 distinct cards with stable indices`() {
        assertEquals(32, Card.ALL.toSet().size)
        Card.ALL.forEachIndexed { i, card -> assertEquals(i, card.index); assertEquals(card, Card.of(i)) }
    }

    @Test
    fun `kritten are Herz Koenig, Schell 7 and Eichel 7`() {
        assertEquals(c("HK"), Card.MAX)
        assertEquals(c("S7"), Card.BELLO)
        assertEquals(c("E7"), Card.SECHER)
        assertEquals(setOf(c("HK"), c("S7"), c("E7")), Card.ALL.filter { it.isKritte }.toSet())
    }

    @Test
    fun `parse is inverse of toString`() {
        Card.ALL.forEach { assertEquals(it, Card.parse(it.toString())) }
    }
}

class TrumpTest {
    private val grasOber = Trump(Rank.OBER, Suit.GRAS)

    @Test
    fun `hierarchy for Gras Ober`() {
        val expected = listOf("HK", "S7", "E7", "GO") // Max, Bello, Secher, Haubi
        assertEquals(expected.map(::c), grasOber.trumpCards().take(4))
        assertEquals(c("GO"), grasOber.haubi)
        // Haubi-Schläge are equal and below the Haubi
        val schlaege = listOf("SO", "HO", "EO").map(::c)
        schlaege.forEach { assertTrue(grasOber.isHaubiSchlag(it)) }
        assertEquals(1, schlaege.map { grasOber.strength(it) }.toSet().size)
        assertTrue(grasOber.strength(c("GO")) > grasOber.strength(c("SO")))
        // Haubi-Farbe from Ass down to 7, below the Schläge
        val farbe = cards("GA GK G10 G9 G8 G7 GU")
        assertTrue(farbe.all { grasOber.isHaubiFarbe(it) })
        assertTrue(grasOber.strength(c("SO")) > grasOber.strength(c("GA")))
        assertEquals(cards("GA GK GU G10 G9 G8 G7"), farbe.sortedByDescending { grasOber.strength(it) })
        // 3 Kritten + Haubi + 3 Schläge + 7 Farbe
        assertEquals(14, grasOber.trumpCards().size)
    }

    @Test
    fun `other cards are not trump`() {
        assertFalse(grasOber.isTrump(c("SA")))
        assertFalse(grasOber.isTrump(c("HA")))
        assertFalse(grasOber.isTrump(c("EU")))
        assertEquals(Trump.NO_TRUMP, grasOber.strength(c("SA")))
    }

    @Test
    fun `Kritte as Haubi keeps its Kritte rank, Schlaege and Farbe stay trump`() {
        val secher = Trump(Rank.SEVEN, Suit.EICHEL)
        assertEquals(Card.SECHER, secher.haubi)
        assertEquals(98, secher.strength(Card.SECHER))
        assertEquals(cards("H7 G7").toSet(), Card.ALL.filter { secher.isHaubiSchlag(it) }.toSet())
        assertEquals(cards("E8 E9 E10 EU EO EK EA").toSet(), Card.ALL.filter { secher.isHaubiFarbe(it) }.toSet())
        // Rank 4 stays empty: Kritten are followed directly by the Haubi-Schlaege
        assertEquals(Card.KRITTEN + cards("H7 G7"), secher.trumpCards().take(5))
        assertEquals(3 + 2 + 7, secher.trumpCards().size)

        val max = Trump(Rank.KOENIG, Suit.HERZ)
        assertEquals(Card.MAX, max.haubi)
        assertEquals(cards("SK EK GK").toSet(), Card.ALL.filter { max.isHaubiSchlag(it) }.toSet())
        assertEquals(cards("H7 H8 H9 H10 HU HO HA").toSet(), Card.ALL.filter { max.isHaubiFarbe(it) }.toSet())

        val bello = Trump(Rank.SEVEN, Suit.SCHELL)
        assertEquals(cards("H7 G7").toSet(), Card.ALL.filter { bello.isHaubiSchlag(it) }.toSet())
        assertEquals(3 + 2 + 7, bello.trumpCards().size)
    }

    @Test
    fun `Kritten among Haubi-Schlaege stay Kritten`() {
        val trump = Trump(Rank.SEVEN, Suit.HERZ)
        assertEquals(c("H7"), trump.haubi)
        assertFalse(trump.isHaubiSchlag(c("S7")))
        assertFalse(trump.isHaubiSchlag(c("E7")))
        assertTrue(trump.isHaubiSchlag(c("G7")))
        assertEquals(Card.KRITTEN + c("H7") + c("G7"), trump.trumpCards().take(5))
    }

    @Test
    fun `Max is not part of the Haubi-Farbe Herz`() {
        val trump = Trump(Rank.UNTER, Suit.HERZ)
        assertFalse(trump.isHaubiFarbe(c("HK")))
        assertEquals(100, trump.strength(c("HK")))
        assertTrue(trump.isHaubiFarbe(c("HA")))
    }

    @Test
    fun `Max is not a Haubi-Schlag when Schlag is Koenig`() {
        val trump = Trump(Rank.KOENIG, Suit.GRAS)
        assertFalse(trump.isHaubiSchlag(c("HK")))
        assertEquals(cards("SK EK").toSet(), Card.ALL.filter { trump.isHaubiSchlag(it) }.toSet())
    }
}

class TrickEvaluationTest {
    private val grasOber = Trump(Rank.OBER, Suit.GRAS)

    private fun winner(trick: String, trump: Trump = grasOber) = trickWinnerIndex(cards(trick), trump)

    @Test
    fun `highest card of led suit wins without trump`() {
        assertEquals(2, winner("S9 S8 SA SK"))
    }

    @Test
    fun `off-suit cards cannot win even if higher`() {
        assertEquals(0, winner("S8 HA EA H9"))
        assertEquals(3, winner("E8 HA SA EK"))
    }

    @Test
    fun `any trump beats the led suit`() {
        assertEquals(1, winner("SA G7 SK S10"))
    }

    @Test
    fun `Kritten order Max over Bello over Secher`() {
        assertEquals(2, winner("E7 S7 HK GO"))
        assertEquals(1, winner("E7 S7 GO SO"))
    }

    @Test
    fun `Haubi beats Haubi-Schlaege which beat Haubi-Farbe`() {
        assertEquals(2, winner("GA SO GO HO"))
        assertEquals(1, winner("GA SO GK G10"))
    }

    @Test
    fun `first played Haubi-Schlag wins on tie`() {
        assertEquals(1, winner("GA HO SO EO"))
        assertEquals(0, winner("EO HO SO GA"))
    }

    @Test
    fun `Haubi-Farbe ranks Ass down to 7`() {
        assertEquals(3, winner("G7 G9 GU GA"))
    }

    @Test
    fun `with a Kritte as Haubi the Schlaege and the Farbe are still trump`() {
        val max = Trump(Rank.KOENIG, Suit.HERZ)
        // SK and EK are Haubi-Schlaege (equal, first wins), H9 and HA Haubi-Farbe
        assertEquals(1, winner("H9 SK HA EK", max))
        assertEquals(1, winner("HA E7 GK SK", max))
        assertEquals(2, winner("SA EA H7 S10", max))
        assertEquals(3, winner("GK SK EK HK", max))
    }

    @Test
    fun `led trump is beaten by higher trump only`() {
        assertEquals(0, winner("GO GA SA HO"))
        assertEquals(1, winner("G9 GK SA EA"))
    }
}
