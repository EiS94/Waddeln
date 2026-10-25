package com.schaubeck.waddeln.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.schaubeck.waddeln.R
import com.schaubeck.waddeln.rules.Card
import com.schaubeck.waddeln.rules.Rank
import com.schaubeck.waddeln.rules.Suit
import com.schaubeck.waddeln.rules.Trump

/** Height / width of the card images. */
const val CARD_ASPECT = 1.75f

fun suitColor(suit: Suit): Color = when (suit) {
    Suit.SCHELL -> Color(0xFFC88A00)
    Suit.HERZ -> Color(0xFFC62828)
    Suit.EICHEL -> Color(0xFF6D4C41)
    Suit.GRAS -> Color(0xFF2E7D32)
}

private val CardShape = RoundedCornerShape(6.dp)
private val TrumpGold = Color(0xFFFFC107)

@DrawableRes
fun cardImage(card: Card): Int = CARD_IMAGES[card.index]

private val CARD_IMAGES: IntArray = Card.ALL.map { card ->
    when (card.suit) {
        Suit.SCHELL -> when (card.rank) {
            Rank.SEVEN -> R.drawable.card_schell_sieben
            Rank.EIGHT -> R.drawable.card_schell_acht
            Rank.NINE -> R.drawable.card_schell_neun
            Rank.TEN -> R.drawable.card_schell_zehn
            Rank.UNTER -> R.drawable.card_schell_unter
            Rank.OBER -> R.drawable.card_schell_ober
            Rank.KOENIG -> R.drawable.card_schell_koenig
            Rank.ASS -> R.drawable.card_schell_ass
        }
        Suit.HERZ -> when (card.rank) {
            Rank.SEVEN -> R.drawable.card_herz_sieben
            Rank.EIGHT -> R.drawable.card_herz_acht
            Rank.NINE -> R.drawable.card_herz_neun
            Rank.TEN -> R.drawable.card_herz_zehn
            Rank.UNTER -> R.drawable.card_herz_unter
            Rank.OBER -> R.drawable.card_herz_ober
            Rank.KOENIG -> R.drawable.card_herz_koenig
            Rank.ASS -> R.drawable.card_herz_ass
        }
        Suit.EICHEL -> when (card.rank) {
            Rank.SEVEN -> R.drawable.card_eichel_sieben
            Rank.EIGHT -> R.drawable.card_eichel_acht
            Rank.NINE -> R.drawable.card_eichel_neun
            Rank.TEN -> R.drawable.card_eichel_zehn
            Rank.UNTER -> R.drawable.card_eichel_unter
            Rank.OBER -> R.drawable.card_eichel_ober
            Rank.KOENIG -> R.drawable.card_eichel_koenig
            Rank.ASS -> R.drawable.card_eichel_ass
        }
        Suit.GRAS -> when (card.rank) {
            Rank.SEVEN -> R.drawable.card_gras_sieben
            Rank.EIGHT -> R.drawable.card_gras_acht
            Rank.NINE -> R.drawable.card_gras_neun
            Rank.TEN -> R.drawable.card_gras_zehn
            Rank.UNTER -> R.drawable.card_gras_unter
            Rank.OBER -> R.drawable.card_gras_ober
            Rank.KOENIG -> R.drawable.card_gras_koenig
            Rank.ASS -> R.drawable.card_gras_ass
        }
    }
}.toIntArray()

/**
 * A card face. If [trump] is given, trump cards get a golden frame (only used for the own hand).
 */
@Composable
fun CardView(
    card: Card,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    trump: Trump? = null,
    enabled: Boolean = true,
    /** Strong golden frame, e.g. for the card that won the trick. */
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val isTrump = trump?.isTrump(card) == true
    val frame = when {
        highlight -> 4.dp
        isTrump -> 2.5.dp
        else -> 0.dp
    }
    Box(
        modifier = modifier
            .size(width, width * CARD_ASPECT)
            .alpha(if (enabled) 1f else 0.55f)
            .shadow(3.dp, CardShape)
            .clip(CardShape)
            .background(Color.White)
            .then(if (frame > 0.dp) Modifier.border(BorderStroke(frame, TrumpGold), CardShape) else Modifier)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = Texts.card(card) },
    ) {
        Image(
            painter = painterResource(cardImage(card)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            // The frame goes around the picture, so it hides no part of it (e.g. the index in the corner).
            modifier = Modifier.size(width, width * CARD_ASPECT).padding(frame),
        )
    }
}

/**
 * Back side of a card.
 * [landscape] lays the card sideways (for the players on the left and right).
 */
@Composable
fun CardBack(modifier: Modifier = Modifier, width: Dp = 40.dp, landscape: Boolean = false) {
    Image(
        painter = painterResource(if (landscape) R.drawable.card_back_landscape else R.drawable.card_back),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .then(if (landscape) Modifier.size(width * CARD_ASPECT, width) else Modifier.size(width, width * CARD_ASPECT))
            .shadow(2.dp, CardShape)
            .clip(CardShape),
    )
}
