package com.schaubeck.waddeln.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.schaubeck.waddeln.WaddelnApp
import com.schaubeck.waddeln.platform.Log
import com.schaubeck.waddeln.ui.AppFonts
import com.schaubeck.waddeln.web.resources.droid_sans_mono
import com.schaubeck.waddeln.web.resources.noto_serif_bold
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import com.schaubeck.waddeln.resources.allDrawableResources
import com.schaubeck.waddeln.sound.TableSound
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import com.schaubeck.waddeln.resources.Res as SharedRes
import com.schaubeck.waddeln.web.resources.Res as WebRes

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        try {
            start()
        } catch (e: Throwable) {
            reportStartError(e)
        }
    }
}

/** Shown by start.js instead of a white page (with the browser's details). */
private fun reportStartError(e: Throwable) =
    startError("${e::class.simpleName}: ${e.message} | ${e.stackTraceToString().take(1500)}")

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun start() {
    run {
        // The network plays the bots; without it (download failed) they play rule-based, as on Android.
        val model = try {
            SharedRes.readBytes("files/waddeln.mlp")
        } catch (e: Throwable) {
            Log.w("Main", "No model, using rule-based bots", e)
            null
        }
        val platform = webPlatform(model)
        val sound = TableSound(WebSoundOutput())
        document.getElementById("loading")?.remove()
        ComposeViewport(document.body!!) {
            // An invitation link (/play/#CODE) opens the room, also when it is opened while the page is open.
            var invite by remember { mutableStateOf(roomCodeFromAddress()) }
            LaunchedEffect(Unit) { roomCodeChanges().collect { invite = it } }
            PreloadImages()
            val fonts = AppFonts(
                serif = FontFamily(Font(WebRes.font.noto_serif_bold, FontWeight.Bold)),
                monospace = FontFamily(Font(WebRes.font.droid_sans_mono)),
                drawnPencil = true,
            )
            WaddelnApp(
                platform, WebSharing, sound, invite,
                resumeOnline = true,
                limitWidth = true,
                fonts = fonts,
                onStarted = ::startedOk,
                onStartError = ::reportStartError,
                onInviteTaken = {
                    invite = null
                    clearRoomCodeFromAddress()
                },
            )
        }
    }
}

private fun startedOk(): Unit = js("{ if (window.waddelnStarted) window.waddelnStarted(); }")

private fun startError(message: String): Unit = js("{ if (window.waddelnStartError) window.waddelnStartError(message); }")

/**
 * In the browser every image is downloaded on first use, so a card dealt for the first time would
 * show blank for a moment. Asking for all of them at the start fills the image cache.
 */
@Composable
private fun PreloadImages() {
    SharedRes.allDrawableResources.values.forEach { painterResource(it) }
}
