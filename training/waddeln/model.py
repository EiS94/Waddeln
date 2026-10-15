"""Q-network for Deep Monte-Carlo: input = state features + action features, output = expected
points of the acting player's team in the current game (round)."""
from __future__ import annotations

import random

import torch
from torch import nn

from .bots import apply_discard_rule
from .engine import A_SCHUB, Observation
from .features import INPUT_SIZE, encode_actions, for_model

DEFAULT_HIDDEN = (512, 512, 256)


class QNet(nn.Module):
    def __init__(self, hidden=DEFAULT_HIDDEN, input_size: int = INPUT_SIZE):
        super().__init__()
        self.hidden = tuple(hidden)
        self.input_size = input_size
        layers: list[nn.Module] = []
        d = input_size
        for h in self.hidden:
            layers += [nn.Linear(d, h), nn.ReLU()]
            d = h
        layers.append(nn.Linear(d, 1))
        self.net = nn.Sequential(*layers)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x).squeeze(-1)


def save_checkpoint(path, model: QNet, **extra) -> None:
    torch.save({"hidden": model.hidden, "input_size": model.input_size, "state_dict": model.state_dict(), **extra}, path)


def load_checkpoint(path) -> tuple[QNet, dict]:
    """Loads a checkpoint; its input size may belong to an older feature layout (see migrate_checkpoint.py)."""
    data = torch.load(path, map_location="cpu", weights_only=False)
    input_size = data.get("input_size") or data["state_dict"]["net.0.weight"].shape[1]
    model = QNet(data["hidden"], input_size)
    model.load_state_dict(data["state_dict"])
    model.eval()
    return model, data


class ModelAgent:
    """Chooses the action with the highest predicted value (epsilon-greedy for exploration)."""

    def __init__(self, model: QNet, epsilon: float = 0.0, rng: random.Random | None = None):
        self.model = model
        self.epsilon = epsilon
        self.rng = rng or random.Random()

    def scores(self, obs: Observation, legal: list[int]):
        x = encode_actions(obs, legal)
        with torch.no_grad():
            # Older checkpoints (before the table talk features) can still play, e.g. in evaluate.py
            return x, self.model(torch.from_numpy(for_model(x, self.model.input_size))).numpy()

    def choose_with_input(self, obs: Observation, legal: list[int]):
        """Returns (action, network input row of that action); the row is None for forced moves."""
        if len(legal) == 1 and legal[0] != A_SCHUB:
            return legal[0], None
        if self.rng.random() < self.epsilon:
            a = apply_discard_rule(obs, legal, legal[self.rng.randrange(len(legal))])
            return a, encode_actions(obs, [a])[0]
        x, q = self.scores(obs, legal)
        a = apply_discard_rule(obs, legal, legal[int(q.argmax())])
        return a, x[legal.index(a)]

    def choose(self, obs: Observation, legal: list[int]) -> int:
        return self.choose_with_input(obs, legal)[0]
