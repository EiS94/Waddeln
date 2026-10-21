"""Table talk of bots – mirror of PartnerHint.kt (PartnerHints), Schadet.kt, Schlagwechsel.kt and BotTalk.kt.

- Before a bot answers a Schub it always asks its partner, who answers with partner_hint().
- Before leading the Haubi into the first trick it asks "Schadder?", the partner answers with
  schadet_answer(), and the bot decides again knowing the answer (see allowed_actions()).
- Before announcing the Schlag the player after the dealer may propose a Schlagwechsel; the dealer
  answers at once (schlagwechsel_before_turn(), called before the actor is chosen).
"""
from __future__ import annotations

from . import engine as E
from .engine import ASS, CARD_OFFSET, A_SCHUB, KOENIG, KRITTE_SET, NO_TRUMP, OBER, TEN, TRICKS_TO_WIN, Observation

# PartnerHint ordinals
NOTHING, LITTLE, ONE_TRICK, TWO_TRICKS, ALL_TRICKS = range(5)

_STRONGER_TRUMP_PENALTY = 0.065
_MIN_TRUMP_VALUE = 0.1
_NOTHING_BELOW = 0.35
_ONE_TRICK_FROM = 0.8
_TWO_TRICKS_FROM = 1.6
_ALL_TRICKS_MARGIN = 0.6


def estimated_tricks(obs: Observation) -> float:
    """Rough number of tricks the hand can take on its own (PartnerHints.estimatedTricks)."""
    st = obs.strengths
    if st is None:
        return 0.0
    seen = set(obs.hand)
    seen.update(c for _, c in obs.current_trick)
    for plays, _ in obs.completed_tricks:
        seen.update(c for _, c in plays)
    unseen = [c for c in range(32) if c not in seen]
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
    needed = TRICKS_TO_WIN - obs.tricks_won[obs.team]
    e = estimated_tricks(obs)
    if e < _NOTHING_BELOW:
        return NOTHING
    if e >= _ONE_TRICK_FROM and e >= needed - _ALL_TRICKS_MARGIN:
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


def proposes_schlagwechsel(obs: Observation) -> bool:
    return best_rank_count(obs.hand) < 2


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


def ask_and_answer_schadet(state: E.State, actor: int) -> None:
    """BotTalk.askAndAnswerSchadet (in place)."""
    E.ask_schadet(state, actor)
    partner = E.partner_of(actor)
    E.answer_schadet(state, partner, schadet_answer(E.observe(state, partner)))
