package com.schaubeck.waddeln.server

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.schaubeck.waddeln.protocol.BotStrength
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.ml.FeatureEncoder
import com.schaubeck.waddeln.rules.ml.MlAgent
import com.schaubeck.waddeln.rules.ml.QFunction
import org.slf4j.LoggerFactory
import java.nio.FloatBuffer

/** Creates the agent that plays a seat without (connected) human. */
fun interface BotProvider {
    fun create(strength: BotStrength): Agent
}

/** ML bots if the network could be loaded, otherwise (and for [BotStrength.EASY]) the rule-based bot. */
class ModelBots(private val q: QFunction?) : BotProvider {
    override fun create(strength: BotStrength): Agent {
        val temperature = strength.temperature
        return if (q == null || temperature == null) RuleBasedAgent() else MlAgent(q, temperature)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ModelBots::class.java)

        /** Loads the bundled network (resource `/models/waddeln.onnx`). */
        fun load(): ModelBots {
            val q = try {
                ModelBots::class.java.getResourceAsStream("/models/waddeln.onnx")?.use { OrtQFunction(it.readBytes()) }
            } catch (e: Exception) {
                log.warn("Could not load the network, using rule-based bots", e)
                null
            }
            log.info(if (q != null) "Network loaded" else "No network, using rule-based bots")
            return ModelBots(q)
        }
    }
}

/** Runs the network with ONNX Runtime (JVM), same interface as the app. */
class OrtQFunction(modelBytes: ByteArray) : QFunction, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes)

    override fun evaluate(inputs: Array<FloatArray>): FloatArray {
        val buffer = FloatBuffer.allocate(inputs.size * FeatureEncoder.INPUT_SIZE)
        inputs.forEach { buffer.put(it) }
        buffer.rewind()
        val shape = longArrayOf(inputs.size.toLong(), FeatureEncoder.INPUT_SIZE.toLong())
        OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
            session.run(mapOf("input" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return result[0].value as FloatArray
            }
        }
    }

    override fun close() = session.close()
}
