package com.schaubeck.waddeln.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.staticCompositionLocalOf
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.game.Speech
import com.schaubeck.waddeln.game.TableUi
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import java.util.Locale

/**
 * Sounds at the table: shuffling before the deal, playing a card, taking a trick, and the
 * players' voices saying what their speech bubbles show (Android's own text-to-speech, works
 * offline once a German voice is installed). Effects and voices can be switched off separately.
 */
class TableSound(context: Context) {
    var effectsOn = true
    var voicesOn = true
        set(value) {
            field = value
            if (!value) tts.stop()
        }

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val shuffleId = pool.load(context, R.raw.shuffle, 1)
    private val cardId = pool.load(context, R.raw.card_play, 1)
    private val trickId = pool.load(context, R.raw.trick_take, 1)

    private var ttsReady = false

    /** One voice per seat (or null = the engine's default), so the four players sound different. */
    private var seatVoices: List<Voice?> = List(PLAYER_COUNT) { null }
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) setUpVoices()
    }

    /** Speech that is said a little later (an answer right after its question). */
    private val later = Handler(Looper.getMainLooper())

    /** What [observe] saw last, per table (offline and online), to notice what changed. */
    private val seen = mutableMapOf<String, Seen>()

    private data class Seen(val round: Int, val played: Int, val speech: Set<Speech>)

    fun shuffle() = play(shuffleId, 0.8f)

    fun cardPlayed() = play(cardId, 0.9f)

    fun trickTaken() = play(trickId, 0.7f)

    /**
     * Compares [table] with what was seen of the table [key] before: a new deal shuffles, new
     * cards in the trick snap, fresh speech bubbles are spoken.
     */
    fun observe(key: String, table: TableUi) {
        val s = table.obs
        val played = s.completedTricks.sumOf { it.plays.size } + s.currentTrick.size
        val before = seen.put(key, Seen(s.roundNumber, played, table.speech.toSet()))
        val freshDeal = played == 0 && s.result == null
        when {
            before == null -> if (freshDeal) shuffle()
            // A new round, or a new match that starts again at round 1.
            s.roundNumber != before.round || played < before.played -> shuffle()
            played > before.played -> cardPlayed()
        }
        // Only what was just said; bubbles from while the table was not shown stay silent.
        val now = SystemClock.elapsedRealtime()
        table.speech
            .filter { it !in before?.speech.orEmpty() && now - it.at < FRESH_SPEECH_MS }
            .forEach { speech ->
                val wait = speech.at - now
                if (wait > 0) later.postDelayed({ say(speech.seat, speech.text) }, wait) else say(speech.seat, speech.text)
            }
    }

    fun say(seat: Int, text: String) {
        if (!voicesOn || !ttsReady) return
        // Some engines have no default voice; setting null would crash, so the current one stays then.
        (seatVoices[seat] ?: tts.defaultVoice)?.let { tts.voice = it }
        tts.setPitch(SEAT_PITCH[seat])
        tts.setSpeechRate(SEAT_RATE[seat])
        tts.speak(Pronunciation.spoken(text), TextToSpeech.QUEUE_ADD, null, "speech-${SystemClock.elapsedRealtime()}")
    }

    /** Stops talking, e.g. when the app goes to the background. */
    fun stop() {
        later.removeCallbacksAndMessages(null)
        if (ttsReady) tts.stop()
    }

    fun release() {
        later.removeCallbacksAndMessages(null)
        tts.shutdown()
        pool.release()
    }

    private fun play(id: Int, volume: Float) {
        if (effectsOn) pool.play(id, volume, volume, 1, 0, 1f)
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
        ttsReady = true
    }

    private companion object {
        /** Voice height and pace per seat, so players stay apart even with only one installed voice. */
        val SEAT_PITCH = listOf(1.0f, 0.8f, 1.15f, 0.92f)
        val SEAT_RATE = listOf(1.0f, 0.95f, 1.05f, 0.9f)

        const val FRESH_SPEECH_MS = 2000L
    }
}

/** The [TableSound] of the activity; null in previews. */
val LocalTableSound = staticCompositionLocalOf<TableSound?> { null }
