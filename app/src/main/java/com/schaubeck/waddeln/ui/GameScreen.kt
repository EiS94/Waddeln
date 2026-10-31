package com.schaubeck.waddeln.ui

import android.os.SystemClock
import androidx.compose.runtime.produceState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.game.Speech
import com.schaubeck.waddeln.game.TableUi
import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.Phase
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.RoundEndReason
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Trump
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import com.schaubeck.waddeln.rules.agent.TrickAdvice
import com.schaubeck.waddeln.rules.nextPlayer
import com.schaubeck.waddeln.rules.teamOf
import com.schaubeck.waddeln.sound.LocalTableSound
import kotlinx.coroutines.delay

/**
 * The card table, drawn for the player [TableUi.me] at the bottom (offline and online).
 * [onContinue] starts the next round or match after the result; [onAskPartner] asks the partner
 * about their cards during a Schub, [onAnswerPartner] (online only) answers such a question;
 * [onAskSchadet] asks the partner "Schadder?" before leading the Haubi, [onAnswerSchadet] answers it;
 * [onAskAdvice] asks the partner "Was soll ich machen?" second in a trick, [onAnswerAdvice] answers it;
 * [onShowLastTrick] shows or hides the last completed trick; [onProposeSchlagwechsel] proposes a
 * Schlagwechsel before the Schlag, [onAnswerSchlagwechsel] answers it as dealer;
 * [onCut] cuts as Abheber before the deal, [onCutSeen] skips the rest of the cut animation;
 * [onLeave] (online only) leaves the room for good. [soundKey] tells the offline and the online
 * table apart for the sounds.
 */
@Composable
fun GameScreen(
    table: TableUi,
    onAction: (Action) -> Unit,
    onContinue: () -> Unit,
    onNoticeConfirmed: () -> Unit,
    onAskPartner: () -> Unit,
    onMenu: () -> Unit,
    onAnswerPartner: (PartnerHint) -> Unit = {},
    onAskSchadet: () -> Unit = {},
    onAnswerSchadet: (SchadetAnswer) -> Unit = {},
    onAskAdvice: () -> Unit = {},
    onAnswerAdvice: (TrickAdvice) -> Unit = {},
    onShowLastTrick: (Boolean) -> Unit = {},
    onProposeSchlagwechsel: () -> Unit = {},
    onAnswerSchlagwechsel: (Boolean) -> Unit = {},
    onCut: (Int) -> Unit = {},
    onCutSeen: () -> Unit = {},
    onLeave: (() -> Unit)? = null,
    soundKey: String = "offline",
) {
    val sound = LocalTableSound.current
    LaunchedEffect(table) { sound?.observe(soundKey, table) }
    WoodBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(table.obs)
            table.connectionProblem?.let { ConnectionBanner(it) }
            val cut = table.cut
            if (cut != null) {
                // Before the deal: the seats around the table and the cut in the middle.
                Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                    Seats(table)
                    CutScene(table, cut, onCut, onCutSeen, Modifier.align(Alignment.Center).padding(horizontal = 12.dp).zIndex(2f))
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Table(table, onSchub = { onAction(Action.Schub) }, onShowLastTrick = onShowLastTrick)
                }
                ActionArea(
                    table, onAction, onNoticeConfirmed, onAskPartner, onAnswerPartner, onAskSchadet, onAnswerSchadet,
                    onAskAdvice, onAnswerAdvice, onProposeSchlagwechsel, onAnswerSchlagwechsel,
                )
                Hand(table, onAction)
            }
        }
        if (table.cut == null) RoundEndOverlay(table, onContinue, onMenu, onLeave)
    }
}

@Composable
private fun ConnectionBanner(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(SchubOrange).padding(4.dp),
    )
}

// ---- Top bar ---------------------------------------------------------------------------------

@Composable
private fun TopBar(s: Observation) {
    val us = s.team
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BarGreen)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoreLabel(stringResource(R.string.game_we), s.scores[us], s.isGespannt(us))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BarText(stringResource(R.string.game_tricks, s.tricksWon[us], s.tricksWon[1 - us]), size = 15, color = Highlight)
            BarText(stringResource(R.string.game_value_round, s.gameValue, s.roundNumber), size = 12)
        }
        ScoreLabel(stringResource(R.string.game_opponents), s.scores[1 - us], s.isGespannt(1 - us))
    }
}

@Composable
private fun ScoreLabel(name: String, score: Int, gespannt: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BarText(stringResource(R.string.game_score, name, score), size = 22, bold = true)
        if (gespannt) BarText(stringResource(R.string.game_gespannt), size = 11, color = Highlight)
    }
}

@Composable
private fun BarText(text: String, size: Int = 14, bold: Boolean = false, color: Color = Color.White) {
    Text(text, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

// ---- Table -----------------------------------------------------------------------------------

@Composable
private fun Table(table: TableUi, onSchub: () -> Unit, onShowLastTrick: (Boolean) -> Unit) {
    val s = table.obs
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The Haubi has the size of the own cards; the table is as wide as the hand.
        val haubiWidth = handCardWidth(maxWidth)
        val trickWidth = trickCardWidth(maxWidth, maxHeight)
        // Below the Haubi so they never overlap
        val sideSeatTop = haubiWidth * CARD_ASPECT + 48.dp
        // Above the trick so speech bubbles stay readable
        Seats(table, sideSeatTop)
        HaubiCorner(s, haubiWidth, Modifier.align(Alignment.TopStart).padding(8.dp))
        BottomCardCorner(s, Modifier.align(Alignment.TopEnd).padding(8.dp))
        TrickArea(table, trickWidth, Modifier.align(Alignment.Center))
        TableInfo(table, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
        if (Action.Schub in table.actions) {
            Button(
                onClick = onSchub,
                colors = ButtonDefaults.buttonColors(containerColor = SchubOrange),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp),
            ) { Text(stringResource(R.string.game_schub_button, Texts.schubCall(s.gameValue)), fontWeight = FontWeight.Bold) }
        }
        if (table.canShowLastTrick && table.lastTrickShownBy == null) {
            OutlinedButton(
                onClick = { onShowLastTrick(true) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
            ) { Text(stringResource(R.string.game_last_trick), color = Color.White, fontSize = 13.sp) }
        }
        table.lastTrickShownBy?.let { shownBy ->
            LastTrickOverlay(
                table, shownBy, trickWidth,
                onClose = if (shownBy == table.me) ({ onShowLastTrick(false) }) else null,
                modifier = Modifier.align(Alignment.Center).zIndex(2f),
            )
        }
    }
}

/** The three other players around the table; [sideSeatTop] keeps the side seats below the Haubi. */
@Composable
private fun Seats(table: TableUi, sideSeatTop: Dp = 48.dp) {
    Box(Modifier.fillMaxSize().zIndex(1f)) {
        PartnerSeat(table, Modifier.align(Alignment.TopCenter).zIndex(1f))
        SideSeat(table, table.seatAt(1), left = true, modifier = Modifier.align(Alignment.TopStart).padding(top = sideSeatTop).zIndex(1f))
        SideSeat(table, table.seatAt(3), left = false, modifier = Modifier.align(Alignment.TopEnd).padding(top = sideSeatTop).zIndex(1f))
    }
}

/** Cards in the hand of [seat] as the others see them; none until the cut has been shown. */
private fun handSize(table: TableUi, seat: Int): Int = if (table.cut != null) 0 else table.obs.handSizes[seat]

/**
 * The last completed trick as it lay on the table, the winning card framed. Shown by [shownBy];
 * only they can close it ([onClose]), playing a card closes it too.
 */
@Composable
private fun LastTrickOverlay(table: TableUi, shownBy: Int, cardWidth: Dp, onClose: (() -> Unit)?, modifier: Modifier) {
    val trick = table.obs.completedTricks.lastOrNull() ?: return
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .background(Color(0xF22B1F14), shape)
            .border(2.dp, Highlight, shape)
            .then(if (onClose != null) Modifier.clickable(onClick = onClose) else Modifier)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.game_last_trick), color = Highlight, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.game_last_trick_won_by, table.names[trick.winner]),
            color = Color.White, fontSize = 13.sp,
        )
        val scale = cardWidth / BaseTrickCardWidth
        Box(Modifier.size(200.dp * scale, 210.dp * scale)) {
            trick.plays.forEachIndexed { i, play ->
                val (spot, angle) = trickSpot(table.relative(play.player), scale)
                CardView(
                    card = play.card,
                    width = cardWidth,
                    highlight = play.player == trick.winner,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(spot.x, spot.y)
                        .rotate(angle)
                        .zIndex(i.toFloat()),
                )
            }
        }
        Text(
            if (onClose != null) stringResource(R.string.game_last_trick_close) else stringResource(R.string.game_last_trick_shown_by, table.names[shownBy]),
            color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
        )
    }
}

@Composable
private fun PartnerSeat(table: TableUi, modifier: Modifier) {
    val seat = table.seatAt(2)
    Column(modifier.padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy((-22).dp)) {
            repeat(handSize(table, seat)) { CardBack(width = 40.dp) }
        }
        Spacer(Modifier.height(4.dp))
        SeatChip(table, seat)
        SpeechBubble(table, seat, BubbleFrom.ABOVE, Modifier.padding(top = 4.dp))
    }
}

/** Opponent on the left/right: cards lie sideways at the screen edge, partly outside. */
@Composable
private fun SideSeat(table: TableUi, seat: Int, left: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // The name moves over the cards sticking out of the screen; without cards it stays inside.
        val shift = if (handSize(table, seat) > 0) 18.dp else (-4).dp
        val backs = @Composable {
            Column(
                modifier = Modifier.offset(x = if (left) (-22).dp else 22.dp),
                verticalArrangement = Arrangement.spacedBy((-14).dp),
            ) {
                repeat(handSize(table, seat)) { CardBack(width = 34.dp, landscape = true) }
            }
        }
        if (left) {
            backs()
            Column {
                SeatChip(table, seat, Modifier.offset(x = -shift))
                SpeechBubble(table, seat, BubbleFrom.LEFT, Modifier.padding(top = 4.dp))
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                SeatChip(table, seat, Modifier.offset(x = shift))
                SpeechBubble(table, seat, BubbleFrom.RIGHT, Modifier.padding(top = 4.dp))
            }
            backs()
        }
    }
}

/** Where the speaker sits as seen from the bubble; the corner towards them is pointed. */
private enum class BubbleFrom(val shape: Shape) {
    ABOVE(RoundedCornerShape(12.dp)),
    LEFT(RoundedCornerShape(topStart = 2.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp)),
    RIGHT(RoundedCornerShape(topStart = 12.dp, topEnd = 2.dp, bottomStart = 12.dp, bottomEnd = 12.dp)),
}

/**
 * What [seat] said last (announcement, Schub, answer, hint), shown for
 * [com.schaubeck.waddeln.game.GameSpeed.speechMs] from when it is said.
 */
@Composable
private fun SpeechBubble(table: TableUi, seat: Int, from: BubbleFrom, modifier: Modifier = Modifier) {
    val speech: Speech? = table.speech.lastOrNull { it.seat == seat }
    val durationMs = table.speed.speechMs
    var visible by remember(speech) { mutableStateOf(speech != null && startsInMs(speech) <= 0 && remainingMs(speech, durationMs) > 0) }
    LaunchedEffect(speech) {
        if (speech == null) return@LaunchedEffect
        val wait = startsInMs(speech)
        if (wait > 0) {
            delay(wait)
            visible = true
        }
        delay(remainingMs(speech, durationMs))
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.7f),
        exit = fadeOut(tween(250)),
        modifier = modifier,
    ) {
        Text(
            speech?.text.orEmpty(),
            color = Color(0xFF2B1F14),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 150.dp)
                .background(Color(0xFFFFF8E1), from.shape)
                .border(1.5.dp, Highlight, from.shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * The partner's answer once it is said aloud: an answer given at once still follows the
 * question, so the panel shows it only together with its speech bubble.
 */
@Composable
private fun saidPartnerHint(table: TableUi): String? {
    val hint = table.partnerHint ?: return null
    val speech = table.speech.lastOrNull { it.seat == table.seatAt(2) && it.text == hint }
    var said by remember(speech) { mutableStateOf(speech == null || startsInMs(speech) <= 0) }
    LaunchedEffect(speech) {
        if (speech == null) return@LaunchedEffect
        delay(startsInMs(speech))
        said = true
    }
    return hint.takeIf { said }
}

private fun remainingMs(speech: Speech, durationMs: Long) = durationMs - (SystemClock.elapsedRealtime() - speech.at)

private fun startsInMs(speech: Speech) = speech.at - SystemClock.elapsedRealtime()

/**
 * Name of a seat with badges (dealer, Kritten taken while cutting, who leads the next trick, laid out
 * cards, lost connection); glows when to move.
 */
@Composable
private fun SeatChip(table: TableUi, seat: Int, modifier: Modifier = Modifier) {
    val s = table.obs
    val cut = table.cut
    val toMove = if (cut != null) {
        cut.cut == null && cut.abheber == seat
    } else {
        s.phase in setOf(Phase.SCHLAG, Phase.FARBE, Phase.PLAYING) && s.pendingSchub == null && s.playerToMove == seat
    }
    // Before each trick everyone sees who leads, also while a Schub waits for the answer.
    val leads = cut == null && s.phase == Phase.PLAYING && s.currentTrick.isEmpty() &&
        s.trickLeader == seat
    val badges = buildList {
        if (seat == s.dealer) add(stringResource(R.string.badge_dealer))
        if (leads) add(stringResource(R.string.badge_leads))
        if (cut != null && seat == cut.abheber) add(stringResource(R.string.badge_abheber))
        if (cut == null && seat == s.abheber && s.abheberKritten > 0) add(stringResource(R.string.badge_kritten_taken, Texts.kritten(s.abheberKritten)))
        if (cut == null && seat == s.dealer && s.geberKritten > 0) add(stringResource(R.string.badge_kritten_taken, Texts.kritten(s.geberKritten)))
        s.laidOutCounts?.get(seat)?.takeIf { it > 0 }?.let { add(stringResource(R.string.badge_laid_out, it)) }
        if (seat in table.absent) add(stringResource(R.string.badge_absent))
        if (seat in table.bots) add(stringResource(R.string.badge_bot))
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .background(if (toMove) Highlight.copy(alpha = 0.3f) else Panel, shape)
            .border(
                width = if (toMove) 2.dp else if (leads) 1.dp else 0.dp,
                color = if (toMove) Highlight else if (leads) Highlight.copy(alpha = 0.7f) else Color.Transparent,
                shape = shape,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(table.names[seat], color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        badges.forEach { Text(it, color = Highlight, fontSize = 10.sp) }
    }
}

@Composable
private fun CornerLabel(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Panel, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private val CornerCardWidth = 52.dp

/** Top left: the Haubi as a card, so everyone sees what is trump. */
@Composable
private fun HaubiCorner(s: Observation, width: Dp, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CornerLabel(stringResource(R.string.game_haubi))
        Spacer(Modifier.height(3.dp))
        val trump = s.trump
        if (trump != null) {
            CardView(trump.haubi, width = width)
        } else {
            EmptySlot(s.schlag?.let { stringResource(R.string.game_schlag_slot, stringResource(Texts.rank(it))) } ?: stringResource(R.string.game_no_haubi_yet), width)
        }
    }
}

/** Top right: the open bottom card of the stock. */
@Composable
private fun BottomCardCorner(s: Observation, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CornerLabel(stringResource(R.string.game_bottom_card))
        Spacer(Modifier.height(3.dp))
        CardView(s.visibleBottomCard, width = CornerCardWidth)
    }
}

@Composable
private fun EmptySlot(text: String, width: Dp) {
    Box(
        modifier = Modifier
            .size(width, width * CARD_ASPECT)
            .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

// ---- Trick with animations ---------------------------------------------------------------------

private const val PLAY_MS = 380
private const val COLLECT_MS = 450

/** Width of the cards in the middle at which [trickSpot] places them; larger cards spread further. */
private val BaseTrickCardWidth = 64.dp

/**
 * Width of the cards in the middle of a table [width] × [height]: they grow with the screen (for
 * players who see less well), but never so far that they reach the seats or the Haubi.
 */
private fun trickCardWidth(width: Dp, height: Dp): Dp =
    minOf(width * 0.19f, height * 0.15f, 80.dp).coerceAtLeast(BaseTrickCardWidth)

/**
 * Where a card lies in the trick (offset from the trick centre) and its slight rotation,
 * by [relative] seat position (0 = own, 1 = left, 2 = partner, 3 = right), for cards [scale] times
 * as wide as [BaseTrickCardWidth]. Larger cards spread only half as much further and overlap more,
 * so they stay clear of the seats.
 */
private fun trickSpot(relative: Int, scale: Float): Pair<DpOffset, Float> {
    val spread = 1f + (scale - 1f) / 2
    return when (relative) {
        0 -> DpOffset(0.dp, 46.dp * spread) to -3f
        1 -> DpOffset((-58).dp * spread, 0.dp) to 6f
        2 -> DpOffset(0.dp, (-46).dp * spread) to 2f
        else -> DpOffset(58.dp * spread, 0.dp) to -6f
    }
}

/** Approximate position of a [relative] seat to the trick centre: cards fly from and to there. */
private fun seatOrigin(relative: Int): DpOffset = when (relative) {
    0 -> DpOffset(0.dp, 330.dp)
    1 -> DpOffset((-210).dp, (-10).dp)
    2 -> DpOffset(0.dp, (-240).dp)
    else -> DpOffset(210.dp, (-10).dp)
}

private fun lerp(a: DpOffset, b: DpOffset, t: Float) = DpOffset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

/**
 * The current trick. Played cards fly in from their player; a finished trick stays briefly with
 * the winning card framed and is then collected towards the winner.
 */
@Composable
private fun TrickArea(table: TableUi, cardWidth: Dp, modifier: Modifier) {
    val s = table.obs
    val speed = table.speed
    val completed = s.completedTricks.lastOrNull()
    val showingCompleted = s.currentTrick.isEmpty() && completed != null
    val plays = if (s.currentTrick.isNotEmpty()) s.currentTrick else completed?.plays.orEmpty()
    val winner = if (showingCompleted) completed.winner else null
    val roundOver = s.phase != Phase.PLAYING
    val sound = LocalTableSound.current

    val collect = remember(s.roundNumber, s.completedTricks.size) { Animatable(0f) }
    LaunchedEffect(s.roundNumber, s.completedTricks.size, showingCompleted, roundOver) {
        // At the end of a round the last trick stays visible below the result.
        if (showingCompleted && !roundOver) {
            delay((speed.trickPauseMs - COLLECT_MS - 100).coerceAtLeast(300))
            sound?.trickTaken()
            collect.animateTo(1f, tween(COLLECT_MS, easing = FastOutSlowInEasing))
        }
    }
    val c = if (showingCompleted) collect.value else 0f

    val scale = cardWidth / BaseTrickCardWidth
    Box(modifier.offset(y = 18.dp).size(220.dp * scale, 230.dp * scale)) {
        plays.forEachIndexed { i, play ->
            key(s.roundNumber, play.card) {
                val fly = remember { Animatable(0f) }
                LaunchedEffect(Unit) { fly.animateTo(1f, tween(PLAY_MS, easing = FastOutSlowInEasing)) }
                val (spot, angle) = trickSpot(table.relative(play.player), scale)
                val inPlace = lerp(seatOrigin(table.relative(play.player)), spot, fly.value)
                val pos = if (winner != null) lerp(inPlace, seatOrigin(table.relative(winner)), c) else inPlace
                CardView(
                    card = play.card,
                    width = cardWidth,
                    highlight = play.player == winner,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(pos.x, pos.y)
                        .rotate(angle * fly.value)
                        .scale(1f - 0.45f * c)
                        .alpha(1f - c)
                        .zIndex(i.toFloat()),
                )
            }
        }
    }
}

/** Short status line on the table above the hand. */
@Composable
private fun TableInfo(table: TableUi, modifier: Modifier) {
    val s = table.obs
    val status = when {
        s.phase == Phase.ROUND_OVER || s.phase == Phase.MATCH_OVER -> null
        s.pendingSchub != null && teamOf(s.pendingSchub!!.askingPlayer) == s.team -> stringResource(R.string.status_wait_opponents)
        s.pendingSchub != null && Action.Bleiben !in table.actions -> stringResource(R.string.status_wait_partner)
        s.pendingSchub != null -> null
        s.playerToMove == table.me -> stringResource(R.string.status_your_turn)
        else -> null // the glowing name shows who is to move
    }
    // We have no name badge, so our lead of the next trick is said here (e.g. while a Schub is open).
    val beforeLead = table.cut == null && s.phase == Phase.PLAYING && s.currentTrick.isEmpty()
    val lead = stringResource(R.string.status_you_lead).takeIf { beforeLead && s.trickLeader == table.me && s.playerToMove != table.me }
    val myDecision = table.actions.any { it != Action.Schub }
    val secondsLeft = secondsUntil(table.deadline.takeIf { myDecision })
    val countdown = secondsLeft?.takeIf { it <= COUNTDOWN_FROM_S }?.let { stringResource(R.string.status_countdown, it) }
    val text = listOfNotNull(status, lead, countdown).joinToString(" · ").ifEmpty { null }
    if (text == null || table.notice != null) return
    Text(
        text,
        color = Highlight,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** The countdown appears for the last seconds of the online time limit. */
private const val COUNTDOWN_FROM_S = 30L

/** Whole seconds until [deadline] (SystemClock.elapsedRealtime), updated every second; null without deadline. */
@Composable
internal fun secondsUntil(deadline: Long?): Long? {
    if (deadline == null) return null
    val left by produceState(initialValue = remainingSeconds(deadline), deadline) {
        while (value > 0) {
            delay(250)
            value = remainingSeconds(deadline)
        }
    }
    return left
}

private fun remainingSeconds(deadline: Long) =
    ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)

// ---- Actions ---------------------------------------------------------------------------------

@Composable
private fun ActionArea(
    table: TableUi,
    onAction: (Action) -> Unit,
    onNoticeConfirmed: () -> Unit,
    onAskPartner: () -> Unit,
    onAnswerPartner: (PartnerHint) -> Unit,
    onAskSchadet: () -> Unit,
    onAnswerSchadet: (SchadetAnswer) -> Unit,
    onAskAdvice: () -> Unit,
    onAnswerAdvice: (TrickAdvice) -> Unit,
    onProposeSchlagwechsel: () -> Unit,
    onAnswerSchlagwechsel: (Boolean) -> Unit,
) {
    val s = table.obs
    val actions = table.actions
    val notice = table.notice
    val content: (@Composable () -> Unit)? = when {
        // Offline the game waits for the notice; online a new question takes precedence over it.
        notice != null && actions.none { it is Action.AnnounceSchlag || it is Action.AnnounceFarbe || it == Action.Bleiben } -> {
            {
                PanelTitle(Texts.schubNotice(notice.askingPlayer, notice.answeringPlayer, notice.newGameValue, table.names, table.me))
                Button(onClick = onNoticeConfirmed, colors = woodButton()) { Text(stringResource(R.string.common_ok)) }
            }
        }

        table.schlagwechselAsksUs -> {
            {
                val proposer = table.names[nextPlayer(s.dealer)]
                PanelTitle(stringResource(R.string.action_schlagwechsel_asks_us, proposer))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onAnswerSchlagwechsel(true) }, colors = woodButton()) { Text(stringResource(R.string.action_accept)) }
                    OutlinedButton(onClick = { onAnswerSchlagwechsel(false) }) { Text(stringResource(R.string.action_decline), color = Color.White) }
                }
            }
        }

        table.waitingForSchlagwechsel -> {
            { Text(stringResource(R.string.action_schlagwechsel_waiting, table.names[s.dealer]), color = Highlight, fontSize = 14.sp) }
        }

        actions.any { it is Action.AnnounceSchlag } -> {
            {
                PanelTitle(stringResource(R.string.action_announce_schlag))
                // Values we hold none of are greyed out, but can still be announced (as at a real table).
                val held = s.hand.map { it.rank }.toSet()
                Rank.entries.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { rank ->
                            Button(
                                onClick = { onAction(Action.AnnounceSchlag(rank)) },
                                colors = if (rank in held) woodButton() else notHeldButton(),
                                contentPadding = CompactPadding,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(Texts.rank(rank)), maxLines = 1) }
                        }
                    }
                }
                if (table.canProposeSchlagwechsel) {
                    OutlinedButton(onClick = onProposeSchlagwechsel, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.action_propose_schlagwechsel), color = Color.White)
                    }
                }
            }
        }

        actions.any { it is Action.AnnounceFarbe } -> {
            {
                PanelTitle(stringResource(R.string.action_announce_farbe, stringResource(Texts.rank(s.schlag!!))))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Suit.entries.forEach { suit ->
                        Button(
                            onClick = { onAction(Action.AnnounceFarbe(suit)) },
                            colors = ButtonDefaults.buttonColors(containerColor = suitColor(suit)),
                            contentPadding = CompactPadding,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(Texts.suit(suit)), maxLines = 1) }
                    }
                }
            }
        }

        Action.Bleiben in actions -> {
            {
                PanelTitle(stringResource(R.string.action_schub_asked, table.names[s.pendingSchub!!.askingPlayer], Texts.schubCall(s.gameValue)))
                val partner = table.names[table.seatAt(2)]
                saidPartnerHint(table)?.let {
                    Text(stringResource(R.string.action_partner_said, partner, it), color = Highlight, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                if (table.canAskPartner) {
                    OutlinedButton(onClick = onAskPartner, modifier = Modifier.padding(bottom = 4.dp)) {
                        Text(stringResource(R.string.action_ask_partner, partner), color = Color.White)
                    }
                }
                if (table.waitingForPartner) {
                    Text(stringResource(R.string.action_wait_for, partner), color = Highlight, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                if (table.partnerAsksUs) {
                    Text(stringResource(R.string.action_partner_asks_us, partner), color = Highlight, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PartnerHint.entries.forEach { hint ->
                            Button(
                                onClick = { onAnswerPartner(hint) },
                                colors = woodButton(),
                                contentPadding = CompactPadding,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(Texts.hintLabel(hint)), fontSize = 12.sp, maxLines = 1) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onAction(Action.Bleiben) }, colors = woodButton()) {
                        Text(stringResource(R.string.action_bleiben, s.gameValue + 1))
                    }
                    OutlinedButton(onClick = { onAction(Action.Gehen) }) {
                        Text(stringResource(R.string.action_gehen, s.gameValue), color = Color.White)
                    }
                }
            }
        }

        table.partnerAsksSchadet -> {
            {
                PanelTitle(stringResource(R.string.action_partner_asks_schadet, table.names[table.seatAt(2)]))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SchadetAnswer.entries.forEach { answer ->
                        Button(
                            onClick = { onAnswerSchadet(answer) },
                            colors = woodButton(),
                            contentPadding = CompactPadding,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(Texts.schadetLabel(answer)), maxLines = 1) }
                    }
                }
            }
        }

        table.canAskSchadet || table.waitingForSchadet || table.schadetAnswer != null -> {
            {
                val partner = table.names[table.seatAt(2)]
                when {
                    table.canAskSchadet -> OutlinedButton(onClick = onAskSchadet) {
                        Text(stringResource(R.string.action_ask_schadet, partner), color = Color.White)
                    }
                    table.waitingForSchadet -> Text(stringResource(R.string.action_wait_for, partner), color = Highlight, fontSize = 14.sp)
                    else -> Text(stringResource(R.string.action_partner_said, partner, table.schadetAnswer.orEmpty()), color = Highlight, fontSize = 15.sp)
                }
            }
        }

        table.partnerAsksAdvice -> {
            {
                PanelTitle(stringResource(R.string.action_partner_asks_advice, table.names[table.seatAt(2)]))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrickAdvice.entries.forEach { answer ->
                        Button(
                            onClick = { onAnswerAdvice(answer) },
                            colors = woodButton(),
                            contentPadding = CompactPadding,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(Texts.adviceLabel(answer)), maxLines = 1) }
                    }
                }
            }
        }

        table.canAskAdvice || table.waitingForAdvice || table.adviceAnswer != null -> {
            {
                val partner = table.names[table.seatAt(2)]
                when {
                    table.canAskAdvice -> OutlinedButton(onClick = onAskAdvice) {
                        Text(stringResource(R.string.action_ask_advice, partner), color = Color.White)
                    }
                    table.waitingForAdvice -> Text(stringResource(R.string.action_wait_for, partner), color = Highlight, fontSize = 14.sp)
                    else -> Text(stringResource(R.string.action_partner_said, partner, table.adviceAnswer.orEmpty()), color = Highlight, fontSize = 15.sp)
                }
            }
        }

        else -> null
    }
    if (content != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

private val CompactPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

// ---- Own hand --------------------------------------------------------------------------------

/** The own cards, overlapping; playable ones are raised. */
@Composable
private fun Hand(table: TableUi, onAction: (Action) -> Unit) {
    val s = table.obs
    val trump = s.trump
    val hand = sortHand(s.hand, trump, s.phase, s.schlag)
    val playable = table.actions.filterIsInstance<Action.PlayCard>().map { it.card }.toSet()
    val myTurn = playable.isNotEmpty()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .navigationBarsPadding(),
    ) {
        val areaWidth = maxWidth
        val cardWidth = handCardWidth(areaWidth)
        val raise = 18.dp
        val n = hand.size
        val step: Dp = if (n > 1) min(cardWidth + 6.dp, (areaWidth - 16.dp - cardWidth) / (n - 1)) else 0.dp
        val total = cardWidth + step * (n - 1).coerceAtLeast(0)
        Box(Modifier.fillMaxWidth().height(cardWidth * CARD_ASPECT + raise)) {
            hand.forEachIndexed { i, card ->
                val canPlay = card in playable
                CardView(
                    card = card,
                    width = cardWidth,
                    trump = trump,
                    enabled = !myTurn || canPlay,
                    onClick = if (canPlay) ({ onAction(Action.PlayCard(card)) }) else null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (areaWidth - total) / 2 + step * i, y = if (myTurn && canPlay) -raise else 0.dp)
                        .zIndex(i.toFloat()),
                )
            }
        }
    }
}

/** Width of the own cards (and of the Haubi) on a screen [areaWidth] wide. */
private fun handCardWidth(areaWidth: Dp): Dp = min(areaWidth * 0.21f, 100.dp)

/**
 * Trumps first (strongest left), then plain cards grouped by suit, high to low. While the Schlag is
 * announced the cards are grouped by rank instead, and while the Farbe is announced the Schläge come
 * right after the Kritten, so the choice can be read off the hand.
 */
private fun sortHand(hand: List<Card>, trump: Trump?, phase: Phase, schlag: Rank?): List<Card> {
    fun kritte(card: Card) = if (card.isKritte) 200 - Card.KRITTEN.indexOf(card) else -1
    val order = when {
        trump != null -> compareByDescending<Card> { trump.strength(it) }
        phase == Phase.SCHLAG -> compareByDescending<Card> { kritte(it) }.thenByDescending { it.rank.ordinal }
        phase == Phase.FARBE && schlag != null ->
            compareByDescending<Card> { kritte(it) }.thenByDescending { it.rank == schlag }
        else -> compareByDescending<Card> { kritte(it) }
    }
    return hand.sortedWith(order.thenBy { it.suit.ordinal }.thenByDescending { it.rank.ordinal })
}

// ---- End of round / match --------------------------------------------------------------------

@Composable
private fun RoundEndOverlay(
    table: TableUi,
    onContinue: () -> Unit,
    onMenu: () -> Unit,
    onLeave: (() -> Unit)?,
) {
    val s = table.obs
    val over = s.phase == Phase.ROUND_OVER || s.phase == Phase.MATCH_OVER
    // Let the last trick be seen before the result covers the table.
    var visible by remember(s.roundNumber) { mutableStateOf(false) }
    LaunchedEffect(s.roundNumber, over) {
        if (over) {
            when {
                s.result?.reason == RoundEndReason.TRICKS -> delay(table.speed.trickPauseMs)
                // Someone else gave up: let their bubble be read first.
                table.speech.lastOrNull()?.seat != table.me -> delay(table.speed.speechMs)
            }
            visible = true
        }
    }
    if (!over || !visible) return
    val matchOver = s.phase == Phase.MATCH_OVER
    val us = s.team
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .background(Color(0xF22B1F14), RoundedCornerShape(16.dp))
                .border(2.dp, Highlight, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (matchOver) {
                val won = s.matchWinner == us
                Text(
                    stringResource(if (won) R.string.end_match_won_title else R.string.end_match_lost_title),
                    color = Highlight, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(if (won) R.string.end_match_won else R.string.end_match_lost),
                    color = Color.White, fontSize = 16.sp,
                )
            } else {
                s.result?.let {
                    Text(
                        Texts.roundResult(it.winningTeam, it.points, it.reason, table.me),
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.end_score, s.scores[us], s.scores[1 - us]), color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(14.dp))
            if (table.waitingForOthers) {
                Text(stringResource(R.string.end_waiting_for_others), color = Highlight, fontSize = 16.sp)
            } else {
                Button(onClick = onContinue, colors = woodButton()) {
                    Text(stringResource(if (matchOver) R.string.end_new_match else R.string.end_next_round), fontSize = 16.sp)
                }
            }
            TextButton(onClick = onMenu) { Text(stringResource(R.string.end_main_menu), color = Color.White) }
            onLeave?.let { TextButton(onClick = it) { Text(stringResource(R.string.common_leave_room), color = Color.White) } }
        }
    }
}
