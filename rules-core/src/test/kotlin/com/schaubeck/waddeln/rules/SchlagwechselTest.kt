package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.BotTalk
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.MatchRunner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchlagwechselTest {
    /** Dealer 3, so player 0 announces the Schlag. Player 0 has no two cards of one rank, the dealer two Unter. */
    private fun round(scores: List<Int> = listOf(0, 0), dealerHand: String = "EA EK H8 SU GU") = roundWith(
        listOf("SA S8 S10 H9 EO", "SK EU H10 HU E10", "GO GA G9 E8 H7", dealerHand),
        scores = scores,
    )

    private val ranks = Rank.entries.map { Action.AnnounceSchlag(it) }
    private val suits = Suit.entries.map { Action.AnnounceFarbe(it) }

    @Test
    fun `only the player after the dealer proposes, once and before the Schlag`() {
        val s = round()
        assertTrue(Waddeln.canProposeSchlagwechsel(s, 0))
        assertFalse((1..3).any { Waddeln.canProposeSchlagwechsel(s, it) })
        assertFailsWith<IllegalActionException> { Waddeln.proposeSchlagwechsel(s, 3) }
        assertFalse(Waddeln.canProposeSchlagwechsel(s.act(0, Action.AnnounceSchlag(Rank.ASS)), 0), "the Schlag is said")
        val declined = Waddeln.answerSchlagwechsel(Waddeln.proposeSchlagwechsel(s, 0), 3, false)
        assertFalse(Waddeln.canProposeSchlagwechsel(declined, 0), "once per round")
    }

    @Test
    fun `nobody announces until the dealer answered`() {
        val s = Waddeln.proposeSchlagwechsel(round(), 0)
        assertEquals(3, Waddeln.schlagwechselAnswerer(s))
        assertTrue((0 until PLAYER_COUNT).all { Waddeln.legalActions(s, it).isEmpty() })
        assertFailsWith<IllegalActionException> { Waddeln.answerSchlagwechsel(s, 0, true) }
        assertEquals(SchlagwechselTalk(), s.observationFor(1).schlagwechsel, "everybody hears it")
    }

    @Test
    fun `accepted, the dealer says the Schlag and the player after him the Farbe, who still leads`() {
        var s = Waddeln.answerSchlagwechsel(Waddeln.proposeSchlagwechsel(round(), 0), 3, true)
        assertNull(Waddeln.schlagwechselAnswerer(s))
        assertEquals(3, s.playerToMove)
        assertEquals(ranks, Waddeln.legalActions(s, 3))
        assertTrue(Waddeln.legalActions(s, 0).isEmpty())
        s = s.act(3, Action.AnnounceSchlag(Rank.UNTER))
        assertEquals(0, s.playerToMove)
        assertEquals(suits, Waddeln.legalActions(s, 0))
        assertTrue(Waddeln.legalActions(s, 3).isEmpty())
        s = s.act(0, Action.AnnounceFarbe(Suit.SCHELL))
        assertEquals(Phase.PLAYING, s.phase)
        assertEquals(Trump(Rank.UNTER, Suit.SCHELL), s.trump)
        assertEquals(0, s.playerToMove, "the first card is still played by the player after the dealer")
    }

    @Test
    fun `declined, everything stays as usual`() {
        var s = Waddeln.answerSchlagwechsel(Waddeln.proposeSchlagwechsel(round(), 0), 3, false)
        assertEquals(ranks, Waddeln.legalActions(s, 0))
        s = s.act(0, Action.AnnounceSchlag(Rank.ASS))
        assertEquals(suits, Waddeln.legalActions(s, 3))
    }

    @Test
    fun `the score does not matter`() {
        assertTrue(Waddeln.canProposeSchlagwechsel(round(scores = listOf(9, 10)), 0))
    }

    @Test
    fun `bots propose without a pair and the dealer accepts with one`() {
        val accepted = BotTalk.schlagwechselBeforeTurn(round())
        assertEquals(SchlagwechselTalk(true), accepted.schlagwechsel)
        val declined = BotTalk.schlagwechselBeforeTurn(round(dealerHand = "EA EK H8 SU G10"))
        assertEquals(SchlagwechselTalk(false), declined.schlagwechsel)
        val withPair = roundWith(listOf("SA SU S10 H9 E9", "SK EU H10 HU E10", "GO GA G9 E8 H7", "EA EK H8 S9 GU"))
        assertNull(BotTalk.schlagwechselBeforeTurn(withPair).schlagwechsel, "an Unter pair: no proposal")
    }

    @Test
    fun `bots play complete matches with Schlagwechsel`() {
        var swapped = 0
        repeat(20) { seed ->
            MatchRunner(List(PLAYER_COUNT) { RuleBasedAgent() }, Random(seed), onAction = { before, _, _ ->
                if (before.phase == Phase.SCHLAG && before.schlagwechsel?.accepted == true) swapped++
            }).playMatch()
        }
        assertTrue(swapped > 0)
    }
}
