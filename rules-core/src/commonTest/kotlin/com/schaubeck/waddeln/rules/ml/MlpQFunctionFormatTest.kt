package com.schaubeck.waddeln.rules.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Reading the WMLP format and the forward pass, on every target (JVM and Wasm). */
class MlpQFunctionFormatTest {
    private fun model(vararg layers: Pair<Array<FloatArray>, FloatArray>, version: Int = 1): ByteArray {
        val out = mutableListOf<Byte>()
        fun int(v: Int) = repeat(4) { out += (v ushr (8 * it)).toByte() }
        out += "WMLP".encodeToByteArray().toList()
        int(version)
        int(layers.size)
        for ((w, b) in layers) {
            int(w[0].size)
            int(w.size)
            w.forEach { row -> row.forEach { int(it.toRawBits()) } }
            b.forEach { int(it.toRawBits()) }
        }
        return out.toByteArray()
    }

    // 2 -> 2 (ReLU) -> 1
    private val small = model(
        arrayOf(floatArrayOf(1f, -1f), floatArrayOf(2f, 0.5f)) to floatArrayOf(0f, -1f),
        arrayOf(floatArrayOf(1f, 1f)) to floatArrayOf(0.5f),
    )

    @Test
    fun computesLinearReluLinear() {
        val q = MlpQFunction(small)
        assertEquals(2, q.inputSize)
        // [1, 2] -> hidden [-1, 2] -> ReLU [0, 2] -> 2.5;  [3, 0] -> [3, 5] -> 8.5;  [0, 0] -> [0, -1] -> [0, 0] -> 0.5
        val values = q.evaluate(arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 0f), floatArrayOf(0f, 0f)))
        assertEquals(listOf(2.5f, 8.5f, 0.5f), values.toList())
    }

    @Test
    fun rejectsBrokenFiles() {
        assertFailsWith<IllegalArgumentException> { MlpQFunction("ONNX".encodeToByteArray() + small.copyOfRange(4, small.size)) }
        assertFailsWith<IllegalArgumentException> { MlpQFunction(small.copyOf(small.size - 1)) }
        assertFailsWith<IllegalArgumentException> { MlpQFunction(small + 0) }
        assertFailsWith<IllegalArgumentException> {
            MlpQFunction(model(arrayOf(floatArrayOf(1f)) to floatArrayOf(0f), version = 2))
        }
        // Layers that do not fit together, last layer with two outputs
        assertFailsWith<IllegalArgumentException> {
            MlpQFunction(model(arrayOf(floatArrayOf(1f)) to floatArrayOf(0f), arrayOf(floatArrayOf(1f, 1f)) to floatArrayOf(0f)))
        }
        assertFailsWith<IllegalArgumentException> {
            MlpQFunction(model(arrayOf(floatArrayOf(1f), floatArrayOf(1f)) to floatArrayOf(0f, 0f)))
        }
        assertFailsWith<IllegalArgumentException> { MlpQFunction(small).evaluate(arrayOf(floatArrayOf(1f))) }
    }
}
