package com.schaubeck.waddeln.rules

import kotlinx.serialization.Serializable

/**
 * What [player] is allowed to know about a [GameState]: their own hand, all played cards,
 * the number (not identity) of Kritten the Abheber took, the open bottom card of the stock and,
 * when gespannt meets gespannt, how many cards each player still has laid out.
 */
@Serializable
data class Observation(
    val player: Int,
    val phase: Phase,
    val scores: List<Int>,
    val dealer: Int,
    val roundNumber: Int,
    val hand: List<Card>,
    val handSizes: List<Int>,
    val abheberKritten: Int,
    val visibleBottomCard: Card,
    val schlag: Rank?,
    val farbe: Suit?,
    val trickLeader: Int,
    val currentTrick: List<Play>,
    val completedTricks: List<Trick>,
    val tricksWon: List<Int>,
    val gameValue: Int,
    val lastSchubTeam: Int?,
    val pendingSchub: PendingSchub?,
    /** Laid-out cards still on the table per player (null unless gespannt vs gespannt). */
    val laidOutCounts: List<Int>?,
    /** Which of the own cards are laid out (empty unless gespannt vs gespannt). */
    val ownLaidOut: Set<Card>,
    val playerToMove: Int?,
    val result: RoundResult?,
) {
    val team: Int get() = teamOf(player)
    val abheber: Int get() = abheberOf(dealer)
    val trump: Trump? get() = if (schlag != null && farbe != null) Trump(schlag, farbe) else null

    fun isGespannt(team: Int): Boolean = scores[team] >= GESPANNT_POINTS

    /** Winning team of the match, or null while it is still running. */
    val matchWinner: Int? get() = if (phase == Phase.MATCH_OVER) scores.indexOfFirst { it >= MATCH_POINTS } else null

    companion object {
        fun of(state: GameState, player: Int): Observation {
            require(player in 0 until PLAYER_COUNT)
            val remainingLaid = state.laidOut?.mapIndexed { p, laid -> laid.filter { it in state.hands[p] }.toSet() }
            return Observation(
                player = player,
                phase = state.phase,
                scores = state.scores,
                dealer = state.dealer,
                roundNumber = state.roundNumber,
                hand = state.hands[player],
                handSizes = state.hands.map { it.size },
                abheberKritten = state.abheberKritten,
                visibleBottomCard = state.visibleBottomCard,
                schlag = state.schlag,
                farbe = state.farbe,
                trickLeader = state.trickLeader,
                currentTrick = state.currentTrick,
                completedTricks = state.completedTricks,
                tricksWon = state.tricksWon,
                gameValue = state.gameValue,
                lastSchubTeam = state.lastSchubTeam,
                pendingSchub = state.pendingSchub,
                laidOutCounts = remainingLaid?.map { it.size },
                ownLaidOut = remainingLaid?.get(player).orEmpty(),
                playerToMove = state.playerToMove,
                result = state.result,
            )
        }
    }
}

fun GameState.observationFor(player: Int): Observation = Observation.of(this, player)
