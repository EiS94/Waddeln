package com.schaubeck.waddeln.ui

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import com.schaubeck.waddeln.rules.agent.PartnerHint
import kotlin.random.Random

/**
 * What players say aloud at the table, shown in speech bubbles. Every entry is a list of
 * variants; each time one of them is picked at random. Edit the lists freely (each needs at
 * least one entry).
 */
object Sayings {

    // ---- Announcements -------------------------------------------------------------------

    val SCHLAG: Map<Rank, List<String>> = mapOf(
        Rank.SEVEN to listOf("Sieben", "Siebener"),
        Rank.EIGHT to listOf("Acht", "Achter"),
        Rank.NINE to listOf("Neun", "Neuner"),
        Rank.TEN to listOf("Zehn", "Zehner"),
        Rank.UNTER to listOf("Unter"),
        Rank.OBER to listOf("Ober"),
        Rank.KOENIG to listOf("König", "Könige"),
        Rank.ASS to listOf("Ass", "Sau", "Assen"),
    )

    val FARBE: Map<Suit, List<String>> = mapOf(
        Suit.SCHELL to listOf("Schell", "Schellen"),
        Suit.HERZ to listOf("Herz"),
        Suit.EICHEL to listOf("Eichel"),
        Suit.GRAS to listOf("Gras", "Grün", "Laub"),
    )

    // ---- Schub ---------------------------------------------------------------------------

    val SCHUB = listOf("Schub!", "Geht ihr?", "Gehen?", "Ab mit euch")
    val BLEIBEN = listOf("Wir bleiben!", "Bleiben!", "Nix da, wir bleiben.")
    val GEHEN = listOf("Wir gehen.", "Gehen.", "Müss ma gehen.")

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

    fun partnerQuestion(random: Random = Random.Default): String = PARTNER_QUESTION.random(random)

    /** A random variant of [hint]; [tricksWon] are the tricks the partner's team already has. */
    fun partnerHint(hint: PartnerHint, tricksWon: Int, random: Random = Random.Default): String = when (hint) {
        PartnerHint.NOTHING -> HINT_NOTHING
        PartnerHint.LITTLE -> HINT_LITTLE
        PartnerHint.ONE_TRICK -> HINT_ONE_TRICK
        PartnerHint.TWO_TRICKS -> HINT_TWO_TRICKS
        PartnerHint.ALL_TRICKS -> HINT_ALL_TRICKS.getValue((TRICKS_TO_WIN - tricksWon).coerceIn(1, TRICKS_TO_WIN))
    }.random(random)

    /** Short label of a hint level for the buttons a human partner answers with. */
    fun hintLabel(hint: PartnerHint): String = when (hint) {
        PartnerHint.NOTHING -> "Gar nichts"
        PartnerHint.LITTLE -> "Wenig"
        PartnerHint.ONE_TRICK -> "1 Stich"
        PartnerHint.TWO_TRICKS -> "2 Stiche"
        PartnerHint.ALL_TRICKS -> "Alle"
    }
}
