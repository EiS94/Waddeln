package com.schaubeck.waddeln.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.online.ConnectionState
import com.schaubeck.waddeln.online.OnlineUi
import com.schaubeck.waddeln.protocol.BotStrength
import com.schaubeck.waddeln.protocol.MAX_NAME_LENGTH
import com.schaubeck.waddeln.protocol.ROOM_CODE_LENGTH
import com.schaubeck.waddeln.protocol.ServerMessage
import com.schaubeck.waddeln.protocol.inviteLink
import com.schaubeck.waddeln.rules.teamOf

/** Before the match: enter a nickname and create or join a room; then the lobby of the room. */
@Composable
fun OnlineScreen(
    ui: OnlineUi,
    name: String,
    serverUrl: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onTakeSeat: (Int) -> Unit,
    onBotStrength: (BotStrength) -> Unit,
    onBotName: (seat: Int, name: String) -> Unit,
    onStart: () -> Unit,
    onLeave: () -> Unit,
    onBack: () -> Unit,
) {
    WoodBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(BarGreen).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Color.White, fontSize = 16.sp) }
                Text(stringResource(R.string.online_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ConnectionLine(ui.connection, serverUrl)
                ui.error?.let { ErrorLine(it) }
                val table = ui.table
                if (table == null) {
                    Home(ui, name, onNameChange, onCreate, onJoin)
                } else {
                    Lobby(table, onTakeSeat, onBotStrength, onBotName, onStart, onLeave)
                }
            }
        }
    }
}

@Composable
private fun ConnectionLine(connection: ConnectionState, serverUrl: String) {
    val (text, color) = when (connection) {
        ConnectionState.OFF, ConnectionState.CONNECTING -> stringResource(R.string.online_connecting) to Color.White
        ConnectionState.CONNECTED -> stringResource(R.string.online_connected) to Color(0xFFA5D6A7)
        ConnectionState.LOST -> stringResource(R.string.online_lost) to Color(0xFFFFAB91)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connection != ConnectionState.CONNECTED) {
                CircularProgressIndicator(color = Highlight, strokeWidth = 2.dp, modifier = Modifier.height(16.dp))
            }
            Text(text, color = color, fontSize = 14.sp)
        }
        if (connection == ConnectionState.LOST) {
            Text(stringResource(R.string.online_server, serverUrl), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().background(SchubOrange, RoundedCornerShape(8.dp)).padding(10.dp),
    )
}

@Composable
private fun Home(
    ui: OnlineUi,
    name: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    // A code from an invitation link waits here until the player has a nickname.
    LaunchedEffect(ui.invite) { ui.invite?.let { code = it } }
    val ready = ui.connection == ConnectionState.CONNECTED && ui.greeted && name.isNotBlank()
    Section(stringResource(R.string.online_nickname_title)) {
        NameField(stringResource(R.string.online_nickname), name, stringResource(R.string.online_nickname_placeholder), onNameChange)
        if (name.isBlank()) {
            val hint = stringResource(if (ui.invite != null) R.string.online_nickname_invited else R.string.online_nickname_hint)
            Text(hint, color = if (ui.invite != null) Highlight else Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
    }
    Section(stringResource(R.string.online_new_room)) {
        Text(
            stringResource(R.string.online_new_room_text),
            color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onCreate, enabled = ready, colors = woodButton(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.online_create_room), fontSize = 17.sp)
        }
    }
    Section(stringResource(R.string.online_join_room)) {
        TextSetting(stringResource(R.string.online_room_code), code, stringResource(R.string.online_room_code_placeholder), keyboardType = KeyboardType.Ascii, maxLength = ROOM_CODE_LENGTH) {
            code = it.uppercase().filter(Char::isLetterOrDigit)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onJoin(code) },
            enabled = ready && code.length == ROOM_CODE_LENGTH,
            colors = woodButton(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(stringResource(R.string.online_join), fontSize = 17.sp) }
    }
}

@Composable
private fun Lobby(
    table: ServerMessage.Table,
    onTakeSeat: (Int) -> Unit,
    onBotStrength: (BotStrength) -> Unit,
    onBotName: (seat: Int, name: String) -> Unit,
    onStart: () -> Unit,
    onLeave: () -> Unit,
) {
    val room = table.room
    val isHost = room.seats[table.yourSeat].host
    var renaming by rememberSaveable { mutableStateOf<Int?>(null) }
    renaming?.let { seat ->
        BotNameDialog(
            current = room.seats[seat].botName.orEmpty(),
            onDone = { name ->
                name?.let { onBotName(seat, it) }
                renaming = null
            },
        )
    }
    // Only the host renames bots (pencil next to the name).
    val onRename: ((Int) -> Unit)? = if (isHost) ({ renaming = it }) else null
    Column(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val context = LocalContext.current
        Text(stringResource(R.string.online_room_code), color = Color.White, fontSize = 14.sp)
        Text(
            room.code,
            color = Highlight,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { copyRoomCode(context, room.code) },
        )
        Text(stringResource(R.string.online_room_code_text), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyRoomCode(context, room.code) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.online_copy), color = Color.White)
            }
            Button(onClick = { shareInvite(context, room.code) }, colors = woodButton(), modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.online_invite))
            }
        }
    }
    Section(stringResource(R.string.online_seats)) {
        Text(
            stringResource(if (isHost) R.string.online_seats_text_host else R.string.online_seats_text),
            color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        // Seats around the table, own team at the bottom.
        val me = table.yourSeat
        SeatBox(table, (me + 2) % 4, onTakeSeat, onRename, Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeatBox(table, (me + 1) % 4, onTakeSeat, onRename, Modifier.weight(1f))
            SeatBox(table, (me + 3) % 4, onTakeSeat, onRename, Modifier.weight(1f))
        }
        SeatBox(table, me, onTakeSeat, onRename, Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally))
    }
    if (isHost) {
        Section(stringResource(R.string.online_bot_strength)) {
            BotStrength.entries.forEach { strength ->
                Choice(stringResource(strengthLabel(strength)), null, strength == room.botStrength) { onBotStrength(strength) }
            }
        }
        Button(onClick = onStart, colors = woodButton(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.online_start), fontSize = 18.sp)
        }
    } else {
        Text(
            stringResource(
                R.string.online_waiting_for_host,
                stringResource(strengthLabel(room.botStrength)),
                room.seats.firstOrNull { it.host }?.name ?: stringResource(R.string.online_the_host),
            ),
            color = Highlight, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_leave_room), color = Color.White) }
}

@Composable
private fun SeatBox(
    table: ServerMessage.Table,
    seat: Int,
    onTakeSeat: (Int) -> Unit,
    onRename: ((Int) -> Unit)?,
    modifier: Modifier,
) {
    val info = table.room.seats[seat]
    val mine = seat == table.yourSeat
    val ourTeam = teamOf(seat) == teamOf(table.yourSeat)
    val shape = RoundedCornerShape(10.dp)
    val free = !info.isHuman
    val isHost = table.room.seats[table.yourSeat].host
    Box(
        modifier = modifier
            .padding(vertical = 3.dp)
            .background(if (ourTeam) BarGreen.copy(alpha = 0.7f) else Color(0x66000000), shape)
            .border(if (mine) 2.dp else 1.dp, if (mine) Highlight else Color.White.copy(alpha = 0.4f), shape)
            // The host swaps with another human by tapping their seat.
            .then(if (!mine && (free || isHost)) Modifier.clickable { onTakeSeat(seat) } else Modifier)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (free) info.botName ?: stringResource(R.string.online_seat_free) else info.name!!,
                    color = if (free) Color.White.copy(alpha = 0.75f) else Color.White,
                    fontSize = 15.sp,
                    fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                )
                if (free && onRename != null) {
                    Text(
                        "✎",
                        color = Highlight,
                        fontSize = 17.sp,
                        modifier = Modifier.clickable { onRename(seat) }.padding(start = 8.dp, end = 2.dp),
                    )
                }
            }
            val tags = buildList {
                if (free) add(stringResource(R.string.online_tag_bot))
                if (mine) add(stringResource(R.string.online_tag_you))
                if (info.host) add(stringResource(R.string.online_tag_host))
                if (info.isHuman && !info.connected) add(stringResource(R.string.online_tag_offline))
                if (seat != table.yourSeat && ourTeam) add(stringResource(R.string.online_tag_partner))
            }
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = Highlight, fontSize = 11.sp)
        }
    }
}

/** An invitation to [invite] arrived while we sit in room [current]. */
@Composable
fun SwitchRoomDialog(current: String, invite: String, inGame: Boolean, onSwitch: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        containerColor = Color(0xFF2B1F14),
        title = { Text(stringResource(R.string.online_switch_title, invite), color = Highlight) },
        text = {
            Text(
                stringResource(if (inGame) R.string.online_switch_text_in_game else R.string.online_switch_text, current, invite),
                color = Color.White,
            )
        },
        confirmButton = { TextButton(onClick = onSwitch) { Text(stringResource(R.string.online_switch), color = Highlight) } },
        dismissButton = { TextButton(onClick = onStay) { Text(stringResource(R.string.online_stay), color = Color.White) } },
    )
}

/** Asks the host for a new bot name; [onDone] gets null when cancelled. */
@Composable
private fun BotNameDialog(current: String, onDone: (String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = Color(0xFF2B1F14),
        title = { Text(stringResource(R.string.online_bot_name_title), color = Highlight) },
        text = { TextSetting(stringResource(R.string.online_bot_name), name, current, maxLength = MAX_NAME_LENGTH) { name = it } },
        confirmButton = {
            TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_ok), color = Highlight) }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.common_cancel), color = Color.White) }
        },
    )
}

private fun copyRoomCode(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.online_room_code), code))
    // From Android 13 on, the system confirms the copy itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.online_copied, Toast.LENGTH_SHORT).show()
    }
}

/** Opens the share sheet with a link that opens the room in the app. */
private fun shareInvite(context: Context, code: String) {
    val text = context.getString(R.string.online_invite_text, code, inviteLink(code))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.online_invite_chooser)))
}

@StringRes
private fun strengthLabel(strength: BotStrength): Int = when (strength) {
    BotStrength.EASY -> R.string.difficulty_easy
    BotStrength.MEDIUM -> R.string.difficulty_medium
    BotStrength.HARD -> R.string.difficulty_hard
}
