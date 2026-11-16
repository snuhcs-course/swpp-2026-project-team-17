#!/usr/bin/env python3
"""Descriptive diagnostics for selected calibration-comparison sessions, per head-position block (no acceptance criteria).

Adapted from tools/gaze_eval/gaze_eval_diagnostics.py (72411ec, feature/gaze-evaluation-home-screen-iter3) to the
three-block structure, with two additions: axis gain (does the mean estimate follow the target across the screen, or
is it compressed toward one part of it?) and the logged face position per block and phase.

Aggregates the analyzer's presentation results (summary.json, produced with --selection) by block, display row,
display column and pass; compares matched targets across the two passes of each block; counts in which direction the
model clamp acted (from the raw attempts, with the analyzer's own loader and validity rule, cross-checked against the
analyzer's clamp count); fits the mean estimate against the target per axis; and tabulates the analyzer's face
summaries. Every technically eligible presentation counts equally. Nothing here is a verdict or changes one.

Writes to the derived directory:
  diagnostic_region_pass.csv     per session x block x (block | row | column) x (pass 1 | pass 2 | both)
  diagnostic_pass_comparison.csv per session x block x target: pass 1 and pass 2 mean error and their difference
  diagnostic_clamp_edges.csv     per session x block: valid measurement samples whose linear output left each screen edge
  diagnostic_axis_gain.csv       per session x block x axis x stream (smoothed | linear): least-squares slope of the
                                 presentation centroid on the target and the spread of per-target mean estimates
  diagnostic_face_by_block.csv   per session x block x phase: logged face_x, face_y and scale (median, quartiles) and
                                 their offset from the calibration reference (mean of the 27 anchors)
  diagnostic_calibration_anchors.csv   per session: the 27 calibration anchors as recorded in model_snapshot.json
  diagnostic_calibration_summary.csv   per session (long format): face position per calibration pass, iris-feature
                                 separation of calibration rows/columns, spread of the three passes per target, the
                                 fitted model's sensitivity to each feature, and the fit error (diagnostic only)

Usage (from the repository root):
    python -B docs/calibration-comparison/tools/calib_eval_diagnostics.py \
        --summary docs/calibration-comparison/data/derived/summary.json \
        --selection docs/calibration-comparison/data/analysis-inputs.json \
        --raw docs/calibration-comparison/data/raw --derived docs/calibration-comparison/data/derived
"""
from __future__ import annotations

import argparse
import hashlib
import math
import sys
from pathlib import Path

import analyze_calibration_eval as analyzer
import calib_eval_figures as figs

ID_COLUMNS = ("calibration_id", "participant_id", "session_label", "session_id", "block_index", "head_position")
REGION_COLUMNS = ID_COLUMNS + ("grouping", "group", "pass", "presentations", "mean_of_presentation_mean_errors",
                               "mean_bias_x_norm", "mean_bias_y_norm", "mean_jitter_rms_norm", "valid_samples",
                               "model_clamp_active_samples", "model_clamp_active_fraction")
PASS_COLUMNS = ID_COLUMNS + ("target_label", "target_id", "pass1_mean_error", "pass2_mean_error", "pass2_minus_pass1")
EDGE_COLUMNS = ID_COLUMNS + ("valid_measurement_samples", "linear_x_below_0_left", "linear_x_above_1_right",
                             "linear_y_below_0_top", "linear_y_above_1_bottom", "any_axis_outside")
GAIN_COLUMNS = ID_COLUMNS + ("axis", "stream", "eligible_presentations", "slope", "intercept", "mean_target",
                             "mean_estimate", "per_target_mean_min", "per_target_mean_max", "per_target_range",
                             "target_range", "range_ratio")
FACE_COLUMNS = ID_COLUMNS + ("phase", "attempts_with_features",
                             "face_x_median", "face_x_q1", "face_x_q3", "face_y_median", "face_y_q1", "face_y_q3",
                             "scale_median", "scale_q1", "scale_q3", "reference_face_x", "reference_face_y",
                             "reference_scale", "face_x_minus_reference", "face_y_minus_reference",
                             "scale_ratio_to_reference")
ANCHOR_COLUMNS = ID_COLUMNS[:4] + ("pass", "point", "calibration_position", "target_x_norm", "target_y_norm", "f_x",
                                   "f_y", "face_x", "face_y", "scale", "movement_retries", "accepted_uptime_ms")
CALIBRATION_COLUMNS = ID_COLUMNS[:4] + ("quantity", "group", "value", "unit")
STREAMS = (("smoothed", "final"), ("linear", "linear_diagnostic"))


def _mean(values):
    return math.fsum(values) / len(values) if values else None


def _ids(session: dict, block: dict) -> dict:
    return {"calibration_id": session["calibration_id"], "participant_id": session["participant_id"],
            "session_label": session["session_label"], "session_id": session["session_id"],
            "block_index": block["block_index"], "head_position": block["head_position"]}


def region_rows(session: dict, block: dict) -> list[dict]:
    """Equal-weight means over technically eligible presentations, by block / display row / display column and pass."""
    out = []
    presentations = block["presentations"]
    eligible = [p for p in presentations if p["eligibility"] == "eligible"]
    for grouping, key in (("block", lambda p: "all"),
                          ("row", lambda p: figs.target_label(p["target_id"])[0]),
                          ("column", lambda p: figs.target_label(p["target_id"])[1:])):
        for group in sorted({key(p) for p in presentations}):
            for pas in ("1", "2", "both"):
                ps = [p for p in eligible if key(p) == group and (pas == "both" or str(p["pass_number"]) == pas)]
                valid = sum(p["valid_samples"] for p in ps)
                clamp = sum(p["model_clamp_active_samples"] for p in ps)
                out.append({
                    **_ids(session, block), "grouping": grouping, "group": group, "pass": pas, "presentations": len(ps),
                    "mean_of_presentation_mean_errors": _mean([p["final"]["mean_error"]["norm"] for p in ps]),
                    "mean_bias_x_norm": _mean([p["final"]["bias_x"]["norm"] for p in ps]),
                    "mean_bias_y_norm": _mean([p["final"]["bias_y"]["norm"] for p in ps]),
                    "mean_jitter_rms_norm": _mean([p["final"]["jitter_rms"]["norm"] for p in ps]),
                    "valid_samples": valid, "model_clamp_active_samples": clamp,
                    "model_clamp_active_fraction": clamp / valid if valid else None,
                })
    return out


def pass_rows(session: dict, block: dict, protocol: dict) -> list[dict]:
    out = []
    for t in figs.target_order(protocol):
        errors = {}
        for p in block["presentations"]:
            if p["target_id"] == t["target_id"]:
                errors[p["pass_number"]] = p["final"]["mean_error"]["norm"] if p["eligibility"] == "eligible" else None
        a, b = errors.get(1), errors.get(2)
        out.append({**_ids(session, block), "target_label": t["label"], "target_id": t["target_id"],
                    "pass1_mean_error": a, "pass2_mean_error": b,
                    "pass2_minus_pass1": None if a is None or b is None else b - a})
    return out


def gain_rows(session: dict, block: dict, protocol: dict) -> list[dict]:
    """Least-squares slope of presentation centroid on target per axis (1 = follows the target, < 1 = compressed) and
    the spread of per-target equal-weight mean estimates, for the measured (smoothed, clamped) and the linear stream."""
    out = []
    eligible = [p for p in block["presentations"] if p["eligibility"] == "eligible"]
    for axis, key in (("x", "x_norm"), ("y", "y_norm")):
        for stream, field_name in STREAMS:
            pairs = [(p[f"target_{key}"], p[field_name]["centroid"][key]) for p in eligible if p[field_name]]
            row = {**_ids(session, block), "axis": axis, "stream": stream, "eligible_presentations": len(pairs)}
            targets = sorted({t for t, _ in pairs})
            if len(targets) < 2:
                out.append({**row, **{c: None for c in GAIN_COLUMNS if c not in row}})
                continue
            mt, me = _mean([t for t, _ in pairs]), _mean([e for _, e in pairs])
            slope = math.fsum((t - mt) * (e - me) for t, e in pairs) / math.fsum((t - mt) ** 2 for t, _ in pairs)
            per_target = {}
            for t in figs.target_order(protocol):
                values = [p[field_name]["centroid"][key] for p in eligible
                          if p[field_name] and p["target_id"] == t["target_id"]]
                if values:
                    per_target[t["target_id"]] = _mean(values)
            lo, hi = min(per_target.values()), max(per_target.values())
            target_range = targets[-1] - targets[0]
            out.append({**row, "slope": slope, "intercept": me - slope * mt, "mean_target": mt, "mean_estimate": me,
                        "per_target_mean_min": lo, "per_target_mean_max": hi, "per_target_range": hi - lo,
                        "target_range": target_range, "range_ratio": (hi - lo) / target_range})
    return out


def face_rows(session: dict, block: dict) -> list[dict]:
    """The analyzer's logged-face summaries (inspection only), with the offset from the calibration reference."""
    ref = session["calibration_reference_face"]
    out = []
    for phase in ("preparation", "countdown", "measurement_valid"):
        f = block["face_inspection"][phase]
        row = {**_ids(session, block), "phase": phase, "attempts_with_features": f["attempts_with_features"],
               "reference_face_x": ref.get("reference_face_x"), "reference_face_y": ref.get("reference_face_y"),
               "reference_scale": ref.get("reference_scale")}
        for name in ("face_x", "face_y", "scale"):
            d = f[name] or {}
            row.update({f"{name}_median": d.get("median"), f"{name}_q1": d.get("q1"), f"{name}_q3": d.get("q3")})
        row["face_x_minus_reference"] = (None if row["face_x_median"] is None or row["reference_face_x"] is None
                                         else row["face_x_median"] - row["reference_face_x"])
        row["face_y_minus_reference"] = (None if row["face_y_median"] is None or row["reference_face_y"] is None
                                         else row["face_y_median"] - row["reference_face_y"])
        row["scale_ratio_to_reference"] = (None if row["scale_median"] is None or not row["reference_scale"]
                                           else row["scale_median"] / row["reference_scale"])
        out.append(row)
    return out


def load_raw(session: dict, raw_root: Path, protocol_path: Path):
    path = raw_root / session["path"]
    loaded = analyzer.load_session(path, raw_root, analyzer.load_protocol(protocol_path))
    if loaded.metadata["session_id"] != session["session_id"]:
        raise SystemExit(f"error: {path} holds {loaded.metadata['session_id']}, expected {session['session_id']}")
    return loaded


def calibration_anchor_rows(session: dict, loaded) -> list[dict]:
    """The 27 calibration anchors of model_snapshot.json as recorded (pass, point, target, median features)."""
    ids = {k: session[k] for k in ID_COLUMNS[:4]}
    return [{**ids, "pass": a["pass"], "point": a["point"], "calibration_position": a.get("calibration_position"),
             "target_x_norm": a["x"], "target_y_norm": a["y"], "f_x": a["eye"]["x"], "f_y": a["eye"]["y"],
             "face_x": a["eye"]["face_x"], "face_y": a["eye"]["face_y"], "scale": a["eye"]["scale"],
             "movement_retries": a.get("movement_retries"), "accepted_uptime_ms": a.get("accepted_uptime_ms")}
            for a in loaded.snapshot["anchors"]]


def calibration_summary_rows(session: dict, loaded) -> list[dict]:
    """Descriptive calibration context (long format): logged face position per pass, the iris-feature separation
    between calibration rows/columns, the spread of the three passes at each target, and the fitted model's
    sensitivity to each feature (weight / feature standard deviation, from the snapshot parameters)."""
    ids = {k: session[k] for k in ID_COLUMNS[:4]}
    anchors = loaded.snapshot["anchors"]
    params = loaded.snapshot["parameters"]
    out = []

    def add(quantity, group, value, unit):
        out.append({**ids, "quantity": quantity, "group": group, "value": value, "unit": unit})
    for p in sorted({a["pass"] for a in anchors}):
        mine = [a["eye"] for a in anchors if a["pass"] == p]
        for name in ("face_x", "face_y", "scale"):
            add(f"pass_mean_{name}", f"pass {p}", _mean([e[name] for e in mine]), "image-normalized (logged face)")
    for y in sorted({a["y"] for a in anchors}):
        add("row_mean_f_y", f"target y {y}", _mean([a["eye"]["y"] for a in anchors if a["y"] == y]), "iris feature f_y")
    for x in sorted({a["x"] for a in anchors}):
        add("column_mean_f_x", f"target x {x}", _mean([a["eye"]["x"] for a in anchors if a["x"] == x]), "iris feature f_x")
    for key in sorted({(a["y"], a["x"]) for a in anchors}):
        same = [a["eye"] for a in anchors if (a["y"], a["x"]) == key]
        target = f"target ({key[1]}, {key[0]})"
        add("between_pass_range_f_x", target, max(e["x"] for e in same) - min(e["x"] for e in same), "iris feature f_x")
        add("between_pass_range_f_y", target, max(e["y"] for e in same) - min(e["y"] for e in same), "iris feature f_y")
    unit = "normalized screen units per unit of the feature (linear output, before the clamp)"
    add("model_sensitivity", "x per f_x", params["wx"][1] / params["std_x"], unit)
    add("model_sensitivity", "x per f_y", params["wx"][2] / params["std_y"], unit)
    add("model_sensitivity", "y per f_x", params["wy"][1] / params["std_x"], unit)
    add("model_sensitivity", "y per f_y", params["wy"][2] / params["std_y"], unit)
    add("fit_error_diagnostic", "all anchors", params["fit_error"], "RMS residual of the fit inputs (not gaze accuracy)")
    return out


def edge_rows(session: dict, loaded) -> list[dict]:
    """Direction of model-clamp activity among valid measurement samples per block, using the analyzer's loader."""
    out = []
    for block in session["blocks"]:
        valid = [a for a in loaded.attempts
                 if a.block == block["block_index"] - 1 and a.phase == "measurement" and a.valid]
        lin = [a.linear for a in valid]
        row = {**_ids(session, block), "valid_measurement_samples": len(valid),
               "linear_x_below_0_left": sum(x < 0 for x, _ in lin), "linear_x_above_1_right": sum(x > 1 for x, _ in lin),
               "linear_y_below_0_top": sum(y < 0 for _, y in lin), "linear_y_above_1_bottom": sum(y > 1 for _, y in lin),
               "any_axis_outside": sum(not (0 <= x <= 1 and 0 <= y <= 1) for x, y in lin)}
        # Same samples, same rule as the analyzer: the counts must agree exactly.
        if row["valid_measurement_samples"] != block["counts"]["valid_measurement_samples"]:
            raise SystemExit(f"error: {session['session_id']} block {block['block_index']}: {len(valid)} valid samples, "
                             f"analyzer reports {block['counts']['valid_measurement_samples']}")
        if row["any_axis_outside"] != block["diagnostics"]["model_clamp_active_samples"]:
            raise SystemExit(f"error: {session['session_id']} block {block['block_index']}: {row['any_axis_outside']} "
                             f"clamped samples, analyzer reports {block['diagnostics']['model_clamp_active_samples']}")
        out.append(row)
    return out


def run(summary_path: Path, selection_path: Path, raw_root: Path, derived: Path,
        protocol_path: Path = figs.DEFAULT_PROTOCOL) -> dict:
    summary, _, protocol, sessions, _, _ = figs.prepare(summary_path, selection_path, protocol_path)
    region = [r for s in sessions for b in s["blocks"] for r in region_rows(s, b)]
    passes = [r for s in sessions for b in s["blocks"] for r in pass_rows(s, b, protocol)]
    gains = [r for s in sessions for b in s["blocks"] for r in gain_rows(s, b, protocol)]
    faces = [r for s in sessions for b in s["blocks"] for r in face_rows(s, b)]
    loaded = [load_raw(s, raw_root.resolve(), protocol_path) for s in sessions]
    edges = [r for s, raw in zip(sessions, loaded) for r in edge_rows(s, raw)]
    anchors = [r for s, raw in zip(sessions, loaded) for r in calibration_anchor_rows(s, raw)]
    calibration = [r for s, raw in zip(sessions, loaded) for r in calibration_summary_rows(s, raw)]
    derived.mkdir(parents=True, exist_ok=True)
    tables = {"diagnostic_region_pass.csv": (REGION_COLUMNS, region),
              "diagnostic_pass_comparison.csv": (PASS_COLUMNS, passes),
              "diagnostic_clamp_edges.csv": (EDGE_COLUMNS, edges),
              "diagnostic_axis_gain.csv": (GAIN_COLUMNS, gains),
              "diagnostic_face_by_block.csv": (FACE_COLUMNS, faces),
              "diagnostic_calibration_anchors.csv": (ANCHOR_COLUMNS, anchors),
              "diagnostic_calibration_summary.csv": (CALIBRATION_COLUMNS, calibration)}
    for name, (columns, rows) in tables.items():
        figs._write(derived / name, figs.table_csv(columns, rows))
    return {"summary_sha256": hashlib.sha256(summary_path.read_bytes()).hexdigest(), "region": region,
            "passes": passes, "edges": edges, "gains": gains, "faces": faces, "tables": list(tables)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--raw", type=Path, required=True, help="raw-data root the analyzer read (session paths are relative to it)")
    parser.add_argument("--derived", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, default=figs.DEFAULT_PROTOCOL)
    args = parser.parse_args(argv)
    try:
        result = run(args.summary, args.selection, args.raw, args.derived, args.protocol)
    except figs.FigureInputError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    for name in result["tables"]:
        print(f"wrote {(args.derived / name).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
