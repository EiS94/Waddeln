package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.DiscardRule
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscardRuleTest {
    // Dealer 3: player 0 leads. Schlag Ass + Farbe Gras: Gras Ass is Haubi, the other Asse are Haubi-Schlaege.
    private val hands = listOf(
        "SA S9 E8 H8 G9",
        "S8 SK EK E10 H9",
        "HA E9 SU HO GO",
        "S10 EU EO G7 H10",
    )

    private fun decide(state: GameState, player: Int, chosen: String): Action =
        DiscardRule.apply(state.observationFor(player), Waddeln.legalActions(state, player), Action.PlayCard(c(chosen)))

    @Test
    fun `no second Ass on the partner's Ass`() {
        // Partner 0 leads Schell Ass, opponent 1 does not take it: Herz Ass would only waste a trump
        val s = playingWith(hands, Rank.ASS, Suit.GRAS).play("SA", "S8")
        assertEquals(Action.PlayCard(c("E9")), decide(s, 2, "HA"))
    }

    @Test
    fun `cheaper choice is kept`() {
        val s = playingWith(hands, Rank.ASS, Suit.GRAS).play("SA", "S8")
        assertEquals(Action.PlayCard(c("E9")), decide(s, 2, "E9"))
    }

    @Test
    fun `no change when the opponents win the trick`() {
        // Opponent 1 leads; player 2 may well want to play high
        val s = playingWith(hands, Rank.ASS, Suit.GRAS).play("S9", "SK")
        assertEquals(Action.PlayCard(c("HA")), decide(s, 2, "HA"))
    }

    @Test
    fun `no change when the card takes the trick over`() {
        // Partner 0 leads Schell 9 (plain), opponent plays Schell 8: Schell Unter would take over within the team
        val s = playingWith(hands, Rank.ASS, Suit.GRAS).play("S9", "S8")
        assertEquals(Action.PlayCard(c("SU")), decide(s, 2, "SU"))
    }
}
