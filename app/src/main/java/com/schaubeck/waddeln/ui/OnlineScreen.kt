package com.schaubeck.waddeln.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schaubeck.waddeln.online.ConnectionState
import com.schaubeck.waddeln.online.OnlineUi
import com.schaubeck.waddeln.protocol.BotStrength
import com.schaubeck.waddeln.protocol.ROOM_CODE_LENGTH
import com.schaubeck.waddeln.protocol.ServerMessage
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
                    Lobby(table, onTakeSeat, onBotStrength, onStart, onLeave)
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
    val ready = ui.connection == ConnectionState.CONNECTED && ui.greeted && name.isNotBlank()
    Section("Dein Spitzname") {
        NameField("Spitzname", name, "z. B. Eike", onNameChange)
        if (name.isBlank()) Text("Den sehen deine Mitspieler.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
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
    onStart: () -> Unit,
    onLeave: () -> Unit,
) {
    val room = table.room
    val isHost = room.seats[table.yourSeat].host
    Column(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Raum-Code", color = Color.White, fontSize = 14.sp)
        Text(room.code, color = Highlight, fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text("Gib den Code deinen Mitspielern.", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
    }
    Section("Plätze") {
        Text(
            "Gegenüber Sitzende spielen zusammen. Tippe auf einen freien Platz, um dich dorthin zu setzen.",
            color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        // Seats around the table, own team at the bottom.
        val me = table.yourSeat
        SeatBox(table, (me + 2) % 4, onTakeSeat, Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeatBox(table, (me + 1) % 4, onTakeSeat, Modifier.weight(1f))
            SeatBox(table, (me + 3) % 4, onTakeSeat, Modifier.weight(1f))
        }
        SeatBox(table, me, onTakeSeat, Modifier.fillMaxWidth(0.5f).align(Alignment.CenterHorizontally))
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
private fun SeatBox(table: ServerMessage.Table, seat: Int, onTakeSeat: (Int) -> Unit, modifier: Modifier) {
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
            Text(
                if (free) "frei (KI)" else info.name!!,
                color = if (free) Color.White.copy(alpha = 0.6f) else Color.White,
                fontSize = 15.sp,
                fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
            )
            val tags = buildList {
                if (mine) add("du")
                if (info.host) add("Gastgeber")
                if (info.isHuman && !info.connected) add("offline")
                if (seat != table.yourSeat && ourTeam) add("Partner")
            }
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = Highlight, fontSize = 11.sp)
        }
    }
}

private fun strengthLabel(strength: BotStrength) = when (strength) {
    BotStrength.EASY -> "Leicht"
    BotStrength.MEDIUM -> "Mittel"
    BotStrength.HARD -> "Schwer"
}
