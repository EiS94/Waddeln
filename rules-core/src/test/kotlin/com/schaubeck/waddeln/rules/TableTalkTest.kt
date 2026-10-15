package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.BotTalk
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.PartnerHints
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.ml.FeatureEncoder
import com.schaubeck.waddeln.rules.ml.MlAgent
import com.schaubeck.waddeln.rules.ml.QFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableTalkTest {
    /** Haubi Gras Ober; player 0 leads and holds it. Player 2 (partner) gets [partnerHand]. */
    private fun state(partnerHand: String = "E7 SO HO E8 H7") = playingWith(
        listOf("GO S8 S10 H9 E9", "SK SU H10 HU E10", partnerHand, "SA EA EK H8 S9"),
        Rank.OBER, Suit.GRAS,
    )

    @Test
    fun `every Schub is recorded with the player and the cards played before it`() {
        val s = state().play("S8").act(1, Action.Schub)
        assertEquals(listOf(SchubCall(1, 1)), s.schubs)
        assertEquals(listOf(SchubCall(1, 1)), s.observationFor(0).schubs)
    }

    @Test
    fun `the asked team gives one hint per Schub`() {
        var s = state().act(1, Action.Schub)
        assertTrue(Waddeln.canGiveHint(s, 0) && Waddeln.canGiveHint(s, 2))
        assertFalse(Waddeln.canGiveHint(s, 1), "the asking team does not talk")
        s = Waddeln.giveHint(s, 2, PartnerHint.ONE_TRICK)
        assertEquals(listOf(HintCall(2, PartnerHint.ONE_TRICK, 0)), s.observationFor(3).hints, "the opponents hear it")
        assertFalse(Waddeln.canGiveHint(s, 0))
        assertFailsWith<IllegalActionException> { Waddeln.giveHint(s, 0, PartnerHint.NOTHING) }

        s = s.act(0, Action.Bleiben)
        assertFalse(Waddeln.canGiveHint(s, 1), "no Schub open")
        s = s.act(0, Action.Schub)
        assertTrue(Waddeln.canGiveHint(s, 1), "the next Schub may be talked about again")
        s = Waddeln.giveHint(s, 3, PartnerHint.NOTHING)
        assertEquals(HintCall(3, PartnerHint.NOTHING, 1), s.hints.last())
    }

    @Test
    fun `Schadder is asked once by the Haubi holder and answered by the partner`() {
        var s = state()
        assertTrue(Waddeln.canAskSchadet(s, 0))
        assertFalse(Waddeln.canAskSchadet(s, 2))
        assertFailsWith<IllegalActionException> { Waddeln.askSchadet(s, 2) }
        s = Waddeln.askSchadet(s, 0)
        assertFalse(Waddeln.canAskSchadet(s, 0), "only once per round")
        assertEquals(2, Waddeln.schadetAnswerer(s))
        assertFailsWith<IllegalActionException> { Waddeln.answerSchadet(s, 1, SchadetAnswer.NO) }
        s = Waddeln.answerSchadet(s, 2, SchadetAnswer.YES)
        assertNull(Waddeln.schadetAnswerer(s))
        assertEquals(SchadetTalk(0, SchadetAnswer.YES), s.observationFor(1).schadet)
    }

    @Test
    fun `an open Schadder becomes moot once the asker led`() {
        val s = Waddeln.askSchadet(state(), 0).play("S8")
        assertNull(Waddeln.schadetAnswerer(s))
    }

    @Test
    fun `not following the Haubi lead reveals a missing Haubi-Farbe until a Kritte lifts the duty`() {
        // Player 1 plays Schell (no Gras), player 2 the Secher: player 3 is free after that.
        val s = state().play("GO", "SK", "E7", "S9")
        assertEquals(setOf(1), FeatureEncoder.haubiFarbeVoids(s.observationFor(0)))
        val noHaubiLead = state().play("S8", "SK", "E7", "S9")
        assertEquals(emptySet(), FeatureEncoder.haubiFarbeVoids(noHaubiLead.observationFor(0)))
    }

    @Test
    fun `table talk is part of the network input`() {
        var s = Waddeln.answerSchadet(Waddeln.askSchadet(state(), 0), 2, SchadetAnswer.YES)
        s = s.act(1, Action.Schub)
        s = Waddeln.giveHint(s, 2, PartnerHint.ONE_TRICK)
        val f = FeatureEncoder.encodeState(s.observationFor(1)) // seats relative to player 1
        assertEquals(1f, f[478 + 1 * 5 + PartnerHint.ONE_TRICK.ordinal], "hint of player 2 (rel 1)")
        assertEquals(1f, f[498 + 3], "player 0 (rel 3) asked Schadder")
        assertEquals(1f, f[502 + SchadetAnswer.YES.ordinal])
        assertEquals(0.5f, f[509], "one Schub by player 1 itself")
        assertEquals(1f, f[513], "said before the first card")
    }

    @Test
    fun `a model trained before the table talk gets the old input layout`() {
        val s = state()
        var rowSize = 0
        val legacy = object : QFunction {
            override val inputSize = FeatureEncoder.LEGACY_INPUT_SIZE
            override fun evaluate(inputs: Array<FloatArray>): FloatArray {
                rowSize = inputs.first().size
                return FloatArray(inputs.size)
            }
        }
        MlAgent(legacy).chooseAction(s.observationFor(0), Waddeln.legalActions(s, 0))
        assertEquals(FeatureEncoder.LEGACY_INPUT_SIZE, rowSize)
    }

    @Test
    fun `a bot asks its partner before answering a Schub`() {
        val s = state().act(1, Action.Schub)
        val (talked, action) = BotTalk.decide(s, 0) { _, legal -> legal.first() }
        assertEquals(listOf(HintCall(2, PartnerHints.hintFor(s.observationFor(2)), 0)), talked.hints)
        assertEquals(Action.Bleiben, action)
    }

    @Test
    fun `a bot asks Schadder before leading the Haubi and keeps it on yes`() {
        val wantsHaubi = Agent { _, legal -> legal.firstOrNull { it == Action.PlayCard(c("GO")) } ?: legal.first() }
        val (hurt, action) = BotTalk.decide(state(partnerHand = "GA E8 H7 HA EO"), 0, wantsHaubi)
        assertEquals(SchadetTalk(0, SchadetAnswer.YES), hurt.schadet)
        assertTrue(action is Action.PlayCard && action != Action.PlayCard(c("GO")))

        val (fine, lead) = BotTalk.decide(state(), 0, wantsHaubi)
        assertEquals(SchadetTalk(0, SchadetAnswer.NO), fine.schadet)
        assertEquals(Action.PlayCard(c("GO")), lead)
    }
}
