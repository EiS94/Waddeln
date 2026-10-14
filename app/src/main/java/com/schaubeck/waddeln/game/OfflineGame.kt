package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.RoundResult
import com.schaubeck.waddeln.rules.Trick
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.PartnerHints
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.teamOf
import kotlin.random.Random

/** Something that happened in the game, shown to the human as a message. */
sealed interface GameEvent {
    data class Acted(val player: Int, val action: Action) : GameEvent
    data class TrickWon(val trick: Trick) : GameEvent
    data class RoundEnded(val result: RoundResult) : GameEvent
}

/**
 * Offline match: one human at [HUMAN] against/with three bots. Holds the authoritative state;
 * timing (bot delays) is left to the caller so this class stays plain and testable.
 */
class OfflineGame(
    private val random: Random = Random.Default,
    /** The bot players per seat; may be replaced, e.g. once the ML model has been loaded. */
    var bots: List<Agent> = List(PLAYER_COUNT) { RuleBasedAgent() },
) {
    var state: GameState = Waddeln.newMatch(random)
        private set

    private val _events = mutableListOf<GameEvent>()
    val events: List<GameEvent> get() = _events

    fun humanActions(): List<Action> = Waddeln.legalActions(state, HUMAN)

    fun applyHuman(action: Action) = apply(HUMAN, action)

    /**
     * The bot that has to act next, or null if the game waits for the human (or a new round).
     * An open Schub against the human's team is always answered by the human.
     */
    fun botToAct(): Int? {
        val toMove = state.playerToMove ?: return null
        val pending = state.pendingSchub
        val actor = if (pending == null) {
            toMove
        } else {
            if (teamOf(HUMAN) == pending.answeringTeam) return null
            generateSequence(toMove, ::nextPlayer).first { teamOf(it) == pending.answeringTeam }
        }
        return actor.takeIf { it != HUMAN }
    }

    /** Lets the next bot act and returns what it did. */
    fun stepBot(): Action {
        val bot = checkNotNull(botToAct()) { "No bot to act" }
        val action = bots[bot].chooseAction(state.observationFor(bot), Waddeln.legalActions(state, bot))
        apply(bot, action)
        return action
    }

    /**
     * The partner's answer when the human asks about their cards during a Schub against the
     * human's team; null if there is no such Schub.
     */
    fun partnerHint(): PartnerHint? {
        val pending = state.pendingSchub ?: return null
        if (pending.answeringTeam != teamOf(HUMAN)) return null
        return PartnerHints.hintFor(state.observationFor(PARTNER))
    }

    fun nextRound() {
        state = Waddeln.startNextRound(state, Deal.random(random))
        _events.clear()
    }

    fun newMatch() {
        state = Waddeln.newMatch(random)
        _events.clear()
    }

    private fun apply(player: Int, action: Action) {
        val before = state
        state = Waddeln.apply(state, player, action)
        _events += GameEvent.Acted(player, action)
        if (state.completedTricks.size > before.completedTricks.size) {
            _events += GameEvent.TrickWon(state.completedTricks.last())
        }
        state.result?.takeIf { before.result == null }?.let { _events += GameEvent.RoundEnded(it) }
    }

    companion object {
        const val HUMAN = 0
        const val PARTNER = 2
    }
}
