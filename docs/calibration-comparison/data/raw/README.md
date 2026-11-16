# Raw evaluation sessions

Non-pilot sessions of protocol `notouch-calibration-comparison-eval` 1.0.0 (log schema 4), stored byte for byte as
recorded on the phone. Whether a session is **official** is decided by the analyzer from the protocol's rules
(collection plan, completeness, clean build, fresh calibration, blind mode), never by its presence here.

| Candidate | Participant | Session ID | Recorded label | State | Official |
|---|---|---|---|---|---|
| `calibration_a_three_pass_static` | ANNA | `eval-20261115T093800Z-761b3d19` | `CALIBRATION_A` | complete, 3/3 blocks | no: label outside the collection plan |
| `calibration_a_three_pass_static` | ANNA | `eval-20261115T130702Z-4ee40520` | `CALIBRATION_A_final` | complete, 3/3 blocks | no: label outside the collection plan |

Frozen checksums and selections, one pair per collection (an earlier list is never rewritten):

| Collection | Frozen list | Selection |
|---|---|---|
| Exploratory (`761b3d19`, imported 2026-11-15 from `export-20261115T094943Z`) | [`../raw-inputs.sha256`](../raw-inputs.sha256) (5 files; it reports files imported later as additions) | [`../analysis-inputs.json`](../analysis-inputs.json) |
| Final (`4ee40520`, imported 2026-11-15 from `export-20261115T131403Z`) | [`../raw-inputs-final.sha256`](../raw-inputs-final.sha256) (all 10 files, written after that import) | [`../analysis-inputs-final.json`](../analysis-inputs-final.json) |

```text
raw/<calibration_id>/<participant_id>/<session_id>/
  attempts.csv          one row per processing attempt (block, head position, phase, features, coordinates, timing, outcome)
  events.csv            pipeline resets, preparation, Ready and other events, in main-thread order
  metadata.json         identity, build, device, display, calibration, block timeline, conditions, counters
  model_snapshot.json   the 27 labelled calibration anchors and fitted model parameters (for replay)
  SHA256SUMS            checksums written on the phone at finalization (missing if the session was interrupted)
```

Rules:

- Add sessions only with `python docs/calibration-comparison/tools/calib_eval_data.py import ...`. It copies byte for byte, checks `SHA256SUMS`, files by the recorded calibration ID and never overwrites.
- Never edit, rename, re-save, reformat or delete files here, and never rename a recorded label. Git stores them unchanged (`-text` in `.gitattributes`).
- Incomplete sessions are kept. The analyzer lists them and never gives them a passing verdict.
- After adding a session, check the existing frozen list, then write a new one and record the addition.
- Technical-pilot sessions (`PILOT*`) never go here; they belong in `../pilot/` (Git-ignored). The import tool refuses them without `--allow-pilot`.
- Synthetic or test data, Iteration 3 sessions (schema 3) and backups or archives must never be placed here.
