package com.schaubeck.waddeln.sound

import androidx.compose.runtime.staticCompositionLocalOf
import com.schaubeck.waddeln.game.Gender
import com.schaubeck.waddeln.game.Speech
import com.schaubeck.waddeln.game.TableUi
import com.schaubeck.waddeln.platform.elapsedMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The three sounds of the table. */
enum class Effect(
    /** File under composeResources/files. */
    val file: String,
    val volume: Float,
) {
    SHUFFLE("files/shuffle.wav", 0.8f),
    CARD("files/card_play.mp3", 0.9f),
    TRICK("files/trick_take.wav", 0.7f),
}

/** What the platform plays and says. */
interface SoundOutput {
    fun play(effect: Effect)

    /**
     * Says [text] (already in [Pronunciation]) in the voice of [seat]. With a [gender] a matching voice
     * is used if one is installed, otherwise the pitch makes the difference; [slot] counts the seats
     * before with the same gender.
     */
    fun say(seat: Int, text: String, gender: Gender?, slot: Int)

    /** Stops talking. */
    fun stopSpeaking()
    fun release()
}

/**
 * Sounds at the table: shuffling before the deal, playing a card, taking a trick, and the
 * players' voices saying what their speech bubbles show (on Android its own text-to-speech, works
 * offline once a German voice is installed). Effects and voices can be switched off separately.
 */
class TableSound(private val output: SoundOutput) {
    var effectsOn = true
    var voicesOn = true
        set(value) {
            field = value
            if (!value) output.stopSpeaking()
        }

    /** Speech that is said a little later (an answer right after its question). */
    private val later = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** What [observe] saw last, per table (offline and online), to notice what changed. */
    private val seen = mutableMapOf<String, Seen>()

    private data class Seen(val round: Int, val played: Int, val speech: Set<Speech>)

    fun shuffle() = play(Effect.SHUFFLE)

    fun cardPlayed() = play(Effect.CARD)

    fun trickTaken() = play(Effect.TRICK)

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
        val now = elapsedMs()
        table.speech
            .filter { it !in before?.speech.orEmpty() && now - it.at < FRESH_SPEECH_MS }
            .forEach { speech ->
                val wait = speech.at - now
                val gender = table.genders[speech.seat]
                // Players of the same gender get different voices where there are enough.
                val slot = table.genders.take(speech.seat).count { it == gender }
                if (wait > 0) {
                    later.launch {
                        delay(wait)
                        say(speech.seat, speech.text, gender, slot)
                    }
                } else {
                    say(speech.seat, speech.text, gender, slot)
                }
            }
    }

    /** Says [text] in the voice of [seat] (see [SoundOutput.say]). */
    fun say(seat: Int, text: String, gender: Gender? = null, slot: Int = 0) {
        if (voicesOn) output.say(seat, Pronunciation.spoken(text), gender, slot)
    }

    /** Stops talking, e.g. when the app goes to the background. */
    fun stop() {
        later.coroutineContext.cancelChildren()
        output.stopSpeaking()
    }

    fun release() {
        later.coroutineContext.cancelChildren()
        output.release()
    }

    private fun play(effect: Effect) {
        if (effectsOn) output.play(effect)
    }

    private companion object {
        const val FRESH_SPEECH_MS = 2000L
    }
}

/** The [TableSound] of the app; null in previews. */
val LocalTableSound = staticCompositionLocalOf<TableSound?> { null }
