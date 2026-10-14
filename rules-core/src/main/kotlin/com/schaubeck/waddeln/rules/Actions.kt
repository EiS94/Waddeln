package com.schaubeck.waddeln.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Everything a player can do. Applied with [Waddeln.apply]. */
@Serializable
sealed interface Action {
    /** Player after the dealer announces the Schlag. */
    @Serializable @SerialName("schlag")
    data class AnnounceSchlag(val rank: Rank) : Action

    /** Dealer announces the Farbe; the Haubi is then fixed. */
    @Serializable @SerialName("farbe")
    data class AnnounceFarbe(val suit: Suit) : Action

    @Serializable @SerialName("play")
    data class PlayCard(val card: Card) : Action

    /** Ask the opposing team whether it gives up. */
    @Serializable @SerialName("schub")
    data object Schub : Action

    /** Answer to a Schub: keep playing, game value +1. */
    @Serializable @SerialName("bleiben")
    data object Bleiben : Action

    /** Answer to a Schub: give up, the asking team wins the current game value. */
    @Serializable @SerialName("gehen")
    data object Gehen : Action
}

class IllegalActionException(message: String) : IllegalArgumentException(message)
