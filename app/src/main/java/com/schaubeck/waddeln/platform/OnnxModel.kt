package com.schaubeck.waddeln.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.schaubeck.waddeln.rules.ml.QFunction
import java.nio.FloatBuffer

/** Runs the trained network (assets/models/waddeln.onnx) with ONNX Runtime for Android. */
class OnnxQFunction(modelBytes: ByteArray) : QFunction, AutoCloseable {
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

/** Loads the bundled network (assets/models/waddeln.onnx); throws if there is none. */
class AssetModelLoader(private val context: Context) : ModelLoader {
    override fun load(): QFunction = context.assets.open(MODEL_ASSET).use { OnnxQFunction(it.readBytes()) }

    private companion object {
        const val MODEL_ASSET = "models/waddeln.onnx"
    }
}
