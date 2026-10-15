package com.schaubeck.waddeln.rules

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.schaubeck.waddeln.rules.agent.MatchRunner
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.ml.FeatureEncoder
import com.schaubeck.waddeln.rules.ml.MlAgent
import com.schaubeck.waddeln.rules.ml.QFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ONNX Runtime backed Q-function, same API as on Android. */
class OrtQFunction(modelBytes: ByteArray) : QFunction, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes)

    /** Input width of the loaded model (it may predate the table talk features). */
    override val inputSize: Int = (session.inputInfo.getValue("input").info as TensorInfo).shape[1].toInt()

    override fun evaluate(inputs: Array<FloatArray>): FloatArray {
        val n = inputs.size
        val buffer = FloatBuffer.allocate(n * inputSize)
        inputs.forEach { buffer.put(it) }
        buffer.rewind()
        OnnxTensor.createTensor(env, buffer, longArrayOf(n.toLong(), inputSize.toLong())).use { tensor ->
            session.run(mapOf("input" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return result[0].value as FloatArray
            }
        }
    }

    override fun close() = session.close()
}

class MlAgentTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/ml/$name")!!.readBytes()

    @Test
    fun `Kotlin feeds and reads the ONNX model like Python`() {
        val vectors = Json.parseToJsonElement(resource("test_vectors.json").decodeToString()).jsonObject
        assertEquals(FeatureEncoder.INPUT_SIZE, vectors["inputSize"]!!.jsonPrimitive.int)
        OrtQFunction(resource("test_model.onnx")).use { q ->
            for (case in vectors["cases"]!!.jsonArray) {
                val inputs = case.jsonObject["input"]!!.jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() }
                val expected = case.jsonObject["q"]!!.jsonArray.map { it.jsonPrimitive.float }
                val got = q.evaluate(inputs.toTypedArray())
                expected.indices.forEach { assertTrue(abs(expected[it] - got[it]) < 1e-5, "q[$it]: ${expected[it]} vs ${got[it]}") }
            }
        }
    }

    @Test
    fun `MlAgent plays legal matches with an ONNX model`() {
        OrtQFunction(resource("test_model.onnx")).use { q ->
            repeat(20) { i ->
                val agents = List(PLAYER_COUNT) { if (it % 2 == 0) MlAgent(q, temperature = 0.2, random = Random(i)) else RuleBasedAgent() }
                MatchRunner(agents, Random(i), onAction = { s, p, a -> assertTrue(Waddeln.isLegal(s, p, a)) }).playMatch()
            }
        }
    }

    @Test
    fun `the bundled app model keeps working whatever feature layout it was trained with`() {
        val model = java.io.File("../app/src/main/assets/models/waddeln.onnx")
        if (!model.exists()) return
        OrtQFunction(model.readBytes()).use { q ->
            assertTrue(q.inputSize == FeatureEncoder.INPUT_SIZE || q.inputSize == FeatureEncoder.LEGACY_INPUT_SIZE)
            var failed = false
            val agents = List(PLAYER_COUNT) { seat ->
                if (seat % 2 == 0) {
                    MlAgent(q, fallback = { _, _ -> failed = true; error("the model must not fail") })
                } else {
                    RuleBasedAgent()
                }
            }
            MatchRunner(agents, Random(1)).playMatch()
            assertTrue(!failed)
        }
    }

    @Test
    fun `greedy MlAgent picks the highest value, falls back on errors`() {
        val s = playingWith(STANDARD_HANDS, Rank.OBER, Suit.GRAS)
        val legal = Waddeln.legalActions(s, 0)
        // Value = index of the action code: prefers the last legal action
        val byOrder = QFunction { rows -> FloatArray(rows.size) { it.toFloat() } }
        assertEquals(legal.last(), MlAgent(byOrder).chooseAction(s.observationFor(0), legal))
        val failing = QFunction { error("model broken") }
        val fallback = RuleBasedAgent().chooseAction(s.observationFor(0), legal)
        assertEquals(fallback, MlAgent(failing).chooseAction(s.observationFor(0), legal))
    }
}
