package com.schaubeck.waddeln

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.schaubeck.waddeln.platform.AndroidSharing
import com.schaubeck.waddeln.platform.AndroidSoundOutput
import com.schaubeck.waddeln.platform.androidPlatform
import com.schaubeck.waddeln.protocol.INVITE_HOST
import com.schaubeck.waddeln.protocol.INVITE_PATH
import com.schaubeck.waddeln.protocol.parseRoomCode
import com.schaubeck.waddeln.sound.TableSound

/** The Android shell: platform services, invitation links and the screen; the app itself is [WaddelnApp]. */
class MainActivity : ComponentActivity() {
    private lateinit var sound: TableSound

    /** Room code of an invitation link the app was opened with, until the online screen takes it. */
    private var invite by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The volume keys change the game volume, not the ringer.
        volumeControlStream = AudioManager.STREAM_MUSIC
        val platform = androidPlatform(this)
        val sharing = AndroidSharing(this)
        sound = TableSound(AndroidSoundOutput(this))
        // After a restart (e.g. a configuration change) the link has already been handled.
        if (savedInstanceState == null) invite = inviteCode(intent)
        setContent {
            WaddelnApp(platform, sharing, sound, invite, onInviteTaken = { invite = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        inviteCode(intent)?.let { invite = it }
    }

    /** The room code of an invitation link (`https://waddeln.schaumler.de/r/<code>`), or null. */
    private fun inviteCode(intent: Intent?): String? {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        if (uri.host != INVITE_HOST) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || "/" + segments[0] != INVITE_PATH) return null
        return parseRoomCode(segments[1])
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }
}
