package com.schaubeck.waddeln.protocol

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.RandomAgent
import com.schaubeck.waddeln.rules.observationFor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagesTest {

    @Test
    fun clientMessagesSurviveARoundTrip() {
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
    fun cardsAndActionsUseReadableNames() {
        assertEquals(
            """{"type":"act","action":{"type":"play","card":"HERZ_KOENIG"}}""",
            ClientMessage.Act(Action.PlayCard(Card.MAX)).encode(),
        )
    }

    @Test
    fun observationsOfWholeMatchesSurviveARoundTrip() {
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

    // Fixed expected texts: the same strings must come out on the JVM (Android, server) and in the
    // browser (Wasm), otherwise the two clients could not share a room.

    @Test
    fun helloHasAFixedEncoding() {
        val hello = ClientMessage.Hello("3f0c9a52-7d1e-4b8a-9c11-0e5d2a6b7f40", "Eike")
        val json = HELLO_JSON
        assertEquals(json, hello.encode())
        assertEquals(hello, decodeClientMessage(json))
    }

    @Test
    fun actionsHaveAFixedEncoding() {
        val expected = mapOf<ClientMessage, String>(
            ClientMessage.Act(Action.AnnounceSchlag(Rank.OBER)) to """{"type":"act","action":{"type":"schlag","rank":"OBER"}}""",
            ClientMessage.Act(Action.AnnounceFarbe(Suit.GRAS)) to """{"type":"act","action":{"type":"farbe","suit":"GRAS"}}""",
            ClientMessage.Act(Action.Schub) to """{"type":"act","action":{"type":"schub"}}""",
            ClientMessage.Cut(12) to """{"type":"cut","cut":12}""",
            ClientMessage.Ready to """{"type":"ready"}""",
        )
        expected.forEach { (message, json) ->
            assertEquals(json, message.encode())
            assertEquals(message, decodeClientMessage(json))
        }
    }

    @Test
    fun tableHasAFixedEncoding() {
        val table = fixedTable()
        assertEquals(TABLE_JSON, table.encode())
        assertEquals(table, decodeServerMessage(TABLE_JSON))
    }

    /** A seeded match played a few steps into the first trick, seen from seat 1. */
    private fun fixedTable(): ServerMessage.Table {
        val random = Random(42)
        var state: GameState = Waddeln.newMatch(0, Deal.random(random))
        var last: SeatAction? = null
        repeat(5) {
            val player = state.playerToMove ?: return@repeat
            val action = Waddeln.legalActions(state, player).first()
            state = Waddeln.apply(state, player, action)
            last = SeatAction(player, action)
        }
        val view = GameView(
            observation = state.observationFor(1),
            legalActions = Waddeln.legalActions(state, 1),
            lastAction = last,
            timeLeftMs = 25_000,
            partnerQuestion = 3,
            partnerHint = SeatHint(3, PartnerHint.entries.first()),
        )
        return ServerMessage.Table(room(), 1, view)
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        assertEquals(ServerMessage.Error("x"), decodeServerMessage("""{"type":"error","message":"x","extra":1}"""))
    }

    private fun room() = RoomInfo(
        code = "ABCD",
        seats = listOf(SeatInfo("Eike", true, true), SeatInfo(), SeatInfo("Anna", false), SeatInfo()),
        botStrength = BotStrength.HARD,
        started = true,
    )
}

private const val HELLO_JSON = """{"type":"hello","playerId":"3f0c9a52-7d1e-4b8a-9c11-0e5d2a6b7f40","name":"Eike","version":3}"""

/** Generated once on the JVM; any change here breaks the apps that are already installed. */
private const val TABLE_JSON =
    """{"type":"table","room":{"code":"ABCD","seats":[{"name":"Eike","connected":true,"host":true,"botName":null},""" +
    """{"name":null,"connected":false,"host":false,"botName":null},{"name":"Anna","connected":false,"host":false,""" +
    """"botName":null},{"name":null,"connected":false,"host":false,"botName":null}],"botStrength":"HARD",""" +
    """"started":true},"yourSeat":1,"game":{"observation":{"player":1,"phase":"PLAYING","scores":[0,0],"dealer":0,""" +
    """"roundNumber":1,"hand":["EICHEL_NINE","EICHEL_SEVEN","SCHELL_UNTER","HERZ_UNTER"],"handSizes":[5,4,4,4],""" +
    """"abheberKritten":0,"visibleBottomCard":"EICHEL_TEN","geberKritten":0,"geberCard":null,"schlag":"SEVEN",""" +
    """"farbe":"SCHELL","trickLeader":1,"currentTrick":[{"player":1,"card":"EICHEL_KOENIG"},{"player":2,""" +
    """"card":"GRAS_TEN"},{"player":3,"card":"HERZ_ASS"}],"completedTricks":[],"tricksWon":[0,0],"gameValue":2,""" +
    """"lastSchubTeam":null,"pendingSchub":null,"laidOutCounts":null,"ownLaidOut":[],"playerToMove":0,"result":null,""" +
    """"schubs":[],"hints":[],"schadet":null,"advice":[],"schlagwechsel":null},"legalActions":[{"type":"schub"}],""" +
    """"lastAction":{"seat":3,"action":{"type":"play","card":"HERZ_ASS"}},"ready":[false,false,false,false],""" +
    """"timeLeftMs":25000,"canAskPartner":false,"partnerQuestion":3,"partnerHint":{"seat":3,"hint":"NOTHING"},""" +
    """"canAskSchadet":false,"schadetQuestion":null,"schadetAnswer":null,"canAskAdvice":false,""" +
    """"canProposeSchlagwechsel":false,"canShowLastTrick":false,"lastTrickShownBy":null,"cut":null}}"""
