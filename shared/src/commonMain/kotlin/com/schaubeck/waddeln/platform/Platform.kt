package com.schaubeck.waddeln.platform

import com.schaubeck.waddeln.game.Settings
import com.schaubeck.waddeln.rules.ml.QFunction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.TimeSource

/**
 * What the shared code needs from the platform it runs on. The Android app and the web app each
 * create one and hand it to [com.schaubeck.waddeln.WaddelnApp]. The view models keep it, so it must
 * not hold on to an Android activity (unlike [Sharing], which is given to the UI only).
 */
class Platform(
    /** Named key-value areas (settings, device id, statistics). */
    val stores: KeyValueStores,
    /** Where the running offline match is kept. */
    val matchFile: TextFile,
    val sockets: OnlineSocketFactory,
    /** Loads the trained network; null = rule-based bots. Called on the [BotThread]. */
    val models: ModelLoader,
    /** A new thread (or what stands in for it) for the bots of one offline game. */
    val newBotThread: () -> BotThread,
    /** Game server used while the player has not entered one of their own. */
    val defaultServerUrl: String = Settings.DEFAULT_SERVER_URL,
    /**
     * Emits when the app is in front again after it was hidden. Browsers on phones cut connections of
     * hidden tabs; the online game then reconnects at once instead of waiting for its next try.
     */
    val foreground: Flow<Unit> = emptyFlow(),
)

/** One named area of key-value pairs, like an Android SharedPreferences file. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean

    /** The value of [key] if it is a number, otherwise null. */
    fun getInt(key: String): Int?
    fun keys(): Set<String>

    /** Applies all changes of [block] together. */
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun putBoolean(key: String, value: Boolean)
        fun putInt(key: String, value: Int)
        fun remove(key: String)
        fun clear()
    }
}

fun interface KeyValueStores {
    /** The area [name]; on Android the SharedPreferences file of that name. */
    fun open(name: String): KeyValueStore
}

/** A text that is always written as a whole: after a crash it is either the old or the new one. */
interface TextFile {
    /** The text, or null if there is none. Throws if it cannot be read. */
    fun read(): String?

    /** Throws if it cannot be written; then the old text stays. */
    fun write(text: String)
    fun delete()
}

/** Opens WebSocket connections. */
fun interface OnlineSocketFactory {
    /** Starts connecting to [url]; null if [url] is no valid address. */
    fun connect(url: String, listener: OnlineSocket.Listener): OnlineSocket?
}

/** One WebSocket connection. Its listener may be called on any thread. */
interface OnlineSocket {
    /** False if the connection is not open (any more). */
    fun send(text: String): Boolean

    /** Closes normally; the listener then gets [Listener.onClosed]. */
    fun close()

    interface Listener {
        fun onOpen(socket: OnlineSocket)
        fun onMessage(socket: OnlineSocket, text: String)

        /** The connection ended or could not be made; [reason] is for the log. */
        fun onClosed(socket: OnlineSocket, reason: String?)
    }
}

fun interface ModelLoader {
    /** The network of the ML bots, or null if there is none (then the rule-based bot plays). */
    fun load(): QFunction?
}

/**
 * Where the bots think. On Android a thread of its own, so the table never stutters; in the browser
 * the only thread there is.
 */
interface BotThread {
    val dispatcher: CoroutineDispatcher

    /** Lets work that was already started finish, then ends the thread. */
    fun shutdown()
}

/** Copying and sharing texts (room codes, invitations). */
interface Sharing {
    /** Copies [text]; [confirmation] is shown where the system does not confirm by itself. */
    fun copy(label: String, text: String, confirmation: String)

    /** Offers [text] to other apps; [chooserTitle] heads the list of apps where there is one. */
    fun share(text: String, chooserTitle: String)
}

/** Milliseconds since some fixed point; only differences mean something. Never goes backwards. */
fun elapsedMs(): Long = START.elapsedNow().inWholeMilliseconds

private val START = TimeSource.Monotonic.markNow()

/** Logging of the platform (Android: logcat). Set once at start; without it messages go to the console. */
object Log {
    var sink: (level: Level, tag: String, message: String, error: Throwable?) -> Unit = { level, tag, message, error ->
        println("$level/$tag: $message" + (error?.let { " ($it)" } ?: ""))
    }

    enum class Level { INFO, WARN }

    fun i(tag: String, message: String) = sink(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink(Level.WARN, tag, message, error)
}
