package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.RoundEndReason
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.RandomAgent
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.teamOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StatsTest {
    private val me = 0

    /** What really happened, counted directly from the actions. */
    private class Truth {
        var rounds = 0
        var roundsWon = 0
        var pointsWon = 0
        var pointsLost = 0
        var ownTricks = 0
        var schlag = 0
        var farbe = 0
        var mySchubs = 0
        var mySchubsWent = 0
        var facedWent = 0
        var facedStayed = 0
    }

    /**
     * Plays [matches] random matches and feeds every observation of [me] (each one twice, like
     * repeated online messages) into a tracker.
     */
    private fun play(matches: Int, seed: Int): Pair<Stats, Truth> {
        val random = Random(seed)
        val agents = List(4) { RandomAgent(random) }
        var stats = Stats()
        val tracker = StatsTracker { stats = stats.with(it) }
        val truth = Truth()
        repeat(matches) {
            var state = Waddeln.newMatch(random)
            while (state.phase != Phase.MATCH_OVER) {
                if (state.phase == Phase.ROUND_OVER) {
                    state = Waddeln.startNextRound(state, Deal.random(random))
                } else {
                    val actor = actor(state)
                    val action = agents[actor].chooseAction(state.observationFor(actor), Waddeln.legalActions(state, actor))
                    count(truth, state, actor, action)
                    state = Waddeln.apply(state, actor, action)
                    if (state.result != null) countRound(truth, state)
                }
                repeat(2) { tracker.observe(state.observationFor(me)) }
            }
        }
        return stats to truth
    }

    private fun actor(state: GameState): Int {
        val toMove = state.playerToMove!!
        val pending = state.pendingSchub ?: return toMove
        return generateSequence(toMove, ::nextPlayer).first { teamOf(it) == pending.answeringTeam }
    }

    private fun count(truth: Truth, state: GameState, actor: Int, action: Action) {
        val pending = state.pendingSchub
        when {
            action == Action.Schub && actor == me -> truth.mySchubs++
            action == Action.Gehen && pending!!.askingPlayer == me -> truth.mySchubsWent++
            action == Action.Gehen && teamOf(actor) == teamOf(me) -> truth.facedWent++
            action == Action.Bleiben && teamOf(actor) == teamOf(me) -> truth.facedStayed++
            action is Action.AnnounceSchlag && actor == me -> truth.schlag++
            action is Action.AnnounceFarbe && actor == me -> truth.farbe++
        }
    }

    private fun countRound(truth: Truth, state: GameState) {
        val result = state.result!!
        truth.rounds++
        if (result.winningTeam == teamOf(me)) {
            truth.roundsWon++
            truth.pointsWon += result.points
        } else {
            truth.pointsLost += result.points
        }
        truth.ownTricks += state.completedTricks.count { it.winner == me }
    }

    @Test
    fun `random matches are counted exactly once`() {
        val (stats, truth) = play(matches = 30, seed = 3)
        assertEquals(30, stats[Counter.MATCHES])
        assertEquals(truth.rounds, stats[Counter.ROUNDS])
        assertEquals(truth.roundsWon, stats[Counter.ROUNDS_WON])
        assertEquals(truth.pointsWon, stats[Counter.POINTS_WON])
        assertEquals(truth.pointsLost, stats[Counter.POINTS_LOST])
        assertEquals(truth.ownTricks, stats[Counter.OWN_TRICKS])
        assertEquals(truth.schlag, stats[Counter.SCHLAG_CALLED])
        assertEquals(truth.schlag, Rank.entries.sumOf(stats::schlag))
        assertEquals(truth.farbe, stats[Counter.FARBE_CALLED])
        assertEquals(truth.farbe, Suit.entries.sumOf(stats::farbe))
        assertEquals(truth.mySchubs, stats[Counter.SCHUB_CALLED])
        assertEquals(truth.mySchubsWent, stats[Counter.SCHUB_WENT])
        assertEquals(truth.facedWent, stats[Counter.FACED_WENT])
        assertEquals(truth.facedStayed, stats[Counter.FACED_STAYED])
        // The random players make sure all of it happens
        assertTrue(truth.mySchubs > 0 && truth.facedWent > 0 && truth.facedStayed > 0)
        assertTrue(stats[Counter.BEST_STREAK] in 1..stats[Counter.MATCHES_WON])
    }

    @Test
    fun `a result seen without the running round is not counted`() {
        val random = Random(5)
        var state = Waddeln.newMatch(random)
        while (state.result == null) {
            val actor = actor(state)
            val actions = Waddeln.legalActions(state, actor).filter { it != Action.Schub }
            state = Waddeln.apply(state, actor, actions.first())
        }
        var counted = 0
        val tracker = StatsTracker { counted++ }
        tracker.observe(state.observationFor(me))
        assertEquals(0, counted)
    }

    private fun round(won: Boolean, points: Int = 2, matchWon: Boolean? = null) = RoundRecord(
        won = won,
        points = points,
        reason = RoundEndReason.TRICKS,
        ownTricks = 1,
        opponentTricks = if (won) 0 else 3,
        schlag = null,
        farbe = null,
        hand = listOf(Card.MAX, Card(Suit.GRAS, Rank.ASS)),
        trump = Trump(Rank.ASS, Suit.GRAS),
        schubs = emptyList(),
        matchWon = matchWon,
    )

    @Test
    fun `streaks, best values and cards`() {
        val stats = listOf(
            round(won = true, matchWon = true),
            round(won = true, points = 5, matchWon = true),
            round(won = false, matchWon = false),
            round(won = true, matchWon = true),
        ).fold(Stats()) { s, r -> s.with(r) }
        assertEquals(4, stats[Counter.MATCHES])
        assertEquals(2, stats[Counter.BEST_STREAK])
        assertEquals(1, stats[Counter.STREAK])
        assertEquals(5, stats[Counter.BEST_ROUND])
        assertEquals(3, stats[Counter.WON_TO_NIL])
        assertEquals(4, stats[Counter.HAUBI_HELD])
        assertEquals(4, stats[Counter.MAX_HELD])
        assertEquals(0, stats[Counter.BELLO_HELD])
        assertEquals(8, stats[Counter.TRUMPS_HELD])

        val total = stats + stats
        assertEquals(8, total[Counter.MATCHES])
        assertEquals(2, total[Counter.BEST_STREAK])
        assertEquals(5, total[Counter.BEST_ROUND])
        assertEquals(0, total[Counter.STREAK])
    }
}
