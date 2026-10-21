package com.schaubeck.waddeln.ui

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import kotlin.random.Random

/**
 * What players say aloud at the table, shown in speech bubbles. Every entry is a list of
 * variants; each time one of them is picked at random. Edit the lists freely (each needs at
 * least one entry).
 */
object Sayings {

    // ---- Announcements -------------------------------------------------------------------

    val SCHLAG: Map<Rank, List<String>> = mapOf(
        Rank.SEVEN to listOf("Sieben", "Siebener", "Die Siebener"),
        Rank.EIGHT to listOf("Acht", "Achter", "Die Achter"),
        Rank.NINE to listOf("Neun", "Neuner", "Die Neuner"),
        Rank.TEN to listOf("Zehn", "Zehner", "Die Zehner"),
        Rank.UNTER to listOf("Unter", "Die Unter"),
        Rank.OBER to listOf("Ober", "Die Ober"),
        Rank.KOENIG to listOf("König", "Könige", "Die Könige"),
        Rank.ASS to listOf("Ass", "Sau", "Assen", "Die Assen"),
    )

    val FARBE: Map<Suit, List<String>> = mapOf(
        Suit.SCHELL to listOf("Schell", "In Schell"),
        Suit.HERZ to listOf("Herz", "In Herz"),
        Suit.EICHEL to listOf("Eichel", "In Eichel"),
        Suit.GRAS to listOf("Gras", "Grün", "Laub", "In Grün"),
    )

    // ---- Schub ---------------------------------------------------------------------------

    val SCHUB = listOf("Schub!", "Geht ihr?", "Gehen?", "Ab mit euch")
    val BLEIBEN = listOf("Wir bleiben!", "Bleiben!", "Nix da, wir bleiben.")
    val GEHEN = listOf("Wir gehen.", "Gehen.", "Müss ma gehen.", "Da müss ma gehen.")

    // ---- Asking the partner during a Schub ----------------------------------------------

    val PARTNER_QUESTION = listOf("Was hast du?", "Wie schaut's aus?", "Was meinst du?")

    val HINT_NOTHING = listOf("Ich hab gar nichts.", "Nix.", "Bei mir schaut's schlecht aus.")
    val HINT_LITTLE = listOf("Ich hab nicht viel.", "Ein bisschen was.", "Nicht viel.")
    val HINT_ONE_TRICK = listOf("Einen Stich mach ich.", "Einen hab ich.")
    val HINT_TWO_TRICKS = listOf("Zwei Stiche mach ich.", "Zwei hab ich.")

    /** "All tricks", by the number of tricks our team still needs (3, 2 or 1). */
    val HINT_ALL_TRICKS: Map<Int, List<String>> = mapOf(
        3 to listOf("Ich mach alle 3 Stiche!", "Die mach ich alle!"),
        2 to listOf("Die zwei Stiche mach ich!", "Die zwei hab ich!"),
        1 to listOf("Den letzten Stich mach ich!", "Den letzten hab ich!"),
    )

    // ---- "Schadder?" before leading the Haubi into the first trick ------------------------

    val SCHADET_QUESTION = listOf("Schadder?", "Schadet er?", "Schadet er dir?")
    val SCHADET_YES = listOf("Ja.", "Ja, schadet.")
    val SCHADET_A_LITTLE = listOf("Ein bisschen.", "A bissl.", "Geht scho.")
    val SCHADET_NO = listOf("Nein.", "Schadet net.", "Kannst spielen.")

    // ---- Schlagwechsel before the Schlag is announced ---------------------------------------

    val SCHLAGWECHSEL_QUESTION = listOf("Schlagwechsel?", "Tauschen wir?", "Sag du den Schlag an!")
    val SCHLAGWECHSEL_ACCEPT = listOf("Passt.", "Machen wir.", "Ja, tauschen wir.")
    val SCHLAGWECHSEL_DECLINE = listOf("Nix da.", "Nein, sag du an.", "Bleibt so.")

    // ---- Kritten taken while cutting --------------------------------------------------------

    /** By the number of Kritten the Abheber had to take (1 to 3). */
    val KRITTEN: Map<Int, List<String>> = mapOf(
        1 to listOf("A Kritte!", "Eine Kritte hab ich.", "Kritte!"),
        2 to listOf("Zwei Kritten!", "Zwei Kritten hab ich."),
        3 to listOf("Alle drei Kritten!", "Drei Kritten!"),
    )

    // ---- Picking -------------------------------------------------------------------------

    /** A random variant for an action said aloud; null for playing a card. */
    fun speech(action: Action, random: Random = Random.Default): String? = when (action) {
        is Action.AnnounceSchlag -> SCHLAG.getValue(action.rank).random(random)
        is Action.AnnounceFarbe -> FARBE.getValue(action.suit).random(random)
        Action.Schub -> SCHUB.random(random)
        Action.Bleiben -> BLEIBEN.random(random)
        Action.Gehen -> GEHEN.random(random)
        is Action.PlayCard -> null
    }

    /** What the Abheber says after taking [count] Kritten (1 to 3). */
    fun kritten(count: Int, random: Random = Random.Default): String = KRITTEN.getValue(count).random(random)

    fun partnerQuestion(random: Random = Random.Default): String = PARTNER_QUESTION.random(random)

    /** A random variant of [hint]; [tricksWon] are the tricks the partner's team already has. */
    fun partnerHint(hint: PartnerHint, tricksWon: Int, random: Random = Random.Default): String = when (hint) {
        PartnerHint.NOTHING -> HINT_NOTHING
        PartnerHint.LITTLE -> HINT_LITTLE
        PartnerHint.ONE_TRICK -> HINT_ONE_TRICK
        PartnerHint.TWO_TRICKS -> HINT_TWO_TRICKS
        PartnerHint.ALL_TRICKS -> HINT_ALL_TRICKS.getValue((TRICKS_TO_WIN - tricksWon).coerceIn(1, TRICKS_TO_WIN))
    }.random(random)

    fun schlagwechselQuestion(random: Random = Random.Default): String = SCHLAGWECHSEL_QUESTION.random(random)

    fun schlagwechselAnswer(accept: Boolean, random: Random = Random.Default): String =
        (if (accept) SCHLAGWECHSEL_ACCEPT else SCHLAGWECHSEL_DECLINE).random(random)

    fun schadetQuestion(random: Random = Random.Default): String = SCHADET_QUESTION.random(random)

    fun schadetAnswer(answer: SchadetAnswer, random: Random = Random.Default): String = when (answer) {
        SchadetAnswer.YES -> SCHADET_YES
        SchadetAnswer.A_LITTLE -> SCHADET_A_LITTLE
        SchadetAnswer.NO -> SCHADET_NO
    }.random(random)

    /** Label of the buttons a human answers "Schadder?" with. */
    fun schadetLabel(answer: SchadetAnswer): String = when (answer) {
        SchadetAnswer.YES -> "Ja"
        SchadetAnswer.A_LITTLE -> "Ein bisschen"
        SchadetAnswer.NO -> "Nein"
    }

    /** Short label of a hint level for the buttons a human partner answers with. */
    fun hintLabel(hint: PartnerHint): String = when (hint) {
        PartnerHint.NOTHING -> "Gar nichts"
        PartnerHint.LITTLE -> "Wenig"
        PartnerHint.ONE_TRICK -> "1 Stich"
        PartnerHint.TWO_TRICKS -> "2 Stiche"
        PartnerHint.ALL_TRICKS -> "Alle"
    }
}
