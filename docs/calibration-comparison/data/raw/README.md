# Raw evaluation sessions

Candidate B sessions of protocol `notouch-calibration-comparison-eval` 1.0.0 (log schema 4), stored byte for byte as
recorded on the phone. Whether a session is **official** is decided by the analyzer from the protocol's rules
(collection plan, completeness, clean build, fresh calibration, blind mode), never by its presence here.

| Candidate | Participant | Session ID | Recorded label | State | Official |
|---|---|---|---|---|---|
| `calibration_b_three_pass_positions` | ANNA | `eval-20261116T012500Z-9ca55817` | `B1` | complete, 3/3 blocks | yes |

Frozen checksums: [`../raw-inputs.sha256`](../raw-inputs.sha256) (written after the import from
`export-20261116T013437Z`, before analysis); selection: [`../analysis-inputs.json`](../analysis-inputs.json). A later
collection gets its own frozen list and selection; an earlier list is never rewritten.

```text
raw/<calibration_id>/<participant_id>/<session_id>/
  attempts.csv          one row per processing attempt (block, head position, phase, features, coordinates, timing, outcome)
  events.csv            pipeline resets, preparation, Ready and other events, in main-thread order
  metadata.json         identity, build, device, display, calibration, block timeline, conditions, counters
  model_snapshot.json   the 27 labelled calibration anchors and fitted model parameters (for replay)
  SHA256SUMS            checksums written on the phone at finalization (missing if the session was interrupted)
```

Rules:

- Add sessions only with `python docs/calibration-comparison/tools/calib_eval_data.py import ... --calibration-id calibration_b_three_pass_positions`. It copies byte for byte, checks `SHA256SUMS`, files by the recorded calibration ID, never overwrites, and leaves other candidates' sessions in the export.
- Never edit, rename, re-save, reformat or delete files here, and never rename a recorded label. Git stores them unchanged (`-text` in `.gitattributes`).
- Incomplete sessions are kept. The analyzer lists them and never gives them a passing verdict.
- Technical-pilot sessions (`PILOT*`) never go here; they belong in `../pilot/` (Git-ignored). The import tool refuses them without `--allow-pilot`.
- Synthetic or test data, Iteration 3 sessions (schema 3), candidate A sessions and backups or archives must never be placed here.
