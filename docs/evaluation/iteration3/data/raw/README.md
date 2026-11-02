# Raw evaluation sessions (official)

**Empty. No session has been recorded yet.**

Official sessions of the home-screen candidate (protocol 2.0.0, log schema 3) go here, one folder per session:

```text
raw/<participant_id>/<session_id>/
  attempts.csv          one row per processing attempt (features, linear/predicted/smoothed coordinates, timing, outcome)
  events.csv            smoothing resets and other events, in main-thread order
  metadata.json         identity, build, device, display, predictor, pipeline, conditions, completion, counters
  model_snapshot.json   frozen calibration inputs (nine anchors) and fitted model parameters (for replay)
  SHA256SUMS            checksums written on the phone at finalization (missing if the run was interrupted)
```

Rules:

- Add sessions only with `python tools/gaze_eval/gaze_eval_data.py import ...`. It copies byte for byte, checks `SHA256SUMS` and never overwrites.
- Never edit, re-save, reformat or delete files here. Git stores them unchanged (`-text` in `.gitattributes`).
- Incomplete sessions are kept. The analyzer lists them and never gives them a passing verdict.
- Technical-pilot sessions (`PILOT*`) never go here; they belong in `../pilot/` (Git-ignored). The import tool refuses them without `--allow-pilot`.
- Synthetic or test data must never be placed here. Sessions of protocol 1.0.0 / schema 2 (integrated main pipeline) must never be placed here.
