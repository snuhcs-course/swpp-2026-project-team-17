#!/usr/bin/env python3
"""Figures for NoTouch Iteration 3 gaze evaluation sessions (home-screen candidate, log schema v3).

Reads only the analyzer's summary.json (analyze_gaze_eval.py), the analysis-input manifest and the protocol manifest.
Presentation eligibility, centroids and mean errors come from the analyzer; nothing here re-derives validity,
eligibility or verdicts. Two figures per selected session:

  <participant>_<label>_<id8>_gaze_map.{svg,png}      targets vs mean estimated gaze on the display
  <participant>_<label>_<id8>_target_error.{svg,png}  mean target error per target and pass

and the plotted tables in the derived directory:

  figure_presentations.csv       one row per presentation (what the error bars show)
  figure_target_centroids.csv    one row per session and target (what the gaze map shows)
  figures-manifest.json          inputs, versions, shared scales and output checksums

Usage:
    python tools/gaze_eval/gaze_eval_figures.py --summary docs/evaluation/iteration3/data/derived/summary.json \
        --selection docs/evaluation/iteration3/data/analysis-inputs.json \
        --derived docs/evaluation/iteration3/data/derived --figures docs/evaluation/iteration3/figures

Rendering needs Matplotlib (tools/gaze_eval/requirements-plot.txt); data preparation does not.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import platform
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

TOOL_VERSION = "1.0.0"
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROTOCOL = REPO_ROOT / "docs" / "evaluation" / "iteration3" / "protocol.json"
TARGET_ID = re.compile(r"^r(\d+)c(\d+)$")
PILOT_PREFIX = "PILOT"


class FigureInputError(Exception):
    """The inputs cannot support trustworthy figures."""


# ---------------------------------------------------------------- target labels

def target_label(target_id: str) -> str:
    """Protocol target r{row}c{col} -> row letter (A = top) and 1-based column (1 = left): r0c0 -> A1, r3c3 -> D4."""
    m = TARGET_ID.match(target_id)
    if not m:
        raise FigureInputError(f"unexpected target id {target_id!r}")
    return f"{chr(ord('A') + int(m.group(1)))}{int(m.group(2)) + 1}"


def target_order(protocol: dict) -> list[dict]:
    """The protocol's distinct targets in row-major order (A1..A4, B1..B4, ...), with their normalized positions."""
    targets = {}
    for case in protocol["cases"]:
        targets[case["target_id"]] = (case["target_x_norm"], case["target_y_norm"])
    def key(tid):
        m = TARGET_ID.match(tid)
        return int(m.group(1)), int(m.group(2))
    return [{"target_id": t, "label": target_label(t), "x_norm": targets[t][0], "y_norm": targets[t][1]}
            for t in sorted(targets, key=key)]


# ---------------------------------------------------------------- data preparation

@dataclass
class SessionFigureData:
    participant_id: str
    session_label: str
    session_id: str
    short_id: str
    mode: str
    width_px: int
    height_px: int
    rotation: object
    presentations: list = field(default_factory=list)  # dicts, protocol trial order
    targets: list = field(default_factory=list)        # dicts, row-major target order

    @property
    def stem(self) -> str:
        return f"{self.participant_id.lower()}_{self.session_label.lower()}_{self.short_id}"

    @property
    def title_id(self) -> str:
        return f"{self.participant_id} / {self.session_label}"


def short_session_id(session_id: str) -> str:
    return session_id.rsplit("-", 1)[-1]


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_inputs(summary_path: Path, selection_path: Path) -> tuple[dict, dict]:
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    return summary, selection


def check_selection(summary: dict, selection: dict, selection_sha256: str | None = None) -> list[dict]:
    """The analyzed sessions must be exactly the manifest's included sessions; returns them in manifest order."""
    wanted = [e["session_id"] for e in selection["included"]]
    excluded = {e["session_id"] for e in selection.get("excluded", [])}
    if excluded & set(wanted):
        raise FigureInputError(f"sessions both included and excluded: {sorted(excluded & set(wanted))}")
    recorded = summary.get("input_selection")
    if recorded is None:
        raise FigureInputError("summary.json was not produced with --selection; its input set is not traceable")
    if recorded.get("included_session_ids") != wanted:
        raise FigureInputError("summary.json was analyzed with a different selection than the manifest")
    if selection_sha256 is not None and recorded.get("manifest_sha256") != selection_sha256:
        raise FigureInputError("the selection manifest changed after the analysis; rerun the analyzer")
    by_id = {s["session_id"]: s for s in summary["sessions"]}
    if sorted(by_id) != sorted(wanted):
        raise FigureInputError(f"analyzed sessions {sorted(by_id)} differ from the selection {sorted(wanted)}")
    if summary.get("rejected_sessions"):
        raise FigureInputError(f"the analysis rejected sessions: {summary['rejected_sessions']}")
    ordered = [by_id[sid] for sid in wanted]
    for entry, s in zip(selection["included"], ordered):
        if (s["participant_id"], s["session_label"]) != (entry["participant_id"], entry["session_label"]):
            raise FigureInputError(f"{s['session_id']}: metadata identity {s['participant_id']}/{s['session_label']} "
                                   f"differs from the manifest {entry['participant_id']}/{entry['session_label']}")
        problems = []
        if s["participant_id"].upper().startswith(PILOT_PREFIX):
            problems.append("technical-pilot participant")
        if s["final_state"] != "complete" or s["presentations_completed"] != s["presentations_planned"]:
            problems.append(f"not complete ({s['final_state']}, {s['presentations_completed']}/{s['presentations_planned']})")
        if s["integrity_error_count"]:
            problems.append(f"{s['integrity_error_count']} integrity problem(s): {s['integrity_errors'][:3]}")
        if s["checksums"] != "verified":
            problems.append(f"device checksums {s['checksums']}")
        if problems:
            raise FigureInputError(f"{s['session_id']}: " + "; ".join(problems))
    return ordered


def session_figure_data(session: dict, protocol: dict) -> SessionFigureData:
    """Presentation rows and per-target equal-weight centroids from the analyzer's presentation results."""
    display = session["display"]
    data = SessionFigureData(session["participant_id"], session["session_label"], session["session_id"],
                             short_session_id(session["session_id"]), session["mode"], display["width_px"],
                             display["height_px"], display.get("rotation"))
    w, h = data.width_px, data.height_px
    if len(session["presentations"]) != len(protocol["cases"]):
        raise FigureInputError(f"{session['session_id']}: {len(session['presentations'])} presentations, "
                               f"protocol has {len(protocol['cases'])}")
    for p, case in zip(session["presentations"], protocol["cases"]):
        if (p["case_id"], p["target_id"], p["pass_number"]) != (case["case_id"], case["target_id"], case["pass_number"]):
            raise FigureInputError(f"{session['session_id']}: presentation {p['trial_index']} does not match protocol case {case['case_id']}")
        eligible = p["eligibility"] == "eligible"
        final = p["final"] if eligible else None
        data.presentations.append({
            "participant_id": data.participant_id, "session_label": data.session_label, "session_id": data.session_id,
            "trial_index": p["trial_index"], "case_id": p["case_id"], "pass_number": p["pass_number"],
            "target_id": p["target_id"], "target_label": target_label(p["target_id"]),
            "target_x_norm": p["target_x_norm"], "target_y_norm": p["target_y_norm"],
            "eligibility": p["eligibility"], "eligibility_reason": p["eligibility_reason"],
            "valid_samples": p["valid_samples"], "measurement_attempts": p["measurement_attempts"],
            "valid_coverage": p["valid_coverage"],
            # Mean over valid samples of each sample's distance to the target (analyzer), not the centroid distance.
            "mean_error_norm": final["mean_error"]["norm"] if final else None,
            "centroid_x_norm": final["centroid"]["x_norm"] if final else None,
            "centroid_y_norm": final["centroid"]["y_norm"] if final else None,
            "analyzer_successful": p["successful"],
        })
    for t in target_order(protocol):
        rows = [r for r in data.presentations if r["target_id"] == t["target_id"]]
        used = [r for r in rows if r["eligibility"] == "eligible"]
        # Equal weight per eligible presentation, whatever its sample count.
        cx = math.fsum(r["centroid_x_norm"] for r in used) / len(used) if used else None
        cy = math.fsum(r["centroid_y_norm"] for r in used) / len(used) if used else None
        data.targets.append({
            "participant_id": data.participant_id, "session_label": data.session_label, "session_id": data.session_id,
            "target_label": t["label"], "target_id": t["target_id"],
            "target_x_norm": t["x_norm"], "target_y_norm": t["y_norm"],
            "target_x_px": t["x_norm"] * w, "target_y_px": t["y_norm"] * h,
            "presentations": len(rows), "eligible_presentations": len(used),
            "eligible_passes": "+".join(str(r["pass_number"]) for r in used),
            "mean_estimate_x_norm": cx, "mean_estimate_y_norm": cy,
            "mean_estimate_x_px": None if cx is None else cx * w, "mean_estimate_y_px": None if cy is None else cy * h,
            # Distance from target to the mean estimate: opposing errors cancel here. Not the presentation mean error.
            "centroid_displacement_norm": None if cx is None else math.hypot(cx - t["x_norm"], cy - t["y_norm"]),
        })
    return data


def shared_error_ymax(sessions: list[SessionFigureData], threshold: float) -> float:
    """One y range for every error figure: zero up to the largest plotted value plus headroom, threshold visible."""
    values = [r["mean_error_norm"] for s in sessions for r in s.presentations if r["mean_error_norm"] is not None]
    top = max(values + [threshold])
    return math.ceil(top * 1.12 / 0.05) * 0.05


def display_consistency(sessions: list[SessionFigureData]) -> dict:
    shapes = sorted({(s.width_px, s.height_px, str(s.rotation)) for s in sessions})
    return {"identical": len(shapes) == 1,
            "displays": [{"session_id": s.session_id, "width_px": s.width_px, "height_px": s.height_px,
                          "rotation": s.rotation} for s in sessions]}


# ---------------------------------------------------------------- tables

PRESENTATION_COLUMNS = ("participant_id", "session_label", "session_id", "trial_index", "case_id", "pass_number",
                        "target_id", "target_label", "target_x_norm", "target_y_norm", "eligibility", "eligibility_reason",
                        "valid_samples", "measurement_attempts", "valid_coverage", "mean_error_norm",
                        "centroid_x_norm", "centroid_y_norm", "analyzer_successful")
TARGET_COLUMNS = ("participant_id", "session_label", "session_id", "target_label", "target_id", "target_x_norm",
                  "target_y_norm", "target_x_px", "target_y_px", "presentations", "eligible_presentations",
                  "eligible_passes", "mean_estimate_x_norm", "mean_estimate_y_norm", "mean_estimate_x_px",
                  "mean_estimate_y_px", "centroid_displacement_norm")


def _cell(v) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return str(v).lower()
    if isinstance(v, float):
        return repr(v)
    return str(v)


def table_csv(columns, rows) -> str:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(columns)
    for r in rows:
        w.writerow([_cell(r[c]) for c in columns])
    return buf.getvalue()


# ---------------------------------------------------------------- rendering

PALETTE = {"target": "#1f5fa8", "estimate": "#e07b16", "arrow": "#7a7a7a", "pass1": "#2f5d8a", "pass2": "#9dbbdc",
           "threshold": "#b0302a", "grid": "#d9d9d9", "frame": "#333333", "na": "#6b6b6b", "text": "#222222"}


def _mpl():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    matplotlib.rcParams.update({"svg.hashsalt": "notouch-gaze-eval", "font.size": 10, "axes.titlesize": 13,
                                "font.family": "DejaVu Sans", "svg.fonttype": "path"})
    return matplotlib, plt


def render_gaze_map(data: SessionFigureData, note: str | None = None):
    _, plt = _mpl()
    from matplotlib.lines import Line2D
    w, h = data.width_px, data.height_px
    fig = plt.figure(figsize=(7.2, 12.4))
    ax = fig.add_axes([0.12, 0.26, 0.80, 0.65])
    ax.set_aspect("equal")
    ax.set_xlim(-0.04 * w, 1.04 * w)
    ax.set_ylim(1.02 * h, -0.02 * h)  # screen coordinates: y grows downward (the only inversion)
    ax.add_patch(plt.Rectangle((0, 0), w, h, fill=False, lw=1.6, ec=PALETTE["frame"]))
    for f in (0.2, 0.4, 0.6, 0.8):
        ax.axvline(f * w, color=PALETTE["grid"], lw=0.6, zorder=0)
        ax.axhline(f * h, color=PALETTE["grid"], lw=0.6, zorder=0)
    ax.set_xticks([0, w * 0.2, w * 0.4, w * 0.6, w * 0.8, w])
    ax.set_yticks([0, h * 0.2, h * 0.4, h * 0.6, h * 0.8, h])
    ax.xaxis.set_major_formatter(lambda v, _: f"{v:.0f}")
    ax.yaxis.set_major_formatter(lambda v, _: f"{v:.0f}")
    ax.set_xlabel("x (display px, increasing rightward)")
    ax.set_ylabel("y (display px, increasing downward)")
    for spine in ax.spines.values():
        spine.set_visible(False)
    partial, missing = [], []
    for t in data.targets:
        tx, ty = t["target_x_px"], t["target_y_px"]
        ax.plot(tx, ty, marker="x", ms=10, mew=2.2, color=PALETTE["target"], zorder=4, linestyle="none")
        label = t["target_label"]
        if t["eligible_presentations"] == 0:
            missing.append(label)
            label += "\n(no estimate)"
        elif t["eligible_presentations"] < t["presentations"]:
            partial.append(label)
            label += f" ({t['eligible_presentations']}/{t['presentations']})"
        # Put the label on the side opposite the arrow so the two do not overlap (lower left without an estimate).
        ox, oy = -0.7, 0.7
        if t["mean_estimate_x_px"] is not None:
            dx, dy = t["mean_estimate_x_px"] - tx, t["mean_estimate_y_px"] - ty
            norm = math.hypot(dx, dy)
            if norm > 1e-9:
                ox, oy = -dx / norm, -dy / norm
        ax.annotate(label, (tx, ty), xytext=(13 * ox, -13 * oy), textcoords="offset points",
                    ha="right" if ox < -0.3 else "left" if ox > 0.3 else "center",
                    va="top" if oy > 0.3 else "bottom" if oy < -0.3 else "center",
                    fontsize=10, fontweight="bold", color=PALETTE["target"], zorder=6)
        if t["mean_estimate_x_px"] is None:
            continue
        ex, ey = t["mean_estimate_x_px"], t["mean_estimate_y_px"]
        ax.annotate("", xy=(ex, ey), xytext=(tx, ty), zorder=3,
                    arrowprops=dict(arrowstyle="-|>", color=PALETTE["arrow"], lw=1.1, shrinkA=4, shrinkB=6, mutation_scale=11))
        full = t["eligible_presentations"] == t["presentations"]
        ax.plot(ex, ey, marker="o", ms=8, mew=1.6, linestyle="none", zorder=5, mec=PALETTE["estimate"],
                mfc=PALETTE["estimate"] if full else "white")
    handles = [Line2D([], [], marker="x", ms=9, mew=2.2, color=PALETTE["target"], ls="none", label="Target (shown twice)"),
               Line2D([], [], marker="o", ms=8, color=PALETTE["estimate"], ls="none", label="Mean estimated gaze (2 presentations)"),
               Line2D([], [], color=PALETTE["arrow"], lw=1.1, label="Mean displacement (target → estimate)")]
    if partial:
        handles.append(Line2D([], [], marker="o", ms=8, mec=PALETTE["estimate"], mfc="white", ls="none",
                              label="Estimate from 1 of 2 presentations"))
    ax.legend(handles=handles, loc="upper center", bbox_to_anchor=(0.5, -0.075), ncol=2, frameon=False, fontsize=9)
    fig.suptitle(f"Target vs mean estimated gaze — {data.title_id}", fontsize=14, fontweight="bold", y=0.975)
    fig.text(0.5, 0.935, f"Session {data.short_id} · blind measurement · display {w} × {h} px (portrait, to scale)",
             ha="center", fontsize=10, color=PALETTE["text"])
    lines = [
        "Crosses mark the 16 targets; circles mark the mean estimated gaze for each target. Each target was shown twice, "
        "and each technically eligible presentation's mean position counts equally. Arrows show the mean displacement.",
        "Estimates are the candidate's smoothed gaze output, which the model clamps to the screen boundary. "
        "Mean positions do not show the spread of individual samples (see the target-error figure).",
    ]
    if partial or missing:
        lines.append(f"Fewer than 2 eligible presentations: {', '.join(partial) or 'none'}; "
                     f"no eligible presentation (no estimate): {', '.join(missing) or 'none'}.")
    else:
        lines.append("All 16 targets have 2 of 2 technically eligible presentations.")
    if note:
        lines.append(note)
    fig.text(0.07, 0.155, _wrap(lines, 92), ha="left", va="top", fontsize=9, color=PALETTE["text"], linespacing=1.45)
    return fig


def _wrap(paragraphs: list[str], width: int) -> str:
    import textwrap
    return "\n".join(textwrap.fill(p, width) for p in paragraphs)


def render_target_error(data: SessionFigureData, ymax: float, threshold: float, rule: dict, note: str | None = None):
    _, plt = _mpl()
    from matplotlib.patches import Patch
    from matplotlib.lines import Line2D
    labels = [t["target_label"] for t in data.targets]
    fig = plt.figure(figsize=(12.0, 6.9))
    ax = fig.add_axes([0.075, 0.33, 0.9, 0.52])
    width = 0.38
    na_marks = []
    for i, t in enumerate(data.targets):
        for p in (1, 2):
            row = next(r for r in data.presentations if r["target_id"] == t["target_id"] and r["pass_number"] == p)
            x = i + (-width / 2 if p == 1 else width / 2)
            if row["mean_error_norm"] is None:
                na_marks.append(f"{t['target_label']} pass {p}")
                ax.text(x, ymax * 0.015, "N/A", rotation=90, ha="center", va="bottom", fontsize=8, color=PALETTE["na"])
                continue
            ax.bar(x, row["mean_error_norm"], width, color=PALETTE[f"pass{p}"], edgecolor=PALETTE["pass1"],
                   linewidth=0.8, hatch="///" if p == 2 else None, zorder=3)
    ax.axhline(threshold, color=PALETTE["threshold"], ls="--", lw=1.4, zorder=4)
    for boundary in (3.5, 7.5, 11.5):
        ax.axvline(boundary, color=PALETTE["grid"], lw=0.8, zorder=0)
    ax.set_xticks(range(len(labels)), labels)
    ax.set_xlim(-0.6, len(labels) - 0.4)
    ax.set_ylim(0, ymax)
    ax.yaxis.grid(True, color=PALETTE["grid"], lw=0.6, zorder=0)
    ax.set_axisbelow(True)
    ax.set_xlabel("Target (A = top row … D = bottom row; 1 = left … 4 = right)")
    ax.set_ylabel("Mean target error (normalized units)")
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    handles = [Patch(fc=PALETTE["pass1"], ec=PALETTE["pass1"], label="Pass 1 (first pass through the grid)"),
               Patch(fc=PALETTE["pass2"], ec=PALETTE["pass1"], hatch="///", label="Pass 2 (second pass, reversed order)"),
               Line2D([], [], color=PALETTE["threshold"], ls="--", lw=1.4, label=f"Error threshold {threshold:.2f}")]
    ax.legend(handles=handles, loc="lower left", bbox_to_anchor=(0.0, 1.01), ncol=3, frameon=False, fontsize=9)
    fig.suptitle(f"Mean target error by pass — {data.title_id}", fontsize=14, fontweight="bold", y=0.975)
    fig.text(0.5, 0.928, f"Session {data.short_id} · blind measurement · two passes within this one session",
             ha="center", fontsize=10, color=PALETTE["text"])
    w, h = data.width_px, data.height_px
    lines = [
        "Each bar is one presentation: the mean, over its valid measurement samples, of the distance from the smoothed "
        "gaze estimate to the target, √(Δx² + Δy²). Lower error is better. N/A marks a presentation without enough "
        "valid data.",
        f"The dashed line is only the error part of the presentation-success rule; full success also needs coverage ≥ "
        f"{rule['min_valid_coverage']:.2f}, at least {rule['min_valid_samples']} valid samples and a complete presentation.",
        f"Units: x is divided by the display width and y by the display height; this is not a percentage of the screen "
        f"diagonal. On this {w} × {h} px display a purely horizontal error of 0.10 is {0.1 * w:.0f} px and a purely "
        f"vertical error of 0.10 is {0.1 * h:.1f} px.",
    ]
    if na_marks:
        lines.append(f"Not enough valid data: {', '.join(na_marks)}.")
    if note:
        lines.append(note)
    fig.text(0.075, 0.2, _wrap(lines, 140), ha="left", va="top", fontsize=9, color=PALETTE["text"], linespacing=1.45)
    return fig


def save(fig, path_stem: Path) -> list[Path]:
    _, plt = _mpl()
    out = []
    for ext, kw in (("svg", {"metadata": {"Date": None}}), ("png", {"dpi": 150, "metadata": {"Software": None}})):
        p = path_stem.with_suffix(f".{ext}")
        buf = io.BytesIO()
        fig.savefig(buf, format=ext, facecolor="white", **kw)
        data = buf.getvalue()
        # SVG is text: write LF line endings on every OS so the bytes (and recorded checksums) are platform-independent.
        p.write_bytes(data.replace(b"\r\n", b"\n") if ext == "svg" else data)
        out.append(p)
    plt.close(fig)
    return out


# ---------------------------------------------------------------- run

def _write(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def _rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def prepare(summary_path: Path, selection_path: Path, protocol_path: Path):
    summary, selection = load_inputs(summary_path, selection_path)
    protocol = json.loads(protocol_path.read_text(encoding="utf-8"))
    if summary["protocol"]["sha256"] != hashlib.sha256(protocol_path.read_bytes().replace(b"\r\n", b"\n")).hexdigest():
        raise FigureInputError("summary.json was produced with a different protocol manifest")
    sessions = check_selection(summary, selection, sha256_file(selection_path))
    data = [session_figure_data(s, protocol) for s in sessions]
    rule = protocol["performance_criteria"]["successful_presentation"]
    return summary, selection, protocol, sessions, data, rule


def run(summary_path: Path, selection_path: Path, derived: Path, figures: Path, protocol_path: Path = DEFAULT_PROTOCOL) -> dict:
    summary, selection, protocol, sessions, data, rule = prepare(summary_path, selection_path, protocol_path)
    threshold = rule["max_mean_error_norm"]
    ymax = shared_error_ymax(data, threshold)
    displays = display_consistency(data)
    note = None if displays["identical"] else "Note: the selected sessions have different display sizes or rotations; compare them with care."
    derived.mkdir(parents=True, exist_ok=True)
    figures.mkdir(parents=True, exist_ok=True)
    _write(derived / "figure_presentations.csv", table_csv(PRESENTATION_COLUMNS, [r for d in data for r in d.presentations]))
    _write(derived / "figure_target_centroids.csv", table_csv(TARGET_COLUMNS, [t for d in data for t in d.targets]))
    outputs = []
    for d in data:
        outputs += save(render_gaze_map(d, note), figures / f"{d.stem}_gaze_map")
        outputs += save(render_target_error(d, ymax, threshold, rule, note), figures / f"{d.stem}_target_error")
    import matplotlib
    import numpy
    manifest = {
        "tool": "gaze_eval_figures", "tool_version": TOOL_VERSION,
        "versions": {"python": platform.python_version(), "matplotlib": matplotlib.__version__, "numpy": numpy.__version__},
        "inputs": {
            "summary": {"path": _rel(summary_path), "sha256": sha256_file(summary_path),
                        "analyzer_version": summary["tool_version"], "source_label": summary.get("source_label")},
            "selection": {"path": _rel(selection_path), "sha256": sha256_file(selection_path)},
            "protocol": {"path": _rel(protocol_path), "id": protocol["protocol_id"], "version": protocol["protocol_version"],
                         "sha256_lf": summary["protocol"]["sha256"]},
            "frozen_raw_inputs": selection.get("frozen_inputs"),
        },
        "sessions": [{"participant_id": s["participant_id"], "session_label": s["session_label"],
                      "session_id": s["session_id"], "short_id": short_session_id(s["session_id"]),
                      "start_wall_time_utc": s["start_wall_time_utc"], "mode": s["mode"], "build": s["build"],
                      "calibration_inputs_sha256": s["predictor"].get("calibration_inputs_sha256"),
                      "conditions": s["conditions"], "raw_path": s["path"]} for s in sessions],
        "excluded": selection.get("excluded", []),
        "shared_scales": {"error_axis_norm": [0.0, ymax], "error_threshold_norm": threshold, "display": displays},
        "definitions": {
            "gaze_map_estimate": "per target: equal-weight mean of the analyzer centroids (mean smoothed x, y of valid "
                                 "measurement samples) of its technically eligible presentations",
            "target_error_bar": "analyzer presentation mean error: mean over valid measurement samples of "
                                "sqrt((smoothed_x - target_x)^2 + (smoothed_y - target_y)^2); technically ineligible "
                                "presentations are shown as N/A",
        },
        "outputs": {
            "tables": [_rel(derived / "figure_presentations.csv"), _rel(derived / "figure_target_centroids.csv")],
            "figures": [{"path": _rel(p), "sha256": sha256_file(p)} for p in outputs],
        },
    }
    _write(derived / "figures-manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--summary", type=Path, required=True, help="analyzer summary.json produced with --selection")
    parser.add_argument("--selection", type=Path, required=True, help="analysis-input manifest")
    parser.add_argument("--derived", type=Path, required=True, help="directory for the plotted tables and figures-manifest.json")
    parser.add_argument("--figures", type=Path, required=True, help="directory for the SVG and PNG figures")
    parser.add_argument("--protocol", type=Path, default=DEFAULT_PROTOCOL)
    args = parser.parse_args(argv)
    try:
        manifest = run(args.summary, args.selection, args.derived, args.figures, args.protocol)
    except FigureInputError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    for f in manifest["outputs"]["figures"]:
        print(f"wrote {f['path']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
