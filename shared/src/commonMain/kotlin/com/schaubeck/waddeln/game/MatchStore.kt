package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.platform.Log
import com.schaubeck.waddeln.platform.TextFile
import kotlinx.serialization.json.Json

/**
 * Keeps the running offline match, so it goes on after Android ended the app in the background
 * (or the browser tab was closed). Written as a whole: a match is either saved completely or not at all.
 */
class MatchStore(private val file: TextFile) {

    /** The saved match, or null if there is none or it cannot be read (e.g. from an incompatible version). */
    fun load(): SavedMatch? = try {
        file.read()?.let(::decode)
    } catch (e: Exception) {
        Log.w(TAG, "Saved match unreadable, starting fresh", e)
        clear()
        null
    }

    fun save(match: SavedMatch) {
        try {
            file.write(encode(match))
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the match", e)
        }
    }

    fun clear() = file.delete()

    companion object {
        private const val TAG = "MatchStore"

        /** Fields added later have defaults, fields removed later are ignored. */
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(match: SavedMatch): String = json.encodeToString(SavedMatch.serializer(), match)

        /** Null if [text] holds no match. */
        fun decode(text: String): SavedMatch? =
            json.decodeFromString(SavedMatch.serializer(), text).takeIf { it.pending != null || it.state != null }
    }
}
