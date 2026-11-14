package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.agent.SchubManners
import com.schaubeck.waddeln.rules.ml.MlAgent
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * How often the bots say Schub, per game value it asks for, and how often it is answered with Bleiben.
 * Only runs with the environment variable SCHUB_STATS = number of matches.
 */
class SchubStats {
    private class Counts {
        var rounds = 0
        val said = IntArray(MAX_VALUE + 1)
        val stayed = IntArray(MAX_VALUE + 1)
        val finalValue = IntArray(MAX_VALUE + 1)

        /** Schub to 3 and Schub higher ([HIGHER]), per range of the win chance when it was said. */
        val byChance = Array(2) { IntArray(CHANCE_LIMITS.size + 1) }

        /** Schub to 3 per trick of the round in which it was said. */
        val byTrick = IntArray(TRICKS_IN_ROUND)

        fun add(other: Counts) {
            rounds += other.rounds
            for (i in said.indices) {
                said[i] += other.said[i]
                stayed[i] += other.stayed[i]
                finalValue[i] += other.finalValue[i]
            }
            for (k in byChance.indices) for (i in byChance[k].indices) byChance[k][i] += other.byChance[k][i]
            for (i in byTrick.indices) byTrick[i] += other.byTrick[i]
        }
    }

    private fun measure(label: String, matches: Int, bot: (Int) -> Agent) {
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val jobs = (0 until matches).map { match ->
            pool.submit<Counts> {
                val counts = Counts()
                val statsRandom = Random(match.toLong() + 77)
                val agents = List(PLAYER_COUNT) { bot(match * 4 + it) }
                MatchRunner(agents, Random(match.toLong()), onAction = { s, p, a ->
                    val target = (s.gameValue + 1).coerceAtMost(MAX_VALUE)
                    if (a == Action.Schub) {
                        counts.said[target]++
                        val chance = SchubManners.winChance(s.observationFor(p), statsRandom)
                        val bucket = CHANCE_LIMITS.indexOfFirst { chance < it }.let { if (it < 0) CHANCE_LIMITS.size else it }
                        counts.byChance[if (target == 3) 0 else HIGHER][bucket]++
                        if (target == 3) counts.byTrick[s.completedTricks.size]++
                    }
                    if (a == Action.Bleiben) counts.stayed[target]++
                    val after = Waddeln.apply(s, p, a)
                    if (after.result != null && s.result == null) {
                        counts.rounds++
                        counts.finalValue[after.gameValue.coerceAtMost(MAX_VALUE)]++
                    }
                }).playMatch()
                counts
            }
        }
        val total = Counts()
        jobs.forEach { total.add(it.get()) }
        pool.shutdown()
        println("$label: ${total.rounds} rounds, ${"%.2f".format(total.said.sum().toDouble() / total.rounds)} Schub per round")
        for (k in total.byChance.indices) {
            val n = total.byChance[k].sum().coerceAtLeast(1)
            println("  ${if (k == 0) "Schub to 3" else "Schub higher"} by win chance (<.3 <.5 <.7 <.9 rest): " + total.byChance[k].joinToString(" ") { "%.0f%%".format(100.0 * it / n) } +
                "  = bluffs (<.5) in ${"%.1f".format(100.0 * (total.byChance[k][0] + total.byChance[k][1]) / total.rounds)} % of rounds")
        }
        println("  Schub to 3 per trick: " + total.byTrick.joinToString(" ") { "%.1f%%".format(100.0 * it / total.rounds) } + " of rounds")
        for (v in 3..6) {
            println(
                "  to $v: said in ${"%5.1f".format(100.0 * total.said[v] / total.rounds)} % of rounds, " +
                    "stayed ${"%5.1f".format(100.0 * total.stayed[v] / total.said[v].coerceAtLeast(1))} %, " +
                    "rounds played for $v: ${"%5.1f".format(100.0 * total.finalValue[v] / total.rounds)} %",
            )
        }
    }

    /** The manners before the brake on raising: only the sure trick and the Schub that could lose the match. */
    private class OldManners(private val base: Agent, private val random: Random) : Agent {
        override fun chooseAction(observation: Observation, legalActions: List<Action>): Action {
            if (Action.Schub !in legalActions) return base.chooseAction(observation, legalActions)
            val without = legalActions - Action.Schub
            if (without.isNotEmpty() && SchubManners.decidingTrickSure(observation)) return base.chooseAction(observation, without)
            val chosen = base.chooseAction(observation, legalActions)
            if (chosen != Action.Schub || without.isEmpty() || !SchubManners.raiseLosesMatch(observation)) return chosen
            val keep = SchubManners.keepChance(SchubManners.winChance(observation, random))
            return if (random.nextDouble() < keep) chosen else base.chooseAction(observation, without)
        }
    }

    /** Each deal twice with swapped seats: the new manners against the old ones. */
    private fun headToHead(pairs: Int, q: OrtQFunction) {
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val jobs = (0 until pairs).flatMap { pair -> listOf(pair to 0, pair to 1) }.map { (pair, newTeam) ->
            pool.submit<Int> {
                val agents = List(PLAYER_COUNT) { seat ->
                    val random = Random(pair * 8L + seat)
                    if (teamOf(seat) == newTeam) SchubManners(MlAgent(q), random) else OldManners(MlAgent(q), random)
                }
                val end = MatchRunner(agents, Random(pair.toLong())).playMatch()
                if (Waddeln.matchWinner(end) == newTeam) 1 else 0
            }
        }
        val won = jobs.sumOf { it.get() }
        pool.shutdown()
        val rate = won.toDouble() / jobs.size
        println("New against old manners: ${jobs.size} matches, matches won ${"%.1f".format(rate * 100)} % ± ${"%.1f".format(sqrt(rate * (1 - rate) / jobs.size) * 100)}")
    }

    /** What a Schub costs at the start of a round, where the most cards are open. */
    private fun timing() {
        val random = Random(1)
        val nanos = (0 until 300).map {
            var s = Waddeln.newMatch(0, Deal.random(random))
            s = Waddeln.apply(s, s.playerToMove!!, Action.AnnounceSchlag(s.hands[s.playerToMove!!].first().rank))
            s = Waddeln.apply(s, s.playerToMove!!, Action.AnnounceFarbe(s.hands[s.playerToMove!!].first().suit))
            val obs = s.observationFor(s.playerToMove!!)
            val start = System.nanoTime()
            SchubManners.winChance(obs, random)
            System.nanoTime() - start
        }.drop(100).sorted()
        println("winChance at the first card: median ${nanos[nanos.size / 2] / 1_000_000} ms, slowest ${nanos.last() / 1_000_000} ms")
    }

    @Test
    fun `schub statistics`() {
        val matches = System.getenv("SCHUB_STATS")?.toInt() ?: return
        timing()
        measure("Rule-based", matches) { SchubManners(RuleBasedAgent(), Random(it.toLong())) }
        OrtQFunction(File("../app/src/main/assets/models/waddeln.onnx").readBytes()).use { q ->
            measure("Before the brake (hard)", matches) { OldManners(MlAgent(q), Random(it.toLong())) }
            measure("Network (hard)", matches) { SchubManners(MlAgent(q), Random(it.toLong())) }
            measure("Network (medium)", matches) { SchubManners(MlAgent(q, 0.7, Random(it.toLong())), Random(it.toLong() + 1)) }
            headToHead(matches, q)
        }
    }

    private companion object {
        const val MAX_VALUE = 8
        const val HIGHER = 1
        const val TRICKS_IN_ROUND = 5
        val CHANCE_LIMITS = listOf(0.3, 0.5, 0.7, 0.9)
    }
}
