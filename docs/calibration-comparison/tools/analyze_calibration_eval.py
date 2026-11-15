#!/usr/bin/env python3
"""Metrics-only analysis of NoTouch calibration-comparison evaluation sessions (log schema v4).

Reads each session's metadata.json, attempts.csv, events.csv, model_snapshot.json and SHA256SUMS. Never modifies them.
Criteria, thresholds, blocks and the case schedule come from the protocol manifest
(docs/calibration-comparison/protocol.json); sessions recorded with a different manifest, pipeline variant, log schema
or an undeclared calibration ID are rejected. Python standard library only; no network; deterministic output for the
same inputs and arguments. No figures are produced.

One session = one fresh 27-anchor calibration + three instructed head-position blocks (neutral, higher, lower), each
the unchanged Iteration 3 schedule. Every criterion is evaluated per block with the Iteration 3 definitions and
thresholds (the per-block code is the Iteration 3 session code, unchanged); a session is MET only if all three blocks
are MET. Preparation and countdown attempts never enter a block metric. Block results are always reported separately,
by candidate (calibration ID) and instructed head position.

The measured coordinate is the smoothed output, which inherits GazeModel's [0, 1] clamp. The displayed target is the
*instructed* fixation point and head positions are *instructed* positions: spatial metrics are target-referenced gaze
errors, and logged face position and scale are summarized for inspection only.

Usage:
    python docs/calibration-comparison/tools/analyze_calibration_eval.py <raw-data dir | session dir> --out <dir>
        [--protocol docs/calibration-comparison/protocol.json] [--source-label TEXT] [--selection MANIFEST]
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import re
import statistics
import sys
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path

TOOL_VERSION = "1.0.0"
SUPPORTED_SCHEMA_VERSIONS = (4,)
RECORD_TYPE = "notouch_calibration_comparison_session"
PIPELINE_VARIANT = "home_screen_gaze_pipeline_e8fa01e"
PILOT_PREFIX = "PILOT"
MODES = ("blind_measurement", "live_preview")
PRIMARY_MODE = "blind_measurement"
MET, NOT_MET, NOT_MEASURED, INSUFFICIENT, INCOMPLETE = "MET", "NOT_MET", "NOT_MEASURED", "INSUFFICIENT_DATA", "INCOMPLETE"
OUTCOMES = ("processed", "discarded_closing", "discarded_epoch", "discarded_delayed", "discarded_block_start",
            "discarded_model_changed", "processing_error")
QUALITIES = ("tracking", "closed", "uncertain", "lost", "out_of_range", "stale")
DETECTORS = ("landmarker_not_ready", "no_face", "face_detected")
EYE_STATES = ("OPEN", "CLOSED", "LEFT_WINK", "RIGHT_WINK", "UNCERTAIN", "LOST")
PHASES = ("preparation", "countdown", "settling", "measurement")
BLOCK_PHASES = ("settling", "measurement")
FEATURE_COLUMNS = ("f_x", "f_y", "f_face_x", "f_face_y", "f_scale")
ATTEMPT_COLUMNS = (
    "schema_version", "session_id", "attempt_seq", "main_seq", "outcome",
    "accepted_uptime_ms", "handled_uptime_ms", "result_uptime_ms", "session_elapsed_ms",
    "block_index", "head_position", "phase", "block_elapsed_ms",
    "pass_number", "trial_index", "index_in_pass", "case_id", "target_id", "target_x_norm", "target_y_norm",
    "detector", "eye_state", "left_open", "right_open", "feature_rejection",
) + FEATURE_COLUMNS + (
    "quality", "prediction_rejection", "valid", "invalid_reason",
    "linear_x_norm", "linear_y_norm", "predicted_x_norm", "predicted_y_norm", "smoothed_x_norm", "smoothed_y_norm",
    "pointer_shown", "rendered_x_px", "rendered_y_px", "landmarker_ms", "delivery_ms", "latency_ms", "published",
)
EVENT_COLUMNS = ("schema_version", "session_id", "main_seq", "event_uptime_ms", "session_elapsed_ms", "block_index",
                 "phase", "event", "detail")
CHECKSUM_FILE = "SHA256SUMS"
CHECKSUMMED_FILES = ("attempts.csv", "events.csv", "metadata.json", "model_snapshot.json")
OUTPUT_FILES = ("summary.json", "sessions.csv", "blocks.csv", "presentations.csv", "report.md")
CRITERIA_IDS = ("successful_presentation_rate", "p95_presentation_error", "mean_jitter_rms",
                "tracking_availability", "valid_sample_rate", "p95_processing_latency")
EXPERIMENT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_PROTOCOL = EXPERIMENT_DIR / "protocol.json"
MAX_LISTED = 50
IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
SHA1_HEX = re.compile(r"^[0-9a-f]{40}$")


class ProtocolError(Exception):
    """The protocol manifest is unusable."""


class SessionError(Exception):
    """A session that cannot be analyzed at all (missing files, wrong schema or protocol, inconsistent identity)."""


# ---------------------------------------------------------------- statistics (unchanged from Iteration 3)

def mean(values):
    return math.fsum(values) / len(values) if values else None


def median(values):
    return statistics.median(values) if values else None


def nearest_rank(values, percent: int):
    """Nearest-rank percentile: the k-th smallest value, k = ceil(percent / 100 * n) in integer arithmetic."""
    if not values:
        return None
    ordered = sorted(values)
    k = max(1, (percent * len(ordered) + 99) // 100)
    return ordered[k - 1]


def normalized_sha256(path: Path) -> str:
    """SHA-256 of a file's bytes with CRLF converted to LF (the rule the app build uses for the manifest)."""
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


# ---------------------------------------------------------------- protocol manifest

@dataclass(frozen=True)
class Protocol:
    path: Path
    sha256: str
    protocol_id: str
    version: str
    grid: tuple
    presentation_ms: int
    settling_ms: int
    measurement_ms: int
    cases: tuple
    blocks: int
    countdown_ms: int
    orders: dict
    positions: tuple
    calibration_ids: tuple
    min_valid_samples: int
    min_valid_coverage: float
    min_eligible_fraction: float
    success: dict
    criteria: tuple
    plan_official: dict
    plan_pilot: dict

    @property
    def planned(self) -> int:
        """Presentations per block."""
        return len(self.cases)

    @property
    def total_ms(self) -> int:
        """Duration of one block's schedule."""
        return self.planned * self.presentation_ms

    @property
    def min_eligible_presentations(self) -> int:
        return math.ceil(Fraction(str(self.min_eligible_fraction)) * self.planned)


def load_protocol(path: Path) -> Protocol:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        timing, sufficiency = data["timing"], data["data_sufficiency"]
        performance = data["performance_criteria"]
        cases = tuple(data["cases"])
        criteria = tuple(performance["block_criteria"])
        plan = data["collection_plan"]
        protocol = Protocol(
            path=path, sha256=normalized_sha256(path), protocol_id=data["protocol_id"], version=data["protocol_version"],
            grid=tuple(timing["grid_norm"]), presentation_ms=timing["presentation_ms"], settling_ms=timing["settling_ms"],
            measurement_ms=timing["measurement_ms"], cases=cases, blocks=timing["blocks"], countdown_ms=timing["countdown_ms"],
            orders={k: tuple(v) for k, v in data["block_orders"].items()},
            positions=tuple(k for k in data["head_positions"] if k != "compliance"),
            calibration_ids=tuple(data["candidates"]["allowed_calibration_ids"]),
            min_valid_samples=sufficiency["min_valid_samples"], min_valid_coverage=sufficiency["min_valid_coverage"],
            min_eligible_fraction=sufficiency["min_eligible_fraction_for_session_spatial_criteria"],
            success=performance["successful_presentation"], criteria=criteria,
            plan_official={(p["participant_id"], p["session_label"]): p for p in plan["official"]},
            plan_pilot={(p["participant_id"], p["session_label"]): p for p in plan["technical_pilot"]},
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as e:
        raise ProtocolError(f"protocol manifest {path} unusable: {e}") from e
    if protocol.settling_ms + protocol.measurement_ms != protocol.presentation_ms:
        raise ProtocolError("protocol: settling_ms + measurement_ms != presentation_ms")
    if timing.get("presentations_per_block") != len(cases) or timing.get("block_ms") != protocol.total_ms:
        raise ProtocolError("protocol: presentation count or block_ms disagrees with the case list")
    for i, c in enumerate(cases):
        if c.get("trial_index") != i or c.get("onset_ms") != i * protocol.presentation_ms:
            raise ProtocolError(f"protocol: case {i} is out of order")
    if len({c["case_suffix"] for c in cases}) != len(cases):
        raise ProtocolError("protocol: case_suffix values are not unique")
    if tuple(c.get("id") for c in criteria) != CRITERIA_IDS or any(c.get("comparison") not in (">=", "<=") for c in criteria):
        raise ProtocolError(f"protocol: block_criteria must be {list(CRITERIA_IDS)} with >= or <= comparisons")
    if any(len(o) != protocol.blocks or sorted(o) != sorted(protocol.positions) for o in protocol.orders.values()):
        raise ProtocolError("protocol: every block order must name each head position exactly once")
    return protocol


# ---------------------------------------------------------------- session timeline

@dataclass(frozen=True)
class Block:
    index: int            # 0-based
    position: str
    preparation_start: int | None
    ready: int | None
    start: int | None
    planned_end: int | None


@dataclass(frozen=True)
class Location:
    block: int
    position: str
    phase: str
    trial: int | None
    block_elapsed: int | None


class Timeline:
    """The session timeline from metadata.blocks; the same rule as the app's EvaluationSchedule.locate."""

    def __init__(self, start: int, blocks: list[Block], presentation_ms: int, settling_ms: int, block_ms: int):
        self.start, self.blocks = start, blocks
        self.presentation_ms, self.settling_ms, self.block_ms = presentation_ms, settling_ms, block_ms

    def locate(self, t: int) -> Location | None:
        if t < self.start:
            return None
        for b in self.blocks:
            if b.ready is None or t < b.ready:
                return Location(b.index, b.position, "preparation", None, None)
            if t < b.start:
                return Location(b.index, b.position, "countdown", None, t - b.start)
            elapsed = t - b.start
            if elapsed < self.block_ms:
                phase = "settling" if elapsed % self.presentation_ms < self.settling_ms else "measurement"
                return Location(b.index, b.position, phase, elapsed // self.presentation_ms, elapsed)
        return None


# ---------------------------------------------------------------- session loading

@dataclass
class Attempt:
    seq: int
    main_seq: int | None
    outcome: str
    accepted_ms: int
    block: int | None
    position: str | None
    phase: str
    trial: int | None
    quality: str | None
    valid: bool
    invalid_reason: str | None
    detail: str | None
    linear: tuple | None
    predicted: tuple | None
    point: tuple | None
    rendered: tuple | None
    latency_ms: float | None
    landmarker_ms: float | None
    delivery_ms: float | None
    face: tuple | None


@dataclass
class Session:
    path: Path
    label: str
    metadata: dict
    timeline: Timeline
    snapshot: dict = field(default_factory=dict)
    attempts: list = field(default_factory=list)
    events: list = field(default_factory=list)
    csv_rows: int = 0
    unassigned_rows: int = 0
    issues: list = field(default_factory=list)
    issue_count: int = 0
    integrity_errors: list = field(default_factory=list)
    integrity_error_count: int = 0
    checksums: str = "absent"

    def issue(self, message: str) -> None:
        self.issue_count += 1
        if len(self.issues) < MAX_LISTED:
            self.issues.append(message)

    def integrity(self, message: str) -> None:
        self.integrity_error_count += 1
        if len(self.integrity_errors) < MAX_LISTED:
            self.integrity_errors.append(message)


def _require(mapping, key, kind, where="metadata"):
    if not isinstance(mapping, dict) or key not in mapping:
        raise SessionError(f"{where}: missing required field '{key}'")
    value = mapping[key]
    ok = (isinstance(value, int) and not isinstance(value, bool)) if kind is int else isinstance(value, kind)
    if not ok:
        raise SessionError(f"{where}: field '{key}' has invalid value {value!r}")
    return value


def _optional_int_field(mapping, key, where):
    value = mapping.get(key)
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool):
        raise SessionError(f"{where}: field '{key}' has invalid value {value!r}")
    return value


def calibration_id_of(meta: dict) -> str | None:
    calibration = meta.get("calibration")
    return calibration.get("calibration_id") if isinstance(calibration, dict) else None


def validate_metadata(meta, protocol: Protocol) -> tuple[dict, Timeline]:
    if not isinstance(meta, dict):
        raise SessionError("metadata.json is not a JSON object")
    version = _require(meta, "schema_version", int)
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        raise SessionError(f"unsupported schema_version {version} (supported: {list(SUPPORTED_SCHEMA_VERSIONS)}); "
                           "schema 3 sessions are Iteration 3 home-screen sessions and are never pooled with this comparison")
    if meta.get("record_type") != RECORD_TYPE:
        raise SessionError(f"metadata: record_type is not {RECORD_TYPE!r}")
    variant = meta["pipeline"].get("variant") if isinstance(meta.get("pipeline"), dict) else None
    if variant != PIPELINE_VARIANT:
        raise SessionError(f"metadata: pipeline variant {variant!r} is not {PIPELINE_VARIANT!r}; never pooled with this dataset")
    cid = calibration_id_of(meta)
    if cid not in protocol.calibration_ids:
        raise SessionError(f"metadata: calibration_id {cid!r} is not a declared candidate {list(protocol.calibration_ids)}")
    if not _require(meta, "session_id", str):
        raise SessionError("metadata: empty session_id")
    if not IDENTIFIER.match(_require(meta, "participant_id", str)):
        raise SessionError(f"metadata: invalid participant_id {meta['participant_id']!r}")
    _require(meta, "session_label", str)
    if _require(meta, "mode", str) not in MODES:
        raise SessionError(f"metadata: unknown mode {meta['mode']!r}")
    if _require(meta, "final_state", str) not in ("complete", "incomplete"):
        raise SessionError(f"metadata: unknown final_state {meta['final_state']!r}")
    _require(meta, "end_reason", str)
    start = _require(meta, "start_uptime_ms", int)
    _optional_int_field(meta, "end_uptime_ms", "metadata")
    p = _require(meta, "protocol", dict)
    recorded = _require(p, "sha256", str, "metadata.protocol")
    if recorded != protocol.sha256:
        raise SessionError(f"recorded with protocol sha256 {recorded}, but the manifest {protocol.path.name} has "
                           f"{protocol.sha256}; analyze it with the manifest it was recorded with")
    expected = {"id": protocol.protocol_id, "version": protocol.version, "presentations_per_block": protocol.planned,
                "presentation_ms": protocol.presentation_ms, "settling_ms": protocol.settling_ms,
                "measurement_ms": protocol.measurement_ms, "blocks_planned": protocol.blocks,
                "countdown_ms": protocol.countdown_ms, "case_suffixes": [c["case_suffix"] for c in protocol.cases]}
    for key, value in expected.items():
        if p.get(key) != value:
            raise SessionError(f"metadata.protocol.{key} {p.get(key)!r} disagrees with the manifest ({value!r})")
    order = p.get("block_order")
    if order not in protocol.orders or tuple(p.get("block_positions") or ()) != protocol.orders[order]:
        raise SessionError(f"metadata.protocol: block order {order!r} / positions {p.get('block_positions')!r} not declared")
    display = _require(meta, "display", dict)
    for key in ("width_px", "height_px"):
        if _require(display, key, int, "metadata.display") <= 0:
            raise SessionError(f"metadata.display.{key} must be positive")
    raw_blocks = _require(meta, "blocks", list)
    if len(raw_blocks) != protocol.blocks:
        raise SessionError(f"metadata.blocks has {len(raw_blocks)} entries, expected {protocol.blocks}")
    blocks, previous_end = [], start
    for k, b in enumerate(raw_blocks):
        where = f"metadata.blocks[{k}]"
        if not isinstance(b, dict) or b.get("block_index") != k + 1 or b.get("head_position") != protocol.orders[order][k]:
            raise SessionError(f"{where}: block index or head position disagrees with the declared order")
        prep, ready = _optional_int_field(b, "preparation_start_uptime_ms", where), _optional_int_field(b, "ready_uptime_ms", where)
        bstart, bend = _optional_int_field(b, "block_start_uptime_ms", where), _optional_int_field(b, "planned_block_end_uptime_ms", where)
        if prep != previous_end:
            raise SessionError(f"{where}: preparation does not start at the previous block's planned end (or the session start)")
        if ready is None:
            if bstart is not None or bend is not None or any(x.get("ready_uptime_ms") is not None for x in raw_blocks[k + 1:]):
                raise SessionError(f"{where}: a block without Ready cannot have a start or later blocks")
        elif prep is None or ready < prep or bstart != ready + protocol.countdown_ms or bend != bstart + protocol.total_ms:
            raise SessionError(f"{where}: ready/start/end times disagree with the countdown and block duration")
        blocks.append(Block(k, b["head_position"], prep, ready, bstart, bend))
        previous_end = bend
    return meta, Timeline(start, blocks, protocol.presentation_ms, protocol.settling_ms, protocol.total_ms)


def verify_checksums(path: Path) -> tuple[str, list[str]]:
    """'verified', 'absent' or 'mismatch' for the session's SHA256SUMS, with problems found."""
    sums = path / CHECKSUM_FILE
    if not sums.is_file():
        return "absent", []
    problems, listed = [], set()
    for line in sums.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        digest, sep, name = line.partition("  ")
        if not sep or not re.fullmatch(r"[0-9a-f]{64}", digest):
            problems.append(f"unreadable SHA256SUMS line {line!r}")
            continue
        listed.add(name)
        target = path / name
        if "/" in name or "\\" in name or not target.is_file():
            problems.append(f"{name}: listed in SHA256SUMS but missing")
        elif hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            problems.append(f"{name}: SHA-256 differs from SHA256SUMS (file changed after recording)")
    for name in CHECKSUMMED_FILES:
        if name not in listed:
            problems.append(f"{name}: not covered by SHA256SUMS")
    return ("mismatch" if problems else "verified"), problems


def _number(cell: str, what: str) -> float | None:
    if cell == "":
        return None
    value = float(cell)
    if not math.isfinite(value):
        raise ValueError(f"non-finite {what} {cell!r}")
    return value


def _feature(cell: str) -> float | None:
    """A logged feature; Double.toString may write NaN or Infinity inside a present feature vector."""
    return None if cell == "" else float(cell)


def _pair(row, x_key, y_key) -> tuple | None:
    x, y = _number(row[x_key], x_key), _number(row[y_key], y_key)
    if (x is None) != (y is None):
        raise ValueError(f"only one of {x_key}/{y_key} is present")
    return None if x is None else (x, y)


def _optional_int(cell: str) -> int | None:
    return None if cell == "" else int(cell)


def _cell_number(v) -> str:
    """Kotlin Double.toString of a protocol coordinate (0.2 -> '0.2')."""
    return "" if v is None else repr(float(v))


def load_session(path: Path, root: Path, protocol: Protocol) -> Session:
    label = path.relative_to(root).as_posix() if path != root else path.name
    missing = [n for n in ("metadata.json", "attempts.csv") if not (path / n).is_file()]
    if missing:
        raise SessionError(f"missing {', '.join(missing)}")
    try:
        meta = json.loads((path / "metadata.json").read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise SessionError(f"metadata.json unreadable: {e}") from e
    meta, timeline = validate_metadata(meta, protocol)
    session = Session(path, label, meta, timeline)
    session.checksums, problems = verify_checksums(path)
    for problem in problems:
        session.integrity(problem)
    if session.checksums == "absent" and meta["final_state"] == "complete":
        session.integrity("SHA256SUMS missing for a complete session")
    for name in ("events.csv", "model_snapshot.json"):
        if not (path / name).is_file():
            session.integrity(f"{name} missing")
    if (path / "model_snapshot.json").is_file():
        try:
            session.snapshot = json.loads((path / "model_snapshot.json").read_text(encoding="utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as e:
            session.integrity(f"model_snapshot.json unreadable: {e}")
    _check_snapshot(session, protocol)
    _read_attempts(session, (path / "attempts.csv").read_text(encoding="utf-8"), protocol)
    if (path / "events.csv").is_file():
        _read_events(session, (path / "events.csv").read_text(encoding="utf-8"))
    _check_integrity(session)
    return session


def _check_snapshot(s: Session, protocol: Protocol) -> None:
    snap, meta = s.snapshot, s.metadata
    if not snap:
        return
    anchors = snap.get("anchors") if isinstance(snap.get("anchors"), list) else []
    labels = [(a.get("pass"), a.get("point")) for a in anchors if isinstance(a, dict)]
    if labels != [(p, q) for p in (1, 2, 3) for q in range(1, 10)]:
        s.integrity("model_snapshot does not hold 27 anchors labelled pass 1..3 x point 1..9 in collection order")
    if snap.get("snapshot_schema_version") != 2:
        s.integrity(f"model_snapshot schema {snap.get('snapshot_schema_version')!r} is not 2")
    if snap.get("calibration_id") != calibration_id_of(meta):
        s.integrity("model_snapshot calibration_id differs from metadata.calibration.calibration_id")
    calibration = meta.get("calibration") or {}
    if snap.get("calibration_inputs_sha256") != calibration.get("calibration_inputs_sha256"):
        s.integrity("model_snapshot calibration_inputs_sha256 differs from metadata.calibration")


def _read_attempts(s: Session, text: str, protocol: Protocol) -> None:
    meta = s.metadata
    reader = csv.reader(io.StringIO(text, newline=""))
    header = next(reader, None)
    if header is None or tuple(header) != ATTEMPT_COLUMNS:
        raise SessionError("attempts.csv header differs from log schema v4")
    seen = set()
    for line_no, cells in enumerate(reader, start=2):
        if not cells:
            continue
        s.csv_rows += 1
        if len(cells) != len(header):
            s.unassigned_rows += 1
            s.integrity(f"attempts.csv line {line_no}: {len(cells)} cells, expected {len(header)}")
            continue
        row = dict(zip(header, cells))
        if row["session_id"] != meta["session_id"]:
            raise SessionError(f"attempts.csv line {line_no}: session_id {row['session_id']!r} does not match metadata")
        try:
            if int(row["schema_version"]) != meta["schema_version"]:
                raise ValueError("schema_version differs from metadata")
            seq = int(row["attempt_seq"])
            if seq in seen:
                raise ValueError(f"duplicate attempt_seq {seq}")
            outcome = row["outcome"]
            if outcome not in OUTCOMES:
                raise ValueError(f"unknown outcome {outcome!r}")
            accepted = int(row["accepted_uptime_ms"])
            # Assignment by the frame-acceptance timestamp and the session timeline, never by arrival time.
            loc = s.timeline.locate(accepted)
            if loc is None:
                raise ValueError(f"accepted time {accepted} is outside the session timeline")
            case = protocol.cases[loc.trial] if loc.trial is not None else None
            derived = (str(loc.block + 1), loc.position, loc.phase,
                       "" if loc.block_elapsed is None else str(loc.block_elapsed),
                       "" if case is None else f"{loc.position}-{case['case_suffix']}",
                       "" if case is None else case["target_id"],
                       "" if case is None else _cell_number(case["target_x_norm"]),
                       "" if case is None else _cell_number(case["target_y_norm"]))
            logged = (row["block_index"], row["head_position"], row["phase"], row["block_elapsed_ms"], row["case_id"],
                      row["target_id"], row["target_x_norm"], row["target_y_norm"])
            if logged != derived:
                s.integrity(f"attempts.csv line {line_no}: logged block/phase/case disagrees with the timeline (timeline used)")
            quality = row["quality"] or None
            if quality is not None and quality not in QUALITIES:
                raise ValueError(f"unknown quality {quality!r}")
            if outcome == "processed" and quality is None:
                raise ValueError("processed attempt without a quality")
            if row["detector"] and row["detector"] not in DETECTORS:
                raise ValueError(f"unknown detector {row['detector']!r}")
            if row["eye_state"] and row["eye_state"] not in EYE_STATES:
                raise ValueError(f"unknown eye_state {row['eye_state']!r}")
            point = _pair(row, "smoothed_x_norm", "smoothed_y_norm")
            predicted, linear = _pair(row, "predicted_x_norm", "predicted_y_norm"), _pair(row, "linear_x_norm", "linear_y_norm")
            rendered = _pair(row, "rendered_x_px", "rendered_y_px")
            valid = outcome == "processed" and quality == "tracking" and point is not None
            if row["valid"] not in ("true", "false"):
                raise ValueError(f"valid {row['valid']!r} is not true/false")
            if (row["valid"] == "true") != valid:
                s.integrity(f"attempts.csv line {line_no}: valid flag disagrees with outcome, quality and coordinates")
            # predict is the linear output clamped to [0, 1]; anything else is not this pipeline's record.
            if (linear is None) != (predicted is None) or (linear is not None and predicted != tuple(min(max(v, 0.0), 1.0) for v in linear)):
                s.integrity(f"attempts.csv line {line_no}: predicted coordinate is not the [0, 1]-clamped linear output")
            if outcome == "processed" and (quality == "tracking") != (predicted is not None):
                s.integrity(f"attempts.csv line {line_no}: quality {quality!r} disagrees with the predicted coordinate")
            reason = None if valid else (outcome if outcome != "processed" else (quality or "unspecified"))
            if (row["invalid_reason"] or None) != reason:
                s.issue(f"attempts.csv line {line_no}: invalid_reason {row['invalid_reason']!r} differs from derived {reason!r}")
            detail = None
            if not valid and outcome == "processed":
                if quality == "out_of_range":
                    detail = row["prediction_rejection"] or "unspecified"
                elif quality == "stale":
                    detail = "stale_timestamp"
                else:
                    detail = (row["feature_rejection"] or (row["detector"] if row["detector"] != "face_detected" else "")
                              or (f"eye_state_{row['eye_state']}" if row["eye_state"] else "")) or "unspecified"
            latency = _number(row["latency_ms"], "latency_ms")
            if latency is not None and latency < 0:
                raise ValueError("negative latency_ms")
            face = None
            if row["f_face_x"] != "":
                fx, fy, sc = _feature(row["f_face_x"]), _feature(row["f_face_y"]), _feature(row["f_scale"])
                if all(math.isfinite(v) for v in (fx, fy, sc)):
                    face = (fx, fy, sc)
            attempt = Attempt(seq, _optional_int(row["main_seq"]), outcome, accepted, loc.block, loc.position, loc.phase,
                              loc.trial, quality, valid, reason, detail, linear, predicted, point, rendered, latency,
                              _number(row["landmarker_ms"], "landmarker_ms"), _number(row["delivery_ms"], "delivery_ms"), face)
        except (ValueError, IndexError) as e:
            s.unassigned_rows += 1
            s.integrity(f"attempts.csv line {line_no}: {e}")
            continue
        seen.add(seq)
        s.attempts.append(attempt)


def _read_events(s: Session, text: str) -> None:
    reader = csv.reader(io.StringIO(text, newline=""))
    header = next(reader, None)
    if header is None or tuple(header) != EVENT_COLUMNS:
        s.integrity("events.csv header differs from log schema v4")
        return
    for line_no, cells in enumerate(reader, start=2):
        if not cells:
            continue
        if len(cells) != len(header) or cells[1] != s.metadata["session_id"]:
            s.integrity(f"events.csv line {line_no}: malformed or foreign row")
            continue
        row = dict(zip(header, cells))
        try:
            s.events.append({"main_seq": int(row["main_seq"]), "uptime_ms": int(row["event_uptime_ms"]),
                             "event": row["event"], "detail": row["detail"]})
        except ValueError as e:
            s.integrity(f"events.csv line {line_no}: {e}")


def _check_integrity(s: Session) -> None:
    meta = s.metadata
    counters = meta.get("counters") if isinstance(meta.get("counters"), dict) else None
    logging = meta.get("logging") if isinstance(meta.get("logging"), dict) else None
    if meta["final_state"] == "complete" and (counters is None or logging is None):
        s.integrity("complete session without finalized counters/logging metadata")
    if logging:
        lost = logging.get("rows_lost_total")
        if not isinstance(lost, int) or lost != 0:
            s.integrity(f"logging loss: rows_lost_total = {lost!r}")
        if logging.get("write_error") is not False:
            s.integrity("logger reported a write error")
        if logging.get("attempt_rows_written") != s.csv_rows:
            s.integrity(f"attempts.csv has {s.csv_rows} rows but metadata says {logging.get('attempt_rows_written')!r} were written")
        if logging.get("event_rows_written") != len(s.events):
            s.integrity(f"events.csv has {len(s.events)} rows but metadata says {logging.get('event_rows_written')!r} were written")
    if counters:
        accepted, unrecorded = counters.get("attempts_accepted"), counters.get("attempts_unrecorded")
        if not isinstance(accepted, int) or not isinstance(unrecorded, int) or accepted != s.csv_rows + unrecorded:
            s.integrity(f"attempt accounting: accepted {accepted!r} != rows {s.csv_rows} + unrecorded {unrecorded!r}")
        if isinstance(accepted, int) and any(not 0 <= a.seq < accepted for a in s.attempts):
            s.integrity("attempt_seq outside the accepted range")
        if counters.get("valid_attempts") != sum(a.valid for a in s.attempts):
            s.integrity("valid attempt count differs between metadata and attempts.csv")
        missing = counters.get("unlogged_results_dropped_in_session")
        if missing != 0:
            s.integrity(f"{missing!r} result(s) accepted during the session were not logged as attempts")
    order = sorted([a.main_seq for a in s.attempts if a.main_seq is not None] + [e["main_seq"] for e in s.events])
    if order != list(range(len(order))):
        s.integrity("main-thread order (main_seq) has gaps or duplicates")
    first = min(s.events, key=lambda e: e["main_seq"], default=None)
    if first is None or (first["event"], first["detail"]) != ("pipeline_reset", "session_start"):
        s.integrity("the session does not start with the session_start pipeline reset")
    end = meta.get("end_uptime_ms")
    if end is not None:
        # Every block that ran must have its block_start reset; one interrupted within its first second may not.
        starts = [b.start for b in s.timeline.blocks if b.start is not None]
        most, least = sum(t <= end for t in starts), sum(t + 1000 <= end for t in starts)
        block_resets = sum(1 for e in s.events if (e["event"], e["detail"]) == ("pipeline_reset", "block_start"))
        if not least <= block_resets <= most:
            s.integrity(f"{block_resets} block_start reset(s) logged for {least}-{most} block(s) that started")


# ---------------------------------------------------------------- per-block metrics (Iteration 3 session code)

def _pair_metric(norm, px):
    return {"norm": norm, "px": px}


def spatial_metrics(points: list[tuple], target: tuple, basis: tuple) -> dict:
    """Target-referenced error, bias, centroid and jitter of one presentation's valid samples."""
    tx, ty = target
    w, h = basis
    dx = [x - tx for x, _ in points]
    dy = [y - ty for _, y in points]
    error = [math.sqrt(a * a + b * b) for a, b in zip(dx, dy)]
    error_px = [math.sqrt((a * w) ** 2 + (b * h) ** 2) for a, b in zip(dx, dy)]
    mx, my = mean([x for x, _ in points]), mean([y for _, y in points])
    spread = [(x - mx, y - my) for x, y in points]
    return {
        "n_samples": len(points),
        "mean_error": _pair_metric(mean(error), mean(error_px)),
        "median_error": _pair_metric(median(error), median(error_px)),
        "max_error": _pair_metric(max(error), max(error_px)),
        "bias_x": _pair_metric(mean(dx), mean(dx) * w),
        "bias_y": _pair_metric(mean(dy), mean(dy) * h),
        "centroid": {"x_norm": mx, "y_norm": my},
        "centroid_error": _pair_metric(math.sqrt((mx - tx) ** 2 + (my - ty) ** 2),
                                       math.sqrt(((mx - tx) * w) ** 2 + ((my - ty) * h) ** 2)),
        "jitter_rms": _pair_metric(math.sqrt(mean([a * a + b * b for a, b in spread])),
                                   math.sqrt(mean([(a * w) ** 2 + (b * h) ** 2 for a, b in spread]))),
        "off_screen_samples": sum(not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0) for x, y in points),
        "_errors": error,
    }


def _count_reasons(attempts) -> dict:
    out: dict[str, int] = {}
    for a in attempts:
        if not a.valid:
            out[a.invalid_reason] = out.get(a.invalid_reason, 0) + 1
    return dict(sorted(out.items()))


def _count_details(attempts) -> dict:
    """The pipeline's own rejection names (feature_rejection, prediction_rejection, detector or eye state) of invalid processed attempts."""
    out: dict[str, int] = {}
    for a in attempts:
        if a.detail is not None:
            key = f"{a.quality}:{a.detail}"
            out[key] = out.get(key, 0) + 1
    return dict(sorted(out.items()))


def _outside_unit_square(p) -> bool:
    return p is not None and not (0.0 <= p[0] <= 1.0 and 0.0 <= p[1] <= 1.0)


def _criterion(spec: dict, value, n, insufficient_reason: str | None) -> dict:
    out = {"id": spec["id"], "value": value, "unit": spec["unit"], "comparison": spec["comparison"],
           "threshold": spec["threshold"], "n": n}
    if insufficient_reason is not None or value is None:
        out.update(status=INSUFFICIENT, reason=insufficient_reason or "no data")
    else:
        # Unrounded comparison against the predeclared threshold.
        ok = value >= spec["threshold"] if spec["comparison"] == ">=" else value <= spec["threshold"]
        out.update(status=MET if ok else NOT_MET, reason=None)
    return out


def _distribution(values) -> dict:
    return {"n": len(values), "mean": mean(values), "median": median(values), "p95": nearest_rank(values, 95),
            "max": max(values) if values else None}


def analyze_block(attempts: list, protocol: Protocol, start: int, completed: int, observed_until: int, basis: tuple) -> dict:
    """The Iteration 3 per-session metrics, applied to one block's 80 s schedule.

    [attempts] are the block's settling- and measurement-phase attempts (objects with trial, phase, valid, point,
    predicted, linear, rendered, latency_ms, landmarker_ms, delivery_ms, invalid_reason, detail, quality, outcome);
    [start] is the block's schedule start, [completed] its fully shown presentations. Preparation and countdown
    attempts must not be passed in. Criteria statuses here never consider session completeness or integrity.
    """
    planned = protocol.planned
    by_trial: dict[int, list] = {i: [] for i in range(planned)}
    for a in attempts:
        by_trial[a.trial].append(a)

    success_rule = protocol.success
    presentations = []
    for trial, case in enumerate(protocol.cases):
        rows = by_trial[trial]
        measurement = [a for a in rows if a.phase == "measurement"]
        settling = [a for a in rows if a.phase == "settling"]
        valid = [a for a in measurement if a.valid]
        complete = trial < completed
        window_start = start + trial * protocol.presentation_ms + protocol.settling_ms
        window_s = (protocol.measurement_ms if complete else
                    min(max(observed_until - window_start, 0), protocol.measurement_ms)) / 1000
        coverage = len(valid) / len(measurement) if measurement else None
        target = (case["target_x_norm"], case["target_y_norm"])
        final = spatial_metrics([a.point for a in valid], target, basis) if valid else None
        predicted = spatial_metrics([a.predicted for a in valid], target, basis) if valid else None
        linear = spatial_metrics([a.linear for a in valid], target, basis) if valid else None
        if not complete:
            eligibility, eligibility_reason = "incomplete_presentation", "not fully shown before the session ended"
        elif not measurement:
            eligibility, eligibility_reason = "insufficient_data", "no measurement-phase attempts"
        elif len(valid) < protocol.min_valid_samples:
            eligibility, eligibility_reason = "insufficient_data", f"{len(valid)} valid samples < {protocol.min_valid_samples}"
        elif coverage < protocol.min_valid_coverage:
            eligibility, eligibility_reason = "insufficient_data", f"coverage {coverage!r} < {protocol.min_valid_coverage}"
        else:
            eligibility, eligibility_reason = "eligible", None
        failures = []
        if not complete:
            failures.append("incomplete_presentation")
        if not measurement:
            failures.append("no_measurement_attempts")
        if len(valid) < success_rule["min_valid_samples"]:
            failures.append(f"valid_samples {len(valid)} < {success_rule['min_valid_samples']}")
        if coverage is not None and coverage < success_rule["min_valid_coverage"]:
            failures.append(f"valid_coverage {coverage!r} < {success_rule['min_valid_coverage']}")
        if final is None:
            failures.append("no_valid_samples")
        elif final["mean_error"]["norm"] > success_rule["max_mean_error_norm"]:
            failures.append(f"mean_error {final['mean_error']['norm']!r} > {success_rule['max_mean_error_norm']}")
        presentations.append({
            "trial_index": trial, "case_suffix": case["case_suffix"], "pass_number": case["pass_number"],
            "index_in_pass": case["index_in_pass"], "target_id": case["target_id"],
            "target_x_norm": target[0], "target_y_norm": target[1],
            "presentation_complete": complete, "measurement_window_s": window_s,
            "measurement_attempts": len(measurement), "valid_samples": len(valid), "valid_coverage": coverage,
            "measurement_invalid_by_reason": _count_reasons(measurement),
            "measurement_invalid_by_detail": _count_details(measurement),
            "settling_attempts": len(settling), "settling_valid": sum(a.valid for a in settling),
            "eligibility": eligibility, "eligibility_reason": eligibility_reason,
            "successful": not failures, "success_failures": failures,
            "final": final, "predicted_diagnostic": predicted, "linear_diagnostic": linear,
            "model_clamp_active_samples": sum(_outside_unit_square(a.linear) for a in valid),
            "pointer_shown_samples": sum(a.rendered is not None for a in valid),
        })

    eligible = [p for p in presentations if p["eligibility"] == "eligible"]
    measurement_all = [a for a in attempts if a.phase == "measurement"]
    valid_all = [a for a in measurement_all if a.valid]
    window_total = sum(p["measurement_window_s"] for p in presentations)
    latencies = [a.latency_ms for a in attempts if a.latency_ms is not None]
    availability = len(valid_all) / len(measurement_all) if measurement_all else None
    valid_rate = len(valid_all) / window_total if window_total > 0 else None
    min_eligible = protocol.min_eligible_presentations
    few = (f"{len(eligible)} technically eligible presentations < {min_eligible} required"
           if len(eligible) < min_eligible else None)
    successful = sum(p["successful"] for p in presentations)
    spec = {c["id"]: c for c in protocol.criteria}
    criteria = [
        _criterion(spec["successful_presentation_rate"], successful / planned, planned, None),
        _criterion(spec["p95_presentation_error"],
                   nearest_rank([p["final"]["mean_error"]["norm"] for p in eligible], 95), len(eligible), few),
        _criterion(spec["mean_jitter_rms"], mean([p["final"]["jitter_rms"]["norm"] for p in eligible]), len(eligible), few),
        _criterion(spec["tracking_availability"], availability, len(measurement_all),
                   None if measurement_all else "no measurement-phase attempts"),
        _criterion(spec["valid_sample_rate"], valid_rate if measurement_all else 0.0, len(valid_all),
                   None if measurement_all else "no measurement-phase attempts"),
        _criterion(spec["p95_processing_latency"], nearest_rank(latencies, 95), len(latencies),
                   None if latencies else "no attempt with a measured latency"),
    ]

    def balanced(key, stream="final"):
        blocks = [p[stream] for p in eligible if p[stream]]
        return _pair_metric(mean([b[key]["norm"] for b in blocks]), mean([b[key]["px"] for b in blocks])) if blocks else None

    pooled_errors = [e for p in presentations if p["final"] for e in p["final"]["_errors"]]
    return {
        "criteria": criteria,
        "presentations": presentations,
        "counts": {
            "attempts": len(attempts), "attempts_by_outcome": {o: sum(a.outcome == o for a in attempts) for o in OUTCOMES},
            "measurement_attempts": len(measurement_all), "valid_measurement_samples": len(valid_all),
            "measurement_invalid_by_reason": _count_reasons(measurement_all),
            "measurement_invalid_by_detail": _count_details(measurement_all),
            "settling_attempts": sum(p["settling_attempts"] for p in presentations),
            "settling_invalid_by_reason": _count_reasons([a for a in attempts if a.phase == "settling"]),
            "measurement_window_s": window_total,
        },
        "diagnostics": {
            "presentations_successful": successful,
            "presentations_eligible": len(eligible), "min_eligible_for_spatial_criteria": min_eligible,
            "block_mean_error": balanced("mean_error"),
            "median_of_presentation_mean_errors": median([p["final"]["mean_error"]["norm"] for p in eligible]),
            "bias_x_mean": balanced("bias_x"), "bias_y_mean": balanced("bias_y"),
            "centroid_error_mean": balanced("centroid_error"), "jitter_rms_mean": balanced("jitter_rms"),
            "off_screen_fraction": (sum(_outside_unit_square(a.point) for a in valid_all) / len(valid_all)) if valid_all else None,
            "off_screen_fraction_note": "zero by construction: the measured coordinate inherits GazeModel's [0, 1] clamp; "
                                        "see model_clamp_active_fraction for overshoot",
            "model_clamp_active_fraction": (sum(_outside_unit_square(a.linear) for a in valid_all) / len(valid_all)) if valid_all else None,
            "model_clamp_active_samples": sum(p["model_clamp_active_samples"] for p in presentations),
            "predicted_stream_mean_error": balanced("mean_error", "predicted_diagnostic"),
            "linear_stream_mean_error": balanced("mean_error", "linear_diagnostic"),
            "pointer_shown_samples": sum(p["pointer_shown_samples"] for p in presentations),
            "pooled_sample_errors": {**_distribution(pooled_errors),
                                     "note": "sensitivity view; every valid measurement sample weighted equally"},
            "latency_ms": {**_distribution(latencies),
                           "attempts_by_outcome": {o: sum(1 for a in attempts if a.outcome == o and a.latency_ms is not None)
                                                   for o in OUTCOMES}},
            "landmarker_ms": {**_distribution([a.landmarker_ms for a in attempts if a.landmarker_ms is not None]),
                              "note": "FaceLandmarker inference only; not end-to-end latency"},
            "delivery_ms": _distribution([a.delivery_ms for a in attempts if a.delivery_ms is not None]),
        },
        "_eligible_means": [p["final"]["mean_error"]["norm"] for p in eligible],
        "_eligible_jitter": [p["final"]["jitter_rms"]["norm"] for p in eligible],
    }


def _verdict(statuses: list[str], complete: bool, integrity: int, what: str, end_reason: str) -> tuple[str, str]:
    if not complete:
        return INCOMPLETE, f"session ended before completion ({end_reason})"
    if integrity:
        return INSUFFICIENT, f"{integrity} data-integrity problem(s)"
    if NOT_MET in statuses:
        return NOT_MET, f"not met: {what}"
    if INSUFFICIENT in statuses:
        return INSUFFICIENT, f"insufficient: {what}"
    return MET, "all criteria met"


def _face_summary(attempts) -> dict:
    """Logged face position and scale (inspection only; nothing is gated on them)."""
    faces = [a.face for a in attempts if a.face is not None]

    def describe(i):
        values = sorted(f[i] for f in faces)
        if not values:
            return None
        q = statistics.quantiles(values, n=4, method="inclusive") if len(values) > 1 else [values[0]] * 3
        return {"median": statistics.median(values), "q1": q[0], "q3": q[2], "min": values[0], "max": values[-1]}

    return {"attempts_with_features": len(faces), "face_x": describe(0), "face_y": describe(1), "scale": describe(2)}


def analyze_session(s: Session, protocol: Protocol) -> dict:
    meta = s.metadata
    display = meta["display"]
    basis = (display["width_px"], display["height_px"])
    end = meta.get("end_uptime_ms")
    last_accepted = max((a.accepted_ms for a in s.attempts), default=meta["start_uptime_ms"])
    complete_session = meta["final_state"] == "complete"
    blocks_meta = meta["blocks"]
    blocks = []
    for b in s.timeline.blocks:
        recorded = blocks_meta[b.index].get("presentations_completed")
        if b.start is None:
            completed, observed_until = 0, b.start or 0
        else:
            observed_until = min(end if end is not None else last_accepted, b.planned_end)
            if isinstance(recorded, int) and not isinstance(recorded, bool) and 0 <= recorded <= protocol.planned:
                completed = recorded
            else:
                # Interrupted before finalization: only presentations a later attempt proves finished count.
                completed = max(0, min(protocol.planned, (observed_until - b.start) // protocol.presentation_ms))
        in_block = [a for a in s.attempts if a.block == b.index and a.phase in BLOCK_PHASES]
        result = analyze_block(in_block, protocol, b.start or 0, completed, observed_until, basis)
        statuses = [c["status"] for c in result["criteria"]]
        verdict, reason = _verdict(statuses, complete_session, s.integrity_error_count,
                                   ", ".join(c["id"] for c in result["criteria"] if c["status"] in (NOT_MET, INSUFFICIENT)),
                                   meta["end_reason"])
        if not complete_session:
            for c in result["criteria"]:
                c.update(status=INCOMPLETE, reason=f"session incomplete ({meta['end_reason']}); value is partial", partial=True)
        for p in result["presentations"]:
            p["case_id"] = f"{b.position}-{p['case_suffix']}"
        prep = [a for a in s.attempts if a.block == b.index and a.phase == "preparation"]
        countdown = [a for a in s.attempts if a.block == b.index and a.phase == "countdown"]
        measurement_valid = [a for a in in_block if a.phase == "measurement" and a.valid]
        bm = blocks_meta[b.index]
        blocks.append({
            "block_index": b.index + 1, "head_position": b.position,
            "state": bm.get("state"), "presentations_completed": completed,
            "preparation_start_uptime_ms": b.preparation_start, "ready_uptime_ms": b.ready,
            "block_start_uptime_ms": b.start, "planned_block_end_uptime_ms": b.planned_end,
            "block_reset_uptime_ms": bm.get("block_reset_uptime_ms"),
            "preparation_ms": None if b.ready is None or b.preparation_start is None else b.ready - b.preparation_start,
            "verdict": verdict, "verdict_reason": reason, **result,
            "excluded_phases": {
                "note": "preparation and countdown attempts are excluded from every block metric and denominator, including latency",
                "preparation_attempts": len(prep), "preparation_valid": sum(a.valid for a in prep),
                "countdown_attempts": len(countdown), "countdown_valid": sum(a.valid for a in countdown),
                "countdown_by_outcome": {o: sum(a.outcome == o for a in countdown) for o in OUTCOMES if any(a.outcome == o for a in countdown)},
            },
            "face_inspection": {
                "note": "instructed head position; logged face_x/face_y/scale for inspection only, never a gate",
                "preparation": _face_summary(prep), "countdown": _face_summary(countdown),
                "measurement_valid": _face_summary(measurement_valid),
            },
        })

    block_verdicts = [b["verdict"] for b in blocks]
    if not complete_session:
        verdict, verdict_reason = INCOMPLETE, f"session ended before completion ({meta['end_reason']})"
    elif s.integrity_error_count:
        verdict, verdict_reason = INSUFFICIENT, f"{s.integrity_error_count} data-integrity problem(s)"
    elif NOT_MET in block_verdicts:
        verdict = NOT_MET
        verdict_reason = "not met in: " + ", ".join(b["head_position"] for b in blocks if b["verdict"] == NOT_MET)
    elif INSUFFICIENT in block_verdicts:
        verdict = INSUFFICIENT
        verdict_reason = "insufficient in: " + ", ".join(b["head_position"] for b in blocks if b["verdict"] == INSUFFICIENT)
    else:
        verdict, verdict_reason = MET, "all three blocks met every criterion"

    resets: dict[str, int] = {}
    other_events: dict[str, int] = {}
    for e in s.events:
        if e["event"] == "pipeline_reset":
            resets[e["detail"]] = resets.get(e["detail"], 0) + 1
        else:
            other_events[e["event"]] = other_events.get(e["event"], 0) + 1
    counters = meta.get("counters") if isinstance(meta.get("counters"), dict) else {}
    build, device = meta.get("build") or {}, meta.get("device") or {}
    calibration = meta.get("calibration") or {}
    cid = calibration_id_of(meta)
    official = []
    if not complete_session:
        official.append("incomplete session")
    if s.integrity_error_count:
        official.append("data-integrity problems")
    if s.checksums != "verified":
        official.append(f"device checksums {s.checksums}")
    if meta["mode"] != PRIMARY_MODE:
        official.append("not the primary blind_measurement mode")
    if build.get("git_dirty_at_build") != "false":
        official.append(f"build dirty flag is {build.get('git_dirty_at_build')!r}")
    if not SHA1_HEX.match(str(build.get("git_commit"))):
        official.append(f"build commit {build.get('git_commit')!r} is not a Git commit")
    if build.get("source_base_is_ancestor") != "true":
        official.append(f"build does not descend from the experiment source base ({build.get('source_base_is_ancestor')!r})")
    if (calibration.get("verified_snapshot_refit") is not True or calibration.get("evaluation_sessions_on_this_calibration_before") != 0
            or calibration.get("anchors") != 27):
        official.append("calibration is not a fresh, verified 27-anchor calibration of the candidate")
    key = (meta["participant_id"], meta["session_label"])
    if meta["participant_id"].upper().startswith(PILOT_PREFIX):
        official.append("technical pilot (PILOT* participant); never official")
    elif key not in protocol.plan_official:
        official.append(f"{key[0]} / {key[1]} is not in the collection plan")
    else:
        planned = protocol.plan_official[key]
        if planned["calibration_id"] != cid or planned["block_order"] != meta["protocol"]["block_order"]:
            official.append(f"differs from the collection plan ({planned['calibration_id']}, order {planned['block_order']})")
    snapshot_parameters = s.snapshot.get("parameters") if isinstance(s.snapshot.get("parameters"), dict) else {}
    return {
        "session_id": meta["session_id"], "participant_id": meta["participant_id"], "session_label": meta["session_label"],
        "calibration_id": cid, "block_order": meta["protocol"]["block_order"],
        "path": s.label, "mode": meta["mode"], "final_state": meta["final_state"], "end_reason": meta["end_reason"],
        "start_wall_time_utc": meta.get("start_wall_time_utc"),
        "build": build, "app": meta.get("app"), "libraries": meta.get("libraries"), "device": device,
        "display": {"width_px": basis[0], "height_px": basis[1], "rotation": display.get("rotation"), "basis": display.get("basis")},
        "pipeline": meta.get("pipeline"),
        "calibration": {k: calibration.get(k) for k in ("calibration_id", "calibration_serial", "duration_ms", "movement_retries",
                                                        "age_ms_at_session_start", "anchors", "fit_error", "calibration_inputs_sha256")},
        "calibration_note": "fit_error is a calibration diagnostic, not a measured gaze accuracy",
        "calibration_reference_face": {k: snapshot_parameters.get(k) for k in ("reference_face_x", "reference_face_y", "reference_scale")},
        "conditions": meta.get("conditions"),
        "compatibility_group": _group_label(meta),
        "checksums": s.checksums,
        "integrity_error_count": s.integrity_error_count, "integrity_errors": s.integrity_errors,
        "issue_count": s.issue_count, "issues": s.issues, "unassigned_rows": s.unassigned_rows,
        "blocks_completed": sum(b["presentations_completed"] == protocol.planned for b in blocks),
        "verdict": verdict, "verdict_reason": verdict_reason,
        "verdict_rule": "MET only if all three head-position blocks are MET",
        "official": not official, "official_exclusions": official,
        "blocks": blocks,
        "counts": {
            "attempts": len(s.attempts),
            "attempts_by_phase": {ph: sum(a.phase == ph for a in s.attempts) for ph in PHASES},
            "attempts_by_outcome": {o: sum(a.outcome == o for a in s.attempts) for o in OUTCOMES},
            "frames_received": counters.get("frames_received"),
            "frames_cadence_skipped": counters.get("frames_cadence_skipped"),
            "attempts_unrecorded": counters.get("attempts_unrecorded"),
            "unlogged_results_dropped_pre_session": counters.get("unlogged_results_dropped_pre_session"),
            "unlogged_results_dropped_in_session": counters.get("unlogged_results_dropped_in_session"),
            "rows_lost_total": (meta.get("logging") or {}).get("rows_lost_total"),
            "pipeline_resets_by_reason": dict(sorted(resets.items())),
            "other_events": dict(sorted(other_events.items())),
        },
    }


def _group_key(meta: dict) -> tuple:
    build, predictor, device, display = (meta.get("build") or {}), (meta.get("predictor") or {}), \
        (meta.get("device") or {}), meta["display"]
    return (calibration_id_of(meta), meta["mode"], meta["protocol"]["sha256"], str(build.get("git_commit")),
            str(build.get("git_dirty_at_build")), f"{meta['pipeline']['variant']}:{predictor.get('kind')}",
            f"{device.get('manufacturer')} {device.get('model')}",
            f"{display['width_px']}x{display['height_px']}", str(display.get("rotation")))


def _group_label(meta: dict) -> str:
    cid, mode, _, commit, dirty, predictor, device, size, rotation = _group_key(meta)
    return (f"{cid} | {mode} | build {commit[:12]}{'' if dirty == 'false' else ' (dirty=' + dirty + ')'} | {predictor} | "
            f"{device} | {size} rotation {rotation}")


# ---------------------------------------------------------------- aggregation

def _criterion_value(block, cid):
    return next(c for c in block["criteria"] if c["id"] == cid)["value"]


def _position_summary(position: str, blocks: list[dict], protocol: Protocol) -> dict:
    """Blocks of one head position from complete sessions: presentations or blocks equally weighted, never averaged p95."""
    verdicts = [b["verdict"] for b in blocks]
    if not blocks:
        verdict = INCOMPLETE
    elif NOT_MET in verdicts:
        verdict = NOT_MET
    elif all(v == MET for v in verdicts):
        verdict = MET
    else:
        verdict = INSUFFICIENT
    presentations = [p for b in blocks for p in b["presentations"]]
    means = [m for b in blocks for m in b["_eligible_means"]]
    jitter = [j for b in blocks for j in b["_eligible_jitter"]]
    latency = [v for v in (_criterion_value(b, "p95_processing_latency") for b in blocks) if v is not None]
    return {
        "head_position": position, "blocks": len(blocks), "block_verdicts": verdicts, "verdict": verdict,
        "verdict_rule": "MET only if every included block of this position is MET; NOT_MET if any is NOT_MET",
        "descriptive": {
            "successful_presentation_rate": (sum(p["successful"] for p in presentations) / len(presentations)) if presentations else None,
            "p95_presentation_error_pooled": nearest_rank(means, 95),
            "mean_presentation_error_pooled": mean(means),
            "mean_jitter_rms_pooled": mean(jitter),
            "eligible_presentations_pooled": len(means),
            "tracking_availability_mean_of_blocks": mean([v for v in (_criterion_value(b, "tracking_availability") for b in blocks) if v is not None]),
            "valid_sample_rate_mean_of_blocks": mean([v for v in (_criterion_value(b, "valid_sample_rate") for b in blocks) if v is not None]),
            "p95_processing_latency_worst_block": max(latency) if latency else None,
            "weighting": "presentations equally (rate, pooled p95, jitter); blocks equally (availability, rate); "
                         "latency is the worst block p95, never an averaged p95",
        },
    }


def aggregate_participant(pid: str, sessions: list[dict], protocol: Protocol) -> dict:
    complete = [s for s in sessions if s["final_state"] == "complete"]
    verdicts = [s["verdict"] for s in complete]
    if not complete:
        verdict = INCOMPLETE
    elif NOT_MET in verdicts:
        verdict = NOT_MET
    elif all(v == MET for v in verdicts):
        verdict = MET
    else:
        verdict = INSUFFICIENT
    return {
        "participant_id": pid,
        "complete_sessions": [s["session_id"] for s in complete],
        "incomplete_sessions": [s["session_id"] for s in sessions if s["final_state"] != "complete"],
        "official_sessions": [s["session_id"] for s in complete if s["official"]],
        "session_verdicts": {s["session_id"]: s["verdict"] for s in complete},
        "verdict": verdict,
        "verdict_rule": "MET only if every complete session in this group is MET; NOT_MET if any is NOT_MET.",
        "by_position": [_position_summary(pos, [b for s in complete for b in s["blocks"] if b["head_position"] == pos], protocol)
                        for pos in protocol.positions],
    }


def build_groups(sessions: list[dict], metas: dict, protocol: Protocol) -> list[dict]:
    groups: dict[tuple, list[dict]] = {}
    for s in sessions:
        groups.setdefault(_group_key(metas[s["session_id"]]), []).append(s)
    out = []
    for key in sorted(groups):
        members = groups[key]
        participants = sorted({s["participant_id"] for s in members})
        aggregated = [aggregate_participant(pid, [s for s in members if s["participant_id"] == pid], protocol)
                      for pid in participants]
        out.append({
            "group": members[0]["compatibility_group"],
            "calibration_id": key[0], "mode": key[1],
            "role": "primary" if key[1] == PRIMARY_MODE else "secondary; a visible pointer can change where the participant looks",
            "key": dict(zip(("calibration_id", "mode", "protocol_sha256", "git_commit", "git_dirty_at_build", "predictor",
                             "device", "display_px", "rotation"), key)),
            "sessions": [s["session_id"] for s in members],
            "participants": aggregated,
        })
    return out


def _mark_shared_calibrations(sessions: list[dict]) -> None:
    """A calibration may serve one session only; reuse is flagged on every session that shares it."""
    seen: dict[tuple, list[dict]] = {}
    for s in sessions:
        sha = s["calibration"].get("calibration_inputs_sha256")
        if sha:
            seen.setdefault((s["participant_id"], sha), []).append(s)
    for shared in seen.values():
        if len(shared) > 1:
            for s in shared:
                others = ", ".join(o["session_id"] for o in shared if o is not s)
                s["official_exclusions"].append(f"calibration shared with {others}")
                s["official"] = False


# ---------------------------------------------------------------- discovery and run

def discover(root: Path) -> list[Path]:
    """A session is any directory holding metadata.json or attempts.csv; the search stops there."""
    if (root / "metadata.json").exists() or (root / "attempts.csv").exists():
        return [root]
    found = []
    for child in sorted(p for p in root.iterdir() if p.is_dir()):
        found.extend(discover(child))
    return found


def select_sessions(paths: list[Path], selection_path: Path) -> tuple[list[Path], dict]:
    """Keeps only the sessions whose metadata session_id is listed under "included" in an analysis-input manifest.

    Selection is by unique session_id, never by label or folder name. Every listed session must be found exactly once.
    """
    try:
        manifest = json.loads(selection_path.read_text(encoding="utf-8"))
        wanted = [entry["session_id"] for entry in manifest["included"]]
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as e:
        raise SystemExit(f"error: selection manifest {selection_path} unusable: {e}") from e
    if len(set(wanted)) != len(wanted):
        raise SystemExit(f"error: selection manifest {selection_path} lists a session_id twice")
    found: dict[str, list[Path]] = {}
    for path in paths:
        try:
            sid = json.loads((path / "metadata.json").read_text(encoding="utf-8")).get("session_id")
        except (OSError, UnicodeDecodeError, json.JSONDecodeError, AttributeError):
            continue
        if sid in wanted:
            found.setdefault(sid, []).append(path)
    problems = [f"{sid} not found" for sid in wanted if sid not in found]
    problems += [f"{sid} found {len(p)} times" for sid, p in found.items() if len(p) != 1]
    if problems:
        raise SystemExit(f"error: selection {selection_path.name}: " + "; ".join(problems))
    info = {"manifest": selection_path.name, "manifest_sha256": hashlib.sha256(selection_path.read_bytes()).hexdigest(),
            "included_session_ids": wanted, "excluded_by_selection": len(paths) - len(wanted)}
    return [found[sid][0] for sid in wanted], info


def _strip_private(value):
    if isinstance(value, dict):
        return {k: _strip_private(v) for k, v in value.items() if not k.startswith("_")}
    if isinstance(value, list):
        return [_strip_private(v) for v in value]
    return value


def _write(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def run(input_path: Path, out_dir: Path, protocol_path: Path, source_label: str | None,
        selection: Path | None = None) -> dict:
    protocol = load_protocol(protocol_path)
    root = input_path.resolve()
    paths = discover(root)
    selection_info = None
    if selection is not None:
        paths, selection_info = select_sessions(paths, selection)
    resolved_out = out_dir.resolve()
    if resolved_out == root or root in resolved_out.parents:
        raise SystemExit(f"error: --out {out_dir} is inside the input data {input_path}; raw data is never written to")
    for path in paths:
        if resolved_out == path or path in resolved_out.parents:
            raise SystemExit(f"error: --out {out_dir} is inside session data {path}")
    sessions, rejected, metas = [], [], {}
    for path in paths:
        label = path.relative_to(root).as_posix() if path != root else path.name
        try:
            loaded = load_session(path, root, protocol)
            sid = loaded.metadata["session_id"]
            if sid in metas:
                raise SessionError(f"duplicate session_id {sid} (already loaded from another folder)")
            metas[sid] = loaded.metadata
            sessions.append(analyze_session(loaded, protocol))
        except (SessionError, OSError, UnicodeDecodeError) as e:
            rejected.append({"path": label, "error": str(e)})
    _mark_shared_calibrations(sessions)
    sessions.sort(key=lambda s: (s["calibration_id"], s["participant_id"], s["start_wall_time_utc"] or "", s["session_id"]))
    summary = {
        "tool": "analyze_calibration_eval", "tool_version": TOOL_VERSION,
        "supported_schema_versions": list(SUPPORTED_SCHEMA_VERSIONS),
        "scope": "metrics only; no figures",
        "pipeline_variant": PIPELINE_VARIANT,
        "measured_coordinate": "smoothed_x_norm/smoothed_y_norm: GazePipeline's smoothed output, which inherits "
                               "GazeModel's [0, 1] clamp (not an unclamped raw output)",
        **({"input_selection": selection_info} if selection_info is not None else {}),
        "protocol": {"id": protocol.protocol_id, "version": protocol.version, "sha256": protocol.sha256,
                     "manifest": protocol.path.name},
        "source_label": source_label,
        "reference_note": "Targets are instructed fixation points and head positions are instructed positions; neither is "
                          "independently measured.",
        "thresholds": {"successful_presentation": protocol.success,
                       "block_criteria": [{k: c[k] for k in ("id", "comparison", "threshold", "unit")} for c in protocol.criteria],
                       "data_sufficiency": {"min_valid_samples": protocol.min_valid_samples,
                                            "min_valid_coverage": protocol.min_valid_coverage,
                                            "min_eligible_presentations_per_block": protocol.min_eligible_presentations}},
        "groups": build_groups(sessions, metas, protocol),
        "incomplete_sessions": [{"calibration_id": s["calibration_id"], "participant_id": s["participant_id"],
                                 "session_id": s["session_id"], "end_reason": s["end_reason"], "path": s["path"]}
                                for s in sessions if s["final_state"] != "complete"],
        "rejected_sessions": rejected,
        "sessions": sessions,
    }
    public = _strip_private(summary)
    out_dir.mkdir(parents=True, exist_ok=True)
    assert "README.md" not in OUTPUT_FILES
    _write(out_dir / "summary.json", json.dumps(public, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    _write(out_dir / "sessions.csv", sessions_csv(public["sessions"]))
    _write(out_dir / "blocks.csv", blocks_csv(public["sessions"]))
    _write(out_dir / "presentations.csv", presentations_csv(public["sessions"]))
    _write(out_dir / "report.md", markdown_report(public))
    return public


# ---------------------------------------------------------------- tabular output

def _cell(v) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return str(v).lower()
    if isinstance(v, float):
        return repr(v)
    return str(v)


def _csv(header: list[str], rows: list[list]) -> str:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(header)
    for r in rows:
        w.writerow([_cell(v) for v in r])
    return buf.getvalue()


def sessions_csv(sessions: list[dict]) -> str:
    header = ["calibration_id", "participant_id", "session_label", "session_id", "block_order", "mode", "compatibility_group",
              "final_state", "end_reason", "git_commit", "git_dirty_at_build", "official", "verdict", "blocks_completed"]
    header += [f"block{k}_{f}" for k in (1, 2, 3) for f in ("head_position", "verdict")]
    header += ["calibration_duration_ms", "calibration_movement_retries", "calibration_age_ms_at_session_start",
               "calibration_fit_error_diagnostic", "integrity_error_count", "checksums"]
    rows = []
    for s in sessions:
        row = [s["calibration_id"], s["participant_id"], s["session_label"], s["session_id"], s["block_order"], s["mode"],
               s["compatibility_group"], s["final_state"], s["end_reason"], s["build"].get("git_commit"),
               s["build"].get("git_dirty_at_build"), s["official"], s["verdict"], s["blocks_completed"]]
        for b in s["blocks"]:
            row += [b["head_position"], b["verdict"]]
        c = s["calibration"]
        row += [c.get("duration_ms"), c.get("movement_retries"), c.get("age_ms_at_session_start"), c.get("fit_error"),
                s["integrity_error_count"], s["checksums"]]
        rows.append(row)
    return _csv(header, rows)


def blocks_csv(sessions: list[dict]) -> str:
    header = ["calibration_id", "participant_id", "session_id", "session_final_state", "block_index", "head_position",
              "block_state", "presentations_completed", "preparation_ms", "verdict"]
    for cid in CRITERIA_IDS:
        header += [f"{cid}_value", f"{cid}_status"]
    header += ["presentations_successful", "presentations_eligible", "block_mean_error_norm", "block_mean_error_px",
               "bias_x_norm", "bias_y_norm", "model_clamp_active_fraction", "measurement_attempts", "valid_measurement_samples",
               "preparation_attempts", "countdown_attempts", "measurement_face_y_median", "measurement_scale_median"]
    rows = []
    for s in sessions:
        for b in s["blocks"]:
            d, c = b["diagnostics"], {x["id"]: x for x in b["criteria"]}
            face = b["face_inspection"]["measurement_valid"]
            row = [s["calibration_id"], s["participant_id"], s["session_id"], s["final_state"], b["block_index"],
                   b["head_position"], b["state"], b["presentations_completed"], b["preparation_ms"], b["verdict"]]
            for cid in CRITERIA_IDS:
                row += [c[cid]["value"], c[cid]["status"]]
            mean_error = d["block_mean_error"] or {}
            row += [d["presentations_successful"], d["presentations_eligible"], mean_error.get("norm"), mean_error.get("px"),
                    (d["bias_x_mean"] or {}).get("norm"), (d["bias_y_mean"] or {}).get("norm"), d["model_clamp_active_fraction"],
                    b["counts"]["measurement_attempts"], b["counts"]["valid_measurement_samples"],
                    b["excluded_phases"]["preparation_attempts"], b["excluded_phases"]["countdown_attempts"],
                    (face["face_y"] or {}).get("median"), (face["scale"] or {}).get("median")]
            rows.append(row)
    return _csv(header, rows)


def presentations_csv(sessions: list[dict]) -> str:
    metrics = ("mean_error", "median_error", "max_error", "bias_x", "bias_y", "centroid_error", "jitter_rms")
    header = ["calibration_id", "participant_id", "session_id", "mode", "session_final_state", "block_index", "head_position",
              "trial_index", "case_id", "pass_number", "index_in_pass", "target_id", "target_x_norm", "target_y_norm",
              "presentation_complete", "measurement_attempts", "valid_samples", "valid_coverage", "measurement_window_s",
              "eligibility", "successful", "success_failures", "off_screen_samples", "model_clamp_active_samples",
              "pointer_shown_samples", "settling_attempts", "settling_valid", "measurement_invalid_by_reason",
              "measurement_invalid_by_detail"]
    header += [f"final_{m}_{u}" for m in metrics for u in ("norm", "px")]
    header += ["predicted_mean_error_norm", "linear_mean_error_norm"]
    rows = []
    for s in sessions:
        for b in s["blocks"]:
            for p in b["presentations"]:
                f = p["final"] or {}
                row = [s["calibration_id"], s["participant_id"], s["session_id"], s["mode"], s["final_state"],
                       b["block_index"], b["head_position"], p["trial_index"], p["case_id"], p["pass_number"],
                       p["index_in_pass"], p["target_id"], p["target_x_norm"], p["target_y_norm"],
                       p["presentation_complete"], p["measurement_attempts"], p["valid_samples"], p["valid_coverage"],
                       p["measurement_window_s"], p["eligibility"], p["successful"], "; ".join(p["success_failures"]),
                       f.get("off_screen_samples"), p["model_clamp_active_samples"], p["pointer_shown_samples"],
                       p["settling_attempts"], p["settling_valid"],
                       "; ".join(f"{k}={v}" for k, v in p["measurement_invalid_by_reason"].items()),
                       "; ".join(f"{k}={v}" for k, v in p["measurement_invalid_by_detail"].items())]
                row += [(f.get(m) or {}).get(u) for m in metrics for u in ("norm", "px")]
                row += [((p[s_] or {}).get("mean_error") or {}).get("norm") for s_ in ("predicted_diagnostic", "linear_diagnostic")]
                rows.append(row)
    return _csv(header, rows)


# ---------------------------------------------------------------- Markdown report

def _f(v, digits=4) -> str:
    return "—" if v is None else f"{v:.{digits}f}"


def _criterion_cell(c: dict) -> str:
    digits = 1 if c["id"] in ("valid_sample_rate", "p95_processing_latency") else 4
    return f"{_f(c['value'], digits)} {c['status']}"


def markdown_report(public: dict) -> str:
    p = public["protocol"]
    out = ["# NoTouch calibration comparison: generated metrics report", "",
           f"Generated by `analyze_calibration_eval` {public['tool_version']} (metrics only, no figures). Do not edit by hand; "
           "rerun the analyzer instead. The human-maintained documentation is `docs/calibration-comparison/README.md`.", "",
           f"- Protocol: `{p['id']}` {p['version']}, manifest SHA-256 `{p['sha256']}`",
           f"- Pipeline: `{public['pipeline_variant']}`. Measured coordinate: {public['measured_coordinate']}.",
           f"- Source label: {public['source_label'] or 'not supplied'}",
           f"- Sessions analyzed: {len(public['sessions'])}; incomplete: {len(public['incomplete_sessions'])}; "
           f"rejected: {len(public['rejected_sessions'])}",
           "- Every criterion is evaluated per head-position block (80 s, 32 presentations); a session is MET only if all "
           "three blocks are MET. Preparation and countdown attempts are excluded from every block metric.",
           "- Targets are instructed fixation points and head positions are instructed positions; spatial values are "
           "target-referenced gaze errors in full-display normalized units (px uses each session's display size).",
           "- Statuses: MET, NOT_MET, NOT_MEASURED, INSUFFICIENT_DATA, INCOMPLETE. Thresholds are compared unrounded; "
           "table values are rounded for display only.", ""]
    th = public["thresholds"]
    out += ["## Predeclared criteria (per block)", "", "| Criterion | Rule |", "|---|---|",
            f"| Successful presentation | mean final error ≤ {th['successful_presentation']['max_mean_error_norm']}, "
            f"coverage ≥ {th['successful_presentation']['min_valid_coverage']}, "
            f"≥ {th['successful_presentation']['min_valid_samples']} valid samples |"]
    out += [f"| `{c['id']}` | {c['comparison']} {c['threshold']} ({c['unit']}) |" for c in th["block_criteria"]]
    out.append("")
    if not public["sessions"]:
        out += ["## Results", "", "No sessions found. Nothing has been measured (NOT_MEASURED).", ""]
    for g in public["groups"]:
        out += [f"## Group: {g['group']}", "", f"Candidate `{g['calibration_id']}`. Role: {g['role']}.", ""]
        for a in g["participants"]:
            out += [f"### Participant {a['participant_id']}: {a['verdict']}", "",
                    "| Head position | Blocks | Verdict | Success rate | Pooled p95 error | Mean jitter | Availability (mean) "
                    "| Valid/s (mean) | Worst p95 latency ms |", "|---|---|---|---|---|---|---|---|---|"]
            for pos in a["by_position"]:
                d = pos["descriptive"]
                out.append(f"| {pos['head_position']} | {pos['blocks']} | {pos['verdict']} | {_f(d['successful_presentation_rate'])} | "
                           f"{_f(d['p95_presentation_error_pooled'])} | {_f(d['mean_jitter_rms_pooled'])} | "
                           f"{_f(d['tracking_availability_mean_of_blocks'])} | {_f(d['valid_sample_rate_mean_of_blocks'], 1)} | "
                           f"{_f(d['p95_processing_latency_worst_block'], 1)} |")
            out.append("")
        out += ["### Blocks", "",
                "| Session | Official | Session verdict | Block | Position | Block verdict | " + " | ".join(f"`{c}`" for c in CRITERIA_IDS) + " |",
                "|---|---|---|---|---|---|" + "---|" * len(CRITERIA_IDS)]
        for s in public["sessions"]:
            if s["session_id"] not in g["sessions"]:
                continue
            for b in s["blocks"]:
                crit = {c["id"]: c for c in b["criteria"]}
                out.append(f"| {s['participant_id']} {s['session_label']} `{s['session_id']}` | {'yes' if s['official'] else 'no'} | "
                           f"{s['verdict']} | {b['block_index']} | {b['head_position']} | {b['verdict']} | "
                           + " | ".join(_criterion_cell(crit[c]) for c in CRITERIA_IDS) + " |")
        out.append("")
        for s in public["sessions"]:
            if s["session_id"] in g["sessions"] and not s["official"]:
                out.append(f"- `{s['session_id']}` not official: {'; '.join(s['official_exclusions'])}")
        out.append("")
    if public["incomplete_sessions"]:
        out += ["## Incomplete sessions", "", "Listed, never given a passing verdict, and excluded from participant verdicts.", ""]
        out += [f"- {i['calibration_id']} {i['participant_id']} `{i['session_id']}`: {i['end_reason']} (`{i['path']}`)"
                for i in public["incomplete_sessions"]]
        out.append("")
    problems = [s for s in public["sessions"] if s["integrity_error_count"]]
    if problems or public["rejected_sessions"]:
        out += ["## Data problems", ""]
        out += [f"- **Rejected** `{r['path']}`: {r['error']}" for r in public["rejected_sessions"]]
        out += [f"- `{s['session_id']}`: {s['integrity_error_count']} integrity problem(s); first: {s['integrity_errors'][0]}"
                for s in problems]
        out.append("")
    out += ["## Definitions", "", "All definitions, schedules and aggregation rules are in the protocol manifest "
            f"`{p['manifest']}`. Per-presentation values are in `presentations.csv`, per-block values in `blocks.csv`, "
            "per-session values in `sessions.csv`; everything, unrounded, in `summary.json` (including logged face "
            "position and scale per block and phase, for inspection only).", ""]
    return "\n".join(out)


# ---------------------------------------------------------------- CLI

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("input", type=Path, help="raw-data directory (searched recursively) or one session directory")
    parser.add_argument("--out", type=Path, required=True, help="output directory, outside the input data")
    parser.add_argument("--protocol", type=Path, default=DEFAULT_PROTOCOL, help="protocol manifest (default: repository copy)")
    parser.add_argument("--source-label", help="free-text label for the analyzed build or dataset")
    parser.add_argument("--selection", type=Path,
                        help="analysis-input manifest; only sessions whose session_id is listed under 'included' are analyzed")
    args = parser.parse_args(argv)
    if not args.input.is_dir():
        parser.error(f"input {args.input} is not a directory")
    try:
        summary = run(args.input, args.out, args.protocol, args.source_label, args.selection)
    except ProtocolError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    for r in summary["rejected_sessions"]:
        print(f"rejected {r['path']}: {r['error']}", file=sys.stderr)
    print(f"analyzed {len(summary['sessions'])} session(s), incomplete {len(summary['incomplete_sessions'])}, "
          f"rejected {len(summary['rejected_sessions'])}; wrote {args.out}")
    return 1 if summary["rejected_sessions"] else 0


if __name__ == "__main__":
    sys.exit(main())
