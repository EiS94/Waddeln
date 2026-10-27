package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.RandomAgent
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleBasedAgentTest {
    private val bot = RuleBasedAgent()

    private fun decide(state: GameState, player: Int): Action =
        bot.chooseAction(state.observationFor(player), Waddeln.legalActions(state, player))

    @Test
    fun `announces the rank it holds most often, ignoring Kritten`() {
        assertEquals(Rank.OBER, bot.chooseSchlag(cards("S7 E7 GO HO S9")))
        assertEquals(Rank.ASS, bot.chooseSchlag(cards("SA HA GO E9 S8")))
    }

    @Test
    fun `dealer chooses the Farbe that makes its hand strongest`() {
        assertEquals(Suit.GRAS, bot.chooseFarbe(cards("GA GK G9 SO E8"), Rank.OBER))
    }

    @Test
    fun `wins a trick as cheaply as possible when playing last`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("S9", "S8", "S10")
        // Player 3 holds SO G9 HA EK H8; G9 is the cheapest trump that wins
        assertEquals(Action.PlayCard(c("G9")), decide(s, 3))
    }

    @Test
    fun `does not overtake the partner who wins the trick as last player`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("SA", "S8", "S10")
        // Player 3's partner (1) is not winning; opponent 0 leads with SA. Take it with a trump.
        assertEquals(Action.PlayCard(c("G9")), decide(s, 3))
        val s2 = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS).play("S9", "SK", "S10")
        // Partner 1 wins with SK: dump the cheapest card
        assertEquals(Action.PlayCard(c("H8")), decide(s2, 3))
    }

    @Test
    fun `leads the highest card that nobody can beat`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS)
        val cardActions = Waddeln.legalActions(s, 0).filter { it != Action.Schub }
        assertEquals(Action.PlayCard(c("HK")), bot.chooseAction(s.observationFor(0), cardActions))
    }

    @Test
    fun `always chooses legal actions`() {
        repeat(300) { i ->
            val random = Random(i)
            MatchRunner(
                List(PLAYER_COUNT) { RuleBasedAgent() }, random,
                onAction = { before, player, action -> assertTrue(Waddeln.isLegal(before, player, action)) },
            ).playMatch()
        }
    }

    @Test
    fun `beats random players clearly`() {
        var wins = 0
        val matches = 300
        repeat(matches) { i ->
            val random = Random(1000 + i)
            val agents: List<Agent> = List(PLAYER_COUNT) { p ->
                if (teamOf(p) == 0) RuleBasedAgent() else RandomAgent(Random(random.nextInt()))
            }
            val end = MatchRunner(agents, random).playMatch()
            if (Waddeln.matchWinner(end) == 0) wins++
        }
        // About 81 %: random players ask "Was soll ich machen?" too and then only play fitting cards.
        assertTrue(wins > matches * 0.75, "Rule-based team won only $wins of $matches")
    }
}
