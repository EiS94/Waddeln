package com.schaubeck.waddeln.rules

import kotlinx.serialization.Serializable

/** Players sit in seats 0..3 clockwise; opposite players (same parity) form a team. */
const val PLAYER_COUNT = 4
const val TEAM_COUNT = 2
const val HAND_SIZE = 5
const val TRICKS_TO_WIN = 3
const val BASE_GAME_VALUE = 2
const val MATCH_POINTS = 11
const val GESPANNT_POINTS = 9

fun teamOf(player: Int): Int = player % TEAM_COUNT
fun nextPlayer(player: Int): Int = (player + 1) % PLAYER_COUNT
fun partnerOf(player: Int): Int = (player + 2) % PLAYER_COUNT

/** The Abheber sits before the dealer (counter-clockwise). */
fun abheberOf(dealer: Int): Int = (dealer + PLAYER_COUNT - 1) % PLAYER_COUNT

fun playersOf(team: Int): List<Int> = (0 until PLAYER_COUNT).filter { teamOf(it) == team }

enum class Phase {
    /** Player after the dealer announces the Schlag (the dealer after an accepted Schlagwechsel). */
    SCHLAG,

    /** Dealer announces the Farbe (the player after the dealer after an accepted Schlagwechsel). */
    FARBE,

    /** Cards are played; Schub may be announced at any time. */
    PLAYING,

    /** The game (round) is decided; start the next one with [Waddeln.startNextRound]. */
    ROUND_OVER,

    /** A team reached [MATCH_POINTS]. */
    MATCH_OVER,
}

@Serializable
data class Play(val player: Int, val card: Card)

@Serializable
data class Trick(val plays: List<Play>, val winner: Int)

enum class RoundEndReason { TRICKS, GIVEN_UP }

@Serializable
data class RoundResult(val winningTeam: Int, val points: Int, val reason: RoundEndReason)

/** An open Schub: [askingPlayer]'s team waits for an answer from the other team. */
@Serializable
data class PendingSchub(val askingPlayer: Int) {
    val askingTeam: Int get() = teamOf(askingPlayer)
    val answeringTeam: Int get() = 1 - askingTeam
}

/**
 * Complete, authoritative state of a match including the current round.
 * Immutable; every action produces a new instance via [Waddeln.apply].
 * Players must only ever see an [Observation] of it.
 */
data class GameState(
    /** Match points per team. Updated when a round ends. */
    val scores: List<Int>,
    val dealer: Int,
    /** 1-based number of the round within the match. */
    val roundNumber: Int,
    val phase: Phase,
    val hands: List<List<Card>>,
    /** The 12 undealt cards; the last one is openly visible. */
    val stock: List<Card>,
    val abheberKritten: Int,
    /** Kritten the dealer took from the bottom of the rest (only after the Abheber took some). */
    val geberKritten: Int = 0,
    /** The card the dealer saw under the rest; only he may know it. Null if he did not look. */
    val geberCard: Card? = null,
    val schlag: Rank? = null,
    val farbe: Suit? = null,
    /** Cards laid face down per player when gespannt meets gespannt (null otherwise). */
    val laidOut: List<Set<Card>>? = null,
    val trickLeader: Int,
    val currentTrick: List<Play> = emptyList(),
    val completedTricks: List<Trick> = emptyList(),
    val gameValue: Int = BASE_GAME_VALUE,
    /** Team that announced the last Schub in this round; it may not announce the next one. */
    val lastSchubTeam: Int? = null,
    val pendingSchub: PendingSchub? = null,
    /** Every Schub of this round, in order. */
    val schubs: List<SchubCall> = emptyList(),
    /** Partner talk during the Schubs of this round (at most one per Schub). */
    val hints: List<HintCall> = emptyList(),
    /** "Schadder?" of this round, if somebody asked. */
    val schadet: SchadetTalk? = null,
    /** Schlagwechsel of this round, if the forehand proposed one. */
    val schlagwechsel: SchlagwechselTalk? = null,
    val result: RoundResult? = null,
) {
    val abheber: Int get() = abheberOf(dealer)

    /** Player after the dealer: opens the first trick and announces the Schlag (unless swapped). */
    val forehand: Int get() = nextPlayer(dealer)

    private val swapped: Boolean get() = schlagwechsel?.accepted == true

    /** Announces the Schlag: the forehand, or the dealer after an accepted Schlagwechsel. */
    val schlagAnnouncer: Int get() = if (swapped) dealer else forehand

    /** Announces the Farbe: the dealer, or the forehand after an accepted Schlagwechsel. */
    val farbeAnnouncer: Int get() = if (swapped) forehand else dealer

    val visibleBottomCard: Card get() = stock.last()

    val trump: Trump? get() = if (schlag != null && farbe != null) Trump(schlag, farbe) else null

    fun isGespannt(team: Int): Boolean = scores[team] >= GESPANNT_POINTS

    /** Cards played in this round so far. */
    val cardsPlayed: Int get() = completedTricks.size * PLAYER_COUNT + currentTrick.size

    val tricksWon: List<Int>
        get() = List(TEAM_COUNT) { team -> completedTricks.count { teamOf(it.winner) == team } }

    /**
     * The player whose turn it is in the normal flow (announcing or playing a card),
     * or null when the round is over. While a Schub is pending, card play is suspended
     * and any player of [PendingSchub.answeringTeam] may answer. While a proposed Schlagwechsel
     * waits for the dealer's answer, nobody can announce (see [Waddeln.schlagwechselAnswerer]).
     */
    val playerToMove: Int?
        get() = when (phase) {
            Phase.SCHLAG -> schlagAnnouncer
            Phase.FARBE -> farbeAnnouncer
            Phase.PLAYING -> (trickLeader + currentTrick.size) % PLAYER_COUNT
            Phase.ROUND_OVER, Phase.MATCH_OVER -> null
        }
}
