package com.schaubeck.waddeln.game

import android.content.Context
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import java.util.UUID

/**
 * How long bots think before acting, how long a finished trick stays on the table and how long
 * a speech bubble (announcement, Schub, answer) is shown. After a bot spoke, the next bot waits
 * until the bubble is gone.
 */
enum class GameSpeed(val label: String, val botDelayMs: Long, val trickPauseMs: Long, val speechMs: Long) {
    SLOW("Langsam", 1600, 2600, 3000),
    NORMAL("Normal", 1100, 1900, 2200),
    FAST("Schnell", 600, 1300, 1400),
}

/**
 * Strength of the opponents. The partner always plays at full strength.
 * [temperature] is the randomness of the ML bot (0 = always the best move), null = rule-based bot.
 */
enum class Difficulty(val label: String, val description: String, val temperature: Double?) {
    EASY("Leicht", "Einfache Regel-Bots", null),
    MEDIUM("Mittel", "KI, macht manchmal Fehler", 0.7),
    HARD("Schwer", "KI in voller Stärke", 0.0),
}

data class Settings(
    val speed: GameSpeed = GameSpeed.NORMAL,
    val difficulty: Difficulty = Difficulty.HARD,
    /** Names per seat: you, left opponent, partner, right opponent. */
    val names: List<String> = DEFAULT_NAMES,
    /** Nickname shown to the other players online. */
    val onlineName: String = "",
    /** WebSocket address of the game server. */
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** Shuffling, cards and tricks are heard. */
    val soundEffects: Boolean = true,
    /** Players say aloud what their speech bubbles show. */
    val voices: Boolean = true,
) {
    fun name(seat: Int): String = names[seat].ifBlank { DEFAULT_NAMES[seat] }

    companion object {
        val DEFAULT_NAMES = listOf("Du", "Links", "Partner", "Rechts")

        /** The public game server (a Raspberry Pi behind a Cloudflare tunnel). */
        const val DEFAULT_SERVER_URL = "wss://waddeln.schaumler.de/ws"
    }
}

/** Persists [Settings] in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        speed = enumOrDefault(prefs.getString(KEY_SPEED, null), GameSpeed.NORMAL),
        difficulty = enumOrDefault(prefs.getString(KEY_DIFFICULTY, null), Difficulty.HARD),
        names = List(PLAYER_COUNT) { prefs.getString("$KEY_NAME$it", null) ?: Settings.DEFAULT_NAMES[it] },
        onlineName = prefs.getString(KEY_ONLINE_NAME, null).orEmpty(),
        serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: Settings.DEFAULT_SERVER_URL,
        soundEffects = prefs.getBoolean(KEY_SOUND_EFFECTS, true),
        voices = prefs.getBoolean(KEY_VOICES, true),
    )

    /** Random id of this installation; identifies the player on the server (e.g. after a reconnect). */
    fun deviceId(): String = prefs.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    fun save(settings: Settings) {
        prefs.edit().apply {
            putString(KEY_SPEED, settings.speed.name)
            putString(KEY_DIFFICULTY, settings.difficulty.name)
            settings.names.forEachIndexed { seat, name -> putString("$KEY_NAME$seat", name) }
            putString(KEY_ONLINE_NAME, settings.onlineName)
            putString(KEY_SERVER_URL, settings.serverUrl)
            putBoolean(KEY_SOUND_EFFECTS, settings.soundEffects)
            putBoolean(KEY_VOICES, settings.voices)
        }.apply()
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_SPEED = "speed"
        const val KEY_DIFFICULTY = "difficulty"
        const val KEY_NAME = "name_"
        const val KEY_ONLINE_NAME = "online_name"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SOUND_EFFECTS = "sound_effects"
        const val KEY_VOICES = "voices"
    }
}
