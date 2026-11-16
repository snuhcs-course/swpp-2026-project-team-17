"""Tests for calib_eval_attempts. The replica check and the excerpt checks read the repository's committed B1 session and
calibration-attempt excerpt; nothing here writes outside a temporary directory.

Run: python -B -m unittest discover -s docs/calibration-comparison/tools -v
"""
from __future__ import annotations

import copy
import csv
import tempfile
import unittest
from pathlib import Path

import calib_eval_attempts as attempts

EXPERIMENT = Path(__file__).resolve().parents[1]
RAW = EXPERIMENT / "data" / "raw"
SELECTION = EXPERIMENT / "data" / "analysis-inputs.json"
EXCERPT = EXPERIMENT / "data" / "calibration-attempts" / "notouchdiag-calibration-20261116.log"
B1 = "eval-20261116T012500Z-9ca55817"


class ReplicaTest(unittest.TestCase):
    def test_refit_reproduces_the_selected_session_snapshot_bit_for_bit(self):
        snapshots = attempts.selected_snapshots(RAW, SELECTION)
        self.assertEqual(list(snapshots), [B1])
        attempts.validate(snapshots[B1], B1)  # raises on any difference

    def test_a_changed_parameter_is_detected(self):
        snap = copy.deepcopy(attempts.selected_snapshots(RAW, SELECTION)[B1])
        snap["parameters"]["fit_error"] = snap["parameters"]["fit_error"] + 1e-15
        with self.assertRaises(attempts.AttemptError):
            attempts.validate(snap, "tampered")

    def test_spread_rule_and_error_limit_follow_the_app(self):
        flat = [(0.01, -0.05 + 1e-4 * i, 0.5, 0.6, 0.16, 0.5, 0.5) for i in range(9)]
        self.assertEqual(attempts.refit(flat)["status"], "rejected_feature_spread")
        good = [((tx - .5) * .3, (ty - .5) * .2, .5, .5, .25, tx, ty)
                for tx, ty in [(.5, .5), (.15, .15), (.5, .15), (.85, .15), (.85, .5), (.85, .85), (.5, .85), (.15, .85), (.15, .5)]]
        self.assertEqual(attempts.refit(good)["status"], "accepted")


class ExcerptTest(unittest.TestCase):
    def test_four_complete_attempts_and_the_last_is_the_calibration_of_b1(self):
        parsed = attempts.parse(EXCERPT.read_text(encoding="utf-8"))
        self.assertEqual([(a["failure"], a["model"]) for a in parsed],
                         [("FIT_REJECTED", "none"), ("FIT_REJECTED", "none"), ("none", "accepted"), ("none", "accepted")])
        self.assertTrue(all(len(a["anchors"]) == 27 for a in parsed))
        rows, anchor_rows = attempts.tables(parsed, attempts.selected_snapshots(RAW, SELECTION))
        self.assertEqual([r["used_by_session"] for r in rows], ["", "", "", B1])
        self.assertEqual([r["recomputed_status"] for r in rows],
                         ["rejected_fit_error", "rejected_fit_error", "accepted", "accepted"])
        self.assertTrue(all(float(r["recomputed_fit_error"]) > attempts.FIT_ERROR_LIMIT for r in rows[:2]))
        self.assertEqual([r["logged_fit_error"] for r in rows[2:]], [r["recomputed_fit_error"] for r in rows[2:]])
        self.assertEqual(len(anchor_rows), 108)

    def test_cli_writes_both_tables(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(attempts.main(["--log", str(EXCERPT), "--raw", str(RAW), "--selection", str(SELECTION),
                                            "--derived", tmp]), 0)
            with open(Path(tmp) / "diagnostic_calibration_attempts.csv", encoding="utf-8", newline="") as f:
                self.assertEqual(len(list(csv.DictReader(f))), 4)
            with open(Path(tmp) / "diagnostic_calibration_attempt_anchors.csv", encoding="utf-8", newline="") as f:
                self.assertEqual(len(list(csv.DictReader(f))), 108)


if __name__ == "__main__":
    unittest.main()
