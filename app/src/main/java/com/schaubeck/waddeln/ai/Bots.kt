package com.schaubeck.waddeln.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.schaubeck.waddeln.game.Difficulty
import com.schaubeck.waddeln.game.OfflineGame
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.agent.Agent
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import com.schaubeck.waddeln.rules.ml.MlAgent
import com.schaubeck.waddeln.rules.ml.QFunction
import com.schaubeck.waddeln.rules.teamOf
import java.io.Closeable
import java.nio.FloatBuffer

/** Runs the trained network (assets/models/waddeln.onnx) with ONNX Runtime for Android. */
class OnnxQFunction(modelBytes: ByteArray) : QFunction, Closeable {
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

/** Creates the bot players: ML bots if a model is bundled, otherwise the rule-based bot. */
class BotFactory(context: Context) : Closeable {
    private val qFunction: OnnxQFunction? = try {
        context.assets.open(MODEL_ASSET).use { OnnxQFunction(it.readBytes()) }
    } catch (e: Exception) {
        Log.w(TAG, "No usable model at $MODEL_ASSET, using rule-based bots", e)
        null
    }

    /**
     * One agent per seat. The opponents play at [difficulty]; the partner always at full strength.
     * Without a model all bots are rule-based.
     */
    fun createBots(difficulty: Difficulty): List<Agent> = List(PLAYER_COUNT) { seat ->
        val q = qFunction
        val temperature = if (teamOf(seat) == teamOf(OfflineGame.HUMAN)) 0.0 else difficulty.temperature
        if (q == null || temperature == null) RuleBasedAgent() else MlAgent(q, temperature)
    }

    override fun close() {
        qFunction?.close()
    }

    private companion object {
        const val MODEL_ASSET = "models/waddeln.onnx"
        const val TAG = "BotFactory"
    }
}
