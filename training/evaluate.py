"""Compares checkpoints against the rule bot (or random play) over many matches.

    python evaluate.py checkpoints/pi_run1/model_*.pt --matches 2000 --workers 8
    python evaluate.py new.pt --vs old.pt --matches 2000      # head-to-head between networks

Each checkpoint plays the same deals (same seeds) on seats 0/2 against the opponent on 1/3.
"""
from __future__ import annotations

import argparse
import math
import random
from concurrent.futures import ProcessPoolExecutor

import torch

from waddeln.bots import RandomBot, RuleBot
from waddeln.model import ModelAgent, load_checkpoint
from waddeln.selfplay import evaluate


def _run(job):
    path, opponent, matches, seed = job
    torch.set_num_threads(1)
    model, _ = load_checkpoint(path)
    if opponent == "rule":
        opp = RuleBot()
    elif opponent == "random":
        opp = RandomBot(random.Random(seed))
    else:
        opp = ModelAgent(load_checkpoint(opponent)[0])
    return path, evaluate(ModelAgent(model), opp, matches, seed=seed)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("checkpoints", nargs="+")
    p.add_argument("--matches", type=int, default=1000)
    p.add_argument("--opponent", choices=["rule", "random"], default="rule")
    p.add_argument("--vs", help="checkpoint of an opponent network (overrides --opponent)")
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--chunk", type=int, default=100, help="matches per job")
    args = p.parse_args()

    opponent = args.vs or args.opponent
    jobs = [(path, opponent, args.chunk, seed)
            for path in args.checkpoints
            for seed in range(0, args.matches, args.chunk)]
    totals = {path: {"wins": 0.0, "matches": 0, "ppr": 0.0} for path in args.checkpoints}
    with ProcessPoolExecutor(args.workers) as pool:
        for path, r in pool.map(_run, jobs):
            t = totals[path]
            t["wins"] += r["win_rate"] * r["matches"]
            t["ppr"] += r["points_per_round"] * r["matches"]
            t["matches"] += r["matches"]

    print(f"opponent: {opponent}")
    print(f"{'checkpoint':55s} {'win rate':>14s} {'points/game':>12s}")
    for path, t in totals.items():
        n = t["matches"]
        wr = t["wins"] / n
        se = math.sqrt(wr * (1 - wr) / n)
        print(f"{path:55s} {wr:6.3f} ± {1.96 * se:.3f} {t['ppr'] / n:+12.3f}")


if __name__ == "__main__":
    main()
