package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.protocol.WS_PATH
import com.schaubeck.waddeln.protocol.decodeClientMessage
import com.schaubeck.waddeln.protocol.encode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("com.schaubeck.waddeln.server.Main")

/** How often a status line is logged while somebody is online. */
private const val STATUS_MINUTES = 15

/** Messages waiting for one connection; far more than an app that reads them ever needs. */
private const val MAX_QUEUED_MESSAGES = 64

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8765
    // All game logic runs on one thread, so rooms need no locking.
    val gameThread = Dispatchers.Default.limitedParallelism(1)
    val errors = CoroutineExceptionHandler { _, e -> log.error("Bot failed", e) }
    val scope = CoroutineScope(SupervisorJob() + gameThread + errors)
    // Played matches as JSON lines in a file that outlives the container (its log is gone after a redeploy).
    val matchLog = System.getenv("MATCH_LOG")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?.also { log.info("Matches are written to {}", it) }
        ?.let(::MatchFile) ?: MatchLog.NONE
    val server = GameServer(scope, ModelBots.load(), matchLog = matchLog)
    scope.launch {
        var minutes = 0
        while (true) {
            delay(1.minutes)
            server.cleanup()
            if (++minutes % STATUS_MINUTES == 0 && server.active) log.info("Status: {}", server.status)
        }
    }
    // The browser version (webApp distribution); without it /play/ answers 404.
    val webDir = System.getenv("WEB_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    if (webDir != null) log.info("Web app from {}", webDir)
    log.info("Waddeln server listening on port {}", port)
    embeddedServer(CIO, port = port) { waddeln(server, gameThread, webDir) }.start(wait = true)
}

/** Installs the WebSocket endpoint [WS_PATH], a health check at `/health`, the [invites] and the [webApp] from [webDir]. */
fun Application.waddeln(server: GameServer, gameThread: CoroutineDispatcher, webDir: Path? = null) {
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 45.seconds
        maxFrameSize = 16 * 1024
    }
    // HEAD requests (link checkers, monitoring) get the headers of the GET routes.
    install(AutoHeadResponse)
    routing {
        get("/health") { call.respondText("ok") }
        invites()
        webApp(webDir)

        webSocket(WS_PATH) {
            val session = this
            val outgoing = Channel<ServerMessage>(MAX_QUEUED_MESSAGES)
            val connection = object : Connection {
                override fun send(message: ServerMessage) {
                    // An app that stops reading must not fill the memory: it is disconnected and reconnects.
                    if (outgoing.trySend(message).isFailure && !outgoing.isClosedForSend) {
                        log.warn("Connection does not read its messages, closing it")
                        outgoing.close()
                        session.cancel()
                    }
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
                            server.onUnreadableMessage(client)
                        } else {
                            try {
                                server.onMessage(client, message)
                            } catch (e: Exception) {
                                log.error("Failed to handle {}", message, e)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // A phone that went to sleep or lost its network stops answering pings: an ordinary disconnect.
                log.info("Connection lost: {}", e.message)
            } finally {
                withContext(NonCancellable + gameThread) { server.disconnect(client) }
                sender.cancel()
            }
        }
    }
}
