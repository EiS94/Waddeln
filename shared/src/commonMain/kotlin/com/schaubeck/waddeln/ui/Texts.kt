package com.schaubeck.waddeln.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import com.schaubeck.waddeln.resources.*
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
        fun suit(suit: Suit): StringResource = when (suit) {
        Suit.SCHELL -> Res.string.suit_schell
        Suit.HERZ -> Res.string.suit_herz
        Suit.EICHEL -> Res.string.suit_eichel
        Suit.GRAS -> Res.string.suit_gras
    }

        fun rank(rank: Rank): StringResource = when (rank) {
        Rank.SEVEN -> Res.string.rank_seven
        Rank.EIGHT -> Res.string.rank_eight
        Rank.NINE -> Res.string.rank_nine
        Rank.TEN -> Res.string.rank_ten
        Rank.UNTER -> Res.string.rank_unter
        Rank.OBER -> Res.string.rank_ober
        Rank.KOENIG -> Res.string.rank_koenig
        Rank.ASS -> Res.string.rank_ass
    }

    /** "1 Kritte", "2 Kritten", ... */
    @Composable
    fun kritten(count: Int): String =
        if (count == 1) stringResource(Res.string.kritten_one) else stringResource(Res.string.kritten_many, count)

    /**
     * What a Schub is called at the table when the game is worth [gameValue]: "Schub" from 2 to 3,
     * after that just the new value ("Vier", "Fünf", ...).
     */
    fun schubCall(strings: Strings, gameValue: Int): String = schubCall(strings.array(Res.array.schub_calls), gameValue)

    @Composable
    fun schubCall(gameValue: Int): String = schubCall(stringArrayResource(Res.array.schub_calls), gameValue)

    private fun schubCall(calls: List<String>, gameValue: Int): String =
        calls.getOrElse(gameValue - BASE_GAME_VALUE) { (gameValue + 1).toString() }

    @Composable
    fun schubNotice(asking: Int, answering: Int, newValue: Int, names: List<String>, me: Int): String {
        val call = schubCall(newValue - 1)
        return if (asking == me) {
            stringResource(Res.string.notice_schub_you, names[answering], newValue, call)
        } else {
            stringResource(Res.string.notice_schub_other, names[asking], names[answering], newValue, call)
        }
    }

    @Composable
    fun roundResult(winningTeam: Int, points: Int, reason: RoundEndReason, me: Int): String {
        val won = winningTeam == teamOf(me)
        val pts = if (points == 1) stringResource(Res.string.end_points_one) else stringResource(Res.string.end_points_many, points)
        val text = when (reason) {
            RoundEndReason.TRICKS -> if (won) Res.string.end_round_won else Res.string.end_round_lost
            RoundEndReason.GIVEN_UP -> if (won) Res.string.end_opponents_gave_up else Res.string.end_we_gave_up
        }
        return stringResource(text, pts)
    }

    /** Label of the buttons a human answers "Schadder?" with. */
        fun schadetLabel(answer: SchadetAnswer): StringResource = when (answer) {
        SchadetAnswer.YES -> Res.string.schadet_label_yes
        SchadetAnswer.A_LITTLE -> Res.string.schadet_label_a_little
        SchadetAnswer.NO -> Res.string.schadet_label_no
    }

    /** Label of the buttons a human answers "Was soll ich machen?" with. */
        fun adviceLabel(answer: TrickAdvice): StringResource = when (answer) {
        TrickAdvice.NICHTS -> Res.string.advice_label_nichts
        TrickAdvice.STICHELN -> Res.string.advice_label_sticheln
        TrickAdvice.MACHEN -> Res.string.advice_label_machen
    }

    /** Short label of a hint level for the buttons a human partner answers with. */
        fun hintLabel(hint: PartnerHint): StringResource = when (hint) {
        PartnerHint.NOTHING -> Res.string.hint_label_nothing
        PartnerHint.LITTLE -> Res.string.hint_label_little
        PartnerHint.ONE_TRICK -> Res.string.hint_label_one_trick
        PartnerHint.TWO_TRICKS -> Res.string.hint_label_two_tricks
        PartnerHint.ALL_TRICKS -> Res.string.hint_label_all
    }
}
