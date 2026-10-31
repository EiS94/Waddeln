package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.SchubRule
import com.schaubeck.waddeln.rules.ml.MlAgent
import com.schaubeck.waddeln.rules.ml.QFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchubRuleTest {
    // Player 1 holds nothing worth staying for: the rule bot would go without the rule.
    private val state = playingWith(
        listOf("HK S8 S10 H9 E9", "S7 SU H10 G8 G7", "E7 SO HO S9 E8", "SA EA EK H8 G9"),
        Rank.OBER,
        Suit.GRAS,
    )

    /** Player 1 (team 1) answers a Schub of player 0 (team 0) from [gameValue] to one more. */
    private fun answering(scores: List<Int>, gameValue: Int = 2) = state.observationFor(1).copy(
        scores = scores,
        gameValue = gameValue,
        pendingSchub = PendingSchub(0),
        lastSchubTeam = 0,
    )

    private val answers = listOf(Action.Bleiben, Action.Gehen)

    @Test
    fun `opponents on 7 say Schub to 3 - going and losing both leave them gespannt`() {
        assertTrue(SchubRule.mustStay(answering(listOf(7, 4))))
        assertTrue(SchubRule.mustStay(answering(listOf(6, 4), gameValue = 3)))
    }

    @Test
    fun `no fixed answer if going keeps them below gespannt or losing would end the match`() {
        assertFalse(SchubRule.mustStay(answering(listOf(6, 4))), "going: 8, not gespannt")
        assertFalse(SchubRule.mustStay(answering(listOf(8, 4))), "losing: 11, match over")
        assertFalse(SchubRule.mustStay(answering(listOf(7, 4)).copy(pendingSchub = null)))
    }

    @Test
    fun `rule bot and network both stay`() {
        val obs = answering(listOf(7, 4))
        assertEquals(Action.Gehen, RuleBasedAgent().chooseAction(answering(listOf(3, 4)), answers), "weak hand: goes otherwise")
        assertEquals(Action.Bleiben, RuleBasedAgent().chooseAction(obs, answers))
        val prefersGehen = QFunction { rows -> FloatArray(rows.size) { it.toFloat() } }
        assertEquals(Action.Gehen, MlAgent(prefersGehen).chooseAction(answering(listOf(3, 4)), answers))
        assertEquals(Action.Bleiben, MlAgent(prefersGehen).chooseAction(obs, answers))
    }
}
