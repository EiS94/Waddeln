"""Network input encoding – must match rules-core/.../ml/Encoding.kt (FeatureEncoder) exactly.

Seats are relative to the observing player: 0 = self, 1 = next (left), 2 = partner, 3 = right.
"""
from __future__ import annotations

import numpy as np

from .engine import (
    ACTION_SIZE, CARD_OFFSET, A_SCHUB, FARBE, GESPANNT_POINTS, MATCH_POINTS, NO_TRUMP, PLAYER_COUNT,
    PLAYING, SCHLAG, Observation, team_of, trick_winner_index,
)

STATE_SIZE = 478
ACTION_FEATURES = ACTION_SIZE + 4
INPUT_SIZE = STATE_SIZE + ACTION_FEATURES


def _trump_value(st, c: int) -> float:
    if st is None:
        return 0.0
    s = st[c]
    return 0.0 if s == NO_TRUMP else (s - 79) / 21.0


def encode_state(obs: Observation) -> np.ndarray:
    f = np.zeros(STATE_SIZE, dtype=np.float32)
    me = obs.player

    def rel(seat: int) -> int:
        return (seat - me + PLAYER_COUNT) % PLAYER_COUNT

    st = obs.strengths
    for c in obs.hand:
        f[c] = 1.0
    known = set(obs.hand)
    for plays, _ in obs.completed_tricks:
        for p, c in plays:
            f[32 + rel(p) * 32 + c] = 1.0
            known.add(c)
    for p, c in obs.current_trick:
        f[160 + rel(p) * 32 + c] = 1.0
        known.add(c)
    known.add(obs.visible_bottom_card)
    for c in range(32):
        if c not in known:
            f[288 + c] = 1.0
    f[320 + obs.visible_bottom_card] = 1.0
    if st is not None:
        for c in range(32):
            if st[c] > NO_TRUMP:
                f[352 + c] = 1.0
                f[384 + c] = (st[c] - 79) / 21.0
    if obs.schlag is not None:
        f[416 + obs.schlag] = 1.0
    if obs.farbe is not None:
        f[424 + obs.farbe] = 1.0
    phase_index = {SCHLAG: 0, FARBE: 1, PLAYING: 2}.get(obs.phase)
    if phase_index is not None:
        f[428 + phase_index] = 1.0
    f[431 + rel(obs.dealer)] = 1.0
    if 0 <= obs.abheber_kritten < 4:
        f[435 + obs.abheber_kritten] = 1.0
    own = obs.team
    opp = 1 - own
    f[439 + obs.tricks_won[own]] = 1.0
    f[443 + obs.tricks_won[opp]] = 1.0
    f[447 + rel(obs.trick_leader)] = 1.0
    f[451] = (obs.game_value - 2) / 4.0
    f[452] = obs.scores[own] / MATCH_POINTS
    f[453] = obs.scores[opp] / MATCH_POINTS
    f[454] = 1.0 if obs.scores[own] >= GESPANNT_POINTS else 0.0
    f[455] = 1.0 if obs.scores[opp] >= GESPANNT_POINTS else 0.0
    f[456] = 1.0 if obs.last_schub_team == own else 0.0
    f[457] = 1.0 if obs.last_schub_team == opp else 0.0
    asking_team = None if obs.pending_schub is None else obs.pending_schub % 2
    f[458] = 1.0 if asking_team == opp else 0.0
    f[459] = 1.0 if asking_team == own else 0.0

    def can_schub(team: int) -> bool:
        return (obs.phase == PLAYING and obs.pending_schub is None
                and obs.scores[team] < GESPANNT_POINTS and obs.last_schub_team != team
                and obs.scores[team] + obs.game_value < MATCH_POINTS)

    f[460] = 1.0 if can_schub(own) else 0.0
    f[461] = 1.0 if can_schub(opp) else 0.0
    laid = obs.laid_out_counts
    for r in range(PLAYER_COUNT):
        seat = (me + r) % PLAYER_COUNT
        f[462 + r] = (laid[seat] if laid is not None else 0) / 3.0
        f[467 + r] = obs.hand_sizes[seat] / 5.0
    f[466] = 1.0 if laid is not None else 0.0
    if len(obs.current_trick) < 4:
        f[471 + len(obs.current_trick)] = 1.0
    leading = _leading_play(obs)
    if leading is not None:
        f[475] = 1.0 if team_of(leading[0]) == own else 0.0
        f[476] = 1.0 if team_of(leading[0]) == opp else 0.0
        f[477] = _trump_value(st, leading[1])
    return f


def _leading_play(obs: Observation):
    """(player, card) currently winning the trick, or None."""
    st = obs.strengths
    if st is None or not obs.current_trick:
        return None
    return obs.current_trick[trick_winner_index([c for _, c in obs.current_trick], st)]


def _would_win_trick(obs: Observation, c: int) -> bool:
    st = obs.strengths
    trick = obs.current_trick
    if st is None or not trick:
        return False
    return trick_winner_index([x for _, x in trick] + [c], st) == len(trick)


def _is_unbeatable(obs: Observation, c: int) -> bool:
    st = obs.strengths
    if st is None or st[c] == NO_TRUMP:
        return False
    known = set(obs.hand)
    known.update(x for _, x in obs.current_trick)
    for plays, _ in obs.completed_tricks:
        known.update(x for _, x in plays)
    known.add(obs.visible_bottom_card)
    return not any(x not in known and st[x] > st[c] for x in range(32))


def encode_action(obs: Observation, action: int) -> np.ndarray:
    f = np.zeros(ACTION_FEATURES, dtype=np.float32)
    f[action] = 1.0
    if CARD_OFFSET <= action < A_SCHUB:
        c = action - CARD_OFFSET
        st = obs.strengths
        f[ACTION_SIZE] = 1.0 if (st is not None and st[c] > NO_TRUMP) else 0.0
        f[ACTION_SIZE + 1] = _trump_value(st, c)
        f[ACTION_SIZE + 2] = 1.0 if _would_win_trick(obs, c) else 0.0
        f[ACTION_SIZE + 3] = 1.0 if _is_unbeatable(obs, c) else 0.0
    return f


def encode_actions(obs: Observation, actions: list[int]) -> np.ndarray:
    """Batch of network inputs, one row per candidate action."""
    state = encode_state(obs)
    out = np.empty((len(actions), INPUT_SIZE), dtype=np.float32)
    out[:, :STATE_SIZE] = state
    for i, a in enumerate(actions):
        out[i, STATE_SIZE:] = encode_action(obs, a)
    return out
