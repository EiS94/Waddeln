package com.schaubeck.waddeln.game

import android.content.Context
import androidx.annotation.StringRes
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import java.util.UUID

/**
 * How long bots think before acting, how long a finished trick stays on the table and how long
 * a speech bubble (announcement, Schub, answer) is shown. After a bot spoke, the next bot waits
 * until the bubble is gone.
 */
enum class GameSpeed(@StringRes val label: Int, val botDelayMs: Long, val trickPauseMs: Long, val speechMs: Long) {
    SLOW(R.string.speed_slow, 1600, 2600, 3000),
    NORMAL(R.string.speed_normal, 1100, 1900, 2200),
    FAST(R.string.speed_fast, 600, 1300, 1400),
}

/**
 * Strength of the opponents. The partner always plays at full strength.
 * [temperature] is the randomness of the ML bot (0 = always the best move), null = rule-based bot.
 */
enum class Difficulty(@StringRes val label: Int, @StringRes val description: Int, val temperature: Double?) {
    EASY(R.string.difficulty_easy, R.string.difficulty_easy_description, null),
    MEDIUM(R.string.difficulty_medium, R.string.difficulty_medium_description, 0.7),
    HARD(R.string.difficulty_hard, R.string.difficulty_hard_description, 0.0),
}

/** Wood of the table behind all screens. */
enum class TableStyle(@StringRes val label: Int) {
    DARK(R.string.table_dark),
    LIGHT(R.string.table_light),
}

data class Settings(
    val speed: GameSpeed = GameSpeed.NORMAL,
    val difficulty: Difficulty = Difficulty.HARD,
    /** Names per seat: you, left opponent, partner, right opponent. */
    val names: List<String> = List(PLAYER_COUNT) { "" },
    /** Nickname shown to the other players online. */
    val onlineName: String = "",
    /** WebSocket address of the game server. */
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** Shuffling, cards and tricks are heard. */
    val soundEffects: Boolean = true,
    /** Players say aloud what their speech bubbles show. */
    val voices: Boolean = true,
    val table: TableStyle = TableStyle.DARK,
    /** As Abheber the player chooses where to cut; otherwise the app cuts at random for them. */
    val selfCut: Boolean = true,
) {
    companion object {
        /** Names of the seats while the player has not named them. */
        val DEFAULT_NAMES = listOf(
            R.string.default_name_you,
            R.string.default_name_left,
            R.string.default_name_partner,
            R.string.default_name_right,
        )

        /** The public game server (a Raspberry Pi behind a Cloudflare tunnel). */
        const val DEFAULT_SERVER_URL = "wss://waddeln.schaumler.de/ws"
    }
}

/** Persists [Settings] in SharedPreferences. */
class SettingsStore(context: Context) {
    val defaultNames: List<String> = Settings.DEFAULT_NAMES.map(context::getString)

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        speed = enumOrDefault(prefs.getString(KEY_SPEED, null), GameSpeed.NORMAL),
        difficulty = enumOrDefault(prefs.getString(KEY_DIFFICULTY, null), Difficulty.HARD),
        names = List(PLAYER_COUNT) { prefs.getString("$KEY_NAME$it", null) ?: defaultNames[it] },
        onlineName = prefs.getString(KEY_ONLINE_NAME, null).orEmpty(),
        serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: Settings.DEFAULT_SERVER_URL,
        soundEffects = prefs.getBoolean(KEY_SOUND_EFFECTS, true),
        voices = prefs.getBoolean(KEY_VOICES, true),
        table = enumOrDefault(prefs.getString(KEY_TABLE, null), TableStyle.DARK),
        selfCut = prefs.getBoolean(KEY_SELF_CUT, true),
    )

    /**
     * Not backed up (see the backup rules): a phone restored from this one must get its own id,
     * or both would push each other off their seat on the server.
     */
    private val devicePrefs = context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)

    /** Random id of this installation; identifies the player on the server (e.g. after a reconnect). */
    fun deviceId(): String {
        devicePrefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        // Older versions kept the id with the settings (and so in the backup); it moves over once.
        val id = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString()
        devicePrefs.edit().putString(KEY_DEVICE_ID, id).apply()
        prefs.edit().remove(KEY_DEVICE_ID).apply()
        return id
    }

    fun save(settings: Settings) {
        prefs.edit().apply {
            putString(KEY_SPEED, settings.speed.name)
            putString(KEY_DIFFICULTY, settings.difficulty.name)
            settings.names.forEachIndexed { seat, name -> putString("$KEY_NAME$seat", name) }
            putString(KEY_ONLINE_NAME, settings.onlineName)
            // Only an address of the user's own is stored, so a later change of the default reaches everyone.
            if (settings.serverUrl.trim() == Settings.DEFAULT_SERVER_URL) {
                remove(KEY_SERVER_URL)
            } else {
                putString(KEY_SERVER_URL, settings.serverUrl)
            }
            putBoolean(KEY_SOUND_EFFECTS, settings.soundEffects)
            putBoolean(KEY_VOICES, settings.voices)
            putString(KEY_TABLE, settings.table.name)
            putBoolean(KEY_SELF_CUT, settings.selfCut)
        }.apply()
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val DEVICE_PREFS = "device"
        const val KEY_SPEED = "speed"
        const val KEY_DIFFICULTY = "difficulty"
        const val KEY_NAME = "name_"
        const val KEY_ONLINE_NAME = "online_name"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SOUND_EFFECTS = "sound_effects"
        const val KEY_VOICES = "voices"
        const val KEY_TABLE = "table"
        const val KEY_SELF_CUT = "self_cut"
    }
}
