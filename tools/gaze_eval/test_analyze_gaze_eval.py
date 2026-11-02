"""Unit tests for analyze_gaze_eval (schema v3, home-screen candidate). Every session here is SYNTHETIC, not participant data.

Run: python -B -m unittest discover -s tools/gaze_eval -v
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
import math
import shutil
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

import analyze_gaze_eval as tool

REPO = Path(__file__).resolve().parents[2]
PROTOCOL = REPO / "docs" / "evaluation" / "iteration3" / "protocol.json"
GOLDEN = Path(__file__).resolve().parent / "testdata" / "synthetic_candidate_session"
MANIFEST = json.loads(PROTOCOL.read_text(encoding="utf-8"))
CASES = MANIFEST["cases"]
START = 1_000_000
W, H = 1080, 2400


def default_rows(trial, phase, k, target):
    """Valid processed sample 0.01 right of and 0.02 below the target."""
    return {"point": (target[0] + 0.01, target[1] + 0.02)}


def kotlin(v: float) -> str:
    """Kotlin Double.toString style for the magnitudes used here."""
    return repr(float(v)) if v == 0 or 1e-3 <= abs(v) < 1e7 else f"{v:.1E}".replace("E-0", "E-")


def clamp(p):
    return None if p is None else (min(max(p[0], 0.0), 1.0), min(max(p[1], 0.0), 1.0))


def write_session(root: Path, participant="P01", sid="eval-a", *, folder=None, label="S1", mode="blind_measurement",
                  final_state="complete", end_reason="completed", rows=default_rows, measurement_rows=20,
                  settling_rows=10, completed=32, build_commit="a" * 40, dirty="false", device_model="SM-TEST",
                  calibration_sha=None, meta_patch=None, checksums=True, protocol_sha=None, schema="3") -> Path:
    d = root / (folder or f"{participant}/{sid}")
    d.mkdir(parents=True)
    attempts = io.StringIO()
    w = csv.writer(attempts, lineterminator="\n")
    w.writerow(tool.ATTEMPT_COLUMNS)
    main = 1  # main_seq 0 is the run_start reset
    seq = 0
    valid_count = 0
    last_trial = completed if final_state == "complete" else completed + 1
    for trial in range(min(last_trial, 32)):
        case = CASES[trial]
        target = (case["target_x_norm"], case["target_y_norm"])
        onset = START + trial * 2500
        for phase, n, offset, step in (("settling", settling_rows, 3, 90), ("measurement", measurement_rows, 1003, 1490 // max(measurement_rows, 1))):
            for k in range(n):
                spec = rows(trial, phase, k, target)
                if spec is None:
                    continue
                outcome, quality = spec.get("outcome", "processed"), spec.get("quality", "tracking")
                processed = outcome == "processed"
                tracking = processed and quality == "tracking"
                point = spec.get("point") if tracking else None
                linear = spec.get("linear", point) if tracking else None
                predicted = spec.get("predicted", clamp(linear)) if tracking else None
                has_features = processed and quality in ("tracking", "prediction_rejected")
                valid = tracking and point is not None
                reason = "" if valid else (outcome if not processed else quality)
                accepted = onset + offset + step * k
                latency = spec.get("latency", 40.0 if outcome != "processing_error" else None)
                rendered = None if point is None or mode != "live_preview" else (point[0] * W, point[1] * H)
                main_cell = "" if outcome == "processing_error" else str(main)
                if outcome != "processing_error":
                    main += 1
                pair = lambda p: ("", "") if p is None else (kotlin(p[0]), kotlin(p[1]))
                cells = [schema, sid, str(seq), main_cell, outcome, str(accepted), str(accepted + 20), str(accepted - START),
                         spec.get("logged_phase", phase), str(trial // 16 + 1), str(trial), str(trial % 16), case["case_id"],
                         case["target_id"], kotlin(target[0]), kotlin(target[1]),
                         spec.get("detector", "face_detected"), spec.get("feature_rejection", "")]
                cells += ["0.01"] * len(tool.FEATURE_COLUMNS) if has_features else [""] * len(tool.FEATURE_COLUMNS)
                cells += [quality if processed else "", spec.get("prediction_rejection", ""),
                          spec.get("logged_valid", str(valid).lower()), reason,
                          *pair(linear), *pair(predicted), *(pair(None) if spec.get("blank_point") else pair(point)),
                          str(rendered is not None).lower(), *pair(rendered),
                          "11.500", "2.000", "" if latency is None else f"{latency:.3f}", str(processed).lower()]
                w.writerow(cells)
                seq += 1
                valid_count += valid and not spec.get("blank_point")
    events = f"{','.join(tool.EVENT_COLUMNS)}\n{schema},{sid},0,{START - 100},-100,smoothing_reset,run_start\n"
    (d / "attempts.csv").write_bytes(attempts.getvalue().encode())
    (d / "events.csv").write_bytes(events.encode())
    (d / "model_snapshot.json").write_bytes(b"{}\n")
    finalized = final_state == "complete" or end_reason != "in_progress_or_interrupted"
    meta = {
        "schema_version": int(schema), "record_type": "notouch_gaze_evaluation_session", "session_id": sid,
        "participant_id": participant, "session_label": label, "mode": mode, "final_state": final_state,
        "end_reason": end_reason, "start_wall_time_utc": f"2026-11-0{1 + len(sid) % 8}T10:00:00Z",
        "end_wall_time_utc": None, "start_uptime_ms": START,
        "end_uptime_ms": START + (80_000 if final_state == "complete" else completed * 2500 + 700) if finalized else None,
        "planned_end_uptime_ms": START + 80_000,
        "protocol": {"id": MANIFEST["protocol_id"], "version": MANIFEST["protocol_version"],
                     "sha256": protocol_sha or tool.normalized_sha256(PROTOCOL), "grid_norm": [0.2, 0.4, 0.6, 0.8],
                     "presentation_ms": 2500, "settling_ms": 1000, "measurement_ms": 1500, "presentations_planned": 32,
                     "presentations_completed": completed if finalized else None,
                     "case_ids": [c["case_id"] for c in CASES]},
        "build": {"git_commit": build_commit, "git_dirty_at_build": dirty, "source_base": "30be1cf726a47ab5751bd8e8bb4f537d0c82c810",
                  "source_base_is_ancestor": "true", "build_type": "debug"},
        "device": {"manufacturer": "synthetic", "model": device_model, "api_level": 35, "android_release": "15"},
        "display": {"basis": "WindowManager.maximumWindowMetrics bounds (calibration basis)", "width_px": W, "height_px": H,
                    "rotation": 0, "density": 2.75},
        "predictor": {"kind": "GazeModel", "source": "candidate_9_point_calibration", "verified_snapshot_refit": True,
                      "evaluation_runs_on_this_calibration_before": 0,
                      "calibration_inputs_sha256": calibration_sha or hashlib.sha256(sid.encode()).hexdigest()},
        "conditions": {"viewing_distance_cm": 40.0, "lighting": "synthetic", "glasses": "none"},
        "pipeline": {"variant": "home_screen_candidate", "frame_interval_min_ms": 66},
        "counters": {"attempts_accepted": seq, "attempts_unrecorded": 0, "valid_attempts": valid_count,
                     "frames_received": seq * 2, "frames_cadence_skipped": seq,
                     "unlogged_results_dropped_pre_run": 0, "unlogged_results_dropped_in_run": 0} if finalized else None,
        "logging": {"attempt_rows_written": seq, "event_rows_written": 1, "rows_lost_total": 0, "write_error": False}
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

    def criterion(self, session, cid):
        return next(c for c in session["criteria"] if c["id"] == cid)


class ManifestTest(unittest.TestCase):
    def test_predeclared_thresholds_are_unchanged(self):
        p = tool.load_protocol(PROTOCOL)
        self.assertEqual(p.success, {"max_mean_error_norm": 0.10, "min_valid_coverage": 0.80, "min_valid_samples": 10,
                                     "requires_complete_presentation": True})
        self.assertEqual([(c["id"], c["comparison"], c["threshold"]) for c in p.criteria],
                         [("successful_presentation_rate", ">=", 0.80), ("p95_presentation_error", "<=", 0.20),
                          ("mean_jitter_rms", "<=", 0.05), ("tracking_availability", ">=", 0.90),
                          ("valid_sample_rate", ">=", 10.0), ("p95_processing_latency", "<=", 200.0)])
        self.assertEqual((p.min_valid_samples, p.min_valid_coverage, p.min_eligible_presentations), (10, 0.5, 26))
        self.assertEqual((p.planned, p.total_ms, p.planned_sessions), (32, 80_000, {"P01": 2}))
        self.assertEqual((p.version, p.presentation_ms, p.settling_ms, p.measurement_ms), ("2.0.0", 2500, 1000, 1500))

    def test_hash_ignores_crlf_conversion(self):
        with tempfile.TemporaryDirectory() as d:
            crlf = Path(d) / "protocol.json"
            crlf.write_bytes(PROTOCOL.read_bytes().replace(b"\n", b"\r\n"))
            self.assertEqual(tool.normalized_sha256(crlf), tool.normalized_sha256(PROTOCOL))


class StatisticsTest(unittest.TestCase):
    def test_nearest_rank_p95_and_basic_stats(self):
        self.assertEqual(tool.nearest_rank([float(i) for i in range(1, 21)], 95), 19.0)  # ceil(0.95 * 20) = 19
        self.assertEqual(tool.nearest_rank([float(i) for i in range(1, 33)], 95), 31.0)  # ceil(30.4) = 31
        self.assertEqual(tool.nearest_rank([7.0], 95), 7.0)
        self.assertIsNone(tool.nearest_rank([], 95))
        self.assertEqual(tool.mean([1.0, 2.0, 6.0]), 3.0)
        self.assertEqual(tool.median([4.0, 1.0, 3.0, 2.0]), 2.5)

    def test_spatial_error_pixels_bias_and_jitter_known_values(self):
        m = tool.spatial_metrics([(0.23, 0.44)], (0.2, 0.4), (1000, 2000))
        self.assertAlmostEqual(m["mean_error"]["norm"], math.sqrt(0.03 ** 2 + 0.04 ** 2))
        self.assertAlmostEqual(m["mean_error"]["px"], math.sqrt(30.0 ** 2 + 80.0 ** 2))
        self.assertAlmostEqual(m["bias_x"]["norm"], 0.03); self.assertAlmostEqual(m["bias_y"]["px"], 80.0)
        a = 0.01
        pts = [(0.5 + a, 0.5 + a), (0.5 - a, 0.5 + a), (0.5 + a, 0.5 - a), (0.5 - a, 0.5 - a)]
        m = tool.spatial_metrics(pts, (0.5, 0.5), (1000, 2000))
        self.assertAlmostEqual(m["jitter_rms"]["norm"], math.sqrt(2) * a)
        self.assertAlmostEqual(m["jitter_rms"]["px"], math.sqrt((a * 1000) ** 2 + (a * 2000) ** 2))
        self.assertAlmostEqual(m["centroid_error"]["norm"], 0.0)
        left_up = tool.spatial_metrics([(0.19, 0.38)], (0.2, 0.4), (1, 1))
        self.assertLess(left_up["bias_x"]["norm"], 0); self.assertLess(left_up["bias_y"]["norm"], 0)

    def test_off_screen_predictions_keep_their_full_error(self):
        m = tool.spatial_metrics([(1.2, 0.8), (1.2, 0.8)], (0.8, 0.8), (1000, 1000))
        self.assertAlmostEqual(m["mean_error"]["norm"], 0.4)
        self.assertEqual(m["off_screen_samples"], 2)


class GoldenKotlinSessionTest(Base):
    def test_analyzer_reads_the_kotlin_logger_output(self):
        dest = self.raw / "SYNTHETIC" / "eval-synthetic-0001"
        shutil.copytree(GOLDEN, dest)
        s = self.analyze()
        self.assertEqual(s["rejected_sessions"], [])
        g = self.session(s, "eval-synthetic-0001")
        self.assertEqual((g["checksums"], g["integrity_error_count"], g["issue_count"]), ("verified", 0, 0), g["integrity_errors"])
        self.assertEqual((g["final_state"], g["verdict"], g["presentations_completed"]), ("incomplete", tool.INCOMPLETE, 3))
        self.assertEqual(g["counts"]["attempts"], 102)
        self.assertEqual(g["counts"]["smoothing_resets_by_reason"], {"run_start": 1, "watchdog_no_result": 2})
        self.assertEqual(g["counts"]["other_events"], {"camera_error": 1, "unlogged_result_dropped": 1})
        self.assertEqual((g["counts"]["unlogged_results_dropped_pre_run"], g["counts"]["unlogged_results_dropped_in_run"]), (1, 0))
        self.assertTrue(all(c["status"] == tool.INCOMPLETE for c in g["criteria"]))
        # The fixture's clamped rows (linear x > 1) were accepted in presentation 1's settling phase, which spatial
        # diagnostics exclude by protocol; their predicted = clamp(linear) consistency is still checked (0 integrity errors).
        self.assertEqual(g["diagnostics"]["model_clamp_active_samples"], 0)
        self.assertEqual(g["diagnostics"]["off_screen_fraction"], 0.0)
        self.assertFalse(g["official"])
        self.assertEqual(s["incomplete_sessions"][0]["session_id"], "eval-synthetic-0001")


class SessionMetricsTest(Base):
    def test_clean_session_meets_all_criteria(self):
        write_session(self.raw)
        s = self.session(self.analyze(), "eval-a")
        self.assertEqual(s["verdict"], tool.MET, s["criteria"])
        self.assertEqual(self.criterion(s, "successful_presentation_rate")["value"], 1.0)
        self.assertAlmostEqual(s["diagnostics"]["session_mean_error"]["norm"], math.hypot(0.01, 0.02))
        self.assertAlmostEqual(s["diagnostics"]["session_mean_error"]["px"], math.hypot(10.8, 48.0))
        self.assertAlmostEqual(s["diagnostics"]["bias_x_mean"]["norm"], 0.01)
        self.assertAlmostEqual(self.criterion(s, "valid_sample_rate")["value"], 640 / 48)
        self.assertTrue(s["official"], s["official_exclusions"])
        self.assertEqual(s["checksums"], "verified")

    def test_eligibility_is_separate_from_performance_success(self):
        def rows(trial, phase, k, t):
            if phase == "settling":
                return {"point": t}
            if trial == 0:   # coverage 0.6, small error: eligible, not successful
                return {"point": t} if k < 12 else {"quality": "no_features", "feature_rejection": "eye_33_133_aperture_ratio_lt_0.10"}
            if trial == 1:   # full coverage, error 0.11: eligible, not successful
                return {"point": (t[0] + 0.11, t[1])}
            if trial == 2:   # coverage 0.85, error 0.09: successful
                return {"point": (t[0] + 0.09, t[1])} if k < 17 else {"quality": "prediction_rejected", "prediction_rejection": "face_x_shift_gt_0.12"}
            if trial == 3:   # 9 valid samples: neither
                return {"point": t} if k < 9 else {"outcome": "discarded_closing"}
            if trial == 4:   # no attempts in the window
                return None
            return {"point": t}
        write_session(self.raw, rows=rows)
        s = self.session(self.analyze(), "eval-a")
        p = s["presentations"]
        self.assertEqual([(x["eligibility"], x["successful"]) for x in p[:5]],
                         [("eligible", False), ("eligible", False), ("eligible", True), ("insufficient_data", False),
                          ("insufficient_data", False)])
        self.assertIn("valid_coverage 0.6 < 0.8", p[0]["success_failures"])
        self.assertTrue(any(f.startswith("mean_error") for f in p[1]["success_failures"]))
        self.assertIsNone(p[4]["valid_coverage"])
        self.assertIn("no_measurement_attempts", p[4]["success_failures"])
        self.assertEqual(p[0]["measurement_invalid_by_reason"], {"no_features": 8})
        self.assertEqual(p[0]["measurement_invalid_by_detail"], {"no_features:eye_33_133_aperture_ratio_lt_0.10": 8})
        self.assertEqual(p[2]["measurement_invalid_by_detail"], {"prediction_rejected:face_x_shift_gt_0.12": 3})
        self.assertEqual(self.criterion(s, "successful_presentation_rate")["value"], 28 / 32)  # trials 0, 1, 3, 4 fail
        # The empty window still counts in the throughput denominator.
        self.assertAlmostEqual(self.criterion(s, "valid_sample_rate")["value"], s["counts"]["valid_measurement_samples"] / 48)

    def test_successful_presentation_rate_threshold_is_unrounded(self):
        for bad, status in ((6, tool.MET), (7, tool.NOT_MET)):
            with self.subTest(bad=bad):
                raw = self.root / f"raw{bad}"
                write_session(raw, rows=lambda tr, ph, k, t, bad=bad: {"point": (t[0] + (0.2 if tr < bad else 0.01), t[1])})
                s = self.session(tool.run(raw, self.root / f"out{bad}", PROTOCOL, None), "eval-a")
                c = self.criterion(s, "successful_presentation_rate")
                self.assertEqual((c["value"], c["status"]), ((32 - bad) / 32, status))

    def test_p95_presentation_error_uses_presentation_means_not_samples(self):
        def rows(trial, phase, k, t):
            if trial >= 30 and phase == "measurement" and k >= 10:
                return {"quality": "no_features"}
            return {"point": (t[0] + (0.15 if trial >= 30 else 0.01), t[1])}
        write_session(self.raw, rows=rows)
        s = self.session(self.analyze(), "eval-a")
        c = self.criterion(s, "p95_presentation_error")
        self.assertAlmostEqual(c["value"], 0.15)  # 31st of 32 presentation means
        self.assertEqual((c["n"], c["status"]), (32, tool.MET))
        self.assertAlmostEqual(s["diagnostics"]["pooled_sample_errors"]["p95"], 0.01)  # frame-weighted view differs

    def test_spatial_criteria_need_enough_eligible_presentations(self):
        write_session(self.raw, rows=lambda tr, ph, k, t: {"point": t} if tr >= 7 or ph == "settling" or k < 5
                      else {"quality": "no_features"})
        s = self.session(self.analyze(), "eval-a")
        for cid in ("p95_presentation_error", "mean_jitter_rms"):
            c = self.criterion(s, cid)
            self.assertEqual(c["status"], tool.INSUFFICIENT); self.assertIn("25 technically eligible", c["reason"])
        self.assertEqual(s["verdict"], tool.NOT_MET)  # the success-rate criterion fails outright

    def test_availability_includes_invalid_attempts_and_threshold_equality_passes(self):
        write_session(self.raw, rows=lambda tr, ph, k, t: {"point": t} if k < 18 else {"quality": "prediction_rejected"})
        s = self.session(self.analyze(), "eval-a")
        c = self.criterion(s, "tracking_availability")
        self.assertEqual((c["value"], c["n"], c["status"]), (0.9, 640, tool.MET))
        self.assertEqual(s["counts"]["measurement_invalid_by_reason"], {"prediction_rejected": 64})

    def test_valid_sample_rate_denominator_is_measurement_time(self):
        for n, status in ((15, tool.MET), (14, tool.NOT_MET)):
            with self.subTest(rows=n):
                raw = self.root / f"rate{n}"
                write_session(raw, measurement_rows=n)
                c = self.criterion(self.session(tool.run(raw, self.root / f"o{n}", PROTOCOL, None), "eval-a"), "valid_sample_rate")
                self.assertAlmostEqual(c["value"], 32 * n / 48); self.assertEqual(c["status"], status)

    def test_latency_counts_invalid_and_discarded_attempts(self):
        def rows(trial, phase, k, t):
            if trial % 8 == 0 and phase == "measurement" and k < 4:
                return {"outcome": "discarded_closing", "latency": 300.0}
            if trial == 5 and phase == "settling" and k == 0:
                return {"outcome": "processing_error"}
            return {"point": t, "latency": 30.0}
        write_session(self.raw, rows=rows)
        s = self.session(self.analyze(), "eval-a")
        c = self.criterion(s, "p95_processing_latency")
        self.assertEqual(c["n"], 32 * 30 - 1)  # every attempt with a measured latency; the processing error has none
        self.assertEqual((c["value"], c["status"]), (30.0, tool.MET))
        write_session(self.root / "slow", rows=lambda tr, ph, k, t: {"quality": "no_features", "latency": 300.0}
                      if k < 2 else {"point": t, "latency": 30.0})
        slow = self.session(tool.run(self.root / "slow", self.root / "o2", PROTOCOL, None), "eval-a")
        self.assertEqual(self.criterion(slow, "p95_processing_latency")["value"], 300.0)

    def test_missing_coordinates_are_never_zero_error(self):
        def rows(trial, phase, k, t):
            if trial == 0 and k < 3:
                return {"quality": "no_features"}
            return {"point": (t[0] + 0.05, t[1])}
        write_session(self.raw, rows=rows)
        s = self.session(self.analyze(), "eval-a")
        self.assertAlmostEqual(s["presentations"][0]["final"]["mean_error"]["norm"], 0.05)
        self.assertEqual(s["presentations"][0]["valid_samples"], 17)

    def test_valid_row_without_coordinates_is_an_integrity_problem(self):
        write_session(self.raw, rows=lambda tr, ph, k, t: {"point": t, "blank_point": tr == 3 and k == 0})
        s = self.session(self.analyze(), "eval-a")
        self.assertEqual(s["verdict"], tool.INSUFFICIENT)
        self.assertTrue(any("valid flag" in e for e in s["integrity_errors"]))
        self.assertEqual(s["presentations"][3]["valid_samples"], 19)

    def test_model_clamp_is_reported_and_the_measured_coordinate_keeps_it(self):
        # The candidate predicts (1.0, y) for a linear output of (1.4, y); the smoothed point inherits the clamp.
        write_session(self.raw, rows=lambda tr, ph, k, t: {"linear": (1.4, t[1]), "point": (1.0, t[1])} if tr == 3 else {"point": t})
        s = self.session(self.analyze(), "eval-a")
        p3 = s["presentations"][3]
        self.assertAlmostEqual(p3["final"]["mean_error"]["norm"], 0.2)              # error of the clamped coordinate
        self.assertAlmostEqual(p3["linear_diagnostic"]["mean_error"]["norm"], 0.6)  # what the clamp hid
        self.assertEqual((p3["model_clamp_active_samples"], p3["final"]["off_screen_samples"]), (20, 0))
        self.assertAlmostEqual(s["diagnostics"]["model_clamp_active_fraction"], 20 / 640)
        self.assertEqual(s["diagnostics"]["off_screen_fraction"], 0.0)
        self.assertEqual(s["integrity_error_count"], 0, s["integrity_errors"])

    def test_predicted_must_be_the_clamped_linear_output(self):
        write_session(self.raw, rows=lambda tr, ph, k, t: {"point": t, "linear": t, "predicted": (t[0] + 0.1, t[1])}
                      if tr == 2 and k == 0 else {"point": t})
        s = self.session(self.analyze(), "eval-a")
        self.assertEqual(s["verdict"], tool.INSUFFICIENT)
        self.assertTrue(any("clamped linear output" in e for e in s["integrity_errors"]))

    def test_schedule_not_logged_phase_assigns_samples(self):
        write_session(self.raw, rows=lambda tr, ph, k, t: {"point": t, "logged_phase": "settling" if tr == 2 and ph == "measurement" else ph})
        s = self.session(self.analyze(), "eval-a")
        self.assertEqual(s["presentations"][2]["measurement_attempts"], 20)
        self.assertTrue(any("disagrees with the schedule" in e for e in s["integrity_errors"]))


class SessionStateTest(Base):
    def test_incomplete_session_cannot_pass(self):
        write_session(self.raw, sid="eval-i", final_state="incomplete", end_reason="back", completed=10)
        s = self.analyze()
        i = self.session(s, "eval-i")
        self.assertEqual(i["verdict"], tool.INCOMPLETE)
        self.assertTrue(all(c["status"] == tool.INCOMPLETE and c["partial"] for c in i["criteria"]))
        self.assertEqual([p["eligibility"] for p in i["presentations"][9:12]],
                         ["eligible", "incomplete_presentation", "incomplete_presentation"])
        self.assertEqual(s["incomplete_sessions"], [{"participant_id": "P01", "session_id": "eval-i", "end_reason": "back",
                                                     "path": "P01/eval-i"}])
        self.assertEqual(s["groups"][0]["participants"][0]["verdict"], tool.INCOMPLETE)

    def test_interrupted_unfinalized_session_is_listed(self):
        write_session(self.raw, sid="eval-x", final_state="incomplete", end_reason="in_progress_or_interrupted", completed=4)
        s = self.session(self.analyze(), "eval-x")
        self.assertEqual((s["verdict"], s["checksums"]), (tool.INCOMPLETE, "absent"))
        self.assertEqual(s["presentations_completed"], 4)

    def test_logging_loss_means_insufficient_data(self):
        write_session(self.raw, meta_patch=lambda m: m["logging"].update(rows_lost_total=2))
        s = self.session(self.analyze(), "eval-a")
        self.assertEqual(s["verdict"], tool.INSUFFICIENT)
        self.assertIn("logging loss", s["integrity_errors"][0])

    def test_unlogged_in_run_results_mean_insufficient_data(self):
        write_session(self.raw, meta_patch=lambda m: m["counters"].update(unlogged_results_dropped_in_run=1))
        s = self.session(self.analyze(), "eval-a")
        self.assertEqual(s["verdict"], tool.INSUFFICIENT)
        self.assertTrue(any("not logged as attempts" in e for e in s["integrity_errors"]))

    def test_changed_raw_file_fails_checksum(self):
        d = write_session(self.raw)
        with open(d / "attempts.csv", "ab") as f:
            f.write(b"\n")
        s = self.session(self.analyze(), "eval-a")
        self.assertEqual((s["checksums"], s["verdict"]), ("mismatch", tool.INSUFFICIENT))

    def test_other_protocol_is_rejected(self):
        write_session(self.raw, protocol_sha="0" * 64)
        s = self.analyze()
        self.assertEqual(s["sessions"], [])
        self.assertIn("protocol sha256", s["rejected_sessions"][0]["error"])

    def test_main_pipeline_schema_2_and_other_variants_are_never_pooled(self):
        write_session(self.raw, sid="eval-main", schema="2")
        write_session(self.raw, sid="eval-other", meta_patch=lambda m: m["pipeline"].update(variant="integrated_main"))
        write_session(self.raw, sid="eval-ok")
        s = self.analyze()
        self.assertEqual([x["session_id"] for x in s["sessions"]], ["eval-ok"])
        errors = {r["path"]: r["error"] for r in s["rejected_sessions"]}
        self.assertIn("never pooled", errors["P01/eval-main"]); self.assertIn("never pooled", errors["P01/eval-other"])

    def test_missing_files_are_rejected_not_guessed(self):
        (self.raw / "P01" / "broken").mkdir(parents=True)
        (self.raw / "P01" / "broken" / "attempts.csv").write_text("x\n")
        s = self.analyze()
        self.assertIn("missing metadata.json", s["rejected_sessions"][0]["error"])


class AggregationTest(Base):
    def test_participants_come_from_metadata_and_new_ones_append(self):
        write_session(self.raw, sid="eval-1", label="S1")
        write_session(self.raw, sid="eval-2", label="S2")
        first = self.analyze(out="o1")
        p01 = first["groups"][0]["participants"][0]
        self.assertEqual((p01["participant_id"], p01["verdict"], p01["planned_sessions"]), ("P01", tool.MET, 2))
        self.assertEqual(p01["complete_sessions"], ["eval-1", "eval-2"])
        write_session(self.raw, participant="P02", sid="eval-3", folder="later/batch/eval-3")
        second = self.analyze(out="o2")
        participants = second["groups"][0]["participants"]
        self.assertEqual([p["participant_id"] for p in participants], ["P01", "P02"])
        self.assertEqual(participants[0], p01)
        self.assertIsNone(participants[1]["planned_sessions"])
        self.assertEqual([s["session_id"] for s in second["sessions"]], ["eval-1", "eval-2", "eval-3"])

    def test_participant_p95_is_recomputed_from_pooled_presentations(self):
        write_session(self.raw, sid="eval-1", rows=lambda tr, ph, k, t: {"point": (t[0] + 0.01, t[1])})
        write_session(self.raw, sid="eval-2", rows=lambda tr, ph, k, t: {"point": (t[0] + (0.19 if tr >= 30 else 0.01), t[1])})
        s = self.analyze()
        p95s = [self.criterion(self.session(s, sid), "p95_presentation_error")["value"] for sid in ("eval-1", "eval-2")]
        self.assertAlmostEqual(p95s[0], 0.01); self.assertAlmostEqual(p95s[1], 0.19)
        pooled = s["groups"][0]["participants"][0]["descriptive"]["p95_presentation_error_pooled"]
        self.assertAlmostEqual(pooled, 0.01)  # 61st of 64 pooled means, not the 0.10 average of session p95s
        self.assertEqual(s["groups"][0]["participants"][0]["verdict"], tool.MET)

    def test_one_failing_session_fails_the_participant(self):
        write_session(self.raw, sid="eval-1")
        write_session(self.raw, sid="eval-2", rows=lambda tr, ph, k, t: {"point": (min(t[0] + 0.3, 1.0), t[1])})
        self.assertEqual(self.analyze()["groups"][0]["participants"][0]["verdict"], tool.NOT_MET)

    def test_modes_and_builds_are_never_combined(self):
        write_session(self.raw, sid="eval-1")
        write_session(self.raw, sid="eval-2", mode="live_preview")
        write_session(self.raw, sid="eval-3", build_commit="c" * 40)
        write_session(self.raw, sid="eval-4", device_model="OTHER")
        groups = self.analyze()["groups"]
        self.assertEqual(sorted(len(g["sessions"]) for g in groups), [1, 1, 1, 1])
        live = next(g for g in groups if g["mode"] == "live_preview")
        self.assertTrue(live["role"].startswith("secondary"))

    def test_reused_calibration_dirty_build_and_pilot_are_not_official(self):
        write_session(self.raw, sid="eval-1", calibration_sha="d" * 64)
        write_session(self.raw, sid="eval-2", calibration_sha="d" * 64)
        write_session(self.raw, sid="eval-3", dirty="true")
        write_session(self.raw, participant="PILOT01", sid="eval-4")
        write_session(self.raw, sid="eval-5", meta_patch=lambda m: m["build"].update(source_base_is_ancestor="false"))
        s = self.analyze()
        self.assertIn("calibration shared with eval-2", self.session(s, "eval-1")["official_exclusions"])
        self.assertIn("build dirty flag is 'true'", self.session(s, "eval-3")["official_exclusions"])
        self.assertTrue(any("technical pilot" in e for e in self.session(s, "eval-4")["official_exclusions"]))
        self.assertTrue(any("source base" in e for e in self.session(s, "eval-5")["official_exclusions"]))
        self.assertEqual(self.session(s, "eval-1")["verdict"], tool.MET)  # verdict itself is unchanged

    def test_no_sessions_is_not_measured(self):
        s = self.analyze()
        self.assertEqual((s["sessions"], s["groups"]), ([], []))
        self.assertIn("NOT_MEASURED", (self.root / "out" / "report.md").read_text(encoding="utf-8"))


class OutputTest(Base):
    def test_outputs_are_deterministic_and_raw_data_is_untouched(self):
        write_session(self.raw, sid="eval-1")
        write_session(self.raw, sid="eval-2", final_state="incomplete", end_reason="backgrounded", completed=5)
        before = {p: p.read_bytes() for p in self.raw.rglob("*") if p.is_file()}
        self.analyze(out="o1"); self.analyze(out="o2")
        for name in tool.OUTPUT_FILES:
            self.assertEqual((self.root / "o1" / name).read_bytes(), (self.root / "o2" / name).read_bytes(), name)
            self.assertNotIn(b"\r\n", (self.root / "o1" / name).read_bytes())
        self.assertEqual(before, {p: p.read_bytes() for p in self.raw.rglob("*") if p.is_file()})
        json.loads((self.root / "o1" / "summary.json").read_text(encoding="utf-8"))
        rows = list(csv.DictReader(io.StringIO((self.root / "o1" / "presentations.csv").read_text(encoding="utf-8"))))
        self.assertEqual(len(rows), 64)
        self.assertIn("not an unclamped raw output", (self.root / "o1" / "report.md").read_text(encoding="utf-8"))

    def test_output_inside_raw_data_is_refused_and_readme_is_never_written(self):
        write_session(self.raw)
        with self.assertRaises(SystemExit):
            tool.run(self.raw, self.raw / "P01" / "eval-a" / "out", PROTOCOL, None)
        with self.assertRaises(SystemExit):
            tool.run(self.raw, self.raw / "derived", PROTOCOL, None)
        out = self.root / "derived"; out.mkdir()
        (out / "README.md").write_text("human text\n", encoding="utf-8")
        tool.run(self.raw, out, PROTOCOL, None)
        self.assertEqual((out / "README.md").read_text(encoding="utf-8"), "human text\n")
        self.assertEqual(sorted(p.name for p in out.iterdir()), sorted(("README.md",) + tool.OUTPUT_FILES))

    def test_cli_exit_codes(self):
        write_session(self.raw)
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(tool.main([str(self.raw), "--out", str(self.root / "cli")]), 0)
            write_session(self.raw, sid="eval-z", protocol_sha="1" * 64)
            self.assertEqual(tool.main([str(self.raw), "--out", str(self.root / "cli2")]), 1)
            self.assertEqual(tool.main([str(self.raw), "--out", str(self.root / "cli3"), "--protocol", str(self.root / "none.json")]), 2)


if __name__ == "__main__":
    unittest.main()
