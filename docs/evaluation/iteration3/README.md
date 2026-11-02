# NoTouch Iteration 3: Gaze-Pipeline Evaluation of the Home-Screen Candidate

*Testing Documentation → AI Module Testing*

**Status: complete for the initial participant.** Two complete sessions, ANNA / S1 and ANNA / S3, were recorded,
verified, replayed and analyzed. Performance verdict: **NOT_MET**. This report is written by hand; the analyzer's
generated summary is [`data/derived/report.md`](data/derived/report.md).

---

## 1. Overview and evaluation objective

We evaluated the on-phone gaze pipeline of the **home-screen candidate** (`home_screen_candidate`, based on
`feature/home-screen` at `30be1cf`): front camera → MediaPipe Face Landmarker → five iris and face features → personal
nine-point calibration model (`GazeModel`) → exponential smoothing. The measured APK was built from commit
`a8f08696afd3a550bd20c9d4c52fd657123fcdcb` (clean tree).

The evaluation measures the delivered gaze coordinate's target-referenced **accuracy**, **stability** during a fixation,
**availability** per processing attempt, **throughput** of valid coordinates and **processing latency**, each against a
predeclared Iteration 3 target. Data: **one participant, two complete sessions, one phone**, each session after its own
independently fitted calibration. Targets are **instructed fixation points**; no independent eye tracker recorded where
the participant actually looked.

These results describe this candidate build only. They do not evaluate the integrated `main` pipeline or clicking,
scrolling and text entry.

---

## 2. Implementation, protocol and metrics

Each run followed a **fresh nine-point calibration** whose fit inputs were frozen and refitted exactly before starting
(one run per calibration). Runs used **blind mode** (no pointer). [Protocol 2.0.0](protocol.json) shows a **4 × 4
grid** in **two passes** (serpentine, then reversed): **32 presentations of 2.5 s**, each **1.0 s settling** (excluded)
plus **1.5 s measurement**, **80 s per session**. Attempts are logged (**schema 3**), assigned by frame-acceptance time,
and replay must reproduce every logged coordinate.

The **measured coordinate** is the candidate's smoothed output, which the model has already clamped to the screen.
Coordinates are normalized per axis (x by display width, y by height); 0.10 is neither a percentage of the diagonal nor
a fixed pixel distance.

| Criterion (unchanged; compared unrounded) | Definition | Target |
|---|---|---|
| Successful-presentation rate | Successful / 32. Success: mean error ≤ 0.10, valid-sample coverage ≥ 0.80, ≥ 10 valid samples, fully shown | ≥ 0.80 |
| p95 presentation error | Nearest-rank p95 of the 32 **presentation mean errors** (not of frames) | ≤ 0.20 |
| Mean jitter RMS | Mean over presentations of the RMS spread of samples around their own mean | ≤ 0.05 |
| Tracking availability | Valid / all measurement-phase attempts | ≥ 0.90 |
| Valid sample rate | Valid measurement samples / 48 s | ≥ 10 /s |
| p95 processing latency | All run attempts: frame acceptance → main-thread publication (not eye-to-display latency) | ≤ 200 ms |

A presentation's **error** is the mean of each valid sample's distance to the target. **Technical eligibility** (≥ 10
valid samples, coverage ≥ 0.5, fully shown) only allows a presentation to be summarized; it is not success.
Diagnostics without thresholds: **mean error** (mean of presentation errors), **signed bias** (mean x/y offset;
positive = right/down) and **model-clamp fraction** (valid samples whose unclamped model output left the screen).

---

## 3. Session results

Device: Samsung SM-S918N (Android 16), 1080 × 2316 px, rotation 0. Declared conditions: 40 cm, indoor good lighting,
contact lenses. Calibrations were independent: serials 3 and 4, different input hashes, fit errors 0.108 and 0.158,
each used once about 18 s before its run.

| Session | Success /32 | Mean error | p95 error | Jitter | Availability | Valid /s | p95 latency | Verdict |
|---|---|---|---|---|---|---|---|---|
| *Target* | *≥ 80 %* | *diagnostic* | *≤ 0.20* | *≤ 0.05* | *≥ 90 %* | *≥ 10* | *≤ 200 ms* | |
| ANNA / S1 `afe0cec4` | 2 (6.25 %) ✗ | 0.250 | 0.381 ✗ | 0.0450 ✓ | 100.0 % ✓ | 11.56 ✓ | 59.1 ms ✓ | **NOT_MET** |
| ANNA / S3 `5333a1c9` | 1 (3.13 %) ✗ | 0.252 | 0.385 ✗ | 0.0472 ✓ | 99.8 % ✓ | 11.48 ✓ | 57.7 ms ✓ | **NOT_MET** |

Errors in normalized units. Participant summary (analyzer aggregation: pooled presentations; never averaged session
p95): success 3/64 (4.7 %), pooled p95 0.381, jitter 0.0461, availability 99.9 %, 11.52 valid/s, worst p95 latency
59.1 ms; verdict **NOT_MET**.

**Integrity.** Checksums verified, 0 integrity errors, 0 logging loss, all 32 presentations technically eligible, and
replay reproduced all 925 and 923 attempts exactly. Inputs are frozen in [`data/raw-inputs.sha256`](data/raw-inputs.sha256).

**Naming.** The participant planned as P01 was recorded as **ANNA**, with session labels **S1** and **S3** (no S2).
Both sessions are classified **official**. Because the plan names `P01`, the participant registry shows ANNA's planned
sessions as "—"; that field affects no verdict, eligibility or integrity rule. Metadata and protocol are unchanged.

**Excluded run.** `eval-20261102T025300Z-a459e68a` ended with Back after about 10 s (4/32) and is excluded as
incomplete, not for its accuracy. The original export is preserved outside the repository at
`C:\Users\user\Desktop\gazedot-ANNA-export-20261102-120341\export-ANNA.tar` (SHA-256 `0e770fe8…27d7`).

Selection: [`data/analysis-inputs.json`](data/analysis-inputs.json). All outputs: [`data/derived/`](data/derived/README.md).

---

## 4. Figures

| Session | Target vs mean estimated gaze | Mean target error by pass |
|---|---|---|
| ANNA / S1 | [gaze map](figures/anna_s1_afe0cec4_gaze_map.png) ([SVG](figures/anna_s1_afe0cec4_gaze_map.svg)) | [target error](figures/anna_s1_afe0cec4_target_error.png) ([SVG](figures/anna_s1_afe0cec4_target_error.svg)) |
| ANNA / S3 | [gaze map](figures/anna_s3_5333a1c9_gaze_map.png) ([SVG](figures/anna_s3_5333a1c9_gaze_map.svg)) | [target error](figures/anna_s3_5333a1c9_target_error.png) ([SVG](figures/anna_s3_5333a1c9_target_error.svg)) |

The gaze maps draw each target and its mean estimate (equal weight per presentation) on the to-scale screen; mean
positions hide sample spread. The bars show each presentation's mean individual-sample error per pass; the 0.10 line is
only the error part of the success rule. Details: [`figures/README.md`](figures/README.md).

---

## 5. Interpretation and failure analysis

**A. Criteria.** Availability (≥ 99.8 %), throughput (about 11.5 valid/s), latency (p95 ≤ 59.1 ms) and jitter
(0.045–0.047) met their targets, jitter and throughput with small margins. Accuracy did not: 3 of 64 presentations
succeeded (S1: A1 and B1 in pass 1; S3: A1 in pass 1), and p95 presentation error was about 0.38 in both sessions,
nearly twice the 0.20 target. All presentations being technically eligible does not make them successful.

**B. Spatial pattern.** The figures show every mean estimate above its target in both sessions; mean vertical bias was
−0.208 (S1) and −0.206 (S3), about 480 px. Rows kept their order but were compressed and shifted: targets at
y = 0.2/0.4/0.6/0.8 have row-average estimates at 0.07/0.21/0.37/0.51 (S1) and 0.12/0.21/0.35/0.50 (S3), and no
mean estimate lies below y = 0.53. Error grew toward the bottom (row D 0.316 and 0.340; row A 0.203 and 0.160).
Horizontal bias depended on the calibration: +0.103 (right) in S1, −0.061 (left) in S3. The clamp acted on 21.6 % of
valid samples in S1 (right and top edges; all of row A in pass 2) and 11.6 % in S3 (left and top). The unclamped
stream's mean error (0.279, 0.265) exceeds the measured 0.250 and 0.252, so clamping makes measured error look
smaller, not larger.

**C. Repeatability.** Within each calibration, pass 2 (the later 40 s) was worse for 15 of 16 targets in S1 and 13 of
16 in S3; mean presentation error rose from 0.182 to 0.318 and from 0.206 to 0.299. Pass 2 also runs in reverse order,
so time and order are confounded. Across calibrations, the upward shift and vertical compression repeated closely;
the horizontal offset changed sign.

**D. Main limitation.** Tracking was almost never missing and processing was fast. Jitter was moderate. The dominant
component is **systematic displacement**: the mean target-to-centroid distance (0.244, 0.241) is nearly the whole mean
error (0.250, 0.252). This may indicate a calibration mapping that compresses the vertical range, head or eye drift
during the run, or both; the high fit errors and the pass-2 increase are consistent with these explanations but do not
establish them.

**Conclusion.** For this participant and phone, the candidate tracks reliably and quickly but does not meet the
Iteration 3 accuracy targets; its errors are mostly consistent offsets that grew within each session.

---

## 6. Known issues and future work

**Limitations.** One participant, one phone, two sessions: no generalization. No independent eye-direction ground
truth. The measured coordinate is already clamped, hiding overshoot at edges. Viewing conditions are declared, not
measured. Latency covers acceptance to publication only. Results belong to build `a8f08696…` of this candidate.

**Proposed experiments** (tested on new recordings, never by fitting corrections to these targets):

1. **Within-session drift.** Pass 2 worse in both sessions → separate time from order (a break, or the same order
   twice) and track logged face position and scale → compare pass error and bias.
2. **Calibration quality.** Fit errors 0.108–0.158; horizontal offset flips between calibrations → several
   calibrations per session and a stricter calibration sample rule → relate fit error to evaluation error.
3. **Vertical range.** Row estimates span only 0.07–0.51 / 0.12–0.50 for targets at 0.2–0.8 → compare calibration and
   evaluation vertical feature ranges, then a revised vertical mapping → fresh sessions.
4. **Viewing distance.** Only a declared 40 cm → repeat at measured 30 and 50 cm → change in vertical bias and
   compression.

---

## 7. Reproduction and artifact locations

Tested on Windows with Python 3.14.6 and matplotlib 3.11.2 ([`requirements-plot.txt`](../../../tools/gaze_eval/requirements-plot.txt));
replay needs JDK 17/21 in `JAVA_HOME` and the Android SDK (`local.properties`). From the repository root:

```powershell
python tools/gaze_eval/gaze_eval_data.py verify docs/evaluation/iteration3/data/raw
python tools/gaze_eval/gaze_eval_data.py check-frozen docs/evaluation/iteration3/data/raw docs/evaluation/iteration3/data/raw-inputs.sha256
.\gradlew.bat :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/evaluation/iteration3/data/raw -PgazeReplayOut=docs/evaluation/iteration3/data/derived/replay
python -B tools/gaze_eval/analyze_gaze_eval.py docs/evaluation/iteration3/data/raw --out docs/evaluation/iteration3/data/derived --selection docs/evaluation/iteration3/data/analysis-inputs.json --source-label "ANNA S1 + S3, build a8f08696afd3a550bd20c9d4c52fd657123fcdcb, selection analysis-inputs.json"
python -B tools/gaze_eval/gaze_eval_figures.py --summary docs/evaluation/iteration3/data/derived/summary.json --selection docs/evaluation/iteration3/data/analysis-inputs.json --derived docs/evaluation/iteration3/data/derived --figures docs/evaluation/iteration3/figures
python -B tools/gaze_eval/gaze_eval_diagnostics.py --summary docs/evaluation/iteration3/data/derived/summary.json --selection docs/evaluation/iteration3/data/analysis-inputs.json --raw docs/evaluation/iteration3/data/raw --derived docs/evaluation/iteration3/data/derived
python -B -m unittest discover -s tools/gaze_eval
```

| Artifact | Location |
|---|---|
| Protocol 2.0.0 | [`protocol.json`](protocol.json) |
| Raw sessions and frozen checksums | [`data/raw/ANNA/`](data/raw/README.md), [`data/raw-inputs.sha256`](data/raw-inputs.sha256) |
| Session selection | [`data/analysis-inputs.json`](data/analysis-inputs.json) |
| Analyzer outputs | `data/derived/summary.json`, `sessions.csv`, `presentations.csv`, `report.md` |
| Replay reports | `data/derived/replay/ANNA/<session_id>/replay_report.json` |
| Figure inputs and provenance | `data/derived/figure_presentations.csv`, `figure_target_centroids.csv`, `figures-manifest.json` |
| Regional and pass diagnostics | `data/derived/diagnostic_region_pass.csv`, `diagnostic_pass_comparison.csv`, `diagnostic_clamp_edges.csv` |
| Figures | [`figures/`](figures/README.md) |
| Tools | `tools/gaze_eval/`; app logger `app/src/main/java/dev/gazedot/Evaluation*.kt` |

**Build identity.** Each recording names its APK in `build.git_commit` (`a8f08696…`). The later commit that adds this
report does not relabel the recordings.

**Adding participants.** Same build and protocol, fresh calibration per session. Export one participant folder at a
time (`adb exec-out run-as dev.gazedot tar` via `cmd /c`), `verify`, then `import` into `data/raw/` (never overwrites;
`PILOT*` only to `data/pilot/`). Add the session IDs to `analysis-inputs.json`, freeze anew and rerun. Different
builds, devices or conditions stay in separate groups; a changed build or protocol is a new dataset.
