"""Chance to win the match from a score, and the training target derived from it.

The network used to learn the points of the current game (round). That ignores the match: with the
opponents on 7, giving up a game worth 2 (they reach 9) is as bad as losing one worth 3 (10) –
gespannt either way – yet −2 looks better than −3. Now the target of every decision is the change
of the team's chance to win the match caused by that game:

    target = SCALE · (V(scores after the game) − V(scores before)),   V(own, opp) = P(own team wins)

V comes from a table built by make_match_value.py: round outcomes per score are counted in
simulated matches and the chances follow by dynamic programming (scores only grow, so the table
is filled from the end of the match backwards). Summed over a match the targets add up to
SCALE · (result − V(0, 0)), so maximising them means maximising the chance to win the match.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from .engine import GESPANNT_POINTS, MATCH_POINTS

TABLE_PATH = Path(__file__).with_name("match_value.json")

# Outcomes are pooled with all scores of the same kind (who is gespannt) until a score has this many.
PRIOR_WEIGHT = 50.0


def _kind(own: int, opp: int) -> int:
    return (own >= GESPANNT_POINTS) * 2 + (opp >= GESPANNT_POINTS)


class OutcomeCounts:
    """Round outcomes seen from one team: counts[own][opp] maps (won, points) to a count."""

    def __init__(self):
        self.counts = [[{} for _ in range(MATCH_POINTS)] for _ in range(MATCH_POINTS)]

    def add_round(self, scores_before, winner_team: int, points: int) -> None:
        for team in (0, 1):
            key = (winner_team == team, points)
            cell = self.counts[scores_before[team]][scores_before[1 - team]]
            cell[key] = cell.get(key, 0) + 1

    def merge(self, other: "OutcomeCounts") -> None:
        for own in range(MATCH_POINTS):
            for opp in range(MATCH_POINTS):
                cell = self.counts[own][opp]
                for key, n in other.counts[own][opp].items():
                    cell[key] = cell.get(key, 0) + n

    def distribution(self, own: int, opp: int) -> dict[tuple[bool, int], float]:
        """Outcome probabilities for this score, smoothed towards the scores of the same kind."""
        pooled: dict[tuple[bool, int], float] = {}
        kind = _kind(own, opp)
        for a in range(MATCH_POINTS):
            for b in range(MATCH_POINTS):
                if _kind(a, b) == kind:
                    for key, n in self.counts[a][b].items():
                        pooled[key] = pooled.get(key, 0) + n
        total_pooled = sum(pooled.values())
        own_counts = self.counts[own][opp]
        total = sum(own_counts.values())
        dist: dict[tuple[bool, int], float] = {}
        for key in set(pooled) | set(own_counts):
            prior = pooled.get(key, 0) / total_pooled if total_pooled else 0.0
            dist[key] = (own_counts.get(key, 0) + PRIOR_WEIGHT * prior) / (total + PRIOR_WEIGHT)
        return dist


def solve(counts: OutcomeCounts) -> np.ndarray:
    """V[own][opp] = chance of the team with `own` points to win against `opp` points."""
    v = np.zeros((MATCH_POINTS, MATCH_POINTS))
    for total in range(2 * (MATCH_POINTS - 1), -1, -1):
        for own in range(MATCH_POINTS):
            opp = total - own
            if not 0 <= opp < MATCH_POINTS:
                continue
            value = 0.0
            for (won, points), p in counts.distribution(own, opp).items():
                if won:
                    value += p * (1.0 if own + points >= MATCH_POINTS else v[own + points][opp])
                else:
                    value += p * (0.0 if opp + points >= MATCH_POINTS else v[own][opp + points])
            v[own][opp] = value
    # The table is used from both sides: make it exactly symmetric, V(a, b) = 1 − V(b, a).
    return (v + 1.0 - v.T) / 2.0


def save(path: Path, v: np.ndarray, scale: float, info: dict) -> None:
    path.write_text(json.dumps({"scale": scale, "value": np.round(v, 5).tolist(), **info}, indent=1))


class MatchValue:
    def __init__(self, path: Path = TABLE_PATH):
        data = json.loads(path.read_text())
        self.v = np.asarray(data["value"], dtype=np.float64)
        self.scale = float(data["scale"])

    def win_chance(self, own: int, opp: int) -> float:
        if own >= MATCH_POINTS:
            return 1.0
        if opp >= MATCH_POINTS:
            return 0.0
        return float(self.v[own][opp])

    def target(self, scores_before, team: int, winner_team: int, points: int) -> float:
        """Training target for a decision of `team` in a game that `winner_team` won with `points`."""
        own, opp = scores_before[team], scores_before[1 - team]
        if winner_team == team:
            after = self.win_chance(own + points, opp)
        else:
            after = self.win_chance(own, opp + points)
        return self.scale * (after - self.win_chance(own, opp))
