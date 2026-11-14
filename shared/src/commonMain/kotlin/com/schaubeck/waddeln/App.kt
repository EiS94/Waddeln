package com.schaubeck.waddeln

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.schaubeck.waddeln.game.GameViewModel
import com.schaubeck.waddeln.game.StatsMode
import com.schaubeck.waddeln.game.StatsStore
import com.schaubeck.waddeln.online.OnlineViewModel
import com.schaubeck.waddeln.platform.Platform
import com.schaubeck.waddeln.platform.Sharing
import com.schaubeck.waddeln.sound.LocalTableSound
import com.schaubeck.waddeln.sound.TableSound
import com.schaubeck.waddeln.ui.GameScreen
import com.schaubeck.waddeln.ui.LocalSharing
import com.schaubeck.waddeln.ui.LocalTableStyle
import com.schaubeck.waddeln.ui.MainMenu
import com.schaubeck.waddeln.ui.OnlineScreen
import com.schaubeck.waddeln.ui.RulesScreen
import com.schaubeck.waddeln.ui.SettingsScreen
import com.schaubeck.waddeln.ui.StatsScreen
import com.schaubeck.waddeln.ui.Strings
import com.schaubeck.waddeln.ui.SwitchRoomDialog

private enum class Screen { MENU, GAME, SETTINGS, STATS, ONLINE, RULES }

/**
 * The whole app: menu, offline game, online game and the other screens.
 *
 * [invite] is the room code of an invitation link the app was opened with; [onInviteTaken] is
 * called once the online screen took it.
 */
@Composable
fun WaddelnApp(platform: Platform, sharing: Sharing, sound: TableSound, invite: String?, onInviteTaken: () -> Unit) {
    // The view models need the texts right away; reading them takes a moment only at the start.
    val strings by produceState<Strings?>(null) { value = Strings.load() }
    val texts = strings ?: return
    val viewModel = viewModel { GameViewModel(platform, texts) }
    val onlineViewModel = viewModel { OnlineViewModel(platform, texts) }
    val statsStore = remember { StatsStore(platform.stores) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        sound.stop()
        // Android may end the app in the background; the offline match then goes on from here.
        viewModel.saveMatch()
    }

    CompositionLocalProvider(LocalTableSound provides sound, LocalSharing provides sharing) { MaterialTheme {
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
            onInviteTaken()
            screen = Screen.ONLINE
            onlineViewModel.openInvite(code)
        }
        PlatformBackHandler(enabled = screen != Screen.MENU) { screen = Screen.MENU }

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
                onAskAdvice = viewModel::onAskAdvice,
                onAnswerAdvice = viewModel::onAnswerAdvice,
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
                val (offline, onlineStats) = remember(resets) {
                    statsStore.load(StatsMode.OFFLINE) to statsStore.load(StatsMode.ONLINE)
                }
                StatsScreen(
                    offline = offline,
                    online = onlineStats,
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
                        onAskAdvice = onlineViewModel::askAdvice,
                        onAnswerAdvice = onlineViewModel::answerAdvice,
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

/** Goes back to the menu instead of leaving the app (Android back gesture; in the browser: Phase 4). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
