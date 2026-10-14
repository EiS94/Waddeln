package com.schaubeck.waddeln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schaubeck.waddeln.game.Counter
import com.schaubeck.waddeln.game.Stats
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import java.util.Locale

private enum class StatsTab(val label: String) { ALL("Gesamt"), OFFLINE("Offline"), ONLINE("Online") }

/** Statistics of the own games, offline and online. */
@Composable
fun StatsScreen(offline: Stats, online: Stats, onReset: () -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(StatsTab.ALL) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val stats = when (tab) {
        StatsTab.ALL -> offline + online
        StatsTab.OFFLINE -> offline
        StatsTab.ONLINE -> online
    }
    WoodBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(BarGreen).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ Zurück", color = Color.White, fontSize = 16.sp) }
                Text("Statistik", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsTab.entries.forEach { TabChip(it.label, it == tab, Modifier.weight(1f)) { tab = it } }
                }
                if (stats.isEmpty) {
                    Text(
                        "Noch keine Spiele gezählt. Gezählt wird jedes Spiel, das du bis zum Ende spielst.",
                        color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(16.dp),
                    )
                } else {
                    StatsContent(stats, showStreak = tab != StatsTab.ALL)
                }
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Statistik zurücksetzen", color = Color.White)
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Color(0xFF2B1F14),
            title = { Text("Statistik zurücksetzen?", color = Highlight) },
            text = { Text("Alle Zahlen (offline und online) werden gelöscht.", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text("Zurücksetzen", color = Highlight) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Abbrechen", color = Color.White) } },
        )
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .background(if (selected) WoodBrown else Panel, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Highlight else Color.White.copy(alpha = 0.3f), shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Highlight else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatsContent(s: Stats, showStreak: Boolean) {
    val rounds = s[Counter.ROUNDS]
    Section("Matches") {
        StatRow("Gespielt", s[Counter.MATCHES].toString())
        StatRow("Gewonnen", withShare(s[Counter.MATCHES_WON], s[Counter.MATCHES]))
        StatRow("Längste Siegesserie", s[Counter.BEST_STREAK].toString())
        if (showStreak) StatRow("Aktuelle Siegesserie", s[Counter.STREAK].toString())
    }
    Section("Spiele") {
        StatRow("Gespielt", rounds.toString())
        StatRow("Gewonnen", withShare(s[Counter.ROUNDS_WON], rounds))
        StatRow("Punkte geholt : abgegeben", "${s[Counter.POINTS_WON]} : ${s[Counter.POINTS_LOST]}")
        StatRow("Meiste Punkte in einem Spiel", s[Counter.BEST_ROUND].toString())
        StatRow("Zu null gewonnen (3:0)", s[Counter.WON_TO_NIL].toString())
    }
    Section("Stiche") {
        StatRow("Selbst gemacht", s[Counter.OWN_TRICKS].toString())
        StatRow("Pro Spiel", average(s[Counter.OWN_TRICKS], rounds))
    }
    Section("Ansagen") {
        StatRow("Schlag angesagt", s[Counter.SCHLAG_CALLED].toString())
        StatRow("… davon gewonnen", withShare(s[Counter.SCHLAG_WON], s[Counter.SCHLAG_CALLED]))
        favourite(Rank.entries, s::schlag)?.let { StatRow("Lieblingsschlag", Texts.rank(it)) }
        StatRow("Farbe angesagt", s[Counter.FARBE_CALLED].toString())
        StatRow("… davon gewonnen", withShare(s[Counter.FARBE_WON], s[Counter.FARBE_CALLED]))
        favourite(Suit.entries, s::farbe)?.let { StatRow("Lieblingsfarbe", Texts.suit(it)) }
    }
    Section("Schub") {
        StatRow("Du hast Schub gesagt", s[Counter.SCHUB_CALLED].toString())
        StatRow("… Gegner gegangen", withShare(s[Counter.SCHUB_WENT], s[Counter.SCHUB_CALLED]))
        StatRow("… Gegner geblieben", s[Counter.SCHUB_STAYED].toString())
        val faced = s[Counter.FACED_STAYED] + s[Counter.FACED_WENT]
        StatRow("Schub der Gegner", faced.toString())
        StatRow("… ihr seid gegangen", withShare(s[Counter.FACED_WENT], faced))
        StatRow("… ihr seid geblieben", withShare(s[Counter.FACED_STAYED], faced))
        StatRow("… geblieben und gewonnen", withShare(s[Counter.FACED_STAYED_WON], s[Counter.FACED_STAYED]))
    }
    Section("Deine Karten") {
        StatRow("Haubi auf der Hand", withShare(s[Counter.HAUBI_HELD], rounds))
        StatRow("Max auf der Hand", withShare(s[Counter.MAX_HELD], rounds))
        StatRow("Bello auf der Hand", withShare(s[Counter.BELLO_HELD], rounds))
        StatRow("Secher auf der Hand", withShare(s[Counter.SECHER_HELD], rounds))
        StatRow("Trümpfe pro Spiel", average(s[Counter.TRUMPS_HELD], rounds))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Highlight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** "7 (35 %)"; without share while [total] is 0. */
private fun withShare(count: Int, total: Int): String =
    if (total == 0) count.toString() else "$count (${count * 100 / total} %)"

private fun average(sum: Int, count: Int): String =
    if (count == 0) "–" else String.format(Locale.GERMAN, "%.1f", sum.toDouble() / count)

/** The most frequent entry, or null if none was counted yet. */
private fun <T> favourite(entries: List<T>, count: (T) -> Int): T? =
    entries.maxBy(count).takeIf { count(it) > 0 }
