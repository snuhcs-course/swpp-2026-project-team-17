"""Tests for calib_eval_diagnostics on SYNTHETIC sessions only.

Run: python -B -m unittest discover -s docs/calibration-comparison/tools -v
"""
from __future__ import annotations

import csv
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import analyze_calibration_eval as analyzer
import calib_eval_diagnostics as diag
from test_analyze_calibration_eval import PROTOCOL, write_session

CHECKSUMMED = ("attempts.csv", "events.csv", "metadata.json", "model_snapshot.json")


def full_snapshot(session: Path) -> None:
    """Gives a synthetic session the anchor and parameter fields of a real schema-2 snapshot, then re-checksums it."""
    path = session / "model_snapshot.json"
    snap = json.loads(path.read_text(encoding="utf-8"))
    grid = [(x, y) for y in (0.15, 0.5, 0.85) for x in (0.15, 0.5, 0.85)]
    for a in snap["anchors"]:
        x, y = grid[a["point"] - 1]
        a.update(x=x, y=y, calibration_position="same", movement_retries=0,
                 eye={"x": -0.04 * x + 0.001 * a["pass"], "y": 0.02 * y, "face_x": 0.5, "face_y": 0.48 + 0.01 * a["pass"],
                      "scale": 0.3})
    snap["parameters"].update(mean_x=0.0, mean_y=0.0, std_x=0.02, std_y=0.01, wx=[0.5, -0.3, -0.1], wy=[0.5, 0.0, 0.25],
                              fit_error=0.05)
    path.write_bytes((json.dumps(snap, indent=2) + "\n").encode())
    (session / "SHA256SUMS").write_bytes("".join(f"{hashlib.sha256((session / n).read_bytes()).hexdigest()}  {n}\n"
                                                 for n in CHECKSUMMED).encode())


def compressed(block, trial, phase, k, target):
    """Valid samples halfway between the target and the screen centre, and pushed below the screen in block 3."""
    x, y = 0.5 + 0.5 * (target[0] - 0.5), 0.5 + 0.5 * (target[1] - 0.5)
    if block == 2:
        return {"point": (x, 1.0), "linear": (x, y + 0.7)}
    return {"point": (x, y)}


def read(path: Path) -> list[dict]:
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


class DiagnosticsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        root = Path(cls._tmp.name)
        cls.raw = root / "raw"
        session = write_session(cls.raw, participant="ANNA", sid="eval-d", label="A1", rows=compressed,
                                faces={"neutral": 0.5, "higher": 0.45, "lower": 0.58})
        full_snapshot(session)
        cls.selection = root / "sel.json"
        cls.selection.write_text(json.dumps({"included": [{"participant_id": "ANNA", "session_label": "A1",
                                                           "session_id": "eval-d"}]}), encoding="utf-8")
        cls.summary = analyzer.run(cls.raw, root / "out", PROTOCOL, None, cls.selection)
        cls.derived = root / "derived"
        cls.result = diag.run(root / "out" / "summary.json", cls.selection, cls.raw, cls.derived, PROTOCOL)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_all_tables_are_written_per_block(self):
        self.assertEqual(sorted(p.name for p in self.derived.iterdir()), sorted(self.result["tables"]))
        edges = read(self.derived / "diagnostic_clamp_edges.csv")
        self.assertEqual([(r["block_index"], r["head_position"]) for r in edges], [("1", "neutral"), ("2", "higher"), ("3", "lower")])

    def test_clamp_directions_agree_with_the_analyzer(self):
        edges = {r["head_position"]: r for r in read(self.derived / "diagnostic_clamp_edges.csv")}
        blocks = {b["head_position"]: b for b in self.summary["sessions"][0]["blocks"]}
        lower = edges["lower"]
        self.assertEqual(int(lower["linear_y_above_1_bottom"]), int(lower["valid_measurement_samples"]))
        self.assertEqual(int(lower["any_axis_outside"]), blocks["lower"]["diagnostics"]["model_clamp_active_samples"])
        self.assertEqual(int(edges["neutral"]["any_axis_outside"]), 0)

    def test_block_rows_reproduce_the_analyzer_block_means(self):
        rows = [r for r in read(self.derived / "diagnostic_region_pass.csv") if r["grouping"] == "block" and r["pass"] == "both"]
        for r, b in zip(rows, self.summary["sessions"][0]["blocks"]):
            self.assertAlmostEqual(float(r["mean_of_presentation_mean_errors"]), b["diagnostics"]["block_mean_error"]["norm"], places=12)
            self.assertAlmostEqual(float(r["mean_bias_y_norm"]), b["diagnostics"]["bias_y_mean"]["norm"], places=12)
        self.assertEqual({r["grouping"] for r in read(self.derived / "diagnostic_region_pass.csv")}, {"block", "row", "column"})

    def test_axis_gain_detects_compression_and_separates_the_clamp(self):
        gain = {(r["head_position"], r["axis"], r["stream"]): r for r in read(self.derived / "diagnostic_axis_gain.csv")}
        self.assertAlmostEqual(float(gain[("neutral", "x", "smoothed")]["slope"]), 0.5, places=9)
        self.assertAlmostEqual(float(gain[("neutral", "y", "smoothed")]["range_ratio"]), 0.5, places=9)
        self.assertAlmostEqual(float(gain[("lower", "y", "smoothed")]["slope"]), 0.0, places=9)  # all clamped to the edge
        self.assertAlmostEqual(float(gain[("lower", "y", "linear")]["slope"]), 0.5, places=9)  # the model output still varies

    def test_face_offsets_and_calibration_summary(self):
        faces = {(r["head_position"], r["phase"]): r for r in read(self.derived / "diagnostic_face_by_block.csv")}
        lower = faces[("lower", "measurement_valid")]
        self.assertAlmostEqual(float(lower["face_y_minus_reference"]), 0.58 - 0.5, places=9)
        self.assertAlmostEqual(float(lower["scale_ratio_to_reference"]), 1.0, places=9)
        calib = {(r["quantity"], r["group"]): float(r["value"]) for r in read(self.derived / "diagnostic_calibration_summary.csv")}
        self.assertAlmostEqual(calib[("model_sensitivity", "y per f_y")], 25.0)
        self.assertAlmostEqual(calib[("model_sensitivity", "x per f_x")], -15.0)
        self.assertAlmostEqual(calib[("pass_mean_face_y", "pass 3")], 0.51)
        self.assertAlmostEqual(calib[("between_pass_range_f_x", "target (0.15, 0.15)")], 0.002)
        self.assertEqual(len(read(self.derived / "diagnostic_calibration_anchors.csv")), 27)


if __name__ == "__main__":
    unittest.main()
