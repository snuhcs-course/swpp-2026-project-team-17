"""Fetch official research-only iTracker assets into ignored local APK assets.
Run from repository root: python tools/itracker/prepare.py
Read https://github.com/CSAILVision/GazeCapture/blob/master/LICENSE.md before use.
Do not publish the downloaded weights or this research APK in a public repository.
"""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import struct
import urllib.request

REVISION = "e4af90da3c4c9068fbce433fa648d1f9bc7a394b"
BASE = f"https://raw.githubusercontent.com/CSAILVision/GazeCapture/{REVISION}/"
ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "app/src/main/assets/itracker"


def unpack_mean(blob):
    def varint(i):
        value = shift = 0
        while True:
            byte = blob[i]
            i += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, i
            shift += 7
    result = bytearray()
    i = 0
    while i < len(blob):
        key, i = varint(i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            _, i = varint(i)
        elif wire == 2:
            size, i = varint(i)
            if field == 5:
                result.extend(blob[i:i+size])
            i += size
        elif wire == 5:
            if field == 5:
                result.extend(blob[i:i+4])
            i += 4
        elif wire == 1:
            i += 8
        else:
            raise ValueError(f"Unexpected protobuf wire type: {wire}")
    assert len(result) == 3*224*224*4
    assert all(0 <= value <= 255 for value, in struct.iter_unpack("<f", result))
    return result


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    files = {
        "deploy.prototxt": "models/itracker_deploy.prototxt",
        "weights.caffemodel": "models/snapshots/itracker25x_iter_92000.caffemodel",
        "LICENSE.md": "LICENSE.md",
        **{f"mean_{name}.binaryproto": f"models/mean_images/mean_{name}_224.binaryproto"
           for name in ("left", "right", "face")},
    }
    def fetch(item):
        name, path = item
        with urllib.request.urlopen(BASE+path, timeout=180) as response:
            data = response.read()
        original_hash = hashlib.sha256(data).hexdigest()
        if name == "deploy.prototxt":
            data = data.replace(b"input_dim: 256", b"input_dim: 1")
        (DEST/name).write_bytes(data)
        return name, {"url": BASE+path, "upstream_sha256": original_hash,
                      "asset_sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        manifest = dict(pool.map(fetch, files.items()))
    for name in ("left", "right", "face"):
        data = unpack_mean((DEST/f"mean_{name}.binaryproto").read_bytes())
        file = f"mean_{name}.f32"
        (DEST/file).write_bytes(data)
        manifest[file] = {"asset_sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                          "format": "little-endian float32 NCHW 1x3x224x224, BGR means"}
    (DEST/"manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"revision": REVISION, "assets": len(manifest)}, indent=2))


if __name__ == "__main__":
    main()
