package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.HandSampler
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.PimcAgent
import com.schaubeck.waddeln.rules.ml.DealInference
import com.schaubeck.waddeln.rules.ml.MlAgent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * The app network with [PimcAgent] against the app network alone. Only runs with the environment
 * variable PIMC_BENCH = number of match pairs (each deal is played twice with swapped seats);
 * PIMC_SAMPLES and PIMC_MARGIN set the agent's parameters.
 */
class PimcBenchmark {

    @Test
    fun `pimc against the network`() {
        val pairs = System.getenv("PIMC_BENCH")?.toInt() ?: return
        val samples = System.getenv("PIMC_SAMPLES")?.toInt() ?: 60
        val margin = System.getenv("PIMC_MARGIN")?.toDouble() ?: 0.0
        // Cheating: PIMC sees the real hands (upper bound for calculating ahead with good deals).
        val oracle = System.getenv("PIMC_ORACLE") != null
        // Softmax temperature of DealInference; without it the deals are not weighted.
        val inference = System.getenv("PIMC_INFERENCE")?.toDouble()
        val model = File("../app/src/main/assets/models/waddeln.onnx").readBytes()

        val cardDecisions = AtomicLong()
        val overridden = AtomicLong()
        val nanos = AtomicLong()
        OrtQFunction(model).use { q ->
            val weigh = inference?.let { DealInference(q, it)::weights }
            val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
            val jobs = (0 until pairs).flatMap { pair -> listOf(pair to 0, pair to 1) }.map { (pair, pimcTeam) ->
                pool.submit<IntArray> {
                    var hands: List<List<Card>> = emptyList()
                    val agents = List(PLAYER_COUNT) { seat ->
                        val net = MlAgent(q)
                        if (teamOf(seat) != pimcTeam) return@List net
                        val sampler = if (oracle) { _, _ -> hands } else HandSampler::sample
                        val pimc = PimcAgent(net, if (oracle) 1 else samples, margin, Random(pair * 4L + seat), sampler, weigh)
                        Agent { obs, legal ->
                            val start = System.nanoTime()
                            val action = pimc.chooseAction(obs, legal)
                            nanos.addAndGet(System.nanoTime() - start)
                            if (legal.count { it is Action.PlayCard } > 1) {
                                cardDecisions.incrementAndGet()
                                if (action != net.chooseAction(obs, legal)) overridden.incrementAndGet()
                            }
                            action
                        }
                    }
                    val points = IntArray(TEAM_COUNT)
                    val end = MatchRunner(agents, Random(pair.toLong()), onAction = { s, p, a ->
                        val after = Waddeln.apply(s, p, a)
                        hands = after.hands
                        after.result?.takeIf { s.result == null }?.let { points[it.winningTeam] += it.points }
                    }, onDeal = { _, s -> hands = s.hands }).playMatch()
                    val won = if (Waddeln.matchWinner(end) == pimcTeam) 1 else 0
                    intArrayOf(won, points[pimcTeam] - points[1 - pimcTeam])
                }
            }
            val results = jobs.map { it.get() }
            pool.shutdown()
            val n = results.size
            val rate = results.sumOf { it[0] }.toDouble() / n
            val se = sqrt(rate * (1 - rate) / n)
            println("PIMC samples=$samples margin=$margin oracle=$oracle inference=$inference: matches $n, won ${"%.1f".format(rate * 100)} % ± ${"%.1f".format(se * 100)}")
            println("  points per match (PIMC − network): ${"%.2f".format(results.sumOf { it[1] }.toDouble() / n)}")
            println("  card decisions ${cardDecisions.get()}, changed ${"%.1f".format(100.0 * overridden.get() / cardDecisions.get())} %, " +
                "${"%.1f".format(nanos.get() / 1e6 / cardDecisions.get())} ms per decision")
        }
    }
}
