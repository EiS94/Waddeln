package com.schaubeck.waddeln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schaubeck.waddeln.game.Difficulty
import com.schaubeck.waddeln.game.GameSpeed
import com.schaubeck.waddeln.game.Settings
import com.schaubeck.waddeln.game.TableStyle

/** Settings are applied and stored immediately. */
@Composable
fun SettingsScreen(settings: Settings, onChange: (Settings) -> Unit, onBack: () -> Unit) {
    WoodBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(BarGreen).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ Zurück", color = Color.White, fontSize = 16.sp) }
                Text("Einstellungen", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Section("Spielgeschwindigkeit") {
                    GameSpeed.entries.forEach { speed ->
                        Choice(speed.label, null, speed == settings.speed) { onChange(settings.copy(speed = speed)) }
                    }
                }
                Section("Schwierigkeitsgrad") {
                    Difficulty.entries.forEach { d ->
                        Choice(d.label, d.description, d == settings.difficulty) { onChange(settings.copy(difficulty = d)) }
                    }
                    Text("Dein Partner spielt immer in voller Stärke.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                Section("Spieltisch") {
                    TableStyle.entries.forEach { t ->
                        Choice(t.label, null, t == settings.table) { onChange(settings.copy(table = t)) }
                    }
                    Toggle("Selbst abheben", "Aus: Die App hebt für dich zufällig ab", settings.selfCut) {
                        onChange(settings.copy(selfCut = it))
                    }
                }
                Section("Ton") {
                    Toggle("Geräusche", "Mischen, Karten und Stiche", settings.soundEffects) {
                        onChange(settings.copy(soundEffects = it))
                    }
                    Toggle("Stimmen", "Die Spieler sprechen ihre Ansagen", settings.voices) {
                        onChange(settings.copy(voices = it))
                    }
                }
                Section("Namen der Spieler") {
                    val labels = listOf("Dein Name", "Gegner links", "Partner", "Gegner rechts")
                    for (seat in listOf(0, 2, 1, 3)) {
                        NameField(labels[seat], settings.names[seat], Settings.DEFAULT_NAMES[seat]) { name ->
                            onChange(settings.copy(names = settings.names.toMutableList().also { it[seat] = name }))
                        }
                    }
                }
                Section("Online") {
                    TextSetting(
                        label = "Server-Adresse",
                        value = settings.serverUrl,
                        placeholder = Settings.DEFAULT_SERVER_URL,
                        keyboardType = KeyboardType.Uri,
                        maxLength = 200,
                    ) { onChange(settings.copy(serverUrl = it)) }
                    TextButton(onClick = { onChange(settings.copy(serverUrl = Settings.DEFAULT_SERVER_URL)) }) {
                        Text("Standard wiederherstellen", color = Highlight)
                    }
                }
                Section("Datenschutz") {
                    Text(
                        "Gegen den Computer bleibt alles auf dem Gerät. Online werden nur Spitzname und eine zufällige Geräte-ID an den Server übertragen.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                    val uriHandler = LocalUriHandler.current
                    TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) {
                        Text("Datenschutzerklärung öffnen", color = Highlight)
                    }
                }
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Highlight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun Choice(label: String, description: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (selected) 1.5.dp else 0.dp, if (selected) Highlight else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = Highlight, unselectedColor = Color.White),
        )
        Column {
            Text(label, color = Color.White, fontSize = 16.sp)
            description?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
        }
    }
}

@Composable
fun Toggle(label: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 16.sp)
            description?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Highlight, checkedThumbColor = Color.White),
        )
    }
}

@Composable
fun NameField(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) =
    TextSetting(label, value, placeholder, onValueChange = onValueChange)

@Composable
fun TextSetting(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int = MAX_NAME_LENGTH,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Highlight,
            unfocusedBorderColor = Color.White.copy(alpha = 0.6f),
            focusedLabelColor = Highlight,
            unfocusedLabelColor = Color.White.copy(alpha = 0.8f),
            cursorColor = Highlight,
            focusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val MAX_NAME_LENGTH = 12

private const val PRIVACY_POLICY_URL = "https://github.com/EiS94/Waddeln/blob/main/PRIVACY.md"
