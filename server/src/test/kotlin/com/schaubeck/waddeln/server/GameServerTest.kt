@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.BotStrength
import com.schaubeck.waddeln.protocol.ClientMessage
import com.schaubeck.waddeln.protocol.SeatHint
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.protocol.decodeServerMessage
import com.schaubeck.waddeln.protocol.encode
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records what the server sends; every message also goes through JSON like on the wire. */
class FakeConnection : Connection {
    val received = mutableListOf<ServerMessage>()
    var closed = false

    override fun send(message: ServerMessage) {
        received += decodeServerMessage(message.encode())
    }

    override fun close() {
        closed = true
    }

    val table: ServerMessage.Table get() = received.filterIsInstance<ServerMessage.Table>().last()
    val last: ServerMessage get() = received.last()
}

class GameServerTest {
    private var now = 0L

    private inner class Player(val server: GameServer, val id: String, val ip: String = "local") {
        var connection = FakeConnection()
        var client = server.connect(connection, ip)!!

        fun send(message: ClientMessage) = server.onMessage(client, message)

        fun hello() = send(ClientMessage.Hello(id, id))

        fun reconnect() {
            connection = FakeConnection()
            client = server.connect(connection, ip)!!
            hello()
        }

        fun disconnect() = server.disconnect(client)

        val table get() = connection.table
        val seat get() = table.yourSeat
    }

    /** Bots run on the virtual test clock; unlike backgroundScope, advanceUntilIdle() also runs them. */
    private fun TestScope.newServer(
        seed: Int = 1,
        timing: Timing = Timing(turnTimeoutMs = null),
        limits: Limits = Limits(),
    ) = GameServer(
        CoroutineScope(StandardTestDispatcher(testScheduler)), { RuleBasedAgent() }, timing, Random(seed),
        clock = { now + testScheduler.currentTime }, limits = limits,
    )

    private fun player(server: GameServer, id: String, ip: String = "local") = Player(server, id, ip).also { it.hello() }

    @Test
    fun `hello without room answers no room`() = runTest {
        val p = player(newServer(), "a")
        assertEquals(ServerMessage.NoRoom, p.connection.last)
    }

    @Test
    fun `messages before hello are rejected`() = runTest {
        val server = newServer()
        val connection = FakeConnection()
        server.onMessage(server.connect(connection)!!, ClientMessage.CreateRoom)
        assertIs<ServerMessage.Error>(connection.last)
    }

    @Test
    fun `wrong protocol version is rejected`() = runTest {
        val server = newServer()
        val connection = FakeConnection()
        server.onMessage(server.connect(connection)!!, ClientMessage.Hello("a", "A", version = 999))
        assertIs<ServerMessage.Error>(connection.last)
    }

    @Test
    fun `create and join a room`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val code = host.table.room.code
        assertEquals(5, code.length)
        assertEquals(0, host.seat)
        assertTrue(host.table.room.seats[0].host)

        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(code.lowercase()))
        assertEquals(1, guest.seat)
        val seats = host.table.room.seats
        assertEquals(listOf("host", "guest", null, null), seats.map { it.name })
        assertTrue(seats[1].connected)
        assertFalse(seats[1].host)
    }

    @Test
    fun `joining an unknown room fails`() = runTest {
        val p = player(newServer(), "a")
        p.send(ClientMessage.JoinRoom("ZZZZ"))
        assertIs<ServerMessage.Error>(p.connection.last)
    }

    @Test
    fun `seats can be changed before the start only`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))

        guest.send(ClientMessage.TakeSeat(0))
        assertIs<ServerMessage.Error>(guest.connection.last)
        guest.send(ClientMessage.TakeSeat(2))
        assertEquals(2, guest.seat)

        host.send(ClientMessage.StartMatch)
        guest.send(ClientMessage.TakeSeat(3))
        assertIs<ServerMessage.Error>(guest.connection.last)
        assertEquals(2, guest.seat)
    }

    @Test
    fun `only the host starts and sets the bots`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))

        guest.send(ClientMessage.StartMatch)
        assertIs<ServerMessage.Error>(guest.connection.last)
        guest.send(ClientMessage.SetBotStrength(BotStrength.EASY))
        assertIs<ServerMessage.Error>(guest.connection.last)

        host.send(ClientMessage.SetBotStrength(BotStrength.MEDIUM))
        assertEquals(BotStrength.MEDIUM, guest.table.room.botStrength)
        host.send(ClientMessage.StartMatch)
        assertTrue(guest.table.room.started)
        assertNotNull(guest.table.game)
    }

    @Test
    fun `bots have different names that only the host can change`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))
        val names = host.table.room.seats.map { it.botName }
        assertTrue(names.all { !it.isNullOrBlank() })
        assertEquals(4, names.toSet().size)

        guest.send(ClientMessage.SetBotName(2, "Gast"))
        assertIs<ServerMessage.Error>(guest.connection.last)
        host.send(ClientMessage.SetBotName(1, "Mensch"))
        assertIs<ServerMessage.Error>(host.connection.last)
        host.send(ClientMessage.SetBotName(2, "   "))
        assertIs<ServerMessage.Error>(host.connection.last)

        host.send(ClientMessage.SetBotName(2, "  Kunigunde von Bamberg  "))
        assertEquals("Kunigunde von Ba", guest.table.room.seats[2].botName)
        // The name stays with the seat when a human sits there for a while.
        guest.send(ClientMessage.TakeSeat(2))
        guest.send(ClientMessage.TakeSeat(3))
        assertEquals("Kunigunde von Ba", host.table.room.seats[2].botName)
    }

    @Test
    fun `every player only sees their own hand`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))
        host.send(ClientMessage.StartMatch)

        val a = host.table.game!!.observation
        val b = guest.table.game!!.observation
        assertEquals(5, a.hand.size)
        assertEquals(5, b.hand.size)
        assertTrue(a.hand.none { it in b.hand })
        // Player ids are secret: they never appear in what others receive.
        assertFalse("host" in guest.table.encode().replace("\"host\":", "").replace("\"name\":\"host\"", ""))
    }

    @Test
    fun `bots play until a human has to act`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        host.send(ClientMessage.StartMatch)
        advanceUntilIdle()
        val game = host.table.game!!
        val obs = game.observation
        // Either the human must act or the round is over; bots never wait forever.
        assertTrue(game.legalActions.isNotEmpty() || obs.phase == Phase.ROUND_OVER || obs.phase == Phase.MATCH_OVER)
        if (obs.pendingSchub == null && obs.phase == Phase.PLAYING) assertEquals(0, obs.playerToMove)
    }

    @Test
    fun `bots wait between their moves`() = runTest {
        val server = newServer(seed = 3)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        host.send(ClientMessage.StartMatch)
        val before = host.connection.received.size
        advanceTimeBy(Timing().botDelayMs - 1)
        assertEquals(before, host.connection.received.size)
    }

    @Test
    fun `two humans and two bots play complete matches`() = runTest {
        val server = newServer(seed = 5)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))
        host.send(ClientMessage.StartMatch)

        val humans = listOf(host, guest)
        val random = Random(9)
        var matches = 0
        var steps = 0
        while (matches < 3) {
            advanceUntilIdle()
            check(++steps < 20_000) { "Game got stuck" }
            val games = humans.associateWith { it.table.game!! }
            val phase = games.values.first().observation.phase
            if (phase == Phase.ROUND_OVER || phase == Phase.MATCH_OVER) {
                if (phase == Phase.MATCH_OVER) matches++
                humans.forEach { it.send(ClientMessage.Ready) }
                continue
            }
            val actors = humans.filter { games[it]!!.legalActions.isNotEmpty() }
            assertTrue(actors.isNotEmpty(), "Nobody can act in ${games.values.first().observation}")
            val p = actors.random(random)
            // Prefer real moves so the game progresses; Schub sometimes.
            val legal = games[p]!!.legalActions
            val action = legal.filter { it != Action.Schub }.randomOrNull(random)?.takeIf { random.nextInt(10) > 0 || Action.Schub !in legal }
                ?: legal.random(random)
            p.send(ClientMessage.Act(action))
        }
    }

    @Test
    fun `next round starts once all connected humans are ready`() = runTest {
        val server = newServer(seed = 11)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))
        host.send(ClientMessage.StartMatch)
        // Both humans give up via Schub answers or play until the round is over.
        playUntilRoundOver(listOf(host, guest))
        val round = host.table.game!!.observation.roundNumber

        host.send(ClientMessage.Ready)
        assertEquals(round, host.table.game!!.observation.roundNumber)
        assertEquals(true, guest.table.game!!.ready[host.seat])

        // The guest drops out: the host alone is enough now.
        guest.disconnect()
        assertEquals(round + 1, host.table.game!!.observation.roundNumber)
    }

    @Test
    fun `a disconnected player is replaced by a bot and gets the seat back`() = runTest {
        val server = newServer(seed = 13)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))
        host.send(ClientMessage.StartMatch)

        guest.disconnect()
        assertFalse(host.table.room.seats[1].connected)
        // With only the host connected, the game must never wait for the guest.
        repeat(200) {
            advanceUntilIdle()
            val game = host.table.game!!
            val phase = game.observation.phase
            if (phase == Phase.ROUND_OVER || phase == Phase.MATCH_OVER) host.send(ClientMessage.Ready)
            else host.send(ClientMessage.Act(game.legalActions.first { it != Action.Schub }))
        }

        guest.reconnect()
        assertEquals(1, guest.seat)
        assertTrue(host.table.room.seats[1].connected)
        assertNotNull(guest.table.game)
    }

    @Test
    fun `the game pauses while nobody is connected`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        host.send(ClientMessage.StartMatch)
        host.disconnect()
        val count = host.connection.received.size
        advanceUntilIdle()
        assertEquals(count, host.connection.received.size)
        host.reconnect()
        assertEquals(0, host.seat)
    }

    @Test
    fun `a new connection of the same player replaces the old one`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val old = host.connection
        val oldClient = host.client
        host.reconnect()
        assertTrue(old.closed)
        // The old socket closing later must not mark the player as disconnected.
        server.disconnect(oldClient)
        assertTrue(host.table.room.seats[0].connected)
    }

    @Test
    fun `leaving frees the seat and passes on the host`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val code = host.table.room.code
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(code))

        host.send(ClientMessage.LeaveRoom)
        assertEquals(ServerMessage.NoRoom, host.connection.last)
        assertNull(guest.table.room.seats[0].name)
        assertTrue(guest.table.room.seats[1].host)

        guest.send(ClientMessage.LeaveRoom)
        assertEquals(0, server.roomCount)
    }

    @Test
    fun `players can join a running match on a bot seat`() = runTest {
        val server = newServer()
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        host.send(ClientMessage.StartMatch)
        advanceUntilIdle()
        val late = player(server, "late")
        late.send(ClientMessage.JoinRoom(host.table.room.code))
        assertEquals(1, late.seat)
        assertEquals(5 - late.table.game!!.observation.completedTricks.count { t -> t.plays.any { it.player == 1 } } -
            (if (late.table.game!!.observation.currentTrick.any { it.player == 1 }) 1 else 0),
            late.table.game!!.observation.hand.size)
    }

    @Test
    fun `a full room rejects further players`() = runTest {
        val server = newServer()
        val host = player(server, "p0")
        host.send(ClientMessage.CreateRoom)
        val code = host.table.room.code
        (1..3).forEach { player(server, "p$it").send(ClientMessage.JoinRoom(code)) }
        val fifth = player(server, "p4")
        fifth.send(ClientMessage.JoinRoom(code))
        assertIs<ServerMessage.Error>(fifth.connection.last)
    }

    @Test
    fun `abandoned rooms are cleaned up, lobbies sooner`() = runTest {
        val server = newServer(limits = Limits(idleLobbyMs = 5_000, idleRoomMs = 20_000))
        val lobby = player(server, "lobby")
        lobby.send(ClientMessage.CreateRoom)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        host.send(ClientMessage.StartMatch)
        lobby.disconnect()
        host.disconnect()
        now += 10_000
        server.cleanup()
        assertEquals(1, server.roomCount)
        now += 10_000
        server.cleanup()
        assertEquals(0, server.roomCount)
        host.reconnect()
        assertEquals(ServerMessage.NoRoom, host.connection.last)
    }

    @Test
    fun `room codes have five characters`() = runTest {
        val host = player(newServer(), "host")
        host.send(ClientMessage.CreateRoom)
        assertEquals(5, host.table.room.code.length)
    }

    @Test
    fun `too many connections are refused`() = runTest {
        val server = newServer(limits = Limits(maxConnections = 5, maxConnectionsPerIp = 3))
        val a = List(3) { assertNotNull(server.connect(FakeConnection(), "1.1.1.1")) }
        assertNull(server.connect(FakeConnection(), "1.1.1.1"), "per IP")
        repeat(2) { assertNotNull(server.connect(FakeConnection(), "2.2.2.2")) }
        assertNull(server.connect(FakeConnection(), "3.3.3.3"), "in total")
        server.disconnect(a[0])
        server.disconnect(a[0]) // closing twice must not free two places
        assertNotNull(server.connect(FakeConnection(), "1.1.1.1"))
        assertNull(server.connect(FakeConnection(), "1.1.1.1"))
    }

    @Test
    fun `flooding with messages closes the connection`() = runTest {
        val server = newServer(limits = Limits(messageBurst = 10, messageIntervalMs = 100))
        val p = player(server, "p")
        repeat(9) { p.send(ClientMessage.Ready) }
        assertFalse(p.connection.closed)
        p.send(ClientMessage.Ready)
        assertTrue(p.connection.closed)

        // At a normal pace nothing happens.
        val calm = player(server, "calm")
        repeat(100) {
            now += 100
            calm.send(ClientMessage.Ready)
        }
        assertFalse(calm.connection.closed)
    }

    @Test
    fun `rooms per IP and in total are limited`() = runTest {
        val server = newServer(limits = Limits(roomCreationBurst = 2, roomCreationIntervalMs = 60_000, maxRooms = 3))
        val p = player(server, "p", ip = "1.1.1.1")
        repeat(2) { p.send(ClientMessage.CreateRoom) }
        assertIs<ServerMessage.Table>(p.connection.last)
        p.send(ClientMessage.CreateRoom)
        assertIs<ServerMessage.Error>(p.connection.last)
        now += 60_000
        p.send(ClientMessage.CreateRoom)
        assertIs<ServerMessage.Table>(p.connection.last)

        // Creating a new room leaves the old one, so only one counts per player.
        assertEquals(1, server.roomCount)
        (1..2).forEach { player(server, "q$it", ip = "2.2.2.$it").send(ClientMessage.CreateRoom) }
        val last = player(server, "last", ip = "3.3.3.3")
        last.send(ClientMessage.CreateRoom)
        assertIs<ServerMessage.Error>(last.connection.last)
    }

    @Test
    fun `guessing room codes is slowed down`() = runTest {
        val server = newServer(limits = Limits(failedJoinBurst = 3, failedJoinIntervalMs = 10_000))
        val host = player(server, "host", ip = "1.1.1.1")
        host.send(ClientMessage.CreateRoom)
        val code = host.table.room.code
        val guesser = player(server, "guesser", ip = "6.6.6.6")
        repeat(3) { guesser.send(ClientMessage.JoinRoom("ZZZZ$it")) }
        // Out of attempts: even the right code is refused for now.
        guesser.send(ClientMessage.JoinRoom(code))
        assertIs<ServerMessage.Error>(guesser.connection.last)
        // Others are not affected.
        val friend = player(server, "friend", ip = "7.7.7.7")
        friend.send(ClientMessage.JoinRoom(code))
        assertEquals(1, friend.seat)
        now += 10_000
        guesser.send(ClientMessage.JoinRoom(code))
        assertEquals(2, guesser.seat)
    }

    @Test
    fun `after a minute the bot decides for the human`() = runTest {
        val server = newServer(timing = Timing(turnTimeoutMs = 60_000))
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        host.send(ClientMessage.StartMatch)
        val ownActions = {
            host.connection.received.filterIsInstance<ServerMessage.Table>().count { it.game?.lastAction?.seat == 0 }
        }
        // Wait until the host has to decide; meanwhile the bots don't wait for a timeout.
        while (host.table.game!!.legalActions.none { it != Action.Schub }) {
            assertNull(host.table.game!!.timeLeftMs)
            advanceTimeBy(100)
        }
        assertEquals(60_000, host.table.game!!.timeLeftMs)

        advanceTimeBy(59_000)
        assertEquals(0, ownActions(), "nothing happens before the minute is over")
        advanceTimeBy(1_500)
        assertEquals(1, ownActions(), "the bot played for the host")
    }

    @Test
    fun `acting in time restarts the clock for the next decision`() = runTest {
        val server = newServer(timing = Timing(turnTimeoutMs = 60_000))
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        host.send(ClientMessage.StartMatch)
        repeat(3) {
            while (host.table.game!!.legalActions.none { it != Action.Schub }) advanceTimeBy(100)
            // Full time again, although the previous decision used up 50 s.
            assertEquals(60_000, host.table.game!!.timeLeftMs)
            advanceTimeBy(50_000)
            if (host.table.game!!.observation.phase != Phase.PLAYING && it > 0) return@runTest
            host.send(ClientMessage.Act(host.table.game!!.legalActions.first { a -> a != Action.Schub }))
        }
    }

    /** Plays announcements and cards for [humans] until [asking] may say Schub, then says it. */
    private fun TestScope.schubBy(asking: Player, humans: List<Player>) {
        var steps = 0
        while (Action.Schub !in asking.table.game!!.legalActions && steps++ < 1000) {
            humans.forEach { p -> p.table.game!!.legalActions.firstOrNull { it != Action.Schub }?.let { p.send(ClientMessage.Act(it)) } }
            advanceTimeBy(100)
        }
        asking.send(ClientMessage.Act(Action.Schub))
    }

    @Test
    fun `during a Schub the bot partner tells how good its cards are, the whole table hears it`() = runTest {
        val server = newServer(seed = 2)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))
        host.send(ClientMessage.StartMatch)
        assertEquals(1, guest.seat)
        assertFalse(guest.table.game!!.canAskPartner)
        schubBy(host, listOf(host, guest))
        assertTrue(guest.table.game!!.canAskPartner)
        assertFalse(host.table.game!!.canAskPartner, "the asking team waits for the answer")

        guest.send(ClientMessage.AskPartner)
        assertEquals(1, host.table.game!!.partnerQuestion)
        assertEquals(3, guest.table.game!!.partnerHint?.seat, "the bot answers at once")
        assertEquals(3, host.table.game!!.partnerHint?.seat, "the opponents hear it too")
        assertFalse(guest.table.game!!.canAskPartner, "asked once per Schub")

        guest.send(ClientMessage.Act(Action.Bleiben))
        assertNull(guest.table.game!!.partnerHint)
        assertNull(guest.table.game!!.partnerQuestion)
    }

    @Test
    fun `bots ask their partner before answering a Schub, the whole table hears it`() = runTest {
        val server = newServer(seed = 2)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        host.send(ClientMessage.StartMatch)
        schubBy(host, listOf(host))
        advanceTimeBy(Timing().speechPauseMs + Timing().botDelayMs + 1)
        val game = host.table.game!!
        val asking = assertNotNull(game.partnerQuestion, "a bot of the asked team asked its partner")
        assertEquals(1, asking % 2)
        assertEquals((asking + 2) % 4, game.partnerHint?.seat, "its bot partner answered at once")
        assertEquals(1, game.observation.hints.size)
        assertNotNull(game.observation.pendingSchub, "the answer to the Schub follows after a pause")
        advanceUntilIdle()
        assertNull(host.table.game!!.observation.pendingSchub)
    }

    @Test
    fun `a human partner answers the question`() = runTest {
        val server = newServer(seed = 2)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))
        val partner = player(server, "partner")
        partner.send(ClientMessage.JoinRoom(host.table.room.code))
        host.send(ClientMessage.StartMatch)
        assertEquals(2, partner.seat)
        schubBy(guest, listOf(host, guest, partner))

        host.send(ClientMessage.AskPartner)
        assertEquals(0, partner.table.game!!.partnerQuestion)
        assertNull(partner.table.game!!.partnerHint, "a human partner is not answered for")
        guest.send(ClientMessage.AnswerPartner(PartnerHint.ALL_TRICKS))
        assertNull(host.table.game!!.partnerHint, "only the asked partner may answer")

        partner.send(ClientMessage.AnswerPartner(PartnerHint.ONE_TRICK))
        assertEquals(SeatHint(2, PartnerHint.ONE_TRICK), host.table.game!!.partnerHint)
        assertEquals(SeatHint(2, PartnerHint.ONE_TRICK), guest.table.game!!.partnerHint)
    }

    @Test
    fun `the bot answers when the asked partner leaves`() = runTest {
        val server = newServer(seed = 2)
        val host = player(server, "host")
        host.send(ClientMessage.CreateRoom)
        val guest = player(server, "guest")
        guest.send(ClientMessage.JoinRoom(host.table.room.code))
        val partner = player(server, "partner")
        partner.send(ClientMessage.JoinRoom(host.table.room.code))
        host.send(ClientMessage.StartMatch)
        schubBy(guest, listOf(host, guest, partner))
        host.send(ClientMessage.AskPartner)
        partner.disconnect()
        assertEquals(2, host.table.game!!.partnerHint?.seat)
    }

    @Test
    fun `the bot partner answers Schadder before the Haubi is led`() = runTest {
        for (seed in 0 until 300) {
            val server = newServer(seed = seed)
            val host = player(server, "host")
            host.send(ClientMessage.CreateRoom)
            host.send(ClientMessage.StartMatch)
            var steps = 0
            while (host.table.game!!.observation.phase != Phase.PLAYING && steps++ < 100) {
                host.table.game!!.legalActions.firstOrNull { it != Action.Schub }?.let { host.send(ClientMessage.Act(it)) }
                advanceTimeBy(500)
            }
            advanceUntilIdle()
            if (!host.table.game!!.canAskSchadet) continue

            host.send(ClientMessage.AskSchadet)
            assertEquals(0, host.table.game!!.schadetQuestion)
            assertFalse(host.table.game!!.canAskSchadet, "asked once per round")
            advanceTimeBy(Timing().botDelayMs + 1)
            assertEquals(2, host.table.game!!.schadetAnswer?.seat)
            return@runTest
        }
        error("the host never led the first trick with the Haubi")
    }

    private fun TestScope.playUntilRoundOver(humans: List<Player>) {
        repeat(10_000) {
            advanceUntilIdle()
            val games = humans.associateWith { it.table.game!! }
            val phase = games.values.first().observation.phase
            if (phase == Phase.ROUND_OVER || phase == Phase.MATCH_OVER) return
            val p = humans.first { games[it]!!.legalActions.isNotEmpty() }
            val legal = games[p]!!.legalActions
            p.send(ClientMessage.Act(legal.firstOrNull { it != Action.Schub } ?: legal.first()))
        }
        error("Round did not end")
    }
}
