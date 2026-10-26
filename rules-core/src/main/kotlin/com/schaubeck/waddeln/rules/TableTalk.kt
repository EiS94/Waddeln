package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.HintTrick
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import kotlinx.serialization.Serializable

/** A Schub as it was said: by [player], after [cardsPlayed] cards of the round. */
@Serializable
data class SchubCall(val player: Int, val cardsPlayed: Int)

/**
 * Partner talk during a Schub: [player] of the asked team told how good their cards are, and how
 * the trick being played is meant in it ([trick]; bots only). [schub] is the index of that Schub in
 * [GameState.schubs]. Everybody at the table hears it.
 */
@Serializable
data class HintCall(val player: Int, val hint: PartnerHint, val schub: Int, val trick: HintTrick = HintTrick.NONE)

/**
 * "Schadder?" asked by [asker] before leading the first trick (with the Haubi, or as a bluff without it);
 * [answer] by the partner once given. Heard by everybody.
 */
@Serializable
data class SchadetTalk(val asker: Int, val answer: SchadetAnswer? = null)

/**
 * Schlagwechsel proposed by the player after the dealer: the dealer would announce the Schlag and the
 * proposer the Farbe. [accepted] is the dealer's answer once given. Heard by everybody.
 */
@Serializable
data class SchlagwechselTalk(val accepted: Boolean? = null)
