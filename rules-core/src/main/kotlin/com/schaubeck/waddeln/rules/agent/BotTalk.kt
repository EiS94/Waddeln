package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.GameState
import com.schaubeck.waddeln.rules.Waddeln
import com.schaubeck.waddeln.rules.observationFor
import com.schaubeck.waddeln.rules.partnerOf

/**
 * How bots talk at the table – the same in the app, on the server, in simulations and in training
 * (training/waddeln/talk.py mirrors it).
 *
 * - Before a bot answers a Schub it always asks its partner, who answers with [PartnerHints].
 * - Before leading the Haubi into the first trick it asks "Schadder?", the partner answers with
 *   [Schadet.answerFor], and the bot decides again knowing the answer (see [Schadet.allowedActions]).
 * - Before announcing the Schlag the player after the dealer may propose a Schlagwechsel
 *   ([Schlagwechsel.proposes]); the dealer answers with [Schlagwechsel.accepts]. This is talked
 *   about before the actor is chosen ([schlagwechselBeforeTurn]), since it changes who announces.
 */
object BotTalk {

    /** The partner's hint, if [actor] is about to answer a Schub and nobody of the team asked yet. */
    fun hintBeforeAnswer(state: GameState, actor: Int): GameState {
        val partner = partnerOf(actor)
        if (!Waddeln.canGiveHint(state, partner)) return state
        return Waddeln.giveHint(state, partner, PartnerHints.hintFor(state.observationFor(partner)))
    }

    /**
     * At the start of a round: the player after the dealer proposes a Schlagwechsel if he wants one
     * and the dealer answers at once. Unchanged if it was talked about already or is not wanted.
     */
    fun schlagwechselBeforeTurn(state: GameState): GameState {
        val proposer = state.forehand
        if (!Waddeln.canProposeSchlagwechsel(state, proposer) || !Schlagwechsel.proposes(state.observationFor(proposer))) {
            return state
        }
        val proposed = Waddeln.proposeSchlagwechsel(state, proposer)
        return Waddeln.answerSchlagwechsel(proposed, state.dealer, Schlagwechsel.accepts(proposed.observationFor(state.dealer)))
    }

    /** True if [actor] wants to play [action] and should ask "Schadder?" first. */
    fun asksSchadet(state: GameState, actor: Int, action: Action): Boolean =
        Schadet.leadsHaubi(state, actor, action) && Waddeln.canAskSchadet(state, actor)

    /** [actor] asks "Schadder?" and the partner answers with [Schadet.answerFor]. */
    fun askAndAnswerSchadet(state: GameState, actor: Int): GameState {
        val asked = Waddeln.askSchadet(state, actor)
        val partner = partnerOf(actor)
        return Waddeln.answerSchadet(asked, partner, Schadet.answerFor(asked.observationFor(partner)))
    }

    /**
     * Lets [agent] choose for [actor] including the table talk around the decision.
     * Returns the state after the talk (the action is not applied yet) and the chosen action.
     */
    fun decide(state: GameState, actor: Int, agent: Agent): Pair<GameState, Action> {
        var s = hintBeforeAnswer(state, actor)
        val legal = Waddeln.legalActions(s, actor)
        var action = agent.chooseAction(s.observationFor(actor), Schadet.allowedActions(s, actor, legal))
        if (asksSchadet(s, actor, action)) {
            s = askAndAnswerSchadet(s, actor)
            action = agent.chooseAction(s.observationFor(actor), Schadet.allowedActions(s, actor, legal))
        }
        return s to action
    }
}
