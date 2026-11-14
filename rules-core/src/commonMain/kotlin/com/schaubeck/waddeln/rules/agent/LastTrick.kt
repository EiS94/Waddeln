package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.partnerOf
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.rules.trickWinnerIndex

/**
 * Answering a Schub once the partner has played his last card: there is nobody left to ask, so the
 * player decides from the trick itself, his own card and the cards the opponents may still hold.
 */
object LastTrick {

    /** From this chance to take the trick on, the player stays. */
    const val STAY_FROM = 0.5

    /** Stay or go, or null if the partner still holds cards (then the usual decision applies). */
    fun shouldStay(obs: Observation): Boolean? = ourChance(obs)?.let { it >= STAY_FROM }

    /**
     * Chance that our team takes the trick with the partner's card already played and the own card
     * added: zero if the opponents' cards on the table win anyway, otherwise the chance that no
     * opponent still to play holds a card that beats it. Null if the partner still holds cards.
     */
    fun ourChance(obs: Observation): Double? {
        val trump = obs.trump ?: return null
        if (obs.handSizes[partnerOf(obs.player)] > 0 || obs.currentTrick.isEmpty()) return null
        val cards = obs.currentTrick.map { it.card } + obs.hand.take(1)
        val players = obs.currentTrick.map { it.player } + listOf(obs.player).take(obs.hand.size.coerceAtMost(1))
        if (teamOf(players[trickWinnerIndex(cards, trump)]) != obs.team) return 0.0
        val unknown = PartnerHints.unseen(obs) - obs.visibleBottomCard
        val beaters = unknown.count { trickWinnerIndex(cards + it, trump) == cards.size }
        val opponents = (0 until PLAYER_COUNT).filter { p -> p !in players && teamOf(p) != obs.team }
        return PartnerHints.chanceNoneAmong(unknown.size, beaters, opponents.sumOf { obs.handSizes[it] })
    }
}
