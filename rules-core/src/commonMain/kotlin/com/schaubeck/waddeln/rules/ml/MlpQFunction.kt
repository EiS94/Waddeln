package com.schaubeck.waddeln.rules.ml

/**
 * Q-network evaluated in plain Kotlin, for platforms without ONNX Runtime (the browser).
 *
 * Loads the weights written by `training/export_weights.py` (format "WMLP", version 1, little-endian):
 * magic, version, layer count, then per layer input width, output width, weights `out × in` (PyTorch
 * layout) and bias, all as float32. Computes `Linear → ReLU → … → Linear`; the last layer has one output.
 */
class MlpQFunction(bytes: ByteArray) : QFunction {
    private class Layer(val inputs: Int, val outputs: Int, val weightsByInput: FloatArray, val bias: FloatArray)

    private val layers: List<Layer>

    override val inputSize: Int

    init {
        val reader = Reader(bytes)
        require(reader.size >= 12 && MAGIC.indices.all { bytes[it] == MAGIC[it] }) { "Not a WMLP model" }
        reader.skip(MAGIC.size)
        val version = reader.int()
        require(version == FORMAT_VERSION) { "WMLP format version $version, expected $FORMAT_VERSION" }
        val count = reader.int()
        require(count in 1..MAX_LAYERS) { "Invalid layer count $count" }
        layers = List(count) {
            val inputs = reader.int()
            val outputs = reader.int()
            require(inputs in 1..MAX_WIDTH && outputs in 1..MAX_WIDTH) { "Invalid layer size $inputs × $outputs" }
            reader.need(4L * outputs * (inputs + 1))
            // Stored as weight[o][i]; kept as weight[i][o] so that zero inputs (one-hot features, ReLU) are skipped.
            val transposed = FloatArray(inputs * outputs)
            for (o in 0 until outputs) for (i in 0 until inputs) transposed[i * outputs + o] = reader.float()
            Layer(inputs, outputs, transposed, FloatArray(outputs) { reader.float() })
        }
        require(reader.remaining == 0) { "${reader.remaining} trailing bytes" }
        for (k in 1 until count) {
            require(layers[k].inputs == layers[k - 1].outputs) { "Layer $k does not fit layer ${k - 1}" }
        }
        require(layers.last().outputs == 1) { "The last layer must have one output" }
        inputSize = layers.first().inputs
    }

    override fun evaluate(inputs: Array<FloatArray>): FloatArray = FloatArray(inputs.size) { evaluate(inputs[it]) }

    private fun evaluate(input: FloatArray): Float {
        require(input.size == inputSize) { "Input has ${input.size} values, the model expects $inputSize" }
        var x = input
        for ((k, layer) in layers.withIndex()) {
            val out = layer.bias.copyOf()
            val w = layer.weightsByInput
            val n = layer.outputs
            for (i in 0 until layer.inputs) {
                val xi = x[i]
                if (xi == 0f) continue
                val base = i * n
                for (o in 0 until n) out[o] += xi * w[base + o]
            }
            if (k < layers.lastIndex) for (o in 0 until n) if (out[o] < 0f) out[o] = 0f
            x = out
        }
        return x[0]
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0
        val size get() = bytes.size
        val remaining get() = bytes.size - pos

        fun skip(n: Int) { pos += n }

        fun need(n: Long) = require(remaining >= n) { "Truncated WMLP model" }

        fun int(): Int {
            need(4)
            val v = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun float() = Float.fromBits(int())
    }

    companion object {
        const val FORMAT_VERSION = 1
        private val MAGIC = "WMLP".encodeToByteArray()
        private const val MAX_LAYERS = 64
        private const val MAX_WIDTH = 1 shl 16
    }
}
