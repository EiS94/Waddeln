"""Exports a checkpoint to ONNX for the app (ONNX Runtime for Android).

    python export_onnx.py checkpoints/run1/best.pt ../app/src/main/assets/models/waddeln.onnx

The model maps a float tensor "input" [N, INPUT_SIZE] (state + action features, see
features.py / Encoding.kt) to "q" [N]: the expected points of the acting team.
With --test-vectors, real inputs and the expected outputs are written as JSON so the Kotlin
side can verify that it feeds and reads the model correctly.
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

from waddeln import engine as E
from waddeln.bots import RuleBot
from waddeln.features import INPUT_SIZE, encode_actions
from waddeln.model import QNet, load_checkpoint

OPSET = 17


def export(model: QNet, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    model.eval()
    dummy = torch.zeros(3, INPUT_SIZE)
    torch.onnx.export(
        model, (dummy,), str(path),
        input_names=["input"], output_names=["q"],
        dynamic_axes={"input": {0: "n"}, "q": {0: "n"}},
        opset_version=OPSET, dynamo=False,
    )


def sample_inputs(count: int, seed: int = 7) -> list[np.ndarray]:
    """Realistic network inputs: all legal actions of decisions from rule-bot matches."""
    rng = random.Random(seed)
    bot = RuleBot()
    out = []
    while len(out) < count:
        state = E.new_random_match(rng)
        while state.phase in (E.SCHLAG, E.FARBE, E.PLAYING) and len(out) < count:
            actor = E.seated_actor(state)
            legal = E.legal_actions(state, actor)
            obs = E.observe(state, actor)
            if len(legal) > 1:
                out.append(encode_actions(obs, legal))
            E.apply(state, actor, bot.choose(obs, legal))
    return out


def verify(model: QNet, path: Path, inputs: list[np.ndarray]) -> float:
    session = ort.InferenceSession(str(path))
    worst = 0.0
    for x in inputs:
        expected = model(torch.from_numpy(x)).detach().numpy()
        got = session.run(["q"], {"input": x})[0]
        worst = max(worst, float(np.abs(expected - got).max()))
    return worst


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("checkpoint", help="checkpoint .pt, or 'random:16' for an untrained test model with hidden size 16")
    p.add_argument("output")
    p.add_argument("--test-vectors", help="write JSON test vectors here")
    args = p.parse_args()

    if args.checkpoint.startswith("random:"):
        torch.manual_seed(0)
        model = QNet((int(args.checkpoint.split(":")[1]),))
    else:
        model, _ = load_checkpoint(args.checkpoint)
    out = Path(args.output)
    export(model, out)
    inputs = sample_inputs(50)
    worst = verify(model, out, inputs)
    print(f"Exported {out} ({out.stat().st_size / 1024:.0f} KiB), max |torch - onnx| = {worst:.2e}")
    if worst > 1e-4:
        raise SystemExit("ONNX output differs from PyTorch")
    if args.test_vectors:
        session = ort.InferenceSession(str(out))
        vectors = [{"input": x.tolist(), "q": session.run(["q"], {"input": x})[0].tolist()} for x in inputs[:10]]
        Path(args.test_vectors).write_text(json.dumps({"inputSize": INPUT_SIZE, "cases": vectors}))
        print(f"Wrote test vectors to {args.test_vectors}")


if __name__ == "__main__":
    main()
