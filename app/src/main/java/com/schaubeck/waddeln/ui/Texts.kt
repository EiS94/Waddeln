package com.schaubeck.waddeln.ui

import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.RoundEndReason
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.teamOf

/** German UI texts. "Wir"/"Du" always refer to the player [me] the table is shown for. */
object Texts {
    fun suit(suit: Suit) = when (suit) {
        Suit.SCHELL -> "Schell"
        Suit.HERZ -> "Herz"
        Suit.EICHEL -> "Eichel"
        Suit.GRAS -> "Gras"
    }

    fun rank(rank: Rank) = when (rank) {
        Rank.SEVEN -> "7"
        Rank.EIGHT -> "8"
        Rank.NINE -> "9"
        Rank.TEN -> "10"
        Rank.UNTER -> "Unter"
        Rank.OBER -> "Ober"
        Rank.KOENIG -> "König"
        Rank.ASS -> "Ass"
    }

    fun card(card: Card) = "${suit(card.suit)} ${rank(card.rank)}"

    /** "1 Kritte", "2 Kritten", ... */
    fun kritten(count: Int) = if (count == 1) "1 Kritte" else "$count Kritten"

    fun kritteName(card: Card): String? = when (card) {
        Card.MAX -> "Max"
        Card.BELLO -> "Bello"
        Card.SECHER -> "Secher"
        else -> null
    }

    fun haubi(trump: Trump): String =
        "Haubi: ${card(trump.haubi)}" + (kritteName(trump.haubi)?.let { " ($it)" } ?: "")

    fun schubNotice(asking: Int, answering: Int, newValue: Int, names: List<String>, me: Int): String {
        val asker = if (asking == me) "Du sagst" else "${names[asking]} sagt"
        return "$asker Schub – ${names[answering]} bleibt.\nSpielwert jetzt $newValue."
    }

    fun roundResult(winningTeam: Int, points: Int, reason: RoundEndReason, me: Int): String {
        val won = winningTeam == teamOf(me)
        val pts = if (points == 1) "1 Punkt" else "$points Punkte"
        return when (reason) {
            RoundEndReason.TRICKS -> if (won) "Wir gewinnen das Spiel: $pts." else "Die Gegner gewinnen das Spiel: $pts."
            RoundEndReason.GIVEN_UP -> if (won) "Die Gegner gehen – wir bekommen $pts." else "Wir gehen – die Gegner bekommen $pts."
        }
    }
}
