package com.schaubeck.waddeln.rules

import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.ml.FeatureEncoder
import com.schaubeck.waddeln.rules.ml.MlAgent
import com.schaubeck.waddeln.rules.ml.MlpQFunction
import com.schaubeck.waddeln.rules.ml.QFunction
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure Kotlin network (used in the browser) must compute what ONNX Runtime computes (Android, server). */
class MlpQFunctionTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/ml/$name")!!.readBytes()

    /** Inputs shaped like real ones: mostly 0/1 features, some values in between. */
    private fun randomInputs(random: Random, rows: Int, size: Int) = Array(rows) {
        FloatArray(size) {
            when (random.nextInt(4)) {
                0 -> 1f
                1 -> random.nextFloat()
                else -> 0f
            }
        }
    }

    private fun maxDifference(a: QFunction, b: QFunction, random: Random, batches: Int): Float {
        assertEquals(a.inputSize, b.inputSize)
        var worst = 0f
        repeat(batches) {
            val x = randomInputs(random, 1 + random.nextInt(12), a.inputSize)
            val qa = a.evaluate(x)
            val qb = b.evaluate(x)
            assertEquals(qa.size, qb.size)
            qa.indices.forEach { worst = maxOf(worst, abs(qa[it] - qb[it])) }
        }
        return worst
    }

    /** Asks both agents at every decision of a match, checks they agree; returns the number of network decisions. */
    private fun compareDecisions(onnx: QFunction, mlp: QFunction, matches: Int): Int {
        val ortAgent = MlAgent(onnx, temperature = 0.0)
        val mlpAgent = MlAgent(mlp, temperature = 0.0)
        var decisions = 0
        val comparing = Agent { observation, legal ->
            val a = ortAgent.chooseAction(observation, legal)
            assertEquals(a, mlpAgent.chooseAction(observation, legal), "different action in $observation")
            if (legal.size > 1) decisions++
            a
        }
        repeat(matches) { i ->
            val agents = List(PLAYER_COUNT) { if (it % 2 == 0) comparing else RuleBasedAgent() }
            MatchRunner(agents, Random(100 + i)).playMatch()
        }
        return decisions
    }

    @Test
    fun `MLP and ONNX give the same values for the test model`() {
        val mlp = MlpQFunction(resource("test_model.mlp"))
        assertEquals(FeatureEncoder.INPUT_SIZE, mlp.inputSize)
        OrtQFunction(resource("test_model.onnx")).use { onnx ->
            val worst = maxDifference(onnx, mlp, Random(1), batches = 500)
            assertTrue(worst < 1e-4f, "max |onnx - mlp| = $worst")
        }
    }

    @Test
    fun `greedy MlAgent chooses the same actions with MLP and ONNX`() {
        OrtQFunction(resource("test_model.onnx")).use { onnx ->
            val decisions = compareDecisions(onnx, MlpQFunction(resource("test_model.mlp")), matches = 20)
            assertTrue(decisions > 500, "only $decisions decisions compared")
        }
    }

    /**
     * Same checks with the real app model plus timings. The .mlp of the app model is not committed:
     * `training/export_weights.py ../app/src/main/assets/models/waddeln.onnx <file>` and set WADDELN_APP_MLP=<file>.
     */
    @Test
    fun `app model - MLP matches ONNX, timing of a typical decision`() {
        val mlpFile = System.getenv("WADDELN_APP_MLP")?.let(::File) ?: return
        val onnxFile = File("../app/src/main/assets/models/waddeln.onnx")
        if (!mlpFile.exists() || !onnxFile.exists()) return
        val mlp = MlpQFunction(mlpFile.readBytes())
        OrtQFunction(onnxFile.readBytes()).use { onnx ->
            // Real inputs: artificial ones with many ones drive the large net to values where float32 rounding exceeds 1e-4.
            var worst = 0f
            situations().forEach { (o, legal) ->
                val state = FeatureEncoder.encodeState(o)
                val x = Array(legal.size) { FeatureEncoder.encodeFor(mlp.inputSize, state, FeatureEncoder.encodeAction(o, legal[it])) }
                val qa = onnx.evaluate(x)
                val qb = mlp.evaluate(x)
                qa.indices.forEach { worst = maxOf(worst, abs(qa[it] - qb[it])) }
            }
            assertTrue(worst < 1e-4f, "max |onnx - mlp| = $worst")
            val decisions = compareDecisions(onnx, mlp, matches = 3)
            println("app model: max |onnx - mlp| = $worst on real inputs, $decisions decisions identical")
            println("timing MLP:  ${timing(mlp)}")
            println("timing ONNX: ${timing(onnx)}")
        }
    }

    /** Real decisions with more than one choice, from a match of rule bots. */
    private fun situations(): List<Pair<Observation, List<Action>>> {
        val situations = mutableListOf<Pair<Observation, List<Action>>>()
        val recorder = Agent { observation, legal ->
            if (legal.size > 1) situations += observation to legal
            RuleBasedAgent().chooseAction(observation, legal)
        }
        MatchRunner(List(PLAYER_COUNT) { recorder }, Random(7)).playMatch()
        return situations
    }

    /** Times MlAgent.chooseAction on real decisions. */
    private fun timing(q: QFunction): String {
        val situations = situations()
        val agent = MlAgent(q)
        repeat(3) { situations.forEach { (o, l) -> agent.chooseAction(o, l) } } // warm-up (JIT)
        val times = situations.map { (o, l) ->
            val start = System.nanoTime()
            agent.chooseAction(o, l)
            (System.nanoTime() - start) / 1e6
        }.sorted()
        val choices = situations.map { it.second.size }.average()
        return "%d decisions (avg %.1f legal actions): median %.2f ms, mean %.2f ms, p95 %.2f ms, max %.2f ms".format(
            times.size, choices, times[times.size / 2], times.average(), times[(times.size * 95) / 100], times.last(),
        )
    }
}
