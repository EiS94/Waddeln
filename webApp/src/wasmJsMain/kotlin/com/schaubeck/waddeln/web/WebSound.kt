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
        if (!userActivated() || !speechAvailable()) return
        val german = germanVoices().split('\n').filter { it.isNotEmpty() }
        // Mobile browsers often list no voices at all (or only later); then the browser picks a German
        // voice by itself. Only when voices are listed and none is German, the table stays silent.
        if (german.isEmpty() && voiceCount() > 0) return
        val matching = gender?.let { g -> german.filter { genderOf(it) == g } }.orEmpty()
        val voice = when {
            matching.isNotEmpty() -> matching[slot % matching.size]
            german.isNotEmpty() -> german[seat % german.size]
            else -> ""
        }
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

/**
 * Remembers the first touch, click or key press: browsers allow sound only after one. Safari (and some
 * Android browsers) also unlock speech only if something is spoken inside such an event, so a silent
 * utterance is spoken right then; later speech from the bots is allowed after that.
 */
private fun watchUserActivation(): Unit = js(
    """
    {
      if (window.__waddelnActivated !== undefined) return;
      window.__waddelnActivated = false;
      let primed = 0;
      const on = (e) => {
        window.__waddelnActivated = true;
        // Which event counts as the user's gesture differs between browsers: prime on the first few.
        if (primed >= 3 || e.type === 'pointerdown') return;
        primed++;
        try {
          if (typeof speechSynthesis !== 'undefined') {
            const u = new SpeechSynthesisUtterance(' ');
            u.volume = 0;
            speechSynthesis.speak(u);
            speechSynthesis.getVoices();
          }
        } catch (err) {}
      };
      for (const type of ['pointerdown', 'touchend', 'keydown', 'click']) window.addEventListener(type, on, { capture: true });
    }
    """,
)

private fun userActivated(): Boolean = js("window.__waddelnActivated === true")

private fun speechAvailable(): Boolean = js("typeof speechSynthesis !== 'undefined' && typeof SpeechSynthesisUtterance !== 'undefined'")

private fun playAudio(url: String, volume: Double): Unit = js(
    "{ try { const a = new Audio(url); a.volume = volume; a.play().catch(() => {}); } catch (e) {} }",
)

private fun voiceCount(): Int = js("speechSynthesis.getVoices().length")

/** Names of the installed German voices (de-DE or de_DE, ...), one per line, German (Germany) first. */
private fun germanVoices(): String = js(
    """
    speechSynthesis.getVoices()
      .map(v => ({ name: v.name, lang: (v.lang || '').replace('_', '-').toLowerCase() }))
      .filter(v => v.lang.startsWith('de'))
      .sort((a, b) => (a.lang !== 'de-de') - (b.lang !== 'de-de') || a.name.localeCompare(b.name))
      .map(v => v.name).join('\n')
    """,
)

/** Says [text]; with an empty [voiceName] the browser picks its German voice. */
private fun speak(text: String, voiceName: String, pitch: Double, rate: Double): Unit = js(
    """
    {
      try {
        const u = new SpeechSynthesisUtterance(text);
        const v = voiceName ? speechSynthesis.getVoices().find(v => v.name === voiceName) : undefined;
        if (v) { u.voice = v; u.lang = v.lang; } else { u.lang = 'de-DE'; }
        u.pitch = pitch;
        u.rate = rate;
        // Chrome on Android pauses speech after the tab was in the background.
        speechSynthesis.resume();
        speechSynthesis.speak(u);
      } catch (e) {}
    }
    """,
)

private fun cancelSpeech(): Unit = js("{ if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel(); }")
