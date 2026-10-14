package com.schaubeck.waddeln.ui

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.RoundEndReason
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.agent.PartnerHint
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

    fun kritteName(card: Card): String? = when (card) {
        Card.MAX -> "Max"
        Card.BELLO -> "Bello"
        Card.SECHER -> "Secher"
        else -> null
    }

    fun haubi(trump: Trump): String =
        "Haubi: ${card(trump.haubi)}" + (kritteName(trump.haubi)?.let { " ($it)" } ?: "")

    /** Speech bubble for an action said aloud; null for playing a card. */
    fun speech(action: Action): String? = when (action) {
        is Action.AnnounceSchlag -> "Schlag: ${rank(action.rank)}"
        is Action.AnnounceFarbe -> "Farbe: ${suit(action.suit)}"
        Action.Schub -> "Schub!"
        Action.Bleiben -> "Wir bleiben!"
        Action.Gehen -> "Wir gehen."
        is Action.PlayCard -> null
    }

    /** What the partner says about their cards; [tricksWon] are the tricks our team already has. */
    fun partnerHint(hint: PartnerHint, tricksWon: Int): String = when (hint) {
        PartnerHint.NOTHING -> "Ich hab gar nichts."
        PartnerHint.LITTLE -> "Ich hab nicht viel."
        PartnerHint.ONE_TRICK -> "Einen Stich mach ich."
        PartnerHint.TWO_TRICKS -> "Zwei Stiche mach ich."
        PartnerHint.ALL_TRICKS -> when (TRICKS_TO_WIN - tricksWon) {
            1 -> "Den letzten Stich mach ich!"
            2 -> "Die zwei Stiche mach ich!"
            else -> "Ich mach alle 3 Stiche!"
        }
    }

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
