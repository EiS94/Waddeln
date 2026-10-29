package com.schaubeck.waddeln.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import com.schaubeck.waddeln.R
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
class Sayings(private val res: Resources) {

    /** A random variant of the saying [id]. */
    private fun pick(@StringRes id: Int, random: Random): String =
        res.getString(id).split('|').map(String::trim).filter(String::isNotEmpty).random(random)

    /** A random variant of the saying [id] formatted with [arg]. */
    private fun pick(@StringRes id: Int, arg: String, random: Random): String =
        res.getString(id, arg).split('|').map(String::trim).filter(String::isNotEmpty).random(random)

    /**
     * A random variant for an action said aloud; null for playing a card. [gameValue] is the value
     * of the game when the action is taken: from 3 on, a Schub is called by the new value ("Vier").
     */
    fun speech(action: Action, gameValue: Int, random: Random = Random.Default): String? = when (action) {
        is Action.AnnounceSchlag -> pick(schlag(action.rank), random)
        is Action.AnnounceFarbe -> pick(farbe(action.suit), random)
        Action.Schub ->
            if (gameValue <= BASE_GAME_VALUE) pick(R.string.say_schub, random)
            else pick(R.string.say_schub_value, Texts.schubCall(res, gameValue), random)
        Action.Bleiben -> pick(R.string.say_bleiben, random)
        Action.Gehen -> pick(R.string.say_gehen, random)
        is Action.PlayCard -> null
    }

    /** What the Abheber says after taking [count] Kritten (1 to 3). */
    fun kritten(count: Int, random: Random = Random.Default): String = pick(
        when (count) {
            1 -> R.string.say_kritten_1
            2 -> R.string.say_kritten_2
            else -> R.string.say_kritten_3
        },
        random,
    )

    fun partnerQuestion(random: Random = Random.Default): String = pick(R.string.say_partner_question, random)

    /** A random variant of [hint]; [tricksWon] are the tricks the partner's team already has. */
    fun partnerHint(hint: PartnerHint, tricksWon: Int, random: Random = Random.Default): String = pick(
        when (hint) {
            PartnerHint.NOTHING -> R.string.say_hint_nothing
            PartnerHint.LITTLE -> R.string.say_hint_little
            PartnerHint.ONE_TRICK -> R.string.say_hint_one_trick
            PartnerHint.TWO_TRICKS -> R.string.say_hint_two_tricks
            // By the number of tricks our team still needs (3, 2 or 1).
            PartnerHint.ALL_TRICKS -> when ((TRICKS_TO_WIN - tricksWon).coerceIn(1, TRICKS_TO_WIN)) {
                1 -> R.string.say_hint_all_1
                2 -> R.string.say_hint_all_2
                else -> R.string.say_hint_all_3
            }
        },
        random,
    )

    fun schlagwechselQuestion(random: Random = Random.Default): String = pick(R.string.say_schlagwechsel_question, random)

    fun schlagwechselAnswer(accept: Boolean, random: Random = Random.Default): String =
        pick(if (accept) R.string.say_schlagwechsel_accept else R.string.say_schlagwechsel_decline, random)

    fun schadetQuestion(random: Random = Random.Default): String = pick(R.string.say_schadet_question, random)

    fun schadetAnswer(answer: SchadetAnswer, random: Random = Random.Default): String = pick(
        when (answer) {
            SchadetAnswer.YES -> R.string.say_schadet_yes
            SchadetAnswer.A_LITTLE -> R.string.say_schadet_a_little
            SchadetAnswer.NO -> R.string.say_schadet_no
        },
        random,
    )

    fun adviceQuestion(random: Random = Random.Default): String = pick(R.string.say_advice_question, random)

    fun adviceAnswer(answer: TrickAdvice, random: Random = Random.Default): String = pick(
        when (answer) {
            TrickAdvice.NICHTS -> R.string.say_advice_nichts
            TrickAdvice.STICHELN -> R.string.say_advice_sticheln
            TrickAdvice.MACHEN -> R.string.say_advice_machen
        },
        random,
    )

    private fun schlag(rank: Rank): Int = when (rank) {
        Rank.SEVEN -> R.string.say_schlag_seven
        Rank.EIGHT -> R.string.say_schlag_eight
        Rank.NINE -> R.string.say_schlag_nine
        Rank.TEN -> R.string.say_schlag_ten
        Rank.UNTER -> R.string.say_schlag_unter
        Rank.OBER -> R.string.say_schlag_ober
        Rank.KOENIG -> R.string.say_schlag_koenig
        Rank.ASS -> R.string.say_schlag_ass
    }

    private fun farbe(suit: Suit): Int = when (suit) {
        Suit.SCHELL -> R.string.say_farbe_schell
        Suit.HERZ -> R.string.say_farbe_herz
        Suit.EICHEL -> R.string.say_farbe_eichel
        Suit.GRAS -> R.string.say_farbe_gras
    }
}
