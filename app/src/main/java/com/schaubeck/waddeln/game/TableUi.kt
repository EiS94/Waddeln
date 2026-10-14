package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT

/**
 * A Schub that others answered with "Bleiben" while the player only watched.
 * Shown so it is not missed.
 */
data class SchubNotice(val askingPlayer: Int, val answeringPlayer: Int, val newGameValue: Int)

/**
 * Everything the table screen shows, from the perspective of [Observation.player]
 * (drawn at the bottom). Built by the offline game and by the online client alike.
 */
data class TableUi(
    val obs: Observation,
    val actions: List<Action>,
    /** Display name per seat. */
    val names: List<String>,
    val speed: GameSpeed,
    val notice: SchubNotice? = null,
    /** Seats whose human lost the connection; a bot plays for them meanwhile. */
    val absent: Set<Int> = emptySet(),
    /** Online: seats played by a bot (marked, since bots have names like humans). */
    val bots: Set<Int> = emptySet(),
    /** Online: this player confirmed the end of the round and waits for the others. */
    val waitingForOthers: Boolean = false,
    /** Online: shown while the connection to the server is lost. */
    val connectionProblem: String? = null,
    /** Online: when (SystemClock.elapsedRealtime) a bot decides for us if we don't act; null = no limit. */
    val deadline: Long? = null,
) {
    val me: Int get() = obs.player

    /** Seat at a position relative to the own one: 1 = left, 2 = partner (top), 3 = right. */
    fun seatAt(relative: Int): Int = (me + relative) % PLAYER_COUNT

    /** Position of [seat] relative to the own one (0 = bottom, 1 = left, 2 = top, 3 = right). */
    fun relative(seat: Int): Int = (seat - me + PLAYER_COUNT) % PLAYER_COUNT
}
