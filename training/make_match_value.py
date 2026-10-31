"""Builds waddeln/match_value.json: the chance to win the match from every score (see match_value.py).

Plays matches, counts the outcome of every game (round) per score and solves for the win chances.
SCALE is chosen so that the training targets are as large on average as the points of a game, so
the bot temperatures of the app (in points) keep their meaning.

    python make_match_value.py --matches 300000 --workers 8
    python make_match_value.py --model checkpoints/run1/best.pt --matches 100000   # outcomes of a trained net
"""
from __future__ import annotations

import argparse
import random
from multiprocessing import Pool
from pathlib import Path

import numpy as np

from waddeln import engine as E
from waddeln import talk as T
from waddeln.bots import RuleBot
from waddeln.match_value import TABLE_PATH, OutcomeCounts, save, solve


def _play(job):
    seed, matches, model_path = job
    rng = random.Random(seed)
    if model_path:
        import torch
        from waddeln.model import ModelAgent, load_checkpoint
        torch.set_num_threads(1)
        agent = ModelAgent(load_checkpoint(model_path)[0], rng=rng)
    else:
        agent = RuleBot()
    agents = [agent] * 4
    counts = OutcomeCounts()
    rounds = []  # (scores before, winner team, points)
    for _ in range(matches):
        state = E.new_random_match(rng)
        while True:
            before = list(state.scores)
            _play_round(state, agents, rng)
            team, points, _ = state.result
            counts.add_round(before, team, points)
            rounds.append((before, team, points))
            if state.phase == E.MATCH_OVER:
                break
            state = E.start_next_round(state, *E.random_deal(rng))
    return counts, rounds


def _play_round(state, agents, rng) -> None:
    """Plays the current game (round) in place, talking like selfplay.play_match."""
    while state.phase in (E.SCHLAG, E.FARBE, E.PLAYING):
        T.schlagwechsel_before_turn(state)
        actor = E.seated_actor(state)
        T.hint_before_answer(state, actor)
        T.advice_before_turn(state, actor, rng)
        legal = T.allowed_actions(state, actor, E.legal_actions(state, actor))
        action = agents[actor].choose(E.observe(state, actor), legal)
        if T.talks_schadet(state, actor, action, rng):
            T.ask_and_answer_schadet(state, actor)
            continue
        E.apply(state, actor, action, check=False)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--matches", type=int, default=300_000)
    p.add_argument("--workers", type=int, default=8)
    p.add_argument("--model", help="checkpoint whose play is counted (default: rule bot)")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--out", default=str(TABLE_PATH))
    args = p.parse_args()

    per_job = 2000
    jobs = [(args.seed * 1_000_003 + i, min(per_job, args.matches - i * per_job), args.model)
            for i in range((args.matches + per_job - 1) // per_job)]
    counts = OutcomeCounts()
    rounds = []
    with Pool(args.workers) as pool:
        for i, (c, r) in enumerate(pool.imap_unordered(_play, jobs), 1):
            counts.merge(c)
            rounds.extend(r)
            print(f"\r{i}/{len(jobs)} jobs", end="", flush=True)
    print()
    v = solve(counts)

    # Scale: mean |target| = mean points of a game.
    deltas, points = [], []
    for before, team, pts in rounds:
        own, opp = before[team], before[1 - team]
        after = 1.0 if own + pts >= E.MATCH_POINTS else v[own + pts][opp]
        deltas.append(abs(after - v[own][opp]))
        points.append(pts)
    scale = float(np.mean(points) / np.mean(deltas))

    save(Path(args.out), v, round(scale, 3),
         {"matches": args.matches, "rounds": len(rounds), "model": args.model or "rule bot"})
    print(f"{len(rounds):,} games, scale {scale:.2f}, V(0,0) = {v[0][0]:.3f}")
    print("own\\opp " + " ".join(f"{b:5d}" for b in range(E.MATCH_POINTS)))
    for a in range(E.MATCH_POINTS):
        print(f"{a:7d} " + " ".join(f"{v[a][b]:5.2f}" for b in range(E.MATCH_POINTS)))


if __name__ == "__main__":
    main()
