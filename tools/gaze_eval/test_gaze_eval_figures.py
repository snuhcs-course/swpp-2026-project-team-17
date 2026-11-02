"""Tests for gaze_eval_figures and the analyzer's --selection. Every session here is SYNTHETIC except the checks on the
repository's analysis-input manifest, which read only that manifest.

Run: python -B -m unittest discover -s tools/gaze_eval -v
"""
from __future__ import annotations

import copy
import io
import json
import tempfile
import unittest
from pathlib import Path

import analyze_gaze_eval as analyzer
import gaze_eval_figures as figs
from test_analyze_gaze_eval import PROTOCOL, write_session

REPO = Path(__file__).resolve().parents[2]
SELECTION = REPO / "docs" / "evaluation" / "iteration3" / "data" / "analysis-inputs.json"
MANIFEST = json.loads(PROTOCOL.read_text(encoding="utf-8"))

try:
    import matplotlib  # noqa: F401
    HAVE_MPL = True
except ImportError:
    HAVE_MPL = False


def fake_session(sid="eval-x-aaaa1111", label="S1", participant="ANNA", width=1080, height=2400, overrides=None):
    """An analyzer-shaped session: every presentation eligible, centroid on target, mean error 0.05."""
    overrides = overrides or {}
    presentations = []
    for case in MANIFEST["cases"]:
        p = {"trial_index": case["trial_index"], "case_id": case["case_id"], "pass_number": case["pass_number"],
             "target_id": case["target_id"], "target_x_norm": case["target_x_norm"], "target_y_norm": case["target_y_norm"],
             "eligibility": "eligible", "eligibility_reason": None, "valid_samples": 20, "measurement_attempts": 20,
             "valid_coverage": 1.0, "successful": True,
             "final": {"mean_error": {"norm": 0.05}, "centroid": {"x_norm": case["target_x_norm"], "y_norm": case["target_y_norm"]}}}
        p.update(overrides.get(case["case_id"], {}))
        presentations.append(p)
    return {"participant_id": participant, "session_label": label, "session_id": sid, "mode": "blind_measurement",
            "final_state": "complete", "presentations_completed": 32, "presentations_planned": 32,
            "integrity_error_count": 0, "integrity_errors": [], "checksums": "verified",
            "display": {"width_px": width, "height_px": height, "rotation": 0}, "presentations": presentations}


def fake_summary(sessions, selection_ids):
    return {"input_selection": {"included_session_ids": selection_ids}, "sessions": sessions, "rejected_sessions": []}


def selection_for(sessions):
    return {"included": [{"participant_id": s["participant_id"], "session_label": s["session_label"],
                          "session_id": s["session_id"]} for s in sessions]}


def ineligible(reason="9 valid samples < 10"):
    return {"eligibility": "insufficient_data", "eligibility_reason": reason, "successful": False, "valid_samples": 9,
            "final": {"mean_error": {"norm": 0.01}, "centroid": {"x_norm": 0.5, "y_norm": 0.5}}}


class RepositorySelectionTest(unittest.TestCase):
    def test_manifest_selects_the_two_completed_sessions_and_excludes_the_interrupted_run(self):
        m = json.loads(SELECTION.read_text(encoding="utf-8"))
        self.assertEqual([(e["participant_id"], e["session_label"], e["session_id"]) for e in m["included"]],
                         [("ANNA", "S1", "eval-20261102T025547Z-afe0cec4"), ("ANNA", "S3", "eval-20261102T025951Z-5333a1c9")])
        self.assertEqual([e["session_id"] for e in m["excluded"]], ["eval-20261102T025300Z-a459e68a"])
        self.assertIn("Interrupted", m["excluded"][0]["reason"])
        self.assertTrue(any("export-ANNA.tar" in c for c in m["excluded"][0]["preserved_copies"]))
        self.assertFalse(any(e["participant_id"].upper().startswith("PILOT") for e in m["included"]))


class AnalyzerSelectionTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.raw = self.root / "raw"
        write_session(self.raw, participant="ANNA", sid="eval-1", label="S1")
        write_session(self.raw, participant="ANNA", sid="eval-x", label="S1", final_state="incomplete", end_reason="back", completed=4)
        write_session(self.raw, participant="ANNA", sid="eval-3", label="S3")
        write_session(self.raw, participant="PILOT01", sid="eval-p", label="T1")

    def tearDown(self):
        self._tmp.cleanup()

    def manifest(self, ids, name="sel.json"):
        path = self.root / name
        path.write_text(json.dumps({"included": [{"session_id": i} for i in ids]}), encoding="utf-8")
        return path

    def test_only_listed_session_ids_are_analyzed(self):
        s = analyzer.run(self.raw, self.root / "out", PROTOCOL, None, self.manifest(["eval-1", "eval-3"]))
        self.assertEqual(sorted(x["session_id"] for x in s["sessions"]), ["eval-1", "eval-3"])
        self.assertEqual(s["incomplete_sessions"], [])  # the interrupted run is not analyzed at all
        self.assertEqual(s["input_selection"]["included_session_ids"], ["eval-1", "eval-3"])
        self.assertEqual(s["input_selection"]["excluded_by_selection"], 2)
        # Same label S1 for two sessions does not matter: selection is by session_id.
        self.assertEqual([x["session_label"] for x in s["sessions"]], ["S1", "S3"])

    def test_missing_or_duplicated_selection_entries_are_refused(self):
        with self.assertRaises(SystemExit):
            analyzer.run(self.raw, self.root / "o1", PROTOCOL, None, self.manifest(["eval-1", "eval-2"]))
        with self.assertRaises(SystemExit):
            analyzer.run(self.raw, self.root / "o2", PROTOCOL, None, self.manifest(["eval-1", "eval-1"], "dup.json"))

    def test_figures_accept_only_the_traceable_selection(self):
        sel = self.manifest(["eval-1", "eval-3"])
        summary = analyzer.run(self.raw, self.root / "out", PROTOCOL, None, sel)
        selection = {"included": [{"participant_id": "ANNA", "session_label": "S1", "session_id": "eval-1"},
                                  {"participant_id": "ANNA", "session_label": "S3", "session_id": "eval-3"}]}
        self.assertEqual([s["session_id"] for s in figs.check_selection(summary, selection)], ["eval-1", "eval-3"])
        with self.assertRaises(figs.FigureInputError):
            figs.check_selection({k: v for k, v in summary.items() if k != "input_selection"}, selection)
        with self.assertRaises(figs.FigureInputError):  # the manifest changed after analysis
            figs.check_selection(summary, selection, selection_sha256="0" * 64)
        relabelled = copy.deepcopy(selection); relabelled["included"][1]["session_label"] = "S2"
        with self.assertRaises(figs.FigureInputError):
            figs.check_selection(summary, relabelled)

    def test_pilot_or_incomplete_sessions_never_reach_figures(self):
        for sid, label, participant in (("eval-p", "T1", "PILOT01"), ("eval-x", "S1", "ANNA")):
            with self.subTest(sid=sid):
                summary = analyzer.run(self.raw, self.root / f"o-{sid}", PROTOCOL, None, self.manifest([sid], f"{sid}.json"))
                with self.assertRaises(figs.FigureInputError):
                    figs.check_selection(summary, {"included": [{"participant_id": participant, "session_label": label,
                                                                 "session_id": sid}]})


class CentroidAndErrorTest(unittest.TestCase):
    def test_target_labels_and_pass_mapping(self):
        self.assertEqual([figs.target_label(t) for t in ("r0c0", "r0c3", "r1c0", "r3c3")], ["A1", "A4", "B1", "D4"])
        order = figs.target_order(MANIFEST)
        self.assertEqual([t["label"] for t in order],
                         [f"{r}{c}" for r in "ABCD" for c in "1234"])
        self.assertEqual((order[0]["x_norm"], order[0]["y_norm"], order[3]["x_norm"], order[12]["y_norm"]), (0.2, 0.2, 0.8, 0.8))
        d = figs.session_figure_data(fake_session(), MANIFEST)
        a1 = [r for r in d.presentations if r["target_label"] == "A1"]
        self.assertEqual([(r["pass_number"], r["case_id"], r["trial_index"]) for r in a1],
                         [(1, "pass1-r0c0", 0), (2, "pass2-r0c0", 31)])
        b4 = [r for r in d.presentations if r["target_label"] == "B4"]
        self.assertEqual([r["case_id"] for r in b4], ["pass1-r1c3", "pass2-r1c3"])

    def test_eligible_presentation_centroids_are_weighted_equally(self):
        s = fake_session(overrides={
            "pass1-r0c0": {"valid_samples": 30, "final": {"mean_error": {"norm": .1}, "centroid": {"x_norm": .30, "y_norm": .20}}},
            "pass2-r0c0": {"valid_samples": 10, "final": {"mean_error": {"norm": .1}, "centroid": {"x_norm": .10, "y_norm": .30}}}})
        a1 = figs.session_figure_data(s, MANIFEST).targets[0]
        self.assertAlmostEqual(a1["mean_estimate_x_norm"], 0.20)  # not the sample-weighted 0.25
        self.assertAlmostEqual(a1["mean_estimate_y_norm"], 0.25)
        self.assertAlmostEqual(a1["mean_estimate_x_px"], 0.20 * 1080)
        self.assertAlmostEqual(a1["mean_estimate_y_px"], 0.25 * 2400)
        self.assertEqual(a1["eligible_presentations"], 2)

    def test_bars_use_the_analyzer_mean_sample_error_not_the_centroid_distance(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp) / "raw"
            # Samples alternate 0.05 left and right of every target: centroid on target, every sample 0.05 away.
            write_session(raw, participant="ANNA", sid="eval-1",
                          rows=lambda tr, ph, k, t: {"point": (t[0] + (0.05 if k % 2 else -0.05), t[1])})
            sel = Path(tmp) / "sel.json"; sel.write_text(json.dumps({"included": [{"session_id": "eval-1"}]}), encoding="utf-8")
            summary = analyzer.run(raw, Path(tmp) / "out", PROTOCOL, None, sel)
        d = figs.session_figure_data(summary["sessions"][0], MANIFEST)
        for r in d.presentations:
            self.assertAlmostEqual(r["mean_error_norm"], 0.05)
        for t in d.targets:
            self.assertAlmostEqual(t["centroid_displacement_norm"], 0.0, places=12)
        p0 = summary["sessions"][0]["presentations"][0]
        self.assertEqual(d.presentations[0]["mean_error_norm"], p0["final"]["mean_error"]["norm"])

    def test_missing_data_never_becomes_zero(self):
        s = fake_session(overrides={"pass1-r0c0": ineligible(), "pass2-r0c0": ineligible("coverage 0.4 < 0.5"),
                                    "pass2-r1c1": ineligible()})
        d = figs.session_figure_data(s, MANIFEST)
        a1, b2 = d.targets[0], d.targets[5]
        self.assertEqual((a1["eligible_presentations"], a1["mean_estimate_x_norm"], a1["mean_estimate_x_px"],
                          a1["centroid_displacement_norm"]), (0, None, None, None))
        self.assertEqual((b2["eligible_presentations"], b2["eligible_passes"]), (1, "1"))
        self.assertAlmostEqual(b2["mean_estimate_x_norm"], 0.4)  # the one eligible presentation, not halved
        rows = {r["case_id"]: r for r in d.presentations}
        self.assertIsNone(rows["pass1-r0c0"]["mean_error_norm"])  # ignores the analyzer value of an ineligible presentation
        self.assertIsNone(rows["pass2-r1c1"]["centroid_x_norm"])
        text = figs.table_csv(figs.PRESENTATION_COLUMNS, d.presentations)
        line = next(l for l in text.splitlines() if ",pass1-r0c0," in l).split(",")
        self.assertEqual(line[figs.PRESENTATION_COLUMNS.index("mean_error_norm")], "")

    def test_one_error_axis_for_all_sessions(self):
        a = figs.session_figure_data(fake_session("eval-a-11111111"), MANIFEST)
        b = figs.session_figure_data(fake_session("eval-b-22222222", label="S3", overrides={
            "pass1-r3c3": {"final": {"mean_error": {"norm": 0.42}, "centroid": {"x_norm": .8, "y_norm": .8}}}}), MANIFEST)
        ymax = figs.shared_error_ymax([a, b], 0.10)
        self.assertGreaterEqual(ymax, 0.42 * 1.12)
        self.assertEqual(ymax, figs.shared_error_ymax([b, a], 0.10))
        self.assertGreater(figs.shared_error_ymax([a], 0.10), 0.10)  # threshold stays visible even when all errors are small
        self.assertTrue(figs.display_consistency([a, b])["identical"])
        c = figs.session_figure_data(fake_session("eval-c-33333333", width=1080, height=2316), MANIFEST)
        self.assertFalse(figs.display_consistency([a, c])["identical"])


@unittest.skipUnless(HAVE_MPL, "matplotlib not installed")
class RenderingTest(unittest.TestCase):
    def test_gaze_map_is_to_scale_with_y_downward_and_error_axes_are_shared(self):
        a = figs.session_figure_data(fake_session("eval-a-11111111", overrides={"pass2-r1c1": ineligible()}), MANIFEST)
        b = figs.session_figure_data(fake_session("eval-b-22222222", label="S3"), MANIFEST)
        fig = figs.render_gaze_map(a)
        ax = fig.axes[0]
        y0, y1 = ax.get_ylim()
        self.assertGreater(y0, y1)  # y increases downward
        self.assertEqual(ax.get_aspect(), 1.0)
        self.assertIn("ANNA / S1", fig._suptitle.get_text())
        self.assertTrue(any("aaaa" in t.get_text() or "11111111" in t.get_text() for t in fig.texts))
        figs._mpl()[1].close(fig)
        rule = MANIFEST["performance_criteria"]["successful_presentation"]
        ymax = figs.shared_error_ymax([a, b], rule["max_mean_error_norm"])
        fa, fb = (figs.render_target_error(d, ymax, rule["max_mean_error_norm"], rule) for d in (a, b))
        self.assertEqual(fa.axes[0].get_ylim(), fb.axes[0].get_ylim())
        self.assertEqual(fa.axes[0].get_ylim()[0], 0.0)
        self.assertEqual(len(fa.axes[0].patches), 31)  # 32 presentations, one N/A drawn as text, not as a zero bar
        self.assertIn("N/A", [t.get_text() for t in fa.axes[0].texts])
        self.assertEqual([t.get_text() for t in fa.axes[0].get_xticklabels()], [f"{r}{c}" for r in "ABCD" for c in "1234"])
        for f in (fa, fb):
            figs._mpl()[1].close(f)


if __name__ == "__main__":
    unittest.main()
