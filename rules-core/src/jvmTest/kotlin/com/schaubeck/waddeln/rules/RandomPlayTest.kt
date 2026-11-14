package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.RandomAgent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Plays many random matches and checks invariants after every action. */
class RandomPlayTest {

    private fun checkInvariants(state: GameState) {
        val played = state.completedTricks.flatMap { t -> t.plays.map { it.card } } + state.currentTrick.map { it.card }
        val all = state.hands.flatten() + played + state.stock
        assertEquals(32, all.size)
        assertEquals(Card.ALL.toSet(), all.toSet())
        assertTrue(state.tricksWon.all { it <= TRICKS_TO_WIN })
        if (state.phase == Phase.PLAYING) {
            assertTrue(state.tricksWon.none { it >= TRICKS_TO_WIN })
            assertEquals(HAND_SIZE * PLAYER_COUNT - played.size, state.hands.sumOf { it.size })
        }
        state.completedTricks.forEach { trick ->
            val trump = state.trump!!
            assertEquals(trick.plays[trickWinnerIndex(trick.plays.map { it.card }, trump)].player, trick.winner)
        }
    }

    private fun runMatches(count: Int, seed: Int, agentFactory: (Random) -> Agent) {
        repeat(count) { i ->
            val random = Random(seed + i)
            val agents = List(PLAYER_COUNT) { agentFactory(Random(random.nextInt())) }
            var actions = 0
            val runner = MatchRunner(
                agents, random,
                onAction = { before, player, action ->
                    assertTrue(Waddeln.isLegal(before, player, action))
                    actions++
                },
                onDeal = { _, state -> checkInvariants(state) },
            )
            val end = runner.playMatch()
            checkInvariants(end)
            assertEquals(Phase.MATCH_OVER, end.phase)
            assertNotNull(Waddeln.matchWinner(end))
            assertTrue(end.scores.any { it >= MATCH_POINTS })
            assertTrue(actions > 0)
        }
    }

    @Test
    fun `random matches with Schub`() = runMatches(500, seed = 1) { RandomAgent(it) }

    @Test
    fun `random matches without Schub play out all tricks`() = runMatches(500, seed = 10_000) { random ->
        Agent { _, legal -> (legal.filter { it != Action.Schub }.ifEmpty { legal }).random(random) }
    }

    @Test
    fun `every intermediate state is consistent`() {
        val random = Random(42)
        repeat(200) {
            var state = Waddeln.newMatch(random)
            while (state.phase != Phase.MATCH_OVER) {
                checkInvariants(state)
                state = if (state.phase == Phase.ROUND_OVER) {
                    Waddeln.startNextRound(state, Deal.random(random))
                } else {
                    // Let every player that has legal actions be considered, like a real-time client.
                    val options = (0 until PLAYER_COUNT).flatMap { p -> Waddeln.legalActions(state, p).map { p to it } }
                    val (player, action) = options.random(random)
                    Waddeln.apply(state, player, action)
                }
            }
        }
    }
}
