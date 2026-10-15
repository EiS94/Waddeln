"""Waddeln rules engine – Python mirror of rules-core (Kotlin).

Both implementations must behave identically; tests/test_parity.py replays JSON protocols
produced by the Kotlin engine (./gradlew :rules-core:generateProtocols) and compares.

Cards are ints 0..31: suit * 8 + rank.
Suits: SCHELL, HERZ, EICHEL, GRAS. Ranks: 7, 8, 9, 10, UNTER, OBER, KOENIG, ASS.
Action codes (see ActionCodec.kt): 0..7 Schlag, 8..11 Farbe, 12..43 play card, 44 Schub,
45 Bleiben, 46 Gehen. Table talk (partner hints, "Schadder?") is no action but part of the state.
"""
from __future__ import annotations

import random as _random
from dataclasses import dataclass, field

SCHELL, HERZ, EICHEL, GRAS = range(4)
SEVEN, EIGHT, NINE, TEN, UNTER, OBER, KOENIG, ASS = range(8)
SUIT_NAMES = ("SCHELL", "HERZ", "EICHEL", "GRAS")
RANK_NAMES = ("SEVEN", "EIGHT", "NINE", "TEN", "UNTER", "OBER", "KOENIG", "ASS")

PLAYER_COUNT = 4
HAND_SIZE = 5
TRICKS_TO_WIN = 3
BASE_GAME_VALUE = 2
MATCH_POINTS = 11
GESPANNT_POINTS = 9
MIN_CUT = 4

NO_TRUMP = -1


def card(suit: int, rank: int) -> int:
    return suit * 8 + rank


def suit_of(c: int) -> int:
    return c // 8


def rank_of(c: int) -> int:
    return c % 8


def card_name(c: int) -> str:
    return f"{SUIT_NAMES[suit_of(c)]}_{RANK_NAMES[rank_of(c)]}"


MAX = card(HERZ, KOENIG)
BELLO = card(SCHELL, SEVEN)
SECHER = card(EICHEL, SEVEN)
KRITTEN = (MAX, BELLO, SECHER)
KRITTE_SET = frozenset(KRITTEN)

# Phases
SCHLAG, FARBE, PLAYING, ROUND_OVER, MATCH_OVER = range(5)
PHASE_NAMES = ("SCHLAG", "FARBE", "PLAYING", "ROUND_OVER", "MATCH_OVER")

# Action codes
ACTION_SIZE = 47
FARBE_OFFSET = 8
CARD_OFFSET = 12
A_SCHUB = 44
A_BLEIBEN = 45
A_GEHEN = 46

# Round end reasons
TRICKS, GIVEN_UP = "TRICKS", "GIVEN_UP"


def team_of(player: int) -> int:
    return player % 2


def next_player(player: int) -> int:
    return (player + 1) % PLAYER_COUNT


def partner_of(player: int) -> int:
    return (player + 2) % PLAYER_COUNT


def abheber_of(dealer: int) -> int:
    return (dealer + PLAYER_COUNT - 1) % PLAYER_COUNT


# ---- Trump --------------------------------------------------------------------------------

def _compute_strengths(schlag: int, farbe: int) -> tuple[int, ...]:
    # A Kritte named as Haubi keeps its Kritte rank; Schlaege and Farbe stay trump (see Trump.kt).
    haubi = card(farbe, schlag)
    out = []
    for c in range(32):
        if c == MAX:
            s = 100
        elif c == BELLO:
            s = 99
        elif c == SECHER:
            s = 98
        elif c == haubi:
            s = 97
        elif rank_of(c) == schlag:
            s = 96
        elif suit_of(c) == farbe:
            s = 80 + rank_of(c)
        else:
            s = NO_TRUMP
        out.append(s)
    return tuple(out)


# STRENGTHS[schlag][farbe][card]
STRENGTHS = [[_compute_strengths(r, s) for s in range(4)] for r in range(8)]


def haubi_of(schlag: int, farbe: int) -> int:
    """The Haubi card; it may be a Kritte, which then keeps its Kritte rank."""
    return card(farbe, schlag)


def trick_winner_index(cards: list[int], strengths) -> int:
    best = 0
    best_s = strengths[cards[0]]
    for i in range(1, len(cards)):
        s = strengths[cards[i]]
        if s > best_s:  # strictly greater: earlier Haubi-Schlag wins ties
            best = i
            best_s = s
    if best_s > NO_TRUMP:
        return best
    lead_suit = suit_of(cards[0])
    best = 0
    for i in range(1, len(cards)):
        c = cards[i]
        if suit_of(c) == lead_suit and rank_of(c) > rank_of(cards[best]):
            best = i
    return best


# ---- Dealing ------------------------------------------------------------------------------

def random_deal(rng: _random.Random) -> tuple[list[int], int]:
    deck = list(range(32))
    rng.shuffle(deck)
    return deck, rng.randint(MIN_CUT, 32 - MIN_CUT)


def deal_cards(dealer: int, deck: list[int], cut: int):
    """Returns (hands, stock, abheber_kritten); see Deal.kt."""
    assert len(deck) == 32 and len(set(deck)) == 32
    assert MIN_CUT <= cut <= 32 - MIN_CUT
    abheber = abheber_of(dealer)
    lifted = list(deck[:cut])
    rest = deck[cut:]
    taken = []
    while lifted[-1] in KRITTE_SET:
        taken.append(lifted.pop())
    pile = rest + lifted
    pos = 0
    hands = [[] for _ in range(PLAYER_COUNT)]
    hands[abheber].extend(taken)
    order = [(dealer + 1 + i) % PLAYER_COUNT for i in range(PLAYER_COUNT)]
    for rnd in (3, 2):
        for p in order:
            count = 3 - len(taken) if (rnd == 3 and p == abheber) else rnd
            hands[p].extend(pile[pos:pos + count])
            pos += count
    return hands, pile[pos:], len(taken)


# ---- State --------------------------------------------------------------------------------

@dataclass
class State:
    scores: list[int]
    dealer: int
    round_number: int
    phase: int
    hands: list[list[int]]
    stock: list[int]
    abheber_kritten: int
    trick_leader: int
    schlag: int | None = None
    farbe: int | None = None
    laid_out: list[set[int]] | None = None
    current_trick: list[tuple[int, int]] = field(default_factory=list)  # (player, card)
    completed_tricks: list[tuple[list[tuple[int, int]], int]] = field(default_factory=list)  # (plays, winner)
    tricks_won: list[int] = field(default_factory=lambda: [0, 0])
    game_value: int = BASE_GAME_VALUE
    last_schub_team: int | None = None
    pending_schub: int | None = None  # asking player
    schubs: list[tuple[int, int]] = field(default_factory=list)  # (player, cards played before)
    hints: list[tuple[int, int, int]] = field(default_factory=list)  # (player, hint, schub index)
    schadet: tuple[int, int | None] | None = None  # (asker, answer)
    result: tuple[int, int, str] | None = None  # (winning team, points, reason)

    @property
    def forehand(self) -> int:
        return next_player(self.dealer)

    @property
    def abheber(self) -> int:
        return abheber_of(self.dealer)

    @property
    def visible_bottom_card(self) -> int:
        return self.stock[-1]

    @property
    def strengths(self):
        if self.schlag is None or self.farbe is None:
            return None
        return STRENGTHS[self.schlag][self.farbe]

    @property
    def haubi(self) -> int | None:
        if self.schlag is None or self.farbe is None:
            return None
        return haubi_of(self.schlag, self.farbe)

    def is_gespannt(self, team: int) -> bool:
        return self.scores[team] >= GESPANNT_POINTS

    @property
    def cards_played(self) -> int:
        return len(self.completed_tricks) * PLAYER_COUNT + len(self.current_trick)

    @property
    def player_to_move(self) -> int | None:
        if self.phase == SCHLAG:
            return self.forehand
        if self.phase == FARBE:
            return self.dealer
        if self.phase == PLAYING:
            return (self.trick_leader + len(self.current_trick)) % PLAYER_COUNT
        return None


def _start_round(scores, dealer, round_number, deck, cut) -> State:
    hands, stock, k = deal_cards(dealer, deck, cut)
    return State(scores=list(scores), dealer=dealer, round_number=round_number, phase=SCHLAG,
                 hands=hands, stock=stock, abheber_kritten=k, trick_leader=next_player(dealer))


def new_match(first_dealer: int, deck: list[int], cut: int) -> State:
    return _start_round([0, 0], first_dealer, 1, deck, cut)


def new_random_match(rng: _random.Random) -> State:
    first_dealer = rng.randrange(PLAYER_COUNT)
    deck, cut = random_deal(rng)
    return new_match(first_dealer, deck, cut)


def start_next_round(state: State, deck: list[int], cut: int) -> State:
    assert state.phase == ROUND_OVER, "round is not over"
    return _start_round(state.scores, next_player(state.dealer), state.round_number + 1, deck, cut)


# ---- Rules --------------------------------------------------------------------------------

def is_bekennen_pflicht(state: State) -> bool:
    """Haubi led first in the first trick and no following player has played a Kritte yet."""
    if state.strengths is None or state.completed_tricks or not state.current_trick:
        return False
    return (state.current_trick[0][1] == state.haubi
            and not any(c in KRITTE_SET for _, c in state.current_trick[1:]))


def _is_haubi_farbe(state: State, c: int) -> bool:
    return suit_of(c) == state.farbe and c != state.haubi and c not in KRITTE_SET


def playable_cards(state: State, player: int) -> list[int]:
    """Free choice; under the Bekennen-Pflicht a holder of Haubi-Farbe must play it or a Kritte."""
    hand = state.hands[player]
    if not is_bekennen_pflicht(state) or not any(_is_haubi_farbe(state, c) for c in hand):
        return list(hand)
    return [c for c in hand if _is_haubi_farbe(state, c) or c in KRITTE_SET]


def can_announce_schub(state: State, player: int) -> bool:
    team = team_of(player)
    return (state.phase == PLAYING and state.pending_schub is None
            and not state.is_gespannt(team) and state.last_schub_team != team
            and state.scores[team] + state.game_value < MATCH_POINTS)


def legal_actions(state: State, player: int) -> list[int]:
    phase = state.phase
    if phase == SCHLAG:
        return list(range(8)) if player == state.forehand else []
    if phase == FARBE:
        return [FARBE_OFFSET + s for s in range(4)] if player == state.dealer else []
    if phase == PLAYING:
        if state.pending_schub is not None:
            answering = 1 - team_of(state.pending_schub)
            return [A_BLEIBEN, A_GEHEN] if team_of(player) == answering else []
        actions = []
        if player == state.player_to_move:
            actions.extend(CARD_OFFSET + c for c in playable_cards(state, player))
        if can_announce_schub(state, player):
            actions.append(A_SCHUB)
        return actions
    return []


class IllegalAction(ValueError):
    pass


def apply(state: State, player: int, action: int, check: bool = True) -> None:
    """Applies the action in place."""
    if check and action not in legal_actions(state, player):
        raise IllegalAction(f"player {player} may not do {action} in phase {PHASE_NAMES[state.phase]}")
    if action < FARBE_OFFSET:
        state.schlag = action
        state.phase = FARBE
    elif action < CARD_OFFSET:
        _fix_haubi(state, action - FARBE_OFFSET)
    elif action < A_SCHUB:
        _play_card(state, player, action - CARD_OFFSET)
    elif action == A_SCHUB:
        state.schubs.append((player, state.cards_played))
        state.pending_schub = player
        state.last_schub_team = team_of(player)
    elif action == A_BLEIBEN:
        state.pending_schub = None
        state.game_value += 1
    elif action == A_GEHEN:
        asking_team = team_of(state.pending_schub)
        _end_round(state, (asking_team, state.game_value, GIVEN_UP))
    else:
        raise IllegalAction(f"unknown action {action}")


# ---- Table talk (mirror of the talk functions in Waddeln.kt) ------------------------------

# PartnerHint ordinals: NOTHING, LITTLE, ONE_TRICK, TWO_TRICKS, ALL_TRICKS
HINT_COUNT = 5
# SchadetAnswer ordinals
SCHADET_NO, SCHADET_A_LITTLE, SCHADET_YES = range(3)


def can_give_hint(state: State, player: int) -> bool:
    if state.pending_schub is None:
        return False
    current = len(state.schubs) - 1
    return team_of(player) != team_of(state.pending_schub) and not any(h[2] == current for h in state.hints)


def give_hint(state: State, player: int, hint: int) -> None:
    if not can_give_hint(state, player):
        raise IllegalAction(f"player {player} may not give a hint now")
    state.hints.append((player, hint, len(state.schubs) - 1))


def leads_first_trick_with_haubi(state: State, player: int) -> bool:
    return (state.strengths is not None and state.phase == PLAYING and state.pending_schub is None
            and not state.completed_tricks and not state.current_trick
            and state.player_to_move == player and state.haubi in state.hands[player])


def can_ask_schadet(state: State, player: int) -> bool:
    return state.schadet is None and leads_first_trick_with_haubi(state, player)


def ask_schadet(state: State, player: int) -> None:
    if not can_ask_schadet(state, player):
        raise IllegalAction(f"player {player} may not ask Schadder now")
    state.schadet = (player, None)


def schadet_answerer(state: State) -> int | None:
    if state.schadet is None:
        return None
    asker, answer = state.schadet
    if answer is not None or not leads_first_trick_with_haubi(state, asker):
        return None
    return partner_of(asker)


def answer_schadet(state: State, player: int, answer: int) -> None:
    if schadet_answerer(state) != player:
        raise IllegalAction(f"player {player} may not answer Schadder now")
    state.schadet = (state.schadet[0], answer)


def _fix_haubi(state: State, farbe: int) -> None:
    state.farbe = farbe
    state.phase = PLAYING
    if state.is_gespannt(0) and state.is_gespannt(1):
        haubi = state.haubi
        state.laid_out = [{c for c in hand if c in KRITTE_SET or c == haubi} for hand in state.hands]


def _play_card(state: State, player: int, c: int) -> None:
    state.hands[player].remove(c)
    state.current_trick.append((player, c))
    if len(state.current_trick) < PLAYER_COUNT:
        return
    plays = state.current_trick
    winner = plays[trick_winner_index([pc for _, pc in plays], state.strengths)][0]
    state.completed_tricks.append((plays, winner))
    state.current_trick = []
    state.trick_leader = winner
    team = team_of(winner)
    state.tricks_won[team] += 1
    if state.tricks_won[team] >= TRICKS_TO_WIN:
        _end_round(state, (team, state.game_value, TRICKS))


def _end_round(state: State, result: tuple[int, int, str]) -> None:
    team, points, _ = result
    state.scores[team] += points
    state.phase = MATCH_OVER if max(state.scores) >= MATCH_POINTS else ROUND_OVER
    state.pending_schub = None
    state.result = result


def match_winner(state: State) -> int | None:
    if state.phase != MATCH_OVER:
        return None
    return 0 if state.scores[0] >= MATCH_POINTS else 1


def seated_actor(state: State) -> int:
    """Who acts next when agents are seated (MatchRunner): Schub answered by the next player of the asked team."""
    p = state.player_to_move
    if state.pending_schub is None:
        return p
    answering = 1 - team_of(state.pending_schub)
    while team_of(p) != answering:
        p = next_player(p)
    return p


# ---- Observation --------------------------------------------------------------------------

@dataclass
class Observation:
    """What a player may know (mirror of Observation.kt)."""
    player: int
    phase: int
    scores: list[int]
    dealer: int
    hand: list[int]
    hand_sizes: list[int]
    abheber_kritten: int
    visible_bottom_card: int
    schlag: int | None
    farbe: int | None
    trick_leader: int
    current_trick: list[tuple[int, int]]
    completed_tricks: list[tuple[list[tuple[int, int]], int]]
    tricks_won: list[int]
    game_value: int
    last_schub_team: int | None
    pending_schub: int | None
    laid_out_counts: list[int] | None
    schubs: list[tuple[int, int]] = field(default_factory=list)
    hints: list[tuple[int, int, int]] = field(default_factory=list)
    schadet: tuple[int, int | None] | None = None

    @property
    def team(self) -> int:
        return team_of(self.player)

    @property
    def strengths(self):
        if self.schlag is None or self.farbe is None:
            return None
        return STRENGTHS[self.schlag][self.farbe]


def observe(state: State, player: int) -> Observation:
    laid = None
    if state.laid_out is not None:
        laid = [sum(1 for c in state.laid_out[p] if c in state.hands[p]) for p in range(PLAYER_COUNT)]
    return Observation(
        player=player, phase=state.phase, scores=state.scores, dealer=state.dealer,
        hand=state.hands[player], hand_sizes=[len(h) for h in state.hands],
        abheber_kritten=state.abheber_kritten, visible_bottom_card=state.visible_bottom_card,
        schlag=state.schlag, farbe=state.farbe, trick_leader=state.trick_leader,
        current_trick=state.current_trick, completed_tricks=state.completed_tricks,
        tricks_won=state.tricks_won, game_value=state.game_value,
        last_schub_team=state.last_schub_team, pending_schub=state.pending_schub,
        laid_out_counts=laid, schubs=state.schubs, hints=state.hints, schadet=state.schadet,
    )
