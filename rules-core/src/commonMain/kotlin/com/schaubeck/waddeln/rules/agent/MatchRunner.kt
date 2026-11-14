package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Deal
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.teamOf
import kotlin.random.Random

/**
 * Plays complete matches between four agents without any UI (simulation, tests, training).
 *
 * Schub is offered to each agent as part of its own turn (alongside its cards). An open Schub is
 * answered by the player of the asked team who comes next in playing order. All agents talk at the
 * table like bots do ([BotTalk]).
 */
class MatchRunner(
    private val agents: List<Agent>,
    private val random: Random,
    /** Called after every applied action with the state before it. */
    private val onAction: (before: GameState, player: Int, action: Action) -> Unit = { _, _, _ -> },
    /** Called whenever a new round has been dealt. */
    private val onDeal: (deal: Deal, state: GameState) -> Unit = { _, _ -> },
) {
    init {
        require(agents.size == PLAYER_COUNT)
    }

    fun playMatch(): GameState {
        val firstDealer = random.nextInt(PLAYER_COUNT)
        var deal = Deal.random(random)
        var state = Waddeln.newMatch(firstDealer, deal)
        onDeal(deal, state)
        while (true) {
            when (state.phase) {
                Phase.MATCH_OVER -> return state
                Phase.ROUND_OVER -> {
                    deal = Deal.random(random)
                    state = Waddeln.startNextRound(state, deal)
                    onDeal(deal, state)
                }
                else -> state = step(state)
            }
        }
    }

    /** Lets the agent who has to act next choose and apply one action. */
    fun step(state: GameState): GameState {
        val swapped = BotTalk.schlagwechselBeforeTurn(state)
        val actor = nextActor(swapped)
        val (talked, action) = BotTalk.decide(swapped, actor, agents[actor], random)
        val next = Waddeln.apply(talked, actor, action)
        onAction(talked, actor, action)
        return next
    }

    private fun nextActor(state: GameState): Int {
        val toMove = checkNotNull(state.playerToMove) { "Nobody to move in phase ${state.phase}" }
        val pending = state.pendingSchub ?: return toMove
        var p = toMove
        while (teamOf(p) != pending.answeringTeam) p = nextPlayer(p)
        return p
    }
}
