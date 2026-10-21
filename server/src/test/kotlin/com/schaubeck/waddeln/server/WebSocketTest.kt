package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.ClientMessage
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.protocol.WS_PATH
import com.schaubeck.waddeln.protocol.decodeServerMessage
import com.schaubeck.waddeln.protocol.encode
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class WebSocketTest {

    @Test
    fun `app and server talk JSON over the WebSocket`() = testApplication {
        val gameThread = Dispatchers.Default.limitedParallelism(1)
        val server = GameServer(CoroutineScope(SupervisorJob() + gameThread), { RuleBasedAgent() }, Timing(), Random(1))
        application { waddeln(server, gameThread) }
        val client = createClient { install(WebSockets) }

        assertEquals("ok", client.get("/health").bodyAsText())

        client.webSocket(WS_PATH) {
            suspend fun receive() = decodeServerMessage((incoming.receive() as Frame.Text).readText())

            send(Frame.Text(ClientMessage.Hello("device-1", "Eike").encode()))
            assertEquals(ServerMessage.NoRoom, receive())

            send(Frame.Text(ClientMessage.CreateRoom.encode()))
            val table = assertIs<ServerMessage.Table>(receive())
            assertEquals("Eike", table.room.seats[0].name)

            send(Frame.Text("""{"type":"nonsense"}"""))
            assertIs<ServerMessage.Error>(receive())

            send(Frame.Text(ClientMessage.StartMatch.encode()))
            val started = assertIs<ServerMessage.Table>(receive())
            val cut = assertNotNull(started.game!!.cut)
            assertEquals(0, started.game!!.observation.hand.size)
            // We cut ourselves, or a bot does after a moment.
            if (cut.abheber == started.yourSeat) send(Frame.Text(ClientMessage.Cut(16).encode()))
            val dealt = assertIs<ServerMessage.Table>(receive())
            assertEquals(5, dealt.game!!.observation.hand.size)
        }
    }
}
