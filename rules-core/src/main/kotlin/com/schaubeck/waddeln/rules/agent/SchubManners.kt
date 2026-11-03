package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.MATCH_POINTS
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.rules.trickWinnerIndex
import kotlin.random.Random

/**
 * How bots at the table handle a Schub, around any [base] agent (as people play, not part of training):
 * - With the deciding trick already sure ([decidingTrickSure]) nobody says Schub anymore.
 * - If the raised game would hand the opponents the match when lost ([raiseLosesMatch]), a Schub is only
 *   kept when the own team is likely to win the game ([winChance]), and not always even then.
 */
class SchubManners(private val base: Agent, private val random: Random = Random.Default) : Agent {

    override fun chooseAction(observation: Observation, legalActions: List<Action>): Action {
        if (Action.Schub !in legalActions) return base.chooseAction(observation, legalActions)
        val withoutSchub = legalActions - Action.Schub
        if (withoutSchub.isNotEmpty() && decidingTrickSure(observation)) return base.chooseAction(observation, withoutSchub)
        val chosen = base.chooseAction(observation, legalActions)
        if (chosen != Action.Schub || withoutSchub.isEmpty() || !raiseLosesMatch(observation)) return chosen
        return if (random.nextDouble() < keepChance(winChance(observation, random))) chosen else base.chooseAction(observation, withoutSchub)
    }

    companion object {
        /** Win chance from which a risky Schub is always kept. */
        const val SURE_FROM = 0.9

        /** Win chance up to which a risky Schub is only kept as a rare bluff ([BLUFF_CHANCE]). */
        const val UNLIKELY_UP_TO = 0.5

        const val BLUFF_CHANCE = 0.05

        /** Deals sampled for [winChance]. */
        const val SAMPLES = 40

        /** Chance to keep a risky Schub at [winChance]: rising from [BLUFF_CHANCE] to 1 between the two limits. */
        fun keepChance(winChance: Double): Double {
            val t = ((winChance - UNLIKELY_UP_TO) / (SURE_FROM - UNLIKELY_UP_TO)).coerceIn(0.0, 1.0)
            return BLUFF_CHANCE + (1 - BLUFF_CHANCE) * t
        }

        /**
         * The own team needs one more trick and has the current one for sure: its card wins so far and no
         * opponent is left to play or no unseen card beats it. Also when the player is to move and holds
         * such a card.
         */
        fun decidingTrickSure(obs: Observation): Boolean {
            val trump = obs.trump ?: return false
            if (obs.tricksWon[obs.team] != TRICKS_TO_WIN - 1) return false
            val trick = obs.currentTrick.map { it.card }
            val unseen = PartnerHints.unseen(obs) - obs.visibleBottomCard

            fun sure(cards: List<Card>, winner: Int): Boolean {
                if (teamOf(winner) != obs.team) return false
                val leader = obs.trickLeader
                val opponentsLeft = (cards.size until PLAYER_COUNT).any { teamOf((leader + it) % PLAYER_COUNT) != obs.team }
                return !opponentsLeft || unseen.none { trickWinnerIndex(cards + it, trump) == cards.size }
            }

            if (trick.isNotEmpty() && sure(trick, obs.currentTrick[trickWinnerIndex(trick, trump)].player)) return true
            if (obs.playerToMove != obs.player || obs.pendingSchub != null) return false
            return obs.hand.any { card ->
                val cards = trick + card
                trickWinnerIndex(cards, trump) == trick.size && sure(cards, obs.player)
            }
        }

        /** Staying would raise the game so far that losing it gives the opponents the match (and now it would not). */
        fun raiseLosesMatch(obs: Observation): Boolean {
            val opponents = obs.scores[1 - obs.team]
            return opponents + obs.gameValue < MATCH_POINTS && opponents + obs.gameValue + 1 >= MATCH_POINTS
        }

        /** Share of deals of the unseen cards in which the own team wins the game with open, perfect play. */
        fun winChance(obs: Observation, random: Random, samples: Int = SAMPLES): Double {
            val trump = obs.trump ?: return 0.0
            val solver = DoubleDummy(trump, obs.team)
            var dealt = 0
            var won = 0
            repeat(samples) {
                val hands = HandSampler.sample(obs, random) ?: return@repeat
                dealt++
                if (solver.teamWins(hands, obs.trickLeader, obs.currentTrick, obs.tricksWon, obs.completedTricks.size)) won++
            }
            return if (dealt == 0) 0.0 else won.toDouble() / dealt
        }
    }
}
