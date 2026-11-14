package com.schaubeck.waddeln.ui

import org.jetbrains.compose.resources.StringResource
import com.schaubeck.waddeln.resources.*
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.BASE_GAME_VALUE
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.agent.TrickAdvice
import kotlin.random.Random

/**
 * What players say aloud at the table, shown in speech bubbles. Every saying is a string resource
 * (say_*) with variants separated by |; each time one of them is picked at random.
 */
class Sayings(private val strings: Strings) {

    /** A random variant of the saying [id]. */
    private fun pick(id: StringResource, random: Random): String =
        strings.get(id).split('|').map(String::trim).filter(String::isNotEmpty).random(random)

    /** A random variant of the saying [id] formatted with [arg]. */
    private fun pick(id: StringResource, arg: String, random: Random): String =
        strings.get(id, arg).split('|').map(String::trim).filter(String::isNotEmpty).random(random)

    /**
     * A random variant for an action said aloud; null for playing a card. [gameValue] is the value
     * of the game when the action is taken: from 3 on, a Schub is called by the new value ("Vier").
     */
    fun speech(action: Action, gameValue: Int, random: Random = Random.Default): String? = when (action) {
        is Action.AnnounceSchlag -> pick(schlag(action.rank), random)
        is Action.AnnounceFarbe -> pick(farbe(action.suit), random)
        Action.Schub ->
            if (gameValue <= BASE_GAME_VALUE) pick(Res.string.say_schub, random)
            else pick(Res.string.say_schub_value, Texts.schubCall(strings, gameValue), random)
        Action.Bleiben -> pick(Res.string.say_bleiben, random)
        Action.Gehen -> pick(Res.string.say_gehen, random)
        is Action.PlayCard -> null
    }

    /** Bleiben when the opponents are gespannt after going as well as after losing (see SchubRule). */
    fun bleibenGespannt(random: Random = Random.Default): String = pick(Res.string.say_bleiben_gespannt, random)

    /** Gespannt against gespannt: one player calls it out before the cut, another answers. */
    fun gespanntGespannt(random: Random = Random.Default): String = pick(Res.string.say_gespannt_gespannt, random)

    fun layOut(random: Random = Random.Default): String = pick(Res.string.say_lay_out, random)

    /** What the Abheber says after taking [count] Kritten (1 to 3). */
    fun kritten(count: Int, random: Random = Random.Default): String = pick(
        when (count) {
            1 -> Res.string.say_kritten_1
            2 -> Res.string.say_kritten_2
            else -> Res.string.say_kritten_3
        },
        random,
    )

    fun partnerQuestion(random: Random = Random.Default): String = pick(Res.string.say_partner_question, random)

    /** A random variant of [hint]; [tricksWon] are the tricks the partner's team already has. */
    fun partnerHint(hint: PartnerHint, tricksWon: Int, random: Random = Random.Default): String = pick(
        when (hint) {
            PartnerHint.NOTHING -> Res.string.say_hint_nothing
            PartnerHint.LITTLE -> Res.string.say_hint_little
            PartnerHint.ONE_TRICK -> Res.string.say_hint_one_trick
            PartnerHint.TWO_TRICKS -> Res.string.say_hint_two_tricks
            // By the number of tricks our team still needs (3, 2 or 1).
            PartnerHint.ALL_TRICKS -> when ((TRICKS_TO_WIN - tricksWon).coerceIn(1, TRICKS_TO_WIN)) {
                1 -> Res.string.say_hint_all_1
                2 -> Res.string.say_hint_all_2
                else -> Res.string.say_hint_all_3
            }
        },
        random,
    )

    fun schlagwechselQuestion(random: Random = Random.Default): String = pick(Res.string.say_schlagwechsel_question, random)

    fun schlagwechselAnswer(accept: Boolean, random: Random = Random.Default): String =
        pick(if (accept) Res.string.say_schlagwechsel_accept else Res.string.say_schlagwechsel_decline, random)

    fun schadetQuestion(random: Random = Random.Default): String = pick(Res.string.say_schadet_question, random)

    fun schadetAnswer(answer: SchadetAnswer, random: Random = Random.Default): String = pick(
        when (answer) {
            SchadetAnswer.YES -> Res.string.say_schadet_yes
            SchadetAnswer.A_LITTLE -> Res.string.say_schadet_a_little
            SchadetAnswer.NO -> Res.string.say_schadet_no
        },
        random,
    )

    fun adviceQuestion(random: Random = Random.Default): String = pick(Res.string.say_advice_question, random)

    fun adviceAnswer(answer: TrickAdvice, random: Random = Random.Default): String = pick(
        when (answer) {
            TrickAdvice.NICHTS -> Res.string.say_advice_nichts
            TrickAdvice.STICHELN -> Res.string.say_advice_sticheln
            TrickAdvice.MACHEN -> Res.string.say_advice_machen
        },
        random,
    )

    private fun schlag(rank: Rank): StringResource = when (rank) {
        Rank.SEVEN -> Res.string.say_schlag_seven
        Rank.EIGHT -> Res.string.say_schlag_eight
        Rank.NINE -> Res.string.say_schlag_nine
        Rank.TEN -> Res.string.say_schlag_ten
        Rank.UNTER -> Res.string.say_schlag_unter
        Rank.OBER -> Res.string.say_schlag_ober
        Rank.KOENIG -> Res.string.say_schlag_koenig
        Rank.ASS -> Res.string.say_schlag_ass
    }

    private fun farbe(suit: Suit): StringResource = when (suit) {
        Suit.SCHELL -> Res.string.say_farbe_schell
        Suit.HERZ -> Res.string.say_farbe_herz
        Suit.EICHEL -> Res.string.say_farbe_eichel
        Suit.GRAS -> Res.string.say_farbe_gras
    }
}
