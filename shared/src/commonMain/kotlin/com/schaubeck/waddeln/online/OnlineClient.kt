package com.schaubeck.waddeln.online

import com.schaubeck.waddeln.platform.Log
import com.schaubeck.waddeln.platform.OnlineSocket
import com.schaubeck.waddeln.platform.OnlineSocketFactory
import com.schaubeck.waddeln.protocol.ClientMessage
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.protocol.decodeServerMessage
import com.schaubeck.waddeln.protocol.encode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WebSocket connection to the game server that reconnects by itself.
 * After every (re)connect it sends [hello], so the server puts the player back on their seat.
 * All callbacks are delivered on the main thread.
 */
class OnlineClient(
    private val sockets: OnlineSocketFactory,
    private val scope: CoroutineScope,
    private val onConnected: (Boolean) -> Unit,
    private val onMessage: (ServerMessage) -> Unit,
) {
    private var socket: OnlineSocket? = null
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
        socket?.close()
        socket = null
        failures = 0
    }

    fun send(message: ClientMessage): Boolean = socket?.send(message.encode()) ?: false

    private fun connect() {
        val target = url ?: return
        val opened = sockets.connect(target, Listener())
        if (opened == null) {
            Log.w(TAG, "Invalid server address $target")
            onConnected(false)
            return
        }
        socket = opened
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private inner class Listener : OnlineSocket.Listener {
        override fun onOpen(socket: OnlineSocket) = onMain {
            if (socket !== this@OnlineClient.socket) return@onMain
            failures = 0
            hello?.let { socket.send(it.encode()) }
            onConnected(true)
        }

        override fun onMessage(socket: OnlineSocket, text: String) {
            val message = try {
                decodeServerMessage(text)
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable message: $text", e)
                return
            }
            onMain { if (socket === this@OnlineClient.socket) onMessage(message) }
        }

        override fun onClosed(socket: OnlineSocket, reason: String?) = onMain {
            if (reason != null) Log.i(TAG, "Connection failed: $reason")
            lost(socket)
        }
    }

    /** Tries again after a growing pause (1, 2, 4, 8 s, then every 10 s). */
    private fun lost(closed: OnlineSocket) {
        if (closed !== socket) return
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
