package com.schaubeck.waddeln.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dealer 3, so player 0 announces the Schlag and opens; teams are {0, 2} and {1, 3}. */
val STANDARD_HANDS = listOf(
    "HK GO SA S9 E8",
    "S7 HO SK S8 EA",
    "E7 GA SU S10 H9",
    "SO G9 HA EK H8",
)

class RoundFlowTest {

    @Test
    fun `player after dealer announces Schlag, dealer announces Farbe`() {
        val s = roundWith(STANDARD_HANDS, dealer = 3)
        assertEquals(Phase.SCHLAG, s.phase)
        assertEquals(0, s.playerToMove)
        assertEquals(8, Waddeln.legalActions(s, 0).size)
        (1..3).forEach { assertTrue(Waddeln.legalActions(s, it).isEmpty()) }

        val s2 = s.act(0, Action.AnnounceSchlag(Rank.OBER))
        assertEquals(Phase.FARBE, s2.phase)
        assertEquals(Suit.entries.map { Action.AnnounceFarbe(it) }, Waddeln.legalActions(s2, 3))
        (0..2).forEach { assertTrue(Waddeln.legalActions(s2, it).isEmpty()) }

        val s3 = s2.act(3, Action.AnnounceFarbe(Suit.GRAS))
        assertEquals(Phase.PLAYING, s3.phase)
        assertEquals(c("GO"), s3.trump!!.haubi)
        assertEquals(0, s3.playerToMove)
    }

    @Test
    fun `forehand rotates with the dealer`() {
        val s = roundWith(STANDARD_HANDS, dealer = 1)
        assertEquals(2, s.forehand)
        assertEquals(0, s.abheber)
        assertEquals(2, s.playerToMove)
    }

    @Test
    fun `only the player to move may play, and only own cards`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS)
        assertFailsWith<IllegalActionException> { s.act(1, Action.PlayCard(c("SK"))) }
        assertFailsWith<IllegalActionException> { s.act(0, Action.PlayCard(c("SK"))) }
        val s2 = s.act(0, Action.PlayCard(c("SA")))
        assertEquals(1, s2.playerToMove)
        assertEquals(4, s2.hands[0].size)
    }

    @Test
    fun `free choice when Haubi is not led`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("SA")
        assertEquals(cards("S7 HO SK S8 EA").toSet(), Waddeln.playableCards(s, 1).toSet())
    }

    @Test
    fun `full round - trick winner leads, first team with 3 tricks scores 2`() {
        var s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS)
        s = s.play("SA", "SK", "S10", "H8")
        assertEquals(0, s.completedTricks.last().winner)
        assertEquals(0, s.playerToMove)
        s = s.play("S9", "S8", "SU", "EK")
        assertEquals(2, s.completedTricks.last().winner)
        assertEquals(2, s.playerToMove)
        s = s.play("H9", "HA", "E8", "EA")
        assertEquals(3, s.completedTricks.last().winner)
        assertEquals(listOf(2, 1), s.tricksWon)
        s = s.play("G9", "HK", "S7", "E7")
        assertEquals(Phase.ROUND_OVER, s.phase)
        assertEquals(RoundResult(0, 2, RoundEndReason.TRICKS), s.result)
        assertEquals(listOf(2, 0), s.scores)
        assertNull(s.playerToMove)
        (0..3).forEach { assertTrue(Waddeln.legalActions(s, it).isEmpty()) }
    }
}

class BekennenTest {

    @Test
    fun `Haubi led first in first trick forces the Haubi-Farbe or a Kritte`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("GO")
        // Player 1 (S7 HO SK S8 EA) has no Gras card: free choice, the Haubi-Schlag HO is not required
        assertEquals(5, Waddeln.playableCards(s, 1).size)
        val s2 = s.play("HO")
        // Player 2 (E7 GA SU S10 H9) holds Gras Ass: Gras Ass or the Kritte Secher
        assertEquals(cards("E7 GA").toSet(), Waddeln.playableCards(s2, 2).toSet())
        assertFailsWith<IllegalActionException> { s2.act(2, Action.PlayCard(c("SU"))) }
        val s3 = s2.play("GA")
        // Player 3 (SO G9 HA EK H8): only Gras 9; Schell Ober (Haubi-Schlag) is not Haubi-Farbe
        assertEquals(cards("G9").toSet(), Waddeln.playableCards(s3, 3).toSet())
    }

    @Test
    fun `a Kritte played after the Haubi lifts the Bekennen-Pflicht`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("GO", "S7")
        assertTrue(!Waddeln.isBekennenPflicht(s))
        assertEquals(5, Waddeln.playableCards(s, 2).size)
        assertEquals(5, Waddeln.playableCards(s.play("SU"), 3).size)
    }

    @Test
    fun `Kritte may always be played under Bekennen-Pflicht`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("GO")
        s.act(1, Action.PlayCard(Card.BELLO))
    }

    @Test
    fun `player without trump may play anything`() {
        val hands = listOf(
            "GO HK SA S9 E8",
            "SK S8 EA E9 H10",
            "E7 GA SU S10 H9",
            "SO G9 HA EK H8",
        )
        val s = playingWith(hands, Rank.OBER, Suit.GRAS).play("GO")
        assertEquals(cards("SK S8 EA E9 H10").toSet(), Waddeln.playableCards(s, 1).toSet())
    }

    @Test
    fun `Haubi not led as first card does not force`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("SA")
        assertEquals(5, Waddeln.playableCards(s, 1).size)
        // Haubi played second in the trick: still free choice for the rest
        val hands = listOf("SA HK S9 E8 H7", "GO S7 HO SK S8", "E7 GA SU S10 H9", "SO G9 HA EK H8")
        val s2 = playingWith(hands, Rank.OBER, Suit.GRAS).play("SA", "GO")
        assertEquals(5, Waddeln.playableCards(s2, 2).size)
    }

    @Test
    fun `other trump led first does not force`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("HK")
        assertEquals(5, Waddeln.playableCards(s, 1).size)
    }

    @Test
    fun `Haubi led in a later trick does not force`() {
        var s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS)
        s = s.play("SA", "SK", "S10", "H8") // player 0 wins and leads again
        s = s.play("GO")
        assertEquals(4, Waddeln.playableCards(s, 1).size)
        assertTrue(Waddeln.playableCards(s, 1).contains(c("EA")))
    }

    @Test
    fun `Kritte as Haubi led first forces the Haubi-Farbe too`() {
        // Schlag Koenig + Farbe Herz = Max, which is Haubi and Kritte at once
        val s = playingWith(STANDARD_HANDS, Rank.KOENIG, Suit.HERZ)
        assertEquals(Card.MAX, s.trump!!.haubi)
        val s2 = s.play("HK")
        // Player 1 holds S7 HO SK S8 EA: Herz Ober (Haubi-Farbe) or the Kritte Bello
        assertEquals(cards("S7 HO").toSet(), Waddeln.playableCards(s2, 1).toSet())
        // A Kritte played afterwards lifts the obligation: player 2 (E7 GA SU S10 H9) may play anything
        assertEquals(5, Waddeln.playableCards(s2.play("S7"), 2).size)
    }

    @Test
    fun `Kritte that is not the Haubi led first does not force`() {
        val hands = listOf("S7 HK GO S9 E8", "E7 HO SK S8 EA", "H7 GA SU S10 H9", "SO G9 HA EK H8")
        val s = playingWith(hands, Rank.KOENIG, Suit.HERZ).play("S7")
        assertEquals(5, Waddeln.playableCards(s, 1).size)
    }
}

class SchubTest {
    private fun start(scores: List<Int> = listOf(0, 0)) =
        playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS, scores = scores)

    @Test
    fun `no Schub before the Haubi is fixed`() {
        val s = roundWith(STANDARD_HANDS)
        (0..3).forEach { assertTrue(Action.Schub !in Waddeln.legalActions(s, it)) }
        val s2 = s.act(0, Action.AnnounceSchlag(Rank.OBER))
        (0..3).forEach { assertTrue(Action.Schub !in Waddeln.legalActions(s2, it)) }
    }

    @Test
    fun `any player may announce Schub at any time, also when not to move`() {
        val s = start()
        (0..3).forEach { assertTrue(Action.Schub in Waddeln.legalActions(s, it)) }
        assertEquals(listOf(Action.Schub), Waddeln.legalActions(s, 1))
        val mid = s.play("SA", "SK")
        assertTrue(Action.Schub in Waddeln.legalActions(mid, 3))
    }

    @Test
    fun `either player of the asked team answers, card play is suspended`() {
        val s = start().act(1, Action.Schub)
        assertEquals(PendingSchub(1), s.pendingSchub)
        assertEquals(listOf(Action.Bleiben, Action.Gehen), Waddeln.legalActions(s, 0))
        assertEquals(listOf(Action.Bleiben, Action.Gehen), Waddeln.legalActions(s, 2))
        assertTrue(Waddeln.legalActions(s, 1).isEmpty())
        assertTrue(Waddeln.legalActions(s, 3).isEmpty())
        assertFailsWith<IllegalActionException> { s.act(0, Action.PlayCard(c("SA"))) }
        assertFailsWith<IllegalActionException> { s.act(0, Action.Schub) }
        // Second answer is not possible: the first one counts for the team
        val answered = s.act(2, Action.Bleiben)
        assertFailsWith<IllegalActionException> { answered.act(0, Action.Gehen) }
    }

    @Test
    fun `Gehen ends the game with the current value for the asking team`() {
        val s = start().act(1, Action.Schub).act(2, Action.Gehen)
        assertEquals(Phase.ROUND_OVER, s.phase)
        assertEquals(RoundResult(1, 2, RoundEndReason.GIVEN_UP), s.result)
        assertEquals(listOf(0, 2), s.scores)
    }

    @Test
    fun `Bleiben raises value by 1 and teams must alternate`() {
        var s = start().act(1, Action.Schub).act(0, Action.Bleiben)
        assertEquals(3, s.gameValue)
        assertNull(s.pendingSchub)
        assertTrue(Action.Schub !in Waddeln.legalActions(s, 1))
        assertTrue(Action.Schub !in Waddeln.legalActions(s, 3))
        assertTrue(Action.Schub in Waddeln.legalActions(s, 2))

        s = s.act(2, Action.Schub).act(3, Action.Gehen)
        assertEquals(RoundResult(0, 3, RoundEndReason.GIVEN_UP), s.result)
        assertEquals(listOf(3, 0), s.scores)
    }

    @Test
    fun `winning by tricks after n Schübe gives 2 + n`() {
        var s = start()
        s = s.act(0, Action.Schub).act(1, Action.Bleiben)
        s = s.play("SA", "SK")
        s = s.act(3, Action.Schub).act(2, Action.Bleiben)
        s = s.play("S10", "H8", "S9", "S8", "SU", "EK", "H9", "HA", "E8", "EA", "G9", "HK", "S7", "E7")
        assertEquals(RoundResult(0, 4, RoundEndReason.TRICKS), s.result)
        assertEquals(listOf(4, 0), s.scores)
    }

    @Test
    fun `gespannt team may not announce Schub but may answer`() {
        val s = start(scores = listOf(9, 4))
        assertTrue(Action.Schub !in Waddeln.legalActions(s, 0))
        assertTrue(Action.Schub !in Waddeln.legalActions(s, 2))
        val asked = s.act(1, Action.Schub)
        assertEquals(listOf(Action.Bleiben, Action.Gehen), Waddeln.legalActions(asked, 0))
        val gone = asked.act(0, Action.Gehen)
        assertEquals(listOf(9, 6), gone.scores)
    }

    @Test
    fun `against a gespannt team only one Schub per game is possible`() {
        val s = start(scores = listOf(4, 10)).act(0, Action.Schub).act(3, Action.Bleiben)
        assertEquals(3, s.gameValue)
        (0..3).forEach { assertTrue(Action.Schub !in Waddeln.legalActions(s, it)) }
    }

    @Test
    fun `both teams gespannt means no Schub at all`() {
        val s = start(scores = listOf(9, 10))
        (0..3).forEach { assertTrue(Action.Schub !in Waddeln.legalActions(s, it)) }
    }

    @Test
    fun `no Schub once winning the game would already give 11 points`() {
        // 7 points and the game is worth 4: a win reaches 11, so team 0 may not ask any more
        val worth4 = start(scores = listOf(7, 0)).copy(gameValue = 4)
        assertTrue(Action.Schub !in Waddeln.legalActions(worth4, 0))
        assertTrue(Action.Schub !in Waddeln.legalActions(worth4, 2))
        assertTrue(Action.Schub in Waddeln.legalActions(worth4, 1))
        // Worth 3: 7 + 3 = 10, still allowed
        val worth3 = start(scores = listOf(7, 0)).copy(gameValue = 3)
        assertTrue(Action.Schub in Waddeln.legalActions(worth3, 0))
    }

    @Test
    fun `Schub limit applies after values were raised by earlier Schuebe`() {
        var s = start(scores = listOf(7, 3))
        s = s.act(0, Action.Schub).act(1, Action.Bleiben)  // value 3
        s = s.act(1, Action.Schub).act(0, Action.Bleiben)  // value 4, team 0 would be next
        assertEquals(4, s.gameValue)
        assertTrue(Action.Schub !in Waddeln.legalActions(s, 0))
    }
}

class GespanntGegenGespanntTest {

    @Test
    fun `Kritten and Haubi are laid out, other Haubi-Schlaege are not`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS, scores = listOf(9, 10))
        assertEquals(listOf(setOf(c("HK"), c("GO")), setOf(c("S7")), setOf(c("E7")), emptySet()), s.laidOut)
        assertEquals(listOf(2, 1, 1, 0), s.observationFor(1).laidOutCounts)
        assertEquals(setOf(c("HK"), c("GO")), s.observationFor(0).ownLaidOut)
        assertEquals(setOf(c("S7")), s.observationFor(1).ownLaidOut)
    }

    @Test
    fun `laid out cards are played normally and leave the table`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS, scores = listOf(9, 10)).play("HK")
        assertEquals(listOf(1, 1, 1, 0), s.observationFor(2).laidOutCounts)
    }

    @Test
    fun `Kritte as Haubi lays out only the Kritten`() {
        val s = playingWith(STANDARD_HANDS, Rank.SEVEN, Suit.SCHELL, scores = listOf(9, 9))
        assertEquals(listOf(setOf(c("HK")), setOf(c("S7")), setOf(c("E7")), emptySet()), s.laidOut)
    }

    @Test
    fun `nothing is laid out unless both teams are gespannt`() {
        assertNull(playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS, scores = listOf(9, 8)).laidOut)
        assertNull(playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).observationFor(0).laidOutCounts)
    }
}

class MatchTest {

    @Test
    fun `next round rotates the dealer clockwise and keeps scores`() {
        val over = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS, dealer = 3)
            .act(1, Action.Schub).act(0, Action.Gehen)
        val next = Waddeln.startNextRound(over, dealFor(0, STANDARD_HANDS.map(::cards)))
        assertEquals(0, next.dealer)
        assertEquals(1, next.forehand)
        assertEquals(2, next.roundNumber)
        assertEquals(listOf(0, 2), next.scores)
        assertEquals(Phase.SCHLAG, next.phase)
        assertEquals(BASE_GAME_VALUE, next.gameValue)
        assertNull(next.lastSchubTeam)
    }

    @Test
    fun `next round only after round is over`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS)
        assertFailsWith<IllegalArgumentException> { Waddeln.startNextRound(s, dealFor(0, STANDARD_HANDS.map(::cards))) }
    }

    @Test
    fun `match ends when a team reaches 11`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS, scores = listOf(10, 7))
            .act(1, Action.Schub).act(0, Action.Gehen)
        assertEquals(listOf(10, 9), s.scores)
        assertEquals(Phase.ROUND_OVER, s.phase)
        assertNull(Waddeln.matchWinner(s))

        val won = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS, scores = listOf(10, 7))
            .play("SA", "SK", "S10", "H8", "S9", "S8", "SU", "EK", "H9", "HA", "E8", "EA", "G9", "HK", "S7", "E7")
        assertEquals(listOf(12, 7), won.scores)
        assertEquals(Phase.MATCH_OVER, won.phase)
        assertEquals(0, Waddeln.matchWinner(won))
        assertFailsWith<IllegalArgumentException> { Waddeln.startNextRound(won, dealFor(0, STANDARD_HANDS.map(::cards))) }
    }
}

class ObservationTest {

    @Test
    fun `observation shows only own hand and public information`() {
        val s = roundWith(
            listOf("SO SU S8 S9 S10", "E7 HA H9 EA EK", "HK S7 GO G9 SA", "G7 G8 GU HO EO"),
            dealer = 3,
            abheberKritten = 2,
        )
        val obs = s.observationFor(1)
        assertEquals(cards("E7 HA H9 EA EK"), obs.hand)
        assertEquals(listOf(5, 5, 5, 5), obs.handSizes)
        assertEquals(2, obs.abheberKritten)
        assertEquals(2, obs.abheber)
        assertEquals(s.visibleBottomCard, obs.visibleBottomCard)
        assertTrue(s.visibleBottomCard !in s.hands.flatten())
    }
}
