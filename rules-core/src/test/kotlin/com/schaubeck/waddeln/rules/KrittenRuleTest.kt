package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.KrittenRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KrittenRuleTest {
    // Dealer 3: player 0 leads. Schlag Ass + Farbe Gras; player 1 holds Max.
    private val hands = listOf(
        "S9 SA E8 H8 G9",
        "HK S8 SK E10 H9",
        "GA HA SU HO GO",
        "S10 EU EO G7 H10",
    )

    private fun allowed(s: GameState, player: Int) = KrittenRule.allowed(s.observationFor(player), Waddeln.legalActions(s, player))

    @Test
    fun `no Kritte into a first trick without trump`() {
        val s = playingWith(hands, Rank.ASS, Suit.GRAS).play("S9")
        val allowed = allowed(s, 1)
        assertTrue(Action.PlayCard(c("HK")) !in allowed)
        assertEquals(Waddeln.legalActions(s, 1) - Action.PlayCard(c("HK")), allowed)
    }

    @Test
    fun `free again once trump lies in the trick`() {
        val s = playingWith(hands, Rank.ASS, Suit.GRAS).play("G9")
        assertEquals(Waddeln.legalActions(s, 1), allowed(s, 1))
    }

    @Test
    fun `nothing else in hand, the Kritte may be played`() {
        val obs = playingWith(hands, Rank.ASS, Suit.GRAS).play("S9").observationFor(1)
        val onlyMax = listOf(Action.PlayCard(c("HK")), Action.Schub)
        assertEquals(onlyMax, KrittenRule.allowed(obs, onlyMax))
    }
}
