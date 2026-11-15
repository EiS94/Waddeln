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
        // The network plays the bots; without it (download failed) they play rule-based, as on Android.
        val model = try {
            WebRes.readBytes("files/waddeln.mlp")
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
            WaddelnApp(platform, WebSharing, sound, invite, resumeOnline = true, onInviteTaken = {
                invite = null
                clearRoomCodeFromAddress()
            })
        }
    }
}

/**
 * In the browser every image is downloaded on first use, so a card dealt for the first time would
 * show blank for a moment. Asking for all of them at the start fills the image cache.
 */
@Composable
private fun PreloadImages() {
    SharedRes.allDrawableResources.values.forEach { painterResource(it) }
}
