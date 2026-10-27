package com.schaubeck.waddeln.ui

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schaubeck.waddeln.R
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
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Color.White, fontSize = 16.sp) }
                Text(stringResource(R.string.rules_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
    data class Text(@StringRes val text: Int) : Block

    data class Bullets(val items: List<Int>) : Block

    /** Example cards with a caption under each. */
    data class Cards(val cards: List<Pair<Card, Int>>) : Block
}

private class Rules(@StringRes val title: Int, val blocks: List<Block>)

@Composable
private fun RuleSection(section: Rules, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(section.title), color = Highlight, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                                    Text(stringResource(caption), color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
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
private fun RuleText(@StringRes text: Int) {
    Text(bold(stringResource(text)), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 21.sp)
}

/** Turns `**term**` into bold text. */
private fun bold(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Highlight)) { append(part) } else append(part)
    }
}

private val GRAS_OBER = Card(Suit.GRAS, Rank.OBER)

/** The texts are in strings.xml (rules_*). */
private val RULES = listOf(
    Rules(
        R.string.rules_intro_title,
        listOf(
            Block.Text(R.string.rules_intro_1),
            Block.Text(R.string.rules_intro_2),
        ),
    ),
    Rules(
        R.string.rules_cards_title,
        listOf(
            Block.Text(R.string.rules_cards_1),
            Block.Text(R.string.rules_cards_2),
            Block.Cards(
                listOf(
                    Card.MAX to R.string.rules_cards_max,
                    Card.BELLO to R.string.rules_cards_bello,
                    Card.SECHER to R.string.rules_cards_secher,
                ),
            ),
            Block.Text(R.string.rules_cards_3),
        ),
    ),
    Rules(
        R.string.rules_haubi_title,
        listOf(
            Block.Text(R.string.rules_haubi_1),
            Block.Text(R.string.rules_haubi_2),
            Block.Bullets(
                listOf(
                    R.string.rules_haubi_bullet_1,
                    R.string.rules_haubi_bullet_2,
                    R.string.rules_haubi_bullet_3,
                    R.string.rules_haubi_bullet_4,
                ),
            ),
            Block.Cards(
                listOf(
                    GRAS_OBER to R.string.rules_haubi_card_haubi,
                    Card(Suit.HERZ, Rank.OBER) to R.string.rules_haubi_card_schlag,
                    Card(Suit.SCHELL, Rank.OBER) to R.string.rules_haubi_card_schlag,
                    Card(Suit.GRAS, Rank.ASS) to R.string.rules_haubi_card_farbe,
                ),
            ),
            Block.Text(R.string.rules_haubi_3),
            Block.Text(R.string.rules_haubi_4),
        ),
    ),
    Rules(
        R.string.rules_round_title,
        listOf(
            Block.Text(R.string.rules_round_1),
            Block.Text(R.string.rules_round_2),
            Block.Text(R.string.rules_round_2b),
            Block.Text(R.string.rules_round_3),
            Block.Text(R.string.rules_round_4),
            Block.Text(R.string.rules_round_5),
        ),
    ),
    Rules(
        R.string.rules_tricks_title,
        listOf(
            Block.Text(R.string.rules_tricks_1),
            Block.Text(R.string.rules_tricks_2),
            Block.Text(R.string.rules_tricks_3),
            Block.Bullets(
                listOf(
                    R.string.rules_tricks_bullet_1,
                    R.string.rules_tricks_bullet_2,
                    R.string.rules_tricks_bullet_3,
                ),
            ),
            Block.Text(R.string.rules_tricks_4),
        ),
    ),
    Rules(
        R.string.rules_schub_title,
        listOf(
            Block.Text(R.string.rules_schub_1),
            Block.Bullets(
                listOf(
                    R.string.rules_schub_bullet_1,
                    R.string.rules_schub_bullet_2,
                ),
            ),
            Block.Text(R.string.rules_schub_2),
            Block.Text(R.string.rules_schub_3),
        ),
    ),
    Rules(
        R.string.rules_partner_title,
        listOf(
            Block.Text(R.string.rules_partner_1),
            Block.Text(R.string.rules_partner_2),
            Block.Text(R.string.rules_partner_3),
            Block.Text(R.string.rules_partner_4),
        ),
    ),
    Rules(
        R.string.rules_points_title,
        listOf(
            Block.Bullets(
                listOf(
                    R.string.rules_points_bullet_1,
                    R.string.rules_points_bullet_2,
                    R.string.rules_points_bullet_3,
                    R.string.rules_points_bullet_4,
                ),
            ),
            Block.Text(R.string.rules_points_1),
        ),
    ),
)
