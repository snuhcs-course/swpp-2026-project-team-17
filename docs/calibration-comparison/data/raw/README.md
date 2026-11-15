# Raw evaluation sessions (official)

**Empty. No session has been recorded yet. Accuracy: NOT_MEASURED.**

Official sessions of protocol `notouch-calibration-comparison-eval` 1.0.0 (log schema 4) go here, one folder per session:

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
- Never edit, re-save, reformat or delete files here. Git stores them unchanged (`-text` in `.gitattributes`).
- Incomplete sessions are kept. The analyzer lists them and never gives them a passing verdict.
- Technical-pilot sessions (`PILOT*`) never go here; they belong in `../pilot/` (Git-ignored). The import tool refuses them without `--allow-pilot`.
- Synthetic or test data, Iteration 3 sessions (schema 3) and backups or archives must never be placed here.
