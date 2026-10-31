package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.MATCH_POINTS
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.rules.trickWinnerIndex

/**
 * Hand-written heuristic bot. Serves as fallback opponent and as sparring partner for training.
 * It only uses information from its [Observation].
 */
class RuleBasedAgent : Agent {

    override fun chooseAction(observation: Observation, legalActions: List<Action>): Action {
        require(legalActions.isNotEmpty())
        // Bots are only asked when it is their turn or when they must answer a Schub, so
        // a lone Schub (announcing out of turn) is never the forced choice.
        if (legalActions.size == 1 && legalActions.single() != Action.Schub) return legalActions.single()
        val first = legalActions.first()
        return when {
            first is Action.AnnounceSchlag -> Action.AnnounceSchlag(chooseSchlag(observation.hand))
            first is Action.AnnounceFarbe -> Action.AnnounceFarbe(chooseFarbe(observation.hand, observation.schlag!!))
            Action.Bleiben in legalActions ->
                if (SchubRule.mustStay(observation) || (LastTrick.shouldStay(observation) ?: shouldStay(observation))) {
                    Action.Bleiben
                } else {
                    Action.Gehen
                }
            else -> {
                val cards = legalActions.filterIsInstance<Action.PlayCard>().map { it.card }
                if (Action.Schub in legalActions && shouldSchub(observation)) return Action.Schub
                if (cards.isEmpty()) return legalActions.first()
                Action.PlayCard(chooseCard(observation, cards))
            }
        }
    }

    /** Announces the rank held most often (Kritten don't count); ties go to the higher rank. */
    internal fun chooseSchlag(hand: List<Card>): Rank =
        Rank.entries.maxWith(compareBy<Rank> { r -> hand.count { it.rank == r && !it.isKritte } }.thenBy { it.ordinal })

    /** Chooses the suit that makes the own hand strongest. */
    internal fun chooseFarbe(hand: List<Card>, schlag: Rank): Suit =
        Suit.entries.maxBy { handStrength(hand, Trump(schlag, it)) }

    private fun handStrength(hand: List<Card>, trump: Trump): Int =
        hand.sumOf { card ->
            val s = trump.strength(card)
            if (s == Trump.NO_TRUMP) (if (card.rank == Rank.ASS) 10 else 0) else 20 + s
        }

    // ---- Card play -------------------------------------------------------------------------

    private fun chooseCard(obs: Observation, playable: List<Card>): Card {
        val trump = obs.trump!!
        val trick = obs.currentTrick
        if (trick.isEmpty()) return chooseLead(obs, playable, trump)

        val trickCards = trick.map { it.card }
        val winningPlay = trick[trickWinnerIndex(trickCards, trump)]
        val isLast = trick.size == PLAYER_COUNT - 1
        val partnerWinning = teamOf(winningPlay.player) == obs.team

        if (partnerWinning && (isLast || isBoss(winningPlay.card, obs, trump))) return cheapest(playable, trump)

        val winners = playable.filter { card -> trickWinnerIndex(trickCards + card, trump) == trick.size }
        if (winners.isEmpty()) return cheapest(playable, trump)
        if (partnerWinning) {
            // Partner is winning but could be beaten: only overtake with a card nobody can beat.
            return winners.filter { isBoss(it, obs, trump) }.minByOrNull { value(it, trump) } ?: cheapest(playable, trump)
        }
        if (isLast) return winners.minBy { value(it, trump) }
        // Prefer a winner that cannot be beaten anymore, otherwise the cheapest winner.
        return winners.filter { isBoss(it, obs, trump) }.minByOrNull { value(it, trump) }
            ?: winners.minBy { value(it, trump) }
    }

    private fun chooseLead(obs: Observation, playable: List<Card>, trump: Trump): Card {
        val bosses = playable.filter { trump.isTrump(it) && isBoss(it, obs, trump) }
        if (bosses.isNotEmpty()) return bosses.maxBy { trump.strength(it) }
        // Lead a high plain card (Ass/König) hoping it holds, otherwise get rid of the weakest card.
        val highPlain = playable.filter { !trump.isTrump(it) && it.rank >= Rank.KOENIG }
        if (highPlain.isNotEmpty()) return highPlain.maxBy { it.rank }
        return cheapest(playable, trump)
    }

    private fun cheapest(cards: List<Card>, trump: Trump): Card = cards.minBy { value(it, trump) }

    /** Rough value of keeping a card: every trump is worth more than any plain card. */
    private fun value(card: Card, trump: Trump): Int {
        val s = trump.strength(card)
        return if (s == Trump.NO_TRUMP) card.rank.ordinal else 100 + s
    }

    private fun seenCards(obs: Observation): Set<Card> =
        (obs.hand + obs.currentTrick.map { it.card } + obs.completedTricks.flatMap { t -> t.plays.map { it.card } }).toSet()

    /** True if no trump outside the known cards can beat [card]. */
    private fun isBoss(card: Card, obs: Observation, trump: Trump): Boolean {
        val s = trump.strength(card)
        if (s == Trump.NO_TRUMP) return false
        val seen = seenCards(obs)
        return Card.ALL.none { it !in seen && trump.strength(it) > s }
    }

    // ---- Schub -----------------------------------------------------------------------------

    /** Estimated number of further tricks the own team wins. */
    private fun expectedTricks(obs: Observation): Double {
        val trump = obs.trump ?: return 0.0
        val seen = seenCards(obs)
        val unseenTrumpStrengths = Card.ALL.filter { it !in seen && trump.isTrump(it) }.map { trump.strength(it) }
        var expected = 0.0
        for (card in obs.hand) {
            val s = trump.strength(card)
            expected += when {
                s == Trump.NO_TRUMP -> if (card.rank == Rank.ASS) 0.3 else 0.0
                unseenTrumpStrengths.none { it > s } -> 1.0
                unseenTrumpStrengths.count { it > s } == 1 -> 0.6
                else -> 0.35
            }
        }
        // Partner's unknown hand contributes on average.
        return expected + 0.6
    }

    private fun ownTricks(obs: Observation) = obs.tricksWon[obs.team]
    private fun opponentTricks(obs: Observation) = obs.tricksWon[1 - obs.team]

    private fun shouldSchub(obs: Observation): Boolean {
        val needed = TRICKS_TO_WIN - ownTricks(obs)
        return expectedTricks(obs) >= needed + 0.8
    }

    private fun shouldStay(obs: Observation): Boolean {
        val needed = TRICKS_TO_WIN - ownTricks(obs)
        val opponentsNeed = TRICKS_TO_WIN - opponentTricks(obs)
        val chance = expectedTricks(obs) - needed
        // Losing after staying costs one point more; be careful if that would end the match.
        val opponents = 1 - obs.team
        val stayingLosesMatch = obs.scores[opponents] + obs.gameValue + 1 >= MATCH_POINTS &&
            obs.scores[opponents] + obs.gameValue < MATCH_POINTS
        val threshold = if (stayingLosesMatch) 0.0 else -0.5
        return chance >= threshold || (opponentsNeed >= 2 && chance >= threshold - 0.5)
    }
}
