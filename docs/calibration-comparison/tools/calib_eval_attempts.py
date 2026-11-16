#!/usr/bin/env python3
"""Candidate B calibration attempts from the debug log of build 011fa7c (logcat tag NoTouchDiag). Standard library only.

Parses the calibration-outcome lines of a device-log excerpt (kept verbatim apart from CR line ends) and writes to the
derived directory:
  diagnostic_calibration_attempts.csv         one row per calibration attempt: the logged outcome, face position per
                                              pass, iris-feature spread and the fit error recomputed offline
  diagnostic_calibration_attempt_anchors.csv  the 27 anchors of every attempt, exactly as logged

The recomputation replicates GazeModel.fit (unchanged since e8fa01e) with Kotlin's sequential summation. Before anything
is written it must reproduce every recorded parameter of each selected session's model_snapshot.json bit for bit from
the snapshot's own anchors. Attempts the app rejected log no fit error; the recomputed value shows by how much they
missed the unchanged 0.18 limit. Descriptive only: calibration attempts are not sessions and no verdict depends on them.

Usage (from the repository root):
    python -B docs/calibration-comparison/tools/calib_eval_attempts.py \
        --log docs/calibration-comparison/data/calibration-attempts/notouchdiag-calibration-20261116.log \
        --raw docs/calibration-comparison/data/raw --selection docs/calibration-comparison/data/analysis-inputs.json \
        --derived docs/calibration-comparison/data/derived
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
from pathlib import Path

FIT_ERROR_LIMIT = 0.18
MIN_STD_X, MIN_STD_Y = 0.008, 0.006
FINISHED = re.compile(r"^(\S+ \S+) .* NoTouchDiag: calibration finished id=(\S+) failure=(\S+) model=(\S+) saved=(\S+) "
                      r"anchors=(\d+)/(\d+) retries=(\d+) duration_ms=(-?\d+)$")
ANCHOR = re.compile(r"NoTouchDiag: anchor pass=(\d+) point=(\d+) pos=(\w+) fx=(\S+) fy=(\S+) face_x=(\S+) face_y=(\S+) "
                    r"scale=(\S+) tx=(\S+) ty=(\S+) retries=(\d+)$")
MODEL = re.compile(r"NoTouchDiag: model fit_error=(\S+) ")
SESSION = re.compile(r"NoTouchDiag: screen=calibration session=(\d+) ")
FEATURES = ("fx", "fy", "face_x", "face_y", "scale", "tx", "ty")
ATTEMPT_COLUMNS = ("attempt", "logged_at", "calibration_session", "calibration_id", "failure", "model", "saved", "anchors",
                   "movement_retries", "duration_ms", "logged_fit_error", "recomputed_fit_error", "recomputed_rms_x",
                   "recomputed_rms_y", "feature_std_x", "feature_std_y", "recomputed_status",
                   "face_y_ordinary", "face_y_higher", "face_y_lower", "face_y_higher_minus_ordinary",
                   "face_y_lower_minus_ordinary", "scale_ordinary", "scale_higher", "scale_lower", "used_by_session")
ANCHOR_COLUMNS = ("attempt", "pass", "point", "position") + FEATURES + ("movement_retries",)


class AttemptError(Exception):
    """The inputs cannot support the recomputation."""


def parse(text: str) -> list[dict]:
    attempts, session = [], None
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if m := SESSION.search(line):
            session = int(m.group(1))
        elif m := FINISHED.match(line):
            stamp, cid, failure, model, saved, n, total, retries, duration = m.groups()
            attempts.append({"attempt": len(attempts) + 1, "logged_at": stamp, "calibration_session": session,
                             "calibration_id": cid, "failure": failure, "model": model, "saved": saved,
                             "anchors_logged": int(n), "anchors_total": int(total), "movement_retries": int(retries),
                             "duration_ms": int(duration), "logged_fit_error": None, "anchors": []})
            session = None
        elif (m := ANCHOR.search(line)) and attempts:
            p, k, pos, *values, retries = m.groups()
            attempts[-1]["anchors"].append({"pass": int(p), "point": int(k), "position": pos,
                                            **dict(zip(FEATURES, values)), "movement_retries": int(retries)})
        elif (m := MODEL.search(line)) and attempts:
            attempts[-1]["logged_fit_error"] = m.group(1)
    for a in attempts:
        if len(a["anchors"]) != a["anchors_logged"]:
            raise AttemptError(f"attempt {a['attempt']}: {len(a['anchors'])} anchor lines, header says {a['anchors_logged']}")
    return attempts


def _sum(values) -> float:
    total = 0.0
    for v in values:  # Kotlin sumOf/average: plain left-to-right addition (Python's sum() compensates since 3.12)
        total += v
    return total


def _avg(values) -> float:
    values = list(values)
    return _sum(values) / len(values)


def _dot(a, b) -> float:
    return _sum(a[i] * b[i] for i in range(len(a)))


def _solve(rows, targets):
    a = [[(_sum(rows[k][i] * targets[k] for k in range(len(rows))) if j == 3 else
           _sum(r[i] * r[j] for r in rows) + (0.03 if i == j and i != 0 else 0.0)) for j in range(4)] for i in range(3)]
    for col in range(3):
        pivot = max(range(col, 3), key=lambda r: (abs(a[r][col]), -r))  # Kotlin maxBy keeps the first maximum
        if abs(a[pivot][col]) < 1e-9:
            return None
        a[col], a[pivot] = a[pivot], a[col]
        divisor = a[col][col]
        for j in range(col, 4):
            a[col][j] /= divisor
        for i in range(3):
            if i != col:
                factor = a[i][col]
                for j in range(col, 4):
                    a[i][j] -= factor * a[col][j]
    return [a[i][3] for i in range(3)]


def refit(anchors: list[tuple]) -> dict:
    """GazeModel.fit on (fx, fy, face_x, face_y, scale, tx, ty) tuples, also reporting why and by how much it rejects."""
    mx, my = _avg(a[0] for a in anchors), _avg(a[1] for a in anchors)
    sx = math.sqrt(_avg((a[0] - mx) ** 2 for a in anchors))
    sy = math.sqrt(_avg((a[1] - my) ** 2 for a in anchors))
    out = {"mean_x": mx, "mean_y": my, "std_x": sx, "std_y": sy,
           "reference_face_x": _avg(a[2] for a in anchors), "reference_face_y": _avg(a[3] for a in anchors),
           "reference_scale": _avg(a[4] for a in anchors)}
    rows = [[1.0, (a[0] - mx) / sx, (a[1] - my) / sy] for a in anchors] if sx > 0 and sy > 0 else None
    wx = _solve(rows, [a[5] for a in anchors]) if rows else None
    wy = _solve(rows, [a[6] for a in anchors]) if rows else None
    if wx is not None and wy is not None:
        out.update(wx=wx, wy=wy,
                   fit_error=math.sqrt(_avg((_dot(wx, rows[i]) - anchors[i][5]) ** 2 + (_dot(wy, rows[i]) - anchors[i][6]) ** 2
                                            for i in range(len(rows)))),
                   rms_x=math.sqrt(_avg((_dot(wx, rows[i]) - anchors[i][5]) ** 2 for i in range(len(rows)))),
                   rms_y=math.sqrt(_avg((_dot(wy, rows[i]) - anchors[i][6]) ** 2 for i in range(len(rows)))))
    if sx < MIN_STD_X or sy < MIN_STD_Y:
        out["status"] = "rejected_feature_spread"  # GazeModel.fit returns before solving
    elif "fit_error" not in out:
        out["status"] = "rejected_singular"
    else:
        out["status"] = "accepted" if math.isfinite(out["fit_error"]) and out["fit_error"] <= FIT_ERROR_LIMIT else "rejected_fit_error"
    return out


def snapshot_anchors(snapshot: dict) -> list[tuple]:
    return [(a["eye"]["x"], a["eye"]["y"], a["eye"]["face_x"], a["eye"]["face_y"], a["eye"]["scale"], a["x"], a["y"])
            for a in snapshot["anchors"]]


def validate(snapshot: dict, where: str) -> None:
    r, p = refit(snapshot_anchors(snapshot)), snapshot["parameters"]
    for key in ("mean_x", "mean_y", "std_x", "std_y", "wx", "wy", "reference_face_x", "reference_face_y",
                "reference_scale", "fit_error"):
        if r.get(key) != p[key]:
            raise AttemptError(f"{where}: recomputed {key} {r.get(key)!r} differs from the snapshot's {p[key]!r}")


def attempt_tuples(attempt: dict) -> list[tuple]:
    return [tuple(float(a[k]) for k in FEATURES) for a in attempt["anchors"]]


def selected_snapshots(raw: Path, selection: Path) -> dict[str, dict]:
    wanted = {e["session_id"] for e in json.loads(selection.read_text(encoding="utf-8"))["included"]}
    found = {}
    for meta in sorted(raw.rglob("metadata.json")):
        sid = json.loads(meta.read_text(encoding="utf-8")).get("session_id")
        if sid in wanted:
            found[sid] = json.loads((meta.parent / "model_snapshot.json").read_text(encoding="utf-8"))
    if missing := sorted(wanted - set(found)):
        raise AttemptError(f"selected sessions not found under {raw}: {missing}")
    return found


def tables(attempts: list[dict], snapshots: dict[str, dict]) -> tuple[list[dict], list[dict]]:
    for sid, snap in snapshots.items():
        validate(snap, sid)
    rows, anchor_rows = [], []
    for a in attempts:
        points = attempt_tuples(a)
        r = refit(points)
        by_pos = {}
        for anchor, t in zip(a["anchors"], points):
            by_pos.setdefault(anchor["position"], []).append(t)
        fy = {pos: _avg(t[3] for t in g) for pos, g in by_pos.items()}
        sc = {pos: _avg(t[4] for t in g) for pos, g in by_pos.items()}
        used = [sid for sid, snap in snapshots.items() if snapshot_anchors(snap) == points]
        if a["logged_fit_error"] is not None and float(a["logged_fit_error"]) != r.get("fit_error"):
            raise AttemptError(f"attempt {a['attempt']}: recomputed fit error {r.get('fit_error')!r} differs from the logged "
                               f"{a['logged_fit_error']}")
        if (a["model"] == "accepted") != (r["status"] == "accepted"):
            raise AttemptError(f"attempt {a['attempt']}: logged model={a['model']} but the recomputation says {r['status']}")
        num = lambda v: "" if v is None else repr(v)
        rows.append({"attempt": a["attempt"], "logged_at": a["logged_at"], "calibration_session": a["calibration_session"],
                     "calibration_id": a["calibration_id"], "failure": a["failure"], "model": a["model"], "saved": a["saved"],
                     "anchors": f"{a['anchors_logged']}/{a['anchors_total']}", "movement_retries": a["movement_retries"],
                     "duration_ms": a["duration_ms"], "logged_fit_error": a["logged_fit_error"] or "",
                     "recomputed_fit_error": num(r.get("fit_error")), "recomputed_rms_x": num(r.get("rms_x")),
                     "recomputed_rms_y": num(r.get("rms_y")), "feature_std_x": num(r["std_x"]), "feature_std_y": num(r["std_y"]),
                     "recomputed_status": r["status"],
                     **{f"face_y_{p}": num(fy.get(p)) for p in ("ordinary", "higher", "lower")},
                     "face_y_higher_minus_ordinary": num(fy["higher"] - fy["ordinary"]) if {"higher", "ordinary"} <= fy.keys() else "",
                     "face_y_lower_minus_ordinary": num(fy["lower"] - fy["ordinary"]) if {"lower", "ordinary"} <= fy.keys() else "",
                     **{f"scale_{p}": num(sc.get(p)) for p in ("ordinary", "higher", "lower")},
                     "used_by_session": ";".join(used)})
        anchor_rows += [{"attempt": a["attempt"], **anchor} for anchor in a["anchors"]]
    return rows, anchor_rows


def write(path: Path, columns, rows) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=columns, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--log", type=Path, required=True, help="NoTouchDiag calibration excerpt")
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--derived", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        attempts = parse(args.log.read_text(encoding="utf-8"))
        rows, anchor_rows = tables(attempts, selected_snapshots(args.raw, args.selection))
    except (AttemptError, OSError, KeyError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    for name, columns, data in (("diagnostic_calibration_attempts.csv", ATTEMPT_COLUMNS, rows),
                                ("diagnostic_calibration_attempt_anchors.csv", ANCHOR_COLUMNS, anchor_rows)):
        write(args.derived / name, columns, data)
        print(f"wrote {(args.derived / name).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
