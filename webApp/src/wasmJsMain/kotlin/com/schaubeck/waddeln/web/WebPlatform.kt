package com.schaubeck.waddeln.web

import com.schaubeck.waddeln.platform.BotThread
import com.schaubeck.waddeln.platform.KeyValueStore
import com.schaubeck.waddeln.platform.Log
import com.schaubeck.waddeln.platform.ModelLoader
import com.schaubeck.waddeln.platform.Platform
import com.schaubeck.waddeln.platform.TextFile
import com.schaubeck.waddeln.rules.ml.MlpQFunction
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The browser implementations of the platform services. [model] is the network (waddeln.mlp),
 * already downloaded; null = rule-based bots.
 */
fun webPlatform(model: ByteArray?): Platform {
    Log.sink = { level, tag, message, error ->
        val text = "$tag: $message" + (error?.let { " ($it)" } ?: "")
        if (level == Log.Level.WARN) consoleWarn(text) else consoleInfo(text)
    }
    return Platform(
        stores = { name -> LocalStorageStore(name) },
        matchFile = LocalStorageFile("match.json"),
        sockets = BrowserSockets,
        models = ModelLoader { model?.let(::MlpQFunction) },
        newBotThread = { MainThread },
        defaultServerUrl = pageServerUrl(),
        foreground = pageVisibleAgain(),
    )
}

/** The browser has one thread; the bots think between frames. */
private object MainThread : BotThread {
    override val dispatcher: CoroutineDispatcher = Dispatchers.Main
    override fun shutdown() {}
}

/**
 * One named area in localStorage: key `<name>.<key>`, all values as text. localStorage may be
 * missing or full (private mode, blocked storage); then nothing is kept and the game still runs.
 */
private class LocalStorageStore(name: String) : KeyValueStore {
    private val prefix = "$name."

    override fun getString(key: String): String? = read(prefix + key)
    override fun getBoolean(key: String, default: Boolean): Boolean = read(prefix + key)?.toBooleanStrictOrNull() ?: default
    override fun getInt(key: String): Int? = read(prefix + key)?.toIntOrNull()

    override fun keys(): Set<String> = guarded(emptySet()) {
        (0 until localStorage.length).mapNotNull { localStorage.key(it) }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) = write(prefix + key, value)
            override fun putBoolean(key: String, value: Boolean) = write(prefix + key, value.toString())
            override fun putInt(key: String, value: Int) = write(prefix + key, value.toString())
            override fun remove(key: String) = guarded(Unit) { localStorage.removeItem(prefix + key) }
            override fun clear() = keys().forEach { remove(it) }
        }.block()
    }
}

/** The saved match: one localStorage entry, written in one go. */
private class LocalStorageFile(private val key: String) : TextFile {
    override fun read(): String? = read(key)
    override fun write(text: String) = localStorage.setItem(key, text)
    override fun delete() = guarded(Unit) { localStorage.removeItem(key) }
}

private fun read(key: String): String? = guarded(null) { localStorage.getItem(key) }

private fun write(key: String, value: String) = guarded(Unit) { localStorage.setItem(key, value) }

private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    Log.w("Storage", "localStorage not usable", e)
    fallback
}

private fun consoleWarn(text: String): Unit = js("console.warn(text)")

private fun consoleInfo(text: String): Unit = js("console.info(text)")
