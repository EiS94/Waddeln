package com.schaubeck.waddeln

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schaubeck.waddeln.game.GameViewModel
import com.schaubeck.waddeln.game.StatsMode
import com.schaubeck.waddeln.game.StatsStore
import com.schaubeck.waddeln.online.OnlineViewModel
import com.schaubeck.waddeln.protocol.INVITE_HOST
import com.schaubeck.waddeln.protocol.INVITE_PATH
import com.schaubeck.waddeln.protocol.parseRoomCode
import com.schaubeck.waddeln.sound.LocalTableSound
import com.schaubeck.waddeln.sound.TableSound
import com.schaubeck.waddeln.ui.GameScreen
import com.schaubeck.waddeln.ui.LocalTableStyle
import com.schaubeck.waddeln.ui.MainMenu
import com.schaubeck.waddeln.ui.OnlineScreen
import com.schaubeck.waddeln.ui.RulesScreen
import com.schaubeck.waddeln.ui.SettingsScreen
import com.schaubeck.waddeln.ui.SwitchRoomDialog
import com.schaubeck.waddeln.ui.StatsScreen

private enum class Screen { MENU, GAME, SETTINGS, STATS, ONLINE, RULES }

class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels()
    private val onlineViewModel: OnlineViewModel by viewModels()
    private val statsStore by lazy { StatsStore(applicationContext) }
    private lateinit var sound: TableSound

    /** Room code of an invitation link the app was opened with, until the online screen takes it. */
    private var invite by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The volume keys change the game volume, not the ringer.
        volumeControlStream = AudioManager.STREAM_MUSIC
        sound = TableSound(this)
        // After a restart (e.g. a configuration change) the link has already been handled.
        if (savedInstanceState == null) invite = inviteCode(intent)
        setContent {
            CompositionLocalProvider(LocalTableSound provides sound) { MaterialTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val online by onlineViewModel.ui.collectAsStateWithLifecycle()
                sound.effectsOn = ui.settings.soundEffects
                sound.voicesOn = ui.settings.voices
                onlineViewModel.selfCut = ui.settings.selfCut
                var screen by rememberSaveable { mutableStateOf(Screen.MENU) }

                // Bots only play while the table is visible. Online, the connection stays open while
                // we sit in a room, so the game can go on in the background.
                LaunchedEffect(screen) {
                    if (screen == Screen.GAME) viewModel.onGameShown() else viewModel.onGameHidden()
                    if (screen == Screen.ONLINE) {
                        onlineViewModel.connect(ui.settings.serverUrl, ui.settings.onlineName)
                    } else {
                        onlineViewModel.disconnectIfIdle()
                    }
                }
                // An invitation link opens the online screen and joins the room there.
                LaunchedEffect(invite) {
                    val code = invite ?: return@LaunchedEffect
                    invite = null
                    screen = Screen.ONLINE
                    onlineViewModel.openInvite(code)
                }
                BackHandler(enabled = screen != Screen.MENU) { screen = Screen.MENU }

                CompositionLocalProvider(LocalTableStyle provides ui.settings.table) { when (screen) {
                    Screen.MENU -> MainMenu(
                        matchInProgress = ui.matchInProgress,
                        onContinue = { screen = Screen.GAME },
                        onNewGame = {
                            viewModel.onNewMatch()
                            screen = Screen.GAME
                        },
                        onOnline = { screen = Screen.ONLINE },
                        onSettings = { screen = Screen.SETTINGS },
                        onStats = { screen = Screen.STATS },
                        onRules = { screen = Screen.RULES },
                    )

                    Screen.RULES -> RulesScreen(onBack = { screen = Screen.MENU })

                    Screen.GAME -> GameScreen(
                        table = ui.table,
                        onAction = viewModel::onHumanAction,
                        onContinue = viewModel::onContinue,
                        onNoticeConfirmed = viewModel::onNoticeConfirmed,
                        onAskPartner = viewModel::onAskPartner,
                        onAskSchadet = viewModel::onAskSchadet,
                        onAnswerSchadet = viewModel::onAnswerSchadet,
                        onShowLastTrick = viewModel::onShowLastTrick,
                        onProposeSchlagwechsel = viewModel::onProposeSchlagwechsel,
                        onAnswerSchlagwechsel = viewModel::onAnswerSchlagwechsel,
                        onCut = viewModel::onCut,
                        onCutSeen = viewModel::onCutSeen,
                        onMenu = { screen = Screen.MENU },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        settings = ui.settings,
                        onChange = viewModel::onSettingsChanged,
                        onBack = { screen = Screen.MENU },
                    )

                    Screen.STATS -> {
                        // Loaded each time the page is opened; reloaded after a reset.
                        var resets by remember { mutableIntStateOf(0) }
                        val (offline, online) = remember(resets) {
                            statsStore.load(StatsMode.OFFLINE) to statsStore.load(StatsMode.ONLINE)
                        }
                        StatsScreen(
                            offline = offline,
                            online = online,
                            onReset = {
                                statsStore.reset()
                                resets++
                            },
                            onBack = { screen = Screen.MENU },
                        )
                    }

                    Screen.ONLINE -> {
                        val game = online.game
                        val current = online.table?.room?.code
                        val switchTo = online.invite
                        if (current != null && switchTo != null && switchTo != current) {
                            SwitchRoomDialog(
                                current = current,
                                invite = switchTo,
                                inGame = game != null,
                                onSwitch = onlineViewModel::switchToInvite,
                                onStay = onlineViewModel::dismissInvite,
                            )
                        }
                        if (game != null) {
                            GameScreen(
                                table = game,
                                onAction = onlineViewModel::act,
                                onContinue = onlineViewModel::ready,
                                onNoticeConfirmed = onlineViewModel::onNoticeConfirmed,
                                onAskPartner = onlineViewModel::askPartner,
                                onAnswerPartner = onlineViewModel::answerPartner,
                                onAskSchadet = onlineViewModel::askSchadet,
                                onAnswerSchadet = onlineViewModel::answerSchadet,
                                onShowLastTrick = onlineViewModel::showLastTrick,
                                onProposeSchlagwechsel = onlineViewModel::proposeSchlagwechsel,
                                onAnswerSchlagwechsel = onlineViewModel::answerSchlagwechsel,
                                onCut = onlineViewModel::cut,
                                onCutSeen = onlineViewModel::onCutSeen,
                                onMenu = { screen = Screen.MENU },
                                onLeave = onlineViewModel::leave,
                                soundKey = "online",
                            )
                        } else {
                            val settings = ui.settings
                            // The nickname is sent again right before creating or joining a room.
                            val introduce = { onlineViewModel.connect(settings.serverUrl, settings.onlineName) }
                            OnlineScreen(
                                ui = online,
                                name = settings.onlineName,
                                serverUrl = settings.serverUrl,
                                onNameChange = { viewModel.onSettingsChanged(settings.copy(onlineName = it)) },
                                onCreate = {
                                    introduce()
                                    onlineViewModel.createRoom()
                                },
                                onJoin = { code ->
                                    introduce()
                                    onlineViewModel.joinRoom(code)
                                },
                                onTakeSeat = onlineViewModel::takeSeat,
                                onBotStrength = onlineViewModel::setBotStrength,
                                onBotName = onlineViewModel::setBotName,
                                onStart = onlineViewModel::start,
                                onLeave = onlineViewModel::leave,
                                onBack = { screen = Screen.MENU },
                            )
                        }
                    }
                } }
            } }
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

    override fun onStop() {
        super.onStop()
        sound.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }
}
