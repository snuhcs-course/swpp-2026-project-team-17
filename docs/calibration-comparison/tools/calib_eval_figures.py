#!/usr/bin/env python3
"""Figures for NoTouch calibration-comparison sessions (protocol notouch-calibration-comparison-eval 1.0.0, log schema 4).

Adapted from the approved Iteration 3 plotting tool (tools/gaze_eval/gaze_eval_figures.py at 72411ec on
feature/gaze-evaluation-home-screen-iter3): the same two figure designs, now drawn once per head-position block.

Reads only the analyzer's summary.json (analyze_calibration_eval.py, produced with --selection), the analysis-input
manifest and the protocol manifest. Presentation eligibility, centroids, mean errors, success counts, clamp fractions
and verdicts come from the analyzer; nothing here re-derives validity, eligibility, metrics or verdicts, and no view
computes a pooled acceptance result. Complete sessions only.

Session view (default since 1.1.0; descriptive): per selected session, two figures that combine the session's three
head-position blocks and two passes per target (six presentations per target), each as SVG and PNG, written with their
source table and manifest to a folder of their own, <figures>/<PARTICIPANT>_<LABEL>_<id8>/:

  cand_<c>_<participant>_<label>_<id8>_all_poses_gaze_map.{svg,png}      targets vs average estimated gaze
  cand_<c>_<participant>_<label>_<id8>_all_poses_target_error.{svg,png}  average of the presentation mean errors
  target_summary.csv             16 rows: per target, contribution counts, averaged position and averaged error
  figures-manifest.json          inputs, versions, verdicts (copied), aggregation rules, scales, output checksums

Per target, the estimate is the equal-weight mean of the eligible presentations' centroids and the bar is the
equal-weight mean of their mean sample errors (never the distance from target to averaged position). Presentations are
matched to targets by grid coordinates. Sessions are never combined, and an existing session folder is not overwritten
with different figures unless --replace is given.

1.1.1 changes only sessions with partial data or a block without valid samples (complete sessions render the same
bytes as 1.1.0): the caption names the presentations that were not eligible, grouping targets with the same ones; the
clamp sentence names the positions when a block has no valid samples; and a figure grows at the bottom instead of
clipping a caption that needs more lines.

Per-block view (--view per-block, the 1.0.0 output): two figures per head-position block (six per session) in
--figures, with figure_presentations.csv, figure_target_centroids.csv and figures-manifest.json in --derived.

Every figure states the session's designation from the analyzer (official, complete but not official, or technical
pilot). Technical-pilot and other sessions are never drawn in one figure set.

Usage (from the repository root):
    python -B docs/calibration-comparison/tools/calib_eval_figures.py \
        --summary docs/calibration-comparison/data/derived/summary.json \
        --selection docs/calibration-comparison/data/analysis-inputs.json \
        --figures docs/calibration-comparison/figures/draft
    (per-block view: add --view per-block --derived <dir> and point --figures at a directory of its own)

Rendering needs Matplotlib (requirements-plot.txt beside this file); data preparation does not.
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
import textwrap
from dataclasses import dataclass, field
from pathlib import Path

TOOL_VERSION = "1.1.1"
ADAPTED_FROM = "tools/gaze_eval/gaze_eval_figures.py 1.0.0 at 72411ec35d5d6e9547c322de3d7a14e65812b82e"
REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_PROTOCOL = Path(__file__).resolve().parents[1] / "protocol.json"
TARGET_ID = re.compile(r"^r(\d+)c(\d+)$")
PILOT_PREFIX = "PILOT"
CANDIDATES = {"calibration_a_three_pass_static": ("A", "Candidate A"),
              "calibration_b_three_pass_positions": ("B", "Candidate B")}
MODE_TEXT = {"blind_measurement": "blind measurement", "live_preview": "live preview (pointer visible)"}


class FigureInputError(Exception):
    """The inputs cannot support trustworthy figures."""


# ---------------------------------------------------------------- labels

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


def candidate(calibration_id: str) -> tuple[str, str]:
    """(letter, display name) of a declared candidate; unknown IDs are shown as themselves."""
    return CANDIDATES.get(calibration_id, (re.sub(r"[^A-Za-z0-9]+", "", calibration_id)[:12] or "X", calibration_id))


def designation(session: dict) -> tuple[str, str]:
    """('official' | 'pilot' | 'not_official', text), from the analyzer's own official flag and exclusion reasons."""
    if session["participant_id"].upper().startswith(PILOT_PREFIX):
        return "pilot", "Technical pilot: never official and not candidate evidence"
    if session["official"]:
        return "official", "Official session"
    return "not_official", "Complete session, not official: " + "; ".join(session["official_exclusions"])


def short_session_id(session_id: str) -> str:
    return session_id.rsplit("-", 1)[-1]


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9_-]+", "_", text.lower())


# ---------------------------------------------------------------- data preparation

@dataclass
class BlockFigureData:
    calibration_id: str
    participant_id: str
    session_label: str
    session_id: str
    mode: str
    width_px: int
    height_px: int
    rotation: object
    block_index: int        # 1-based, as recorded
    blocks_planned: int
    head_position: str
    designation_kind: str
    designation_text: str
    presentations_planned: int
    presentations_successful: int
    model_clamp_active_fraction: float | None
    presentations: list = field(default_factory=list)  # dicts, protocol trial order
    targets: list = field(default_factory=list)        # dicts, row-major target order

    @property
    def short_id(self) -> str:
        return short_session_id(self.session_id)

    @property
    def candidate_letter(self) -> str:
        return candidate(self.calibration_id)[0]

    @property
    def candidate_name(self) -> str:
        return candidate(self.calibration_id)[1]

    @property
    def position_label(self) -> str:
        return self.head_position.upper()

    @property
    def stem(self) -> str:
        return (f"cand_{_slug(self.candidate_letter)}_{_slug(self.participant_id)}_{_slug(self.session_label)}_"
                f"{self.short_id}_b{self.block_index}_{_slug(self.head_position)}")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_inputs(summary_path: Path, selection_path: Path) -> tuple[dict, dict]:
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    return summary, selection


def _listed_elsewhere(selection: dict) -> set:
    return {e["session_id"] for key in ("excluded", "pilot_sessions", "incomplete_sessions")
            for e in selection.get(key, []) if isinstance(e, dict) and "session_id" in e}


def check_selection(summary: dict, selection: dict, selection_sha256: str | None = None, blocks_planned: int = 3) -> list[dict]:
    """The analyzed sessions must be exactly the manifest's included sessions; returns them in manifest order.

    Only complete, integrity-checked sessions with verified device checksums are drawn. A technical pilot may be drawn
    (with its designation) but never together with other sessions.
    """
    wanted = [e["session_id"] for e in selection["included"]]
    if not wanted:
        raise FigureInputError("the selection includes no session")
    elsewhere = _listed_elsewhere(selection)
    if elsewhere & set(wanted):
        raise FigureInputError(f"sessions both included and listed as excluded/pilot/incomplete: {sorted(elsewhere & set(wanted))}")
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
        if "official" in entry and entry["official"] != s["official"]:
            raise FigureInputError(f"{s['session_id']}: manifest says official={entry['official']}, analyzer says {s['official']}")
        problems = []
        if s["final_state"] != "complete" or s["blocks_completed"] != blocks_planned or len(s["blocks"]) != blocks_planned:
            problems.append(f"not complete ({s['final_state']}, {s['blocks_completed']}/{blocks_planned} blocks)")
        if s["integrity_error_count"]:
            problems.append(f"{s['integrity_error_count']} integrity problem(s): {s['integrity_errors'][:3]}")
        if s["checksums"] != "verified":
            problems.append(f"device checksums {s['checksums']}")
        if problems:
            raise FigureInputError(f"{s['session_id']}: " + "; ".join(problems))
    if len({designation(s)[0] == "pilot" for s in ordered}) > 1:
        raise FigureInputError("technical-pilot and other sessions must not share one figure set")
    return ordered


def block_figure_data(session: dict, block: dict, protocol: dict) -> BlockFigureData:
    """Presentation rows and per-target equal-weight centroids of one block, from the analyzer's presentation results."""
    display = session["display"]
    kind, text = designation(session)
    cases = protocol["cases"]
    position = block["head_position"]
    data = BlockFigureData(
        session["calibration_id"], session["participant_id"], session["session_label"], session["session_id"],
        session["mode"], display["width_px"], display["height_px"], display.get("rotation"), block["block_index"],
        len(session["blocks"]), position, kind, text, len(cases), block["diagnostics"]["presentations_successful"],
        block["diagnostics"]["model_clamp_active_fraction"])
    w, h = data.width_px, data.height_px
    if len(block["presentations"]) != len(cases):
        raise FigureInputError(f"{session['session_id']} block {block['block_index']}: {len(block['presentations'])} "
                               f"presentations, protocol has {len(cases)}")
    for p, case in zip(block["presentations"], cases):
        if ((p["case_suffix"], p["target_id"], p["pass_number"]) != (case["case_suffix"], case["target_id"], case["pass_number"])
                or p["case_id"] != f"{position}-{case['case_suffix']}"):
            raise FigureInputError(f"{session['session_id']} block {block['block_index']}: presentation {p['trial_index']} "
                                   f"does not match protocol case {position}-{case['case_suffix']}")
        eligible = p["eligibility"] == "eligible"
        final = p["final"] if eligible else None
        data.presentations.append({
            "calibration_id": data.calibration_id, "participant_id": data.participant_id,
            "session_label": data.session_label, "session_id": data.session_id,
            "block_index": data.block_index, "head_position": position,
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
            "calibration_id": data.calibration_id, "participant_id": data.participant_id,
            "session_label": data.session_label, "session_id": data.session_id,
            "block_index": data.block_index, "head_position": position,
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


def shared_error_ymax(blocks: list[BlockFigureData], threshold: float) -> float:
    """One y range for every error figure: zero up to the largest plotted value plus headroom, threshold visible."""
    values = [r["mean_error_norm"] for b in blocks for r in b.presentations if r["mean_error_norm"] is not None]
    top = max(values + [threshold])
    return math.ceil(top * 1.12 / 0.05) * 0.05


@dataclass
class SessionSummaryData:
    """One session's targets combined over all head-position blocks and passes (descriptive view, no verdict)."""
    calibration_id: str
    participant_id: str
    session_label: str
    session_id: str
    mode: str
    width_px: int
    height_px: int
    rotation: object
    designation_kind: str
    designation_text: str
    positions: list            # head positions in recorded block order
    block_verdicts: list       # (head_position, verdict) in block order, copied from the analyzer
    session_verdict: str
    clamp_fractions: list      # (head_position, model_clamp_active_fraction) in block order, from the analyzer
    presentations_per_target: int
    targets: list = field(default_factory=list)

    @property
    def short_id(self) -> str:
        return short_session_id(self.session_id)

    @property
    def candidate_name(self) -> str:
        return candidate(self.calibration_id)[1]

    @property
    def folder(self) -> str:
        """Output folder per session: <PARTICIPANT>_<LABEL>_<short id> (recorded case kept)."""
        return re.sub(r"[^A-Za-z0-9_-]+", "_", f"{self.participant_id}_{self.session_label}_{self.short_id}")

    @property
    def stem(self) -> str:
        return (f"cand_{_slug(candidate(self.calibration_id)[0])}_{_slug(self.participant_id)}_"
                f"{_slug(self.session_label)}_{self.short_id}_all_poses")


def session_summary_data(session: dict, protocol: dict) -> SessionSummaryData:
    """Per target, the equal-weight mean over all technically eligible presentations of the session (every head-position
    block and pass) of the analyzer's presentation centroid and of its presentation mean error.

    Presentations are matched to targets by their canonical grid coordinates (and the protocol target id at those
    coordinates must agree), never by case ID, which also encodes position and pass. Ineligible presentations are left
    out and counted, never replaced by zero."""
    blocks = [block_figure_data(session, b, protocol) for b in session["blocks"]]
    order = target_order(protocol)
    by_xy = {(t["x_norm"], t["y_norm"]): t for t in order}
    if len(by_xy) != len(order):
        raise FigureInputError("protocol targets do not have distinct grid coordinates")
    per_block = {t["target_id"]: sum(c["target_id"] == t["target_id"] for c in protocol["cases"]) for t in order}
    first = blocks[0]
    kind, text = first.designation_kind, first.designation_text
    data = SessionSummaryData(
        session["calibration_id"], session["participant_id"], session["session_label"], session["session_id"],
        session["mode"], first.width_px, first.height_px, first.rotation, kind, text,
        [b["head_position"] for b in session["blocks"]], [(b["head_position"], b["verdict"]) for b in session["blocks"]],
        session["verdict"], [(b["head_position"], b["diagnostics"]["model_clamp_active_fraction"]) for b in session["blocks"]],
        len(blocks) * max(per_block.values()))
    rows_by_target: dict[str, list] = {t["target_id"]: [] for t in order}
    for d in blocks:
        for r in d.presentations:
            t = by_xy.get((r["target_x_norm"], r["target_y_norm"]))
            if t is None or t["target_id"] != r["target_id"]:
                raise FigureInputError(f"{session['session_id']}: {r['case_id']} at ({r['target_x_norm']}, "
                                       f"{r['target_y_norm']}) does not match a protocol target position")
            rows_by_target[t["target_id"]].append(r)
    w, h = data.width_px, data.height_px
    for t in order:
        rows = rows_by_target[t["target_id"]]
        if len(rows) != len(blocks) * per_block[t["target_id"]]:
            raise FigureInputError(f"{session['session_id']}: target {t['label']} has {len(rows)} presentations")
        used = [r for r in rows if r["eligibility"] == "eligible"]
        errors = [r["mean_error_norm"] for r in used]
        # Equal weight per eligible presentation, whatever its sample count: one sixth each with complete data.
        cx = math.fsum(r["centroid_x_norm"] for r in used) / len(used) if used else None
        cy = math.fsum(r["centroid_y_norm"] for r in used) / len(used) if used else None
        mean_error = math.fsum(errors) / len(errors) if errors else None
        data.targets.append({
            "calibration_id": data.calibration_id, "participant_id": data.participant_id,
            "session_label": data.session_label, "session_id": data.session_id,
            "target_label": t["label"], "target_id": t["target_id"],
            "target_x_norm": t["x_norm"], "target_y_norm": t["y_norm"],
            "target_x_px": t["x_norm"] * w, "target_y_px": t["y_norm"] * h,
            "presentations": len(rows), "eligible_presentations": len(used),
            "contribution": "complete" if len(used) == len(rows) else "partial" if used else "none",
            "contributing_presentations": ";".join(f"{r['head_position']}-pass{r['pass_number']}" for r in used),
            "missing_presentations": ";".join(f"{r['head_position']}-pass{r['pass_number']}" for r in rows
                                              if r["eligibility"] != "eligible"),
            "mean_estimate_x_norm": cx, "mean_estimate_y_norm": cy,
            "mean_estimate_x_px": None if cx is None else cx * w, "mean_estimate_y_px": None if cy is None else cy * h,
            # The bar: mean of the presentations' mean sample errors (opposite errors do not cancel).
            "mean_presentation_error_norm": mean_error,
            "min_presentation_error_norm": min(errors) if errors else None,
            "max_presentation_error_norm": max(errors) if errors else None,
            # Distance from target to the averaged position (opposite errors cancel): shown as the arrow, never as the bar.
            "centroid_displacement_norm": None if cx is None else math.hypot(cx - t["x_norm"], cy - t["y_norm"]),
        })
    return data


def session_error_ymax(summaries: list[SessionSummaryData], threshold: float) -> float:
    """One y range for the session error charts of one run: 0 to 0.5, extended in steps of 0.05 when a bar (plus room
    for its value label) needs more. The fixed floor keeps a session's chart unchanged when other sessions are added."""
    values = [t["mean_presentation_error_norm"] for s in summaries for t in s.targets
              if t["mean_presentation_error_norm"] is not None]
    top = max(values + [threshold])
    return max(0.5, math.ceil(round(top * 1.15 / 0.05, 9)) * 0.05)


def display_consistency(blocks: list[BlockFigureData]) -> dict:
    sessions = {}
    for b in blocks:
        sessions.setdefault(b.session_id, (b.width_px, b.height_px, b.rotation))
    shapes = {(w, h, str(r)) for w, h, r in sessions.values()}
    return {"identical": len(shapes) == 1,
            "displays": [{"session_id": sid, "width_px": w, "height_px": h, "rotation": r}
                         for sid, (w, h, r) in sessions.items()]}


# ---------------------------------------------------------------- tables

PRESENTATION_COLUMNS = ("calibration_id", "participant_id", "session_label", "session_id", "block_index", "head_position",
                        "trial_index", "case_id", "pass_number", "target_id", "target_label", "target_x_norm",
                        "target_y_norm", "eligibility", "eligibility_reason", "valid_samples", "measurement_attempts",
                        "valid_coverage", "mean_error_norm", "centroid_x_norm", "centroid_y_norm", "analyzer_successful")
SESSION_TARGET_COLUMNS = ("calibration_id", "participant_id", "session_label", "session_id", "target_label", "target_id",
                          "target_x_norm", "target_y_norm", "target_x_px", "target_y_px", "presentations",
                          "eligible_presentations", "contribution", "contributing_presentations", "missing_presentations",
                          "mean_estimate_x_norm", "mean_estimate_y_norm", "mean_estimate_x_px", "mean_estimate_y_px",
                          "mean_presentation_error_norm", "min_presentation_error_norm", "max_presentation_error_norm",
                          "centroid_displacement_norm")
TARGET_COLUMNS = ("calibration_id", "participant_id", "session_label", "session_id", "block_index", "head_position",
                  "target_label", "target_id", "target_x_norm", "target_y_norm", "target_x_px", "target_y_px",
                  "presentations", "eligible_presentations", "eligible_passes", "mean_estimate_x_norm",
                  "mean_estimate_y_norm", "mean_estimate_x_px", "mean_estimate_y_px", "centroid_displacement_norm")


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

PALETTE = {"target": "#1f5fa8", "estimate": "#e07b16", "estimate_text": "#a5560b", "arrow": "#7a7a7a",
           "pass1": "#2f5d8a", "pass2": "#9dbbdc",
           "threshold": "#b0302a", "grid": "#d9d9d9", "frame": "#333333", "na": "#6b6b6b", "text": "#222222",
           "official": "#222222", "not_official": "#8a3b00", "pilot": "#b0302a"}


def _mpl():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    matplotlib.rcParams.update({"svg.hashsalt": "notouch-calibration-comparison", "font.size": 10, "axes.titlesize": 13,
                                "font.family": "DejaVu Sans", "svg.fonttype": "path"})
    return matplotlib, plt


def _wrap(paragraphs: list[str], width: int) -> str:
    return "\n".join(textwrap.fill(p, width) for p in paragraphs)


def _header(fig, data: BlockFigureData, title: str, ys: tuple, detail: str, wrap: int) -> None:
    fig.suptitle(title, fontsize=14, fontweight="bold", y=ys[0])
    fig.text(0.5, ys[1], f"{data.candidate_name} · {data.position_label} head position (block {data.block_index} of "
             f"{data.blocks_planned})", ha="center", fontsize=12, fontweight="bold", color=PALETTE["text"])
    fig.text(0.5, ys[2], f"{data.participant_id} / {data.session_label} · session {data.short_id} · "
             f"{MODE_TEXT.get(data.mode, data.mode)} · {detail}", ha="center", fontsize=9.5, color=PALETTE["text"])
    fig.text(0.5, ys[3], textwrap.fill(data.designation_text, wrap), ha="center", va="top", fontsize=9.5,
             fontweight="bold" if data.designation_kind != "official" else "normal",
             color=PALETTE[data.designation_kind])


# ---- label placement for the gaze map (geometry in data units; y grows downward)

def _label_rect(anchor, direction, radius_pt, w_pt, h_pt, scale):
    """Rectangle a label occupies when offset from [anchor] along [direction], with the reference's ha/va rule."""
    ox, oy = direction
    lx, ly = anchor[0] + ox * radius_pt * scale, anchor[1] + oy * radius_pt * scale
    ha = "right" if ox < -0.3 else "left" if ox > 0.3 else "center"
    va = "top" if oy > 0.3 else "bottom" if oy < -0.3 else "center"
    w, h = w_pt * scale, h_pt * scale
    x0 = lx - w if ha == "right" else lx if ha == "left" else lx - w / 2
    y0 = ly if va == "top" else ly - h if va == "bottom" else ly - h / 2
    return (x0, y0, x0 + w, y0 + h), ha, va


def _rect_hits_circle(rect, center, radius) -> bool:
    dx = max(rect[0] - center[0], 0.0, center[0] - rect[2])
    dy = max(rect[1] - center[1], 0.0, center[1] - rect[3])
    return dx * dx + dy * dy < radius * radius


def _rect_hits_rect(a, b) -> bool:
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def _segment_hits_rect(p, q, rect) -> bool:
    """Liang-Barsky clipping of segment p-q against the rectangle."""
    dx, dy = q[0] - p[0], q[1] - p[1]
    t0, t1 = 0.0, 1.0
    for pk, qk in ((-dx, p[0] - rect[0]), (dx, rect[2] - p[0]), (-dy, p[1] - rect[1]), (dy, rect[3] - p[1])):
        if pk == 0:
            if qk < 0:
                return False
            continue
        t = qk / pk
        if pk < 0:
            t0 = max(t0, t)
        else:
            t1 = min(t1, t)
        if t0 > t1:
            return False
    return True


def place_label(anchor, preferred, text, fontsize, scale, obstacles, bounds, radii=(13, 22), rivals=()):
    """Offset (points), ha and va for a label near [anchor]: the [preferred] direction if it is free, otherwise the
    nearest of 16 directions that overlaps no marker, arrow, placed label or the plot edge and lies closer to its own
    marker than to any [rivals] (other markers of the same kind); fewest violations if none is free."""
    lines = text.split("\n")
    w_pt = max(len(line) for line in lines) * 0.62 * fontsize + 2
    h_pt = len(lines) * 1.2 * fontsize + 1
    norm = math.hypot(*preferred)
    preferred = (preferred[0] / norm, preferred[1] / norm) if norm > 1e-9 else (-0.7071, 0.7071)
    base = math.atan2(preferred[1], preferred[0])
    angles = sorted((k * math.pi / 8 for k in range(16)),
                    key=lambda a: abs(((a - base + math.pi) % (2 * math.pi)) - math.pi))
    directions = [preferred] + [(math.cos(a), math.sin(a)) for a in angles]
    best = None
    for radius in radii:
        for d in directions:
            rect, ha, va = _label_rect(anchor, d, radius, w_pt, h_pt, scale)
            hits = 5 * (rect[0] < bounds[0] or rect[2] > bounds[2] or rect[1] < bounds[1] or rect[3] > bounds[3])
            hits += sum(_rect_hits_circle(rect, c, r) for c, r in obstacles["circles"])
            hits += sum(_segment_hits_rect(p, q, rect) for p, q in obstacles["segments"])
            hits += sum(_rect_hits_rect(rect, o) for o in obstacles["rects"])
            cx, cy = (rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2
            own = math.hypot(cx - anchor[0], cy - anchor[1])
            hits += any(math.hypot(cx - p[0], cy - p[1]) < own for p in rivals)
            if best is None or hits < best[0]:
                best = (hits, d, radius, rect, ha, va)
            if hits == 0:
                break
        if best[0] == 0:
            break
    _, d, radius, rect, ha, va = best
    obstacles["rects"].append(rect)
    return (radius * d[0], -radius * d[1]), ha, va


def _clamp_sentence(data: BlockFigureData) -> str:
    if data.model_clamp_active_fraction is None:
        return ""
    return (f" In this block the clamp acted on {100 * data.model_clamp_active_fraction:.1f}% of valid measurement samples "
            "(their unclamped output lay outside the screen).")


def _draw_gaze_map(targets: list[dict], w: int, h: int, figsize: tuple, axes_box: list, legend: tuple):
    """The gaze-map body shared by the per-block and session views: screen to scale, targets, arrows, estimates,
    labels and legend. [targets] rows need target_*_px, mean_estimate_*_px, presentations and eligible_presentations;
    [legend] = (target, estimate, partial-estimate) labels. Returns (fig, partial labels, missing labels)."""
    _, plt = _mpl()
    from matplotlib.lines import Line2D
    fig = plt.figure(figsize=figsize)
    ax = fig.add_axes(axes_box)
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
    ax.apply_aspect()
    scale = (1.04 * h) / (ax.get_position().height * fig.get_figheight() * 72)  # data units per point
    bounds = (-0.04 * w, -0.02 * h, 1.04 * w, 1.02 * h)
    estimated = [t for t in targets if t["mean_estimate_x_px"] is not None]
    obstacles = {
        "circles": [((t["target_x_px"], t["target_y_px"]), 7 * scale) for t in targets]
                   + [((t["mean_estimate_x_px"], t["mean_estimate_y_px"]), 6 * scale) for t in estimated],
        "segments": [((t["target_x_px"], t["target_y_px"]), (t["mean_estimate_x_px"], t["mean_estimate_y_px"]))
                     for t in estimated],
        "rects": [],
    }
    partial, missing = [], []
    for t in targets:
        tx, ty = t["target_x_px"], t["target_y_px"]
        ax.plot(tx, ty, marker="x", ms=10, mew=2.2, color=PALETTE["target"], zorder=4, linestyle="none")
        label = t["target_label"]
        if t["eligible_presentations"] == 0:
            missing.append(label)
            label += "\n(no estimate)"
        elif t["eligible_presentations"] < t["presentations"]:
            partial.append(label)
            label += f" ({t['eligible_presentations']}/{t['presentations']})"
        # Prefer the side opposite the arrow (lower left without an estimate), but avoid other markers and arrows.
        preferred = (-0.7, 0.7)
        if t["mean_estimate_x_px"] is not None:
            preferred = (tx - t["mean_estimate_x_px"], ty - t["mean_estimate_y_px"])
        offset, ha, va = place_label((tx, ty), preferred, label, 10, scale, obstacles, bounds,
                                     rivals=[(o["target_x_px"], o["target_y_px"]) for o in targets if o is not t])
        ax.annotate(label, (tx, ty), xytext=offset, textcoords="offset points", ha=ha, va=va,
                    fontsize=10, fontweight="bold", color=PALETTE["target"], zorder=6)
        if t["mean_estimate_x_px"] is None:
            continue
        ex, ey = t["mean_estimate_x_px"], t["mean_estimate_y_px"]
        ax.annotate("", xy=(ex, ey), xytext=(tx, ty), zorder=3,
                    arrowprops=dict(arrowstyle="-|>", color=PALETTE["arrow"], lw=1.1, shrinkA=4, shrinkB=6, mutation_scale=11))
        full = t["eligible_presentations"] == t["presentations"]
        ax.plot(ex, ey, marker="o", ms=8, mew=1.6, linestyle="none", zorder=5, mec=PALETTE["estimate"],
                mfc=PALETTE["estimate"] if full else "white")
    # Name each mean estimate after its target (arrows can cross); coincident estimates share one label.
    groups: list[list[dict]] = []
    for t in estimated:
        for g in groups:
            if math.hypot(g[0]["mean_estimate_x_px"] - t["mean_estimate_x_px"],
                          g[0]["mean_estimate_y_px"] - t["mean_estimate_y_px"]) < 8 * scale:
                g.append(t)
                break
        else:
            groups.append([t])
    for g in groups:
        ex = math.fsum(t["mean_estimate_x_px"] for t in g) / len(g)
        ey = math.fsum(t["mean_estimate_y_px"] for t in g) / len(g)
        preferred = (math.fsum(t["mean_estimate_x_px"] - t["target_x_px"] for t in g),
                     math.fsum(t["mean_estimate_y_px"] - t["target_y_px"] for t in g))
        text = "/".join(t["target_label"] for t in g)
        offset, ha, va = place_label((ex, ey), preferred, text, 8, scale, obstacles, bounds, radii=(8, 15, 24),
                                     rivals=[(o["mean_estimate_x_px"], o["mean_estimate_y_px"]) for o in estimated if o not in g])
        ax.annotate(text, (ex, ey), xytext=offset, textcoords="offset points", ha=ha, va=va,
                    fontsize=8, fontweight="bold", color=PALETTE["estimate_text"], zorder=6)
    handles = [Line2D([], [], marker="x", ms=9, mew=2.2, color=PALETTE["target"], ls="none", label=legend[0]),
               Line2D([], [], marker="o", ms=8, color=PALETTE["estimate"], ls="none", label=legend[1]),
               Line2D([], [], color=PALETTE["arrow"], lw=1.1, label="Mean displacement (target → estimate)")]
    if partial:
        handles.append(Line2D([], [], marker="o", ms=8, mec=PALETTE["estimate"], mfc="white", ls="none",
                              label=legend[2]))
    ax.legend(handles=handles, loc="upper center", bbox_to_anchor=(0.5, -0.075), ncol=2, frameon=False, fontsize=9)
    return fig, partial, missing


def render_gaze_map(data: BlockFigureData, note: str | None = None):
    w, h = data.width_px, data.height_px
    fig, partial, missing = _draw_gaze_map(
        data.targets, w, h, (7.2, 12.8), [0.12, 0.25, 0.80, 0.625],
        ("Target (shown twice)", "Mean estimated gaze (2 presentations)", "Estimate from 1 of 2 presentations"))
    _header(fig, data, "Target vs mean estimated gaze", (0.983, 0.950, 0.930, 0.919),
            f"{w} × {h} px, to scale", 88)
    lines = [
        "Crosses mark the 16 targets; circles mark the mean estimated gaze for each target in this block, and the small "
        "orange label beside each circle names its target. Each target was shown twice (pass 1 and pass 2), and each "
        "technically eligible presentation's mean position counts equally. Arrows show the mean displacement.",
        "Estimates are the pipeline's smoothed output, which the model clamps to [0, 1] on each axis, so an estimate can "
        "sit on the screen edge but never beyond it." + _clamp_sentence(data),
        "Mean positions do not show the spread of individual samples (see the target-error figure).",
    ]
    if partial or missing:
        lines.append(f"Fewer than 2 eligible presentations: {', '.join(partial) or 'none'}; "
                     f"no eligible presentation (no estimate): {', '.join(missing) or 'none'}.")
    else:
        lines.append("All 16 targets have 2 of 2 technically eligible presentations.")
    if note:
        lines.append(note)
    fig.text(0.07, 0.152, _wrap(lines, 96), ha="left", va="top", fontsize=9, color=PALETTE["text"], linespacing=1.4)
    return fig


def render_target_error(data: BlockFigureData, ymax: float, threshold: float, rule: dict, note: str | None = None):
    _, plt = _mpl()
    from matplotlib.patches import Patch
    from matplotlib.lines import Line2D
    labels = [t["target_label"] for t in data.targets]
    fig = plt.figure(figsize=(12.0, 7.6))
    ax = fig.add_axes([0.075, 0.30, 0.9, 0.475])
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
    _header(fig, data, "Mean target error by pass", (0.983, 0.925, 0.893, 0.877),
            "two passes within this block", 140)
    w, h = data.width_px, data.height_px
    lines = [
        "Each bar is one presentation: the mean, over its valid measurement samples, of the distance from the smoothed "
        "gaze estimate to the target, √(Δx² + Δy²). Lower error is better. N/A marks a presentation without "
        "enough valid data.",
        f"The dashed line is only the error part of the presentation-success rule; full success also needs coverage ≥ "
        f"{rule['min_valid_coverage']:.2f}, at least {rule['min_valid_samples']} valid samples and a complete presentation. "
        f"In this block {data.presentations_successful} of {data.presentations_planned} presentations met the full rule.",
        f"Units: x is divided by the display width and y by the display height; this is not a percentage of the screen "
        f"diagonal. On this {w} × {h} px display a purely horizontal error of 0.10 is {0.1 * w:.0f} px and a purely "
        f"vertical error of 0.10 is {0.1 * h:.1f} px. All candidate figures share this y axis.",
    ]
    if na_marks:
        lines.append(f"Not enough valid data: {', '.join(na_marks)}.")
    if note:
        lines.append(note)
    fig.text(0.075, 0.175, _wrap(lines, 150), ha="left", va="top", fontsize=9, color=PALETTE["text"], linespacing=1.4)
    return fig


# ---- session summary view (three head positions x two passes combined per target; descriptive only)

def _session_header(fig, data: SessionSummaryData, title: str, ys: tuple, detail: str, wrap: int) -> None:
    fig.suptitle(title, fontsize=14, fontweight="bold", y=ys[0])
    fig.text(0.5, ys[1], f"{data.candidate_name} · all {len(data.positions)} head positions, both passes combined",
             ha="center", fontsize=12, fontweight="bold", color=PALETTE["text"])
    fig.text(0.5, ys[2], f"{data.participant_id} / {data.session_label} · session {data.short_id} · "
             f"{MODE_TEXT.get(data.mode, data.mode)} · {detail}", ha="center", fontsize=9.5, color=PALETTE["text"])
    fig.text(0.5, ys[3], textwrap.fill(data.designation_text, wrap), ha="center", va="top", fontsize=9.5,
             fontweight="bold" if data.designation_kind != "official" else "normal",
             color=PALETTE[data.designation_kind])


def _session_sentences(data: SessionSummaryData) -> tuple[str, str]:
    order = ", ".join(p.upper() for p in data.positions[:-1]) + f" and {data.positions[-1].upper()}"
    per_position = data.presentations_per_target // len(data.positions)
    shown = (f"Each target was shown {data.presentations_per_target} times in this session: in the {order} head "
             f"positions (in that order), {per_position} passes each.")
    verdicts = ", ".join(f"{p.upper()} {v}" for p, v in data.block_verdicts)
    caveat = ("Combining the head positions can hide differences between them; the per-position views are kept "
              f"separately. Descriptive only: the per-block acceptance verdicts are unchanged ({verdicts}).")
    return shown, caveat


def _presentation_names(keys: str) -> str:
    """'lower-pass1;lower-pass2' -> 'LOWER pass 1, LOWER pass 2'."""
    return ", ".join(k.split("-pass")[0].upper() + " pass " + k.split("-pass")[1] for k in keys.split(";") if k)


def _partial_sentence(data: SessionSummaryData, missing_word: str) -> str | None:
    """Targets with fewer than all presentations, grouped by count and by which presentations were not eligible."""
    missing = [t["target_label"] for t in data.targets if t["contribution"] == "none"]
    n = data.presentations_per_target
    groups: dict[tuple, list[str]] = {}
    for t in data.targets:
        if t["contribution"] == "partial":
            key = (t["eligible_presentations"], t["presentations"], t["missing_presentations"])
            groups.setdefault(key, []).append(t["target_label"])
    if not groups and not missing:
        return f"All 16 targets have {n} of {n} technically eligible presentations."
    every = len(data.targets)
    partial = [f"{'all ' + str(every) + ' targets' if len(labels) == every else ', '.join(labels)} ({k}/{m}; not "
               f"eligible: {_presentation_names(keys)})" for (k, m, keys), labels in groups.items()]
    return (f"Fewer than {n} eligible presentations (partial, count shown): {', '.join(partial) or 'none'}. "
            f"No eligible presentation ({missing_word}): {', '.join(missing) or 'none'}.")


CAPTION_LINE_IN = 9 * 1.4 / 72  # height of one caption line (fontsize 9, linespacing 1.4), inches


def _grow_for_caption(figsize: tuple, axes_box: list, header_ys: tuple, caption_y: float, caption: str,
                      fits: int) -> tuple:
    """Adds height at the bottom when the caption has more than [fits] lines; everything keeps its distance from the
    top edge. Returns (figsize, axes_box, header_ys, caption_y), unchanged when the caption fits."""
    extra = max(0, caption.count("\n") + 1 - fits) * CAPTION_LINE_IN
    if not extra:
        return figsize, axes_box, header_ys, caption_y
    w, h = figsize
    grown = h + extra

    def from_top(y: float) -> float:
        return 1 - (1 - y) * h / grown

    x0, y0, bw, bh = axes_box
    box = [x0, from_top(y0 + bh) - bh * h / grown, bw, bh * h / grown]
    return (w, grown), box, tuple(from_top(y) for y in header_ys), from_top(caption_y)


def _clamp_clause(data: SessionSummaryData) -> str:
    fractions = data.clamp_fractions
    if not fractions:
        return ""
    if all(f is not None for _, f in fractions):
        return f" (clamp active for {' / '.join(f'{100 * f:.1f}%' for _, f in fractions)} of valid samples, by position)"
    measured = [f"{100 * f:.1f}% of {p.upper()}" for p, f in fractions if f is not None]
    empty = [p.upper() for p, f in fractions if f is None]
    return (f" (clamp active for {_and(measured)} valid samples; " if measured else " (") + \
        f"{_and(empty)} had no valid samples)"


def _and(items: list[str]) -> str:
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " and " + items[-1]


def render_session_gaze_map(data: SessionSummaryData, note: str | None = None):
    w, h = data.width_px, data.height_px
    n = data.presentations_per_target
    shown, caveat = _session_sentences(data)
    lines = [
        "Crosses mark the 16 target positions; circles mark the average estimated gaze for each target (the small orange "
        "label names its target); arrows show the displacement from target to average.",
        shown + f" The circle averages the mean positions of the target's technically eligible presentations with equal "
        f"weight (1/{n} each with complete data), whatever their sample counts.",
        caveat,
        "Estimates are the pipeline's smoothed output, clamped by the model to [0, 1] per axis" + _clamp_clause(data)
        + ". Averages hide the spread of samples, and opposite errors cancel in them; see the error figure.",
        _partial_sentence(data, "no estimate"),
    ]
    if note:
        lines.append(note)
    caption = _wrap(lines, 98)
    figsize, box, ys, caption_y = _grow_for_caption((7.2, 13.6), [0.12, 0.27, 0.80, 0.60],
                                                    (0.984, 0.953, 0.934, 0.924), 0.172, caption, 13)
    fig, _, _ = _draw_gaze_map(
        data.targets, w, h, figsize, box,
        (f"Target position (shown {n} times)", f"Average estimated gaze ({n} presentations)",
         f"Partial average (fewer than {n} presentations)"))
    _session_header(fig, data, "Target vs average estimated gaze", ys, f"{w} × {h} px, to scale", 88)
    fig.text(0.07, caption_y, caption, ha="left", va="top", fontsize=9, color=PALETTE["text"], linespacing=1.4)
    return fig


def render_session_target_error(data: SessionSummaryData, ymax: float, threshold: float, rule: dict,
                                note: str | None = None):
    _, plt = _mpl()
    from matplotlib.patches import Patch
    from matplotlib.lines import Line2D
    n = data.presentations_per_target
    labels = [t["target_label"] for t in data.targets]
    w, h = data.width_px, data.height_px
    shown, caveat = _session_sentences(data)
    lines = [
        shown + f" Each bar is the equal-weight mean of those presentations' mean errors (technically eligible ones "
        f"only), where a presentation's mean error is the mean over its valid samples of the distance from the smoothed "
        f"estimate to the target, √(Δx² + Δy²). Lower is better.",
        "A bar is not the distance from the target to the averaged position in the gaze map: opposite errors cancel in "
        "that average, not here.",
        f"The dashed line is the {threshold:.2f} error condition of the original per-presentation success rule, shown for "
        f"reference. A bar below it does not show that every presentation passed: success was judged per presentation "
        f"and also needed coverage ≥ {rule['min_valid_coverage']:.2f}, at least {rule['min_valid_samples']} valid "
        f"samples and a complete presentation.",
        caveat,
        f"Units: x is divided by the display width and y by the display height; this is not a percentage of the screen "
        f"diagonal. On this {w} × {h} px display a purely horizontal error of 0.10 is {0.1 * w:.0f} px and a purely "
        f"vertical error of 0.10 is {0.1 * h:.1f} px.",
    ]
    if any(t["contribution"] != "complete" for t in data.targets):
        lines.append(_partial_sentence(data, "N/A"))
    if note:
        lines.append(note)
    caption = _wrap(lines, 150)
    figsize, box, ys, caption_y = _grow_for_caption((12.0, 9.0), [0.075, 0.315, 0.9, 0.49],
                                                    (0.986, 0.940, 0.913, 0.899), 0.225, caption, 11)
    fig = plt.figure(figsize=figsize)
    ax = fig.add_axes(box)
    partial = False
    for i, t in enumerate(data.targets):
        value = t["mean_presentation_error_norm"]
        if value is None:
            ax.text(i, ymax * 0.015, "N/A", ha="center", va="bottom", fontsize=9, color=PALETTE["na"])
            continue
        complete = t["contribution"] == "complete"
        partial |= not complete
        ax.bar(i, value, 0.62, color=PALETTE["pass1"] if complete else PALETTE["pass2"], edgecolor=PALETTE["pass1"],
               linewidth=0.8, hatch=None if complete else "///", zorder=3)
        label = f"{value:.2f}" if complete else f"{value:.2f}\n{t['eligible_presentations']}/{t['presentations']}"
        ax.text(i, value + ymax * 0.01, label, ha="center", va="bottom", fontsize=8, color=PALETTE["text"], zorder=5)
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
    handles = [Patch(fc=PALETTE["pass1"], ec=PALETTE["pass1"], label=f"Average of the {n} presentations' mean errors"),
               Line2D([], [], color=PALETTE["threshold"], ls="--", lw=1.4,
                      label=f"Per-presentation error condition {threshold:.2f} (reference)")]
    if partial:
        handles.insert(1, Patch(fc=PALETTE["pass2"], ec=PALETTE["pass1"], hatch="///",
                                label=f"Partial: fewer than {n} presentations (count shown)"))
    ax.legend(handles=handles, loc="lower left", bbox_to_anchor=(0.0, 1.01), ncol=3, frameon=False, fontsize=9)
    _session_header(fig, data, f"Average target error across the {n} presentations", ys,
                    f"{n} presentations per target", 140)
    fig.text(0.075, caption_y, caption, ha="left", va="top", fontsize=9, color=PALETTE["text"], linespacing=1.4)
    return fig


def figure_bytes(fig) -> dict:
    """{'svg': bytes, 'png': bytes} of a figure, then closes it. SVG gets LF line endings on every OS so the bytes (and
    recorded checksums) are platform-independent."""
    _, plt = _mpl()
    out = {}
    for ext, kw in (("svg", {"metadata": {"Date": None}}), ("png", {"dpi": 150, "metadata": {"Software": None}})):
        buf = io.BytesIO()
        fig.savefig(buf, format=ext, facecolor="white", **kw)
        data = buf.getvalue()
        out[ext] = data.replace(b"\r\n", b"\n") if ext == "svg" else data
    plt.close(fig)
    return out


def save(fig, path_stem: Path) -> list[Path]:
    out = []
    for ext, data in figure_bytes(fig).items():
        p = path_stem.with_suffix(f".{ext}")
        p.write_bytes(data)
        out.append(p)
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
    if not str(summary.get("measured_coordinate", "")).startswith("smoothed_x_norm/smoothed_y_norm"):
        raise FigureInputError(f"unexpected measured coordinate in summary.json: {summary.get('measured_coordinate')!r}")
    sessions = check_selection(summary, selection, sha256_file(selection_path), protocol["timing"]["blocks"])
    blocks = [block_figure_data(s, b, protocol) for s in sessions for b in s["blocks"]]
    rule = protocol["performance_criteria"]["successful_presentation"]
    return summary, selection, protocol, sessions, blocks, rule


def run_per_block(summary_path: Path, selection_path: Path, derived: Path, figures: Path,
                  protocol_path: Path = DEFAULT_PROTOCOL) -> dict:
    """The per-block view (two figures per head-position block; tool version 1.0.0 output, unchanged)."""
    summary, selection, protocol, sessions, blocks, rule = prepare(summary_path, selection_path, protocol_path)
    threshold = rule["max_mean_error_norm"]
    ymax = shared_error_ymax(blocks, threshold)
    displays = display_consistency(blocks)
    note = None if displays["identical"] else "Note: the selected sessions have different display sizes or rotations; compare them with care."
    derived.mkdir(parents=True, exist_ok=True)
    figures.mkdir(parents=True, exist_ok=True)
    _write(derived / "figure_presentations.csv", table_csv(PRESENTATION_COLUMNS, [r for d in blocks for r in d.presentations]))
    _write(derived / "figure_target_centroids.csv", table_csv(TARGET_COLUMNS, [t for d in blocks for t in d.targets]))
    figure_entries = []
    for d in blocks:
        for kind, fig in (("gaze_map", render_gaze_map(d, note)), ("target_error", render_target_error(d, ymax, threshold, rule, note))):
            for p in save(fig, figures / f"{d.stem}_{kind}"):
                figure_entries.append({"path": _rel(p), "sha256": sha256_file(p), "session_id": d.session_id,
                                       "block_index": d.block_index, "head_position": d.head_position, "figure": kind,
                                       "designation": d.designation_kind})
    import matplotlib
    import numpy
    manifest = {
        "tool": "calib_eval_figures", "tool_version": TOOL_VERSION, "adapted_from": ADAPTED_FROM,
        "versions": {"python": platform.python_version(), "matplotlib": matplotlib.__version__, "numpy": numpy.__version__},
        "inputs": {
            "summary": {"path": _rel(summary_path), "sha256": sha256_file(summary_path),
                        "analyzer_version": summary["tool_version"], "source_label": summary.get("source_label"),
                        "measured_coordinate": summary.get("measured_coordinate")},
            "selection": {"path": _rel(selection_path), "sha256": sha256_file(selection_path)},
            "protocol": {"path": _rel(protocol_path), "id": protocol["protocol_id"], "version": protocol["protocol_version"],
                         "sha256_lf": summary["protocol"]["sha256"]},
            "frozen_raw_inputs": selection.get("frozen_inputs"),
        },
        "sessions": [{"calibration_id": s["calibration_id"], "participant_id": s["participant_id"],
                      "session_label": s["session_label"], "session_id": s["session_id"],
                      "short_id": short_session_id(s["session_id"]), "start_wall_time_utc": s["start_wall_time_utc"],
                      "mode": s["mode"], "block_order": s["block_order"], "official": s["official"],
                      "official_exclusions": s["official_exclusions"], "designation": designation(s)[1],
                      "build": s["build"], "calibration_inputs_sha256": s["calibration"].get("calibration_inputs_sha256"),
                      "conditions": s["conditions"], "raw_path": s["path"]} for s in sessions],
        "excluded": selection.get("excluded", []),
        "shared_scales": {"error_axis_norm": [0.0, ymax], "error_threshold_norm": threshold, "display": displays},
        "definitions": {
            "gaze_map_estimate": "per block and target: equal-weight mean of the analyzer centroids (mean smoothed x, y of "
                                 "valid measurement samples) of its technically eligible presentations",
            "target_error_bar": "analyzer presentation mean error: mean over valid measurement samples of "
                                "sqrt((smoothed_x - target_x)^2 + (smoothed_y - target_y)^2); technically ineligible "
                                "presentations are shown as N/A",
            "measured_coordinate": "GazePipeline's smoothed output, which inherits GazeModel's [0, 1] clamp",
        },
        "outputs": {
            "tables": [_rel(derived / "figure_presentations.csv"), _rel(derived / "figure_target_centroids.csv")],
            "figures": figure_entries,
        },
    }
    _write(derived / "figures-manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    return manifest


SESSION_TABLE = "target_summary.csv"
SESSION_MANIFEST = "figures-manifest.json"


def _write_session_folder(folder: Path, files: dict, replace: bool) -> None:
    """Writes one session's figures and table. An existing folder is only rewritten when its figures and table would
    stay byte-identical (the manifest may be refreshed), unless [replace] is given deliberately."""
    if folder.exists():
        changed = [name for name, data in files.items()
                   if name != SESSION_MANIFEST and (not (folder / name).is_file() or (folder / name).read_bytes() != data)]
        extra = sorted(p.name for p in folder.iterdir() if p.is_file() and p.name not in files)
        if (changed or extra) and not replace:
            raise FigureInputError(f"{folder} already holds different outputs ({', '.join(changed + extra)}); refusing to "
                                   "overwrite them. Choose another --figures directory, or pass --replace deliberately")
    folder.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        (folder / name).write_bytes(data)


def run_session(summary_path: Path, selection_path: Path, figures: Path, protocol_path: Path = DEFAULT_PROTOCOL,
                replace: bool = False) -> dict:
    """The default view: per selected session, two figures combining all head positions and passes per target, written
    with their source table and manifest to <figures>/<PARTICIPANT>_<LABEL>_<short id>/ (sessions are never combined)."""
    summary, selection, protocol, sessions, blocks, rule = prepare(summary_path, selection_path, protocol_path)
    threshold = rule["max_mean_error_norm"]
    summaries = [session_summary_data(s, protocol) for s in sessions]
    if len({s.folder for s in summaries}) != len(summaries):
        raise FigureInputError("two selected sessions would share an output folder")
    ymax = session_error_ymax(summaries, threshold)
    displays = display_consistency(blocks)
    note = None if displays["identical"] else "Note: the selected sessions have different display sizes or rotations; compare them with care."
    import matplotlib
    import numpy
    results = []
    for session, data in zip(sessions, summaries):
        folder = figures / data.folder
        files = {SESSION_TABLE: table_csv(SESSION_TARGET_COLUMNS, data.targets).encode("utf-8")}
        entries = []
        for kind, fig in (("gaze_map", render_session_gaze_map(data, note)),
                          ("target_error", render_session_target_error(data, ymax, threshold, rule, note))):
            for ext, payload in figure_bytes(fig).items():
                name = f"{data.stem}_{kind}.{ext}"
                files[name] = payload
                entries.append({"path": _rel(folder / name), "sha256": hashlib.sha256(payload).hexdigest(), "figure": kind})
        manifest = {
            "tool": "calib_eval_figures", "tool_version": TOOL_VERSION, "view": "session_summary",
            "adapted_from": ADAPTED_FROM,
            "versions": {"python": platform.python_version(), "matplotlib": matplotlib.__version__, "numpy": numpy.__version__},
            "inputs": {
                "summary": {"path": _rel(summary_path), "sha256": sha256_file(summary_path),
                            "analyzer_version": summary["tool_version"], "source_label": summary.get("source_label"),
                            "measured_coordinate": summary.get("measured_coordinate")},
                "selection": {"path": _rel(selection_path), "sha256": sha256_file(selection_path)},
                "protocol": {"path": _rel(protocol_path), "id": protocol["protocol_id"], "version": protocol["protocol_version"],
                             "sha256_lf": summary["protocol"]["sha256"]},
                "frozen_raw_inputs": selection.get("frozen_inputs"),
            },
            "session": {"calibration_id": session["calibration_id"], "participant_id": session["participant_id"],
                        "session_label": session["session_label"], "session_id": session["session_id"],
                        "short_id": data.short_id, "start_wall_time_utc": session["start_wall_time_utc"],
                        "mode": session["mode"], "block_order": session["block_order"], "official": session["official"],
                        "official_exclusions": session["official_exclusions"], "designation": data.designation_text,
                        "build": session["build"],
                        "calibration_inputs_sha256": session["calibration"].get("calibration_inputs_sha256"),
                        "conditions": session["conditions"], "raw_path": session["path"]},
            "acceptance": {"note": "copied unchanged from summary.json; this view computes no pooled metric or verdict",
                           "block_verdicts": [{"block_index": b["block_index"], "head_position": b["head_position"],
                                               "verdict": b["verdict"]} for b in session["blocks"]],
                           "session_verdict": session["verdict"]},
            "aggregation": {
                "presentations_per_target": data.presentations_per_target,
                "matching": "presentations are assigned to targets by their canonical grid coordinates (target_x_norm, "
                            "target_y_norm); the protocol target id at those coordinates must agree",
                "eligibility": "the analyzer's technical eligibility, unchanged; ineligible presentations are left out and "
                               "counted, never replaced by zero",
                "gaze_map_estimate": "per target: equal-weight mean, over the target's technically eligible presentations "
                                     "in every block and pass, of the analyzer centroid (mean smoothed x, y of the "
                                     "presentation's valid measurement samples)",
                "target_error_bar": "per target: equal-weight mean, over the same presentations, of the analyzer "
                                    "presentation mean error (mean over valid samples of sqrt((smoothed_x - target_x)^2 + "
                                    "(smoothed_y - target_y)^2)); not the distance from the target to the averaged position",
                "measured_coordinate": "GazePipeline's smoothed output, which inherits GazeModel's [0, 1] clamp",
            },
            "shared_scales": {"error_axis_norm": [0.0, ymax], "error_reference_norm": threshold, "display": displays},
            "outputs": {"table": {"path": _rel(folder / SESSION_TABLE),
                                  "sha256": hashlib.sha256(files[SESSION_TABLE]).hexdigest()},
                        "figures": entries},
        }
        files[SESSION_MANIFEST] = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
        _write_session_folder(folder, files, replace)
        results.append(manifest)
    return {"view": "session_summary", "sessions": results}


def run(summary_path: Path, selection_path: Path, figures: Path, protocol_path: Path = DEFAULT_PROTOCOL,
        view: str = "session", derived: Path | None = None, replace: bool = False) -> dict:
    if view == "session":
        return run_session(summary_path, selection_path, figures, protocol_path, replace)
    if view == "per-block":
        if derived is None:
            raise FigureInputError("the per-block view needs --derived for its plot tables and manifest")
        return run_per_block(summary_path, selection_path, derived, figures, protocol_path)
    raise FigureInputError(f"unknown view {view!r}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--summary", type=Path, required=True, help="analyzer summary.json produced with --selection")
    parser.add_argument("--selection", type=Path, required=True, help="analysis-input manifest")
    parser.add_argument("--figures", type=Path, required=True,
                        help="session view: root directory, one subfolder per session; per-block view: figure directory")
    parser.add_argument("--view", choices=("session", "per-block"), default="session",
                        help="session (default): two figures per session combining all head positions and passes; "
                             "per-block: two figures per head-position block")
    parser.add_argument("--derived", type=Path, help="per-block view only: directory for its plot tables and manifest")
    parser.add_argument("--replace", action="store_true",
                        help="session view: allow replacing a session folder whose figures or table would change")
    parser.add_argument("--protocol", type=Path, default=DEFAULT_PROTOCOL)
    args = parser.parse_args(argv)
    try:
        result = run(args.summary, args.selection, args.figures, args.protocol, args.view, args.derived, args.replace)
    except FigureInputError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    manifests = result["sessions"] if result.get("view") == "session_summary" else [result]
    for m in manifests:
        for f in m["outputs"]["figures"]:
            print(f"wrote {f['path']}")
        if "table" in m["outputs"]:
            print(f"wrote {m['outputs']['table']['path']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
