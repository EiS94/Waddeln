"""Non-learning agents. RuleBot mirrors RuleBasedAgent.kt exactly (checked by parity tests)."""
from __future__ import annotations

import random

from .engine import (
    A_BLEIBEN, A_GEHEN, A_SCHUB, ASS, CARD_OFFSET, FARBE_OFFSET, GESPANNT_POINTS, KOENIG, KRITTE_SET, MATCH_POINTS,
    NO_TRUMP,
    PLAYER_COUNT, STRENGTHS, TRICKS_TO_WIN, Observation, rank_of, team_of, trick_winner_index,
)
from .talk import last_trick_should_stay


class RandomBot:
    def __init__(self, rng: random.Random | None = None):
        self.rng = rng or random.Random()

    def choose(self, obs: Observation, legal: list[int]) -> int:
        return self.rng.choice(legal)


class RuleBot:
    """Port of RuleBasedAgent.kt. Keep both in sync."""

    def choose(self, obs: Observation, legal: list[int]) -> int:
        assert legal
        if len(legal) == 1 and legal[0] != A_SCHUB:
            return legal[0]
        first = legal[0]
        if first < FARBE_OFFSET:
            return self.choose_schlag(obs.hand)
        if first < CARD_OFFSET:
            return FARBE_OFFSET + self.choose_farbe(obs.hand, obs.schlag)
        if A_BLEIBEN in legal:
            if schub_must_stay(obs):
                return A_BLEIBEN
            last = last_trick_should_stay(obs)
            stay = self._should_stay(obs) if last is None else last
            return A_BLEIBEN if stay else A_GEHEN
        cards = [a - CARD_OFFSET for a in legal if CARD_OFFSET <= a < A_SCHUB]
        if A_SCHUB in legal and self._should_schub(obs):
            return A_SCHUB
        if not cards:
            return legal[0]
        return CARD_OFFSET + self._choose_card(obs, cards)

    @staticmethod
    def choose_schlag(hand: list[int]) -> int:
        return max(range(8), key=lambda r: (sum(1 for c in hand if rank_of(c) == r and c not in KRITTE_SET), r))

    @staticmethod
    def choose_farbe(hand: list[int], schlag: int) -> int:
        return max(range(4), key=lambda s: _hand_strength(hand, STRENGTHS[schlag][s]))

    # ---- card play ----

    def _choose_card(self, obs: Observation, playable: list[int]) -> int:
        st = obs.strengths
        trick = obs.current_trick
        if not trick:
            return self._choose_lead(obs, playable, st)
        trick_cards = [c for _, c in trick]
        winning_player, winning_card = trick[trick_winner_index(trick_cards, st)]
        is_last = len(trick) == PLAYER_COUNT - 1
        partner_winning = team_of(winning_player) == obs.team
        if partner_winning and (is_last or _is_boss(winning_card, obs, st)):
            return _cheapest(playable, st)
        winners = [c for c in playable if trick_winner_index(trick_cards + [c], st) == len(trick)]
        if not winners:
            return _cheapest(playable, st)
        if partner_winning:
            bosses = [c for c in winners if _is_boss(c, obs, st)]
            return min(bosses, key=lambda c: _value(c, st)) if bosses else _cheapest(playable, st)
        if is_last:
            return min(winners, key=lambda c: _value(c, st))
        bosses = [c for c in winners if _is_boss(c, obs, st)]
        if bosses:
            return min(bosses, key=lambda c: _value(c, st))
        return min(winners, key=lambda c: _value(c, st))

    @staticmethod
    def _choose_lead(obs: Observation, playable: list[int], st) -> int:
        bosses = [c for c in playable if st[c] > NO_TRUMP and _is_boss(c, obs, st)]
        if bosses:
            return max(bosses, key=lambda c: st[c])
        high_plain = [c for c in playable if st[c] == NO_TRUMP and rank_of(c) >= KOENIG]
        if high_plain:
            return max(high_plain, key=rank_of)
        return _cheapest(playable, st)

    # ---- Schub ----

    @staticmethod
    def _expected_tricks(obs: Observation) -> float:
        st = obs.strengths
        if st is None:
            return 0.0
        seen = _seen(obs)
        unseen = [st[c] for c in range(32) if c not in seen and st[c] > NO_TRUMP]
        expected = 0.0
        for c in obs.hand:
            s = st[c]
            if s == NO_TRUMP:
                expected += 0.3 if rank_of(c) == ASS else 0.0
            else:
                higher = sum(1 for u in unseen if u > s)
                expected += 1.0 if higher == 0 else (0.6 if higher == 1 else 0.35)
        return expected + 0.6

    def _should_schub(self, obs: Observation) -> bool:
        needed = TRICKS_TO_WIN - obs.tricks_won[obs.team]
        return self._expected_tricks(obs) >= needed + 0.8

    def _should_stay(self, obs: Observation) -> bool:
        needed = TRICKS_TO_WIN - obs.tricks_won[obs.team]
        opponents_need = TRICKS_TO_WIN - obs.tricks_won[1 - obs.team]
        chance = self._expected_tricks(obs) - needed
        opp_score = obs.scores[1 - obs.team]
        staying_loses_match = (opp_score + obs.game_value + 1 >= MATCH_POINTS
                               and opp_score + obs.game_value < MATCH_POINTS)
        threshold = 0.0 if staying_loses_match else -0.5
        return chance >= threshold or (opponents_need >= 2 and chance >= threshold - 0.5)


def _hand_strength(hand: list[int], st) -> int:
    total = 0
    for c in hand:
        s = st[c]
        total += (10 if rank_of(c) == ASS else 0) if s == NO_TRUMP else 20 + s
    return total


def _value(c: int, st) -> int:
    s = st[c]
    return rank_of(c) if s == NO_TRUMP else 100 + s


def _cheapest(cards: list[int], st) -> int:
    return min(cards, key=lambda c: _value(c, st))


def _seen(obs: Observation) -> set[int]:
    seen = set(obs.hand)
    seen.update(c for _, c in obs.current_trick)
    for plays, _ in obs.completed_tricks:
        seen.update(c for _, c in plays)
    return seen


def _is_boss(c: int, obs: Observation, st) -> bool:
    s = st[c]
    if s == NO_TRUMP:
        return False
    seen = _seen(obs)
    return not any(x not in seen and st[x] > s for x in range(32))


def schub_must_stay(obs: Observation) -> bool:
    """SchubRule.mustStay: going would make the opponents gespannt, and losing the raised game would
    leave them gespannt as well without ending the match – so staying is never worse."""
    if obs.pending_schub is None:
        return False
    asking = team_of(obs.pending_schub)
    if asking == obs.team:
        return False
    after_going = obs.scores[asking] + obs.game_value
    return after_going >= GESPANNT_POINTS and after_going + 1 < MATCH_POINTS


def apply_discard_rule(obs: Observation, legal: list[int], action: int) -> int:
    """Abwurf-Regel (mirror of DiscardRule.kt): if the chosen card would not take the trick over, play
    the cheapest legal card that does not take it over either, whoever wins the trick."""
    if not (CARD_OFFSET <= action < A_SCHUB):
        return action
    st = obs.strengths
    trick = obs.current_trick
    if st is None or not trick:
        return action
    trick_cards = [c for _, c in trick]

    def takes_over(c: int) -> bool:
        return trick_winner_index(trick_cards + [c], st) == len(trick)

    chosen = action - CARD_OFFSET
    if takes_over(chosen):
        return action
    candidates = [a - CARD_OFFSET for a in legal if CARD_OFFSET <= a < A_SCHUB and not takes_over(a - CARD_OFFSET)]
    if not candidates:
        return action
    cheapest = min(candidates, key=lambda c: _value(c, st))
    return CARD_OFFSET + cheapest if _value(cheapest, st) < _value(chosen, st) else action
