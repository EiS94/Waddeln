package com.schaubeck.waddeln.game

import android.content.Context
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.RoundEndReason
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.teamOf

/** Statistics are kept apart for games against the bots on the device and online games. */
enum class StatsMode { OFFLINE, ONLINE }

/** The counters of the statistics. The names are stored, so they must not be renamed. */
enum class Counter {
    MATCHES, MATCHES_WON, STREAK, BEST_STREAK,
    ROUNDS, ROUNDS_WON, POINTS_WON, POINTS_LOST, BEST_ROUND, WON_TO_NIL,
    OWN_TRICKS,
    SCHLAG_CALLED, SCHLAG_WON, FARBE_CALLED, FARBE_WON,
    HAUBI_HELD, MAX_HELD, BELLO_HELD, SECHER_HELD, TRUMPS_HELD,
    SCHUB_CALLED, SCHUB_WENT, SCHUB_STAYED,
    FACED_STAYED, FACED_STAYED_WON, FACED_WENT,
}

/** Counters that hold a best value instead of a sum. */
private val BEST_VALUES = setOf(Counter.BEST_STREAK, Counter.BEST_ROUND)

/**
 * Collected statistics of one player. [values] maps a [Counter] name, or `SCHLAG_<rank>` /
 * `FARBE_<suit>` for the announcements, to its count. Immutable.
 */
data class Stats(val values: Map<String, Int> = emptyMap()) {
    operator fun get(counter: Counter): Int = values[counter.name] ?: 0
    fun schlag(rank: Rank): Int = values[schlagKey(rank)] ?: 0
    fun farbe(suit: Suit): Int = values[farbeKey(suit)] ?: 0

    val isEmpty: Boolean get() = this[Counter.ROUNDS] == 0

    /** Adds one finished round (and match, if it ended with this round). */
    fun with(round: RoundRecord): Stats {
        val v = values.toMutableMap()
        fun add(key: String, amount: Int = 1) {
            if (amount != 0) v[key] = (v[key] ?: 0) + amount
        }
        fun add(counter: Counter, amount: Int = 1) = add(counter.name, amount)
        fun count(counter: Counter, condition: Boolean) = add(counter, if (condition) 1 else 0)
        fun best(counter: Counter, value: Int) {
            v[counter.name] = maxOf(v[counter.name] ?: 0, value)
        }

        add(Counter.ROUNDS)
        if (round.won) {
            add(Counter.ROUNDS_WON)
            add(Counter.POINTS_WON, round.points)
            best(Counter.BEST_ROUND, round.points)
        } else {
            add(Counter.POINTS_LOST, round.points)
        }
        count(Counter.WON_TO_NIL, round.won && round.reason == RoundEndReason.TRICKS && round.opponentTricks == 0)
        add(Counter.OWN_TRICKS, round.ownTricks)

        round.schlag?.let {
            add(Counter.SCHLAG_CALLED)
            count(Counter.SCHLAG_WON, round.won)
            add(schlagKey(it))
        }
        round.farbe?.let {
            add(Counter.FARBE_CALLED)
            count(Counter.FARBE_WON, round.won)
            add(farbeKey(it))
        }

        round.trump?.let { trump ->
            count(Counter.HAUBI_HELD, trump.haubi in round.hand)
            add(Counter.TRUMPS_HELD, round.hand.count(trump::isTrump))
        }
        count(Counter.MAX_HELD, Card.MAX in round.hand)
        count(Counter.BELLO_HELD, Card.BELLO in round.hand)
        count(Counter.SECHER_HELD, Card.SECHER in round.hand)

        for (schub in round.schubs) {
            when {
                schub.byMe -> {
                    add(Counter.SCHUB_CALLED)
                    add(if (schub.went) Counter.SCHUB_WENT else Counter.SCHUB_STAYED)
                }
                schub.byUs -> Unit // the partner's Schub
                schub.went -> add(Counter.FACED_WENT)
                else -> {
                    add(Counter.FACED_STAYED)
                    count(Counter.FACED_STAYED_WON, round.won)
                }
            }
        }

        round.matchWon?.let { won ->
            add(Counter.MATCHES)
            if (won) {
                add(Counter.MATCHES_WON)
                add(Counter.STREAK)
                best(Counter.BEST_STREAK, v[Counter.STREAK.name] ?: 0)
            } else {
                v[Counter.STREAK.name] = 0
            }
        }
        return Stats(v)
    }

    /** Both statistics together; the current streak only makes sense per mode and is left out. */
    operator fun plus(other: Stats): Stats {
        val v = values.toMutableMap()
        other.values.forEach { (key, value) ->
            val best = BEST_VALUES.any { it.name == key }
            v[key] = if (best) maxOf(v[key] ?: 0, value) else (v[key] ?: 0) + value
        }
        v.remove(Counter.STREAK.name)
        return Stats(v)
    }

    companion object {
        fun schlagKey(rank: Rank) = "SCHLAG_${rank.name}"
        fun farbeKey(suit: Suit) = "FARBE_${suit.name}"
    }
}

/** A Schub of the round as seen by the player: who asked and whether the asked team gave up. */
data class SchubRecord(val byMe: Boolean, val byUs: Boolean, val went: Boolean)

/** One finished round from the perspective of the player. */
data class RoundRecord(
    val won: Boolean,
    val points: Int,
    val reason: RoundEndReason,
    val ownTricks: Int,
    val opponentTricks: Int,
    /** Announced by the player (null if someone else announced). */
    val schlag: Rank?,
    val farbe: Suit?,
    /** The cards the player had during the round (played ones included). */
    val hand: List<Card>,
    val trump: Trump?,
    val schubs: List<SchubRecord>,
    /** Whether the match was won, if this round ended it; null while it goes on. */
    val matchWon: Boolean?,
)

/**
 * Turns the stream of observations of one player into finished rounds. Works the same offline and
 * online: it only needs every observation in order (duplicates do no harm). A round is only counted
 * if it was seen while running, so a result shown again (e.g. after a reconnect) is not counted twice.
 */
class StatsTracker(private val onRound: (RoundRecord) -> Unit) {
    private var previous: Observation? = null
    private var inRound = false
    private val schubs = mutableListOf<SchubRecord>()

    /** Opened Schub that is not answered yet. */
    private var openSchub: Int? = null

    fun observe(obs: Observation) {
        val prev = previous
        previous = obs
        val over = obs.phase == Phase.ROUND_OVER || obs.phase == Phase.MATCH_OVER
        if (!over && (!inRound || obs.phase == Phase.SCHLAG || prev?.roundNumber != obs.roundNumber)) {
            schubs.clear()
            openSchub = null
        }
        if (!over) inRound = true
        if (!inRound) return
        trackSchub(obs)
        if (over) {
            inRound = false
            obs.result?.let { onRound(record(obs, it.winningTeam == obs.team, it.points, it.reason)) }
        }
    }

    /** Forgets the current round, e.g. after leaving an online room. */
    fun reset() {
        previous = null
        inRound = false
        schubs.clear()
        openSchub = null
    }

    private fun trackSchub(obs: Observation) {
        val pending = obs.pendingSchub
        val open = openSchub
        if (open == null && pending != null) {
            openSchub = pending.askingPlayer
        } else if (open != null && pending == null) {
            val went = obs.result?.reason == RoundEndReason.GIVEN_UP
            schubs += SchubRecord(byMe = open == obs.player, byUs = teamOf(open) == obs.team, went = went)
            openSchub = null
        }
    }

    private fun record(obs: Observation, won: Boolean, points: Int, reason: RoundEndReason): RoundRecord {
        val me = obs.player
        val played = obs.completedTricks.flatMap { it.plays } + obs.currentTrick
        val hand = played.filter { it.player == me }.map { it.card } + obs.hand
        return RoundRecord(
            won = won,
            points = points,
            reason = reason,
            ownTricks = obs.completedTricks.count { it.winner == me },
            opponentTricks = obs.tricksWon[1 - obs.team],
            schlag = obs.schlag.takeIf { me == nextPlayer(obs.dealer) },
            farbe = obs.farbe.takeIf { me == obs.dealer },
            hand = hand.distinct(),
            trump = obs.trump,
            schubs = schubs.toList(),
            matchWon = obs.matchWinner?.let { it == obs.team },
        )
    }
}

/** Persists the [Stats] of both modes in SharedPreferences. */
class StatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    fun load(mode: StatsMode): Stats {
        val prefix = prefix(mode)
        val values = prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(prefix) && value is Int) key.removePrefix(prefix) to value else null
        }.toMap()
        return Stats(values)
    }

    fun add(mode: StatsMode, round: RoundRecord) {
        val stats = load(mode).with(round)
        val prefix = prefix(mode)
        prefs.edit().apply { stats.values.forEach { (key, value) -> putInt(prefix + key, value) } }.apply()
    }

    fun reset() = prefs.edit().clear().apply()

    private fun prefix(mode: StatsMode) = "${mode.name}."
}
