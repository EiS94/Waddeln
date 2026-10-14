package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.protocol.WS_PATH
import com.schaubeck.waddeln.protocol.decodeClientMessage
import com.schaubeck.waddeln.protocol.encode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("com.schaubeck.waddeln.server.Main")

/** How often a status line is logged while somebody is online. */
private const val STATUS_MINUTES = 15

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8765
    // All game logic runs on one thread, so rooms need no locking.
    val gameThread = Dispatchers.Default.limitedParallelism(1)
    val errors = CoroutineExceptionHandler { _, e -> log.error("Bot failed", e) }
    val scope = CoroutineScope(SupervisorJob() + gameThread + errors)
    val server = GameServer(scope, ModelBots.load())
    scope.launch {
        var minutes = 0
        while (true) {
            delay(1.minutes)
            server.cleanup()
            if (++minutes % STATUS_MINUTES == 0 && server.active) log.info("Status: {}", server.status)
        }
    }
    log.info("Waddeln server listening on port {}", port)
    embeddedServer(CIO, port = port) { waddeln(server, gameThread) }.start(wait = true)
}

/** Installs the WebSocket endpoint [WS_PATH] and a health check at `/health`. */
fun Application.waddeln(server: GameServer, gameThread: CoroutineDispatcher) {
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 45.seconds
        maxFrameSize = 16 * 1024
    }
    routing {
        get("/health") { call.respondText("ok") }

        webSocket(WS_PATH) {
            val outgoing = Channel<ServerMessage>(Channel.UNLIMITED)
            val connection = object : Connection {
                override fun send(message: ServerMessage) {
                    outgoing.trySend(message)
                }

                override fun close() {
                    outgoing.close()
                }
            }
            // Behind the Cloudflare tunnel the real address is in this header (the port is only reachable locally).
            val ip = call.request.headers["CF-Connecting-IP"] ?: call.request.origin.remoteHost
            val client = withContext(gameThread) { server.connect(connection, ip) }
            if (client == null) {
                send(Frame.Text(ServerMessage.Error("Der Server ist gerade voll. Versuch es später noch einmal.").encode()))
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "full"))
                return@webSocket
            }
            val sender = launch {
                for (message in outgoing) send(Frame.Text(message.encode()))
                close() // the server dropped this connection (same player connected again)
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = runCatching { decodeClientMessage(frame.readText()) }.getOrNull()
                    withContext(gameThread) {
                        if (message == null) {
                            client.send(ServerMessage.Error("Unbekannte Nachricht."))
                        } else {
                            try {
                                server.onMessage(client, message)
                            } catch (e: Exception) {
                                log.error("Failed to handle {}", message, e)
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + gameThread) { server.disconnect(client) }
                sender.cancel()
            }
        }
    }
}
