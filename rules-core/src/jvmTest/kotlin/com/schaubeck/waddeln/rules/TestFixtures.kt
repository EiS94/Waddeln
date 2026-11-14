package com.schaubeck.waddeln.rules

/**
 * Short card notation for tests: suit letter S/H/E/G + rank 7 8 9 10 U O K A.
 * Example: "HK" = Herz König (Max), "S7" = Bello, "G10" = Gras 10.
 */
fun c(code: String): Card {
    val suit = when (code[0]) {
        'S' -> Suit.SCHELL
        'H' -> Suit.HERZ
        'E' -> Suit.EICHEL
        'G' -> Suit.GRAS
        else -> error("Unknown suit in $code")
    }
    val rank = when (code.substring(1)) {
        "7" -> Rank.SEVEN
        "8" -> Rank.EIGHT
        "9" -> Rank.NINE
        "10" -> Rank.TEN
        "U" -> Rank.UNTER
        "O" -> Rank.OBER
        "K" -> Rank.KOENIG
        "A" -> Rank.ASS
        else -> error("Unknown rank in $code")
    }
    return Card(suit, rank)
}

fun cards(codes: String): List<Card> = codes.trim().split(Regex("\\s+")).map(::c)

/**
 * Builds a [Deal] that results in exactly the given [hands] for [dealer]. The first
 * [abheberKritten] cards of the Abheber's hand must be Kritten; they are placed at the bottom
 * of the lifted stack so the Abheber has to take them.
 */
fun dealFor(dealer: Int, hands: List<List<Card>>, abheberKritten: Int = 0): Deal {
    require(hands.size == PLAYER_COUNT && hands.all { it.size == HAND_SIZE })
    val abheber = abheberOf(dealer)
    val taken = hands[abheber].take(abheberKritten)
    require(taken.all { it.isKritte })

    val order = List(PLAYER_COUNT) { (dealer + 1 + it) % PLAYER_COUNT }
    val dealt = mutableListOf<Card>()
    for (p in order) {
        val skip = if (p == abheber) abheberKritten else 0
        dealt += hands[p].subList(skip, 3)
    }
    for (p in order) dealt += hands[p].subList(3, 5)

    val used = hands.flatten().toSet()
    require(used.size == 20) { "Hands must contain 20 distinct cards" }
    val (stockKritten, stockOthers) = (Card.ALL - used).partition { it.isKritte }
    val stock = stockKritten + stockOthers // a non-Kritte ends up at the bottom
    val finalPile = dealt + stock

    val liftedSize = 8
    val rest = finalPile.dropLast(liftedSize)
    val lifted = finalPile.takeLast(liftedSize) + taken.reversed()
    return Deal(lifted + rest, cut = lifted.size)
}

/** A fresh match state with the given hands, dealer and scores in phase [Phase.SCHLAG]. */
fun roundWith(
    hands: List<String>,
    dealer: Int = 3,
    scores: List<Int> = listOf(0, 0),
    abheberKritten: Int = 0,
): GameState {
    val state = Waddeln.newMatch(dealer, dealFor(dealer, hands.map(::cards), abheberKritten))
    return state.copy(scores = scores)
}

/** Like [roundWith], with Schlag and Farbe already announced (phase [Phase.PLAYING]). */
fun playingWith(
    hands: List<String>,
    schlag: Rank,
    farbe: Suit,
    dealer: Int = 3,
    scores: List<Int> = listOf(0, 0),
): GameState {
    var s = roundWith(hands, dealer, scores)
    s = Waddeln.apply(s, s.forehand, Action.AnnounceSchlag(schlag))
    return Waddeln.apply(s, s.dealer, Action.AnnounceFarbe(farbe))
}

/** Plays the given cards in turn order, starting with whoever is to move. */
fun GameState.play(vararg codes: String): GameState =
    codes.fold(this) { s, code -> Waddeln.apply(s, s.playerToMove!!, Action.PlayCard(c(code))) }

fun GameState.act(player: Int, action: Action): GameState = Waddeln.apply(this, player, action)
