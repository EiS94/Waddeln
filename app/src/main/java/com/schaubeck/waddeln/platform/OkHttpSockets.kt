package com.schaubeck.waddeln.platform

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** WebSockets with OkHttp; it sends a ping every 20 s, so a dead connection is noticed. */
class OkHttpSockets : OnlineSocketFactory {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun connect(url: String, listener: OnlineSocket.Listener): OnlineSocket? {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid server address $url", e)
            return null
        }
        val socket = Socket()
        socket.ws = http.newWebSocket(request, socket.Bridge(listener))
        return socket
    }

    private class Socket : OnlineSocket {
        lateinit var ws: WebSocket

        override fun send(text: String): Boolean = ws.send(text)

        override fun close() {
            ws.close(1000, null)
        }

        inner class Bridge(private val listener: OnlineSocket.Listener) : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(this@Socket)

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(this@Socket, text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(this@Socket, null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onClosed(this@Socket, t.message ?: t.toString())
        }
    }

    private companion object {
        const val TAG = "OnlineClient"
    }
}
