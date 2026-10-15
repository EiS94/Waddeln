"""Playing matches with seated agents and collecting Deep Monte-Carlo training samples."""
from __future__ import annotations

import random

import numpy as np

from . import engine as E
from . import talk as T
from .bots import apply_discard_rule
from .features import INPUT_SIZE, encode_actions


def play_match(agents, rng: random.Random, record_seats=(), max_rounds: int = 200):
    """Plays one match. Agents with `choose_with_input` on seats in `record_seats` produce samples.

    Returns (X, y, final_state): X holds the network inputs of all recorded decisions, y the points
    the decider's team gained (+) or conceded (−) in that game (round). All seats talk at the table
    like bots (talk.py); a decision that leads to "Schadder?" is taken again with the answer and
    only the second one is recorded.
    """
    state = E.new_random_match(rng)
    xs: list[np.ndarray] = []
    ys: list[float] = []
    for _ in range(max_rounds):
        pending: list[tuple[int, np.ndarray]] = []
        while state.phase in (E.SCHLAG, E.FARBE, E.PLAYING):
            actor = E.seated_actor(state)
            T.hint_before_answer(state, actor)
            legal = T.allowed_actions(state, actor, E.legal_actions(state, actor))
            obs = E.observe(state, actor)
            agent = agents[actor]
            row = None
            if actor in record_seats:
                action, row = agent.choose_with_input(obs, legal)
            else:
                action = agent.choose(obs, legal)
            if T.asks_schadet(state, actor, action):
                T.ask_and_answer_schadet(state, actor)
                continue
            if row is not None:
                pending.append((actor, row))
            E.apply(state, actor, action, check=False)
        team, points, _ = state.result
        for seat, row in pending:
            xs.append(row)
            ys.append(points if E.team_of(seat) == team else -points)
        if state.phase == E.MATCH_OVER:
            break
        deck, cut = E.random_deal(rng)
        state = E.start_next_round(state, deck, cut)
    if xs:
        X = np.stack(xs).astype(np.float32)
    else:
        X = np.zeros((0, INPUT_SIZE), dtype=np.float32)
    return X, np.asarray(ys, dtype=np.float32), state


def evaluate(agent_team0, agent_team1, matches: int, seed: int = 0) -> dict:
    """Plays matches with agent_team0 on seats 0/2 and agent_team1 on seats 1/3."""
    rng = random.Random(seed)
    wins = 0
    point_diff = 0
    rounds = 0
    for _ in range(matches):
        agents = [agent_team0, agent_team1, agent_team0, agent_team1]
        _, _, end = play_match(agents, rng)
        wins += E.match_winner(end) == 0
        point_diff += end.scores[0] - end.scores[1]
        rounds += end.round_number
    return {
        "matches": matches,
        "win_rate": wins / matches,
        "points_per_round": point_diff / rounds,
    }


# ---- Batched self-play --------------------------------------------------------------------

MODEL = None  # marker for a seat played by the network in BatchedSelfPlay


class _Slot:
    """One match running inside BatchedSelfPlay."""

    def __init__(self, state, agents, record):
        self.state = state
        self.agents = agents      # per seat: MODEL or an agent with .choose(obs, legal)
        self.record = record      # seats whose (network) decisions become samples
        self.pending: list[tuple[int, np.ndarray]] = []


class BatchedSelfPlay:
    """Plays many matches at once so that all pending network decisions are evaluated in one
    forward pass. On CPUs this is much faster than one small forward pass per decision, because
    the weights are read from memory once per batch instead of once per decision.

    `new_config(rng)` returns (agents, record_seats) for each new match; agents contains MODEL for
    network seats. Network decisions are epsilon-greedy.
    """

    def __init__(self, model, games: int, rng: random.Random, new_config, epsilon: float = 0.0):
        import torch  # local import keeps the module usable without torch for pure simulation
        self._torch = torch
        self.model = model
        self.rng = rng
        self.new_config = new_config
        self.epsilon = epsilon
        self.slots = [self._new_slot() for _ in range(games)]
        self._xs: list[np.ndarray] = []
        self._ys: list[float] = []

    def _new_slot(self) -> _Slot:
        agents, record = self.new_config(self.rng)
        return _Slot(E.new_random_match(self.rng), agents, set(record))

    def collect(self, min_samples: int):
        """Plays until at least `min_samples` samples are finished; returns (X, y)."""
        while len(self._ys) < min_samples:
            self._step()
        X = np.stack(self._xs).astype(np.float32)
        y = np.asarray(self._ys, dtype=np.float32)
        self._xs, self._ys = [], []
        return X, y

    def _step(self) -> None:
        # 1) Advance every slot until the network has to decide.
        waiting = []  # (slot, actor, obs, legal)
        for i, slot in enumerate(self.slots):
            while True:
                st = slot.state
                if st.phase in (E.ROUND_OVER, E.MATCH_OVER):
                    self._finish_round(slot)
                    if st.phase == E.MATCH_OVER:
                        slot = self.slots[i] = self._new_slot()
                    else:
                        slot.state = E.start_next_round(st, *E.random_deal(self.rng))
                    continue
                actor = E.seated_actor(st)
                T.hint_before_answer(st, actor)
                legal = T.allowed_actions(st, actor, E.legal_actions(st, actor))
                obs = E.observe(st, actor)
                agent = slot.agents[actor]
                if agent is not MODEL:
                    a = agent.choose(obs, legal)
                    if T.asks_schadet(st, actor, a):
                        T.ask_and_answer_schadet(st, actor)  # decides again with the answer
                    else:
                        E.apply(st, actor, a, check=False)
                    continue
                if len(legal) == 1 and legal[0] != E.A_SCHUB:
                    E.apply(st, actor, legal[0], check=False)
                    continue
                if self.rng.random() < self.epsilon:
                    a = apply_discard_rule(obs, legal, legal[self.rng.randrange(len(legal))])
                    if T.asks_schadet(st, actor, a):
                        T.ask_and_answer_schadet(st, actor)
                        continue
                    self._record(slot, actor, encode_actions(obs, [a])[0])
                    E.apply(st, actor, a, check=False)
                    continue
                waiting.append((slot, actor, obs, legal))
                break

        # 2) One forward pass for all waiting decisions.
        if not waiting:
            return
        inputs =[encode_actions(obs, legal) for _, _, obs, legal in waiting]
        with self._torch.no_grad():
            q = self.model(self._torch.from_numpy(np.concatenate(inputs))).numpy()
        offset = 0
        for (slot, actor, obs, legal), x in zip(waiting, inputs):
            k = int(q[offset:offset + len(legal)].argmax())
            offset += len(legal)
            k = legal.index(apply_discard_rule(obs, legal, legal[k]))
            if T.asks_schadet(slot.state, actor, legal[k]):
                # Not recorded: the actor decides again in the next step, knowing the answer.
                T.ask_and_answer_schadet(slot.state, actor)
                continue
            self._record(slot, actor, x[k])
            E.apply(slot.state, actor, legal[k], check=False)

    @staticmethod
    def _record(slot: _Slot, actor: int, row: np.ndarray) -> None:
        if actor in slot.record:
            slot.pending.append((actor, row))

    def _finish_round(self, slot: _Slot) -> None:
        team, points, _ = slot.state.result
        for seat, row in slot.pending:
            self._xs.append(row)
            self._ys.append(points if E.team_of(seat) == team else -points)
        slot.pending = []
