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
 */
object BotTalk {

    /** The partner's hint, if [actor] is about to answer a Schub and nobody of the team asked yet. */
    fun hintBeforeAnswer(state: GameState, actor: Int): GameState {
        val partner = partnerOf(actor)
        if (!Waddeln.canGiveHint(state, partner)) return state
        return Waddeln.giveHint(state, partner, PartnerHints.hintFor(state.observationFor(partner)))
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
