package com.schaubeck.waddeln.game

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT

/**
 * A Schub that others answered with "Bleiben" while the player only watched.
 * Shown so it is not missed.
 */
data class SchubNotice(val askingPlayer: Int, val answeringPlayer: Int, val newGameValue: Int)

/**
 * Something [seat] said aloud, shown as a speech bubble for [GameSpeed.speechMs] from [at]
 * (SystemClock.elapsedRealtime). [at] may lie a little in the future: an answer given right away
 * is heard and shown only after the question.
 */
data class Speech(val seat: Int, val text: String, val at: Long)

/**
 * The Abheber cuts before the deal; shown instead of the table until the cut has been seen
 * (timing like [com.schaubeck.waddeln.protocol.CutAnimation]).
 */
data class CutUi(
    val abheber: Int,
    /** We are the Abheber and choose where to cut. */
    val choosing: Boolean = false,
    /** Cards lifted; null while the Abheber still chooses. */
    val cut: Int? = null,
    /** Number of Kritten the Abheber took. */
    val krittenCount: Int = 0,
    /** Which Kritten, if we may see them (we are the Abheber); otherwise empty. */
    val kritten: List<Card> = emptyList(),
    /** The open bottom card of the lifted stack, once cut. */
    val bottomCard: Card? = null,
)

/** How long after a question its immediate answer is said. */
const val ANSWER_DELAY_MS = 1300L

/** True for actions that are said aloud at the table (everything but playing a card). */
fun Action.isSpoken(): Boolean = this !is Action.PlayCard

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
    /** Recent speech bubbles, oldest first; the UI shows the latest per seat while it is fresh. */
    val speech: List<Speech> = emptyList(),
    /** During a Schub against us: the partner can be asked how good their cards are. */
    val canAskPartner: Boolean = false,
    /** The partner's answer to that question, shown until the Schub is answered. */
    val partnerHint: String? = null,
    /** Online: we asked our human partner and wait for the answer. */
    val waitingForPartner: Boolean = false,
    /** Online: our partner asked us how good our cards are; we answer with a [com.schaubeck.waddeln.rules.agent.PartnerHint]. */
    val partnerAsksUs: Boolean = false,
    /** We lead the first trick and may ask the partner "Schadder?" (without the Haubi it is a bluff). */
    val canAskSchadet: Boolean = false,
    /** We asked "Schadder?" and wait for the partner's answer. */
    val waitingForSchadet: Boolean = false,
    /** The partner's answer to our "Schadder?", shown until we lead. */
    val schadetAnswer: String? = null,
    /** The partner asked us "Schadder?"; we answer with a [com.schaubeck.waddeln.rules.agent.SchadetAnswer]. */
    val partnerAsksSchadet: Boolean = false,
    /** We announce the Schlag and may propose a Schlagwechsel first. */
    val canProposeSchlagwechsel: Boolean = false,
    /** A Schlagwechsel waits for the dealer's answer (we proposed it or just watch). */
    val waitingForSchlagwechsel: Boolean = false,
    /** We are the dealer and answer the Schlagwechsel proposed to us. */
    val schlagwechselAsksUs: Boolean = false,
    /** We are to play a card and may show the last completed trick. */
    val canShowLastTrick: Boolean = false,
    /** Seat that shows the last completed trick right now (online to the whole table), or null. */
    val lastTrickShownBy: Int? = null,
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
    /** The cut before the deal, while it is shown (then the table is hidden). */
    val cut: CutUi? = null,
) {
    val me: Int get() = obs.player

    /** Seat at a position relative to the own one: 1 = left, 2 = partner (top), 3 = right. */
    fun seatAt(relative: Int): Int = (me + relative) % PLAYER_COUNT

    /** Position of [seat] relative to the own one (0 = bottom, 1 = left, 2 = top, 3 = right). */
    fun relative(seat: Int): Int = (seat - me + PLAYER_COUNT) % PLAYER_COUNT
}
