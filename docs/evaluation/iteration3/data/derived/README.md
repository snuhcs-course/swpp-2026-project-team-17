# Derived outputs

Generated from `../raw/` only, for the sessions selected in [`../analysis-inputs.json`](../analysis-inputs.json)
(ANNA / S1 `eval-20261102T025547Z-afe0cec4` and ANNA / S3 `eval-20261102T025951Z-5333a1c9`); regenerate rather than edit:

| File | Produced by |
|---|---|
| `summary.json`, `sessions.csv`, `presentations.csv`, `report.md` | `python -B tools/gaze_eval/analyze_gaze_eval.py docs/evaluation/iteration3/data/raw --out docs/evaluation/iteration3/data/derived --selection docs/evaluation/iteration3/data/analysis-inputs.json --source-label "..."` |
| `replay/<participant_id>/<session_id>/replay_report.json` | `gradlew :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/evaluation/iteration3/data/raw -PgazeReplayOut=docs/evaluation/iteration3/data/derived/replay` |
| `figure_presentations.csv`, `figure_target_centroids.csv`, `figures-manifest.json` | `python -B tools/gaze_eval/gaze_eval_figures.py --summary docs/evaluation/iteration3/data/derived/summary.json --selection docs/evaluation/iteration3/data/analysis-inputs.json --derived docs/evaluation/iteration3/data/derived --figures docs/evaluation/iteration3/figures` |

- `figure_presentations.csv`: the values behind the target-error figures, one row per presentation (analyzer mean
  error and centroid; empty cells for technically ineligible presentations).
- `figure_target_centroids.csv`: the values behind the gaze maps, one row per session and target (equal-weight mean of
  the eligible presentation centroids, eligible-presentation count, centroid displacement).
- `figures-manifest.json`: input checksums, analyzer and library versions, shared scales and the SHA-256 of every figure.
- `diagnostic_region_pass.csv`, `diagnostic_pass_comparison.csv`, `diagnostic_clamp_edges.csv`: descriptive
  diagnostics used in the report's interpretation (no thresholds). Per display row and column and per pass: the mean of
  presentation mean errors, signed bias, jitter and model-clamp activity (each eligible presentation weighted equally);
  per target, pass 2 minus pass 1 mean error; per session, the screen edges the unclamped model output crossed. Produced
  by `python -B tools/gaze_eval/gaze_eval_diagnostics.py --summary docs/evaluation/iteration3/data/derived/summary.json --selection docs/evaluation/iteration3/data/analysis-inputs.json --raw docs/evaluation/iteration3/data/raw --derived docs/evaluation/iteration3/data/derived`.

The tools write only the files listed above and never write this `README.md`. The canonical, human-written report is
`../../README.md`; the figures are described in [`../../figures/README.md`](../../figures/README.md).
