"""Widens a checkpoint to a feature layout with additional inputs, keeping what it has learned.

    python migrate_checkpoint.py old.pt new.pt --old-state 475 --old-action 49

The first layer's weights are copied to the new positions; weights of the new inputs start at zero,
so the migrated network computes exactly the same values as before until training uses the new
features. Assumes new state features were appended to the state block and new action features
to the action block (see features.py).
"""
from __future__ import annotations

import argparse

import torch

from waddeln.features import ACTION_FEATURES, INPUT_SIZE, STATE_SIZE
from waddeln.model import QNet, load_checkpoint, save_checkpoint


def migrate(old: QNet, old_state: int, old_action: int) -> QNet:
    old_first = old.net[0]
    assert old_first.in_features == old_state + old_action, "old layout does not match the checkpoint"
    assert STATE_SIZE >= old_state and ACTION_FEATURES >= old_action
    new = QNet(old.hidden, INPUT_SIZE)
    new.load_state_dict({k: v for k, v in old.state_dict().items() if not k.startswith("net.0.")}, strict=False)
    with torch.no_grad():
        w = torch.zeros(old_first.out_features, INPUT_SIZE)
        w[:, :old_state] = old_first.weight[:, :old_state]
        w[:, STATE_SIZE:STATE_SIZE + old_action] = old_first.weight[:, old_state:]
        new.net[0].weight.copy_(w)
        new.net[0].bias.copy_(old_first.bias)
    return new


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("old")
    p.add_argument("new")
    p.add_argument("--old-state", type=int, required=True)
    p.add_argument("--old-action", type=int, required=True)
    args = p.parse_args()

    old, meta = load_checkpoint(args.old)
    new = migrate(old, args.old_state, args.old_action)

    # Check: same outputs for random inputs placed in the old positions, new inputs set to anything.
    x_old = torch.rand(64, args.old_state + args.old_action)
    x_new = torch.rand(64, INPUT_SIZE)
    x_new[:, :args.old_state] = x_old[:, :args.old_state]
    x_new[:, STATE_SIZE:STATE_SIZE + args.old_action] = x_old[:, args.old_state:]
    diff = (old(x_old) - new(x_new)).abs().max().item()
    print(f"max output difference after migration: {diff:.2e}")
    assert diff < 1e-4

    save_checkpoint(args.new, new, samples=meta.get("samples", 0))
    print(f"Wrote {args.new} ({args.old_state}+{args.old_action} -> {STATE_SIZE}+{ACTION_FEATURES} inputs)")


if __name__ == "__main__":
    main()
