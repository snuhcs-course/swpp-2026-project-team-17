# Test fixtures — not participant data

Nothing under this folder was recorded from a person or a phone. It must never be used as an evaluation result or copied into `../../data/`.

- `synthetic_session/`: a short, deliberately **incomplete** SYNTHETIC session (log schema 4, calibration comparison) written by the app's own logger (`EvaluationSession`) through the synthetic driver (`app/src/test/.../SyntheticEvaluation.kt`). It covers the session_start reset, preparation, Ready, the countdown, a result dropped at the block start and the first seconds of block 1, then ends with Back. The JVM test `EvaluationGoldenSessionTest` regenerates it with fixed clocks, requires it to match byte for byte, and replays it. The Python tool tests read the same files, so the Kotlin writer and the Python reader share one pinned format.
- `reference_iter3/`: unmodified copies of the Iteration 3 analyzer (`analyze_gaze_eval.py`) and protocol 2.0.0 (`protocol.json`) from `feature/gaze-evaluation-home-screen-iter3` at `72411ec35d5d6e9547c322de3d7a14e65812b82e`. They are a regression fixture only: `test_analyze_calibration_eval.py` feeds identical synthetic attempts to that analyzer and to this one and requires identical per-presentation results and criteria, so the original metric calculations are shown to be unchanged. The Iteration 3 results themselves are not copied here.

Git stores these files byte for byte (`-text` in `.gitattributes`), so their checksums do not change between operating systems.
