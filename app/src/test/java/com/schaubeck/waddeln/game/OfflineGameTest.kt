package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.game.OfflineGame.Companion.HUMAN
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.teamOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OfflineGameTest {

    /** Announces the rank of its first card and always wants to lead the Haubi. */
    private class HaubiLeader : Agent {
        override fun chooseAction(observation: Observation, legalActions: List<Action>): Action {
            val schlag = Action.AnnounceSchlag(observation.hand.first().rank)
            if (schlag in legalActions) return schlag
            observation.trump?.let { Action.PlayCard(it.haubi) }?.takeIf { it in legalActions }?.let { return it }
            return legalActions.first { it != Action.Schub }
        }
    }

    /** Simulates the human with the rule-based bot, acting only when the game waits for them. */
    private fun playMatchAsHuman(game: OfflineGame) {
        val human = RuleBasedAgent()
        var steps = 0
        while (game.state.phase != Phase.MATCH_OVER) {
            check(steps++ < 10_000) { "Match does not terminate" }
            when {
                game.state.phase == Phase.ROUND_OVER -> game.nextRound()
                game.humanMustAnswerSchadet() -> game.answerSchadet(SchadetAnswer.entries.random())
                game.humanMustAnswerSchlagwechsel() -> game.answerSchlagwechsel(Random.nextBoolean())
                game.botToAct() != null -> game.stepBot()
                else -> {
                    val actions = game.humanActions()
                    assertTrue("Game waits for the human, who has no action", actions.any { it != Action.Schub })
                    game.applyHuman(human.chooseAction(game.state.observationFor(HUMAN), actions))
                }
            }
        }
    }

    /** One step towards the card play: the human declines a Schlagwechsel and otherwise takes the first action. */
    private fun step(game: OfflineGame) {
        when {
            game.humanMustAnswerSchlagwechsel() -> game.answerSchlagwechsel(false)
            game.botToAct() != null -> game.stepBot()
            else -> game.applyHuman(game.humanActions().first())
        }
    }

    @Test
    fun `complete matches can be played`() {
        repeat(100) { playMatchAsHuman(OfflineGame(Random(it))) }
    }

    @Test
    fun `a bot leading the Haubi asks Schadder and plays another card on yes`() {
        var asked = 0
        repeat(300) { seed ->
            val game = OfflineGame(Random(seed), bots = List(4) { HaubiLeader() })
            while (game.state.completedTricks.isEmpty() && game.state.playerToMove != null) {
                if (game.humanMustAnswerSchadet()) {
                    asked++
                    game.answerSchadet(SchadetAnswer.YES)
                    val haubi = game.state.trump!!.haubi
                    val step = game.stepBot()
                    assertTrue(step is BotStep.Acted && step.action != Action.PlayCard(haubi))
                    return@repeat
                }
                when {
                    game.humanMustAnswerSchlagwechsel() -> game.answerSchlagwechsel(false)
                    game.botToAct() != null -> game.stepBot()
                    else -> game.applyHuman(game.humanActions().first { it != Action.Schub })
                }
            }
        }
        assertTrue("some partner bot asked within 300 rounds", asked > 0)
    }

    @Test
    fun `the partner bot answers the human's Schadder`() {
        repeat(300) { seed ->
            val game = OfflineGame(Random(seed))
            while (game.state.phase != Phase.PLAYING) {
                step(game)
            }
            if (!game.humanCanAskSchadet()) return@repeat
            game.askSchadet()
            assertEquals(OfflineGame.PARTNER, game.botToAct())
            assertTrue(game.stepBot() is BotStep.AnsweredSchadet)
            assertNotNull(game.schadet!!.answer)
            assertTrue("asked once per round", !game.humanCanAskSchadet())
            return
        }
        error("the human never led with the Haubi")
    }

    @Test
    fun `the dealer bot answers the human's Schlagwechsel`() {
        repeat(100) { seed ->
            val game = OfflineGame(Random(seed))
            while (game.state.phase == Phase.SCHLAG && !game.humanCanProposeSchlagwechsel()) step(game)
            if (!game.humanCanProposeSchlagwechsel()) return@repeat
            game.proposeSchlagwechsel()
            assertTrue("no Schlag before the answer", game.humanActions().isEmpty())
            assertEquals(game.state.dealer, game.botToAct())
            val answer = game.stepBot()
            assertTrue(answer is BotStep.AnsweredSchlagwechsel)
            val announcer = if ((answer as BotStep.AnsweredSchlagwechsel).accept) game.state.dealer else HUMAN
            assertEquals(announcer, game.state.playerToMove)
            return
        }
        error("the human never announced the Schlag")
    }

    @Test
    fun `human answers a Schub against their team`() {
        val game = OfflineGame(Random(7))
        // Advance until a bot (opponent) may announce Schub on its turn
        while (game.state.phase != Phase.PLAYING) {
            step(game)
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
            step(game)
        }
        assertTrue(Action.Schub in game.humanActions())
        game.applyHuman(Action.Schub)
        val answering = game.botToAct()
        assertNotNull(answering)
        assertEquals(1 - teamOf(HUMAN), teamOf(answering!!))
        // First the bot asks its partner; everybody hears the answer
        val asked = game.stepBot()
        assertTrue(asked is BotStep.AskedPartner)
        assertEquals((answering + 2) % 4, game.state.hints.single().player)
        assertEquals(game.state.hints.single(), game.state.observationFor(HUMAN).hints.single())
        game.stepBot()
        assertNull(game.state.pendingSchub)
        assertTrue(game.events.any { it is GameEvent.Acted && it.action == Action.Schub })
    }
}
