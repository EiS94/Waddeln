package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import kotlin.random.Random

/** The rules engine: pure functions from state + action to new state. */
object Waddeln {

    /** Starts a match: the first dealer is chosen at random. */
    fun newMatch(random: Random): GameState =
        newMatch(firstDealer = random.nextInt(PLAYER_COUNT), deal = Deal.random(random))

    fun newMatch(firstDealer: Int, deal: Deal): GameState {
        require(firstDealer in 0 until PLAYER_COUNT)
        return startRound(scores = listOf(0, 0), dealer = firstDealer, roundNumber = 1, deal = deal)
    }

    /** Starts the next round after [Phase.ROUND_OVER]; the dealer moves on clockwise. */
    fun startNextRound(state: GameState, deal: Deal): GameState {
        require(state.phase == Phase.ROUND_OVER) { "Round is not over (phase ${state.phase})" }
        return startRound(state.scores, nextPlayer(state.dealer), state.roundNumber + 1, deal)
    }

    /** Like [newMatch], but the Abheber cuts later: [PendingRound.deal] starts the first round. */
    fun prepareMatch(random: Random): PendingRound =
        PendingRound(listOf(0, 0), dealer = random.nextInt(PLAYER_COUNT), roundNumber = 1, deck = Card.ALL.shuffled(random))

    /** Like [startNextRound], but the Abheber cuts later: [PendingRound.deal] starts the round. */
    fun prepareNextRound(state: GameState, random: Random): PendingRound {
        require(state.phase == Phase.ROUND_OVER) { "Round is not over (phase ${state.phase})" }
        return PendingRound(state.scores, nextPlayer(state.dealer), state.roundNumber + 1, Card.ALL.shuffled(random))
    }

    internal fun startRound(scores: List<Int>, dealer: Int, roundNumber: Int, deal: Deal): GameState {
        val dealt = dealCards(dealer, deal)
        return GameState(
            scores = scores,
            dealer = dealer,
            roundNumber = roundNumber,
            phase = Phase.SCHLAG,
            hands = dealt.hands,
            stock = dealt.stock,
            abheberKritten = dealt.abheberKritten,
            trickLeader = nextPlayer(dealer),
        )
    }

    /** All actions [player] may take right now (possibly empty). */
    fun legalActions(state: GameState, player: Int): List<Action> {
        require(player in 0 until PLAYER_COUNT)
        return when (state.phase) {
            Phase.SCHLAG ->
                if (player == state.schlagAnnouncer && schlagwechselAnswerer(state) == null) {
                    Rank.entries.map { Action.AnnounceSchlag(it) }
                } else {
                    emptyList()
                }

            Phase.FARBE ->
                if (player == state.farbeAnnouncer) Suit.entries.map { Action.AnnounceFarbe(it) } else emptyList()

            Phase.PLAYING -> {
                val pending = state.pendingSchub
                if (pending != null) {
                    if (teamOf(player) == pending.answeringTeam) listOf(Action.Bleiben, Action.Gehen) else emptyList()
                } else {
                    val actions = mutableListOf<Action>()
                    if (player == state.playerToMove) playableCards(state, player).mapTo(actions) { Action.PlayCard(it) }
                    if (canAnnounceSchub(state, player)) actions += Action.Schub
                    actions
                }
            }

            Phase.ROUND_OVER, Phase.MATCH_OVER -> emptyList()
        }
    }

    fun isLegal(state: GameState, player: Int, action: Action): Boolean =
        action in legalActions(state, player)

    /**
     * Cards [player] may play when it is their turn. Free choice, except under [isBekennenPflicht]:
     * then a player holding cards of the Haubi-Farbe must play one of them or a Kritte.
     */
    fun playableCards(state: GameState, player: Int): List<Card> {
        val hand = state.hands[player]
        val trump = state.trump ?: return hand
        if (!isBekennenPflicht(state)) return hand
        if (hand.none { trump.isHaubiFarbe(it) }) return hand
        return hand.filter { trump.isHaubiFarbe(it) || it.isKritte }
    }

    /**
     * The Haubi was led as the very first card of the very first trick and no following player
     * has played a Kritte yet (a Kritte lifts the obligation for the rest of the trick).
     */
    fun isBekennenPflicht(state: GameState): Boolean {
        val trump = state.trump ?: return false
        val trick = state.currentTrick
        return state.completedTricks.isEmpty() &&
            trick.isNotEmpty() &&
            trick.first().card == trump.haubi &&
            trick.drop(1).none { it.card.isKritte }
    }

    /**
     * A team may announce Schub unless it is gespannt, it announced the previous Schub, or winning
     * the game at its current value would already give it [MATCH_POINTS].
     */
    fun canAnnounceSchub(state: GameState, player: Int): Boolean {
        val team = teamOf(player)
        return state.phase == Phase.PLAYING &&
            state.pendingSchub == null &&
            !state.isGespannt(team) &&
            state.lastSchubTeam != team &&
            state.scores[team] + state.gameValue < MATCH_POINTS
    }

    /** Applies [action] by [player]. Throws [IllegalActionException] if it is not legal. */
    fun apply(state: GameState, player: Int, action: Action): GameState {
        if (!isLegal(state, player, action)) {
            throw IllegalActionException("Player $player may not do $action in phase ${state.phase}")
        }
        return when (action) {
            is Action.AnnounceSchlag -> state.copy(schlag = action.rank, phase = Phase.FARBE)
            is Action.AnnounceFarbe -> fixHaubi(state, action.suit)
            is Action.PlayCard -> playCard(state, player, action.card)
            Action.Schub -> state.copy(
                pendingSchub = PendingSchub(player),
                lastSchubTeam = teamOf(player),
                schubs = state.schubs + SchubCall(player, state.cardsPlayed),
            )
            Action.Bleiben -> state.copy(pendingSchub = null, gameValue = state.gameValue + 1)
            Action.Gehen -> {
                val pending = state.pendingSchub!!
                endRound(state, RoundResult(pending.askingTeam, state.gameValue, RoundEndReason.GIVEN_UP))
            }
        }
    }

    // ---- Table talk: not actions of the game, but heard by everybody and part of the state ----

    /**
     * During a Schub, [player] of the asked team may tell (once per Schub, on the partner's
     * question) how good their cards are.
     */
    fun canGiveHint(state: GameState, player: Int): Boolean {
        val pending = state.pendingSchub ?: return false
        return teamOf(player) == pending.answeringTeam && state.hints.none { it.schub == state.schubs.lastIndex }
    }

    fun giveHint(state: GameState, player: Int, hint: PartnerHint): GameState {
        if (!canGiveHint(state, player)) throw IllegalActionException("Player $player may not give a hint now")
        return state.copy(hints = state.hints + HintCall(player, hint, state.schubs.lastIndex))
    }

    /** True while [player] is about to lead the first trick holding the Haubi (the moment for "Schadder?"). */
    fun leadsFirstTrickWithHaubi(state: GameState, player: Int): Boolean {
        val trump = state.trump ?: return false
        return leadsFirstTrick(state, player) && trump.haubi in state.hands[player]
    }

    /** True while [player] is about to lead the first trick (the moment for "Schadder?"). */
    fun leadsFirstTrick(state: GameState, player: Int): Boolean =
        state.phase == Phase.PLAYING && state.pendingSchub == null &&
            state.completedTricks.isEmpty() && state.currentTrick.isEmpty() && state.playerToMove == player

    /**
     * "Schadder?" may be asked once per round by the player leading the first trick: with the Haubi,
     * or as a bluff without it.
     */
    fun canAskSchadet(state: GameState, player: Int): Boolean =
        state.schadet == null && leadsFirstTrick(state, player)

    fun askSchadet(state: GameState, player: Int): GameState {
        if (!canAskSchadet(state, player)) throw IllegalActionException("Player $player may not ask Schadder now")
        return state.copy(schadet = SchadetTalk(player))
    }

    /** The partner who still has to answer an open "Schadder?" (none once it became moot by a lead). */
    fun schadetAnswerer(state: GameState): Int? = state.schadet
        ?.takeIf { it.answer == null && leadsFirstTrick(state, it.asker) }
        ?.let { partnerOf(it.asker) }

    fun answerSchadet(state: GameState, player: Int, answer: SchadetAnswer): GameState {
        if (schadetAnswerer(state) != player) throw IllegalActionException("Player $player may not answer Schadder now")
        return state.copy(schadet = state.schadet!!.copy(answer = answer))
    }

    /** Before announcing the Schlag, the player after the dealer may propose a Schlagwechsel once per round. */
    fun canProposeSchlagwechsel(state: GameState, player: Int): Boolean =
        state.phase == Phase.SCHLAG && state.schlagwechsel == null && player == state.forehand

    fun proposeSchlagwechsel(state: GameState, player: Int): GameState {
        if (!canProposeSchlagwechsel(state, player)) {
            throw IllegalActionException("Player $player may not propose a Schlagwechsel now")
        }
        return state.copy(schlagwechsel = SchlagwechselTalk())
    }

    /** The dealer while a proposed Schlagwechsel waits for the answer; nobody announces meanwhile. */
    fun schlagwechselAnswerer(state: GameState): Int? =
        state.dealer.takeIf { state.phase == Phase.SCHLAG && state.schlagwechsel?.accepted == null && state.schlagwechsel != null }

    /** On [accept] the dealer announces the Schlag and the proposer the Farbe; otherwise nothing changes. */
    fun answerSchlagwechsel(state: GameState, player: Int, accept: Boolean): GameState {
        if (schlagwechselAnswerer(state) != player) {
            throw IllegalActionException("Player $player may not answer the Schlagwechsel now")
        }
        return state.copy(schlagwechsel = SchlagwechselTalk(accept))
    }

    /**
     * The last completed trick may be looked at until the next one is complete. To keep it simple
     * only the player who is to play a card may show it (online it is shown to the whole table).
     */
    fun canShowLastTrick(state: GameState, player: Int): Boolean =
        state.phase == Phase.PLAYING && state.pendingSchub == null &&
            state.completedTricks.isNotEmpty() && state.playerToMove == player

    private fun fixHaubi(state: GameState, farbe: Suit): GameState {
        val withTrump = state.copy(farbe = farbe, phase = Phase.PLAYING)
        val bothGespannt = (0 until TEAM_COUNT).all { state.isGespannt(it) }
        if (!bothGespannt) return withTrump
        val trump = withTrump.trump!!
        val laid = state.hands.map { hand -> hand.filter { it.isKritte || it == trump.haubi }.toSet() }
        return withTrump.copy(laidOut = laid)
    }

    private fun playCard(state: GameState, player: Int, card: Card): GameState {
        val hands = state.hands.mapIndexed { p, hand -> if (p == player) hand - card else hand }
        val trick = state.currentTrick + Play(player, card)
        if (trick.size < PLAYER_COUNT) return state.copy(hands = hands, currentTrick = trick)

        val winner = trick[trickWinnerIndex(trick.map { it.card }, state.trump!!)].player
        val next = state.copy(
            hands = hands,
            currentTrick = emptyList(),
            completedTricks = state.completedTricks + Trick(trick, winner),
            trickLeader = winner,
        )
        val team = teamOf(winner)
        return if (next.tricksWon[team] >= TRICKS_TO_WIN) {
            endRound(next, RoundResult(team, next.gameValue, RoundEndReason.TRICKS))
        } else {
            next
        }
    }

    private fun endRound(state: GameState, result: RoundResult): GameState {
        val scores = state.scores.mapIndexed { team, s -> if (team == result.winningTeam) s + result.points else s }
        val matchOver = scores.any { it >= MATCH_POINTS }
        return state.copy(
            scores = scores,
            phase = if (matchOver) Phase.MATCH_OVER else Phase.ROUND_OVER,
            pendingSchub = null,
            result = result,
        )
    }

    /** Winning team of the match, or null while it is still running. */
    fun matchWinner(state: GameState): Int? =
        if (state.phase == Phase.MATCH_OVER) state.scores.indexOfFirst { it >= MATCH_POINTS } else null
}
