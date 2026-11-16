# Derived outputs

Generated from the frozen raw inputs (`../raw/`, checksums in `../raw-inputs.sha256`) and the selection
`../analysis-inputs.json`; never edited by hand. Rerun the commands in section 7.3 of the main report
([`../../README.md`](../../README.md)) instead. The tools never overwrite this README.

Current contents: candidate B, session `eval-20261116T012500Z-9ca55817` (ANNA / B1, complete, official).

| File | Produced by | Contents |
|---|---|---|
| `summary.json` | `analyze_calibration_eval.py` | Everything, unrounded: per-block criteria, presentations, diagnostics, face summaries, provenance, selection hash |
| `sessions.csv`, `blocks.csv`, `presentations.csv` | `analyze_calibration_eval.py` | One row per session, per block and per presentation |
| `report.md` | `analyze_calibration_eval.py` | Generated metrics report (the human-maintained report is the main README) |
| `replay/<calibration_id>/<participant>/<session>/replay_report.json` | Kotlin `EvaluationReplayRunner` | Exact refit of the 27 anchors and replay of every processed attempt |
| `diagnostic_region_pass.csv` | `calib_eval_diagnostics.py` | Mean error, bias, jitter and clamp fraction by block, display row and column, and pass (descriptive) |
| `diagnostic_pass_comparison.csv` | `calib_eval_diagnostics.py` | Pass 1 vs pass 2 mean error per block and target |
| `diagnostic_clamp_edges.csv` | `calib_eval_diagnostics.py` | Valid measurement samples whose unclamped output left each screen edge, per block |
| `diagnostic_axis_gain.csv` | `calib_eval_diagnostics.py` | Slope of mean estimate on target per axis, measured and unclamped streams |
| `diagnostic_face_by_block.csv` | `calib_eval_diagnostics.py` | Logged face position and scale per block and phase vs the calibration reference (inspection only) |
| `diagnostic_calibration_anchors.csv`, `diagnostic_calibration_summary.csv` | `calib_eval_diagnostics.py` | The 27 calibration anchors with their positions; face position per pass, feature separation, between-pass spread, model sensitivity, fit error |
| `diagnostic_calibration_attempts.csv`, `diagnostic_calibration_attempt_anchors.csv` | `calib_eval_attempts.py` | Every calibration attempt of 2026-11-16 from the debug-log excerpt in `../calibration-attempts/` (outcome, face position per pass, feature spread, recomputed fit error; 4 × 27 anchors); attempt 4 is the session's calibration |

The summary figures and their plotted values are in
[`../../figures/final/ANNA_B1_9ca55817/`](../../figures/final/ANNA_B1_9ca55817/). The diagnostic tables are
descriptive and never change a verdict. Technical-pilot outputs never go here.
