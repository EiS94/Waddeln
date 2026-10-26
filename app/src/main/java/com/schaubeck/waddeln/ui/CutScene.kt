package com.schaubeck.waddeln.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.game.CutUi
import com.schaubeck.waddeln.game.TableUi
import com.schaubeck.waddeln.protocol.CutAnimation
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Deal
import kotlinx.coroutines.delay

private const val DECK_SIZE = 32

/**
 * The Abheber cuts before the deal, in the middle of the table. While they choose, the deck lies
 * spread out (we choose by tapping or dragging, [onCut]); then the lifted stack is set apart, the
 * Kritten at its bottom are taken one after the other (face down for everybody but the Abheber)
 * and the first other card is turned over. [onCutSeen] skips the rest of the animation.
 */
@Composable
fun CutScene(table: TableUi, cut: CutUi, onCut: (Int) -> Unit, onCutSeen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .widthIn(max = 340.dp)
            .background(Color(0xE62B1F14), shape)
            .border(2.dp, Highlight, shape)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val name = table.names[cut.abheber]
        val mine = cut.abheber == table.me
        val lifted = cut.cut
        if (lifted == null) {
            Text(if (mine) stringResource(R.string.cut_you_cut) else stringResource(R.string.cut_other_cuts, name), color = Highlight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (cut.choosing) ChooseCut(table, onCut) else Spread(selected = null, onSelect = null)
        } else {
            Text(
                if (mine) stringResource(R.string.cut_you_lifted, lifted) else stringResource(R.string.cut_other_lifted, name, lifted),
                color = Highlight, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Reveal(cut, lifted, name, mine, onCutSeen)
        }
    }
}

/** The spread deck to pick the cut from, the number of lifted cards and the button. */
@Composable
private fun ChooseCut(table: TableUi, onCut: (Int) -> Unit) {
    var selected by remember { mutableIntStateOf(DECK_SIZE / 2) }
    Text(
        stringResource(R.string.cut_instructions),
        color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Spread(selected, onSelect = { selected = it })
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.cut_selected, selected), color = Color.White, fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    Button(onClick = { onCut(selected) }, colors = woodButton()) { Text(stringResource(R.string.cut_button), fontSize = 16.sp) }
    secondsUntil(table.deadline)?.let {
        Text(stringResource(R.string.cut_countdown, it), color = Highlight, fontSize = 12.sp)
    }
}

/**
 * All 32 cards face down from left (top card) to right. The first [selected] cards are raised:
 * they would be lifted. [onSelect] (if given) picks the cut by tapping or dragging.
 */
@Composable
private fun Spread(selected: Int?, onSelect: ((Int) -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = 40.dp
        val raise = 14.dp
        val step = (maxWidth - cardWidth) / (DECK_SIZE - 1)
        val density = LocalDensity.current
        val pick = { x: Float ->
            val stepPx = with(density) { step.toPx() }
            val index = ((x - with(density) { cardWidth.toPx() } / 2) / stepPx).toInt() + 1
            onSelect?.invoke(index.coerceIn(Deal.CUT_RANGE))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(cardWidth * CARD_ASPECT + raise)
                .then(
                    if (onSelect == null) {
                        Modifier
                    } else {
                        Modifier
                            .pointerInput(Unit) { detectTapGestures { pick(it.x) } }
                            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> pick(change.position.x) } }
                    },
                ),
        ) {
            repeat(DECK_SIZE) { i ->
                val up = selected != null && i < selected
                val y by animateDpAsState(if (up) 0.dp else raise, tween(120), label = "lift")
                CardBack(
                    width = cardWidth,
                    modifier = Modifier.offset(x = step * i, y = y).zIndex(i.toFloat()),
                )
            }
            if (selected != null) {
                // A thin golden line where the deck is cut.
                Box(
                    Modifier
                        .offset(x = step * (selected - 1) + cardWidth + 1.dp)
                        .size(2.dp, cardWidth * CARD_ASPECT + raise)
                        .background(Highlight)
                        .zIndex(DECK_SIZE.toFloat()),
                )
            }
        }
    }
}

/** After the cut: the two stacks, the Kritten taken one by one and the open bottom card. */
@Composable
private fun Reveal(cut: CutUi, lifted: Int, name: String, mine: Boolean, onCutSeen: () -> Unit) {
    val count = cut.krittenCount
    val split = remember(cut) { Animatable(0f) }
    /** Kritten taken so far; the one at index [taken] lies face up below the lifted stack meanwhile. */
    var taken by remember(cut) { mutableIntStateOf(0) }
    var showing by remember(cut) { mutableStateOf<Int?>(null) }
    var bottomShown by remember(cut) { mutableStateOf(false) }
    LaunchedEffect(cut) {
        split.animateTo(1f, tween((CutAnimation.SPLIT_MS * 0.7).toInt(), easing = FastOutSlowInEasing))
        delay((CutAnimation.SPLIT_MS * 0.3).toLong())
        repeat(count) { i ->
            showing = i
            delay(CutAnimation.KRITTE_MS * 2 / 3)
            showing = null
            taken = i + 1
            delay(CutAnimation.KRITTE_MS / 3)
        }
        delay(CutAnimation.BOTTOM_MS / 2)
        bottomShown = true
    }
    val pileWidth = 44.dp
    val slotWidth = 58.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier.offset(x = (-24).dp * split.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pile(lifted - taken, pileWidth, R.string.cut_pile_lifted)
            Spacer(Modifier.height(6.dp))
            // Below the lifted stack: what the Abheber looks at.
            Box(Modifier.size(slotWidth, slotWidth * CARD_ASPECT), contentAlignment = Alignment.Center) {
                val index = showing
                when {
                    index != null -> key(index) { Appearing { KritteCard(cut.kritten.getOrNull(index), slotWidth) } }
                    bottomShown -> Appearing { cut.bottomCard?.let { CardView(it, width = slotWidth, highlight = true) } }
                }
            }
        }
        Column(
            Modifier.offset(x = 24.dp * split.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pile(DECK_SIZE - lifted, pileWidth, R.string.cut_pile_rest)
        }
    }
    Spacer(Modifier.height(8.dp))
    val status = when {
        showing != null -> if (mine) stringResource(R.string.cut_kritte_you) else stringResource(R.string.cut_kritte_other, name)
        bottomShown && count == 0 -> stringResource(R.string.cut_no_kritte)
        bottomShown -> stringResource(R.string.cut_kritten_bottom, Texts.kritten(count))
        else -> " "
    }
    Text(status, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
    if (count > 0) {
        Spacer(Modifier.height(6.dp))
        Text(if (mine) stringResource(R.string.cut_your_kritten) else stringResource(R.string.cut_kritten_of, name), color = Highlight, fontSize = 12.sp)
        Row(Modifier.height(36.dp * CARD_ASPECT), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(taken) { i -> Appearing(fromAbove = true) { KritteCard(cut.kritten.getOrNull(i), 36.dp) } }
        }
    }
    if (mine && bottomShown) {
        TextButton(onClick = onCutSeen) { Text(stringResource(R.string.cut_next), color = Highlight) }
    }
}

/** A taken Kritte: its face for the Abheber, its back for everybody else. */
@Composable
private fun KritteCard(card: Card?, width: Dp) {
    if (card != null) CardView(card, width = width, highlight = true) else CardBack(width = width)
}

/** A face-down stack of [count] cards with a [label] (a text with the count as placeholder). */
@Composable
private fun Pile(count: Int, width: Dp, @StringRes label: Int) {
    val layers = count.coerceIn(1, 6)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width + 6.dp, width * CARD_ASPECT + 6.dp)) {
            repeat(layers) { i ->
                CardBack(width = width, modifier = Modifier.offset(x = 1.dp * i, y = 6.dp - 1.dp * i))
            }
        }
        Text(stringResource(label, count), color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
}

/** Shows [content] with a short fade and zoom (or sliding down from above). */
@Composable
private fun Appearing(fromAbove: Boolean = false, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = if (fromAbove) {
            fadeIn(tween(250)) + slideInVertically(tween(300)) { -it }
        } else {
            fadeIn(tween(250)) + scaleIn(tween(300), initialScale = 0.6f)
        },
    ) { content() }
}
