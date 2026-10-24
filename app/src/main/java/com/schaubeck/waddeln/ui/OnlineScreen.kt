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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                TextButton(onClick = onBack) { Text("‹ Zurück", color = Color.White, fontSize = 16.sp) }
                Text("Online spielen", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
        ConnectionState.OFF, ConnectionState.CONNECTING -> "Verbinde mit dem Server …" to Color.White
        ConnectionState.CONNECTED -> "Mit dem Server verbunden" to Color(0xFFA5D6A7)
        ConnectionState.LOST -> "Keine Verbindung – neuer Versuch läuft …" to Color(0xFFFFAB91)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connection != ConnectionState.CONNECTED) {
                CircularProgressIndicator(color = Highlight, strokeWidth = 2.dp, modifier = Modifier.height(16.dp))
            }
            Text(text, color = color, fontSize = 14.sp)
        }
        if (connection == ConnectionState.LOST) {
            Text("Server: $serverUrl (änderbar in den Einstellungen)", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
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
    Section("Dein Spitzname") {
        NameField("Spitzname", name, "z. B. Schorsch", onNameChange)
        if (name.isBlank()) {
            val hint = if (ui.invite != null) "Du bist eingeladen: Gib deinen Spitznamen ein und tippe unten auf „Beitreten“." else "Den sehen deine Mitspieler."
            Text(hint, color = if (ui.invite != null) Highlight else Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
    }
    Section("Neuer Raum") {
        Text(
            "Du bekommst einen Code, den du deinen Mitspielern gibst. Freie Plätze übernimmt die KI.",
            color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onCreate, enabled = ready, colors = woodButton(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Raum erstellen", fontSize = 17.sp)
        }
    }
    Section("Raum beitreten") {
        TextSetting("Raum-Code", code, "z. B. K7QMX", keyboardType = KeyboardType.Ascii, maxLength = ROOM_CODE_LENGTH) {
            code = it.uppercase().filter(Char::isLetterOrDigit)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onJoin(code) },
            enabled = ready && code.length == ROOM_CODE_LENGTH,
            colors = woodButton(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Beitreten", fontSize = 17.sp) }
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
        Text("Raum-Code", color = Color.White, fontSize = 14.sp)
        Text(
            room.code,
            color = Highlight,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { copyRoomCode(context, room.code) },
        )
        Text("Gib den Code deinen Mitspielern oder schick ihnen eine Einladung.", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyRoomCode(context, room.code) }, modifier = Modifier.weight(1f)) {
                Text("Kopieren", color = Color.White)
            }
            Button(onClick = { shareInvite(context, room.code) }, colors = woodButton(), modifier = Modifier.weight(1f)) {
                Text("Einladen")
            }
        }
    }
    Section("Plätze") {
        Text(
            "Gegenüber Sitzende spielen zusammen. Tippe auf einen KI-Platz, um dich dorthin zu setzen." +
                if (isHost) " Mit dem Stift gibst du einer KI einen anderen Namen." else "",
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
        Section("Stärke der KI") {
            BotStrength.entries.forEach { strength ->
                Choice(strengthLabel(strength), null, strength == room.botStrength) { onBotStrength(strength) }
            }
        }
        Button(onClick = onStart, colors = woodButton(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Spiel starten", fontSize = 18.sp)
        }
    } else {
        Text(
            "KI: ${strengthLabel(room.botStrength)} · Warte, bis ${room.seats.firstOrNull { it.host }?.name ?: "der Gastgeber"} das Spiel startet …",
            color = Highlight, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Raum verlassen", color = Color.White) }
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
    Box(
        modifier = modifier
            .padding(vertical = 3.dp)
            .background(if (ourTeam) BarGreen.copy(alpha = 0.7f) else Color(0x66000000), shape)
            .border(if (mine) 2.dp else 1.dp, if (mine) Highlight else Color.White.copy(alpha = 0.4f), shape)
            .then(if (free) Modifier.clickable { onTakeSeat(seat) } else Modifier)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (free) info.botName ?: "frei" else info.name!!,
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
                if (free) add("KI")
                if (mine) add("du")
                if (info.host) add("Gastgeber")
                if (info.isHuman && !info.connected) add("offline")
                if (seat != table.yourSeat && ourTeam) add("Partner")
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
        title = { Text("Einladung in Raum $invite", color = Highlight) },
        text = {
            Text(
                "Du sitzt gerade in Raum $current." +
                    (if (inGame) " Wenn du gehst, spielt dort die KI für dich weiter." else "") +
                    " Möchtest du in Raum $invite wechseln?",
                color = Color.White,
            )
        },
        confirmButton = { TextButton(onClick = onSwitch) { Text("Wechseln", color = Highlight) } },
        dismissButton = { TextButton(onClick = onStay) { Text("Bleiben", color = Color.White) } },
    )
}

/** Asks the host for a new bot name; [onDone] gets null when cancelled. */
@Composable
private fun BotNameDialog(current: String, onDone: (String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = Color(0xFF2B1F14),
        title = { Text("Name der KI", color = Highlight) },
        text = { TextSetting("Name", name, current, maxLength = MAX_NAME_LENGTH) { name = it } },
        confirmButton = {
            TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("OK", color = Highlight) }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text("Abbrechen", color = Color.White) }
        },
    )
}

private fun copyRoomCode(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Raum-Code", code))
    // From Android 13 on, the system confirms the copy itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Raum-Code kopiert", Toast.LENGTH_SHORT).show()
    }
}

/** Opens the share sheet with a link that opens the room in the app. */
private fun shareInvite(context: Context, code: String) {
    val text = "Spiel mit mir Waddeln! Tipp auf den Link oder gib in der App bei „Raum beitreten“ den Code $code ein: ${inviteLink(code)}"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Zu Waddeln einladen"))
}

private fun strengthLabel(strength: BotStrength) = when (strength) {
    BotStrength.EASY -> "Leicht"
    BotStrength.MEDIUM -> "Mittel"
    BotStrength.HARD -> "Schwer"
}
