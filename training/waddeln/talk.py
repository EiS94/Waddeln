"""Table talk of bots – mirror of PartnerHint.kt (PartnerHints), Schadet.kt, Schlagwechsel.kt and BotTalk.kt.

- Before a bot answers a Schub it always asks its partner, who answers with partner_hint().
- Before leading the Haubi into the first trick it asks "Schadder?", the partner answers with
  schadet_answer(), and the bot decides again knowing the answer (see allowed_actions()).
  Without the Haubi it sometimes asks as a bluff (bluffs_schadet()).
- Before announcing the Schlag the player after the dealer may propose a Schlagwechsel; the dealer
  answers at once (schlagwechsel_before_turn(), called before the actor is chosen).
"""
from __future__ import annotations

import random as _random

from . import engine as E
from .engine import ASS, CARD_OFFSET, A_SCHUB, KOENIG, KRITTE_SET, NO_TRUMP, OBER, TEN, TRICKS_TO_WIN, Observation

# PartnerHint ordinals
NOTHING, LITTLE, ONE_TRICK, TWO_TRICKS, ALL_TRICKS = range(5)

_STRONGER_TRUMP_PENALTY = 0.1
_MIN_TRUMP_VALUE = 0.1
_NOTHING_BELOW = 0.35
_ONE_TRICK_FROM = 0.8
_TWO_TRICKS_FROM = 1.6
_ALL_TRICKS_MARGIN = 0.25


def _unseen(obs: Observation) -> list[int]:
    seen = set(obs.hand)
    seen.update(c for _, c in obs.current_trick)
    for plays, _ in obs.completed_tricks:
        seen.update(c for _, c in plays)
    return [c for c in range(32) if c not in seen]


def sure_winner_of_current_trick(obs: Observation) -> int | None:
    """PartnerHints.sureWinnerOfCurrentTrick: the team that surely takes the trick being played."""
    st = obs.strengths
    if st is None or not obs.current_trick:
        return None
    cards = [c for _, c in obs.current_trick]
    winner = E.team_of(obs.current_trick[E.trick_winner_index(cards, st)][0])
    unseen = _unseen(obs)
    played = {p for p, _ in obs.current_trick}
    for p in range(E.PLAYER_COUNT):
        if p in played or E.team_of(p) == winner:
            continue
        candidates = obs.hand if p == obs.player else unseen
        if any(E.trick_winner_index(cards + [c], st) == len(cards) for c in candidates):
            return None
    return winner


def sure_tricks(obs: Observation) -> int:
    """PartnerHints.sureTricks: trumps in the hand that no unseen card can beat."""
    st = obs.strengths
    if st is None:
        return 0
    unseen = _unseen(obs)
    return sum(1 for c in obs.hand if st[c] != NO_TRUMP and not any(st[u] > st[c] for u in unseen))


def estimated_tricks(obs: Observation) -> float:
    """Rough number of tricks the hand can take on its own (PartnerHints.estimatedTricks)."""
    st = obs.strengths
    if st is None:
        return 0.0
    unseen = _unseen(obs)
    trumps = [c for c in obs.hand if st[c] != NO_TRUMP]
    trump_value = 0.0
    for c in trumps:
        stronger = sum(1 for u in unseen if st[u] > st[c])
        trump_value += max(1.0 - _STRONGER_TRUMP_PENALTY * stronger, _MIN_TRUMP_VALUE)
    plain_value = 0.0
    for c in obs.hand:
        if st[c] == NO_TRUMP:
            plain_value += 0.25 if E.rank_of(c) == ASS else (0.1 if E.rank_of(c) == KOENIG else 0.0)
    control = 0.35 if len(trumps) >= 4 else (0.2 if len(trumps) >= 3 else 0.0)
    return min(trump_value + plain_value + control, float(len(obs.hand)))


def partner_hint(obs: Observation) -> int:
    """PartnerHints.hintFor."""
    current = sure_winner_of_current_trick(obs)
    needed = TRICKS_TO_WIN - obs.tricks_won[obs.team] - (1 if current == obs.team else 0)
    opponents_need = TRICKS_TO_WIN - obs.tricks_won[1 - obs.team] - (1 if current == 1 - obs.team else 0)
    if needed <= 0:
        return ALL_TRICKS
    if opponents_need <= 0:
        return NOTHING
    e = estimated_tricks(obs)
    if opponents_need <= 1:
        all_tricks = sure_tricks(obs) >= needed
    else:
        all_tricks = e >= needed - _ALL_TRICKS_MARGIN
    if e < _NOTHING_BELOW:
        return NOTHING
    if e >= _ONE_TRICK_FROM and all_tricks:
        return ALL_TRICKS
    if e < _ONE_TRICK_FROM:
        return LITTLE
    if e < _TWO_TRICKS_FROM:
        return ONE_TRICK
    return TWO_TRICKS


def schadet_answer(obs: Observation) -> int:
    """Schadet.answerFor: does following with the Haubi-Farbe cost the partner a good card?"""
    if obs.schlag is None or obs.farbe is None:
        return E.SCHADET_NO
    haubi = E.haubi_of(obs.schlag, obs.farbe)
    if haubi in obs.hand:
        return E.SCHADET_YES  # the asker bluffs; the partner covers it
    farbe = [c for c in obs.hand if E.suit_of(c) == obs.farbe and c != haubi and c not in KRITTE_SET]
    if not farbe or len(farbe) >= 3:
        return E.SCHADET_NO
    given = min(E.rank_of(c) for c in farbe)
    if given >= OBER:
        return E.SCHADET_YES
    if given >= TEN:
        return E.SCHADET_A_LITTLE
    return E.SCHADET_NO


def best_rank_count(hand: list[int]) -> int:
    """Schlagwechsel.bestRankCount: most cards of one rank, Kritten don't count."""
    return max(sum(1 for c in hand if E.rank_of(c) == r and c not in KRITTE_SET) for r in range(8))


def longest_suit(hand: list[int]) -> int:
    """Schlagwechsel.longestSuit: most cards of one suit, Kritten don't count."""
    return max(sum(1 for c in hand if E.suit_of(c) == s and c not in KRITTE_SET) for s in range(4))


def proposes_schlagwechsel(obs: Observation) -> bool:
    return best_rank_count(obs.hand) < 2 and longest_suit(obs.hand) >= 3


def accepts_schlagwechsel(obs: Observation) -> bool:
    return best_rank_count(obs.hand) >= 2


def schlagwechsel_before_turn(state: E.State) -> None:
    """BotTalk.schlagwechselBeforeTurn (in place)."""
    proposer = state.forehand
    if not E.can_propose_schlagwechsel(state, proposer) or not proposes_schlagwechsel(E.observe(state, proposer)):
        return
    E.propose_schlagwechsel(state, proposer)
    E.answer_schlagwechsel(state, state.dealer, accepts_schlagwechsel(E.observe(state, state.dealer)))


def leads_haubi(state: E.State, player: int, action: int) -> bool:
    return E.leads_first_trick_with_haubi(state, player) and action == CARD_OFFSET + state.haubi


# Schadet.BLUFF_CHANCE / BLUFF_MIN_TRUMPS
BLUFF_CHANCE = 0.2
BLUFF_MIN_TRUMPS = 2


def can_bluff(state: E.State, player: int) -> bool:
    """Schadet.canBluff: leads the first trick without the Haubi but with two trumps or more."""
    st = state.strengths
    if st is None or not E.can_ask_schadet(state, player):
        return False
    hand = state.hands[player]
    return state.haubi not in hand and sum(1 for c in hand if st[c] != NO_TRUMP) >= BLUFF_MIN_TRUMPS


def bluffs_schadet(state: E.State, actor: int, action: int, rng: _random.Random) -> bool:
    """BotTalk.bluffsSchadet: about to lead a card without the Haubi and bluffs with "Schadder?"."""
    return CARD_OFFSET <= action < A_SCHUB and can_bluff(state, actor) and rng.random() < BLUFF_CHANCE


def actions_after(answer: int, state: E.State, player: int, legal: list[int]) -> list[int]:
    """On "yes" the Haubi lead is dropped (if another card can be played)."""
    if answer != E.SCHADET_YES or not E.leads_first_trick_with_haubi(state, player):
        return legal
    haubi = CARD_OFFSET + state.haubi
    others = [a for a in legal if CARD_OFFSET <= a < A_SCHUB and a != haubi]
    return others or legal


def allowed_actions(state: E.State, player: int, legal: list[int]) -> list[int]:
    """Schadet.allowedActions: restricted once the player's own "Schadder?" was answered."""
    if state.schadet is None or state.schadet[0] != player or state.schadet[1] is None:
        return legal
    return actions_after(state.schadet[1], state, player, legal)


def hint_before_answer(state: E.State, actor: int) -> None:
    """BotTalk.hintBeforeAnswer (in place)."""
    partner = E.partner_of(actor)
    if E.can_give_hint(state, partner):
        E.give_hint(state, partner, partner_hint(E.observe(state, partner)))


def asks_schadet(state: E.State, actor: int, action: int) -> bool:
    return leads_haubi(state, actor, action) and E.can_ask_schadet(state, actor)


def talks_schadet(state: E.State, actor: int, action: int, rng: _random.Random) -> bool:
    """BotTalk.talksSchadet: "Schadder?" before leading the Haubi, or as a bluff."""
    return asks_schadet(state, actor, action) or bluffs_schadet(state, actor, action, rng)


def ask_and_answer_schadet(state: E.State, actor: int) -> None:
    """BotTalk.askAndAnswerSchadet (in place)."""
    E.ask_schadet(state, actor)
    partner = E.partner_of(actor)
    E.answer_schadet(state, partner, schadet_answer(E.observe(state, partner)))
