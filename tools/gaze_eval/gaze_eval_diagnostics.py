#!/usr/bin/env python3
"""Descriptive regional and pass diagnostics for the selected Iteration 3 sessions (no acceptance criteria).

Aggregates the analyzer's presentation results (summary.json, produced with --selection) by display row, display
column and pass, compares matched targets across the two passes of each session, and counts in which direction the
model clamp acted. Clamp directions are counted from the raw attempts with the analyzer's own loader and validity rule
(valid measurement samples only). Every presentation counts equally; nothing here is a verdict.

Writes to the derived directory:
  diagnostic_region_pass.csv     per session x (row | column) x (pass 1 | pass 2 | both)
  diagnostic_pass_comparison.csv per session x target: pass 1 and pass 2 mean error and their difference
  diagnostic_clamp_edges.csv     per session: valid measurement samples whose linear output left each screen edge

Usage:
    python -B tools/gaze_eval/gaze_eval_diagnostics.py --summary docs/evaluation/iteration3/data/derived/summary.json \
        --selection docs/evaluation/iteration3/data/analysis-inputs.json --raw docs/evaluation/iteration3/data/raw \
        --derived docs/evaluation/iteration3/data/derived
"""
from __future__ import annotations

import argparse
import hashlib
import math
import sys
from pathlib import Path

import analyze_gaze_eval as analyzer
import gaze_eval_figures as figs

REGION_COLUMNS = ("participant_id", "session_label", "session_id", "grouping", "group", "pass", "presentations",
                  "mean_of_presentation_mean_errors", "mean_bias_x_norm", "mean_bias_y_norm", "mean_jitter_rms_norm",
                  "valid_samples", "model_clamp_active_samples", "model_clamp_active_fraction")
PASS_COLUMNS = ("participant_id", "session_label", "session_id", "target_label", "target_id", "pass1_mean_error",
                "pass2_mean_error", "pass2_minus_pass1")
EDGE_COLUMNS = ("participant_id", "session_label", "session_id", "valid_measurement_samples", "linear_x_below_0",
                "linear_x_above_1", "linear_y_below_0_top", "linear_y_above_1_bottom", "any_axis_outside")


def _mean(values):
    return math.fsum(values) / len(values) if values else None


def region_rows(session: dict) -> list[dict]:
    """Equal-weight means over technically eligible presentations, grouped by display row / column and pass."""
    out = []
    eligible = [p for p in session["presentations"] if p["eligibility"] == "eligible"]
    for grouping, key in (("row", lambda p: figs.target_label(p["target_id"])[0]),
                          ("column", lambda p: figs.target_label(p["target_id"])[1:])):
        for group in sorted({key(p) for p in session["presentations"]}):
            for pas in ("1", "2", "both"):
                ps = [p for p in eligible if key(p) == group and (pas == "both" or str(p["pass_number"]) == pas)]
                valid = sum(p["valid_samples"] for p in ps)
                clamp = sum(p["model_clamp_active_samples"] for p in ps)
                out.append({
                    "participant_id": session["participant_id"], "session_label": session["session_label"],
                    "session_id": session["session_id"], "grouping": grouping, "group": group, "pass": pas,
                    "presentations": len(ps),
                    "mean_of_presentation_mean_errors": _mean([p["final"]["mean_error"]["norm"] for p in ps]),
                    "mean_bias_x_norm": _mean([p["final"]["bias_x"]["norm"] for p in ps]),
                    "mean_bias_y_norm": _mean([p["final"]["bias_y"]["norm"] for p in ps]),
                    "mean_jitter_rms_norm": _mean([p["final"]["jitter_rms"]["norm"] for p in ps]),
                    "valid_samples": valid, "model_clamp_active_samples": clamp,
                    "model_clamp_active_fraction": clamp / valid if valid else None,
                })
    return out


def pass_rows(session: dict, protocol: dict) -> list[dict]:
    out = []
    for t in figs.target_order(protocol):
        errors = {}
        for p in session["presentations"]:
            if p["target_id"] == t["target_id"]:
                errors[p["pass_number"]] = p["final"]["mean_error"]["norm"] if p["eligibility"] == "eligible" else None
        a, b = errors.get(1), errors.get(2)
        out.append({"participant_id": session["participant_id"], "session_label": session["session_label"],
                    "session_id": session["session_id"], "target_label": t["label"], "target_id": t["target_id"],
                    "pass1_mean_error": a, "pass2_mean_error": b,
                    "pass2_minus_pass1": None if a is None or b is None else b - a})
    return out


def edge_row(session: dict, raw_root: Path, protocol_path: Path) -> dict:
    """Direction of model-clamp activity among valid measurement samples, using the analyzer's loader."""
    path = raw_root / session["path"]
    loaded = analyzer.load_session(path, raw_root, analyzer.load_protocol(protocol_path))
    if loaded.metadata["session_id"] != session["session_id"]:
        raise SystemExit(f"error: {path} holds {loaded.metadata['session_id']}, expected {session['session_id']}")
    valid = [a for a in loaded.attempts if a.phase == "measurement" and a.valid]
    lin = [a.linear for a in valid]
    return {"participant_id": session["participant_id"], "session_label": session["session_label"],
            "session_id": session["session_id"], "valid_measurement_samples": len(valid),
            "linear_x_below_0": sum(x < 0 for x, _ in lin), "linear_x_above_1": sum(x > 1 for x, _ in lin),
            "linear_y_below_0_top": sum(y < 0 for _, y in lin), "linear_y_above_1_bottom": sum(y > 1 for _, y in lin),
            "any_axis_outside": sum(not (0 <= x <= 1 and 0 <= y <= 1) for x, y in lin)}


def run(summary_path: Path, selection_path: Path, raw_root: Path, derived: Path,
        protocol_path: Path = figs.DEFAULT_PROTOCOL) -> dict:
    summary, _, protocol, sessions, _, _ = figs.prepare(summary_path, selection_path, protocol_path)
    region = [r for s in sessions for r in region_rows(s)]
    passes = [r for s in sessions for r in pass_rows(s, protocol)]
    edges = [edge_row(s, raw_root.resolve(), protocol_path) for s in sessions]
    for s, e in zip(sessions, edges):
        expected = s["diagnostics"]["model_clamp_active_samples"]
        if e["any_axis_outside"] != expected:  # same samples, same rule as the analyzer: must agree exactly
            raise SystemExit(f"error: {s['session_id']}: {e['any_axis_outside']} clamped samples, analyzer reports {expected}")
    derived.mkdir(parents=True, exist_ok=True)
    figs._write(derived / "diagnostic_region_pass.csv", figs.table_csv(REGION_COLUMNS, region))
    figs._write(derived / "diagnostic_pass_comparison.csv", figs.table_csv(PASS_COLUMNS, passes))
    figs._write(derived / "diagnostic_clamp_edges.csv", figs.table_csv(EDGE_COLUMNS, edges))
    return {"summary_sha256": hashlib.sha256(summary_path.read_bytes()).hexdigest(), "region": region,
            "passes": passes, "edges": edges}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--raw", type=Path, required=True, help="raw-data root the analyzer read (session paths are relative to it)")
    parser.add_argument("--derived", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, default=figs.DEFAULT_PROTOCOL)
    args = parser.parse_args(argv)
    try:
        run(args.summary, args.selection, args.raw, args.derived, args.protocol)
    except figs.FigureInputError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    for name in ("diagnostic_region_pass.csv", "diagnostic_pass_comparison.csv", "diagnostic_clamp_edges.csv"):
        print(f"wrote {(args.derived / name).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
