"""Tests for calib_eval_figures (adapted from the Iteration 3 figure tests). Every session here is SYNTHETIC except the
checks on the repository's analysis-input manifest, which read only that manifest and the frozen-input list.

Run: python -B -m unittest discover -s docs/calibration-comparison/tools -v
"""
from __future__ import annotations

import copy
import csv
import json
import tempfile
import unittest
from pathlib import Path

import analyze_calibration_eval as analyzer
import calib_eval_figures as figs
from test_analyze_calibration_eval import PROTOCOL, write_session

EXPERIMENT = Path(__file__).resolve().parents[1]
SELECTION = EXPERIMENT / "data" / "analysis-inputs.json"
MANIFEST = json.loads(PROTOCOL.read_text(encoding="utf-8"))
POSITIONS = ("neutral", "higher", "lower")

try:
    import matplotlib  # noqa: F401
    HAVE_MPL = True
except ImportError:
    HAVE_MPL = False


def fake_block(index, position, overrides=None):
    """An analyzer-shaped block: every presentation eligible, centroid on target, mean error 0.05."""
    overrides = overrides or {}
    presentations = []
    for case in MANIFEST["cases"]:
        p = {"trial_index": case["trial_index"], "case_suffix": case["case_suffix"],
             "case_id": f"{position}-{case['case_suffix']}", "pass_number": case["pass_number"],
             "target_id": case["target_id"], "target_x_norm": case["target_x_norm"], "target_y_norm": case["target_y_norm"],
             "eligibility": "eligible", "eligibility_reason": None, "valid_samples": 20, "measurement_attempts": 20,
             "valid_coverage": 1.0, "successful": True,
             "final": {"mean_error": {"norm": 0.05}, "centroid": {"x_norm": case["target_x_norm"], "y_norm": case["target_y_norm"]}}}
        p.update(overrides.get(case["case_suffix"], {}))
        presentations.append(p)
    return {"block_index": index, "head_position": position, "presentations": presentations, "verdict": "NOT_MET",
            "diagnostics": {"presentations_successful": sum(p["successful"] for p in presentations),
                            "model_clamp_active_fraction": 0.0}}


def fake_session(sid="eval-x-aaaa1111", label="B1", participant="ANNA", width=1080, height=2400, official=True,
                 exclusions=(), overrides=None):
    overrides = overrides or {}
    return {"calibration_id": "calibration_b_three_pass_positions", "participant_id": participant, "session_label": label,
            "session_id": sid, "mode": "blind_measurement", "final_state": "complete", "blocks_completed": 3,
            "integrity_error_count": 0, "integrity_errors": [], "checksums": "verified", "official": official,
            "verdict": "NOT_MET",
            "official_exclusions": list(exclusions), "display": {"width_px": width, "height_px": height, "rotation": 0},
            "blocks": [fake_block(i + 1, pos, overrides.get(pos)) for i, pos in enumerate(POSITIONS)]}


def blocks_of(session):
    return [figs.block_figure_data(session, b, MANIFEST) for b in session["blocks"]]


def ineligible(reason="9 valid samples < 10"):
    return {"eligibility": "insufficient_data", "eligibility_reason": reason, "successful": False, "valid_samples": 9,
            "final": {"mean_error": {"norm": 0.01}, "centroid": {"x_norm": 0.5, "y_norm": 0.5}}}


class RepositorySelectionTest(unittest.TestCase):
    def test_manifest_selects_the_complete_b1_session_by_id_with_its_recorded_label(self):
        m = json.loads(SELECTION.read_text(encoding="utf-8"))
        self.assertEqual([(e["participant_id"], e["session_label"], e["session_id"], e["calibration_id"]) for e in m["included"]],
                         [("ANNA", "B1", "eval-20261116T012500Z-9ca55817", "calibration_b_three_pass_positions")])
        self.assertEqual((m["excluded"], m["pilot_sessions"], m["incomplete_sessions"]), ([], [], []))
        self.assertFalse(any(e["participant_id"].upper().startswith("PILOT") for e in m["included"]))
        self.assertEqual(m["frozen_inputs"], "docs/calibration-comparison/data/raw-inputs.sha256")
        frozen = (EXPERIMENT.parents[1] / m["frozen_inputs"]).read_text(encoding="utf-8").splitlines()
        for entry in m["included"]:
            self.assertEqual(sum(f"/{entry['session_id']}/" in line for line in frozen), 5)
        # Only candidate B data is frozen here: candidate A sessions of the shared export were never imported.
        self.assertTrue(all(line.split("  ", 1)[1].startswith("calibration_b_three_pass_positions/") for line in frozen))


class AnalyzerSelectionTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.raw = self.root / "raw"
        write_session(self.raw, participant="ANNA", sid="eval-1", label="B1")
        write_session(self.raw, participant="ANNA", sid="eval-x", label="B2", order="lhn", final_state="incomplete",
                      end_reason="back", reached=(2, 5))
        write_session(self.raw, participant="ANNA", sid="eval-u", label="CALIBRATION_B")
        write_session(self.raw, participant="PILOT01", sid="eval-p", label="TB1")

    def tearDown(self):
        self._tmp.cleanup()

    def manifest(self, ids, name="sel.json"):
        path = self.root / name
        path.write_text(json.dumps({"included": [{"session_id": i} for i in ids]}), encoding="utf-8")
        return path

    def analyze(self, ids, name):
        return analyzer.run(self.raw, self.root / f"out-{name}", PROTOCOL, None, self.manifest(ids, f"{name}.json"))

    @staticmethod
    def selection(summary):
        return {"included": [{"participant_id": s["participant_id"], "session_label": s["session_label"],
                              "session_id": s["session_id"]} for s in summary["sessions"]]}

    def test_only_listed_session_ids_are_analyzed(self):
        s = self.analyze(["eval-1", "eval-u"], "two")
        self.assertEqual(sorted(x["session_id"] for x in s["sessions"]), ["eval-1", "eval-u"])
        self.assertEqual(s["incomplete_sessions"], [])
        self.assertEqual(s["input_selection"]["excluded_by_selection"], 2)

    def test_figures_accept_only_the_traceable_selection(self):
        summary = self.analyze(["eval-1", "eval-u"], "trace")
        selection = {"included": [{"participant_id": "ANNA", "session_label": "B1", "session_id": "eval-1"},
                                  {"participant_id": "ANNA", "session_label": "CALIBRATION_B", "session_id": "eval-u"}]}
        self.assertEqual([s["session_id"] for s in figs.check_selection(summary, selection)], ["eval-1", "eval-u"])
        with self.assertRaises(figs.FigureInputError):
            figs.check_selection({k: v for k, v in summary.items() if k != "input_selection"}, selection)
        with self.assertRaises(figs.FigureInputError):  # the manifest changed after analysis
            figs.check_selection(summary, selection, selection_sha256="0" * 64)
        relabelled = copy.deepcopy(selection)
        relabelled["included"][1]["session_label"] = "B1"  # a recorded label must not be renamed to fit the plan
        with self.assertRaises(figs.FigureInputError):
            figs.check_selection(summary, relabelled)
        claimed = copy.deepcopy(selection)
        claimed["included"][1]["official"] = True
        with self.assertRaises(figs.FigureInputError):
            figs.check_selection(summary, claimed)
        both = copy.deepcopy(selection)
        both["excluded"] = [{"session_id": "eval-u"}]
        with self.assertRaises(figs.FigureInputError):
            figs.check_selection(summary, both)

    def test_designation_comes_from_the_analyzer(self):
        summary = self.analyze(["eval-1", "eval-u"], "designation")
        by_id = {s["session_id"]: s for s in summary["sessions"]}
        self.assertEqual(figs.designation(by_id["eval-1"]), ("official", "Official session"))
        kind, text = figs.designation(by_id["eval-u"])
        self.assertEqual(kind, "not_official")
        self.assertIn("ANNA / CALIBRATION_B is not in the collection plan", text)
        pilot = self.analyze(["eval-p"], "pilot")
        self.assertEqual(figs.designation(pilot["sessions"][0])[0], "pilot")
        self.assertEqual(len(figs.check_selection(pilot, self.selection(pilot))), 1)  # a pilot alone may be drawn

    def test_incomplete_sessions_and_pilot_mixtures_never_reach_figures(self):
        incomplete = self.analyze(["eval-x"], "incomplete")
        with self.assertRaises(figs.FigureInputError):
            figs.check_selection(incomplete, self.selection(incomplete))
        mixed = self.analyze(["eval-1", "eval-p"], "mixed")
        with self.assertRaises(figs.FigureInputError):
            figs.check_selection(mixed, self.selection(mixed))

    def test_bars_use_the_analyzer_mean_sample_error_not_the_centroid_distance(self):
        raw = self.root / "raw-spread"
        # Samples alternate 0.05 left and right of every target: centroid on target, every sample 0.05 away.
        write_session(raw, participant="ANNA", sid="eval-s", label="B1",
                      rows=lambda block, trial, phase, k, t: {"point": (t[0] + (0.05 if k % 2 else -0.05), t[1])})
        sel = self.root / "spread.json"
        sel.write_text(json.dumps({"included": [{"session_id": "eval-s"}]}), encoding="utf-8")
        summary = analyzer.run(raw, self.root / "out-spread", PROTOCOL, None, sel)
        session = summary["sessions"][0]
        for block, data in zip(session["blocks"], blocks_of(session)):
            for row, p in zip(data.presentations, block["presentations"]):
                self.assertEqual(row["mean_error_norm"], p["final"]["mean_error"]["norm"])
                self.assertAlmostEqual(row["mean_error_norm"], 0.05)
            for t in data.targets:
                self.assertAlmostEqual(t["centroid_displacement_norm"], 0.0, places=12)


class CentroidAndErrorTest(unittest.TestCase):
    def test_target_labels_and_per_block_case_mapping(self):
        self.assertEqual([figs.target_label(t) for t in ("r0c0", "r0c3", "r1c0", "r3c3")], ["A1", "A4", "B1", "D4"])
        self.assertEqual([t["label"] for t in figs.target_order(MANIFEST)], [f"{r}{c}" for r in "ABCD" for c in "1234"])
        data = blocks_of(fake_session())
        self.assertEqual([(d.block_index, d.head_position) for d in data], [(1, "neutral"), (2, "higher"), (3, "lower")])
        a1 = [r for r in data[1].presentations if r["target_label"] == "A1"]
        self.assertEqual([(r["pass_number"], r["case_id"], r["trial_index"]) for r in a1],
                         [(1, "higher-pass1-r0c0", 0), (2, "higher-pass2-r0c0", 31)])

    def test_a_block_that_does_not_match_the_protocol_is_refused(self):
        s = fake_session()
        s["blocks"][2]["presentations"][4]["case_id"] = "neutral-pass1-r1c3"  # wrong position prefix
        with self.assertRaises(figs.FigureInputError):
            figs.block_figure_data(s, s["blocks"][2], MANIFEST)
        s = fake_session()
        del s["blocks"][0]["presentations"][-1]
        with self.assertRaises(figs.FigureInputError):
            figs.block_figure_data(s, s["blocks"][0], MANIFEST)

    def test_eligible_presentation_centroids_are_weighted_equally(self):
        s = fake_session(overrides={"lower": {
            "pass1-r0c0": {"valid_samples": 30, "final": {"mean_error": {"norm": .1}, "centroid": {"x_norm": .30, "y_norm": .20}}},
            "pass2-r0c0": {"valid_samples": 10, "final": {"mean_error": {"norm": .1}, "centroid": {"x_norm": .10, "y_norm": .30}}}}})
        lower, neutral = blocks_of(s)[2], blocks_of(s)[0]
        a1 = lower.targets[0]
        self.assertAlmostEqual(a1["mean_estimate_x_norm"], 0.20)  # not the sample-weighted 0.25
        self.assertAlmostEqual(a1["mean_estimate_y_norm"], 0.25)
        self.assertAlmostEqual(a1["mean_estimate_y_px"], 0.25 * 2400)
        self.assertAlmostEqual(neutral.targets[0]["mean_estimate_x_norm"], 0.2)  # other blocks unaffected

    def test_missing_data_never_becomes_zero(self):
        s = fake_session(overrides={"higher": {"pass1-r0c0": ineligible(), "pass2-r0c0": ineligible("coverage 0.4 < 0.5"),
                                               "pass2-r1c1": ineligible()}})
        d = blocks_of(s)[1]
        a1, b2 = d.targets[0], d.targets[5]
        self.assertEqual((a1["eligible_presentations"], a1["mean_estimate_x_norm"], a1["mean_estimate_x_px"],
                          a1["centroid_displacement_norm"]), (0, None, None, None))
        self.assertEqual((b2["eligible_presentations"], b2["eligible_passes"]), (1, "1"))
        self.assertAlmostEqual(b2["mean_estimate_x_norm"], 0.4)  # the one eligible presentation, not halved
        rows = {r["case_id"]: r for r in d.presentations}
        self.assertIsNone(rows["higher-pass1-r0c0"]["mean_error_norm"])  # ignores the analyzer value of an ineligible one
        text = figs.table_csv(figs.PRESENTATION_COLUMNS, d.presentations)
        line = next(l for l in text.splitlines() if ",higher-pass1-r0c0," in l).split(",")
        self.assertEqual(line[figs.PRESENTATION_COLUMNS.index("mean_error_norm")], "")

    def test_one_error_axis_for_every_block_and_session(self):
        a = blocks_of(fake_session("eval-a-11111111"))
        b = blocks_of(fake_session("eval-b-22222222", label="B2", overrides={"lower": {
            "pass1-r3c3": {"final": {"mean_error": {"norm": 0.62}, "centroid": {"x_norm": .8, "y_norm": .8}}}}}))
        ymax = figs.shared_error_ymax(a + b, 0.10)
        self.assertGreaterEqual(ymax, 0.62 * 1.12)
        self.assertEqual(ymax, figs.shared_error_ymax(list(reversed(a + b)), 0.10))
        self.assertGreater(figs.shared_error_ymax(a, 0.10), 0.10)  # threshold visible even when every error is small
        self.assertTrue(figs.display_consistency(a + b)["identical"])
        c = blocks_of(fake_session("eval-c-33333333", width=1080, height=2316))
        self.assertFalse(figs.display_consistency(a + c)["identical"])

    def test_file_stems_carry_candidate_session_and_pose(self):
        stems = [d.stem for d in blocks_of(fake_session("eval-20260101T000000Z-5e5e5e5e", label="CALIBRATION_B"))]
        self.assertEqual(stems, ["cand_b_anna_calibration_b_5e5e5e5e_b1_neutral", "cand_b_anna_calibration_b_5e5e5e5e_b2_higher",
                                 "cand_b_anna_calibration_b_5e5e5e5e_b3_lower"])

    def test_label_placement_avoids_markers_and_prefers_the_requested_side(self):
        scale = 1.0
        bounds = (-100, -100, 1100, 2500)
        free = {"circles": [], "segments": [], "rects": []}
        offset, ha, va = figs.place_label((500, 500), (0, -1), "A1", 10, scale, free, bounds)
        self.assertEqual((ha, va), ("center", "bottom"))  # straight up, as requested
        self.assertGreater(offset[1], 0)
        blocked = {"circles": [((500, 480), 8)], "segments": [], "rects": []}
        offset, ha, va = figs.place_label((500, 500), (0, -1), "A1", 10, scale, blocked, bounds)
        self.assertNotEqual((ha, va), ("center", "bottom"))
        self.assertEqual(len(blocked["rects"]), 1)  # placed labels become obstacles for the next one


def presentation(x, y, error, valid=20):
    return {"valid_samples": valid, "final": {"mean_error": {"norm": error}, "centroid": {"x_norm": x, "y_norm": y}}}


class SessionSummaryTest(unittest.TestCase):
    """The default view: per target, all three head positions and both passes of one session combined."""

    def test_six_presentation_centroids_are_weighted_equally_whatever_their_sample_counts(self):
        s = fake_session(overrides={
            "neutral": {"pass1-r0c0": presentation(.30, .20, .1, valid=60), "pass2-r0c0": presentation(.10, .20, .2, valid=10)},
            "higher": {"pass1-r0c0": presentation(.20, .50, .3, valid=30), "pass2-r0c0": presentation(.20, .20, .4, valid=12)},
            "lower": {"pass1-r0c0": presentation(.26, .26, .5, valid=40), "pass2-r0c0": presentation(.14, .14, .6, valid=11)}})
        d = figs.session_summary_data(s, MANIFEST)
        a1 = d.targets[0]
        self.assertEqual((a1["target_label"], a1["presentations"], a1["eligible_presentations"], a1["contribution"]),
                         ("A1", 6, 6, "complete"))
        self.assertEqual(d.presentations_per_target, 6)
        self.assertAlmostEqual(a1["mean_estimate_x_norm"], 1.2 / 6)  # 1/6 each; the sample-weighted mean would be 0.2347
        self.assertAlmostEqual(a1["mean_estimate_y_norm"], 1.5 / 6)
        self.assertAlmostEqual(a1["mean_presentation_error_norm"], 2.1 / 6)
        self.assertEqual((a1["min_presentation_error_norm"], a1["max_presentation_error_norm"]), (.1, .6))
        self.assertEqual(a1["contributing_presentations"],
                         "neutral-pass1;neutral-pass2;higher-pass1;higher-pass2;lower-pass1;lower-pass2")
        sample_weighted = (.30 * 60 + .10 * 10 + .20 * 30 + .20 * 12 + .26 * 40 + .14 * 11) / 163
        self.assertNotAlmostEqual(a1["mean_estimate_x_norm"], sample_weighted, places=3)

    def test_mean_error_stays_large_when_opposite_errors_cancel(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp) / "raw"

            def opposite(block, trial, phase, k, t):
                # NEUTRAL 0.1 right, HIGHER 0.1 left, LOWER alternately 0.1 above and below every target.
                if block == 2:
                    return {"point": (t[0], t[1] + (0.1 if k % 2 else -0.1))}
                return {"point": (t[0] + (0.1 if block == 0 else -0.1), t[1])}
            write_session(raw, participant="ANNA", sid="eval-o", label="B1", rows=opposite)
            sel = Path(tmp) / "sel.json"
            sel.write_text(json.dumps({"included": [{"session_id": "eval-o"}]}), encoding="utf-8")
            summary = analyzer.run(raw, Path(tmp) / "out", PROTOCOL, None, sel)
        d = figs.session_summary_data(summary["sessions"][0], MANIFEST)
        for t in d.targets:
            self.assertEqual(t["eligible_presentations"], 6)
            self.assertAlmostEqual(t["centroid_displacement_norm"], 0.0, places=9)  # the averaged position is on target
            self.assertAlmostEqual(t["mean_presentation_error_norm"], 0.1, places=9)  # yet every sample was 0.1 away

    def test_partial_and_missing_presentations_are_counted_never_zero_filled(self):
        s = fake_session(overrides={
            "higher": {"pass1-r0c0": ineligible(), "pass1-r1c1": ineligible(), "pass2-r1c1": ineligible()},
            "lower": {"pass2-r0c0": ineligible(), "pass1-r1c1": ineligible(), "pass2-r1c1": ineligible()},
            "neutral": {"pass1-r0c0": presentation(.2, .2, .3), "pass1-r1c1": ineligible(), "pass2-r1c1": ineligible()}})
        d = figs.session_summary_data(s, MANIFEST)
        a1, b2 = d.targets[0], d.targets[5]
        self.assertEqual((a1["eligible_presentations"], a1["contribution"]), (4, "partial"))
        self.assertEqual(a1["missing_presentations"], "higher-pass1;lower-pass2")
        self.assertAlmostEqual(a1["mean_presentation_error_norm"], (.3 + .05 * 3) / 4)  # over 4, not 6
        self.assertEqual((b2["eligible_presentations"], b2["contribution"], b2["mean_presentation_error_norm"],
                          b2["mean_estimate_x_norm"], b2["centroid_displacement_norm"]), (0, "none", None, None, None))
        line = next(l for l in figs.table_csv(figs.SESSION_TARGET_COLUMNS, d.targets).splitlines() if ",B2,r1c1," in l)
        cells = next(csv.reader([line]))
        self.assertEqual(cells[figs.SESSION_TARGET_COLUMNS.index("mean_presentation_error_norm")], "")
        self.assertEqual(cells[figs.SESSION_TARGET_COLUMNS.index("eligible_presentations")], "0")

    def test_presentations_are_matched_by_grid_position_not_case_id(self):
        d = figs.session_summary_data(fake_session(), MANIFEST)
        self.assertEqual(len(d.targets), 16)
        for t in d.targets:
            self.assertEqual(t["contributing_presentations"].count("-pass"), 6)
        s = fake_session(overrides={"lower": {"pass1-r0c0": {"target_x_norm": 0.4}}})  # r0c0 placed at r0c1's position
        with self.assertRaises(figs.FigureInputError):
            figs.session_summary_data(s, MANIFEST)

    def test_error_axis_keeps_a_fixed_floor_and_grows_only_when_needed(self):
        small = figs.session_summary_data(fake_session(), MANIFEST)
        self.assertEqual(figs.session_error_ymax([small], 0.10), 0.5)
        large = figs.session_summary_data(fake_session("eval-b-22222222", overrides={
            "neutral": {"pass1-r3c3": presentation(.8, .8, 4.0)}}), MANIFEST)
        top = large.targets[15]["mean_presentation_error_norm"]  # (4.0 + 5 x 0.05) / 6
        ymax = figs.session_error_ymax([small, large], 0.10)
        self.assertGreater(ymax, 0.5)
        self.assertGreaterEqual(ymax, top * 1.15)
        self.assertEqual(ymax, figs.session_error_ymax([large, small], 0.10))


@unittest.skipUnless(HAVE_MPL, "matplotlib not installed")
class SessionOutputTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.raw = self.root / "raw"
        write_session(self.raw, participant="ANNA", sid="eval-20261115T093800Z-aaaa1111", label="B1")
        write_session(self.raw, participant="ANNA", sid="eval-20261116T093800Z-bbbb2222", label="B2", order="lhn",
                      rows=lambda block, trial, phase, k, t: {"point": (t[0] - 0.05, t[1] + 0.15)})

    def tearDown(self):
        self._tmp.cleanup()

    LABELS = {"eval-20261115T093800Z-aaaa1111": "B1", "eval-20261116T093800Z-bbbb2222": "B2"}

    def analyze(self, ids, name):
        sel = self.root / f"{name}.json"
        sel.write_text(json.dumps({"included": [{"participant_id": "ANNA", "session_label": self.LABELS[i], "session_id": i}
                                                for i in ids]}), encoding="utf-8")
        analyzer.run(self.raw, self.root / name, PROTOCOL, None, sel)
        return self.root / name / "summary.json", sel

    def test_each_session_gets_its_own_folder_and_sessions_are_never_combined(self):
        summary, sel = self.analyze(["eval-20261115T093800Z-aaaa1111", "eval-20261116T093800Z-bbbb2222"], "both")
        result = figs.run(summary, sel, self.root / "draft", PROTOCOL)
        folders = sorted(p.name for p in (self.root / "draft").iterdir())
        self.assertEqual(folders, ["ANNA_B1_aaaa1111", "ANNA_B2_bbbb2222"])
        for folder, sid in zip(folders, ["eval-20261115T093800Z-aaaa1111", "eval-20261116T093800Z-bbbb2222"]):
            files = sorted(p.name for p in (self.root / "draft" / folder).iterdir())
            stem = f"cand_b_anna_{folder.split('_')[1].lower()}_{sid[-8:]}_all_poses"
            self.assertEqual(files, sorted(["figures-manifest.json", "target_summary.csv"]
                                           + [f"{stem}_{k}.{e}" for k in ("gaze_map", "target_error") for e in ("png", "svg")]))
            with open(self.root / "draft" / folder / "target_summary.csv", encoding="utf-8", newline="") as f:
                rows = list(csv.DictReader(f))
            self.assertEqual((len(rows), {r["session_id"] for r in rows}), (16, {sid}))
        manifest = result["sessions"][0]
        self.assertEqual([b["verdict"] for b in manifest["acceptance"]["block_verdicts"]],
                         [b["verdict"] for b in json.loads(summary.read_text(encoding="utf-8"))["sessions"][0]["blocks"]])
        # The first session's table is the same whether or not the second session is selected too.
        alone_summary, alone_sel = self.analyze(["eval-20261115T093800Z-aaaa1111"], "alone")
        figs.run(alone_summary, alone_sel, self.root / "alone-draft", PROTOCOL)
        self.assertEqual((self.root / "draft" / "ANNA_B1_aaaa1111" / "target_summary.csv").read_bytes(),
                         (self.root / "alone-draft" / "ANNA_B1_aaaa1111" / "target_summary.csv").read_bytes())

    def test_an_existing_session_folder_is_not_overwritten_with_different_figures(self):
        summary, sel = self.analyze(["eval-20261115T093800Z-aaaa1111"], "one")
        figs.run(summary, sel, self.root / "draft", PROTOCOL)
        folder = self.root / "draft" / "ANNA_B1_aaaa1111"
        png = next(folder.glob("*_gaze_map.png"))
        original = png.read_bytes()
        figs.run(summary, sel, self.root / "draft", PROTOCOL)  # identical regeneration is allowed
        png.write_bytes(b"changed")
        with self.assertRaises(figs.FigureInputError):
            figs.run(summary, sel, self.root / "draft", PROTOCOL)
        self.assertEqual(png.read_bytes(), b"changed")
        figs.run(summary, sel, self.root / "draft", PROTOCOL, replace=True)
        self.assertEqual(png.read_bytes(), original)


@unittest.skipUnless(HAVE_MPL, "matplotlib not installed")
class RenderingTest(unittest.TestCase):
    def test_session_figures_show_one_bar_per_target_partial_counts_and_na(self):
        s = fake_session("eval-20260101T000000Z-5e5e5e5e", label="CALIBRATION_B", official=False,
                         exclusions=["ANNA / CALIBRATION_B is not in the collection plan"], overrides={
                             "higher": {"pass1-r0c0": ineligible(), "pass1-r1c1": ineligible(), "pass2-r1c1": ineligible()},
                             "lower": {"pass2-r0c0": ineligible(), "pass1-r1c1": ineligible(), "pass2-r1c1": ineligible()},
                             "neutral": {"pass1-r1c1": ineligible(), "pass2-r1c1": ineligible()}})
        d = figs.session_summary_data(s, MANIFEST)
        rule = MANIFEST["performance_criteria"]["successful_presentation"]
        ymax = figs.session_error_ymax([d], rule["max_mean_error_norm"])
        fig = figs.render_session_target_error(d, ymax, rule["max_mean_error_norm"], rule)
        ax = fig.axes[0]
        self.assertEqual(len(ax.patches), 15)  # 16 targets, B2 without data drawn as N/A, never as a zero bar
        texts = [t.get_text() for t in ax.texts]
        self.assertIn("N/A", texts)
        self.assertTrue(any(t.endswith("4/6") for t in texts))  # A1: partial, count shown
        self.assertEqual([t.get_text() for t in ax.get_xticklabels()], [f"{r}{c}" for r in "ABCD" for c in "1234"])
        self.assertEqual(ax.get_ylim(), (0.0, ymax))
        self.assertTrue(any(list(line.get_ydata()) == [0.1, 0.1] for line in ax.lines))  # the 0.10 reference line
        caption = " ".join(t.get_text() for t in fig.texts) + " " + fig._suptitle.get_text()
        for expected in ("Average target error across the 6 presentations", "all 3 head positions, both passes combined",
                         "ANNA / CALIBRATION_B", "5e5e5e5e", "not official", "NEUTRAL, HIGHER and LOWER",
                         "can hide differences", "verdicts are unchanged", "does not show that every presentation passed",
                         "not a percentage of the screen", "cancel"):
            self.assertIn(expected, caption.replace("\n", " "))
        figs._mpl()[1].close(fig)
        fig = figs.render_session_gaze_map(d)
        ax = fig.axes[0]
        y0, y1 = ax.get_ylim()
        self.assertGreater(y0, y1)  # y increases downward
        self.assertEqual(ax.get_aspect(), 1.0)
        labels = [t.get_text() for t in ax.texts]
        self.assertIn("A1 (4/6)", labels)
        self.assertIn("B2\n(no estimate)", labels)
        figs._mpl()[1].close(fig)

    def test_partial_caption_groups_targets_and_names_the_presentations_left_out(self):
        s = fake_session(overrides={"higher": {"pass1-r0c0": ineligible(), "pass1-r0c1": ineligible()},
                                    "lower": {"pass2-r3c3": ineligible()}})
        sentence = figs._partial_sentence(figs.session_summary_data(s, MANIFEST), "N/A")
        self.assertIn("A1, A2 (5/6; not eligible: HIGHER pass 1), D4 (5/6; not eligible: LOWER pass 2).", sentence)
        self.assertIn("No eligible presentation (N/A): none.", sentence)

    def test_a_block_without_valid_samples_is_named_and_long_captions_are_not_clipped(self):
        def bottoms(fig):
            renderer = fig.canvas.get_renderer()
            return min(t.get_window_extent(renderer).y0 for t in fig.texts)

        complete = figs.session_summary_data(fake_session(), MANIFEST)
        s = fake_session(overrides={"lower": {c["case_suffix"]: ineligible("valid_samples 0 < 10")
                                              for c in MANIFEST["cases"]}})
        s["blocks"][2]["diagnostics"]["model_clamp_active_fraction"] = None  # the analyzer's value without valid samples
        d = figs.session_summary_data(s, MANIFEST)
        self.assertEqual(figs._clamp_clause(complete), " (clamp active for 0.0% / 0.0% / 0.0% of valid samples, by position)")
        self.assertEqual(figs._clamp_clause(d),
                         " (clamp active for 0.0% of NEUTRAL and 0.0% of HIGHER valid samples; LOWER had no valid samples)")
        self.assertIn("all 16 targets (4/6; not eligible: LOWER pass 1, LOWER pass 2)", figs._partial_sentence(d, "N/A"))
        rule = MANIFEST["performance_criteria"]["successful_presentation"]
        threshold = rule["max_mean_error_norm"]
        plt = figs._mpl()[1]
        for data, grows in ((complete, False), (d, True)):
            ymax = figs.session_error_ymax([data], threshold)
            for fig, size in ((figs.render_session_gaze_map(data), (7.2, 13.6)),
                              (figs.render_session_target_error(data, ymax, threshold, rule), (12.0, 9.0))):
                width, height = fig.get_size_inches()
                self.assertEqual(width, size[0])
                (self.assertGreater if grows else self.assertEqual)(height, size[1])  # complete sessions keep 1.1.0 sizes
                self.assertGreaterEqual(bottoms(fig), 0)  # no caption line below the figure's bottom edge
                plt.close(fig)

    def test_gaze_map_is_to_scale_with_y_downward_and_names_the_session_pose_and_designation(self):
        s = fake_session("eval-20260101T000000Z-5e5e5e5e", label="CALIBRATION_B", official=False,
                         exclusions=["ANNA / CALIBRATION_B is not in the collection plan"],
                         overrides={"higher": {"pass2-r1c1": ineligible()}})
        d = blocks_of(s)[1]
        fig = figs.render_gaze_map(d)
        ax = fig.axes[0]
        y0, y1 = ax.get_ylim()
        self.assertGreater(y0, y1)  # y increases downward
        self.assertEqual(ax.get_aspect(), 1.0)
        texts = " ".join(t.get_text() for t in fig.texts) + " " + fig._suptitle.get_text()
        for expected in ("Target vs mean estimated gaze", "Candidate B", "HIGHER head position (block 2 of 3)",
                         "ANNA / CALIBRATION_B", "5e5e5e5e", "not official", "not in the collection plan"):
            self.assertIn(expected, texts)
        self.assertIn("B2 (1/2)", [t.get_text() for t in ax.texts])  # partially eligible target is marked
        self.assertIn("B2", [t.get_text() for t in ax.texts])  # its estimate carries the target name
        figs._mpl()[1].close(fig)

    def test_error_charts_share_one_axis_and_show_missing_data_as_na(self):
        s = fake_session(overrides={"neutral": {"pass2-r1c1": ineligible()}, "lower": {
            "pass1-r3c3": {"final": {"mean_error": {"norm": 0.42}, "centroid": {"x_norm": .8, "y_norm": .8}}}}})
        data = blocks_of(s)
        rule = MANIFEST["performance_criteria"]["successful_presentation"]
        ymax = figs.shared_error_ymax(data, rule["max_mean_error_norm"])
        charts = [figs.render_target_error(d, ymax, rule["max_mean_error_norm"], rule) for d in data]
        self.assertEqual({c.axes[0].get_ylim() for c in charts}, {(0.0, ymax)})
        self.assertEqual(len(charts[0].axes[0].patches), 31)  # 32 presentations, one N/A drawn as text, not a zero bar
        self.assertIn("N/A", [t.get_text() for t in charts[0].axes[0].texts])
        self.assertEqual([t.get_text() for t in charts[0].axes[0].get_xticklabels()], [f"{r}{c}" for r in "ABCD" for c in "1234"])
        for c in charts:
            figs._mpl()[1].close(c)

    def test_saved_files_are_deterministic(self):
        d = blocks_of(fake_session())[0]
        with tempfile.TemporaryDirectory() as tmp:
            first = [p.read_bytes() for p in figs.save(figs.render_gaze_map(d), Path(tmp) / "a")]
            second = [p.read_bytes() for p in figs.save(figs.render_gaze_map(d), Path(tmp) / "b")]
        self.assertEqual(first, second)
        self.assertTrue(first[0].startswith(b"<?xml") and first[1].startswith(b"\x89PNG"))


if __name__ == "__main__":
    unittest.main()
