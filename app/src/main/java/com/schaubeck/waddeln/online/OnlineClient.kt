package com.schaubeck.waddeln.online

import android.util.Log
import com.schaubeck.waddeln.protocol.ClientMessage
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.protocol.decodeServerMessage
import com.schaubeck.waddeln.protocol.encode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * WebSocket connection to the game server that reconnects by itself.
 * After every (re)connect it sends [hello], so the server puts the player back on their seat.
 * All callbacks are delivered on the main thread.
 */
class OnlineClient(
    private val scope: CoroutineScope,
    private val onConnected: (Boolean) -> Unit,
    private val onMessage: (ServerMessage) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var url: String? = null
    private var hello: ClientMessage.Hello? = null
    private var reconnectJob: Job? = null
    private var failures = 0

    /** Connects to [url] (or keeps the existing connection to it) and introduces the player. */
    fun start(url: String, hello: ClientMessage.Hello) {
        val sameServer = url == this.url && socket != null
        this.hello = hello
        if (sameServer) {
            send(hello) // e.g. a changed nickname
            return
        }
        stop()
        this.url = url
        connect()
    }

    fun stop() {
        url = null
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        failures = 0
    }

    fun send(message: ClientMessage): Boolean = socket?.send(message.encode()) ?: false

    private fun connect() {
        val target = url ?: return
        val request = try {
            Request.Builder().url(target).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid server address $target", e)
            onConnected(false)
            return
        }
        socket = http.newWebSocket(request, Listener())
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onMain {
            if (webSocket !== socket) return@onMain
            failures = 0
            hello?.let { webSocket.send(it.encode()) }
            onConnected(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                decodeServerMessage(text)
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable message: $text", e)
                return
            }
            onMain { if (webSocket === socket) onMessage(message) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onMain { lost(webSocket) }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onMain {
            Log.i(TAG, "Connection failed: ${t.message}")
            lost(webSocket)
        }
    }

    /** Tries again after a growing pause (1, 2, 4, 8 s, then every 10 s). */
    private fun lost(webSocket: WebSocket) {
        if (webSocket !== socket) return
        socket = null
        onConnected(false)
        if (url == null) return
        val pauseMs = minOf(1000L shl failures.coerceAtMost(3), 10_000L)
        failures++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(pauseMs)
            if (socket == null) connect()
        }
    }

    private companion object {
        const val TAG = "OnlineClient"
    }
}
