package com.schaubeck.waddeln.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.schaubeck.waddeln.platform.Sharing
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [value] with one decimal and a German decimal comma ("2,5", "-0,3"), rounded half up like
 * `String.format(Locale.GERMAN, "%.1f", value)`.
 */
fun oneDecimal(value: Double): String {
    val tenths = (abs(value) * 10).roundToLong()
    val sign = if (value < 0 && tenths != 0L) "-" else ""
    return "$sign${tenths / 10},${tenths % 10}"
}

/** Copying and sharing of the platform; null in previews. */
val LocalSharing = staticCompositionLocalOf<Sharing?> { null }
