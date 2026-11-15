package com.schaubeck.waddeln.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schaubeck.waddeln.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Fonts the screens ask for by kind. Android has them as system fonts; the browser has none, so the
 * web app brings its own and draws the pencil (✎) that no bundled font has.
 */
class AppFonts(
    val serif: FontFamily = FontFamily.Serif,
    val monospace: FontFamily = FontFamily.Monospace,
    /** Draw the pencil next to renamable bots instead of writing the ✎ character. */
    val drawnPencil: Boolean = false,
)

val LocalAppFonts = staticCompositionLocalOf { AppFonts() }

/** The ✎ next to a bot's name: the character on Android, a drawing where no font has it. */
@Composable
fun PencilGlyph(color: Color, size: Dp, modifier: Modifier = Modifier) {
    if (!LocalAppFonts.current.drawnPencil) {
        Text("✎", color = color, fontSize = (size.value - 1).sp, modifier = modifier)
        return
    }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        rotate(45f, pivot = Offset(w / 2, h / 2)) {
            val body = w * 0.22f
            val left = w / 2 - body / 2
            val top = h * 0.08f
            val tipStart = h * 0.72f
            // Body, ferrule line and tip of a pencil standing on its point, turned by 45°.
            drawRect(color, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(body, tipStart - top))
            drawRect(Color.Black.copy(alpha = 0.35f), topLeft = Offset(left, top + h * 0.12f), size = androidx.compose.ui.geometry.Size(body, h * 0.04f))
            drawPath(
                Path().apply {
                    moveTo(left, tipStart)
                    lineTo(left + body, tipStart)
                    lineTo(w / 2, h * 0.94f)
                    close()
                },
                color,
            )
        }
    }
}

private val MAX_COLUMN = 600.dp
private val MIN_COLUMN = 360.dp

/** Below this height a landscape window is too low for the portrait table. */
private val MIN_LANDSCAPE_HEIGHT = 480.dp

/**
 * The screens are made for a phone held upright. In a wide window (laptop, tablet, phone turned
 * sideways) they stay a centred column of sensible width instead of being stretched; the rest of
 * the window shows the darkened table. A landscape window that is too low asks to turn the device.
 * Used by the web app; Android shows the screens as they are.
 */
@Composable
fun AppFrame(limitWidth: Boolean, content: @Composable () -> Unit) {
    if (!limitWidth) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val column = minOf(maxWidth, MAX_COLUMN, maxOf(maxHeight * 0.62f, MIN_COLUMN))
        val tooLow = maxWidth > maxHeight && maxHeight < MIN_LANDSCAPE_HEIGHT
        // Always the same structure, so no screen loses its state when the window is resized.
        WoodBackground { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) }
        // Clipped like the edges of a phone screen (the side players' cards reach over it).
        Box(Modifier.width(column).fillMaxHeight().align(Alignment.Center).clipToBounds()) { content() }
        if (tooLow) {
            Box(Modifier.fillMaxSize().background(Color(0xF21B140D)), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.rotate_device),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .background(Panel, RoundedCornerShape(14.dp))
                        .padding(20.dp),
                )
            }
        }
    }
}
