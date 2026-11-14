package com.schaubeck.waddeln.rules.ml

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.HAND_SIZE
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Play
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.TEAM_COUNT
import com.schaubeck.waddeln.rules.Trick
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.teamOf
import kotlin.math.exp
import kotlin.math.ln

/**
 * How likely a deal of the unseen cards is, judged by what the other players did: the Schlag and Farbe
 * they announced and the cards they played. Each of their decisions is rated with the network as if it
 * had played with that deal (softmax over its values with [temperature], in game points), so a deal in
 * which their moves look sensible gets a higher weight. Schub decisions and what the dealer saw under
 * the rest are not used.
 */
class DealInference(private val q: QFunction, private val temperature: Double = 0.5) {

    /** One weight per deal (each deal = current hands of all players), proportional to its likelihood. */
    fun weights(obs: Observation, deals: List<List<List<Card>>>): DoubleArray {
        val trump = obs.trump ?: return DoubleArray(deals.size) { 1.0 }
        val decisions = deals.map { decisionsOf(obs, trump, it) }
        val rows = decisions.flatMap { d -> d.flatMap { it.rows } }
        if (rows.isEmpty()) return DoubleArray(deals.size) { 1.0 }
        val values = evaluate(rows)
        var at = 0
        val logs = decisions.map { d ->
            d.sumOf { decision ->
                val v = values.copyOfRange(at, at + decision.rows.size).also { at += decision.rows.size }
                logChance(v, decision.taken)
            }
        }
        val max = logs.max()
        return DoubleArray(deals.size) { exp(logs[it] - max) }
    }

    private fun evaluate(rows: List<FloatArray>): FloatArray {
        val out = FloatArray(rows.size)
        rows.chunked(BATCH).fold(0) { at, chunk ->
            q.evaluate(chunk.toTypedArray()).copyInto(out, at)
            at + chunk.size
        }
        return out
    }

    private fun logChance(values: FloatArray, taken: Int): Double {
        val max = values.max()
        val sum = values.sumOf { exp((it - max) / temperature) }
        return (values[taken] - max) / temperature - ln(sum)
    }

    private class Decision(val rows: List<FloatArray>, val taken: Int)

    private fun decision(obs: Observation, legal: List<Action>, taken: Action): Decision? {
        if (legal.size < 2) return null
        val index = legal.indexOf(taken).takeIf { it >= 0 } ?: return null
        val state = FeatureEncoder.encodeState(obs)
        return Decision(legal.map { FeatureEncoder.encodeFor(q.inputSize, state, FeatureEncoder.encodeAction(obs, it)) }, index)
    }

    /** The decisions of the other players in this round, seen from their side with [hands] as the deal. */
    private fun decisionsOf(obs: Observation, trump: Trump, hands: List<List<Card>>): List<Decision> {
        val plays = obs.completedTricks.flatMap { it.plays } + obs.currentTrick
        val original = List(PLAYER_COUNT) { p -> hands[p] + plays.filter { it.player == p }.map { it.card } }
        val laid = obs.laidOutCounts != null
        val start = obs.copy(
            phase = Phase.SCHLAG,
            handSizes = List(PLAYER_COUNT) { HAND_SIZE },
            schlag = null,
            farbe = null,
            trickLeader = nextPlayer(obs.dealer),
            currentTrick = emptyList(),
            completedTricks = emptyList(),
            tricksWon = List(TEAM_COUNT) { 0 },
            gameValue = obs.gameValue - obs.schubs.size,
            lastSchubTeam = null,
            pendingSchub = null,
            laidOutCounts = null,
            ownLaidOut = emptySet(),
            result = null,
            schubs = emptyList(),
            hints = emptyList(),
            schadet = null,
            advice = emptyList(),
            geberCard = null,
        )
        val decisions = mutableListOf<Decision>()
        val swapped = obs.schlagwechsel?.accepted == true
        val schlagBy = if (swapped) obs.dealer else nextPlayer(obs.dealer)
        val farbeBy = if (swapped) nextPlayer(obs.dealer) else obs.dealer
        if (schlagBy != obs.player) {
            val o = start.copy(player = schlagBy, hand = original[schlagBy], playerToMove = schlagBy)
            decision(o, Rank.entries.map { Action.AnnounceSchlag(it) }, Action.AnnounceSchlag(trump.schlag))?.let { decisions += it }
        }
        if (farbeBy != obs.player) {
            val o = start.copy(player = farbeBy, phase = Phase.FARBE, schlag = trump.schlag, hand = original[farbeBy], playerToMove = farbeBy)
            decision(o, Suit.entries.map { Action.AnnounceFarbe(it) }, Action.AnnounceFarbe(trump.farbe))?.let { decisions += it }
        }

        val tricks = obs.completedTricks
        for (n in plays.indices) {
            val p = plays[n].player
            if (p == obs.player) continue
            val done = tricks.take(n / PLAYER_COUNT)
            val trick = plays.subList(n - n % PLAYER_COUNT, n)
            val before = plays.subList(0, n)
            val hand = original[p] - before.filter { it.player == p }.map { it.card }.toSet()
            val schubs = obs.schubs.filter { it.cardsPlayed <= n }
            val o = start.copy(
                player = p,
                phase = Phase.PLAYING,
                hand = hand,
                handSizes = List(PLAYER_COUNT) { x -> original[x].size - before.count { it.player == x } },
                schlag = trump.schlag,
                farbe = trump.farbe,
                trickLeader = trick.firstOrNull()?.player ?: (done.lastOrNull()?.winner ?: nextPlayer(obs.dealer)),
                currentTrick = trick,
                completedTricks = done,
                tricksWon = List(TEAM_COUNT) { t -> done.count { teamOf(it.winner) == t } },
                gameValue = start.gameValue + schubs.size,
                lastSchubTeam = schubs.lastOrNull()?.let { teamOf(it.player) },
                laidOutCounts = if (laid) List(PLAYER_COUNT) { x -> (original[x] - before.filter { it.player == x }.map { it.card }.toSet()).count { it.isKritte || it == trump.haubi } } else null,
                ownLaidOut = if (laid) hand.filter { it.isKritte || it == trump.haubi }.toSet() else emptySet(),
                playerToMove = p,
                schubs = schubs,
                hints = obs.hints.filter { it.schub < schubs.size },
                schadet = obs.schadet,
                advice = obs.advice.filter { it.trick < n / PLAYER_COUNT || (it.trick == n / PLAYER_COUNT && n % PLAYER_COUNT >= 1) },
            )
            decision(o, playable(hand, trick, done, trump).map { Action.PlayCard(it) }, Action.PlayCard(plays[n].card))?.let { decisions += it }
        }
        return decisions
    }

    /** As [com.schaubeck.waddeln.rules.Waddeln.playableCards]. */
    private fun playable(hand: List<Card>, trick: List<Play>, done: List<Trick>, trump: Trump): List<Card> {
        val pflicht = done.isEmpty() && trick.isNotEmpty() && trick.first().card == trump.haubi && trick.drop(1).none { it.card.isKritte }
        if (!pflicht || hand.none { trump.isHaubiFarbe(it) }) return hand
        return hand.filter { trump.isHaubiFarbe(it) || it.isKritte }
    }

    private companion object {
        const val BATCH = 2048
    }
}
