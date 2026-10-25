package com.schaubeck.waddeln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit

/** The rules of the game for players who do not know them (yet), in sections that fold open. */
@Composable
fun RulesScreen(onBack: () -> Unit) {
    WoodBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(BarGreen).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ Zurück", color = Color.White, fontSize = 16.sp) }
                Text("Spielregeln", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            // Index of the open section; one at a time keeps the page short.
            var open by rememberSaveable { mutableStateOf<Int?>(0) }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RULES.forEachIndexed { i, section ->
                    RuleSection(section, expanded = open == i) { open = if (open == i) null else i }
                }
            }
        }
    }
}

private sealed interface Block {
    /** A paragraph; `**bold**` marks terms. */
    data class Text(val text: String) : Block

    data class Bullets(val items: List<String>) : Block

    /** Example cards with a caption under each. */
    data class Cards(val cards: List<Pair<Card, String>>) : Block
}

private class Rules(val title: String, val blocks: List<Block>)

@Composable
private fun RuleSection(section: Rules, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.title, color = Highlight, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (expanded) "▲" else "▼", color = Highlight, fontSize = 14.sp)
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                section.blocks.forEach { block ->
                    when (block) {
                        is Block.Text -> RuleText(block.text)
                        is Block.Bullets -> block.items.forEach { item ->
                            Row {
                                Text("•", color = Highlight, fontSize = 15.sp, modifier = Modifier.width(16.dp))
                                RuleText(item)
                            }
                        }
                        is Block.Cards -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        ) {
                            block.cards.forEach { (card, caption) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
                                    CardView(card = card, width = 54.dp)
                                    Text(caption, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleText(text: String) {
    Text(bold(text), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 21.sp)
}

/** Turns `**term**` into bold text. */
private fun bold(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Highlight)) { append(part) } else append(part)
    }
}

private val GRAS_OBER = Card(Suit.GRAS, Rank.OBER)

private val RULES = listOf(
    Rules(
        "Worum geht's?",
        listOf(
            Block.Text(
                "Waddeln ist ein fränkisches Kartenspiel für **4 Spieler in 2 Teams**. Wer sich gegenübersitzt, " +
                    "spielt zusammen – in der App sitzt dein Partner oben, die Gegner links und rechts.",
            ),
            Block.Text(
                "Jede Runde („ein Spiel“) gewinnt das Team, das zuerst **3 Stiche** macht. Dafür gibt es Punkte. " +
                    "Wer zuerst **11 Punkte** hat, gewinnt das ganze Match.",
            ),
        ),
    ),
    Rules(
        "Die Karten",
        listOf(
            Block.Text(
                "Gespielt wird mit 32 fränkischen Karten: den vier Farben **Schell, Herz, Eichel und Gras** mit den " +
                    "Werten 7, 8, 9, 10, Unter, Ober, König und Ass (aufsteigend).",
            ),
            Block.Text("Drei Karten sind etwas Besonderes, die **Kritten**. Sie sind immer die stärksten Trümpfe:"),
            Block.Cards(listOf(Card.MAX to "Max\n(Herz König)", Card.BELLO to "Bello\n(Schell 7)", Card.SECHER to "Secher\n(Eichel 7)")),
            Block.Text("Der Max schlägt alles, dann kommt der Bello, dann der Secher."),
        ),
    ),
    Rules(
        "Der Haubi – was ist Trumpf?",
        listOf(
            Block.Text(
                "Zu Beginn jeder Runde wird ein **Schlag** (ein Wert, z. B. Ober) und eine **Farbe** (z. B. Gras) angesagt. " +
                    "Zusammen ergeben sie den **Haubi**, hier also den Gras Ober.",
            ),
            Block.Text("Trumpf sind dann, von stark nach schwach:"),
            Block.Bullets(
                listOf(
                    "die drei **Kritten** (Max, Bello, Secher),",
                    "der **Haubi** selbst,",
                    "die **Haubi-Schläge**: derselbe Wert in den anderen Farben – alle gleich stark,",
                    "die **Haubi-Farbe**: die übrigen Karten in der Farbe des Haubis, vom Ass abwärts.",
                ),
            ),
            Block.Cards(
                listOf(
                    GRAS_OBER to "Haubi",
                    Card(Suit.HERZ, Rank.OBER) to "Haubi-\nSchlag",
                    Card(Suit.SCHELL, Rank.OBER) to "Haubi-\nSchlag",
                    Card(Suit.GRAS, Rank.ASS) to "Haubi-\nFarbe",
                ),
            ),
            Block.Text(
                "Alle anderen Karten sind kein Trumpf. Sie können einen Stich nur gewinnen, wenn sie die Farbe der " +
                    "ersten Karte im Stich haben und kein Trumpf liegt.",
            ),
            Block.Text(
                "**Sonderfall:** Ergeben Schlag und Farbe eine Kritte (z. B. Eichel 7 = Secher), ist sie zugleich Haubi " +
                    "und behält ihren Kritten-Rang. Haubi-Schläge und Haubi-Farbe gelten ganz normal.",
            ),
        ),
    ),
    Rules(
        "Ablauf einer Runde",
        listOf(
            Block.Text(
                "**Geber:** Zuerst wird der Geber ausgelost, danach wandert das Amt jede Runde im Uhrzeigersinn weiter.",
            ),
            Block.Text(
                "**Abheben:** Der Spieler vor dem Geber (der **Abheber**) hebt den Stapel ab und schaut unter den " +
                    "abgehobenen Teil. Liegt dort eine Kritte, **muss** er sie nehmen – und die nächste auch, solange " +
                    "Kritten direkt übereinander liegen. Die anderen sehen, wie viele Kritten er genommen hat, aber nicht welche.",
            ),
            Block.Text(
                "**Ausgeben:** Jeder bekommt 5 Karten, erst 3, dann 2. Die Kritten des Abhebers zählen schon mit. " +
                    "12 Karten bleiben übrig; die unterste davon liegt offen und jeder kann sie sehen.",
            ),
            Block.Text(
                "**Haubi ansagen:** Der Spieler nach dem Geber sagt den **Schlag** an, der Geber die **Farbe**.",
            ),
            Block.Text(
                "**Schlagwechsel:** Statt den Schlag anzusagen, darf der Spieler nach dem Geber einmal pro Runde einen " +
                    "Schlagwechsel vorschlagen. Stimmt der Geber zu, sagt der Geber den Schlag und der Spieler nach ihm die Farbe.",
            ),
        ),
    ),
    Rules(
        "Stiche spielen",
        listOf(
            Block.Text(
                "Der Spieler **nach dem Geber kommt raus**, er spielt also die erste Karte. Dann folgen die anderen im " +
                    "Uhrzeigersinn. Wer den Stich gewinnt, spielt den nächsten an.",
            ),
            Block.Text(
                "Es gilt **freie Wahl**: Du musst weder Farbe bedienen noch stechen und darfst jede Karte spielen.",
            ),
            Block.Text("**Wer gewinnt den Stich?**"),
            Block.Bullets(
                listOf(
                    "Liegt Trumpf im Stich, gewinnt der stärkste Trumpf.",
                    "Sonst gewinnt die höchste Karte in der Farbe der ersten Karte.",
                    "Liegen zwei gleich starke Haubi-Schläge, gewinnt der zuerst gespielte.",
                ),
            ),
            Block.Text(
                "**Ausnahme – Bekennen:** Wird der Haubi als allererste Karte der Runde gespielt, müssen die anderen in " +
                    "diesem Stich eine Karte der Haubi-Farbe zugeben, wenn sie eine haben. Eine Kritte darf man trotzdem " +
                    "immer spielen – und sobald eine Kritte liegt, ist die Pflicht für den Rest des Stichs aufgehoben. " +
                    "Das gilt nur im ersten Stich.",
            ),
        ),
    ),
    Rules(
        "Schub",
        listOf(
            Block.Text(
                "Sobald der Haubi feststeht, darf jedes Team jederzeit **Schub** sagen: Es fragt die Gegner, ob sie gehen.",
            ),
            Block.Bullets(
                listOf(
                    "**Gehen:** Die Runde ist sofort vorbei, das fragende Team bekommt den bisherigen Wert.",
                    "**Bleiben:** Die Runde ist einen Punkt mehr wert und es wird weitergespielt.",
                ),
            ),
            Block.Text(
                "Die Teams fragen **abwechselnd** – zweimal hintereinander darf dasselbe Team nicht. Antworten darf jeder " +
                    "Spieler des gefragten Teams, die erste Antwort gilt. Solange ein Schub offen ist, wird keine Karte gespielt.",
            ),
            Block.Text(
                "Schub sagen darf ein Team nur, solange es mit dem Sieg in dieser Runde noch keine 11 Punkte hätte. " +
                    "Ein **gespanntes** Team (ab 9 Punkten) darf gar keinen Schub mehr sagen, aber antworten.",
            ),
        ),
    ),
    Rules(
        "Absprachen mit dem Partner",
        listOf(
            Block.Text("Partner dürfen sich kurz absprechen, ohne Karten zu verraten. Die Gegner hören mit."),
            Block.Text(
                "**Beim Schub:** Wird dein Team gefragt, kannst du deinen Partner fragen, wie gut seine Karten sind. " +
                    "Er antwortet grob: gar nichts, wenig, einen Stich, zwei Stiche oder alle (noch nötigen) Stiche. " +
                    "Einmal pro Schub.",
            ),
            Block.Text(
                "**„Schadder?“:** Wer den ersten Stich eröffnet, darf vorher fragen, ob es dem Partner schadet, wenn er " +
                    "den Haubi ausspielt – weil er dann eine Karte der Haubi-Farbe zugeben muss. Antworten: Ja, ein " +
                    "bisschen, Nein. Fragen darf man auch ohne Haubi, als **Bluff**. Hat der Partner den Haubi selbst, " +
                    "sagt er „Ja“, damit der Bluff nicht auffliegt.",
            ),
        ),
    ),
    Rules(
        "Punkte und Matchende",
        listOf(
            Block.Bullets(
                listOf(
                    "Wer 3 Stiche macht, gewinnt die Runde: **2 Punkte**, plus 1 für jedes „Bleiben“ beim Schub.",
                    "Geht ein Team beim Schub, bekommen die Fragenden den Wert vor der Frage.",
                    "Wer zuerst **11 Punkte** hat, gewinnt das Match.",
                    "Ab **9 Punkten** ist ein Team **gespannt** und darf keinen Schub mehr sagen.",
                ),
            ),
            Block.Text(
                "**Gespannt gegen gespannt:** Sind beide Teams gespannt, legt vor dem ersten Stich jeder seine Kritten und " +
                    "den Haubi verdeckt vor sich hin. So sieht jeder, wer wie viele gute Karten hat – aber nicht welche. " +
                    "Gespielt werden sie ganz normal.",
            ),
        ),
    ),
)
