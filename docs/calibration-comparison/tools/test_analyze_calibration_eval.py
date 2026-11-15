"""Unit tests for analyze_calibration_eval (schema v4). Every session here is SYNTHETIC, not participant data.

Run: python -B -m unittest discover -s docs/calibration-comparison/tools -v
"""
from __future__ import annotations

import csv
import hashlib
import importlib.util
import io
import json
import math
import random
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

import analyze_calibration_eval as tool

TOOLS = Path(__file__).resolve().parent
PROTOCOL = TOOLS.parent / "protocol.json"
GOLDEN = TOOLS / "testdata" / "synthetic_session"
REFERENCE = TOOLS / "testdata" / "reference_iter3"
MANIFEST = json.loads(PROTOCOL.read_text(encoding="utf-8"))
CASES = MANIFEST["cases"]
ORDERS = MANIFEST["block_orders"]
A, B = "calibration_a_three_pass_static", "calibration_b_three_pass_positions"  # B: this branch; A: the sibling candidate
PLAN_B = json.loads((TOOLS.parent / "collection-plan-b.json").read_text(encoding="utf-8"))
START = 1_000_000
W, H = 1080, 2400


def default_rows(block, trial, phase, k, target):
    """Valid processed sample 0.01 right of and 0.02 below the target."""
    return {"point": (target[0] + 0.01, target[1] + 0.02)}


def kotlin(v: float) -> str:
    """Kotlin Double.toString style for the magnitudes used here."""
    return repr(float(v)) if v == 0 or 1e-3 <= abs(v) < 1e7 else f"{v:.1E}".replace("E-0", "E-")


def clamp(p):
    return None if p is None else (min(max(p[0], 0.0), 1.0), min(max(p[1], 0.0), 1.0))


def write_session(root: Path, participant="ANNA", label="B1", sid="eval-b", *, calibration_id=B, order="nhl", folder=None,
                  mode="blind_measurement", final_state="complete", end_reason="completed", rows=default_rows,
                  measurement_rows=20, settling_rows=10, prep_rows=6, countdown_rows=4, prep_spec=None,
                  preparation_ms=(2000, 1500, 1200), reached=(3, 32), build_commit="a" * 40, dirty="false",
                  device_model="SM-TEST", calibration_sha=None, meta_patch=None, checksums=True, protocol_sha=None,
                  schema="4", block_resets=True, session_reset=True, faces=None) -> Path:
    """A schema-4 session. [reached] = (blocks started, presentations completed in the last started block)."""
    d = root / (folder or f"{calibration_id}/{participant}/{sid}")
    d.mkdir(parents=True)
    positions = ORDERS[order]
    blocks_started, last_completed = reached
    complete = final_state == "complete"
    finalized = complete or end_reason != "in_progress_or_interrupted"
    timeline, prep_start = [], START
    for k in range(3):
        if k < blocks_started:
            ready = prep_start + preparation_ms[k]
            bstart = ready + 3000
            timeline.append((prep_start, ready, bstart, bstart + 80_000))
            prep_start = bstart + 80_000
        else:
            timeline.append((prep_start if k == blocks_started else None, None, None, None))
            prep_start = None
    end_uptime = None
    if finalized:
        if complete:
            end_uptime = timeline[2][3]
        elif blocks_started == 0:
            end_uptime = START + 700
        else:
            end_uptime = timeline[blocks_started - 1][2] + last_completed * 2500 + 700
    attempts = io.StringIO()
    w = csv.writer(attempts, lineterminator="\n")
    w.writerow(tool.ATTEMPT_COLUMNS)
    events = [["4" if schema == "4" else schema, sid, None, str(START - 100), "-100", "", "", "pipeline_reset", "session_start"]] \
        if session_reset else []
    entries = []  # (time, kind, payload) in main-thread order
    seq = valid_count = 0

    def row(block, phase, accepted, spec, trial=None):
        nonlocal seq, valid_count
        position = positions[block]
        prep, ready, bstart, _ = timeline[block]
        outcome, quality = spec.get("outcome", "processed"), spec.get("quality", "tracking")
        processed = outcome == "processed"
        tracking = processed and quality == "tracking"
        point = spec.get("point") if tracking else None
        linear = spec.get("linear", point) if tracking else None
        predicted = spec.get("predicted", clamp(linear)) if tracking else None
        has_features = processed and quality in ("tracking", "out_of_range")
        valid = tracking and point is not None
        reason = "" if valid else (outcome if not processed else quality)
        latency = spec.get("latency", 40.0 if outcome != "processing_error" else None)
        rendered = None if point is None or mode != "live_preview" else (point[0] * W, point[1] * H)
        case = CASES[trial] if trial is not None else None
        target = (case["target_x_norm"], case["target_y_norm"]) if case else None
        face = spec.get("face", (0.5, 0.5 if faces is None else faces[position], 0.3))
        pair = lambda p: ("", "") if p is None else (kotlin(p[0]), kotlin(p[1]))
        cells = [schema, sid, str(seq), None, outcome, str(accepted), str(accepted + 14), str(accepted + 20), str(accepted - START),
                 str(block + 1), position, spec.get("logged_phase", phase),
                 "" if phase == "preparation" else str(accepted - bstart),
                 "" if case is None else str(case["pass_number"]), "" if case is None else str(trial),
                 "" if case is None else str(case["index_in_pass"]), "" if case is None else f"{position}-{case['case_suffix']}",
                 "" if case is None else case["target_id"], "" if case is None else kotlin(target[0]),
                 "" if case is None else kotlin(target[1]),
                 spec.get("detector", "face_detected"), "OPEN" if has_features else ("CLOSED" if quality == "closed" else "LOST"),
                 "0.26", "0.25", spec.get("feature_rejection", "")]
        cells += [kotlin(0.01), kotlin(0.01), kotlin(face[0]), kotlin(face[1]), kotlin(face[2])] if has_features else [""] * 5
        cells += [quality if processed else "", spec.get("prediction_rejection", ""), spec.get("logged_valid", str(valid).lower()), reason,
                  *pair(linear), *pair(predicted), *(pair(None) if spec.get("blank_point") else pair(point)),
                  str(rendered is not None).lower(), *pair(rendered),
                  "11.500", "2.000", "" if latency is None else f"{latency:.3f}", str(processed).lower()]
        entries.append((accepted + 14, "attempt", (cells, outcome != "processing_error")))
        seq += 1
        valid_count += valid and not spec.get("blank_point")

    for k in range(blocks_started):
        prep, ready, bstart, bend = timeline[k]
        for j in range(prep_rows):
            row(k, "preparation", prep + 10 + j * ((ready - prep - 20) // max(prep_rows, 1)),
                (prep_spec or (lambda block, phase, j: {"point": (0.5, 0.5)}))(k, "preparation", j))
        for j in range(countdown_rows):
            row(k, "countdown", ready + 10 + j * 600, (prep_spec or (lambda block, phase, j: {"point": (0.5, 0.5)}))(k, "countdown", j))
        if block_resets:
            entries.append((bstart, "event", ["4", sid, None, str(bstart), str(bstart - START), str(k + 1), "settling",
                                              "pipeline_reset", "block_start"]))
        trials = 32 if (complete or k < blocks_started - 1) else min(last_completed + 1, 32)
        for trial in range(trials):
            case = CASES[trial]
            target = (case["target_x_norm"], case["target_y_norm"])
            onset = bstart + trial * 2500
            for phase, n, offset, step in (("settling", settling_rows, 3, 90),
                                           ("measurement", measurement_rows, 1003, 1490 // max(measurement_rows, 1))):
                for j in range(n):
                    spec = rows(k, trial, phase, j, target)
                    if spec is not None:
                        row(k, phase, onset + offset + step * j, spec, trial)
    main = 0
    for e in events:
        e[2] = str(main); main += 1
    out_events = list(events)
    for _, kind, payload in sorted(entries, key=lambda x: (x[0], x[1] == "attempt")):
        if kind == "event":
            payload[2] = str(main); main += 1
            out_events.append(payload)
        else:
            cells, has_main = payload
            cells[3] = str(main) if has_main else ""
            if has_main:
                main += 1
            w.writerow(cells)
    (d / "attempts.csv").write_bytes(attempts.getvalue().encode())
    ev = io.StringIO()
    ew = csv.writer(ev, lineterminator="\n")
    ew.writerow(tool.EVENT_COLUMNS)
    for e in out_events:
        ew.writerow(e)
    (d / "events.csv").write_bytes(ev.getvalue().encode())
    sha = calibration_sha or hashlib.sha256(sid.encode()).hexdigest()
    snapshot = {"snapshot_schema_version": 2, "calibration_id": calibration_id, "calibration_inputs_sha256": sha,
                "anchors": [{"pass": p, "point": q} for p in (1, 2, 3) for q in range(1, 10)],
                "parameters": {"reference_face_x": 0.5, "reference_face_y": 0.5, "reference_scale": 0.3}}
    (d / "model_snapshot.json").write_bytes((json.dumps(snapshot, indent=2) + "\n").encode())
    blocks = []
    for k in range(3):
        prep, ready, bstart, bend = timeline[k]
        done = 32 if (complete or k < blocks_started - 1) else (last_completed if k == blocks_started - 1 else 0)
        blocks.append({"block_index": k + 1, "head_position": positions[k], "case_id_prefix": f"{positions[k]}-",
                       "preparation_start_uptime_ms": prep, "ready_uptime_ms": ready, "countdown_ms": 3000,
                       "block_start_uptime_ms": bstart, "planned_block_end_uptime_ms": bend,
                       "block_reset_uptime_ms": bstart if block_resets and bstart is not None else None,
                       "presentations_planned": 32, "presentations_completed": done if finalized else None,
                       "state": (None if not finalized else "complete" if done == 32 else "not_started" if bstart is None else "incomplete")})
    meta = {
        "schema_version": int(schema), "record_type": tool.RECORD_TYPE, "session_id": sid,
        "participant_id": participant, "session_label": label, "mode": mode, "final_state": final_state,
        "end_reason": end_reason, "start_wall_time_utc": f"2026-11-1{len(sid) % 9}T10:00:00Z", "end_wall_time_utc": None,
        "start_uptime_ms": START, "end_uptime_ms": end_uptime, "planned_end_uptime_ms": timeline[2][3],
        "protocol": {"id": MANIFEST["protocol_id"], "version": MANIFEST["protocol_version"],
                     "sha256": protocol_sha or tool.normalized_sha256(PROTOCOL), "grid_norm": [0.2, 0.4, 0.6, 0.8],
                     "presentation_ms": 2500, "settling_ms": 1000, "measurement_ms": 1500, "presentations_per_block": 32,
                     "blocks_planned": 3, "countdown_ms": 3000, "session_start_lead_ms": 100, "block_order": order,
                     "block_positions": positions, "case_suffixes": [c["case_suffix"] for c in CASES]},
        "blocks": blocks,
        "build": {"git_commit": build_commit, "git_dirty_at_build": dirty, "source_base": "e8fa01ef1e397f508aa5a3b3a595539b4ef4490a",
                  "source_base_is_ancestor": "true", "build_type": "debug"},
        "device": {"manufacturer": "synthetic", "model": device_model, "api_level": 35, "android_release": "15"},
        "display": {"basis": "WindowManager.maximumWindowMetrics bounds (calibration basis)", "width_px": W, "height_px": H,
                    "rotation": 0, "density": 2.75},
        "calibration": {"calibration_id": calibration_id, "anchors": 27, "verified_snapshot_refit": True,
                        "evaluation_sessions_on_this_calibration_before": 0, "calibration_inputs_sha256": sha,
                        "duration_ms": 85_000, "movement_retries": 1, "fit_error": 0.05},
        "predictor": {"kind": "GazeModel", "calibration_id": calibration_id},
        "conditions": {"viewing_distance_cm": 40.0, "lighting": "synthetic", "glasses": "none", "head_position_markers": "synthetic"},
        "pipeline": {"variant": tool.PIPELINE_VARIANT, "frame_interval_min_ms": 66},
        "counters": {"attempts_accepted": seq, "attempts_unrecorded": 0, "valid_attempts": valid_count,
                     "frames_received": seq * 2, "frames_cadence_skipped": seq,
                     "unlogged_results_dropped_pre_session": 0, "unlogged_results_dropped_in_session": 0} if finalized else None,
        "logging": {"attempt_rows_written": seq, "event_rows_written": len(out_events), "rows_lost_total": 0, "write_error": False}
        if finalized else None,
    }
    if meta_patch:
        meta_patch(meta)
    (d / "metadata.json").write_bytes((json.dumps(meta, indent=2) + "\n").encode())
    if checksums and finalized:
        names = ("attempts.csv", "events.csv", "metadata.json", "model_snapshot.json")
        (d / "SHA256SUMS").write_bytes("".join(f"{hashlib.sha256((d / n).read_bytes()).hexdigest()}  {n}\n"
                                               for n in names).encode())
    return d


class Base(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.raw = self.root / "raw"
        self.raw.mkdir()

    def tearDown(self):
        self._tmp.cleanup()

    def analyze(self, path=None, out="out", label=None):
        return tool.run(path or self.raw, self.root / out, PROTOCOL, label)

    def session(self, summary, sid):
        return next(s for s in summary["sessions"] if s["session_id"] == sid)

    @staticmethod
    def criterion(block, cid):
        return next(c for c in block["criteria"] if c["id"] == cid)

    @staticmethod
    def block(session, position):
        return next(b for b in session["blocks"] if b["head_position"] == position)


class ManifestTest(unittest.TestCase):
    def test_predeclared_thresholds_are_the_iteration3_values(self):
        p = tool.load_protocol(PROTOCOL)
        self.assertEqual(p.success, {"max_mean_error_norm": 0.10, "min_valid_coverage": 0.80, "min_valid_samples": 10,
                                     "requires_complete_presentation": True})
        self.assertEqual([(c["id"], c["comparison"], c["threshold"]) for c in p.criteria],
                         [("successful_presentation_rate", ">=", 0.80), ("p95_presentation_error", "<=", 0.20),
                          ("mean_jitter_rms", "<=", 0.05), ("tracking_availability", ">=", 0.90),
                          ("valid_sample_rate", ">=", 10.0), ("p95_processing_latency", "<=", 200.0)])
        self.assertEqual((p.min_valid_samples, p.min_valid_coverage, p.min_eligible_presentations), (10, 0.5, 26))
        self.assertEqual((p.planned, p.total_ms, p.blocks, p.countdown_ms), (32, 80_000, 3, 3000))
        self.assertEqual((p.version, p.presentation_ms, p.settling_ms, p.measurement_ms), ("1.0.0", 2500, 1000, 1500))
        self.assertEqual(p.orders, {"nhl": ("neutral", "higher", "lower"), "lhn": ("lower", "higher", "neutral")})
        self.assertEqual(p.calibration_ids, (A, B))
        self.assertEqual({k: (v["calibration_id"], v["block_order"]) for k, v in p.plan_official.items()},
                         {("ANNA", "A1"): (A, "nhl"), ("ANNA", "A2"): (A, "lhn"), ("ANNA", "B1"): (B, "nhl"), ("ANNA", "B2"): (B, "lhn")})
        self.assertIn(("PILOT01", "T1"), p.plan_pilot)

    def test_cases_and_rules_are_those_of_protocol_2_0_0(self):
        ref = json.loads((REFERENCE / "protocol.json").read_text(encoding="utf-8"))
        self.assertEqual([{**c, "case_id": c["case_suffix"]} for c in CASES],
                         [{**c, "case_suffix": c["case_id"]} for c in ref["cases"]])
        self.assertEqual(MANIFEST["data_sufficiency"]["min_eligible_fraction_for_session_spatial_criteria"],
                         ref["data_sufficiency"]["min_eligible_fraction_for_session_spatial_criteria"])
        self.assertEqual([{k: c[k] for k in ("id", "comparison", "threshold", "unit")} for c in MANIFEST["performance_criteria"]["block_criteria"]],
                         [{k: c[k] for k in ("id", "comparison", "threshold", "unit")} for c in ref["performance_criteria"]["session_criteria"]])

    def test_hash_ignores_crlf_conversion(self):
        with tempfile.TemporaryDirectory() as d:
            crlf = Path(d) / "protocol.json"
            crlf.write_bytes(PROTOCOL.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
            self.assertEqual(tool.normalized_sha256(crlf), tool.normalized_sha256(PROTOCOL))


class StatisticsTest(unittest.TestCase):
    def test_nearest_rank_p95_and_basic_stats(self):
        self.assertEqual(tool.nearest_rank([float(i) for i in range(1, 21)], 95), 19.0)
        self.assertEqual(tool.nearest_rank([float(i) for i in range(1, 33)], 95), 31.0)
        self.assertEqual(tool.nearest_rank([7.0], 95), 7.0)
        self.assertIsNone(tool.nearest_rank([], 95))
        self.assertEqual(tool.mean([1.0, 2.0, 6.0]), 3.0)
        self.assertEqual(tool.median([4.0, 1.0, 3.0, 2.0]), 2.5)

    def test_spatial_error_pixels_bias_and_jitter_known_values(self):
        m = tool.spatial_metrics([(0.23, 0.44)], (0.2, 0.4), (1000, 2000))
        self.assertAlmostEqual(m["mean_error"]["norm"], math.sqrt(0.03 ** 2 + 0.04 ** 2))
        self.assertAlmostEqual(m["mean_error"]["px"], math.sqrt(30.0 ** 2 + 80.0 ** 2))
        a = 0.01
        pts = [(0.5 + a, 0.5 + a), (0.5 - a, 0.5 + a), (0.5 + a, 0.5 - a), (0.5 - a, 0.5 - a)]
        self.assertAlmostEqual(tool.spatial_metrics(pts, (0.5, 0.5), (1000, 2000))["jitter_rms"]["norm"], math.sqrt(2) * a)


class GoldenKotlinSessionTest(Base):
    def test_analyzer_reads_the_kotlin_logger_output(self):
        dest = self.raw / B / "SYNTHETIC" / "eval-synthetic-0001"
        shutil.copytree(GOLDEN, dest)
        s = self.analyze()
        self.assertEqual(s["rejected_sessions"], [])
        g = self.session(s, "eval-synthetic-0001")
        self.assertEqual((g["checksums"], g["integrity_error_count"], g["issue_count"]), ("verified", 0, 0), g["integrity_errors"])
        self.assertEqual((g["final_state"], g["verdict"], g["calibration_id"], g["block_order"]), ("incomplete", tool.INCOMPLETE, B, "nhl"))
        self.assertEqual([(b["head_position"], b["state"]) for b in g["blocks"]],
                         [("neutral", "incomplete"), ("higher", "not_started"), ("lower", "not_started")])
        self.assertEqual(g["counts"]["pipeline_resets_by_reason"], {"block_start": 1, "session_start": 1})
        self.assertEqual(g["counts"]["other_events"], {"camera_error": 1, "preparation_shown": 1, "ready": 1, "unlogged_result_dropped": 1})
        self.assertEqual(g["counts"]["attempts_by_outcome"]["discarded_block_start"], 1)
        first = g["blocks"][0]
        excluded = first["excluded_phases"]
        self.assertEqual(excluded["countdown_by_outcome"]["discarded_block_start"], 1)
        self.assertEqual(g["counts"]["attempts_by_phase"]["preparation"], excluded["preparation_attempts"])
        self.assertEqual(first["counts"]["attempts"], g["counts"]["attempts_by_phase"]["settling"] + g["counts"]["attempts_by_phase"]["measurement"])
        self.assertTrue(all(c["status"] == tool.INCOMPLETE for b in g["blocks"] for c in b["criteria"]))
        self.assertFalse(g["official"])
        self.assertIsNotNone(first["face_inspection"]["measurement_valid"]["face_y"])


class BlockMetricsTest(Base):
    def test_clean_session_meets_every_block(self):
        write_session(self.raw)
        s = self.session(self.analyze(), "eval-b")
        self.assertEqual(s["verdict"], tool.MET, [b["criteria"] for b in s["blocks"]])
        self.assertEqual([b["verdict"] for b in s["blocks"]], [tool.MET] * 3)
        for b in s["blocks"]:
            self.assertEqual(self.criterion(b, "successful_presentation_rate")["value"], 1.0)
            self.assertAlmostEqual(b["diagnostics"]["block_mean_error"]["norm"], math.hypot(0.01, 0.02))
            self.assertAlmostEqual(self.criterion(b, "valid_sample_rate")["value"], 640 / 48)
            self.assertEqual(len(b["presentations"]), 32)
        self.assertTrue(s["official"], s["official_exclusions"])
        self.assertEqual([p["case_id"] for p in s["blocks"][1]["presentations"][:2]], ["higher-pass1-r0c0", "higher-pass1-r0c1"])

    def test_one_failing_position_fails_the_session_and_stays_visible(self):
        write_session(self.raw, rows=lambda b, tr, ph, k, t: {"point": (min(t[0] + (0.3 if b == 1 else 0.01), 1.0), t[1])})
        s = self.session(self.analyze(), "eval-b")
        self.assertEqual([(b["head_position"], b["verdict"]) for b in s["blocks"]],
                         [("neutral", tool.MET), ("higher", tool.NOT_MET), ("lower", tool.MET)])
        self.assertEqual(s["verdict"], tool.NOT_MET)
        self.assertIn("higher", s["verdict_reason"])
        summary = self.analyze(out="o2")
        by_position = {p["head_position"]: p["verdict"] for p in summary["groups"][0]["participants"][0]["by_position"]}
        self.assertEqual(by_position, {"neutral": tool.MET, "higher": tool.NOT_MET, "lower": tool.MET})
        report = (self.root / "o2" / "report.md").read_text(encoding="utf-8")
        self.assertIn("| higher | 1 | NOT_MET |", report)

    def test_preparation_and_countdown_never_enter_block_metrics(self):
        write_session(self.raw, prep_rows=40, countdown_rows=5,
                      prep_spec=lambda block, phase, j: {"quality": "lost", "latency": 900.0, "detector": "no_face"})
        s = self.session(self.analyze(), "eval-b")
        self.assertEqual(s["verdict"], tool.MET)
        for b in s["blocks"]:
            self.assertEqual(self.criterion(b, "tracking_availability")["value"], 1.0)
            self.assertEqual(self.criterion(b, "p95_processing_latency")["value"], 40.0)
            self.assertEqual(self.criterion(b, "p95_processing_latency")["n"], 32 * 30)
            self.assertEqual(b["counts"]["measurement_attempts"], 640)
            self.assertEqual((b["excluded_phases"]["preparation_attempts"], b["excluded_phases"]["countdown_attempts"]), (40, 5))
            self.assertEqual(b["excluded_phases"]["preparation_valid"], 0)
        self.assertEqual(s["counts"]["attempts_by_phase"]["preparation"], 120)

    def test_eligibility_is_separate_from_success_within_a_block(self):
        def rows(block, trial, phase, k, t):
            if block != 2 or phase == "settling":
                return {"point": t}
            if trial == 0:
                return {"point": t} if k < 12 else {"quality": "closed", "feature_rejection": "eye_33_133_aperture_ratio_lt_0.10"}
            if trial == 1:
                return {"point": (t[0] + 0.11, t[1])}
            if trial == 2:
                return {"point": (t[0] + 0.09, t[1])} if k < 17 else {"quality": "out_of_range", "prediction_rejection": "face_x_shift_gt_0.12"}
            if trial == 3:
                return {"point": t} if k < 9 else {"outcome": "discarded_epoch"}
            if trial == 4:
                return None
            return {"point": t}
        write_session(self.raw, rows=rows)
        s = self.session(self.analyze(), "eval-b")
        lower = self.block(s, "lower")
        p = lower["presentations"]
        self.assertEqual([(x["eligibility"], x["successful"]) for x in p[:5]],
                         [("eligible", False), ("eligible", False), ("eligible", True), ("insufficient_data", False),
                          ("insufficient_data", False)])
        self.assertEqual(p[0]["measurement_invalid_by_detail"], {"closed:eye_33_133_aperture_ratio_lt_0.10": 8})
        self.assertEqual(p[2]["measurement_invalid_by_detail"], {"out_of_range:face_x_shift_gt_0.12": 3})
        self.assertEqual(p[3]["measurement_invalid_by_reason"], {"discarded_epoch": 11})
        self.assertEqual(self.criterion(lower, "successful_presentation_rate")["value"], 28 / 32)
        self.assertAlmostEqual(self.criterion(lower, "valid_sample_rate")["value"], lower["counts"]["valid_measurement_samples"] / 48)
        self.assertEqual(self.criterion(self.block(s, "neutral"), "successful_presentation_rate")["value"], 1.0)

    def test_p95_uses_presentation_means_and_is_recomputed_when_pooled(self):
        def first(block, tr, ph, k, t):
            return {"point": (t[0] + 0.01, t[1])}

        def second(block, tr, ph, k, t):
            return {"point": (t[0] + (0.19 if tr >= 30 and block == 2 else 0.01), t[1])}
        write_session(self.raw, sid="eval-1", label="B1", order="nhl", rows=first)
        write_session(self.raw, sid="eval-2", label="B2", order="lhn", rows=second)  # lhn: block 2 is neutral
        s = self.analyze()
        neutral2 = self.block(self.session(s, "eval-2"), "neutral")
        self.assertAlmostEqual(self.criterion(neutral2, "p95_presentation_error")["value"], 0.19)
        self.assertAlmostEqual(neutral2["diagnostics"]["pooled_sample_errors"]["p95"], 0.19)
        pooled = next(p for p in s["groups"][0]["participants"][0]["by_position"] if p["head_position"] == "neutral")
        self.assertEqual(pooled["blocks"], 2)
        self.assertAlmostEqual(pooled["descriptive"]["p95_presentation_error_pooled"], 0.01)  # 61st of 64, not an average
        self.assertEqual(pooled["verdict"], tool.MET)

    def test_spatial_criteria_need_enough_eligible_presentations_per_block(self):
        write_session(self.raw, rows=lambda b, tr, ph, k, t: {"point": t} if b != 0 or tr >= 7 or ph == "settling" or k < 5
                      else {"quality": "lost"})
        s = self.session(self.analyze(), "eval-b")
        neutral = self.block(s, "neutral")
        for cid in ("p95_presentation_error", "mean_jitter_rms"):
            c = self.criterion(neutral, cid)
            self.assertEqual(c["status"], tool.INSUFFICIENT); self.assertIn("25 technically eligible", c["reason"])
        self.assertEqual(neutral["verdict"], tool.NOT_MET)  # the success-rate criterion fails outright
        self.assertEqual(self.block(s, "higher")["verdict"], tool.MET)

    def test_availability_includes_invalid_attempts_and_equality_passes(self):
        write_session(self.raw, rows=lambda b, tr, ph, k, t: {"point": t} if k < 18 else {"quality": "out_of_range"})
        s = self.session(self.analyze(), "eval-b")
        for b in s["blocks"]:
            c = self.criterion(b, "tracking_availability")
            self.assertEqual((c["value"], c["n"], c["status"]), (0.9, 640, tool.MET))

    def test_latency_counts_invalid_and_discarded_block_attempts_including_settling(self):
        def rows(block, trial, phase, k, t):
            if trial % 8 == 0 and phase == "measurement" and k < 4:
                return {"outcome": "discarded_delayed", "latency": 300.0}
            if trial == 5 and phase == "settling" and k == 0:
                return {"outcome": "processing_error"}
            return {"point": t, "latency": 30.0}
        write_session(self.raw, rows=rows)
        c = self.criterion(self.block(self.session(self.analyze(), "eval-b"), "neutral"), "p95_processing_latency")
        self.assertEqual((c["n"], c["value"], c["status"]), (32 * 30 - 1, 30.0, tool.MET))

    def test_missing_coordinates_are_never_zero_error(self):
        write_session(self.raw, rows=lambda b, tr, ph, k, t: {"quality": "lost"} if tr == 0 and k < 3 else {"point": (t[0] + 0.05, t[1])})
        s = self.session(self.analyze(), "eval-b")
        p0 = self.block(s, "neutral")["presentations"][0]
        self.assertAlmostEqual(p0["final"]["mean_error"]["norm"], 0.05)
        self.assertEqual(p0["valid_samples"], 17)

    def test_model_clamp_is_reported_and_the_measured_coordinate_keeps_it(self):
        write_session(self.raw, rows=lambda b, tr, ph, k, t: {"linear": (1.4, t[1]), "point": (1.0, t[1])} if tr == 3 else {"point": t})
        p3 = self.block(self.session(self.analyze(), "eval-b"), "higher")["presentations"][3]
        self.assertAlmostEqual(p3["final"]["mean_error"]["norm"], 0.2)
        self.assertAlmostEqual(p3["linear_diagnostic"]["mean_error"]["norm"], 0.6)
        self.assertEqual((p3["model_clamp_active_samples"], p3["final"]["off_screen_samples"]), (20, 0))

    def test_face_position_is_summarized_per_block_for_inspection_only(self):
        write_session(self.raw, faces={"neutral": 0.5, "higher": 0.46, "lower": 0.54})
        s = self.session(self.analyze(), "eval-b")
        medians = {b["head_position"]: b["face_inspection"]["measurement_valid"]["face_y"]["median"] for b in s["blocks"]}
        self.assertEqual(medians, {"neutral": 0.5, "higher": 0.46, "lower": 0.54})
        self.assertEqual(s["verdict"], tool.MET)  # never a gate
        self.assertEqual(s["calibration_reference_face"]["reference_face_y"], 0.5)


class SessionStateTest(Base):
    def test_incomplete_session_cannot_pass_and_shows_each_block(self):
        write_session(self.raw, sid="eval-i", final_state="incomplete", end_reason="back", reached=(2, 10))
        s = self.analyze()
        i = self.session(s, "eval-i")
        self.assertEqual(i["verdict"], tool.INCOMPLETE)
        self.assertEqual([(b["state"], b["presentations_completed"]) for b in i["blocks"]],
                         [("complete", 32), ("incomplete", 10), ("not_started", 0)])
        self.assertTrue(all(c["status"] == tool.INCOMPLETE and c["partial"] for b in i["blocks"] for c in b["criteria"]))
        self.assertEqual([p["eligibility"] for p in i["blocks"][1]["presentations"][9:12]],
                         ["eligible", "incomplete_presentation", "incomplete_presentation"])
        self.assertEqual(s["incomplete_sessions"][0]["session_id"], "eval-i")
        self.assertEqual(s["groups"][0]["participants"][0]["verdict"], tool.INCOMPLETE)
        self.assertEqual(i["integrity_error_count"], 0, i["integrity_errors"])

    def test_interrupted_unfinalized_session_is_listed(self):
        write_session(self.raw, sid="eval-x", final_state="incomplete", end_reason="in_progress_or_interrupted", reached=(1, 4))
        s = self.session(self.analyze(), "eval-x")
        self.assertEqual((s["verdict"], s["checksums"]), (tool.INCOMPLETE, "absent"))
        self.assertEqual(s["blocks"][0]["presentations_completed"], 4)

    def test_logging_loss_and_unlogged_results_mean_insufficient_data(self):
        write_session(self.raw, sid="eval-l", meta_patch=lambda m: m["logging"].update(rows_lost_total=2))
        write_session(self.raw, sid="eval-u", meta_patch=lambda m: m["counters"].update(unlogged_results_dropped_in_session=1))
        s = self.analyze()
        for sid, text in (("eval-l", "logging loss"), ("eval-u", "not logged as attempts")):
            x = self.session(s, sid)
            self.assertEqual(x["verdict"], tool.INSUFFICIENT)
            self.assertEqual({b["verdict"] for b in x["blocks"]}, {tool.INSUFFICIENT})
            self.assertTrue(any(text in e for e in x["integrity_errors"]))

    def test_missing_resets_are_integrity_problems(self):
        write_session(self.raw, sid="eval-n", session_reset=False)
        write_session(self.raw, sid="eval-b", block_resets=False)
        s = self.analyze()
        self.assertTrue(any("session_start" in e for e in self.session(s, "eval-n")["integrity_errors"]))
        self.assertTrue(any("block_start reset" in e for e in self.session(s, "eval-b")["integrity_errors"]))

    def test_timeline_not_logged_phase_assigns_samples(self):
        write_session(self.raw, rows=lambda b, tr, ph, k, t: {"point": t, "logged_phase": "settling" if tr == 2 and ph == "measurement" else ph})
        s = self.session(self.analyze(), "eval-b")
        self.assertEqual(s["blocks"][0]["presentations"][2]["measurement_attempts"], 20)
        self.assertTrue(any("disagrees with the timeline" in e for e in s["integrity_errors"]))

    def test_changed_raw_file_fails_checksum(self):
        d = write_session(self.raw)
        with open(d / "attempts.csv", "ab") as f:
            f.write(b"\n")
        s = self.session(self.analyze(), "eval-b")
        self.assertEqual((s["checksums"], s["verdict"]), ("mismatch", tool.INSUFFICIENT))

    def test_other_protocols_schemas_variants_candidates_and_timelines_are_rejected(self):
        write_session(self.raw, sid="eval-p", protocol_sha="0" * 64)
        write_session(self.raw, sid="eval-3", schema="3")
        write_session(self.raw, sid="eval-v", meta_patch=lambda m: m["pipeline"].update(variant="home_screen_candidate"))
        write_session(self.raw, sid="eval-c", calibration_id="calibration_nine_point")
        write_session(self.raw, sid="eval-t", meta_patch=lambda m: m["blocks"][1].update(block_start_uptime_ms=m["blocks"][1]["block_start_uptime_ms"] + 1))
        write_session(self.raw, sid="eval-o", meta_patch=lambda m: m["protocol"].update(block_order="hnl"))
        write_session(self.raw, sid="eval-ok")
        s = self.analyze()
        self.assertEqual([x["session_id"] for x in s["sessions"]], ["eval-ok"])
        errors = {r["path"].split("/")[-1]: r["error"] for r in s["rejected_sessions"]}
        self.assertIn("protocol sha256", errors["eval-p"])
        self.assertIn("never pooled", errors["eval-3"]); self.assertIn("never pooled", errors["eval-v"])
        self.assertIn("not a declared candidate", errors["eval-c"])
        self.assertIn("countdown and block duration", errors["eval-t"])
        self.assertIn("not declared", errors["eval-o"])

    def test_missing_files_are_rejected_not_guessed(self):
        (self.raw / B / "ANNA" / "broken").mkdir(parents=True)
        (self.raw / B / "ANNA" / "broken" / "attempts.csv").write_text("x\n")
        self.assertIn("missing metadata.json", self.analyze()["rejected_sessions"][0]["error"])


class OfficialityAndAggregationTest(Base):
    def test_plan_pilot_reuse_dirty_and_mode_rules(self):
        write_session(self.raw, sid="eval-1", label="B1", calibration_sha="d" * 64)
        write_session(self.raw, sid="eval-2", label="B2", order="lhn", calibration_sha="d" * 64)
        write_session(self.raw, sid="eval-3", label="B2", order="nhl")             # B2 planned with lhn
        write_session(self.raw, sid="eval-4", participant="PILOT01", label="TB1")  # candidate B's technical pilot
        write_session(self.raw, sid="eval-5", label="B9")                          # not in the plan
        write_session(self.raw, sid="eval-6", label="B1", dirty="true")
        write_session(self.raw, sid="eval-7", label="B1", calibration_id=A)       # sibling calibration labelled B1
        write_session(self.raw, sid="eval-8", label="B2", order="lhn")            # the planned B2: official
        s = self.analyze()
        ex = {x["session_id"]: x["official_exclusions"] for x in s["sessions"]}
        self.assertIn("calibration shared with eval-2", ex["eval-1"])
        self.assertTrue(any("differs from the collection plan" in e for e in ex["eval-3"]))
        self.assertTrue(any("technical pilot" in e for e in ex["eval-4"]))
        self.assertTrue(any("not in the collection plan" in e for e in ex["eval-5"]))
        self.assertIn("build dirty flag is 'true'", ex["eval-6"])
        self.assertTrue(any("differs from the collection plan" in e for e in ex["eval-7"]))
        self.assertEqual(ex["eval-8"], [])
        self.assertEqual(self.session(s, "eval-1")["verdict"], tool.MET)  # verdicts are unaffected

    def test_candidates_modes_and_builds_are_never_pooled(self):
        write_session(self.raw, sid="eval-1")
        write_session(self.raw, sid="eval-2", calibration_id=A, label="A1")
        write_session(self.raw, sid="eval-3", mode="live_preview")
        write_session(self.raw, sid="eval-4", build_commit="c" * 40)
        groups = self.analyze()["groups"]
        self.assertEqual(sorted(len(g["sessions"]) for g in groups), [1, 1, 1, 1])
        self.assertEqual({g["calibration_id"] for g in groups}, {A, B})
        self.assertTrue(next(g for g in groups if g["mode"] == "live_preview")["role"].startswith("secondary"))

    def test_one_failing_session_fails_the_participant(self):
        write_session(self.raw, sid="eval-1")
        write_session(self.raw, sid="eval-2", label="B2", order="lhn", rows=lambda b, tr, ph, k, t: {"point": (min(t[0] + 0.3, 1.0), t[1])})
        self.assertEqual(self.analyze()["groups"][0]["participants"][0]["verdict"], tool.NOT_MET)

    def test_no_sessions_is_not_measured(self):
        s = self.analyze()
        self.assertEqual((s["sessions"], s["groups"]), ([], []))
        self.assertIn("NOT_MEASURED", (self.root / "out" / "report.md").read_text(encoding="utf-8"))


class OutputTest(Base):
    def test_outputs_are_deterministic_and_raw_data_is_untouched(self):
        write_session(self.raw, sid="eval-1")
        write_session(self.raw, sid="eval-2", label="B2", order="lhn", final_state="incomplete", end_reason="backgrounded", reached=(1, 5))
        before = {p: p.read_bytes() for p in self.raw.rglob("*") if p.is_file()}
        self.analyze(out="o1"); self.analyze(out="o2")
        for name in tool.OUTPUT_FILES:
            self.assertEqual((self.root / "o1" / name).read_bytes(), (self.root / "o2" / name).read_bytes(), name)
            self.assertNotIn(b"\r\n", (self.root / "o1" / name).read_bytes())
        self.assertEqual(before, {p: p.read_bytes() for p in self.raw.rglob("*") if p.is_file()})
        json.loads((self.root / "o1" / "summary.json").read_text(encoding="utf-8"))
        presentations = list(csv.DictReader(io.StringIO((self.root / "o1" / "presentations.csv").read_text(encoding="utf-8"))))
        self.assertEqual(len(presentations), 2 * 96)
        self.assertEqual(len({(r["session_id"], r["case_id"]) for r in presentations}), 2 * 96)
        blocks = list(csv.DictReader(io.StringIO((self.root / "o1" / "blocks.csv").read_text(encoding="utf-8"))))
        self.assertEqual([(r["session_id"], r["head_position"]) for r in blocks][:3],
                         [("eval-1", "neutral"), ("eval-1", "higher"), ("eval-1", "lower")])

    def test_output_inside_raw_data_is_refused_and_readme_is_never_written(self):
        write_session(self.raw)
        with self.assertRaises(SystemExit):
            tool.run(self.raw, self.raw / "derived", PROTOCOL, None)
        out = self.root / "derived"; out.mkdir()
        (out / "README.md").write_text("human text\n", encoding="utf-8")
        tool.run(self.raw, out, PROTOCOL, None)
        self.assertEqual((out / "README.md").read_text(encoding="utf-8"), "human text\n")
        self.assertEqual(sorted(p.name for p in out.iterdir()), sorted(("README.md",) + tool.OUTPUT_FILES))

    def test_selection_by_session_id(self):
        write_session(self.raw, sid="eval-1")
        write_session(self.raw, sid="eval-2", label="B2", order="lhn")
        manifest = self.root / "analysis-inputs.json"
        manifest.write_text(json.dumps({"included": [{"session_id": "eval-2"}]}), encoding="utf-8")
        s = tool.run(self.raw, self.root / "sel", PROTOCOL, None, manifest)
        self.assertEqual([x["session_id"] for x in s["sessions"]], ["eval-2"])
        self.assertEqual(s["input_selection"]["excluded_by_selection"], 1)

    def test_cli_exit_codes(self):
        write_session(self.raw)
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(tool.main([str(self.raw), "--out", str(self.root / "cli")]), 0)
            write_session(self.raw, sid="eval-z", protocol_sha="1" * 64)
            self.assertEqual(tool.main([str(self.raw), "--out", str(self.root / "cli2")]), 1)
            self.assertEqual(tool.main([str(self.raw), "--out", str(self.root / "cli3"), "--protocol", str(self.root / "none.json")]), 2)


class CandidateBPlanTest(Base):
    """Candidate B is evaluated under the unchanged protocol 1.0.0 and the prospectively committed collection-plan-b.json."""

    @staticmethod
    def entries(rows):
        return {(e["participant_id"], e["session_label"]): (e["calibration_id"], e["block_order"]) for e in rows}

    def test_protocol_and_plan_b_agree(self):
        p = tool.load_protocol(PROTOCOL)
        self.assertIn(B, p.calibration_ids)
        self.assertEqual(PLAN_B["calibration_id"], B)
        self.assertEqual((PLAN_B["protocol"]["protocol_id"], PLAN_B["protocol"]["protocol_version"]), (p.protocol_id, p.version))
        self.assertEqual(PLAN_B["protocol"]["manifest_sha256"], p.sha256)  # the same manifest as candidate A's build
        protocol_b = {k: (v["calibration_id"], v["block_order"]) for k, v in p.plan_official.items() if v["calibration_id"] == B}
        self.assertEqual(self.entries(PLAN_B["official"]), protocol_b)
        self.assertEqual(protocol_b, {("ANNA", "B1"): (B, "nhl"), ("ANNA", "B2"): (B, "lhn")})
        pilot = self.entries(PLAN_B["technical_pilot"])
        self.assertEqual(pilot, {("PILOT01", "TB1"): (B, "nhl")})
        self.assertTrue(all(pid.upper().startswith(tool.PILOT_PREFIX) for pid, _ in pilot))
        self.assertFalse(set(pilot) & set(p.plan_official))

    def test_planned_b_sessions_are_official_and_the_b_pilot_is_not(self):
        write_session(self.raw, sid="eval-b1", label="B1", order="nhl")
        write_session(self.raw, sid="eval-b2", label="B2", order="lhn")
        write_session(self.raw, sid="eval-tb1", participant="PILOT01", label="TB1", order="nhl")
        s = self.analyze()
        ex = {x["session_id"]: x["official_exclusions"] for x in s["sessions"]}
        self.assertEqual((ex["eval-b1"], ex["eval-b2"]), ([], []))
        self.assertEqual(ex["eval-tb1"], ["technical pilot (PILOT* participant); never official"])
        self.assertEqual({x["calibration_id"] for x in s["sessions"]}, {B})
        self.assertTrue(all(x["verdict"] == tool.MET for x in s["sessions"]))  # verdicts never depend on officiality


class CandidateEquivalenceTest(Base):
    """Equivalent SYNTHETIC inputs give identical metrics and verdicts whichever candidate's calibration recorded them.

    Only identity (calibration ID, labels, group, officiality bookkeeping) may differ; the per-block code never looks at
    the candidate.
    """

    IDENTITY = {"calibration_id", "session_label", "path", "compatibility_group", "calibration", "official",
                "official_exclusions"}

    def both(self, sid, **kwargs):
        label = {"nhl": "1", "lhn": "2"}[kwargs.get("order", "nhl")]
        write_session(self.root / "raw-b", sid=sid, label="B" + label, calibration_id=B, **kwargs)
        write_session(self.root / "raw-a", sid=sid, label="A" + label, calibration_id=A, **kwargs)

    def compare(self):
        sa = tool.run(self.root / "raw-a", self.root / "out-a", PROTOCOL, None)
        sb = tool.run(self.root / "raw-b", self.root / "out-b", PROTOCOL, None)
        self.assertEqual(len(sa["sessions"]), len(sb["sessions"]))
        self.assertTrue(sb["sessions"])
        for xa, xb in zip(sa["sessions"], sb["sessions"]):
            self.assertEqual((xa["calibration_id"], xb["calibration_id"]), (A, B))
            self.assertEqual({k: v for k, v in xa.items() if k not in self.IDENTITY},
                             {k: v for k, v in xb.items() if k not in self.IDENTITY}, xb["session_id"])
            self.assertEqual(xa["official"], xb["official"])
        for ga, gb in zip(sa["groups"], sb["groups"]):
            self.assertEqual(ga["participants"], gb["participants"])
        return sa, sb

    def test_identical_metrics_verdicts_and_aggregates_for_a_and_b(self):
        self.both("eval-clean")
        self.both("eval-fail", order="lhn", rows=lambda b, tr, ph, k, t: {"point": (min(t[0] + (0.3 if b == 1 else 0.01), 1.0), t[1])})
        self.both("eval-mixed", rows=lambda b, tr, ph, k, t: {"point": (t[0] + 0.02 * (tr % 5), t[1] - 0.01 * b)} if (k + tr + b) % 7
                  else {"quality": "out_of_range", "prediction_rejection": "face_y_shift_gt_0.12"},
                  faces={"neutral": 0.5, "higher": 0.46, "lower": 0.54})
        self.both("eval-short", final_state="incomplete", end_reason="back", reached=(2, 10))
        self.both("eval-live", mode="live_preview")
        sa, sb = self.compare()
        verdicts = {x["session_id"]: x["verdict"] for x in sb["sessions"]}
        self.assertEqual(verdicts["eval-short"], tool.INCOMPLETE)
        self.assertEqual(verdicts["eval-fail"], tool.NOT_MET)
        self.assertEqual(verdicts["eval-clean"], tool.MET)

    def test_the_kotlin_golden_session_under_either_identity(self):
        # The B golden session, and the same files relabelled as candidate A (as A's build writes them).
        shutil.copytree(GOLDEN, self.root / "raw-b" / B / "SYNTHETIC" / "eval-synthetic-0001")
        dest = self.root / "raw-a" / A / "SYNTHETIC" / "eval-synthetic-0001"
        shutil.copytree(GOLDEN, dest)
        for name in ("metadata.json", "model_snapshot.json"):
            text = (dest / name).read_text(encoding="utf-8").replace(B, A)
            for position in ("ordinary", "higher", "lower"):
                text = text.replace(f'"calibration_position": "{position}"', '"calibration_position": "same"')
            text = text.replace('["ordinary", "higher", "lower"]', '["same", "same", "same"]')
            (dest / name).write_bytes(text.encode("utf-8"))
        names = ("attempts.csv", "events.csv", "metadata.json", "model_snapshot.json")
        (dest / "SHA256SUMS").write_bytes("".join(f"{hashlib.sha256((dest / n).read_bytes()).hexdigest()}  {n}\n"
                                                  for n in names).encode())
        sa, sb = self.compare()
        self.assertEqual(sb["sessions"][0]["checksums"], "verified")
        self.assertEqual(sb["sessions"][0]["integrity_error_count"], 0)


def _load_reference():
    spec = importlib.util.spec_from_file_location("reference_iter3_analyzer", REFERENCE / "analyze_gaze_eval.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ReferenceRegressionTest(unittest.TestCase):
    """The per-block metric code reproduces the Iteration 3 per-session calculations exactly.

    Identical SYNTHETIC attempts are analyzed by the frozen Iteration 3 analyzer (testdata/reference_iter3, protocol
    2.0.0) and by analyze_block; every presentation, criterion value and status and every shared diagnostic must be equal.
    """

    @classmethod
    def setUpClass(cls):
        cls.ref = _load_reference()
        cls.ref_protocol = cls.ref.load_protocol(REFERENCE / "protocol.json")
        cls.protocol = tool.load_protocol(PROTOCOL)

    def attempts(self, rng: random.Random, completed: int):
        ref = self.ref
        out, seq = [], 0
        last = 32 if completed == 32 else completed + 1
        for trial in range(last):
            case = CASES[trial]
            target = (case["target_x_norm"], case["target_y_norm"])
            quality_bias = rng.choice((0.0, 0.05, 0.3, 0.9))
            error_scale = rng.choice((0.005, 0.03, 0.08, 0.2))
            # Half the presentations sit exactly on or next to the eligibility and success boundaries
            # (10 valid samples; coverage 0.5 and 0.8 of 20 measurement attempts).
            boundary = rng.choice((None, 9, 10, 11, 15, 16, 17, 20)) if rng.random() < 0.5 else None
            measurement_n = 20 if boundary is not None else rng.choice((0, 3, 9, 15, 22))
            for phase, n, offset in (("settling", rng.randint(0, 12), 0), ("measurement", measurement_n, 1000)):
                for k in range(n):
                    accepted = START + trial * 2500 + offset + k * (1490 // max(n, 1)) + 3
                    roll = rng.random()
                    if boundary is not None and phase == "measurement":
                        # Exactly [boundary] valid samples; the rest are invalid processed or discarded attempts.
                        kind = "valid" if k < boundary else ("discarded" if roll < 0.3 else "invalid")
                    else:
                        kind = "discarded" if roll < quality_bias * 0.2 else "invalid" if roll < quality_bias else "valid"
                    if kind == "discarded":
                        outcome = rng.choice(("discarded_closing", "discarded_model_changed", "processing_error"))
                        latency = None if outcome == "processing_error" else rng.uniform(5, 400)
                        out.append(ref.Attempt(seq, None, outcome, accepted, trial, phase, None, False, outcome, None,
                                               None, None, None, None, latency, None, None))
                        seq += 1
                        continue
                    outcome = "processed"
                    if kind == "invalid":
                        quality = rng.choice(("no_features", "prediction_rejected"))
                        out.append(ref.Attempt(seq, seq, outcome, accepted, trial, phase, quality, False, quality,
                                               rng.choice(("eye_33_133_aperture_ratio_lt_0.10", "face_x_shift_gt_0.12")),
                                               None, None, None, None, rng.uniform(5, 300), rng.uniform(5, 20), rng.uniform(0, 5)))
                    else:
                        linear = (target[0] + rng.gauss(0, error_scale), target[1] + rng.gauss(0, error_scale))
                        if rng.random() < 0.05:
                            linear = (linear[0] + 0.9, linear[1])
                        predicted = (min(max(linear[0], 0.0), 1.0), min(max(linear[1], 0.0), 1.0))
                        point = (min(max(predicted[0] + rng.gauss(0, 0.004), 0.0), 1.0), predicted[1])
                        rendered = (point[0] * W, point[1] * H) if rng.random() < 0.3 else None
                        out.append(ref.Attempt(seq, seq, outcome, accepted, trial, phase, "tracking", True, None, None,
                                               linear, predicted, point, rendered, rng.uniform(5, 300), rng.uniform(5, 20), rng.uniform(0, 5)))
                    seq += 1
        return out

    def test_block_metrics_equal_the_iteration3_session_metrics(self):
        rng = random.Random(20261115)
        compared = 0
        for case_no in range(40):
            completed = 32 if case_no % 4 else rng.randint(0, 31)
            attempts = self.attempts(rng, completed)
            end = START + (80_000 if completed == 32 else completed * 2500 + 700)
            meta = {"session_id": f"s{case_no}", "participant_id": "P01", "session_label": "S1", "mode": "blind_measurement",
                    "final_state": "complete" if completed == 32 else "incomplete", "end_reason": "completed" if completed == 32 else "back",
                    "start_uptime_ms": START, "end_uptime_ms": end, "display": {"width_px": W, "height_px": H, "rotation": 0},
                    "protocol": {"presentations_completed": completed, "sha256": self.ref_protocol.sha256},
                    "pipeline": {"variant": self.ref.PIPELINE_VARIANT}, "build": {}, "predictor": {}, "device": {}}
            session = self.ref.Session(Path("."), "synthetic", meta, attempts=attempts, checksums="verified")
            expected = self.ref.analyze_session(session, self.ref_protocol)
            actual = tool.analyze_block(attempts, self.protocol, START, completed, end, (W, H))
            for e, a in zip(expected["presentations"], actual["presentations"]):
                self.assertEqual(e["case_id"], a["case_suffix"])
                self.assertEqual({k: v for k, v in e.items() if k != "case_id"}, {k: v for k, v in a.items() if k != "case_suffix"})
            self.assertEqual(len(expected["presentations"]), len(actual["presentations"]))
            for e, a in zip(expected["criteria"], actual["criteria"]):
                if completed == 32:
                    self.assertEqual(e, a)
                else:  # the reference marks an incomplete session's criteria INCOMPLETE; values and counts are the same
                    self.assertEqual((e["id"], e["value"], e["n"]), (a["id"], a["value"], a["n"]))
            renamed = {"session_mean_error": "block_mean_error", "predicted_stream_session_mean_error": "predicted_stream_mean_error",
                       "linear_stream_session_mean_error": "linear_stream_mean_error"}
            for key, value in expected["diagnostics"].items():
                got = actual["diagnostics"][renamed.get(key, key)]
                if key == "latency_ms":
                    # Same distribution; this pipeline has more outcomes, the extra ones count zero here.
                    by_outcome = got["attempts_by_outcome"]
                    self.assertEqual(value["attempts_by_outcome"], {o: by_outcome[o] for o in value["attempts_by_outcome"]})
                    self.assertEqual(sum(by_outcome.values()), sum(value["attempts_by_outcome"].values()))
                    value = {k: v for k, v in value.items() if k != "attempts_by_outcome"}
                    got = {k: v for k, v in got.items() if k != "attempts_by_outcome"}
                self.assertEqual(value, got, key)
            for key in ("measurement_attempts", "valid_measurement_samples", "measurement_invalid_by_reason",
                        "measurement_invalid_by_detail", "settling_attempts", "settling_invalid_by_reason", "measurement_window_s"):
                self.assertEqual(expected["counts"][key], actual["counts"][key], key)
            self.assertEqual(expected["_eligible_means"], actual["_eligible_means"])
            compared += 1
        self.assertEqual(compared, 40)


if __name__ == "__main__":
    unittest.main()
