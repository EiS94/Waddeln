package com.schaubeck.waddeln.rules.agent

import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.HAND_SIZE
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.Play
import com.schaubeck.waddeln.rules.TEAM_COUNT
import com.schaubeck.waddeln.rules.TRICKS_TO_WIN
import com.schaubeck.waddeln.rules.Trump

/**
 * Solves the rest of a game with all hands open: does [team] win it if everybody plays perfectly?
 * Schub is left out (the game value does not change who wins). Results of positions at the start of
 * a trick are cached, so one instance should be reused for the deals of one decision.
 */
class DoubleDummy(private val trump: Trump, private val team: Int) {
    private val strength = IntArray(CARDS) { trump.strength(Card.of(it)) }
    private val suit = IntArray(CARDS) { Card.of(it).suit.ordinal }
    private val rank = IntArray(CARDS) { Card.of(it).rank.ordinal }
    private val haubi = trump.haubi.index
    private val haubiFarbe = maskOf(Card.ALL.filter { trump.isHaubiFarbe(it) })
    private val kritten = maskOf(Card.KRITTEN)

    private val hands = IntArray(PLAYER_COUNT)
    /** Cards of each trick (deeper tricks must not overwrite the ones still being searched). */
    private val tricks = IntArray(HAND_SIZE * PLAYER_COUNT)
    private val won = IntArray(TEAM_COUNT)
    private val cache = HashMap<Position, Boolean>()

    /**
     * [hands] as they are now, [trick] the cards of the current trick (in playing order, led by [leader]),
     * [tricksWon] per team and [completedTricks] so far.
     */
    fun teamWins(hands: List<List<Card>>, leader: Int, trick: List<Play>, tricksWon: List<Int>, completedTricks: Int): Boolean {
        hands.forEachIndexed { p, h -> this.hands[p] = maskOf(h) }
        val at = completedTricks * PLAYER_COUNT
        trick.forEachIndexed { i, play -> tricks[at + i] = play.card.index }
        tricksWon.forEachIndexed { t, n -> won[t] = n }
        return search(leader, trick.size, completedTricks)
    }

    private fun search(leader: Int, played: Int, trickNumber: Int): Boolean {
        if (played == PLAYER_COUNT) return afterTrick(leader, trickNumber)
        val at = trickNumber * PLAYER_COUNT
        val player = (leader + played) % PLAYER_COUNT
        var moves = hands[player]
        if (bekennenPflicht(played, trickNumber)) {
            val farbe = moves and haubiFarbe
            if (farbe != 0) moves = farbe or (moves and kritten)
        }
        val ours = player % TEAM_COUNT == team
        while (moves != 0) {
            val card = Integer.numberOfTrailingZeros(moves)
            moves = moves and (moves - 1)
            hands[player] = hands[player] xor (1 shl card)
            tricks[at + played] = card
            val wins = search(leader, played + 1, trickNumber)
            hands[player] = hands[player] xor (1 shl card)
            // Our player takes the first winning card, an opponent the first losing one for us.
            if (wins == ours) return wins
        }
        return !ours
    }

    private fun afterTrick(leader: Int, trickNumber: Int): Boolean {
        val winner = (leader + winnerIndex(trickNumber * PLAYER_COUNT)) % PLAYER_COUNT
        val winnerTeam = winner % TEAM_COUNT
        won[winnerTeam]++
        val result = if (won[winnerTeam] >= TRICKS_TO_WIN) {
            winnerTeam == team
        } else {
            val position = position(winner)
            cache[position] ?: search(winner, 0, trickNumber + 1).also { cache[position] = it }
        }
        won[winnerTeam]--
        return result
    }

    /** As [com.schaubeck.waddeln.rules.trickWinnerIndex] for the four cards of the trick starting [at]. */
    private fun winnerIndex(at: Int): Int {
        var best = 0
        for (i in 1 until PLAYER_COUNT) if (strength[tricks[at + i]] > strength[tricks[at + best]]) best = i
        if (strength[tricks[at + best]] != Trump.NO_TRUMP) return best
        val lead = suit[tricks[at]]
        for (i in 1 until PLAYER_COUNT) if (suit[tricks[at + i]] == lead && rank[tricks[at + i]] > rank[tricks[at + best]]) best = i
        return best
    }

    /** As [com.schaubeck.waddeln.rules.Waddeln.isBekennenPflicht]. */
    private fun bekennenPflicht(played: Int, trickNumber: Int): Boolean {
        if (trickNumber != 0 || played == 0 || tricks[0] != haubi) return false
        for (i in 1 until played) if ((1 shl tricks[i]) and kritten != 0) return false
        return true
    }

    private fun position(leader: Int): Position {
        var owners = 0L
        for (p in 0 until PLAYER_COUNT) {
            var m = hands[p]
            while (m != 0) {
                val card = Integer.numberOfTrailingZeros(m)
                m = m and (m - 1)
                owners = owners or (p.toLong() shl (2 * card))
            }
        }
        val present = hands.fold(0) { acc, h -> acc or h }
        return Position(owners, present, leader + PLAYER_COUNT * (won[0] + PLAYER_COUNT * won[1]))
    }

    private data class Position(val owners: Long, val present: Int, val rest: Int)

    private companion object {
        const val CARDS = 32

        fun maskOf(cards: Iterable<Card>): Int = cards.fold(0) { acc, c -> acc or (1 shl c.index) }
    }
}
