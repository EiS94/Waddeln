"""Deep Monte-Carlo self-play training (DouZero-style) on the CPU.

Actor processes play many matches at once with the current network (epsilon-greedy) and send
(input, return) samples to the learner, which fits the Q-network with MSE.

Example (Raspberry Pi 5):
    python train_dmc.py --actors 1 --learner-threads 3 --hours 12 --out checkpoints/run1
"""
from __future__ import annotations

import argparse
import csv
import queue
import random
import signal
import time
import traceback
from pathlib import Path

import numpy as np
import torch
import torch.multiprocessing as mp

from waddeln.bots import RandomBot, RuleBot
from waddeln.features import INPUT_SIZE
from waddeln.model import DEFAULT_HIDDEN, ModelAgent, QNet, load_checkpoint, save_checkpoint
from waddeln.selfplay import MODEL, BatchedSelfPlay, evaluate


def actor_loop(actor_seed: int, shared: QNet, sample_queue, stop, args) -> None:
    try:
        _actor_loop(actor_seed, shared, sample_queue, stop, args)
    except Exception:
        print(f"Actor {actor_seed} crashed:\n{traceback.format_exc()}", flush=True)
        raise


def _actor_loop(actor_seed: int, shared: QNet, sample_queue, stop, args) -> None:
    torch.set_num_threads(1)
    rng = random.Random(actor_seed)
    local = QNet(shared.hidden, shared.input_size)
    local.load_state_dict(shared.state_dict())
    local.eval()
    rule_bot = RuleBot()
    random_bot = RandomBot(rng)

    def new_config(r: random.Random):
        """Opponents of a new match: rule bot, random play or self-play (MODEL = network seat)."""
        x = r.random()
        if x < args.rule_opponents + args.random_opponents:
            # Model team vs a bot team: only the model's decisions are recorded.
            bot = rule_bot if x < args.rule_opponents else random_bot
            model_team = r.randrange(2)
            agents = [MODEL if seat % 2 == model_team else bot for seat in range(4)]
            return agents, [s for s in range(4) if s % 2 == model_team]
        return [MODEL] * 4, [0, 1, 2, 3]

    selfplay = BatchedSelfPlay(local, args.games, rng, new_config, epsilon=args.epsilon)
    chunks = 0
    while not stop.is_set():
        sample_queue.put(selfplay.collect(args.chunk_samples))
        chunks += 1
        if chunks % args.sync_every == 0:
            local.load_state_dict(shared.state_dict())


class ReplayBuffer:
    def __init__(self, capacity: int):
        self.X = np.zeros((capacity, INPUT_SIZE), dtype=np.float32)
        self.y = np.zeros(capacity, dtype=np.float32)
        self.capacity = capacity
        self.size = 0
        self.pos = 0

    def add(self, X: np.ndarray, y: np.ndarray) -> None:
        n = len(y)
        idx = (self.pos + np.arange(n)) % self.capacity
        self.X[idx] = X
        self.y[idx] = y
        self.pos = (self.pos + n) % self.capacity
        self.size = min(self.size + n, self.capacity)

    def sample(self, n: int, rng: np.random.Generator):
        idx = rng.integers(0, self.size, n)
        return torch.from_numpy(self.X[idx]), torch.from_numpy(self.y[idx])


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", default="checkpoints/run")
    p.add_argument("--resume", help="checkpoint to continue from")
    p.add_argument("--actors", type=int, default=1, help="self-play processes")
    p.add_argument("--hours", type=float, default=1.0, help="wall-clock training time")
    p.add_argument("--max-samples", type=int, default=0, help="stop after this many samples (0 = no limit)")
    p.add_argument("--batch", type=int, default=512)
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--buffer", type=int, default=200_000)
    p.add_argument("--replay-ratio", type=float, default=1.0, help="training rows per new sample")
    p.add_argument("--epsilon", type=float, default=0.05)
    p.add_argument("--rule-opponents", type=float, default=0.2, help="share of matches vs. the rule bot")
    p.add_argument("--random-opponents", type=float, default=0.05, help="share of matches vs. random play")
    p.add_argument("--games", type=int, default=32,
                   help="matches each actor plays at once (their decisions share one network call)")
    p.add_argument("--chunk-samples", type=int, default=1000, help="samples per message from actor to learner")
    p.add_argument("--sync-every", type=int, default=2, help="chunks between weight refreshes in actors")
    p.add_argument("--eval-every", type=int, default=5_000_000, help="samples between evaluations")
    p.add_argument("--eval-matches", type=int, default=200)
    p.add_argument("--learner-threads", type=int, default=3)
    p.add_argument("--seed", type=int, default=1)
    args = p.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(args.learner_threads)
    torch.manual_seed(args.seed)

    if args.resume:
        model, meta = load_checkpoint(args.resume)
        samples_total = meta.get("samples", 0)
        if model.input_size != INPUT_SIZE:
            raise SystemExit(f"{args.resume} has {model.input_size} inputs, the features have {INPUT_SIZE}: "
                             f"convert it with migrate_checkpoint.py first")
    else:
        model, samples_total = QNet(DEFAULT_HIDDEN), 0
    model.share_memory()
    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)

    ctx = mp.get_context("spawn")
    sample_queue = ctx.Queue(maxsize=64)
    stop = ctx.Event()
    actor_starts = 0

    def start_actor() -> mp.Process:
        nonlocal actor_starts
        actor_starts += 1
        proc = ctx.Process(target=actor_loop, args=(args.seed * 100_000 + actor_starts, model, sample_queue, stop, args),
                           daemon=True)
        proc.start()
        return proc

    actors = [start_actor() for _ in range(args.actors)]

    buffer = ReplayBuffer(args.buffer)
    np_rng = np.random.default_rng(args.seed)
    log_path = out / "log.csv"
    new_log = not log_path.exists()
    log_file = log_path.open("a", newline="")
    log = csv.writer(log_file)
    if new_log:
        log.writerow(["time_s", "samples", "updates", "loss", "eval_win_rate_vs_rule", "eval_points_per_round_vs_rule"])

    start = time.time()
    samples_at_start = samples_total
    deadline = start + args.hours * 3600
    updates = 0
    owed_rows = 0.0
    next_eval = samples_total + args.eval_every
    loss_sum, loss_n = 0.0, 0
    last_print = start
    best_win_rate = -1.0
    last_eval_samples = -1
    # Graceful stop: create the file <out>/STOP (works everywhere), or Ctrl+C / SIGTERM.
    stop_file = out / "STOP"
    stop_file.unlink(missing_ok=True)
    def on_sigterm(*_):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, on_sigterm)
    print(f"Training with {args.actors} actors, output {out} (stop early: create {stop_file})", flush=True)
    try:
        while time.time() < deadline and (args.max_samples == 0 or samples_total < args.max_samples):
            if stop_file.exists():
                print("STOP file found, finishing", flush=True)
                stop_file.unlink(missing_ok=True)
                break
            for i, proc in enumerate(actors):
                if not proc.is_alive():
                    print(f"Actor process {proc.pid} died (exit code {proc.exitcode}), restarting", flush=True)
                    actors[i] = start_actor()
            try:
                X, y = sample_queue.get(timeout=5)
            except queue.Empty:
                continue
            buffer.add(X, y)
            samples_total += len(y)
            owed_rows += len(y) * args.replay_ratio
            if buffer.size < args.batch * 4:
                continue
            while owed_rows >= args.batch:
                xb, yb = buffer.sample(args.batch, np_rng)
                loss = torch.nn.functional.mse_loss(model(xb), yb)
                optimizer.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 10.0)
                optimizer.step()
                owed_rows -= args.batch
                updates += 1
                loss_sum += loss.item()
                loss_n += 1

            now = time.time()
            if now - last_print > 30 and loss_n:
                rate = (samples_total - samples_at_start) / max(1.0, now - start)
                print(f"[{(now - start) / 60:6.1f} min] samples {samples_total:,} updates {updates:,} "
                      f"loss {loss_sum / loss_n:.3f} ({rate:,.0f} samples/s)", flush=True)
                log.writerow([round(now - start), samples_total, updates, round(loss_sum / loss_n, 4), "", ""])
                log_file.flush()
                loss_sum, loss_n = 0.0, 0
                last_print = now

            if samples_total >= next_eval:
                next_eval += args.eval_every
                last_eval_samples = samples_total
                win_rate = run_eval(model, args, out, samples_total, log, start)
                log_file.flush()
                if win_rate > best_win_rate:
                    best_win_rate = win_rate
                    save_checkpoint(out / "best.pt", model, samples=samples_total, win_rate=win_rate)
    except KeyboardInterrupt:
        print("Interrupted, saving", flush=True)
    finally:
        stop.set()
        save_checkpoint(out / "latest.pt", model, samples=samples_total)
        if samples_total != last_eval_samples:
            win_rate = run_eval(model, args, out, samples_total, log, start)
            if win_rate > best_win_rate:
                save_checkpoint(out / "best.pt", model, samples=samples_total, win_rate=win_rate)
        log_file.close()
        for a in actors:
            a.join(timeout=10)
            if a.is_alive():
                a.terminate()


def run_eval(model: QNet, args, out: Path, samples_total: int, log, start: float) -> float:
    model.eval()
    result = evaluate(ModelAgent(model), RuleBot(), args.eval_matches, seed=12345)
    model.train()
    save_checkpoint(out / f"model_{samples_total // 1000:08d}k.pt", model, samples=samples_total,
                    win_rate=result["win_rate"])
    print(f"  eval vs rule bot: win rate {result['win_rate']:.3f}, "
          f"points/round {result['points_per_round']:+.3f}", flush=True)
    log.writerow([round(time.time() - start), samples_total, "", "", result["win_rate"], round(result["points_per_round"], 4)])
    return result["win_rate"]


if __name__ == "__main__":
    main()
