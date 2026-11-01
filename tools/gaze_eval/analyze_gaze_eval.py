#!/usr/bin/env python3
"""Metrics-only analysis of NoTouch Iteration 3 gaze evaluation sessions (log schema v2).

Reads each session's metadata.json, attempts.csv, events.csv and SHA256SUMS. Never modifies them.
Criteria, thresholds and the case schedule come from the protocol manifest
(docs/evaluation/iteration3/protocol.json); sessions recorded with a different manifest are rejected.
Python standard library only; no network; deterministic output for the same inputs and arguments.
No figures are produced (Stage 1).

The displayed target is the *instructed* fixation point. There is no independent eye tracker, so every
spatial metric is a target-referenced gaze error, not a ground-truth eye-direction error.

Usage:
    python tools/gaze_eval/analyze_gaze_eval.py <raw-data dir | session dir> --out <dir>
        [--protocol docs/evaluation/iteration3/protocol.json] [--source-label TEXT]
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

TOOL_VERSION = "2.0.0"
SUPPORTED_SCHEMA_VERSIONS = (2,)
RECORD_TYPE = "notouch_gaze_evaluation_session"
MODES = ("blind_measurement", "live_preview")
PRIMARY_MODE = "blind_measurement"
MET, NOT_MET, NOT_MEASURED, INSUFFICIENT, INCOMPLETE = "MET", "NOT_MET", "NOT_MEASURED", "INSUFFICIENT_DATA", "INCOMPLETE"
OUTCOMES = ("processed", "stale_delivery", "discarded_epoch", "discarded_closing", "discarded_model_changed",
            "processing_error")
QUALITIES = ("tracking", "closed", "uncertain", "lost", "out_of_range", "stale")
FEATURE_COLUMNS = ("f_x", "f_y", "f_face_x", "f_face_y", "f_scale", "f_left_x", "f_left_y", "f_right_x", "f_right_y",
                   "f_yaw_proxy", "f_pitch_proxy", "f_roll", "f_distance_cm", "f_head_yaw", "f_head_pitch")
ATTEMPT_COLUMNS = (
    "schema_version", "session_id", "attempt_seq", "main_seq", "outcome",
    "accepted_uptime_ms", "result_uptime_ms", "elapsed_ms",
    "phase", "pass_number", "trial_index", "index_in_pass", "case_id", "target_id", "target_x_norm", "target_y_norm",
    "detector", "eye_state", "left_open", "right_open",
) + FEATURE_COLUMNS + (
    "quality", "valid", "invalid_reason", "raw_x_norm", "raw_y_norm", "point_x_norm", "point_y_norm",
    "display_x_norm", "display_y_norm", "pointer_shown", "landmarker_ms", "delivery_ms", "latency_ms", "published",
)
EVENT_COLUMNS = ("schema_version", "session_id", "main_seq", "event_uptime_ms", "elapsed_ms", "event", "detail")
CHECKSUM_FILE = "SHA256SUMS"
CHECKSUMMED_FILES = ("attempts.csv", "events.csv", "metadata.json", "model_snapshot.json")
OUTPUT_FILES = ("summary.json", "sessions.csv", "presentations.csv", "report.md")
CRITERIA_IDS = ("successful_presentation_rate", "p95_presentation_error", "mean_jitter_rms",
                "tracking_availability", "valid_sample_rate", "p95_processing_latency")
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROTOCOL = REPO_ROOT / "docs" / "evaluation" / "iteration3" / "protocol.json"
MAX_LISTED = 50
IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
SHA1_HEX = re.compile(r"^[0-9a-f]{40}$")


class ProtocolError(Exception):
    """The protocol manifest is unusable."""


class SessionError(Exception):
    """A session that cannot be analyzed at all (missing files, wrong schema or protocol, inconsistent identity)."""


# ---------------------------------------------------------------- statistics

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
    min_valid_samples: int
    min_valid_coverage: float
    min_eligible_fraction: float
    success: dict
    criteria: tuple
    planned_sessions: dict

    @property
    def planned(self) -> int:
        return len(self.cases)

    @property
    def total_ms(self) -> int:
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
        criteria = tuple(performance["session_criteria"])
        protocol = Protocol(
            path=path, sha256=normalized_sha256(path), protocol_id=data["protocol_id"], version=data["protocol_version"],
            grid=tuple(timing["grid_norm"]), presentation_ms=timing["presentation_ms"], settling_ms=timing["settling_ms"],
            measurement_ms=timing["measurement_ms"], cases=cases,
            min_valid_samples=sufficiency["min_valid_samples"], min_valid_coverage=sufficiency["min_valid_coverage"],
            min_eligible_fraction=sufficiency["min_eligible_fraction_for_session_spatial_criteria"],
            success=performance["successful_presentation"], criteria=criteria,
            planned_sessions={p["participant_id"]: p["sessions"] for p in data["collection_plan"]["initial"]},
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as e:
        raise ProtocolError(f"protocol manifest {path} unusable: {e}") from e
    if protocol.settling_ms + protocol.measurement_ms != protocol.presentation_ms:
        raise ProtocolError("protocol: settling_ms + measurement_ms != presentation_ms")
    if timing.get("presentations") != len(cases) or timing.get("total_ms") != protocol.total_ms:
        raise ProtocolError("protocol: presentation count or total_ms disagrees with the case list")
    for i, c in enumerate(cases):
        if c.get("trial_index") != i or c.get("onset_ms") != i * protocol.presentation_ms:
            raise ProtocolError(f"protocol: case {i} is out of order")
    if len({c["case_id"] for c in cases}) != len(cases):
        raise ProtocolError("protocol: case_id values are not unique")
    if tuple(c.get("id") for c in criteria) != CRITERIA_IDS or any(c.get("comparison") not in (">=", "<=") for c in criteria):
        raise ProtocolError(f"protocol: session_criteria must be {list(CRITERIA_IDS)} with >= or <= comparisons")
    return protocol


# ---------------------------------------------------------------- session loading

@dataclass
class Attempt:
    seq: int
    main_seq: int | None
    outcome: str
    accepted_ms: int
    trial: int
    phase: str
    quality: str | None
    valid: bool
    invalid_reason: str | None
    raw: tuple | None
    point: tuple | None
    display: tuple | None
    latency_ms: float | None
    landmarker_ms: float | None
    delivery_ms: float | None


@dataclass
class Session:
    path: Path
    label: str
    metadata: dict
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


def validate_metadata(meta, protocol: Protocol) -> dict:
    if not isinstance(meta, dict):
        raise SessionError("metadata.json is not a JSON object")
    version = _require(meta, "schema_version", int)
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        raise SessionError(f"unsupported schema_version {version} (supported: {list(SUPPORTED_SCHEMA_VERSIONS)})")
    if meta.get("record_type") != RECORD_TYPE:
        raise SessionError(f"metadata: record_type is not {RECORD_TYPE!r}")
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
    _require(meta, "start_uptime_ms", int)
    if meta.get("end_uptime_ms") is not None:
        _require(meta, "end_uptime_ms", int)
    p = _require(meta, "protocol", dict)
    recorded = _require(p, "sha256", str, "metadata.protocol")
    if recorded != protocol.sha256:
        raise SessionError(f"recorded with protocol sha256 {recorded}, but the manifest {protocol.path.name} has "
                           f"{protocol.sha256}; analyze it with the manifest it was recorded with")
    expected = {"id": protocol.protocol_id, "version": protocol.version, "presentations_planned": protocol.planned,
                "presentation_ms": protocol.presentation_ms, "settling_ms": protocol.settling_ms,
                "measurement_ms": protocol.measurement_ms, "case_ids": [c["case_id"] for c in protocol.cases]}
    for key, value in expected.items():
        if p.get(key) != value:
            raise SessionError(f"metadata.protocol.{key} {p.get(key)!r} disagrees with the manifest ({value!r})")
    completed = p.get("presentations_completed")
    if completed is not None and (not isinstance(completed, int) or isinstance(completed, bool) or not 0 <= completed <= protocol.planned):
        raise SessionError(f"metadata.protocol: invalid presentations_completed {completed!r}")
    display = _require(meta, "display", dict)
    for key in ("width_px", "height_px"):
        if _require(display, key, int, "metadata.display") <= 0:
            raise SessionError(f"metadata.display.{key} must be positive")
    return meta


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


def _pair(row, x_key, y_key) -> tuple | None:
    x, y = _number(row[x_key], x_key), _number(row[y_key], y_key)
    if (x is None) != (y is None):
        raise ValueError(f"only one of {x_key}/{y_key} is present")
    return None if x is None else (x, y)


def _optional_int(cell: str) -> int | None:
    return None if cell == "" else int(cell)


def load_session(path: Path, root: Path, protocol: Protocol) -> Session:
    label = path.relative_to(root).as_posix() if path != root else path.name
    missing = [n for n in ("metadata.json", "attempts.csv") if not (path / n).is_file()]
    if missing:
        raise SessionError(f"missing {', '.join(missing)}")
    try:
        meta = json.loads((path / "metadata.json").read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise SessionError(f"metadata.json unreadable: {e}") from e
    session = Session(path, label, validate_metadata(meta, protocol))
    session.checksums, problems = verify_checksums(path)
    for problem in problems:
        session.integrity(problem)
    if session.checksums == "absent" and meta["final_state"] == "complete":
        session.integrity("SHA256SUMS missing for a complete session")
    for name in ("events.csv", "model_snapshot.json"):
        if not (path / name).is_file():
            session.integrity(f"{name} missing")
    _read_attempts(session, (path / "attempts.csv").read_text(encoding="utf-8"), protocol)
    if (path / "events.csv").is_file():
        _read_events(session, (path / "events.csv").read_text(encoding="utf-8"))
    _check_integrity(session)
    return session


def _read_attempts(s: Session, text: str, protocol: Protocol) -> None:
    meta, start = s.metadata, s.metadata["start_uptime_ms"]
    reader = csv.reader(io.StringIO(text, newline=""))
    header = next(reader, None)
    if header is None or tuple(header) != ATTEMPT_COLUMNS:
        raise SessionError("attempts.csv header differs from log schema v2")
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
            elapsed = accepted - start
            if not 0 <= elapsed < protocol.total_ms:
                raise ValueError(f"accepted time {accepted} is outside the run schedule")
            # Assignment by the frame-acceptance timestamp and the schedule, never by arrival time.
            trial = elapsed // protocol.presentation_ms
            phase = "settling" if elapsed % protocol.presentation_ms < protocol.settling_ms else "measurement"
            case = protocol.cases[trial]
            logged = (row["phase"], row["trial_index"], row["case_id"], row["target_id"],
                      _number(row["target_x_norm"], "target_x"), _number(row["target_y_norm"], "target_y"))
            if logged != (phase, str(trial), case["case_id"], case["target_id"], case["target_x_norm"], case["target_y_norm"]):
                s.integrity(f"attempts.csv line {line_no}: logged phase/case disagrees with the schedule (schedule used)")
            quality = row["quality"] or None
            if quality is not None and quality not in QUALITIES:
                raise ValueError(f"unknown quality {quality!r}")
            point, raw = _pair(row, "point_x_norm", "point_y_norm"), _pair(row, "raw_x_norm", "raw_y_norm")
            display = _pair(row, "display_x_norm", "display_y_norm")
            valid = outcome == "processed" and quality == "tracking" and point is not None
            if row["valid"] not in ("true", "false"):
                raise ValueError(f"valid {row['valid']!r} is not true/false")
            if (row["valid"] == "true") != valid:
                s.integrity(f"attempts.csv line {line_no}: valid flag disagrees with outcome, quality and coordinates")
            reason = None if valid else (outcome if outcome != "processed" else (quality or "unspecified"))
            if (row["invalid_reason"] or None) != reason:
                s.issue(f"attempts.csv line {line_no}: invalid_reason {row['invalid_reason']!r} differs from derived {reason!r}")
            latency = _number(row["latency_ms"], "latency_ms")
            if latency is not None and latency < 0:
                raise ValueError("negative latency_ms")
            attempt = Attempt(seq, _optional_int(row["main_seq"]), outcome, accepted, trial, phase, quality, valid, reason,
                              raw, point, display, latency, _number(row["landmarker_ms"], "landmarker_ms"),
                              _number(row["delivery_ms"], "delivery_ms"))
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
        s.integrity("events.csv header differs from log schema v2")
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
    order = sorted([a.main_seq for a in s.attempts if a.main_seq is not None] + [e["main_seq"] for e in s.events])
    if order != list(range(len(order))):
        s.integrity("main-thread order (main_seq) has gaps or duplicates")
    first = min(s.events, key=lambda e: e["main_seq"], default=None)
    if first is None or (first["event"], first["detail"]) != ("pipeline_reset", "run_start"):
        s.integrity("the run does not start with the run_start filter reset")


# ---------------------------------------------------------------- metrics

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


def analyze_session(s: Session, protocol: Protocol) -> dict:
    meta = s.metadata
    display = meta["display"]
    basis = (display["width_px"], display["height_px"])
    start, planned = meta["start_uptime_ms"], protocol.planned
    completed = meta["protocol"].get("presentations_completed")
    if completed is None:
        # Interrupted before finalization: only presentations a later attempt proves finished count.
        last = max((a.accepted_ms for a in s.attempts), default=None)
        completed = 0 if last is None else max(0, min(planned, (last - start) // protocol.presentation_ms))
    observed_until = meta.get("end_uptime_ms") or max((a.accepted_ms for a in s.attempts), default=start)
    by_trial: dict[int, list[Attempt]] = {i: [] for i in range(planned)}
    for a in s.attempts:
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
        raw = spatial_metrics([a.raw for a in valid if a.raw is not None], target, basis) if valid else None
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
            "trial_index": trial, "case_id": case["case_id"], "pass_number": case["pass_number"],
            "index_in_pass": case["index_in_pass"], "target_id": case["target_id"],
            "target_x_norm": target[0], "target_y_norm": target[1],
            "presentation_complete": complete, "measurement_window_s": window_s,
            "measurement_attempts": len(measurement), "valid_samples": len(valid), "valid_coverage": coverage,
            "measurement_invalid_by_reason": _count_reasons(measurement),
            "settling_attempts": len(settling), "settling_valid": sum(a.valid for a in settling),
            "eligibility": eligibility, "eligibility_reason": eligibility_reason,
            "successful": not failures, "success_failures": failures,
            "final": final, "raw_diagnostic": raw,
            "display_clamped_samples": sum(a.display != a.point for a in valid),
            "raw_off_screen_samples": sum(a.raw is not None and not (0 <= a.raw[0] <= 1 and 0 <= a.raw[1] <= 1) for a in valid),
        })

    eligible = [p for p in presentations if p["eligibility"] == "eligible"]
    measurement_all = [a for a in s.attempts if a.phase == "measurement"]
    valid_all = [a for a in measurement_all if a.valid]
    window_total = sum(p["measurement_window_s"] for p in presentations)
    latencies = [a.latency_ms for a in s.attempts if a.latency_ms is not None]
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
    complete_session = meta["final_state"] == "complete"
    if not complete_session:
        for c in criteria:
            c.update(status=INCOMPLETE, reason=f"session incomplete ({meta['end_reason']}); value is partial", partial=True)
        verdict, verdict_reason = INCOMPLETE, f"session ended before completion ({meta['end_reason']})"
    elif s.integrity_error_count:
        verdict, verdict_reason = INSUFFICIENT, f"{s.integrity_error_count} data-integrity problem(s)"
    elif any(c["status"] == NOT_MET for c in criteria):
        verdict = NOT_MET
        verdict_reason = "not met: " + ", ".join(c["id"] for c in criteria if c["status"] == NOT_MET)
    elif any(c["status"] == INSUFFICIENT for c in criteria):
        verdict = INSUFFICIENT
        verdict_reason = "insufficient: " + ", ".join(c["id"] for c in criteria if c["status"] == INSUFFICIENT)
    else:
        verdict, verdict_reason = MET, "all session criteria met"

    def balanced(key, stream="final"):
        blocks = [p[stream] for p in eligible if p[stream]]
        return _pair_metric(mean([b[key]["norm"] for b in blocks]), mean([b[key]["px"] for b in blocks])) if blocks else None

    pooled_errors = [e for p in presentations if p["final"] for e in p["final"]["_errors"]]
    resets: dict[str, int] = {}
    for e in s.events:
        if e["event"] == "pipeline_reset":
            resets[e["detail"]] = resets.get(e["detail"], 0) + 1
    outcomes = {o: sum(a.outcome == o for a in s.attempts) for o in OUTCOMES}
    counters = meta.get("counters") if isinstance(meta.get("counters"), dict) else {}
    build, predictor, device = meta.get("build") or {}, meta.get("predictor") or {}, meta.get("device") or {}
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
    if (predictor.get("source") != "personal_9_point_calibration" or predictor.get("verified_against_saved_calibration") is not True
            or predictor.get("evaluation_runs_on_this_calibration_before") != 0):
        official.append("predictor is not a fresh, verified personal nine-point calibration")
    return {
        "session_id": meta["session_id"], "participant_id": meta["participant_id"], "session_label": meta["session_label"],
        "path": s.label, "mode": meta["mode"], "final_state": meta["final_state"], "end_reason": meta["end_reason"],
        "start_wall_time_utc": meta.get("start_wall_time_utc"),
        "build": build, "app": meta.get("app"), "libraries": meta.get("libraries"), "device": device,
        "display": {"width_px": basis[0], "height_px": basis[1], "rotation": display.get("rotation"),
                    "basis": display.get("basis")},
        "predictor": {k: predictor.get(k) for k in ("kind", "source", "label", "config_key", "calibration_serial",
                                                    "calibration_age_ms_at_start", "fit_error", "saved_calibration_sha256")},
        "conditions": meta.get("conditions"),
        "compatibility_group": _group_label(meta),
        "checksums": s.checksums,
        "integrity_error_count": s.integrity_error_count, "integrity_errors": s.integrity_errors,
        "issue_count": s.issue_count, "issues": s.issues, "unassigned_rows": s.unassigned_rows,
        "presentations_planned": planned, "presentations_completed": completed,
        "criteria": criteria, "verdict": verdict, "verdict_reason": verdict_reason,
        "official": not official, "official_exclusions": official,
        "counts": {
            "attempts": len(s.attempts), "attempts_by_outcome": outcomes,
            "measurement_attempts": len(measurement_all), "valid_measurement_samples": len(valid_all),
            "measurement_invalid_by_reason": _count_reasons(measurement_all),
            "settling_attempts": sum(p["settling_attempts"] for p in presentations),
            "settling_invalid_by_reason": _count_reasons([a for a in s.attempts if a.phase == "settling"]),
            "measurement_window_s": window_total,
            "frames_received": counters.get("frames_received"),
            "frames_cadence_skipped": counters.get("frames_cadence_skipped"),
            "attempts_unrecorded": counters.get("attempts_unrecorded"),
            "rows_lost_total": (meta.get("logging") or {}).get("rows_lost_total"),
            "pipeline_resets_by_reason": dict(sorted(resets.items())),
        },
        "diagnostics": {
            "presentations_successful": successful,
            "presentations_eligible": len(eligible), "min_eligible_for_spatial_criteria": min_eligible,
            "session_mean_error": balanced("mean_error"),
            "median_of_presentation_mean_errors": median([p["final"]["mean_error"]["norm"] for p in eligible]),
            "bias_x_mean": balanced("bias_x"), "bias_y_mean": balanced("bias_y"),
            "centroid_error_mean": balanced("centroid_error"), "jitter_rms_mean": balanced("jitter_rms"),
            "off_screen_fraction": (sum(a.point is not None and not (0 <= a.point[0] <= 1 and 0 <= a.point[1] <= 1)
                                        for a in valid_all) / len(valid_all)) if valid_all else None,
            "raw_off_screen_fraction": (sum(a.raw is not None and not (0 <= a.raw[0] <= 1 and 0 <= a.raw[1] <= 1)
                                            for a in valid_all) / len(valid_all)) if valid_all else None,
            "display_clamped_samples": sum(p["display_clamped_samples"] for p in presentations),
            "raw_stream_session_mean_error": balanced("mean_error", "raw_diagnostic"),
            "pooled_sample_errors": {**_distribution(pooled_errors),
                                     "note": "sensitivity view; every valid measurement sample weighted equally"},
            "latency_ms": {**_distribution(latencies),
                           "attempts_by_outcome": {o: sum(1 for a in s.attempts if a.outcome == o and a.latency_ms is not None)
                                                   for o in OUTCOMES}},
            "landmarker_ms": {**_distribution([a.landmarker_ms for a in s.attempts if a.landmarker_ms is not None]),
                              "note": "FaceLandmarker inference only; not end-to-end latency"},
            "delivery_ms": _distribution([a.delivery_ms for a in s.attempts if a.delivery_ms is not None]),
        },
        "presentations": presentations,
        "_eligible_means": [p["final"]["mean_error"]["norm"] for p in eligible],
        "_eligible_jitter": [p["final"]["jitter_rms"]["norm"] for p in eligible],
    }


def _group_key(meta: dict) -> tuple:
    build, predictor, device, display = (meta.get("build") or {}), (meta.get("predictor") or {}), \
        (meta.get("device") or {}), meta["display"]
    return (meta["mode"], meta["protocol"]["sha256"], str(build.get("git_commit")), str(build.get("git_dirty_at_build")),
            f"{predictor.get('kind')}/{predictor.get('source')}", f"{device.get('manufacturer')} {device.get('model')}",
            f"{display['width_px']}x{display['height_px']}", str(display.get("rotation")))


def _group_label(meta: dict) -> str:
    mode, _, commit, dirty, predictor, device, size, rotation = _group_key(meta)
    return (f"{mode} | build {commit[:12]}{'' if dirty == 'false' else ' (dirty=' + dirty + ')'} | {predictor} | "
            f"{device} | {size} rotation {rotation}")


# ---------------------------------------------------------------- aggregation

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
    presentations = [p for s in complete for p in s["presentations"]]
    eligible_means = [m for s in complete for m in s["_eligible_means"]]
    eligible_jitter = [j for s in complete for j in s["_eligible_jitter"]]

    def criterion(sess, cid):
        return next(c for c in sess["criteria"] if c["id"] == cid)["value"]

    latency_p95 = [criterion(s, "p95_processing_latency") for s in complete if criterion(s, "p95_processing_latency") is not None]
    return {
        "participant_id": pid,
        "planned_sessions": protocol.planned_sessions.get(pid),
        "complete_sessions": [s["session_id"] for s in complete],
        "incomplete_sessions": [s["session_id"] for s in sessions if s["final_state"] != "complete"],
        "official_sessions": [s["session_id"] for s in complete if s["official"]],
        "session_verdicts": {s["session_id"]: s["verdict"] for s in complete},
        "verdict": verdict,
        "verdict_rule": "MET only if every complete session in this group is MET; NOT_MET if any is NOT_MET.",
        "descriptive": {
            "successful_presentation_rate": (sum(p["successful"] for p in presentations) / len(presentations)
                                             if presentations else None),
            "p95_presentation_error_pooled": nearest_rank(eligible_means, 95),
            "mean_presentation_error_pooled": mean(eligible_means),
            "mean_jitter_rms_pooled": mean(eligible_jitter),
            "eligible_presentations_pooled": len(eligible_means),
            "tracking_availability_mean_of_sessions": mean([v for v in (criterion(s, "tracking_availability") for s in complete) if v is not None]),
            "valid_sample_rate_mean_of_sessions": mean([v for v in (criterion(s, "valid_sample_rate") for s in complete) if v is not None]),
            "p95_processing_latency_worst_session": max(latency_p95) if latency_p95 else None,
            "weighting": "presentations equally (rate, pooled p95, jitter); sessions equally (availability, rate); "
                         "latency is the worst session p95, never an averaged p95",
        },
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
            "mode": key[0], "role": "primary" if key[0] == PRIMARY_MODE else
            "secondary; a visible pointer can change where the participant looks",
            "key": dict(zip(("mode", "protocol_sha256", "git_commit", "git_dirty_at_build", "predictor", "device",
                             "display_px", "rotation"), key)),
            "sessions": [s["session_id"] for s in members],
            "participants": aggregated,
            "participant_verdict_counts": {v: sum(p["verdict"] == v for p in aggregated)
                                           for v in (MET, NOT_MET, INSUFFICIENT, INCOMPLETE)},
        })
    return out


def _mark_shared_calibrations(sessions: list[dict]) -> None:
    """A calibration may serve one session only; reuse is flagged on every session that shares it."""
    seen: dict[tuple, list[dict]] = {}
    for s in sessions:
        sha = s["predictor"].get("saved_calibration_sha256")
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


def _strip_private(value):
    if isinstance(value, dict):
        return {k: _strip_private(v) for k, v in value.items() if not k.startswith("_")}
    if isinstance(value, list):
        return [_strip_private(v) for v in value]
    return value


def _write(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def run(input_path: Path, out_dir: Path, protocol_path: Path, source_label: str | None) -> dict:
    protocol = load_protocol(protocol_path)
    root = input_path.resolve()
    paths = discover(root)
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
    sessions.sort(key=lambda s: (s["participant_id"], s["start_wall_time_utc"] or "", s["session_id"]))
    summary = {
        "tool": "analyze_gaze_eval", "tool_version": TOOL_VERSION,
        "supported_schema_versions": list(SUPPORTED_SCHEMA_VERSIONS),
        "scope": "metrics only; no figures",
        "protocol": {"id": protocol.protocol_id, "version": protocol.version, "sha256": protocol.sha256,
                     "manifest": protocol.path.name},
        "source_label": source_label,
        "reference_note": "Targets are instructed fixation points, not independently measured eye direction.",
        "thresholds": {"successful_presentation": protocol.success,
                       "session_criteria": [{k: c[k] for k in ("id", "comparison", "threshold", "unit")} for c in protocol.criteria],
                       "data_sufficiency": {"min_valid_samples": protocol.min_valid_samples,
                                            "min_valid_coverage": protocol.min_valid_coverage,
                                            "min_eligible_presentations": protocol.min_eligible_presentations}},
        "groups": build_groups(sessions, metas, protocol),
        "incomplete_sessions": [{"participant_id": s["participant_id"], "session_id": s["session_id"],
                                 "end_reason": s["end_reason"], "path": s["path"]}
                                for s in sessions if s["final_state"] != "complete"],
        "rejected_sessions": rejected,
        "sessions": sessions,
    }
    public = _strip_private(summary)
    out_dir.mkdir(parents=True, exist_ok=True)
    assert "README.md" not in OUTPUT_FILES
    _write(out_dir / "summary.json", json.dumps(public, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    _write(out_dir / "sessions.csv", sessions_csv(public["sessions"]))
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
    header = ["participant_id", "session_label", "session_id", "mode", "compatibility_group", "final_state", "end_reason",
              "git_commit", "git_dirty_at_build", "official", "verdict"]
    for cid in CRITERIA_IDS:
        header += [f"{cid}_value", f"{cid}_status"]
    header += ["presentations_successful", "presentations_eligible", "session_mean_error_norm", "session_mean_error_px",
               "bias_x_norm", "bias_y_norm", "off_screen_fraction", "measurement_attempts", "valid_measurement_samples",
               "integrity_error_count", "checksums"]
    rows = []
    for s in sessions:
        d, c = s["diagnostics"], {x["id"]: x for x in s["criteria"]}
        row = [s["participant_id"], s["session_label"], s["session_id"], s["mode"], s["compatibility_group"],
               s["final_state"], s["end_reason"], s["build"].get("git_commit"), s["build"].get("git_dirty_at_build"),
               s["official"], s["verdict"]]
        for cid in CRITERIA_IDS:
            row += [c[cid]["value"], c[cid]["status"]]
        mean_error = d["session_mean_error"] or {}
        row += [d["presentations_successful"], d["presentations_eligible"], mean_error.get("norm"), mean_error.get("px"),
                (d["bias_x_mean"] or {}).get("norm"), (d["bias_y_mean"] or {}).get("norm"), d["off_screen_fraction"],
                s["counts"]["measurement_attempts"], s["counts"]["valid_measurement_samples"],
                s["integrity_error_count"], s["checksums"]]
        rows.append(row)
    return _csv(header, rows)


def presentations_csv(sessions: list[dict]) -> str:
    metrics = ("mean_error", "median_error", "max_error", "bias_x", "bias_y", "centroid_error", "jitter_rms")
    header = ["participant_id", "session_id", "mode", "session_final_state", "trial_index", "case_id", "pass_number",
              "index_in_pass", "target_id", "target_x_norm", "target_y_norm", "presentation_complete",
              "measurement_attempts", "valid_samples", "valid_coverage", "measurement_window_s", "eligibility",
              "successful", "success_failures", "off_screen_samples", "display_clamped_samples", "raw_off_screen_samples",
              "settling_attempts", "settling_valid", "measurement_invalid_by_reason"]
    header += [f"final_{m}_{u}" for m in metrics for u in ("norm", "px")]
    header += ["raw_mean_error_norm"]
    rows = []
    for s in sessions:
        for p in s["presentations"]:
            f = p["final"] or {}
            row = [s["participant_id"], s["session_id"], s["mode"], s["final_state"], p["trial_index"], p["case_id"],
                   p["pass_number"], p["index_in_pass"], p["target_id"], p["target_x_norm"], p["target_y_norm"],
                   p["presentation_complete"], p["measurement_attempts"], p["valid_samples"], p["valid_coverage"],
                   p["measurement_window_s"], p["eligibility"], p["successful"], "; ".join(p["success_failures"]),
                   f.get("off_screen_samples"), p["display_clamped_samples"], p["raw_off_screen_samples"],
                   p["settling_attempts"], p["settling_valid"],
                   "; ".join(f"{k}={v}" for k, v in p["measurement_invalid_by_reason"].items())]
            row += [(f.get(m) or {}).get(u) for m in metrics for u in ("norm", "px")]
            row += [((p["raw_diagnostic"] or {}).get("mean_error") or {}).get("norm")]
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
    out = ["# NoTouch Iteration 3 gaze evaluation: generated metrics report", "",
           f"Generated by `analyze_gaze_eval` {public['tool_version']} (metrics only, no figures). Do not edit by hand; "
           "rerun the analyzer instead. The human-maintained report is `docs/evaluation/iteration3/README.md`.", "",
           f"- Protocol: `{p['id']}` {p['version']}, manifest SHA-256 `{p['sha256']}`",
           f"- Source label: {public['source_label'] or 'not supplied'}",
           f"- Sessions analyzed: {len(public['sessions'])}; incomplete: {len(public['incomplete_sessions'])}; "
           f"rejected: {len(public['rejected_sessions'])}",
           "- Targets are instructed fixation points, not independently measured eye direction: spatial values are "
           "target-referenced gaze errors in full-display normalized units (px uses each session's display size).",
           "- Statuses: MET, NOT_MET, NOT_MEASURED, INSUFFICIENT_DATA, INCOMPLETE. Thresholds are compared unrounded; "
           "table values are rounded for display only.", ""]
    th = public["thresholds"]
    out += ["## Predeclared criteria", "", "| Criterion | Rule |", "|---|---|",
            f"| Successful presentation | mean final error ≤ {th['successful_presentation']['max_mean_error_norm']}, "
            f"coverage ≥ {th['successful_presentation']['min_valid_coverage']}, "
            f"≥ {th['successful_presentation']['min_valid_samples']} valid samples |"]
    out += [f"| `{c['id']}` | {c['comparison']} {c['threshold']} ({c['unit']}) |" for c in th["session_criteria"]]
    out.append("")
    if not public["sessions"]:
        out += ["## Results", "", "No sessions found. Nothing has been measured (NOT_MEASURED).", ""]
    for g in public["groups"]:
        out += [f"## Group: {g['group']}", "", f"Role: {g['role']}.", "",
                "### Participants", "",
                "| Participant | Complete / planned sessions | Verdict | Success rate | Pooled p95 error | Mean jitter "
                "| Availability (mean) | Valid/s (mean) | Worst p95 latency ms |",
                "|---|---|---|---|---|---|---|---|---|"]
        for a in g["participants"]:
            d = a["descriptive"]
            out.append(f"| {a['participant_id']} | {len(a['complete_sessions'])} / {a['planned_sessions'] or '—'} | "
                       f"{a['verdict']} | {_f(d['successful_presentation_rate'])} | {_f(d['p95_presentation_error_pooled'])} | "
                       f"{_f(d['mean_jitter_rms_pooled'])} | {_f(d['tracking_availability_mean_of_sessions'])} | "
                       f"{_f(d['valid_sample_rate_mean_of_sessions'], 1)} | {_f(d['p95_processing_latency_worst_session'], 1)} |")
        out += ["", "### Sessions", "",
                "| Participant | Session | Official | Verdict | " + " | ".join(f"`{c}`" for c in CRITERIA_IDS) + " |",
                "|---|---|---|---|" + "---|" * len(CRITERIA_IDS)]
        for s in public["sessions"]:
            if s["session_id"] not in g["sessions"]:
                continue
            crit = {c["id"]: c for c in s["criteria"]}
            out.append(f"| {s['participant_id']} | {s['session_label']} `{s['session_id']}` | "
                       f"{'yes' if s['official'] else 'no'} | {s['verdict']} | "
                       + " | ".join(_criterion_cell(crit[c]) for c in CRITERIA_IDS) + " |")
        out.append("")
        for s in public["sessions"]:
            if s["session_id"] in g["sessions"] and not s["official"]:
                out.append(f"- `{s['session_id']}` not official: {'; '.join(s['official_exclusions'])}")
        out.append("")
    if public["incomplete_sessions"]:
        out += ["## Incomplete sessions", "", "Listed, never given a passing verdict, and excluded from participant verdicts.", ""]
        out += [f"- {i['participant_id']} `{i['session_id']}`: {i['end_reason']} (`{i['path']}`)" for i in public["incomplete_sessions"]]
        out.append("")
    problems = [s for s in public["sessions"] if s["integrity_error_count"]]
    if problems or public["rejected_sessions"]:
        out += ["## Data problems", ""]
        out += [f"- **Rejected** `{r['path']}`: {r['error']}" for r in public["rejected_sessions"]]
        out += [f"- `{s['session_id']}`: {s['integrity_error_count']} integrity problem(s); first: {s['integrity_errors'][0]}"
                for s in problems]
        out.append("")
    out += ["## Definitions", "", "All definitions, case schedules and aggregation rules are in the protocol manifest "
            f"`{p['manifest']}`. Per-presentation values are in `presentations.csv`; per-session values in `sessions.csv`; "
            "everything, unrounded, in `summary.json`.", ""]
    return "\n".join(out)


# ---------------------------------------------------------------- CLI

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("input", type=Path, help="raw-data directory (searched recursively) or one session directory")
    parser.add_argument("--out", type=Path, required=True, help="output directory, outside the input data")
    parser.add_argument("--protocol", type=Path, default=DEFAULT_PROTOCOL, help="protocol manifest (default: repository copy)")
    parser.add_argument("--source-label", help="free-text label for the analyzed build or dataset")
    args = parser.parse_args(argv)
    if not args.input.is_dir():
        parser.error(f"input {args.input} is not a directory")
    try:
        summary = run(args.input, args.out, args.protocol, args.source_label)
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
