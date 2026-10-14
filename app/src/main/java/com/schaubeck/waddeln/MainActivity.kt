package com.schaubeck.waddeln

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
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
import com.schaubeck.waddeln.ui.GameScreen
import com.schaubeck.waddeln.ui.MainMenu
import com.schaubeck.waddeln.ui.OnlineScreen
import com.schaubeck.waddeln.ui.SettingsScreen
import com.schaubeck.waddeln.ui.StatsScreen

private enum class Screen { MENU, GAME, SETTINGS, STATS, ONLINE }

class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels()
    private val onlineViewModel: OnlineViewModel by viewModels()
    private val statsStore by lazy { StatsStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val online by onlineViewModel.ui.collectAsStateWithLifecycle()
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
                BackHandler(enabled = screen != Screen.MENU) { screen = Screen.MENU }

                when (screen) {
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
                    )

                    Screen.GAME -> GameScreen(
                        table = ui.table,
                        onAction = viewModel::onHumanAction,
                        onContinue = viewModel::onContinue,
                        onNoticeConfirmed = viewModel::onNoticeConfirmed,
                        onAskPartner = viewModel::onAskPartner,
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
                        if (game != null) {
                            GameScreen(
                                table = game,
                                onAction = onlineViewModel::act,
                                onContinue = onlineViewModel::ready,
                                onNoticeConfirmed = onlineViewModel::onNoticeConfirmed,
                                onAskPartner = onlineViewModel::askPartner,
                                onMenu = { screen = Screen.MENU },
                                onLeave = onlineViewModel::leave,
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
                }
            }
        }
    }
}
