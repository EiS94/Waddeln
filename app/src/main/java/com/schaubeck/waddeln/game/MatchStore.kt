package com.schaubeck.waddeln.game

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps the running offline match in a file, so it goes on after Android ended the app in the
 * background. Written atomically: a match is either saved completely or not at all.
 */
class MatchStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    /** The saved match, or null if there is none or it cannot be read (e.g. from an incompatible version). */
    fun load(): SavedMatch? = try {
        if (!file.baseFile.exists()) null else decode(file.readFully().decodeToString())
    } catch (e: Exception) {
        Log.w(TAG, "Saved match unreadable, starting fresh", e)
        clear()
        null
    }

    fun save(match: SavedMatch) {
        val out = try {
            file.startWrite()
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the match", e)
            return
        }
        try {
            out.write(encode(match).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the match", e)
            file.failWrite(out)
        }
    }

    fun clear() = file.delete()

    companion object {
        private const val FILE_NAME = "match.json"
        private const val TAG = "MatchStore"

        /** Fields added later have defaults, fields removed later are ignored. */
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(match: SavedMatch): String = json.encodeToString(SavedMatch.serializer(), match)

        /** Null if [text] holds no match. */
        fun decode(text: String): SavedMatch? =
            json.decodeFromString(SavedMatch.serializer(), text).takeIf { it.pending != null || it.state != null }
    }
}
