package com.schaubeck.waddeln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.game.TableStyle
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Shared look of all screens: wooden table, dark panels, golden highlights. */
val BarGreen = Color(0xFF2F5D1E)
val Panel = Color(0xCC1B140D)
val Highlight = Color(0xFFFFD54F)
val SchubOrange = Color(0xFFE65100)
val WoodBrown = Color(0xFF6D4C2F)

@Composable
fun woodButton() = ButtonDefaults.buttonColors(containerColor = WoodBrown)

/** The table chosen in the settings; provided once for all screens. */
val LocalTableStyle = staticCompositionLocalOf { TableStyle.DARK }

@Composable
fun WoodBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val wood = ImageBitmap.imageResource(
        when (LocalTableStyle.current) {
            TableStyle.DARK -> R.drawable.table_dark
            TableStyle.LIGHT -> R.drawable.table_light
        },
    )
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF3B2A1A))
            .drawBehind { drawWood(wood) },
    ) {
        content()
    }
}

/**
 * The wood photo is landscape; stretching it to the full height of a phone would blur it.
 * Instead it is scaled to the screen width and repeated downwards, every second copy mirrored
 * so the seams match.
 */
private fun DrawScope.drawWood(wood: ImageBitmap) {
    val width = size.width.roundToInt()
    val tileHeight = wood.height * size.width / wood.width
    var top = 0f
    var mirrored = false
    while (top < size.height) {
        scale(1f, if (mirrored) -1f else 1f, pivot = Offset(size.width / 2, top + tileHeight / 2)) {
            drawImage(
                wood,
                dstOffset = IntOffset(0, top.roundToInt()),
                dstSize = IntSize(width, ceil(tileHeight).toInt() + 1),
                filterQuality = FilterQuality.High,
            )
        }
        top += tileHeight
        mirrored = !mirrored
    }
}
