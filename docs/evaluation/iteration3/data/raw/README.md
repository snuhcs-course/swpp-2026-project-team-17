# Raw evaluation sessions

**Empty in Stage 1. No session has been recorded yet.**

Recorded sessions go here, one folder per session:

```text
raw/<participant_id>/<session_id>/
  attempts.csv          one row per processing attempt (inputs, outputs, timing, outcome)
  events.csv            filter resets, in main-thread order
  metadata.json         identity, build, device, display, predictor, conditions, completion, counters
  model_snapshot.json   frozen calibration and fitted model parameters (for replay)
  SHA256SUMS            checksums written on the phone at finalization (missing if the run was interrupted)
```

Rules:

- Add sessions only with `python tools/gaze_eval/gaze_eval_data.py import ...`. It copies byte for byte, checks `SHA256SUMS` and never overwrites.
- Never edit, re-save, reformat or delete files here. Git stores them unchanged (`-text` in `.gitattributes`).
- Incomplete sessions are kept. The analyzer lists them and never gives them a passing verdict.
- Synthetic or test data must never be placed here.
