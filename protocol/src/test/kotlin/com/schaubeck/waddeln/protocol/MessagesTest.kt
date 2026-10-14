package com.schaubeck.waddeln.protocol

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.RandomAgent
import com.schaubeck.waddeln.rules.observationFor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagesTest {

    @Test
    fun `client messages survive a round trip`() {
        val messages = listOf(
            ClientMessage.Hello("id-1", "Eike"),
            ClientMessage.CreateRoom,
            ClientMessage.JoinRoom("K7QM"),
            ClientMessage.TakeSeat(2),
            ClientMessage.SetBotStrength(BotStrength.MEDIUM),
            ClientMessage.SetBotName(3, "Schorsch"),
            ClientMessage.StartMatch,
            ClientMessage.Act(Action.AnnounceSchlag(Rank.OBER)),
            ClientMessage.Act(Action.AnnounceFarbe(Suit.GRAS)),
            ClientMessage.Act(Action.PlayCard(Card.MAX)),
            ClientMessage.Act(Action.Schub),
            ClientMessage.Act(Action.Bleiben),
            ClientMessage.Act(Action.Gehen),
            ClientMessage.Ready,
            ClientMessage.LeaveRoom,
        )
        messages.forEach { assertEquals(it, decodeClientMessage(it.encode())) }
    }

    @Test
    fun `cards and actions use readable names`() {
        assertEquals(
            """{"type":"act","action":{"type":"play","card":"HERZ_KOENIG"}}""",
            ClientMessage.Act(Action.PlayCard(Card.MAX)).encode(),
        )
    }

    @Test
    fun `observations of whole matches survive a round trip`() {
        val random = Random(7)
        var checked = 0
        val runner = MatchRunner(List(4) { RandomAgent(random) }, random, onAction = { before, _, _ ->
            for (seat in 0 until 4) {
                val view = GameView(before.observationFor(seat), Waddeln.legalActions(before, seat))
                val message = ServerMessage.Table(room(), seat, view)
                assertEquals(message, decodeServerMessage(message.encode()))
                checked++
            }
        })
        repeat(3) { runner.playMatch() }
        assertTrue(checked > 100)
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        assertEquals(ServerMessage.Error("x"), decodeServerMessage("""{"type":"error","message":"x","extra":1}"""))
    }

    private fun room() = RoomInfo(
        code = "ABCD",
        seats = listOf(SeatInfo("Eike", true, true), SeatInfo(), SeatInfo("Anna", false), SeatInfo()),
        botStrength = BotStrength.HARD,
        started = true,
    )
}
