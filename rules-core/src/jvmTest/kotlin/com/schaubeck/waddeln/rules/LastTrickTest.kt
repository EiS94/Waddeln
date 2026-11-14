package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.LastTrick
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastTrickTest {
    // Haubi Gras Ober. Max and Bello are with players 0 and 3 here, but player 1 does not know that.
    private val state = playingWith(
        listOf("HK S8 S10 H9 E9", "SK SU H10 HU E10", "E7 SO HO S9 E8", "SA EA EK H8 G9"),
        Rank.OBER,
        Suit.GRAS,
    )

    /** Player 1 in the last trick, holding [card] (or nothing); partner 3 has played his last card. */
    private fun lastTrick(trick: List<Play>, card: String? = "SK"): Observation {
        val played = trick.map { it.player }
        return state.observationFor(1).copy(
            hand = listOfNotNull(card?.let(::c)),
            handSizes = List(PLAYER_COUNT) { p -> if (p in played || (p == 1 && card == null)) 0 else 1 },
            tricksWon = listOf(2, 2),
            currentTrick = trick,
        )
    }

    @Test
    fun `the partner's Max takes the trick for sure, so we stay`() {
        val obs = lastTrick(listOf(Play(3, Card.MAX)))
        assertEquals(1.0, LastTrick.ourChance(obs))
        assertEquals(Action.Bleiben, RuleBasedAgent().chooseAction(obs, listOf(Action.Bleiben, Action.Gehen)))
    }

    @Test
    fun `the opponents' Max beats everything we have, so we go`() {
        val obs = lastTrick(listOf(Play(2, Card.MAX), Play(3, c("S8"))))
        assertEquals(0.0, LastTrick.ourChance(obs))
        assertEquals(Action.Gehen, RuleBasedAgent().chooseAction(obs, listOf(Action.Bleiben, Action.Gehen)))
    }

    @Test
    fun `the partner's Secher is probably enough if only two single cards are still out`() {
        // 29 unknown cards, Max and Bello among them; the two opponents hold one card each.
        val obs = lastTrick(listOf(Play(3, Card.SECHER)))
        assertEquals(27.0 / 29 * 26 / 28, LastTrick.ourChance(obs)!!, 1e-9)
        assertTrue(LastTrick.shouldStay(obs)!!)
    }

    @Test
    fun `our own last card counts too`() {
        // Opponent 2 leads the Haubi, partner 3 could not beat it, but we still hold the Max.
        val obs = lastTrick(listOf(Play(2, c("GO")), Play(3, c("S8"))), card = "HK")
        assertEquals(1.0, LastTrick.ourChance(obs))
    }

    @Test
    fun `while the partner still holds cards the usual decision applies`() {
        assertNull(LastTrick.ourChance(state.observationFor(1)))
    }

    @Test
    fun `nobody can be asked who has no cards any more`() {
        val schub = state.copy(pendingSchub = PendingSchub(askingPlayer = 0))
        assertTrue(Waddeln.canGiveHint(schub, 3))
        val partnerEmpty = schub.copy(hands = schub.hands.mapIndexed { p, h -> if (p == 3) emptyList() else h })
        assertFalse(Waddeln.canGiveHint(partnerEmpty, 3))
    }
}
