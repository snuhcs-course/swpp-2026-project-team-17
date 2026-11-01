"""Unit tests for gaze_eval_data. Sessions are SYNTHETIC (the committed Kotlin golden fixture and generated copies)."""
from __future__ import annotations

import io
import json
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import gaze_eval_data as data

GOLDEN = Path(__file__).resolve().parent / "testdata" / "synthetic_kotlin_session"


class DataToolTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        # Shape of a phone export: files/evaluation/iter3/<participant>/<session>/
        self.export = self.root / "incoming" / "files" / "evaluation" / "iter3"
        self.session = self.export / "SYNTHETIC" / "eval-synthetic-0001"
        shutil.copytree(GOLDEN, self.session)
        self.raw = self.root / "raw"

    def tearDown(self):
        self._tmp.cleanup()

    def test_import_copies_bytes_exactly_into_participant_layout(self):
        log = data.import_sessions(self.root / "incoming", self.raw)
        dest = self.raw / "SYNTHETIC" / "eval-synthetic-0001"
        self.assertEqual(log, ["imported SYNTHETIC/eval-synthetic-0001: 5 files, checksums verified"])
        for name in data.SESSION_FILES:
            self.assertEqual((dest / name).read_bytes(), (GOLDEN / name).read_bytes())
        self.assertEqual(data.check_sums(dest), [])
        self.assertEqual(sorted(p.name for p in (self.raw / "SYNTHETIC").iterdir()), ["eval-synthetic-0001"])

    def test_reimport_is_a_no_op_and_conflicts_are_refused(self):
        data.import_sessions(self.root / "incoming", self.raw)
        self.assertEqual(data.import_sessions(self.root / "incoming", self.raw),
                         ["unchanged SYNTHETIC/eval-synthetic-0001 (already imported)"])
        (self.raw / "SYNTHETIC" / "eval-synthetic-0001" / "events.csv").write_bytes(b"different\n")
        with self.assertRaises(ValueError):
            data.import_sessions(self.root / "incoming", self.raw)
        self.assertEqual((self.raw / "SYNTHETIC" / "eval-synthetic-0001" / "events.csv").read_bytes(), b"different\n")

    def test_altered_export_is_refused(self):
        with open(self.session / "attempts.csv", "ab") as f:
            f.write(b"x")
        with self.assertRaises(ValueError):
            data.import_sessions(self.root / "incoming", self.raw)
        self.assertFalse(self.raw.exists() and any(self.raw.rglob("attempts.csv")))

    def test_folder_must_match_metadata_identity(self):
        self.session.rename(self.session.parent / "renamed")
        with self.assertRaises(ValueError):
            data.import_sessions(self.root / "incoming", self.raw)

    def test_unfinalized_session_imports_with_warning(self):
        (self.session / "SHA256SUMS").unlink()
        log = data.import_sessions(self.root / "incoming", self.raw)
        self.assertIn("WARNING no SHA256SUMS", log[0])

    def test_freeze_and_check_frozen(self):
        data.import_sessions(self.root / "incoming", self.raw)
        manifest = self.root / "raw-inputs.sha256"
        manifest.write_text(data.freeze(self.raw), encoding="utf-8", newline="\n")
        self.assertEqual(len(manifest.read_text(encoding="utf-8").splitlines()), 5)
        self.assertEqual(data.check_frozen(self.raw, manifest), [])
        (self.raw / "SYNTHETIC" / "eval-synthetic-0001" / "metadata.json").write_bytes(b"{}")
        self.assertEqual(data.check_frozen(self.raw, manifest), ["SYNTHETIC/eval-synthetic-0001/metadata.json changed"])

    def test_verify_cli(self):
        with redirect_stdout(io.StringIO()) as out:
            self.assertEqual(data.main(["verify", str(self.export)]), 0)
        self.assertIn("OK", out.getvalue())
        meta = json.loads((self.session / "metadata.json").read_text(encoding="utf-8"))
        (self.session / "metadata.json").write_text(json.dumps(meta), encoding="utf-8")
        with redirect_stdout(io.StringIO()):
            self.assertEqual(data.main(["verify", str(self.export)]), 1)


if __name__ == "__main__":
    unittest.main()
