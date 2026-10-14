package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.game.OfflineGame.Companion.HUMAN
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.teamOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OfflineGameTest {

    /** Simulates the human with the rule-based bot, acting only when the game waits for them. */
    private fun playMatchAsHuman(game: OfflineGame) {
        val human = RuleBasedAgent()
        var steps = 0
        while (game.state.phase != Phase.MATCH_OVER) {
            check(steps++ < 10_000) { "Match does not terminate" }
            when {
                game.state.phase == Phase.ROUND_OVER -> game.nextRound()
                game.botToAct() != null -> game.stepBot()
                else -> {
                    val actions = game.humanActions()
                    assertTrue("Game waits for the human, who has no action", actions.any { it != Action.Schub })
                    game.applyHuman(human.chooseAction(game.state.observationFor(HUMAN), actions))
                }
            }
        }
    }

    @Test
    fun `complete matches can be played`() {
        repeat(100) { playMatchAsHuman(OfflineGame(Random(it))) }
    }

    @Test
    fun `human answers a Schub against their team`() {
        val game = OfflineGame(Random(7))
        // Advance until a bot (opponent) may announce Schub on its turn
        while (game.state.phase != Phase.PLAYING) {
            if (game.botToAct() != null) game.stepBot() else game.applyHuman(game.humanActions().first())
        }
        val opponent = (0..3).first { teamOf(it) != teamOf(HUMAN) }
        val state = Waddeln.apply(game.state, opponent, Action.Schub)
        assertEquals(teamOf(HUMAN), state.pendingSchub!!.answeringTeam)
        assertTrue(Action.Bleiben in Waddeln.legalActions(state, HUMAN))
    }

    @Test
    fun `human may announce Schub out of turn and a bot answers`() {
        val game = OfflineGame(Random(3))
        while (game.state.phase != Phase.PLAYING) {
            if (game.botToAct() != null) game.stepBot() else game.applyHuman(game.humanActions().first())
        }
        assertTrue(Action.Schub in game.humanActions())
        game.applyHuman(Action.Schub)
        val answering = game.botToAct()
        assertNotNull(answering)
        assertEquals(1 - teamOf(HUMAN), teamOf(answering!!))
        game.stepBot()
        assertNull(game.state.pendingSchub)
        assertTrue(game.events.any { it is GameEvent.Acted && it.action == Action.Schub })
    }
}
