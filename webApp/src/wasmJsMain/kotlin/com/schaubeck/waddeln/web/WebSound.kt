package com.schaubeck.waddeln.web

import com.schaubeck.waddeln.game.Gender
import com.schaubeck.waddeln.resources.Res
import com.schaubeck.waddeln.sound.Effect
import com.schaubeck.waddeln.sound.SoundOutput

/**
 * Effects with HTML audio elements, voices with the browser's speech synthesis. Browsers only play
 * sound after the user touched the page; until then (and without a German voice) it stays silent.
 */
class WebSoundOutput : SoundOutput {
    init {
        watchUserActivation()
    }

    override fun play(effect: Effect) {
        if (userActivated()) playAudio(Res.getUri(effect.file), effect.volume.toDouble())
    }

    override fun say(seat: Int, text: String, gender: Gender?, slot: Int) {
        if (!userActivated()) return
        val german = germanVoices().split('\n').filter { it.isNotEmpty() }
        if (german.isEmpty()) return
        val matching = gender?.let { g -> german.filter { genderOf(it) == g } }.orEmpty()
        val voice = if (matching.isNotEmpty()) matching[slot % matching.size] else german[seat % german.size]
        val pitch = when {
            gender == null -> SEAT_PITCH[seat]
            matching.isNotEmpty() -> SEAT_VARIATION[seat]
            else -> GENDER_PITCH.getValue(gender) * SEAT_VARIATION[seat]
        }
        speak(text, voice, pitch.toDouble(), SEAT_RATE[seat].toDouble())
    }

    override fun stopSpeaking() = cancelSpeech()

    override fun release() = cancelSpeech()

    private companion object {
        /** Voice height and pace per seat (as on Android), so players stay apart even with one voice. */
        val SEAT_PITCH = listOf(1.0f, 0.8f, 1.15f, 0.92f)
        val SEAT_RATE = listOf(1.0f, 0.95f, 1.05f, 0.9f)
        val SEAT_VARIATION = listOf(1.0f, 0.95f, 1.05f, 0.98f)
        val GENDER_PITCH = mapOf(Gender.MALE to 0.8f, Gender.FEMALE to 1.2f, Gender.DIVERSE to 1.0f)

        val FEMALE = listOf("female", "katja", "hedda", "amala", "seraphina", "louisa", "ingrid", "elke", "gisela",
            "tanja", "maja", "anna", "petra", "helena", "sabina", "vicki", "marlene", "google deutsch")
        val MALE = listOf("male", "conrad", "stefan", "killian", "florian", "kasper", "ralf", "bernd", "christoph",
            "markus", "yannick", "viktor", "martin", "hans", "jonas")

        /** Gender of a browser voice by its name (Google, Microsoft, Apple); null if unknown. */
        fun genderOf(name: String): Gender? {
            val n = name.lowercase()
            return when {
                FEMALE.any { it in n } -> Gender.FEMALE
                MALE.any { it in n } -> Gender.MALE
                else -> null
            }
        }
    }
}

/** Remembers the first touch, click or key press (browsers allow sound only after one). */
private fun watchUserActivation(): Unit = js(
    """
    {
      if (window.__waddelnActivated !== undefined) return;
      window.__waddelnActivated = false;
      const on = () => { window.__waddelnActivated = true; };
      for (const type of ['pointerdown', 'keydown', 'touchstart']) window.addEventListener(type, on, { once: true, capture: true });
    }
    """,
)

private fun userActivated(): Boolean = js("window.__waddelnActivated === true")

private fun playAudio(url: String, volume: Double): Unit = js(
    "{ try { const a = new Audio(url); a.volume = volume; a.play().catch(() => {}); } catch (e) {} }",
)

/** Names of the installed German voices, one per line, German (Germany) first. */
private fun germanVoices(): String = js(
    """
    (typeof speechSynthesis === 'undefined') ? '' : speechSynthesis.getVoices()
      .filter(v => v.lang && v.lang.toLowerCase().startsWith('de'))
      .sort((a, b) => (a.lang !== 'de-DE') - (b.lang !== 'de-DE') || a.name.localeCompare(b.name))
      .map(v => v.name).join('\n')
    """,
)

private fun speak(text: String, voiceName: String, pitch: Double, rate: Double): Unit = js(
    """
    {
      const u = new SpeechSynthesisUtterance(text);
      const v = speechSynthesis.getVoices().find(v => v.name === voiceName);
      if (v) { u.voice = v; u.lang = v.lang; } else { u.lang = 'de-DE'; }
      u.pitch = pitch;
      u.rate = rate;
      speechSynthesis.speak(u);
    }
    """,
)

private fun cancelSpeech(): Unit = js("{ if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel(); }")
