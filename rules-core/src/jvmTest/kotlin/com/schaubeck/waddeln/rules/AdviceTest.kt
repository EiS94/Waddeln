package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Advice
import com.schaubeck.waddeln.rules.agent.BotTalk
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.TrickAdvice
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdviceTest {
    /**
     * Haubi Gras Ober; player 0 leads, player 1 plays second and may ask player 3, who plays last.
     * Player 3 holds the Max unless [lastHand] says otherwise.
     */
    private fun state(
        askerHand: String = "SA GU HO E8 H10",
        lastHand: String = "HK EO G9 EK H8",
    ) = playingWith(listOf("S8 S10 H9 E9 EA", askerHand, "SK SU HU E10 G8", lastHand), Rank.OBER, Suit.GRAS)

    private fun cardsAllowed(s: GameState, player: Int): List<Card> =
        BotTalk.allowedActions(s, player, Waddeln.legalActions(s, player)).filterIsInstance<Action.PlayCard>().map { it.card }

    @Test
    fun `only the second player of a trick may ask, once, and the partner answers`() {
        var s = state()
        assertFalse((0 until PLAYER_COUNT).any { Waddeln.canAskAdvice(s, it) }, "nobody before the lead")
        s = s.play("S8")
        assertTrue(Waddeln.canAskAdvice(s, 1))
        assertFalse(Waddeln.canAskAdvice(s, 0) || Waddeln.canAskAdvice(s, 2) || Waddeln.canAskAdvice(s, 3))
        assertFailsWith<IllegalActionException> { Waddeln.askAdvice(s, 3) }

        s = Waddeln.askAdvice(s, 1)
        assertFalse(Waddeln.canAskAdvice(s, 1), "once per trick")
        assertEquals(3, Waddeln.adviceAnswerer(s))
        assertFailsWith<IllegalActionException> { Waddeln.answerAdvice(s, 2, TrickAdvice.MACHEN) }
        s = Waddeln.answerAdvice(s, 3, TrickAdvice.STICHELN)
        assertNull(Waddeln.adviceAnswerer(s))
        assertEquals(listOf(AdviceTalk(1, 0, TrickAdvice.STICHELN)), s.observationFor(0).advice, "the opponents hear it")
        assertEquals(TrickAdvice.STICHELN, Waddeln.adviceFor(s, 1))

        s = s.play("SA")
        assertNull(Waddeln.adviceFor(s, 1), "the answer only counts for the card it was asked for")
        assertFalse(Waddeln.canAskAdvice(s, 2), "the third player does not ask")
    }

    @Test
    fun `asking is possible in every trick`() {
        var s = state().play("S8")
        s = Waddeln.answerAdvice(Waddeln.askAdvice(s, 1), 3, TrickAdvice.NICHTS)
        s = s.play("E8", "SK", "HK") // player 3 takes the trick and leads the next one
        assertEquals(3, s.playerToMove)
        s = s.play("EO")
        assertTrue(Waddeln.canAskAdvice(s, 0))
        s = Waddeln.askAdvice(s, 0)
        assertEquals(2, Waddeln.adviceAnswerer(s))
        assertEquals(AdviceTalk(0, 1), s.advice.last())
    }

    @Test
    fun `an open question is not answered while a Schub is pending`() {
        var s = Waddeln.askAdvice(state().play("S8"), 1)
        s = s.act(0, Action.Schub)
        assertNull(Waddeln.adviceAnswerer(s))
        assertFalse(Waddeln.canAskAdvice(s, 1))
        s = s.act(1, Action.Bleiben)
        assertEquals(3, Waddeln.adviceAnswerer(s))
    }

    @Test
    fun `holding a card nobody can beat the partner says Sticheln`() {
        val s = state().play("S8")
        assertEquals(TrickAdvice.STICHELN, Advice.answerFor(s.observationFor(3)))
    }

    @Test
    fun `without a sure card the partner says Machen`() {
        val s = state(lastHand = "EO G9 EK H8 GA", askerHand = "SA GU HO E8 HK").play("S8")
        assertEquals(TrickAdvice.MACHEN, Advice.answerFor(s.observationFor(3)))
    }

    @Test
    fun `taking the trick fairly surely the partner says Nichts`() {
        // The Bello is beaten only by the Max, which is unseen.
        val s = state(lastHand = "S7 G9 EK H8 GK").play("S8")
        assertEquals(TrickAdvice.NICHTS, Advice.answerFor(s.observationFor(3)))
    }

    @Test
    fun `a lead nobody can beat gets Nichts`() {
        val s = playingWith(
            listOf("HK S10 H9 E9 EA", "SA GU HO E8 H10", "SK SU HU E10 G8", "S8 EO G9 EK H8"),
            Rank.OBER, Suit.GRAS,
        ).play("HK")
        assertEquals(TrickAdvice.NICHTS, Advice.answerFor(s.observationFor(3)))
        assertFalse(Advice.mayAsk(s, 1), "a bot that cannot take the trick does not ask")
    }

    @Test
    fun `the answer restricts the cards a bot plays`() {
        // Player 1 holds Haubi-Schlag, Max, Haubi and Schell Ass; Bello and Secher are unseen.
        val asked = Waddeln.askAdvice(state(askerHand = "HO HK GO SA E8", lastHand = "EO G9 EK H8 GA").play("S8"), 1)
        fun allowed(answer: TrickAdvice) = cardsAllowed(Waddeln.answerAdvice(asked, 3, answer), 1)

        assertEquals(cards("E8"), allowed(TrickAdvice.NICHTS))
        assertEquals(cards("HO GO SA"), allowed(TrickAdvice.STICHELN), "the Max cannot be beaten, so it is too high")
        assertEquals(cards("HO"), allowed(TrickAdvice.MACHEN), "the Haubi-Schlag holds well enough, the Max is kept")
    }

    @Test
    fun `Machen takes the Max if the Haubi-Schlag is too risky`() {
        // Haubi, Bello and Secher are unseen: the Haubi-Schlag would too often be beaten.
        val asked = Waddeln.askAdvice(state(askerHand = "HO HK SA E8 H10", lastHand = "EO G9 EK H8 GA").play("S8"), 1)
        assertEquals(cards("HK"), cardsAllowed(Waddeln.answerAdvice(asked, 3, TrickAdvice.MACHEN), 1))
    }

    @Test
    fun `a bot asks before playing second and follows the answer`() {
        val s = state().play("S8")
        assertTrue(Advice.mayAsk(s, 1))
        val (talked, action) = BotTalk.decide(s, 1, RuleBasedAgent(), fixedRandom(0.0))
        assertEquals(AdviceTalk(1, 0, TrickAdvice.STICHELN), talked.advice.single())
        assertEquals(Action.PlayCard(c("SA")), action, "the cheapest card that takes the trick and can still be beaten")
    }

    @Test
    fun `bots do not always ask`() {
        val s = state().play("S8")
        assertTrue(Advice.asks(s, 1, fixedRandom(Advice.ASK_CHANCE - 0.01)))
        assertFalse(Advice.asks(s, 1, fixedRandom(Advice.ASK_CHANCE)))
        val (talked, _) = BotTalk.decide(s, 1, RuleBasedAgent(), fixedRandom(0.99))
        assertTrue(talked.advice.isEmpty())
    }

    @Test
    fun `on Nichts a bot that must take the trick plays its cheapest card`() {
        // Every card of player 1 beats the Schell 8: the Ass is the cheapest of them.
        val asked = Waddeln.askAdvice(state(askerHand = "HO HK GO SA GU", lastHand = "EO G9 EK H8 GA").play("S8"), 1)
        assertEquals(cards("SA"), cardsAllowed(Waddeln.answerAdvice(asked, 3, TrickAdvice.NICHTS), 1))
    }

    /** A [Random] whose nextDouble() is always [value]. */
    private fun fixedRandom(value: Double) = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = value
    }
}
