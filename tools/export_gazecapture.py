#!/usr/bin/env python3
"""Export the official frozen iTracker checkpoint; no dataset, training, or fine-tuning.

See docs/gazecapture.md for setup. Upstream files and their research license are
pinned to one commit. Model classes are loaded from that source, without its
unused legacy training imports. The output includes the original mean images.
"""
import argparse
import ast
import copy
import hashlib
import json
from pathlib import Path
import subprocess

import numpy as np
import onnx
import onnxruntime as ort
import scipy.io
import torch
from torch import nn

UPSTREAM = "e4af90da3c4c9068fbce433fa648d1f9bc7a394b"
ROOT = Path(__file__).resolve().parents[1]
FILES = ["ITrackerModel.py", "ITrackerData.py", "README.md", "LICENSE.md",
         "checkpoint.pth.tar", "mean_face_224.mat", "mean_left_224.mat", "mean_right_224.mat"]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch(cache):
    cache.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        target = cache / name
        if target.exists():
            continue
        url = f"https://raw.githubusercontent.com/CSAILVision/GazeCapture/{UPSTREAM}/pytorch/{name}"
        temporary = target.with_suffix(target.suffix + ".download")
        subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "2",
                        "--max-time", "180", url, "-o", str(temporary)], check=True)
        temporary.replace(target)


def original_model(cache):
    source = ast.parse((cache / "ITrackerModel.py").read_text())
    # Original classes only use torch and nn; importing the old training stack is unnecessary.
    classes = ast.Module(body=[node for node in source.body if isinstance(node, ast.ClassDef)], type_ignores=[])
    namespace = {"torch": torch, "nn": nn}
    exec(compile(classes, str(cache / "ITrackerModel.py"), "exec"), namespace)
    model = namespace["ITrackerModel"]()
    checkpoint = torch.load(cache / "checkpoint.pth.tar", map_location="cpu", weights_only=True)
    weights = {key.removeprefix("module."): value for key, value in checkpoint["state_dict"].items()}
    model.load_state_dict(weights, strict=True)
    return model.eval()


class WithMeans(nn.Module):
    def __init__(self, model, cache):
        super().__init__()
        self.model = model
        for key, name in [("face_mean", "face"), ("left_mean", "left"), ("right_mean", "right")]:
            mean = scipy.io.loadmat(cache / f"mean_{name}_224.mat")["image_mean"]
            mean = np.asarray(mean, dtype=np.float32).transpose(2, 0, 1)[None] / 255
            self.register_buffer(key, torch.from_numpy(mean.copy()))

    def forward(self, face, left_eye, right_eye, face_grid):
        return self.model(face - self.face_mean, left_eye - self.left_mean,
                          right_eye - self.right_mean, face_grid)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / ".tools" / "gazecapture" / UPSTREAM)
    parser.add_argument("--offline", action="store_true", help="Use previously downloaded source files")
    args = parser.parse_args()
    cache = args.cache
    if not args.offline:
        fetch(cache)
    hashes = {name: sha256(cache / name) for name in FILES}
    manifest_path = ROOT / "app/src/main/assets/itracker-manifest.json"
    if manifest_path.exists():
        previous = json.loads(manifest_path.read_text())
        if previous["upstream_commit"] == UPSTREAM and previous["source_sha256"] != hashes:
            raise RuntimeError("Cached source files do not match the verified manifest")

    torch.set_num_threads(2)
    original = original_model(cache)
    export_model = copy.deepcopy(original)
    # Same normalization equation, with an ONNX-supported implementation.
    for branch in [export_model.eyeModel, export_model.faceModel.conv]:
        for i in [3, 7]:
            branch.features[i] = nn.LocalResponseNorm(5, alpha=0.0001, beta=0.75, k=1.0)
    reference = WithMeans(original, cache).eval()
    converted = WithMeans(export_model, cache).eval()
    rng = np.random.default_rng(17)
    examples = []
    for case in range(4):
        images = [rng.random((1, 3, 224, 224), dtype=np.float32) for _ in range(3)]
        if case == 0:
            images = [np.zeros_like(image) for image in images]
        grid = np.zeros((1, 25, 25), dtype=np.float32)
        grid[:, 3 + case:19, 4:18 + case] = 1
        examples.append([*images, grid.reshape(1, 625)])
    inputs = tuple(torch.from_numpy(array) for array in examples[1])
    target = ROOT / "app/src/main/assets/itracker.onnx"
    temporary = target.with_suffix(".onnx.tmp")
    torch.onnx.export(converted, inputs, temporary, opset_version=13, dynamo=False,
                      input_names=["face", "left_eye", "right_eye", "face_grid"], output_names=["gaze_cm"])
    onnx.checker.check_model(str(temporary))
    session = ort.InferenceSession(str(temporary), providers=["CPUExecutionProvider"])
    max_error = 0.0
    for example in examples:
        tensors = tuple(torch.from_numpy(array) for array in example)
        with torch.no_grad():
            expected = reference(*tensors).numpy()
            replaced = converted(*tensors).numpy()
        actual = session.run(None, dict(zip(["face", "left_eye", "right_eye", "face_grid"], example)))[0]
        np.testing.assert_allclose(replaced, expected, atol=1e-4, rtol=1e-4)
        np.testing.assert_allclose(actual, expected, atol=1e-4, rtol=1e-4)
        max_error = max(max_error, float(np.abs(actual - expected).max()))
    temporary.replace(target)
    license_target = ROOT / "app/src/main/assets/licenses/GazeCapture-LICENSE.md"
    license_target.write_bytes((cache / "LICENSE.md").read_bytes())
    manifest = {
        "upstream_repository": "https://github.com/CSAILVision/GazeCapture",
        "upstream_commit": UPSTREAM,
        "source_sha256": hashes,
        "onnx_sha256": sha256(target),
        "format": "ONNX opset 13, float32, batch 1; official RGB mean images embedded",
        "inputs": {"face": [1, 3, 224, 224], "left_eye": [1, 3, 224, 224],
                   "right_eye": [1, 3, 224, 224], "face_grid": [1, 625]},
        "output": "gaze_cm [1,2]: camera-relative centimetres, not display coordinates",
        "verification": {"cases": len(examples), "max_abs_error_cm": max_error,
                         "torch": torch.__version__, "onnx": onnx.__version__, "onnxruntime": ort.__version__},
        "license": "GazeCapture Research License; see licenses/GazeCapture-LICENSE.md",
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Exported {target.name} ({target.stat().st_size:,} bytes); {len(examples)} parity cases, max error {max_error:.8f} cm")


if __name__ == "__main__":
    main()
