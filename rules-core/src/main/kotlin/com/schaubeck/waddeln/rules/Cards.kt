package com.schaubeck.waddeln.rules

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The four suits of the Franconian/Bavarian deck. */
enum class Suit { SCHELL, HERZ, EICHEL, GRAS }

/** Card values in ascending order (7 lowest, Ass highest). Also used as the `Schlag`. */
enum class Rank { SEVEN, EIGHT, NINE, TEN, UNTER, OBER, KOENIG, ASS }

/**
 * A playing card. Each of the 32 cards exists exactly once; use [Card.of] or [Card.ALL]
 * to get the canonical instances. [index] (0..31) is stable and used for encodings.
 * Serialized as its [toString] form, e.g. `HERZ_KOENIG`.
 */
@Serializable(with = CardSerializer::class)
data class Card(val suit: Suit, val rank: Rank) {
    val index: Int get() = suit.ordinal * Rank.entries.size + rank.ordinal

    /** True for the three fixed special trumps Max, Bello and Secher. */
    val isKritte: Boolean get() = this == MAX || this == BELLO || this == SECHER

    override fun toString(): String = "${suit.name}_${rank.name}"

    companion object {
        /** Max: Herz König, the highest card. */
        val MAX = Card(Suit.HERZ, Rank.KOENIG)

        /** Bello: Schell 7, the second highest card. */
        val BELLO = Card(Suit.SCHELL, Rank.SEVEN)

        /** Secher: Eichel 7, the third highest card. */
        val SECHER = Card(Suit.EICHEL, Rank.SEVEN)

        /** Kritten in descending strength. */
        val KRITTEN: List<Card> = listOf(MAX, BELLO, SECHER)

        /** All 32 cards ordered by [index]. */
        val ALL: List<Card> = Suit.entries.flatMap { suit -> Rank.entries.map { Card(suit, it) } }

        fun of(index: Int): Card = ALL[index]

        fun parse(text: String): Card {
            val (suit, rank) = text.split("_", limit = 2)
            return Card(Suit.valueOf(suit), Rank.valueOf(rank))
        }
    }
}

object CardSerializer : KSerializer<Card> {
    override val descriptor = PrimitiveSerialDescriptor("Card", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Card) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Card = Card.parse(decoder.decodeString())
}
