# Calibration attempts behind session B1 (debug log excerpt)

`notouchdiag-calibration-20261116.log` holds the calibration-outcome lines that build
`011fa7cddd03d662c437bc9ed64704292485b8bd` wrote to logcat (tag `NoTouchDiag`, debug builds only) on 2026-11-16
between 10:05 and 10:24 KST, on the Samsung SM-S918N used for session `eval-20261116T012500Z-9ca55817`: every
`calibration finished` line with its 27 `anchor` lines, the per-pass face summary, the `model` line of accepted fits,
and the `screen=` changes. Times are device local time (KST).

Provenance: captured on the analysis PC with `adb logcat -v threadtime NoTouchDiag:I AndroidRuntime:E *:S` while the
participant calibrated; the full capture (1,233 lines, SHA-256 `deead1d1769dd7b891047111d146479060fba74f21d7ae58c1d7921a6c65ae0c`,
mostly once-per-second live-gaze lines) stays outside the repository. The excerpt keeps the selected lines verbatim
with CR line ends removed (SHA-256 `1732a2f81a64feeeee01692ea04e8aa9a0a54842cc5f3ce1e9d2ce63231c8099`); Git stores it
byte for byte (`-text`).

These are calibration attempts, not evaluation sessions: they are context for the report and play no part in session
selection or verdicts. [`../../tools/calib_eval_attempts.py`](../../tools/calib_eval_attempts.py) turns them into
`../derived/diagnostic_calibration_attempts.csv` and `../derived/diagnostic_calibration_attempt_anchors.csv`,
recomputing each attempt's fit with a replica of the unchanged `GazeModel.fit` that must first reproduce B1's
`model_snapshot.json` bit for bit; attempt 4's anchors are exactly that snapshot's.
