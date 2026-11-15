"""Unit tests for calib_eval_data (verify, import, freeze, check-frozen, export extraction). SYNTHETIC data only.

Run: python -B -m unittest discover -s docs/calibration-comparison/tools -v
"""
from __future__ import annotations

import hashlib
import io
import json
import shutil
import tarfile
import tempfile
import unittest
from pathlib import Path

import calib_eval_data as data

GOLDEN = Path(__file__).resolve().parent / "testdata" / "synthetic_session"
B = "calibration_b_three_pass_positions"  # this branch's candidate (the golden session's calibration)
A = "calibration_a_three_pass_static"     # the sibling candidate, declared by the same protocol


def resum(session: Path) -> None:
    names = ("attempts.csv", "events.csv", "metadata.json", "model_snapshot.json")
    (session / "SHA256SUMS").write_bytes("".join(f"{hashlib.sha256((session / n).read_bytes()).hexdigest()}  {n}\n"
                                                 for n in names).encode())


def copy_session(dest_root: Path, participant="SYNTHETIC", sid="eval-synthetic-0001", patch=None) -> Path:
    dest = dest_root / participant / sid
    shutil.copytree(GOLDEN, dest)
    if patch or participant != "SYNTHETIC" or sid != "eval-synthetic-0001":
        meta = json.loads((dest / "metadata.json").read_text(encoding="utf-8"))
        meta["participant_id"], meta["session_id"] = participant, sid
        if patch:
            patch(meta)
        (dest / "metadata.json").write_bytes((json.dumps(meta, indent=2) + "\n").encode())
        resum(dest)
    return dest


def tar_bytes(entries) -> bytes:
    """[(name, bytes | None for a directory | 'symlink')] as a tar archive."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        for name, payload in entries:
            info = tarfile.TarInfo(name)
            if payload is None:
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
            elif payload == "symlink":
                info.type = tarfile.SYMTYPE
                info.linkname = "../../../../etc/passwd"
                tar.addfile(info)
            else:
                info.size = len(payload)
                tar.addfile(info, io.BytesIO(payload))
    return buf.getvalue()


def device_export(participant="ANNA", sid="eval-20261115T040000Z-0123abcd", extra=()):
    base = f"files/evaluation/calibration-comparison/{participant}/{sid}"
    entries = [("files/evaluation/calibration-comparison", None), (f"files/evaluation/calibration-comparison/{participant}", None),
               (base, None)]
    entries += [(f"{base}/{name}", (GOLDEN / name).read_bytes()) for name in data.SESSION_FILES]
    return tar_bytes(entries + list(extra))


class DataToolTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_golden_session_verifies_and_a_changed_file_fails(self):
        self.assertEqual(data.check_sums(GOLDEN), [])
        s = copy_session(self.root / "x")
        with open(s / "events.csv", "ab") as f:
            f.write(b"\n")
        self.assertEqual(data.check_sums(s), ["events.csv changed after recording"])
        (s / "SHA256SUMS").unlink()
        self.assertIn("not finalized", data.check_sums(s)[0])

    def test_import_files_sessions_by_candidate_and_never_overwrites(self):
        src = self.root / "incoming"
        copy_session(src, "ANNA", "eval-1")
        raw = self.root / "raw"
        log = data.import_sessions(src, raw)
        self.assertEqual(log, [f"imported {B}/ANNA/eval-1: 5 files, checksums verified"])
        dest = raw / B / "ANNA" / "eval-1"
        self.assertEqual(sorted(p.name for p in dest.iterdir()), sorted(data.SESSION_FILES))
        self.assertTrue(all((dest / n).read_bytes() == (src / "ANNA" / "eval-1" / n).read_bytes() for n in data.SESSION_FILES))
        self.assertEqual(data.import_sessions(src, raw), [f"unchanged {B}/ANNA/eval-1 (already imported)"])
        other = self.root / "other"
        copy_session(other, "ANNA", "eval-1", patch=lambda m: m.update(session_label="B2"))
        with self.assertRaises(ValueError):
            data.import_sessions(other, raw)
        self.assertEqual(json.loads((dest / "metadata.json").read_text(encoding="utf-8"))["session_label"], "S1")

    def test_pilot_sessions_only_with_allow_pilot_and_never_together(self):
        src = self.root / "incoming"
        copy_session(src, "PILOT01", "eval-p", patch=lambda m: m.update(session_label="TB1"))
        with self.assertRaises(ValueError):
            data.import_sessions(src, self.root / "raw")
        log = data.import_sessions(src, self.root / "pilot", allow_pilot=True)
        self.assertTrue(log[0].startswith(f"imported {B}/PILOT01/eval-p"))
        copy_session(src, "ANNA", "eval-o")
        with self.assertRaises(ValueError):
            data.import_sessions(src, self.root / "pilot2", allow_pilot=True)

    def test_golden_session_is_a_candidate_b_session(self):
        meta = json.loads((GOLDEN / "metadata.json").read_text(encoding="utf-8"))
        snapshot = json.loads((GOLDEN / "model_snapshot.json").read_text(encoding="utf-8"))
        self.assertEqual(meta["calibration"]["calibration_id"], B)
        self.assertEqual(meta["predictor"]["calibration_id"], B)
        self.assertEqual(snapshot["calibration_id"], B)
        self.assertEqual(meta["calibration"]["calibration_positions_by_pass"], ["ordinary", "higher", "lower"])
        self.assertEqual([a["calibration_position"] for a in snapshot["anchors"]],
                         ["ordinary"] * 9 + ["higher"] * 9 + ["lower"] * 9)
        self.assertIn(B, data.allowed_calibration_ids())

    def test_calibration_id_filter_imports_only_this_candidate_from_a_shared_export(self):
        # Both candidates use the same app storage on the phone, so one export can hold sessions of each.
        src = self.root / "incoming" / "ANNA"
        copy_session(src.parent, "ANNA", "eval-b1", patch=lambda m: m.update(session_label="B1"))
        copy_session(src.parent, "ANNA", "eval-a1", patch=lambda m: (m.update(session_label="A1"),
                                                                     m["calibration"].update(calibration_id=A)))
        log = data.import_sessions(src, self.root / "raw", calibration_id=B)
        self.assertEqual(log, [f"skipped {A}/ANNA/eval-a1: not {B} (left in the export, not copied)",
                               f"imported {B}/ANNA/eval-b1: 5 files, checksums verified"])
        self.assertEqual([p.name for p in (self.root / "raw").iterdir()], [B])
        # Without the filter the tool behaves as before and files each session by its own candidate.
        both = data.import_sessions(src, self.root / "raw-all")
        self.assertEqual(sorted(p.name for p in (self.root / "raw-all").iterdir()), sorted([A, B]))
        self.assertEqual(len(both), 2)
        with self.assertRaises(ValueError) as e:
            data.import_sessions(src, self.root / "raw-x", calibration_id="calibration_nine_point")
        self.assertIn("not a declared candidate", str(e.exception))
        self.assertFalse((self.root / "raw-x").exists())

    def test_calibration_id_filter_keeps_the_pilot_rule(self):
        src = self.root / "incoming"
        copy_session(src, "PILOT01", "eval-pb", patch=lambda m: m.update(session_label="TB1"))
        copy_session(src, "PILOT01", "eval-pa", patch=lambda m: (m.update(session_label="T1"),
                                                                 m["calibration"].update(calibration_id=A)))
        with self.assertRaises(ValueError):
            data.import_sessions(src, self.root / "raw", calibration_id=B)  # a pilot never goes to raw
        log = data.import_sessions(src, self.root / "pilot", allow_pilot=True, calibration_id=B)
        self.assertEqual(log[-1], f"imported {B}/PILOT01/eval-pb: 5 files, checksums verified")
        self.assertTrue(log[0].startswith(f"skipped {A}/PILOT01/eval-pa"))
        self.assertFalse((self.root / "pilot" / A).exists())

    def test_import_refuses_failed_checksums_other_schemas_candidates_and_renamed_folders(self):
        bad = self.root / "bad"
        s = copy_session(bad, "ANNA", "eval-1")
        (s / "attempts.csv").write_bytes(b"tampered\n")
        with self.assertRaises(ValueError) as e:
            data.import_sessions(bad, self.root / "raw")
        self.assertIn("export again", str(e.exception))
        for i, (patch, text) in enumerate(((lambda m: m.update(schema_version=3), "schema_version"),
                                           (lambda m: m["calibration"].update(calibration_id="calibration_nine_point"), "not a declared candidate"))):
            src = self.root / f"src-{i}"
            copy_session(src, "ANNA", "eval-2", patch=patch)
            with self.assertRaises(ValueError) as e:
                data.import_sessions(src, self.root / "raw")
            self.assertIn(text, str(e.exception))
        renamed = self.root / "renamed"
        s = copy_session(renamed, "ANNA", "eval-3")
        s.rename(s.parent / "eval-other")
        with self.assertRaises(ValueError) as e:
            data.import_sessions(renamed, self.root / "raw")
        self.assertIn("folder name differs", str(e.exception))

    def test_interrupted_session_without_checksums_is_imported_with_a_warning(self):
        src = self.root / "incoming"
        s = copy_session(src, "ANNA", "eval-i")
        (s / "SHA256SUMS").unlink()
        log = data.import_sessions(src, self.root / "raw")
        self.assertIn("WARNING", log[0])
        self.assertEqual(len(list((self.root / "raw" / B / "ANNA" / "eval-i").iterdir())), 4)

    def test_freeze_and_check_frozen_detect_changes_and_additions(self):
        raw = self.root / "raw"
        copy_session(raw / B, "ANNA", "eval-1")
        (raw / "README.md").write_text("not frozen\n", encoding="utf-8")
        manifest = self.root / "raw-inputs.sha256"
        manifest.write_text(data.freeze(raw), encoding="utf-8")
        self.assertEqual(data.check_frozen(raw, manifest), [])
        self.assertNotIn("README.md", manifest.read_text(encoding="utf-8"))
        copy_session(raw / B, "ANNA", "eval-2")
        with open(raw / B / "ANNA" / "eval-1" / "events.csv", "ab") as f:
            f.write(b"x")
        problems = data.check_frozen(raw, manifest)
        self.assertIn(f"{B}/ANNA/eval-1/events.csv changed", problems)
        self.assertTrue(any("added later" in p for p in problems))

    def test_export_extraction_writes_only_the_session_layout(self):
        out = self.root / "export"
        log = data.extract_export(device_export(extra=[
            ("files/evaluation/calibration-comparison/ANNA/eval-20261115T040000Z-0123abcd/metadata.json.tmp", b"partial")]), out)
        self.assertIn("skipped temporary files", log[0])
        session = out / "files/evaluation/calibration-comparison/ANNA/eval-20261115T040000Z-0123abcd"
        self.assertEqual(sorted(p.name for p in session.iterdir()), sorted(data.SESSION_FILES))
        self.assertEqual(data.check_sums(session), [])
        with self.assertRaises(ValueError):
            data.extract_export(device_export(), out)  # never over an existing folder

    def test_export_extraction_refuses_unsafe_or_unknown_members_and_writes_nothing(self):
        cases = {
            "parent": [("files/evaluation/calibration-comparison/ANNA/../../../../evil.txt", b"x")],
            "absolute": [("/tmp/evil.txt", b"x")],
            "symlink": [("files/evaluation/calibration-comparison/ANNA/eval-1/attempts.csv", "symlink")],
            "unknown": [("files/evaluation/calibration-comparison/ANNA/eval-1/notes.txt", b"x")],
            "outside": [("shared_prefs/evaluation.xml", b"x")],
        }
        for name, extra in cases.items():
            with self.subTest(name):
                out = self.root / f"export-{name}"
                with self.assertRaises(ValueError):
                    data.extract_export(device_export(extra=extra), out)
                self.assertFalse(out.exists())
        with self.assertRaises(ValueError):
            data.extract_export(tar_bytes([("files/evaluation/calibration-comparison", None)]), self.root / "empty")


if __name__ == "__main__":
    unittest.main()
