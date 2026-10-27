package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.game.OfflineGame.Companion.HUMAN
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.agent.TrickAdvice
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.rules.trickWinnerIndex
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
                game.humanMustCut() -> game.cut(Random.nextInt(4, 29))
                game.botToAct() != null && game.abheberToCut != null -> game.stepBot()
                game.state.phase == Phase.ROUND_OVER -> game.nextRound()
                game.humanMustAnswerSchadet() -> game.answerSchadet(SchadetAnswer.entries.random())
                game.humanMustAnswerAdvice() -> game.answerAdvice(TrickAdvice.entries.random())
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

    /**
     * One step of the game: the human declines a Schlagwechsel, answers the partner's questions and
     * otherwise takes the first action.
     */
    private fun step(game: OfflineGame) {
        when {
            game.humanMustCut() -> game.cut(16)
            game.humanMustAnswerSchlagwechsel() -> game.answerSchlagwechsel(false)
            game.humanMustAnswerAdvice() -> game.answerAdvice(TrickAdvice.NICHTS)
            game.humanMustAnswerSchadet() -> game.answerSchadet(SchadetAnswer.NO)
            game.botToAct() != null -> game.stepBot()
            else -> game.applyHuman(game.humanActions().first())
        }
    }

    /** Lets the Abheber cut the first round, so the game starts with the deal. */
    private fun dealt(game: OfflineGame): OfflineGame {
        while (game.abheberToCut != null) if (game.humanMustCut()) game.cut(16) else game.stepBot()
        return game
    }

    @Test
    fun `the round is dealt once the Abheber has cut`() {
        repeat(40) { seed ->
            val game = OfflineGame(Random(seed))
            val abheber = game.abheberToCut!!
            assertTrue(game.state.observationFor(HUMAN).hand.isEmpty())
            assertTrue(game.humanActions().isEmpty())
            if (abheber == HUMAN) {
                assertNull(game.botToAct())
                val reveal = game.cut(9)
                assertTrue(game.state.hands[HUMAN].containsAll(reveal.kritten))
            } else {
                assertEquals(abheber, game.botToAct())
                val step = game.stepBot() as BotStep.Cut
                assertEquals(step.reveal.kritten.size, game.state.abheberKritten)
                assertEquals(step.reveal.bottomCard, game.state.visibleBottomCard)
            }
            assertNull(game.abheberToCut)
            assertEquals(Phase.SCHLAG, game.state.phase)
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
            val game = dealt(OfflineGame(Random(seed), bots = List(4) { HaubiLeader() }))
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
                    game.humanMustAnswerAdvice() -> game.answerAdvice(TrickAdvice.NICHTS)
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
            val game = dealt(OfflineGame(Random(seed)))
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
    fun `the partner bot answers the human's Was soll ich machen`() {
        repeat(300) { seed ->
            val game = dealt(OfflineGame(Random(seed)))
            while (game.state.phase != Phase.ROUND_OVER && !game.humanCanAskAdvice()) step(game)
            if (!game.humanCanAskAdvice()) return@repeat
            game.askAdvice()
            assertEquals(OfflineGame.PARTNER, game.botToAct())
            assertTrue(game.stepBot() is BotStep.AnsweredAdvice)
            assertNotNull(game.state.advice.last().answer)
            assertTrue("asked once per trick", !game.humanCanAskAdvice())
            return
        }
        error("the human never played second in a trick")
    }

    @Test
    fun `a bot asks the human partner and waits for the answer`() {
        repeat(300) { seed ->
            val game = dealt(OfflineGame(Random(seed)))
            while (game.state.phase != Phase.ROUND_OVER) {
                if (game.humanMustAnswerAdvice()) {
                    assertNull("the game waits for the human", game.botToAct())
                    game.answerAdvice(TrickAdvice.MACHEN)
                    assertTrue(game.stepBot() is BotStep.Acted)
                    return
                }
                step(game)
            }
        }
        error("the partner bot never asked")
    }

    @Test
    fun `without the setting nobody asks Was soll ich machen`() {
        repeat(30) { seed ->
            val game = dealt(OfflineGame(Random(seed)).apply { adviceEnabled = false })
            while (game.state.phase == Phase.SCHLAG || game.state.phase == Phase.FARBE || game.state.phase == Phase.PLAYING) {
                assertTrue("the human cannot ask", !game.humanCanAskAdvice())
                step(game)
            }
            assertTrue("no bot asked", game.state.advice.isEmpty())
        }
    }

    @Test
    fun `a bot told Nichts only takes the trick if every card would`() {
        var checked = 0
        repeat(300) { seed ->
            val game = dealt(OfflineGame(Random(seed)))
            while (game.state.phase == Phase.SCHLAG || game.state.phase == Phase.FARBE || game.state.phase == Phase.PLAYING) {
                if (!game.humanMustAnswerAdvice()) {
                    step(game)
                    continue
                }
                game.answerAdvice(TrickAdvice.NICHTS)
                val s = game.state
                val lead = s.currentTrick.map { it.card }
                fun beats(card: Card) = trickWinnerIndex(lead + card, s.trump!!) == lead.size
                val playable = Waddeln.playableCards(s, OfflineGame.PARTNER)
                val played = (game.stepBot() as? BotStep.Acted)?.action as? Action.PlayCard ?: continue
                if (beats(played.card)) assertTrue("only if every card takes the trick", playable.all(::beats))
                checked++
            }
        }
        assertTrue("the partner bot asked often enough", checked > 20)
    }

    @Test
    fun `the dealer bot answers the human's Schlagwechsel`() {
        repeat(100) { seed ->
            val game = dealt(OfflineGame(Random(seed)))
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
        val game = dealt(OfflineGame(Random(7)))
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
        val game = dealt(OfflineGame(Random(3)))
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
