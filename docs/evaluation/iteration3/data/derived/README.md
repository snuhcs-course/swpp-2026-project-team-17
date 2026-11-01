# Derived outputs

**Empty in Stage 1. Nothing has been analyzed yet.**

Generated from `../raw/` only; regenerate rather than edit:

| File | Produced by |
|---|---|
| `summary.json`, `sessions.csv`, `presentations.csv`, `report.md` | `python tools/gaze_eval/analyze_gaze_eval.py docs/evaluation/iteration3/data/raw --out docs/evaluation/iteration3/data/derived` |
| `replay/<participant_id>/<session_id>/replay_report.json` | `gradlew :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/evaluation/iteration3/data/raw -PgazeReplayOut=docs/evaluation/iteration3/data/derived/replay` |

The analyzer writes only the four files listed above and never writes this `README.md`. The canonical, human-written report is `../../README.md`.
