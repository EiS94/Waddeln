package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.rules.trickWinnerIndex

/**
 * Abwurf-Regel: if the chosen card would not take the current trick over, play the cheapest legal
 * card that does not take it over either – whether the own team or the opponents win the trick
 * (e.g. no Haubi-Schlag on an earlier Haubi-Schlag: the first one wins). Without a duty to follow
 * suit, a higher card in hand is practically never worse than a lower one, so giving it away here
 * gains nothing. Likewise a card that would take a sure trick from the partner – the partner wins it
 * and either nobody plays after us or the partner's card cannot be beaten anymore – is replaced by
 * the cheapest one that leaves the trick to the partner (as the rule bot plays).
 * Mirrored in training/waddeln/bots.py (`apply_discard_rule`).
 */
object DiscardRule {

    /** Value of keeping a card (as in [RuleBasedAgent]): every trump is worth more than any plain card. */
    fun value(card: Card, trump: Trump): Int {
        val s = trump.strength(card)
        return if (s == Trump.NO_TRUMP) card.rank.ordinal else 100 + s
    }

    fun apply(observation: Observation, legalActions: List<Action>, chosen: Action): Action {
        if (chosen !is Action.PlayCard) return chosen
        val trump = observation.trump ?: return chosen
        val trick = observation.currentTrick
        if (trick.isEmpty()) return chosen
        val trickCards = trick.map { it.card }

        fun takesOver(card: Card) = trickWinnerIndex(trickCards + card, trump) == trick.size
        if (takesOver(chosen.card) && !partnerSurelyWins(observation, trump)) return chosen
        val cheapest = legalActions
            .filterIsInstance<Action.PlayCard>()
            .filter { !takesOver(it.card) }
            .minByOrNull { value(it.card, trump) } ?: return chosen
        return if (!takesOver(chosen.card) && value(cheapest.card, trump) >= value(chosen.card, trump)) chosen else cheapest
    }

    /** The partner wins the current trick for sure: we play last, or nobody can beat their card anymore. */
    private fun partnerSurelyWins(obs: Observation, trump: Trump): Boolean {
        val trick = obs.currentTrick
        val winning = trick[trickWinnerIndex(trick.map { it.card }, trump)]
        if (teamOf(winning.player) != obs.team) return false
        if (trick.size == PLAYER_COUNT - 1) return true
        val s = trump.strength(winning.card)
        if (s == Trump.NO_TRUMP) return false
        val seen = obs.hand + trick.map { it.card } + obs.completedTricks.flatMap { t -> t.plays.map { it.card } }
        return Card.ALL.none { it !in seen && trump.strength(it) > s }
    }
}
