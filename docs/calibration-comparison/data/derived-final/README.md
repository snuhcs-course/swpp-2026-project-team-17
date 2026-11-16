# Derived outputs: final collection

Generated from the frozen raw inputs (`../raw/`, checksums in `../raw-inputs-final.sha256`) and the selection
`../analysis-inputs-final.json`; never edited by hand. Rerun the commands in section 7.3 of the main report
([`../../README.md`](../../README.md)) instead. The tools never overwrite this README.

Current contents: candidate A, session `eval-20261115T130702Z-4ee40520` (ANNA / CALIBRATION_A_final, complete, not
official). The exploratory collection's outputs stay in [`../derived/`](../derived/README.md) and are never mixed with
these.

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
| `diagnostic_calibration_anchors.csv`, `diagnostic_calibration_summary.csv` | `calib_eval_diagnostics.py` | The 27 calibration anchors; face position per pass, feature separation, between-pass spread, model sensitivity, fit error |

The summary figures and their plotted values (`target_summary.csv`, `figures-manifest.json`) are in
[`../../figures/final/ANNA_CALIBRATION_A_final_4ee40520/`](../../figures/final/ANNA_CALIBRATION_A_final_4ee40520/).
In this session the LOWER block has no valid measurement sample, so its rows in the diagnostic tables are empty or
zero. The diagnostic tables are descriptive and never change a verdict. Technical-pilot outputs never go here.
