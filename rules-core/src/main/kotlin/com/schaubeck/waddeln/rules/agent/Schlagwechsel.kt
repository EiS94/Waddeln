package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit

/**
 * How bots handle the Schlagwechsel (training/waddeln/talk.py mirrors it). A good Schlag needs
 * several cards of one rank; whoever lacks that but is long in one suit would rather name the Farbe.
 */
object Schlagwechsel {

    /** Most cards of one rank in [hand]; Kritten don't count (as in [RuleBasedAgent.chooseSchlag]). */
    fun bestRankCount(hand: List<Card>): Int = Rank.entries.maxOf { r -> hand.count { it.rank == r && !it.isKritte } }

    /** Most cards of one suit in [hand]; Kritten don't count. */
    fun longestSuit(hand: List<Card>): Int = Suit.entries.maxOf { s -> hand.count { it.suit == s && !it.isKritte } }

    /** The player after the dealer proposes it without two cards of one rank but with three of one suit. */
    fun proposes(obs: Observation): Boolean = bestRankCount(obs.hand) < 2 && longestSuit(obs.hand) >= 3

    /** The dealer accepts with two or more cards of one rank (a Schlag of his own). */
    fun accepts(obs: Observation): Boolean = bestRankCount(obs.hand) >= 2
}
