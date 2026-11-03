package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.DoubleDummy
import com.schaubeck.waddeln.rules.agent.HandSampler
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.PimcAgent
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.ml.DealInference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PimcTest {

    /** A random game after [cardsPlayed] random cards (no Schub), both teams at [scores]. */
    private fun randomPosition(random: Random, cardsPlayed: Int, scores: List<Int> = listOf(0, 0)): GameState {
        var s = Waddeln.newMatch(random).copy(scores = scores)
        s = Waddeln.apply(s, s.schlagAnnouncer, Action.AnnounceSchlag(Rank.entries.random(random)))
        s = Waddeln.apply(s, s.farbeAnnouncer, Action.AnnounceFarbe(Suit.entries.random(random)))
        repeat(cardsPlayed) {
            if (s.phase != Phase.PLAYING) return s
            val p = s.playerToMove!!
            s = Waddeln.apply(s, p, Action.PlayCard(Waddeln.playableCards(s, p).random(random)))
        }
        return s
    }

    /** Reference: plays every legal card with the engine. */
    private fun engineTeamWins(s: GameState, team: Int): Boolean {
        if (s.phase != Phase.PLAYING) return s.result!!.winningTeam == team
        val p = s.playerToMove!!
        val results = Waddeln.playableCards(s, p).map { engineTeamWins(Waddeln.apply(s, p, Action.PlayCard(it)), team) }
        return if (teamOf(p) == team) results.any { it } else results.all { it }
    }

    @Test
    fun `double dummy agrees with the engine`() {
        val random = Random(7)
        repeat(300) {
            val s = randomPosition(random, cardsPlayed = random.nextInt(4, 19))
            if (s.phase != Phase.PLAYING) return@repeat
            val team = random.nextInt(TEAM_COUNT)
            val solved = DoubleDummy(s.trump!!, team).teamWins(s.hands, s.trickLeader, s.currentTrick, s.tricksWon, s.completedTricks.size)
            assertEquals(engineTeamWins(s, team), solved, "position $it")
        }
    }

    @Test
    fun `double dummy knows the Bekennen-Pflicht`() {
        // Gras Ass is Haubi and led: player 1 must follow with Gras 9 although Schell Ass would take the trick
        val hands = listOf("GA S9 E8 H8 E9", "G9 SA EK E10 H9", "HA S8 SU HO GO", "S10 EU EO G7 H10")
        val s = playingWith(hands, Rank.ASS, Suit.GRAS, dealer = 3).play("GA")
        assertTrue(Waddeln.isBekennenPflicht(s))
        val team = 1
        assertEquals(engineTeamWins(s, team), DoubleDummy(s.trump!!, team).teamWins(s.hands, s.trickLeader, s.currentTrick, s.tricksWon, 0))
    }

    @Test
    fun `sampled deals fit everything the player knows`() {
        val random = Random(11)
        repeat(400) { i ->
            val gespannt = i % 4 == 0
            val s = randomPosition(random, random.nextInt(0, 18), if (gespannt) listOf(9, 9) else listOf(0, 0))
            if (s.phase != Phase.PLAYING) return@repeat
            val me = s.playerToMove!!
            val obs = s.observationFor(me)
            val hands = assertNotNull(HandSampler.sample(obs, random), "position $i")
            assertEquals(obs.hand, hands[me])
            assertEquals(obs.handSizes, hands.map { it.size })
            val all = hands.flatten()
            assertEquals(all.size, all.toSet().size)
            val seen = obs.completedTricks.flatMap { t -> t.plays.map { it.card } } + obs.currentTrick.map { it.card } + obs.visibleBottomCard
            assertTrue(all.none { it in seen })
            val trump = s.trump!!
            obs.laidOutCounts?.forEachIndexed { p, n -> assertEquals(n, hands[p].count { it.isKritte || it == trump.haubi }) }
            if (obs.laidOutCounts == null) {
                val played = (obs.completedTricks.flatMap { it.plays } + obs.currentTrick)
                fun kritten(p: Int) = hands[p].count { it.isKritte } + played.count { it.player == p && it.card.isKritte }
                assertTrue(kritten(obs.abheber) >= obs.abheberKritten)
                assertTrue(kritten(obs.dealer) >= obs.geberKritten)
            }
        }
    }

    @Test
    fun `players who could not follow the Haubi-Farbe get none`() {
        val hands = listOf("GA S9 E8 H8 E9", "SA EK E10 H9 HU", "HA S8 SU HO GO", "S10 EU EO G7 H10")
        val s = playingWith(hands, Rank.ASS, Suit.GRAS, dealer = 3).play("GA", "SA")
        val trump = s.trump!!
        val random = Random(3)
        repeat(100) {
            val dealt = assertNotNull(HandSampler.sample(s.observationFor(2), random))
            assertTrue(dealt[1].none { trump.isHaubiFarbe(it) })
        }
    }

    @Test
    fun `deal inference rates every deal`() {
        val model = javaClass.getResourceAsStream("/ml/test_model.onnx")!!.readBytes()
        OrtQFunction(model).use { q ->
            val random = Random(13)
            repeat(30) { i ->
                val s = randomPosition(random, random.nextInt(0, 18), if (i % 3 == 0) listOf(9, 9) else listOf(0, 0))
                if (s.phase != Phase.PLAYING) return@repeat
                val obs = s.observationFor(s.playerToMove!!)
                val deals = List(10) { HandSampler.sample(obs, random)!! } + listOf(s.hands)
                val weights = DealInference(q).weights(obs, deals)
                assertEquals(deals.size, weights.size)
                assertTrue(weights.all { it in 0.0..1.0 } && weights.max() == 1.0)
            }
        }
    }

    @Test
    fun `PimcAgent plays legal matches`() {
        repeat(5) { i ->
            val agents = List(PLAYER_COUNT) { if (it % 2 == 0) PimcAgent(RuleBasedAgent(), samples = 20, random = Random(i)) else RuleBasedAgent() }
            MatchRunner(agents, Random(i), onAction = { s, p, a -> assertTrue(Waddeln.isLegal(s, p, a)) }).playMatch()
        }
    }
}
