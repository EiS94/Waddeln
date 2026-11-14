"""Exports the Q-network weights to a small binary file for the pure Kotlin inference (MlpQFunction.kt),
used where ONNX Runtime is not available (the browser version).

    python export_weights.py checkpoints/run1/best.pt waddeln.mlp
    python export_weights.py waddeln.onnx waddeln.mlp          # from an already exported ONNX model

The input is the same as for export_onnx.py (a checkpoint, or 'random:16' for an untrained test model
with hidden size 16 and seed 0), or an ONNX model written by export_onnx.py.

Format (all integers uint32, all numbers little-endian):

    magic "WMLP" (4 bytes), format version (1), number of layers L
    L times: input width I, output width O, weights O*I float32 (row-major, PyTorch layout
             weight[o][i]), bias O float32

Between two layers there is a ReLU, after the last layer none; the last layer has one output (the Q-value).
The export is checked against PyTorch (checkpoint) or ONNX Runtime (ONNX model) on real network inputs.
"""
from __future__ import annotations

import argparse
import struct
from pathlib import Path

import numpy as np

MAGIC = b"WMLP"
FORMAT_VERSION = 1

Layer = tuple[np.ndarray, np.ndarray]  # weight [out, in], bias [out]


def layers_from_model(model) -> list[Layer]:
    from torch import nn
    linears = [m for m in model.net if isinstance(m, nn.Linear)]
    others = [m for m in model.net if not isinstance(m, (nn.Linear, nn.ReLU))]
    if others:
        raise SystemExit(f"unsupported layers in the model: {others}")
    return [(m.weight.detach().numpy(), m.bias.detach().numpy()) for m in linears]


def layers_from_onnx(path: Path) -> list[Layer]:
    """Reads Gemm (+ Relu) layers from an ONNX model as written by export_onnx.py."""
    import onnx
    from onnx import numpy_helper
    graph = onnx.load(str(path)).graph
    weights = {i.name: numpy_helper.to_array(i) for i in graph.initializer}
    layers: list[Layer] = []
    for node in graph.node:
        if node.op_type == "Gemm":
            attrs = {a.name: onnx.helper.get_attribute_value(a) for a in node.attribute}
            if attrs.get("alpha", 1.0) != 1.0 or attrs.get("beta", 1.0) != 1.0 or attrs.get("transA", 0) != 0:
                raise SystemExit(f"unsupported Gemm attributes {attrs}")
            w = weights[node.input[1]]
            if attrs.get("transB", 0) == 0:
                w = w.T
            layers.append((w, weights[node.input[2]]))
        elif node.op_type not in ("Relu", "Constant", "Squeeze"):
            raise SystemExit(f"unsupported ONNX operator {node.op_type}")
    return layers


def write(layers: list[Layer], path: Path) -> None:
    for k, (w, b) in enumerate(layers):
        if w.ndim != 2 or b.shape != (w.shape[0],):
            raise SystemExit(f"layer {k}: weight {w.shape} and bias {b.shape} do not fit")
        if k > 0 and w.shape[1] != layers[k - 1][0].shape[0]:
            raise SystemExit(f"layer {k} has {w.shape[1]} inputs, layer {k - 1} {layers[k - 1][0].shape[0]} outputs")
    if layers[-1][0].shape[0] != 1:
        raise SystemExit("the last layer must have exactly one output")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<II", FORMAT_VERSION, len(layers)))
        for w, b in layers:
            f.write(struct.pack("<II", w.shape[1], w.shape[0]))
            f.write(np.ascontiguousarray(w, dtype="<f4").tobytes())
            f.write(np.ascontiguousarray(b, dtype="<f4").tobytes())


def read(path: Path) -> list[Layer]:
    """Reads the file back (independent of the writer's in-memory layers)."""
    data = path.read_bytes()
    if data[:4] != MAGIC:
        raise SystemExit(f"{path} is not a WMLP file")
    version, count = struct.unpack_from("<II", data, 4)
    if version != FORMAT_VERSION:
        raise SystemExit(f"{path} has format version {version}, expected {FORMAT_VERSION}")
    pos, layers = 12, []
    for _ in range(count):
        n_in, n_out = struct.unpack_from("<II", data, pos)
        pos += 8
        w = np.frombuffer(data, "<f4", n_out * n_in, pos).reshape(n_out, n_in)
        pos += 4 * n_out * n_in
        b = np.frombuffer(data, "<f4", n_out, pos)
        pos += 4 * n_out
        layers.append((w, b))
    if pos != len(data):
        raise SystemExit(f"{path} has {len(data) - pos} trailing bytes")
    return layers


def forward(layers: list[Layer], x: np.ndarray) -> np.ndarray:
    for k, (w, b) in enumerate(layers):
        x = x @ w.T + b
        if k < len(layers) - 1:
            x = np.maximum(x, 0)
    return x[:, 0]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("model", help="checkpoint .pt, 'random:16', or an ONNX model from export_onnx.py")
    p.add_argument("output")
    args = p.parse_args()

    from export_onnx import sample_inputs
    from waddeln.features import for_model

    if args.model.endswith(".onnx"):
        import onnxruntime as ort
        layers = layers_from_onnx(Path(args.model))
        session = ort.InferenceSession(args.model)
        reference = lambda x: session.run(["q"], {"input": x})[0]
    else:
        import torch
        from waddeln.model import QNet, load_checkpoint
        if args.model.startswith("random:"):
            torch.manual_seed(0)
            model = QNet((int(args.model.split(":")[1]),))
        else:
            model, _ = load_checkpoint(args.model)
        layers = layers_from_model(model)
        reference = lambda x: model(torch.from_numpy(x)).detach().numpy()

    out = Path(args.output)
    write(layers, out)
    exported = read(out)
    input_size = exported[0][0].shape[1]
    worst = 0.0
    for x in sample_inputs(50):
        x = for_model(x, input_size)
        worst = max(worst, float(np.abs(reference(x) - forward(exported, x)).max()))
    shape = " -> ".join([str(input_size)] + [str(w.shape[0]) for w, _ in exported])
    print(f"Exported {out} ({out.stat().st_size / 1024:.0f} KiB, {shape}), max |reference - mlp| = {worst:.2e}")
    if worst > 1e-4:
        raise SystemExit("exported weights differ from the reference model")


if __name__ == "__main__":
    main()
