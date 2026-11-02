"""Tests for gaze_eval_diagnostics. Every session here is SYNTHETIC.

Run: python -B -m unittest discover -s tools/gaze_eval -v
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import analyze_gaze_eval as analyzer
import gaze_eval_diagnostics as diag
from test_analyze_gaze_eval import PROTOCOL, write_session
from test_gaze_eval_figures import MANIFEST, fake_session, ineligible


def with_bias(session, case_id, error, bias_x, bias_y, valid=20, clamp=0, jitter=0.03):
    p = next(p for p in session["presentations"] if p["case_id"] == case_id)
    p["valid_samples"] = valid
    p["model_clamp_active_samples"] = clamp
    p["final"] = {"mean_error": {"norm": error}, "bias_x": {"norm": bias_x}, "bias_y": {"norm": bias_y},
                  "jitter_rms": {"norm": jitter}, "centroid": {"x_norm": 0.0, "y_norm": 0.0}}


class DiagnosticsTest(unittest.TestCase):
    def session(self):
        s = fake_session()
        for p in s["presentations"]:
            p["model_clamp_active_samples"] = 0
            p["final"].update(bias_x={"norm": 0.0}, bias_y={"norm": 0.0}, jitter_rms={"norm": 0.02})
        return s

    def test_rows_and_passes_weight_presentations_equally(self):
        s = self.session()
        # Row A, pass 1: errors 0.1, 0.2, 0.3, 0.4 with very different sample counts; mean must be 0.25.
        for case, err, n, clamp in (("pass1-r0c0", .1, 10, 0), ("pass1-r0c1", .2, 40, 0), ("pass1-r0c2", .3, 10, 5),
                                    ("pass1-r0c3", .4, 20, 15)):
            with_bias(s, case, err, err / 2, -err, valid=n, clamp=clamp)
        rows = {(r["grouping"], r["group"], r["pass"]): r for r in diag.region_rows(s)}
        a1 = rows[("row", "A", "1")]
        self.assertEqual(a1["presentations"], 4)
        self.assertAlmostEqual(a1["mean_of_presentation_mean_errors"], 0.25)
        self.assertAlmostEqual(a1["mean_bias_x_norm"], 0.125)
        self.assertAlmostEqual(a1["mean_bias_y_norm"], -0.25)
        self.assertEqual((a1["model_clamp_active_samples"], a1["valid_samples"]), (20, 80))
        self.assertAlmostEqual(a1["model_clamp_active_fraction"], 0.25)
        self.assertEqual(rows[("row", "A", "both")]["presentations"], 8)
        self.assertEqual(rows[("column", "1", "both")]["presentations"], 8)
        self.assertEqual(len(rows), (4 + 4) * 3)

    def test_ineligible_presentations_are_left_out_not_counted_as_zero(self):
        s = self.session()
        p = next(p for p in s["presentations"] if p["case_id"] == "pass2-r3c3")
        p.update(ineligible())
        rows = {(r["grouping"], r["group"], r["pass"]): r for r in diag.region_rows(s)}
        self.assertEqual(rows[("row", "D", "2")]["presentations"], 3)
        self.assertAlmostEqual(rows[("row", "D", "2")]["mean_of_presentation_mean_errors"], 0.05)
        d4 = next(r for r in diag.pass_rows(s, MANIFEST) if r["target_label"] == "D4")
        self.assertEqual((d4["pass1_mean_error"], d4["pass2_mean_error"], d4["pass2_minus_pass1"]), (0.05, None, None))

    def test_pass_difference_is_pass2_minus_pass1_per_target(self):
        s = self.session()
        with_bias(s, "pass1-r1c2", 0.12, 0, 0)
        with_bias(s, "pass2-r1c2", 0.30, 0, 0)
        b3 = next(r for r in diag.pass_rows(s, MANIFEST) if r["target_label"] == "B3")
        self.assertAlmostEqual(b3["pass2_minus_pass1"], 0.18)
        self.assertEqual([r["target_label"] for r in diag.pass_rows(s, MANIFEST)][:5], ["A1", "A2", "A3", "A4", "B1"])

    def test_clamp_edges_count_the_analyzers_valid_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp) / "raw"
            # Trial 0 (A1): linear output above the top edge, clamped to y = 0; trial 3 (A4): beyond the right edge.
            def rows(tr, ph, k, t):
                if tr == 0:
                    return {"linear": (t[0], -0.1), "point": (t[0], 0.0)}
                if tr == 3:
                    return {"linear": (1.2, t[1]), "point": (1.0, t[1])}
                return {"point": t}
            write_session(raw, participant="ANNA", sid="eval-1", rows=rows)
            sel = Path(tmp) / "sel.json"
            sel.write_text(json.dumps({"included": [{"session_id": "eval-1"}]}), encoding="utf-8")
            summary = analyzer.run(raw, Path(tmp) / "out", PROTOCOL, None, sel)
            s = summary["sessions"][0]
            edges = diag.edge_row(s, raw.resolve(), PROTOCOL)
        self.assertEqual((edges["linear_y_below_0_top"], edges["linear_x_above_1"], edges["linear_x_below_0"],
                          edges["linear_y_above_1_bottom"]), (20, 20, 0, 0))  # measurement-phase samples only
        self.assertEqual(edges["any_axis_outside"], s["diagnostics"]["model_clamp_active_samples"])
        self.assertEqual(edges["valid_measurement_samples"], s["counts"]["valid_measurement_samples"])


if __name__ == "__main__":
    unittest.main()
