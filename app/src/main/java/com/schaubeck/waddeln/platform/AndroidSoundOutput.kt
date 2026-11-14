package com.schaubeck.waddeln.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.schaubeck.waddeln.game.Gender
import com.schaubeck.waddeln.resources.Res
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.sound.Effect
import com.schaubeck.waddeln.sound.SoundOutput
import java.util.Locale

/** Effects with [SoundPool], voices with Android's own text-to-speech. */
class AndroidSoundOutput(context: Context) : SoundOutput {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    /** The sound files are Compose resources, i.e. assets of the APK (stored uncompressed). */
    private val ids: Map<Effect, Int> = Effect.entries.associateWith { effect ->
        context.assets.openFd(Res.getUri(effect.file).removePrefix(ASSET_PREFIX)).use { pool.load(it, 1) }
    }

    private var ttsReady = false

    /** One voice per seat (or null = the engine's default), so the four players sound different. */
    private var seatVoices: List<Voice?> = List(PLAYER_COUNT) { null }

    /** Installed German voices recognised as male or female by their name (engines do not tell otherwise). */
    private var voicesOf: Map<Gender, List<Voice>> = emptyMap()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) setUpVoices()
    }

    override fun play(effect: Effect) {
        pool.play(ids.getValue(effect), effect.volume, effect.volume, 1, 0, 1f)
    }

    override fun say(seat: Int, text: String, gender: Gender?, slot: Int) {
        if (!ttsReady) return
        val matching = gender?.let { voicesOf[it] }.orEmpty()
        val voice = if (matching.isNotEmpty()) matching[slot % matching.size] else seatVoices[seat]
        // Some engines have no default voice; setting null would crash, so the current one stays then.
        (voice ?: tts.defaultVoice)?.let { tts.voice = it }
        val pitch = when {
            gender == null -> SEAT_PITCH[seat]
            matching.isNotEmpty() -> SEAT_VARIATION[seat]
            else -> GENDER_PITCH.getValue(gender) * SEAT_VARIATION[seat]
        }
        tts.setPitch(pitch)
        tts.setSpeechRate(SEAT_RATE[seat])
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "speech-${SystemClock.elapsedRealtime()}")
    }

    override fun stopSpeaking() {
        if (ttsReady) tts.stop()
    }

    override fun release() {
        tts.shutdown()
        pool.release()
    }

    private fun setUpVoices() {
        if (tts.setLanguage(Locale.GERMAN) < TextToSpeech.LANG_AVAILABLE) return
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val german = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { voice ->
                voice.locale.language == "de" &&
                    !voice.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features
            }
            .sortedWith(compareBy({ it.locale.country != "DE" }, { it.name }))
        if (german.isNotEmpty()) seatVoices = List(PLAYER_COUNT) { german[it % german.size] }
        voicesOf = german.mapNotNull { voice -> genderOf(voice.name)?.let { it to voice } }
            .groupBy({ it.first }, { it.second })
        ttsReady = true
    }

    private companion object {
        const val ASSET_PREFIX = "file:///android_asset/"

        /** Voice height and pace per seat, so players stay apart even with only one installed voice. */
        val SEAT_PITCH = listOf(1.0f, 0.8f, 1.15f, 0.92f)
        val SEAT_RATE = listOf(1.0f, 0.95f, 1.05f, 0.9f)

        /** With a gender: small differences between seats on top of the voice's own height. */
        val SEAT_VARIATION = listOf(1.0f, 0.95f, 1.05f, 0.98f)

        /** Voice height for a gender when no matching voice is installed. */
        val GENDER_PITCH = mapOf(Gender.MALE to 0.8f, Gender.FEMALE to 1.2f, Gender.DIVERSE to 1.0f)

        /**
         * Gender of a voice by its name: Google's German voices (x-deg male; x-dea, x-deb, x-nfh female),
         * Samsung's (SMTm / SMTf) and names that say "male"/"female". Null if unknown.
         */
        fun genderOf(name: String): Gender? {
            val n = name.lowercase()
            return when {
                "female" in n || Regex("x-(dea|deb|nfh)|smtf").containsMatchIn(n) -> Gender.FEMALE
                "male" in n || Regex("x-deg|smtm").containsMatchIn(n) -> Gender.MALE
                else -> null
            }
        }
    }
}
