package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.SchubManners
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchubMannersTest {
    // Dealer 3: player 0 leads. Schlag Ass + Farbe Gras. Team 0 (players 0 and 2) holds all Kritten.
    private val hands = listOf(
        "HK S7 E7 G9 S9",
        "S8 SK EK E10 H9",
        "GA SA HA SU G8",
        "S10 EU EO G7 H10",
    )

    /** Team 0 took the first two tricks with Max and Bello; player 0 leads the third. */
    private fun twoTricks(scores: List<Int> = listOf(0, 0)) =
        playingWith(hands, Rank.ASS, Suit.GRAS, scores = scores).play("HK", "S8", "G8", "S10", "S7", "SK", "SU", "EU")

    /** Always says Schub when it may, otherwise plays its first card. */
    private val schubber = Agent { _, legal -> if (Action.Schub in legal) Action.Schub else legal.first() }

    @Test
    fun `the deciding trick is sure with the highest card left`() {
        val s = twoTricks()
        assertTrue(SchubManners.decidingTrickSure(s.observationFor(0)), "Secher in hand is the highest card left")
        val led = s.play("E7")
        assertTrue(SchubManners.decidingTrickSure(led.observationFor(2)))
        assertFalse(SchubManners.decidingTrickSure(led.observationFor(1)), "not for the opponents")
    }

    @Test
    fun `not sure while an unseen card can still take the trick`() {
        val led = twoTricks().play("G9")
        assertFalse(SchubManners.decidingTrickSure(led.observationFor(0)))
        assertFalse(SchubManners.decidingTrickSure(playingWith(hands, Rank.ASS, Suit.GRAS).play("HK").observationFor(2)), "first trick")
    }

    @Test
    fun `no Schub with the deciding trick sure`() {
        val s = twoTricks()
        val legal = Waddeln.legalActions(s, 0)
        assertTrue(Action.Schub in legal)
        // Schub is taken away, so the base plays its first card
        assertEquals(Action.PlayCard(c("E7")), SchubManners(schubber).chooseAction(s.observationFor(0), legal))
    }

    @Test
    fun `raising is risky when losing would hand the opponents the match`() {
        val obs = playingWith(hands, Rank.ASS, Suit.GRAS, scores = listOf(0, 8)).observationFor(0)
        assertTrue(SchubManners.raiseLosesMatch(obs), "8 + 3 = 11")
        assertTrue(SchubManners.raiseLosesMatch(obs.copy(scores = listOf(6, 8))), "own score does not matter")
        assertTrue(SchubManners.raiseLosesMatch(obs.copy(scores = listOf(6, 7), gameValue = 3)), "7 + 4 = 11")
        assertFalse(SchubManners.raiseLosesMatch(obs.copy(scores = listOf(0, 7))), "7 + 3 = 10")
        assertFalse(SchubManners.raiseLosesMatch(obs.copy(scores = listOf(0, 9))), "they win the match with 2 already")
        assertFalse(SchubManners.raiseLosesMatch(playingWith(hands, Rank.ASS, Suit.GRAS, scores = listOf(8, 0)).observationFor(1).copy(scores = listOf(0, 8))))
    }

    @Test
    fun `win chance with open play`() {
        val s = twoTricks()
        assertEquals(1.0, SchubManners.winChance(s.observationFor(2), Random(1)))
        // The opponents cannot know where Secher and the Haubi lie, but would need all three tricks
        assertTrue(SchubManners.winChance(s.observationFor(1), Random(1)) < 0.3)
    }

    @Test
    fun `a risky Schub is kept the likelier the game is won, never quite predictably`() {
        assertEquals(SchubManners.BLUFF_CHANCE, SchubManners.keepChance(0.3))
        assertEquals(1.0, SchubManners.keepChance(0.95))
        assertTrue(SchubManners.keepChance(0.7) in 0.4..0.6)
    }

    @Test
    fun `a hopeless risky Schub is mostly dropped`() {
        // Team 1 against 8 points of team 0 has nothing: player 1 would hand them the match
        val s = playingWith(hands, Rank.ASS, Suit.GRAS, scores = listOf(8, 0)).play("HK")
        val legal = Waddeln.legalActions(s, 1)
        val random = Random(4)
        val schubs = (1..200).count { SchubManners(schubber, random).chooseAction(s.observationFor(1), legal) == Action.Schub }
        assertTrue(schubs in 1..30, "$schubs of 200")
    }

    @Test
    fun `the higher the game, the better it must look to keep a Schub`() {
        for (chance in listOf(0.5, 0.7, 0.9)) {
            val keep = (4..7).map { SchubManners.raiseChance(chance, it) }
            assertEquals(keep.sortedDescending(), keep, "at win chance $chance")
        }
        assertTrue(SchubManners.raiseChance(0.7, 4) > SchubManners.raiseChance(0.7, 5))
        assertTrue(SchubManners.raiseChance(0.9, 5) > SchubManners.raiseChance(0.9, 6))
    }

    @Test
    fun `a Schub to 3 is kept with a good game and often enough as a bluff with a bad one`() {
        assertEquals(1.0, SchubManners.raiseChance(0.9, 3))
        assertEquals(0.4, SchubManners.raiseChance(0.3, 3))
    }

    @Test
    fun `bluffs get rarer the higher the game is`() {
        val bluffs = (3..6).map { SchubManners.raiseChance(0.2, it) }
        assertEquals(bluffs.sortedDescending(), bluffs)
        assertEquals(bluffs.distinct(), bluffs)
        assertTrue(bluffs.last() > 0, "even that high it can happen")
    }

    @Test
    fun `a Schub to 6 or higher is a rare exception even with a sure game`() {
        assertEquals(0.3, SchubManners.raiseChance(1.0, 6))
        assertEquals(0.3, SchubManners.raiseChance(1.0, 9))
        assertEquals(0.01, SchubManners.raiseChance(0.7, 6), "hardly any bluffs that high")
    }

    @Test
    fun `a hopeless Schub to 3 is sometimes kept as a bluff, sometimes dropped`() {
        val s = playingWith(hands, Rank.ASS, Suit.GRAS).play("HK")
        val legal = Waddeln.legalActions(s, 1)
        val random = Random(4)
        val schubs = (1..200).count { SchubManners(schubber, random).chooseAction(s.observationFor(1), legal) == Action.Schub }
        assertTrue(schubs in 50..110, "$schubs of 200")
    }
}
