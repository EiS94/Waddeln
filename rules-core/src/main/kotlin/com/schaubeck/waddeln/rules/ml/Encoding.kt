package com.schaubeck.waddeln.rules.ml

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.GESPANNT_POINTS
import com.schaubeck.waddeln.rules.MATCH_POINTS
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Play
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.rules.trickWinnerIndex
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer

/**
 * Integer codes for actions, shared with the Python simulator (training/waddeln/engine.py):
 * 0..7 AnnounceSchlag(rank), 8..11 AnnounceFarbe(suit), 12..43 PlayCard(card index),
 * 44 Schub, 45 Bleiben, 46 Gehen.
 */
object ActionCodec {
    const val SIZE = 47
    private const val FARBE_OFFSET = 8
    private const val CARD_OFFSET = 12
    const val SCHUB = 44
    const val BLEIBEN = 45
    const val GEHEN = 46

    fun encode(action: Action): Int = when (action) {
        is Action.AnnounceSchlag -> action.rank.ordinal
        is Action.AnnounceFarbe -> FARBE_OFFSET + action.suit.ordinal
        is Action.PlayCard -> CARD_OFFSET + action.card.index
        Action.Schub -> SCHUB
        Action.Bleiben -> BLEIBEN
        Action.Gehen -> GEHEN
    }

    fun decode(code: Int): Action = when (code) {
        in 0 until FARBE_OFFSET -> Action.AnnounceSchlag(Rank.entries[code])
        in FARBE_OFFSET until CARD_OFFSET -> Action.AnnounceFarbe(Suit.entries[code - FARBE_OFFSET])
        in CARD_OFFSET until SCHUB -> Action.PlayCard(Card.of(code - CARD_OFFSET))
        SCHUB -> Action.Schub
        BLEIBEN -> Action.Bleiben
        GEHEN -> Action.Gehen
        else -> throw IllegalArgumentException("Unknown action code $code")
    }
}

/**
 * Turns an [Observation] into the network input. The layout must match
 * training/waddeln/features.py exactly; parity is checked via the JSON protocols.
 * Seats are relative to the observing player: 0 = self, 1 = next (left), 2 = partner, 3 = right.
 */
object FeatureEncoder {
    const val STATE_SIZE = 517
    const val ACTION_SIZE = ActionCodec.SIZE + 4
    const val INPUT_SIZE = STATE_SIZE + ACTION_SIZE

    /**
     * State features of models trained before the table talk (Schub calls, partner hints,
     * "Schadder?", known Haubi-Farbe voids) was added. New features are only appended, so such a
     * model gets the first [LEGACY_STATE_SIZE] state features (see [encodeFor]).
     */
    const val LEGACY_STATE_SIZE = 478
    const val LEGACY_INPUT_SIZE = LEGACY_STATE_SIZE + ACTION_SIZE

    private fun rel(obs: Observation, seat: Int) = (seat - obs.player + PLAYER_COUNT) % PLAYER_COUNT

    /** Normalized trump strength in (0, 1], 0 for non-trump. */
    fun trumpValue(trump: Trump?, card: Card): Float {
        val s = trump?.strength(card) ?: return 0f
        return if (s == Trump.NO_TRUMP) 0f else (s - 79) / 21f
    }

    fun encodeState(obs: Observation): FloatArray {
        val f = FloatArray(STATE_SIZE)
        var o = 0
        fun cardBlock(cards: Iterable<Card>) {
            for (c in cards) f[o + c.index] = 1f
            o += 32
        }
        fun oneHot(index: Int, size: Int) {
            if (index in 0 until size) f[o + index] = 1f
            o += size
        }
        fun scalar(value: Float) {
            f[o++] = value
        }

        val trump = obs.trump
        val playedBySeat = List(PLAYER_COUNT) { mutableListOf<Card>() }
        obs.completedTricks.forEach { t -> t.plays.forEach { playedBySeat[rel(obs, it.player)] += it.card } }
        val trickBySeat = List(PLAYER_COUNT) { mutableListOf<Card>() }
        obs.currentTrick.forEach { trickBySeat[rel(obs, it.player)] += it.card }

        cardBlock(obs.hand)                                                   // 0: own hand
        playedBySeat.forEach { cardBlock(it) }                                // 32: completed tricks per seat
        trickBySeat.forEach { cardBlock(it) }                                 // 160: current trick per seat
        val known = obs.hand.toSet() + playedBySeat.flatten() + trickBySeat.flatten() + obs.visibleBottomCard
        cardBlock(Card.ALL.filter { it !in known })                           // 288: unseen cards
        cardBlock(listOf(obs.visibleBottomCard))                              // 320: open bottom card
        cardBlock(Card.ALL.filter { trump?.isTrump(it) == true })             // 352: trump flags
        for (c in Card.ALL) f[o + c.index] = trumpValue(trump, c)             // 384: trump strength
        o += 32
        oneHot(obs.schlag?.ordinal ?: -1, 8)                                  // 416
        oneHot(obs.farbe?.ordinal ?: -1, 4)                                   // 424
        oneHot(
            when (obs.phase) {
                Phase.SCHLAG -> 0
                Phase.FARBE -> 1
                Phase.PLAYING -> 2
                else -> -1
            },
            3,
        )                                                                     // 428
        oneHot(rel(obs, obs.dealer), 4)                                       // 431
        oneHot(obs.abheberKritten, 4)                                         // 435
        val own = obs.team
        val opp = 1 - own
        oneHot(obs.tricksWon[own], 4)                                         // 439
        oneHot(obs.tricksWon[opp], 4)                                         // 443
        oneHot(rel(obs, obs.trickLeader), 4)                                  // 447
        scalar((obs.gameValue - 2) / 4f)                                      // 451
        scalar(obs.scores[own] / MATCH_POINTS.toFloat())                      // 452
        scalar(obs.scores[opp] / MATCH_POINTS.toFloat())
        scalar(if (obs.scores[own] >= GESPANNT_POINTS) 1f else 0f)
        scalar(if (obs.scores[opp] >= GESPANNT_POINTS) 1f else 0f)
        scalar(if (obs.lastSchubTeam == own) 1f else 0f)                      // 456
        scalar(if (obs.lastSchubTeam == opp) 1f else 0f)
        scalar(if (obs.pendingSchub?.askingTeam == opp) 1f else 0f)           // 458
        scalar(if (obs.pendingSchub?.askingTeam == own) 1f else 0f)
        fun canSchub(team: Int) = obs.phase == Phase.PLAYING && obs.pendingSchub == null &&
            obs.scores[team] < GESPANNT_POINTS && obs.lastSchubTeam != team &&
            obs.scores[team] + obs.gameValue < MATCH_POINTS
        scalar(if (canSchub(own)) 1f else 0f)                                 // 460
        scalar(if (canSchub(opp)) 1f else 0f)
        val laid = obs.laidOutCounts
        for (r in 0 until PLAYER_COUNT) {                                     // 462
            val seat = (obs.player + r) % PLAYER_COUNT
            scalar((laid?.get(seat) ?: 0) / 3f)
        }
        scalar(if (laid != null) 1f else 0f)                                  // 466
        for (r in 0 until PLAYER_COUNT) {                                     // 467
            val seat = (obs.player + r) % PLAYER_COUNT
            scalar(obs.handSizes[seat] / 5f)
        }
        oneHot(obs.currentTrick.size, 4)                                      // 471
        val leading = leadingPlay(obs)
        scalar(if (leading != null && teamOf(leading.player) == own) 1f else 0f) // 475: own team holds the trick
        scalar(if (leading != null && teamOf(leading.player) == opp) 1f else 0f) // 476: opponents hold it
        scalar(leading?.let { trumpValue(trump, it.card) } ?: 0f)             // 477: strength of the leading card

        // ---- Table talk (everybody hears it) ----
        for (r in 0 until PLAYER_COUNT) {                                     // 478: latest hint per seat
            val seat = (obs.player + r) % PLAYER_COUNT
            oneHot(obs.hints.lastOrNull { it.player == seat }?.hint?.ordinal ?: -1, PartnerHint.entries.size)
        }
        val schadet = obs.schadet
        oneHot(schadet?.let { rel(obs, it.asker) } ?: -1, 4)                  // 498: who asked "Schadder?" (holds the Haubi)
        oneHot(schadet?.answer?.ordinal ?: -1, SchadetAnswer.entries.size)    // 502: the partner's answer
        val voids = haubiFarbeVoids(obs)
        for (r in 0 until PLAYER_COUNT) {                                     // 505: known to have no Haubi-Farbe
            scalar(if ((obs.player + r) % PLAYER_COUNT in voids) 1f else 0f)
        }
        for (r in 0 until PLAYER_COUNT) {                                     // 509: Schubs said per seat
            val seat = (obs.player + r) % PLAYER_COUNT
            scalar(obs.schubs.count { it.player == seat } / 2f)
        }
        for (r in 0 until PLAYER_COUNT) {                                     // 513: said Schub before the first card
            val seat = (obs.player + r) % PLAYER_COUNT
            scalar(if (obs.schubs.any { it.player == seat && it.cardsPlayed == 0 }) 1f else 0f)
        }
        check(o == STATE_SIZE) { "Feature layout mismatch: $o" }
        return f
    }

    fun encodeAction(obs: Observation, action: Action): FloatArray {
        val f = FloatArray(ACTION_SIZE)
        f[ActionCodec.encode(action)] = 1f
        if (action is Action.PlayCard) {
            val trump = obs.trump
            f[ActionCodec.SIZE] = if (trump?.isTrump(action.card) == true) 1f else 0f
            f[ActionCodec.SIZE + 1] = trumpValue(trump, action.card)
            f[ActionCodec.SIZE + 2] = if (wouldWinTrick(obs, action.card)) 1f else 0f
            f[ActionCodec.SIZE + 3] = if (isUnbeatable(obs, action.card)) 1f else 0f
        }
        return f
    }

    /**
     * Players known to hold no Haubi-Farbe: the Haubi was led into the first trick and they
     * followed with neither Haubi-Farbe nor a Kritte while the Bekennen-Pflicht was in force.
     */
    fun haubiFarbeVoids(obs: Observation): Set<Int> {
        val trump = obs.trump ?: return emptySet()
        val firstTrick = obs.completedTricks.firstOrNull()?.plays ?: obs.currentTrick
        if (firstTrick.firstOrNull()?.card != trump.haubi) return emptySet()
        val voids = mutableSetOf<Int>()
        for (play in firstTrick.drop(1)) {
            if (play.card.isKritte) break
            if (!trump.isHaubiFarbe(play.card)) voids += play.player
        }
        return voids
    }

    /** The play currently winning the trick, or null if the trick is empty / trump not yet fixed. */
    private fun leadingPlay(obs: Observation): Play? {
        val trump = obs.trump ?: return null
        val trick = obs.currentTrick
        if (trick.isEmpty()) return null
        return trick[trickWinnerIndex(trick.map { it.card }, trump)]
    }

    /** True if playing [card] now would make the observer the current winner of a started trick. */
    private fun wouldWinTrick(obs: Observation, card: Card): Boolean {
        val trump = obs.trump ?: return false
        val trick = obs.currentTrick
        if (trick.isEmpty()) return false
        return trickWinnerIndex(trick.map { it.card } + card, trump) == trick.size
    }

    /** True if [card] is a trump that no card still in other hands can beat. */
    private fun isUnbeatable(obs: Observation, card: Card): Boolean {
        val trump = obs.trump ?: return false
        val s = trump.strength(card)
        if (s == Trump.NO_TRUMP) return false
        val known = obs.hand.toSet() + obs.currentTrick.map { it.card } +
            obs.completedTricks.flatMap { t -> t.plays.map { it.card } } + obs.visibleBottomCard
        return Card.ALL.none { it !in known && trump.strength(it) > s }
    }

    /** Network input for one candidate action: state features followed by action features. */
    fun encode(obs: Observation, action: Action): FloatArray = encodeState(obs) + encodeAction(obs, action)

    /** Network input for a model with [inputSize] inputs: the current layout or the legacy one. */
    fun encodeFor(inputSize: Int, state: FloatArray, action: FloatArray): FloatArray = when (inputSize) {
        INPUT_SIZE -> state + action
        LEGACY_INPUT_SIZE -> state.copyOf(LEGACY_STATE_SIZE) + action
        else -> throw IllegalArgumentException("Model has $inputSize inputs, expected $INPUT_SIZE or $LEGACY_INPUT_SIZE")
    }
}
