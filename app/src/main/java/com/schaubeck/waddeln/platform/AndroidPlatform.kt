package com.schaubeck.waddeln.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/** The Android implementations of the shared code's platform services. */
fun androidPlatform(context: Context): Platform {
    val app = context.applicationContext
    Log.sink = { level, tag, message, error ->
        when (level) {
            Log.Level.INFO -> android.util.Log.i(tag, message, error)
            Log.Level.WARN -> android.util.Log.w(tag, message, error)
        }
    }
    return Platform(
        stores = { name -> PreferencesStore(app.getSharedPreferences(name, Context.MODE_PRIVATE)) },
        matchFile = AtomicTextFile(File(app.filesDir, "match.json")),
        sockets = OkHttpSockets(),
        models = AssetModelLoader(app),
        newBotThread = ::ExecutorBotThread,
    )
}

private class PreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun getInt(key: String): Int? = prefs.all[key] as? Int
    override fun keys(): Set<String> = prefs.all.keys

    override fun edit(block: KeyValueStore.Editor.() -> Unit) = prefs.edit {
        val editor = this
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) { editor.putString(key, value) }
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun remove(key: String) { editor.remove(key) }
            override fun clear() { editor.clear() }
        }.block()
    }
}

/** Written atomically: after a crash the file holds either the old or the new text. */
private class AtomicTextFile(file: File) : TextFile {
    private val file = AtomicFile(file)

    override fun read(): String? = if (!file.baseFile.exists()) null else file.readFully().decodeToString()

    override fun write(text: String) {
        val out = file.startWrite()
        try {
            out.write(text.encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    override fun delete() = file.delete()
}

/** The bots think on a thread of their own, one step at a time, so the table never stutters. */
private class ExecutorBotThread : BotThread {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "bots") }
    override val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    override fun shutdown() = executor.shutdown()
}

/** Needs the activity (the share sheet is started from it). */
class AndroidSharing(private val context: Context) : Sharing {
    override fun copy(label: String, text: String, confirmation: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        // From Android 13 on, the system confirms the copy itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
        }
    }

    override fun share(text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}
