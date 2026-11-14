package com.schaubeck.waddeln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schaubeck.waddeln.resources.*
import com.schaubeck.waddeln.rules.Card

@Composable
fun MainMenu(
    matchInProgress: Boolean,
    onContinue: () -> Unit,
    onNewGame: () -> Unit,
    onOnline: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onRules: () -> Unit,
) {
    WoodBackground {
        // Centred when it fits, scrollable with large fonts, small screens or in landscape.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            KrittenFan()
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(Res.string.app_name),
                color = Highlight,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            )
            Text(stringResource(Res.string.menu_subtitle), color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(40.dp))
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (matchInProgress) MenuButton(stringResource(Res.string.menu_continue), onContinue)
                MenuButton(stringResource(Res.string.menu_new_game), onNewGame, primary = !matchInProgress)
                MenuButton(stringResource(Res.string.menu_online), onOnline)
                MenuButton(stringResource(Res.string.menu_rules), onRules, primary = false)
                MenuButton(stringResource(Res.string.menu_stats), onStats, primary = false)
                MenuButton(stringResource(Res.string.menu_settings), onSettings, primary = false)
            }
        }
        }
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit, primary: Boolean = true) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    if (primary) {
        Button(onClick = onClick, colors = woodButton(), modifier = modifier) { Text(text, fontSize = 18.sp) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text, fontSize = 18.sp, color = Color.White) }
    }
}

/** Max, Bello and Secher fanned out as decoration. */
@Composable
private fun KrittenFan() {
    Box(Modifier.size(220.dp, 150.dp), contentAlignment = Alignment.Center) {
        listOf(Card.SECHER to -18f, Card.BELLO to 0f, Card.MAX to 18f).forEachIndexed { i, (card, angle) ->
            CardView(
                card = card,
                width = 72.dp,
                modifier = Modifier.offset(x = ((i - 1) * 46).dp, y = if (i == 1) (-6).dp else 6.dp).rotate(angle),
            )
        }
    }
}
