package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.trickWinnerIndex
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Answer to "Was soll ich machen?": [NICHTS] don't take the trick; [STICHELN] take it, but not too high,
 * so the opponent after you has to spend a good card that the partner beats at the end; [MACHEN] play a
 * card that takes the trick fairly surely (roughly a Haubi-Schlag or better).
 */
@Serializable
enum class TrickAdvice { NICHTS, STICHELN, MACHEN }

/**
 * "Was soll ich machen?": after an opponent led, the second player of the trick asks the partner, who
 * plays last in it. A plain heuristic (no ML) for asking, answering and following the answer.
 */
object Advice {

    /** A [TrickAdvice.MACHEN] card below the strongest one is played if the next opponent cannot beat it with this chance. */
    const val MACHEN_SAFE = 0.5

    /** The partner says "Nichts" if they take the trick themselves with this chance. */
    const val NICHTS_FROM = 0.6

    /** How often a bot that [mayAsk] really asks; always asking would make the bots predictable and the game slow. */
    const val ASK_CHANCE = 0.6

    /** True if a bot asks now: it [mayAsk], with [ASK_CHANCE]. */
    fun asks(state: GameState, player: Int, random: Random): Boolean =
        mayAsk(state, player) && random.nextDouble() < ASK_CHANCE

    /** A bot considers asking if it could take the trick and has a choice of cards. */
    fun mayAsk(state: GameState, player: Int): Boolean {
        if (!Waddeln.canAskAdvice(state, player)) return false
        val trump = state.trump ?: return false
        val lead = state.currentTrick.map { it.card }
        val playable = Waddeln.playableCards(state, player)
        return playable.size > 1 && playable.any { beats(lead, it, trump) }
    }

    /**
     * The partner's answer; they play last in the trick and see the card of the opponent before them.
     * Holding a card that beats whatever that opponent plays, they say "Sticheln" (or "Nichts" if nobody
     * else can beat the lead anyway). Taking the trick themselves with a chance of [NICHTS_FROM], they
     * say "Nichts". Otherwise "Machen", unless no unseen card beats the lead: then there is nothing to do.
     */
    fun answerFor(obs: Observation): TrickAdvice {
        val trump = obs.trump ?: return TrickAdvice.NICHTS
        val lead = obs.currentTrick.map { it.card }
        if (lead.isEmpty()) return TrickAdvice.NICHTS
        val unknown = PartnerHints.unseen(obs) - obs.visibleBottomCard
        val leadBeaters = unknown.filter { beats(lead, it, trump) }
        val opponent = (obs.player + PLAYER_COUNT - 1) % PLAYER_COUNT
        val chance = obs.hand.filter { beats(lead, it, trump) }.maxOfOrNull { card ->
            val unanswered = leadBeaters.count { !beats(lead + it, card, trump) }
            PartnerHints.chanceNoneAmong(unknown.size, unanswered, obs.handSizes[opponent])
        } ?: 0.0
        return when {
            chance >= 1.0 -> if (leadBeaters.isNotEmpty()) TrickAdvice.STICHELN else TrickAdvice.NICHTS
            chance >= NICHTS_FROM || leadBeaters.isEmpty() -> TrickAdvice.NICHTS
            else -> TrickAdvice.MACHEN
        }
    }

    /**
     * The actions [player] chooses from once the partner answered their "Was soll ich machen?" in this
     * trick: "Nichts" only cards that don't take the trick (if every card takes it, the cheapest one),
     * "Sticheln" cards that take it but can still
     * be beaten, "Machen" cards from a Haubi-Schlag up: the cheapest one the next opponent cannot beat
     * with a chance of [MACHEN_SAFE], else any of them. If no card fits, nothing is restricted.
     */
    fun allowedActions(state: GameState, player: Int, legalActions: List<Action>): List<Action> {
        val answer = Waddeln.adviceFor(state, player) ?: return legalActions
        val trump = state.trump ?: return legalActions
        val obs = state.observationFor(player)
        val lead = obs.currentTrick.map { it.card }
        val cards = legalActions.filterIsInstance<Action.PlayCard>().map { it.card }
        val beaters = cards.filter { beats(lead, it, trump) }
        val chosen = when (answer) {
            TrickAdvice.NICHTS -> (cards - beaters.toSet()).ifEmpty { listOfNotNull(beaters.minByOrNull { DiscardRule.value(it, trump) }) }
            TrickAdvice.STICHELN -> beaters.filter { holdChance(obs, lead, it, trump) < 1.0 }.ifEmpty { beaters }
            TrickAdvice.MACHEN -> machen(obs, lead, beaters, trump)
        }
        if (chosen.isEmpty()) return legalActions
        return legalActions.filter { it !is Action.PlayCard || it.card in chosen }
    }

    private fun machen(obs: Observation, lead: List<Card>, beaters: List<Card>, trump: Trump): List<Card> {
        val strong = beaters.filter { it.isKritte || it == trump.haubi || trump.isHaubiSchlag(it) }
        if (strong.isEmpty()) return beaters
        val cheapestSafe = strong.filter { holdChance(obs, lead, it, trump) >= MACHEN_SAFE }.minByOrNull { trump.strength(it) }
        return listOfNotNull(cheapestSafe).ifEmpty { strong }
    }

    /** Chance that the opponent after [Observation.player] holds no unseen card that beats [card] played on [lead]. */
    internal fun holdChance(obs: Observation, lead: List<Card>, card: Card, trump: Trump): Double {
        val unknown = PartnerHints.unseen(obs) - obs.visibleBottomCard
        val beaters = unknown.count { beats(lead + card, it, trump) }
        return PartnerHints.chanceNoneAmong(unknown.size, beaters, obs.handSizes[nextPlayer(obs.player)])
    }

    private fun beats(cards: List<Card>, card: Card, trump: Trump): Boolean = trickWinnerIndex(cards + card, trump) == cards.size
}
