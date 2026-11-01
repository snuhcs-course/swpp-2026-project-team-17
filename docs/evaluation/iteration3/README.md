# NoTouch Iteration 3: Gaze-Pipeline Evaluation

*Testing Documentation → AI Module Testing*

**Status: Stage 1. Instrumentation, protocol, metrics and checks are in place. No real sessions have been collected yet.** Every result field below is pending. Nothing in this document is a measurement of the gaze pipeline.

This file is maintained by hand. The analyzer never writes to it; its generated outputs go to [`data/derived/`](data/derived/README.md).

---

## 1. Purpose and Scope

### What is evaluated

The **local gaze-estimation pipeline** of the integrated NoTouch app (`main`), running on a phone:

front camera → MediaPipe Face Landmarker → eye and head features (`LandmarkFeatures`) → personal nine-point calibration model (`GazeModel`) → filter (`GazeStabilizer` inside `GazePipeline`) → final gaze coordinate `GazeSample.point`.

`GazeSample.point` is the coordinate the interaction logic uses for dwell clicks and scrolling. It is the primary measured output.

### Questions this evaluation answers

1. **Accuracy.** How far is the final gaze coordinate from a target the participant was told to look at?
2. **Stability.** How much does the final coordinate move while the participant holds a fixation?
3. **Availability.** How often does a processing attempt during a fixation produce a valid coordinate?
4. **Throughput.** How many valid coordinates per second does the pipeline deliver?
5. **Processing latency.** How long does it take from accepting a camera frame to publishing its result on the main thread?
6. **Engineering targets.** Do these meet the predeclared Iteration 3 targets in [Section 3](#3-metrics-and-predeclared-passfail-criteria)?

### Ground truth

Targets are **instructed fixation points**. No independent eye tracker measures where the participant actually looked, so every spatial value is a *target-referenced gaze error*, not a ground-truth eye-direction error. A participant who looks slightly off target adds to the measured error.

### What is not evaluated here

Dwell clicking, wink scrolling, double-blink dictation, the tutorial and text entry are **separate tests**. During an evaluation run all of them are switched off. No click, scroll, dictation, tutorial action or gesture-triggered recalibration can fire, and GPT experiments are disabled. This report measures the gaze coordinate those features consume, not the features themselves. Meeting the gaze targets here does not show that small buttons can be selected reliably.

---

## 2. Tested Build, Configuration, and Protocol

### Build and model identity

| Item | Value |
|---|---|
| Application base | `main` at `fd387ccf785582c016793da30c5789e5153e9253` |
| Instrumented build | Local branch `feature/gaze-evaluation-iter3`, Stage 1 pre-run commit. Each session records the exact build commit (`build.git_commit`), whether the working tree was dirty at build time (`build.git_dirty_at_build`) and the merge base with `origin/main` (`build.git_base_main`). |
| Primary predictor | The **personal nine-point calibration** `GazeModel`, completed in the current tracking run just before the session |
| Predictor verification | Before a run starts, the app refits the saved calibration with `GazeModel.fit` and requires its parameters to equal the live model's exactly. It also requires a matching device, display size and rotation key, and a calibration that no earlier run has used. A run never falls back to the basic iris pointer or to another saved model. If the active model changes during a run, the run ends as incomplete. |
| Frozen model input | `model_snapshot.json` in each session: the saved calibration text, its SHA-256 and the fitted parameters |
| Libraries | MediaPipe Tasks Vision 0.10.26, CameraX 1.4.2, `face_landmarker.task` (SHA-256 recorded per session) |
| Device and display | Recorded per session: manufacturer, model, Android version, `DisplaySpace` size in px, rotation, density, evaluation-view offset |

### Conditions

Before each run the operator enters the **viewing distance** (measured with a ruler, cm), the **lighting** and **glasses** (none / glasses / contact lenses), plus optional notes. These are declared values and are stored in `metadata.json` → `conditions`. Keep the phone on a stable mount in a fixed orientation.

### Calibration

Every session follows a **fresh nine-point calibration**, completed in the same tracking run. The app enforces this: one evaluation run per calibration, including runs that were interrupted.

### Case protocol

Full specification: [`protocol.json`](protocol.json) (protocol `notouch-gaze-eval-iter3` version 1.0.0). It holds the 32 case definitions, timing, coordinate definitions, validity and sufficiency rules, thresholds, and assertion and aggregation rules. Each session records the SHA-256 of this file (computed after converting CRLF to LF), and the analyzer rejects sessions whose recorded hash differs.

| Element | Definition |
|---|---|
| Coordinates | Full-display normalized: x = px / display width, y = px / display height (`DisplaySpace.bounds`). Calibration anchors, targets and predictions all use this basis. Origin top-left; +x right, +y down. |
| Targets | 4 × 4 grid at 0.20, 0.40, 0.60, 0.80 on each axis |
| Order | Pass 1: serpentine from the top-left. Pass 2: pass 1 reversed. 32 presentations with unique case IDs (`pass1-r0c0` … `pass2-r0c0`). |
| Timing | 2,500 ms per presentation: 1,000 ms settling, then 1,500 ms measurement. 80 s of targets in total. |
| Sample assignment | By each frame's acceptance timestamp (monotonic `SystemClock.uptimeMillis`) against the schedule, never by the target on screen when a delayed result arrives |
| Primary mode | `blind_measurement`, pointer hidden. `live_preview` (pointer visible) exists as a secondary mode and is always reported separately. |
| Filter context | The gaze filter is reset when the run clock starts. Every later reset (stale delivery, camera watchdog, camera error, notification actions) is logged in order. |

### Participant plan

- **Initial collection:** participant **P01**, **two sessions** (S1, S2), each after a fresh nine-point calibration.
- **Further participants:** optional, added later under the same unchanged protocol (see [Section 8](#8-artifact-index-and-adding-participants)).

---

## 3. Metrics and Predeclared Pass/Fail Criteria

These Iteration 3 **engineering targets** were fixed in Stage 1, before any session, and must not be changed after results are seen. They are prototype tolerances, not evidence that small buttons can be selected reliably. All comparisons use unrounded values.

### Definitions

| Metric | Definition | Unit |
|---|---|---|
| Spatial error | `e = sqrt((pred_x - target_x)^2 + (pred_y - target_y)^2)`; pixels: `sqrt(((pred_x-target_x)·W)^2 + ((pred_y-target_y)·H)^2)` with the session's display size W × H | normalized (not % of the screen diagonal, not a visual angle); px |
| Presentation mean error | Mean error of the presentation's valid measurement samples | normalized, px |
| Session mean error | Mean of presentation mean errors (each eligible presentation weighted equally) | normalized, px |
| p95 presentation error | Nearest-rank p95 (`k = ceil(0.95·n)`) of **presentation mean errors**. Not a p95 over camera samples. | normalized |
| Jitter RMS | Within a presentation, `sqrt(mean((x-mean_x)^2 + (y-mean_y)^2))`; session value is the mean over eligible presentations | normalized, px |
| Tracking availability | Valid measurement attempts / all measurement-phase processing attempts (invalid ones included) | fraction |
| Valid sample rate | Valid measurement samples / measurement-window time (32 × 1.5 s = 48 s per complete run) | samples/s |
| Processing latency | From acceptance of a frame for processing to publication of its result on the main thread, or its explicit discard. Includes invalid, stale and discarded results. Not FaceLandmarker time alone, not physical eye-to-display latency, not dwell time. | ms |
| Diagnostics | Signed X/Y bias; fraction of off-screen final coordinates; invalid attempts by actual outcome or quality; cadence-skipped frames; filter resets by cause; logging-loss and integrity counts; the raw predictor output as a separate stream | — |

**Validity.** A valid measurement sample is a measurement-phase attempt with outcome `processed`, quality `TRACKING` and finite final x and y. Invalid attempts stay in availability accounting with their real reason (for example `lost`, `closed`, `out_of_range`, `stale_delivery`, `discarded_epoch`). They are never imputed, and missing coordinates never count as zero error. Finite off-screen final coordinates stay in every spatial metric. The pointer's rendering clamp to [0.015, 0.985] is logged separately and never used for error.

**Technical eligibility** (for diagnostic spatial summaries): a fully shown presentation with ≥ 10 valid measurement samples and coverage ≥ 0.5. The p95-error and jitter criteria are `INSUFFICIENT_DATA` when fewer than 26 of 32 presentations are eligible (⌈0.8 × 32⌉, reusing the predeclared 0.80 rate).

### Predeclared targets

| Criterion | Target |
|---|---|
| Successful presentation | Mean final error ≤ 0.10; valid-attempt coverage ≥ 0.80; ≥ 10 valid measurement samples; fully shown |
| Successful-presentation rate | ≥ 0.80 of all 32 planned presentations |
| p95 presentation error | ≤ 0.20 normalized |
| Mean per-presentation jitter RMS | ≤ 0.05 normalized |
| Session tracking availability | ≥ 0.90 |
| Session valid sample rate | ≥ 10 valid measurements per second of measurement time |
| p95 processing latency | ≤ 200 ms |

### Statuses and aggregation

- **Statuses:** `MET`, `NOT_MET`, `NOT_MEASURED` (no data yet), `INSUFFICIENT_DATA` (too few data, or failed integrity checks), `INCOMPLETE` (the session ended early).
- **Session verdict:** `MET` only if all six criteria are `MET`. An incomplete session is always `INCOMPLETE`. Logging loss, unassigned rows or a checksum mismatch make it `INSUFFICIENT_DATA`.
- **Participant verdict:** uses the complete sessions within one compatibility group. It is `MET` only if every such session is `MET`, and `NOT_MET` if any is `NOT_MET`.
- **Participant summary values:**
  - The success rate, p95 error and jitter are recomputed from pooled presentations, never by averaging session p95 values.
  - Availability and sample rate weight each session equally.
  - Latency is reported as the worst session p95.
  - No summary is weighted by frame count.
- **Kept separate:** blind and live modes, and different builds, devices, display sizes, rotations or protocol versions, are never summarized together.
- **Official sessions:** a session counts as official only if it is complete, passes the integrity checks, has verified checksums, ran in blind mode, came from a clean committed build, and used a fresh verified calibration that no other session shares.

---

## 4. Session Registry and Results

**No real sessions have been collected yet.** All values are pending. The table is filled from `data/derived/sessions.csv` after collection.

| Participant | Session | Session ID | Build commit (dirty?) | Model / calibration | Conditions (distance, light, glasses) | Success rate | p95 error | Mean jitter | Availability | Valid/s | p95 latency (ms) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P01 | S1 | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | NOT_MEASURED |
| P01 | S2 | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | NOT_MEASURED |

| Participant | Complete / planned sessions | Pooled success rate | Pooled p95 error | Participant verdict |
|---|---|---|---|---|
| P01 | 0 / 2 | *pending* | *pending* | NOT_MEASURED |

Incomplete and rejected sessions will be listed here with their end reasons. None exist yet.

---

## 5. Visualizations

**Later stage.** No charts have been made, and none are embedded here. Planned figures will go in [`figures/`](figures/README.md), built from `data/derived/` only. They will be designed for readers unfamiliar with NoTouch.

---

## 6. Interpretation, Failure Analysis, and Limitations

### Interpretation

> *Pending real sessions. Not written in Stage 1.*

### Failure analysis

> *Pending real sessions. No failure causes are suggested before data exist.*

### Methodological limitations (known now)

- **No independent ground truth.** Targets are instructed fixation points. Where the participant actually looked is not measured.
- **Small sample.** The initial plan is one participant (P01) and two sessions on one phone. Results describe that participant, device and set of conditions only.
- **Declared conditions.** Viewing distance, lighting and glasses are entered by the operator, not measured by the app.
- **Screen-normalized units.** Errors are in full-display normalized units and pixels. There is no visual-angle conversion. One normalized unit is a different physical length on each axis.
- **Latency scope.** Processing latency covers frame acceptance to main-thread publication. It excludes camera exposure and readout before acceptance, display refresh after publication, and physical eye-to-display time.
- **Cadence limiter.** Frames arriving within 33 ms of the previously accepted frame are skipped on purpose. They are counted separately and are not invalid attempts.
- **Frames never delivered.** CameraX's keep-only-latest backpressure drops frames before they reach the app. The app cannot see or count them.
- **Replay scope.** Offline replay re-runs the numerical module (model and filter) from logged features. It does not re-run the camera or landmark detection, and its timing is not a latency measurement.
- **Fixed engineering thresholds.** The thresholds are prototype targets. Meeting them does not establish usability or small-button accuracy.

---

## 7. Reproduction

*Provisional. The offline commands below were run successfully in Stage 1 (Windows, JDK 21, Android SDK 35, Python 3.14). Phone collection, export and the full end-to-end path are **awaiting validation on a real device**.*

### Offline build and checks (verified in Stage 1)

Run from the repository root with a JDK 17 or 21 and the Android SDK (`local.properties` or `ANDROID_HOME`):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python -B -m unittest discover -s tools/gaze_eval -v
```

- **APK:** `app/build/outputs/apk/debug/app-debug.apk` (debug only; the evaluation entry exists only in debuggable builds).
- **Build provenance:** taken from Git at build time. Build the session APK from a clean, committed tree so that `git_dirty_at_build` is `false`.

### Collecting a session (awaiting phone validation)

1. **Install** the debug APK built from the committed tree: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
   - If the phone has NoTouch signed with a different key, the install fails. Uninstalling deletes the app's saved models and any evaluation data still on the phone, so export first.
2. **Set up the phone.** Mount it in a fixed orientation, then enable Do Not Disturb. Measure the eye-to-screen distance with a ruler.
3. **Calibrate.** Press **카메라 시작**, wait for eye detection, then press **보정 시작** and complete the nine-point calibration.
4. **Leave the tutorial.** The tutorial opens after calibration. Press **Back** to return home. Do not stop the camera, and do not start another calibration from home.
   - On the home screen, a 1-second gaze still selects buttons. Move on promptly.
5. **Open the evaluation screen.** Go to **설정 · 개발자 기능** → **시선 정확도 평가 (Iteration 3 · 새 9점 보정 직후)**.
6. **Enter the session details.**
   - Participant `P01`, session `S1` (or `S2`).
   - Distance in cm, lighting, glasses.
   - Mode: **블라인드 측정 (기본)**.
7. **Run it.** Press **평가 시작**. The participant keeps their head still and looks at each blue dot until the run ends after 80 s.
   - **Back**, leaving the screen, rotating it, or stopping tracking ends the run. Such a run is saved as incomplete.
8. **Repeat for S2** after a **new** nine-point calibration. The app refuses a second run on the same calibration.

Files are written to app-private storage: `files/evaluation/iter3/<participant>/<session_id>/`. Each session folder contains `attempts.csv`, `events.csv`, `metadata.json`, `model_snapshot.json`, and `SHA256SUMS` once finalized.

### Export and import (awaiting phone validation)

Use `cmd /c` for the redirection. In Windows PowerShell 5.1, `>` re-encodes output and corrupts binary data.

```powershell
New-Item -ItemType Directory -Force docs\evaluation\iteration3\data\incoming | Out-Null
adb shell run-as dev.gazedot ls -R files/evaluation/iter3
cmd /c "adb exec-out run-as dev.gazedot tar -cf - files/evaluation/iter3 > docs\evaluation\iteration3\data\incoming\export.tar"
tar -xf docs\evaluation\iteration3\data\incoming\export.tar -C docs\evaluation\iteration3\data\incoming
python tools/gaze_eval/gaze_eval_data.py verify docs/evaluation/iteration3/data/incoming/files/evaluation/iter3
python tools/gaze_eval/gaze_eval_data.py import docs/evaluation/iteration3/data/incoming/files/evaluation/iter3 --raw docs/evaluation/iteration3/data/raw
```

- **`incoming/` is Git-ignored.** Only imported sessions under `data/raw/` are versioned.
- **What `import` does:** it copies files byte for byte and never overwrites an existing session. It refuses a session whose `SHA256SUMS` check fails.
- **Alternative export:** Android Studio's Device Explorer (`/data/data/dev.gazedot/files/evaluation/iter3`).

### Freeze, replay and analyze (commands exist; no real data yet)

```powershell
python tools/gaze_eval/gaze_eval_data.py freeze docs/evaluation/iteration3/data/raw --out docs/evaluation/iteration3/data/raw-inputs.sha256
.\gradlew.bat :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/evaluation/iteration3/data/raw -PgazeReplayOut=docs/evaluation/iteration3/data/derived/replay
python tools/gaze_eval/analyze_gaze_eval.py docs/evaluation/iteration3/data/raw --out docs/evaluation/iteration3/data/derived --source-label "<build commit / dataset note>"
```

1. **Freeze** the raw inputs before the official analysis. `check-frozen` confirms later that they have not changed.
2. **Replay** rebuilds the predictor from each `model_snapshot.json` and re-runs the logged observations through the app's own `GazePipeline`. It requires every processed attempt to reproduce its logged quality and raw and final coordinates exactly. Without `-PgazeReplaySession` the runner is skipped.
3. **Analyze.** The analyzer writes `summary.json`, `sessions.csv`, `presentations.csv` and `report.md` (metrics only). It does not modify raw data.

---

## 8. Artifact Index and Adding Participants

| Location | Content |
|---|---|
| [`protocol.json`](protocol.json) | Versioned protocol manifest: cases, timing, coordinates, validity, thresholds, assertions and aggregation |
| [`data/raw/`](data/raw/README.md) | Recorded sessions, `data/raw/<participant_id>/<session_id>/`, stored byte for byte. Empty in Stage 1. |
| [`data/derived/`](data/derived/README.md) | Analyzer and replay outputs (regenerated, never edited by hand). Empty in Stage 1. |
| [`figures/`](figures/README.md) | Future figures (later stage) |
| `tools/gaze_eval/analyze_gaze_eval.py` | Metrics-only analyzer (schema v2) |
| `tools/gaze_eval/gaze_eval_data.py` | Checksum verification, import, freeze |
| `tools/gaze_eval/testdata/` | **Synthetic** test fixtures only, not participant data |
| `app/src/main/java/dev/gazedot/Evaluation*.kt` | Evaluation protocol, start gate, run screen and logger |
| `app/src/test/java/dev/gazedot/Evaluation*.kt`, `SyntheticEvaluation.kt` | JVM tests, synthetic driver and the replay tool |

### Adding participants

1. Use the unchanged protocol. If `protocol.json` changes, that is a new protocol version, and earlier sessions are not comparable.
2. Collect sessions with the same procedure, using a new participant code (for example `P02`). Each session follows a fresh calibration.
3. Import with `gaze_eval_data.py import`. New sessions are appended under `data/raw/<participant_id>/<session_id>/`, and existing sessions are never overwritten.
4. Re-freeze the raw inputs, then rerun replay and the analyzer.
   - **Discovery:** the analyzer finds participants and sessions from each `metadata.json`, not from folder names.
   - **Grouping:** it keeps different builds, devices, displays and modes in separate groups.
5. Add the new rows to the registry in [Section 4](#4-session-registry-and-results). No participant count is required. Rows are never added for sessions that do not exist.

Historical results from the earlier `feature/gaze-accuracy-evaluation` branch (`test_results/`) belong to a different build and log schema. They are not part of this dataset.
