package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.teamOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextBotActorTest {
    /** Dealer 3: seat 0 announces the Schlag and leads; Farbe chosen; now seat 0 is to play. */
    private fun playing(): GameState {
        var s = Waddeln.newMatch(firstDealer = 3, deal = Deal.random(Random(1)))
        s = Waddeln.apply(s, 0, Action.AnnounceSchlag(Rank.OBER))
        return Waddeln.apply(s, 3, Action.AnnounceFarbe(Suit.GRAS))
    }

    @Test
    fun `the player to move acts if it is a bot`() {
        val s = playing()
        assertEquals(0, nextBotActor(s, humanSeats = setOf(1)))
        assertNull(nextBotActor(s, humanSeats = setOf(0)))
    }

    @Test
    fun `a Schub is answered by the human of the asked team`() {
        // Seat 1 (team 1) asks; team 0 = seats 0 and 2 answers.
        val s = Waddeln.apply(playing(), 1, Action.Schub)
        assertEquals(0, s.pendingSchub!!.answeringTeam)
        assertNull(nextBotActor(s, humanSeats = setOf(2)), "the bot at seat 0 must wait for its human partner")
        assertNull(nextBotActor(s, humanSeats = setOf(0)))
    }

    @Test
    fun `a Schub against two bots is answered by the next one in playing order`() {
        val s = Waddeln.apply(playing(), 0, Action.Schub)
        // Seat 0 is to play, team 1 (seats 1, 3) answers: seat 1 comes first.
        assertEquals(1, nextBotActor(s, humanSeats = setOf(0)))
        assertEquals(1, teamOf(nextBotActor(s, humanSeats = setOf(2))!!))
    }

    @Test
    fun `nobody acts after the round`() {
        var s = playing()
        s = Waddeln.apply(s, 1, Action.Schub)
        s = Waddeln.apply(s, 0, Action.Gehen)
        assertNull(nextBotActor(s, humanSeats = emptySet()))
    }
}
