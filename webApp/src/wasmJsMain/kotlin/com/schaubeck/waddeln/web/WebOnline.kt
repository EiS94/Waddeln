package com.schaubeck.waddeln.web

import com.schaubeck.waddeln.platform.OnlineSocket
import com.schaubeck.waddeln.platform.OnlineSocketFactory
import com.schaubeck.waddeln.protocol.WS_PATH
import com.schaubeck.waddeln.protocol.parseRoomCode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The browser's own WebSocket. It answers the server's pings by itself, so an idle connection stays
 * open (the server pings every 20 s); it cannot send pings of its own.
 */
object BrowserSockets : OnlineSocketFactory {
    override fun connect(url: String, listener: OnlineSocket.Listener): OnlineSocket? {
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) return null
        val socket = BrowserSocket()
        val handle = openSocket(
            url,
            onOpen = { listener.onOpen(socket) },
            onMessage = { text -> listener.onMessage(socket, text) },
            onClose = { reason -> listener.onClosed(socket, reason.ifEmpty { null }) },
        ) ?: return null
        socket.handle = handle
        return socket
    }

    private class BrowserSocket : OnlineSocket {
        lateinit var handle: JsAny

        override fun send(text: String): Boolean = sendText(handle, text)

        override fun close() = closeSocket(handle)
    }
}

/**
 * The game server of the page itself: a page served by the server (https://host/play/) talks to
 * wss://host/ws; a page from a local development server to a game server on port 8765 of that host.
 */
fun pageServerUrl(): String {
    val protocol = pageProtocol()
    val host = pageHostname()
    val port = pagePort()
    return when {
        port.isNotEmpty() && port != "80" && port != "443" -> "ws://$host:8765$WS_PATH"
        protocol == "https:" -> "wss://$host$WS_PATH"
        else -> "ws://$host$WS_PATH"
    }
}

/** The room code in the page address (`/play/#K7QM2`), or null. */
fun roomCodeFromAddress(): String? = parseRoomCode(pageHash().removePrefix("#"))

/** Takes the room code out of the address again, so reloading the page does not join once more. */
fun clearRoomCodeFromAddress() = clearHash()

/** Emits whenever the page becomes visible again (tab or app back in front). */
fun pageVisibleAgain(): Flow<Unit> = callbackFlow {
    val listener = onVisible { trySend(Unit) }
    awaitClose { removeVisibilityListener(listener) }
}

/** Emits the room code whenever an invitation link is opened while the page is already open. */
fun roomCodeChanges(): Flow<String> = callbackFlow {
    val listener = onHashChange { roomCodeFromAddress()?.let { trySend(it) } }
    awaitClose { removeHashListener(listener) }
}

/** Opens a WebSocket; null if the browser refuses the address. */
private fun openSocket(url: String, onOpen: () -> Unit, onMessage: (String) -> Unit, onClose: (String) -> Unit): JsAny? = js(
    """
    {
      let ws;
      try { ws = new WebSocket(url); } catch (e) { return null; }
      ws.onopen = () => onOpen();
      ws.onmessage = (e) => { if (typeof e.data === 'string') onMessage(e.data); };
      ws.onclose = (e) => onClose(e.wasClean ? '' : ('code ' + e.code + (e.reason ? ', ' + e.reason : '')));
      return ws;
    }
    """,
)

private fun sendText(ws: JsAny, text: String): Boolean = js(
    "{ if (ws.readyState !== 1) return false; try { ws.send(text); return true; } catch (e) { return false; } }",
)

private fun closeSocket(ws: JsAny): Unit = js("{ try { ws.close(1000); } catch (e) {} }")

private fun pageProtocol(): String = js("location.protocol")

private fun pageHostname(): String = js("location.hostname")

private fun pagePort(): String = js("location.port")

private fun pageHash(): String = js("location.hash")

private fun clearHash(): Unit = js("history.replaceState(history.state, '', location.pathname + location.search)")

private fun onVisible(callback: () -> Unit): JsAny = js(
    "{ const l = () => { if (document.visibilityState === 'visible') callback(); }; document.addEventListener('visibilitychange', l); return l; }",
)

private fun removeVisibilityListener(listener: JsAny): Unit = js("document.removeEventListener('visibilitychange', listener)")

private fun onHashChange(callback: () -> Unit): JsAny = js(
    "{ const l = () => callback(); window.addEventListener('hashchange', l); return l; }",
)

private fun removeHashListener(listener: JsAny): Unit = js("window.removeEventListener('hashchange', listener)")
