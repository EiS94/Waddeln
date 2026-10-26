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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.game.Counter
import com.schaubeck.waddeln.game.Stats
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import java.util.Locale

private enum class StatsTab(@StringRes val label: Int) {
    ALL(R.string.stats_tab_all),
    OFFLINE(R.string.stats_tab_offline),
    ONLINE(R.string.stats_tab_online),
}

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
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Color.White, fontSize = 16.sp) }
                Text(stringResource(R.string.stats_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsTab.entries.forEach { TabChip(stringResource(it.label), it == tab, Modifier.weight(1f)) { tab = it } }
                }
                if (stats.isEmpty) {
                    Text(
                        stringResource(R.string.stats_empty),
                        color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(16.dp),
                    )
                } else {
                    StatsContent(stats, showStreak = tab != StatsTab.ALL)
                }
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stats_reset), color = Color.White)
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Color(0xFF2B1F14),
            title = { Text(stringResource(R.string.stats_reset_title), color = Highlight) },
            text = { Text(stringResource(R.string.stats_reset_text), color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text(stringResource(R.string.stats_reset_confirm), color = Highlight) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel), color = Color.White) } },
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
    Section(stringResource(R.string.stats_matches)) {
        StatRow(R.string.stats_played, s[Counter.MATCHES].toString())
        StatRow(R.string.stats_won, withShare(s[Counter.MATCHES_WON], s[Counter.MATCHES]))
        StatRow(R.string.stats_best_streak, s[Counter.BEST_STREAK].toString())
        if (showStreak) StatRow(R.string.stats_streak, s[Counter.STREAK].toString())
    }
    Section(stringResource(R.string.stats_rounds)) {
        StatRow(R.string.stats_played, rounds.toString())
        StatRow(R.string.stats_won, withShare(s[Counter.ROUNDS_WON], rounds))
        StatRow(R.string.stats_points, stringResource(R.string.stats_points_value, s[Counter.POINTS_WON], s[Counter.POINTS_LOST]))
        StatRow(R.string.stats_best_round, s[Counter.BEST_ROUND].toString())
        StatRow(R.string.stats_won_to_nil, s[Counter.WON_TO_NIL].toString())
    }
    Section(stringResource(R.string.stats_tricks)) {
        StatRow(R.string.stats_own_tricks, s[Counter.OWN_TRICKS].toString())
        StatRow(R.string.stats_per_round, average(s[Counter.OWN_TRICKS], rounds))
    }
    Section(stringResource(R.string.stats_announcements)) {
        StatRow(R.string.stats_schlag_called, s[Counter.SCHLAG_CALLED].toString())
        StatRow(R.string.stats_of_which_won, withShare(s[Counter.SCHLAG_WON], s[Counter.SCHLAG_CALLED]))
        favourite(Rank.entries, s::schlag)?.let { StatRow(R.string.stats_favourite_schlag, stringResource(Texts.rank(it))) }
        StatRow(R.string.stats_farbe_called, s[Counter.FARBE_CALLED].toString())
        StatRow(R.string.stats_of_which_won, withShare(s[Counter.FARBE_WON], s[Counter.FARBE_CALLED]))
        favourite(Suit.entries, s::farbe)?.let { StatRow(R.string.stats_favourite_farbe, stringResource(Texts.suit(it))) }
    }
    Section(stringResource(R.string.stats_schub)) {
        StatRow(R.string.stats_schub_called, s[Counter.SCHUB_CALLED].toString())
        StatRow(R.string.stats_schub_went, withShare(s[Counter.SCHUB_WENT], s[Counter.SCHUB_CALLED]))
        StatRow(R.string.stats_schub_stayed, s[Counter.SCHUB_STAYED].toString())
        val faced = s[Counter.FACED_STAYED] + s[Counter.FACED_WENT]
        StatRow(R.string.stats_faced, faced.toString())
        StatRow(R.string.stats_faced_went, withShare(s[Counter.FACED_WENT], faced))
        StatRow(R.string.stats_faced_stayed, withShare(s[Counter.FACED_STAYED], faced))
        StatRow(R.string.stats_faced_stayed_won, withShare(s[Counter.FACED_STAYED_WON], s[Counter.FACED_STAYED]))
    }
    Section(stringResource(R.string.stats_your_cards)) {
        StatRow(R.string.stats_haubi_held, withShare(s[Counter.HAUBI_HELD], rounds))
        StatRow(R.string.stats_max_held, withShare(s[Counter.MAX_HELD], rounds))
        StatRow(R.string.stats_bello_held, withShare(s[Counter.BELLO_HELD], rounds))
        StatRow(R.string.stats_secher_held, withShare(s[Counter.SECHER_HELD], rounds))
        StatRow(R.string.stats_trumps_held, average(s[Counter.TRUMPS_HELD], rounds))
    }
}

@Composable
private fun StatRow(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Highlight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** "7 (35 %)"; without share while [total] is 0. */
@Composable
private fun withShare(count: Int, total: Int): String =
    if (total == 0) count.toString() else stringResource(R.string.stats_share, count, count * 100 / total)

@Composable
private fun average(sum: Int, count: Int): String =
    if (count == 0) stringResource(R.string.stats_no_average) else String.format(Locale.GERMAN, "%.1f", sum.toDouble() / count)

/** The most frequent entry, or null if none was counted yet. */
private fun <T> favourite(entries: List<T>, count: (T) -> Int): T? =
    entries.maxBy(count).takeIf { count(it) > 0 }
