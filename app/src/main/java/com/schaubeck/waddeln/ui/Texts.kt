package com.schaubeck.waddeln.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.rules.BASE_GAME_VALUE
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.RoundEndReason
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.agent.TrickAdvice
import com.schaubeck.waddeln.rules.teamOf

/** UI texts built from string resources. "Wir"/"Du" always refer to the player [me] the table is shown for. */
object Texts {
    @StringRes
    fun suit(suit: Suit): Int = when (suit) {
        Suit.SCHELL -> R.string.suit_schell
        Suit.HERZ -> R.string.suit_herz
        Suit.EICHEL -> R.string.suit_eichel
        Suit.GRAS -> R.string.suit_gras
    }

    @StringRes
    fun rank(rank: Rank): Int = when (rank) {
        Rank.SEVEN -> R.string.rank_seven
        Rank.EIGHT -> R.string.rank_eight
        Rank.NINE -> R.string.rank_nine
        Rank.TEN -> R.string.rank_ten
        Rank.UNTER -> R.string.rank_unter
        Rank.OBER -> R.string.rank_ober
        Rank.KOENIG -> R.string.rank_koenig
        Rank.ASS -> R.string.rank_ass
    }

    /** "1 Kritte", "2 Kritten", ... */
    @Composable
    fun kritten(count: Int): String =
        if (count == 1) stringResource(R.string.kritten_one) else stringResource(R.string.kritten_many, count)

    /**
     * What a Schub is called at the table when the game is worth [gameValue]: "Schub" from 2 to 3,
     * after that just the new value ("Vier", "Fünf", ...).
     */
    fun schubCall(res: Resources, gameValue: Int): String =
        res.getStringArray(R.array.schub_calls).getOrElse(gameValue - BASE_GAME_VALUE) { (gameValue + 1).toString() }

    @Composable
    fun schubCall(gameValue: Int): String = schubCall(LocalContext.current.resources, gameValue)

    @Composable
    fun schubNotice(asking: Int, answering: Int, newValue: Int, names: List<String>, me: Int): String {
        val call = schubCall(newValue - 1)
        return if (asking == me) {
            stringResource(R.string.notice_schub_you, names[answering], newValue, call)
        } else {
            stringResource(R.string.notice_schub_other, names[asking], names[answering], newValue, call)
        }
    }

    @Composable
    fun roundResult(winningTeam: Int, points: Int, reason: RoundEndReason, me: Int): String {
        val won = winningTeam == teamOf(me)
        val pts = if (points == 1) stringResource(R.string.end_points_one) else stringResource(R.string.end_points_many, points)
        val text = when (reason) {
            RoundEndReason.TRICKS -> if (won) R.string.end_round_won else R.string.end_round_lost
            RoundEndReason.GIVEN_UP -> if (won) R.string.end_opponents_gave_up else R.string.end_we_gave_up
        }
        return stringResource(text, pts)
    }

    /** Label of the buttons a human answers "Schadder?" with. */
    @StringRes
    fun schadetLabel(answer: SchadetAnswer): Int = when (answer) {
        SchadetAnswer.YES -> R.string.schadet_label_yes
        SchadetAnswer.A_LITTLE -> R.string.schadet_label_a_little
        SchadetAnswer.NO -> R.string.schadet_label_no
    }

    /** Label of the buttons a human answers "Was soll ich machen?" with. */
    @StringRes
    fun adviceLabel(answer: TrickAdvice): Int = when (answer) {
        TrickAdvice.NICHTS -> R.string.advice_label_nichts
        TrickAdvice.STICHELN -> R.string.advice_label_sticheln
        TrickAdvice.MACHEN -> R.string.advice_label_machen
    }

    /** Short label of a hint level for the buttons a human partner answers with. */
    @StringRes
    fun hintLabel(hint: PartnerHint): Int = when (hint) {
        PartnerHint.NOTHING -> R.string.hint_label_nothing
        PartnerHint.LITTLE -> R.string.hint_label_little
        PartnerHint.ONE_TRICK -> R.string.hint_label_one_trick
        PartnerHint.TWO_TRICKS -> R.string.hint_label_two_tricks
        PartnerHint.ALL_TRICKS -> R.string.hint_label_all
    }
}
