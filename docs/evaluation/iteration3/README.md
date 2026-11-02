# NoTouch Iteration 3: Gaze-Pipeline Evaluation of the Home-Screen Candidate

*Testing Documentation → AI Module Testing*

**Status: pre-run. Instrumentation, protocol 2.0.0, metrics and offline checks are in place for the home-screen candidate. No real session has been collected, and this instrumented build has not yet been run on a phone.** Every result field below is pending. Nothing in this document is a measurement of the gaze pipeline.

This file is maintained by hand. The analyzer never writes to it; its generated outputs go to [`data/derived/`](data/derived/README.md).

---

## 1. Purpose and Scope

### What is evaluated

The **local gaze-estimation pipeline of the home-screen candidate** (`feature/home-screen` at `30be1cf`), the version tested subjectively on the phone and preferred over the integrated `main` pipeline. It is a different, simpler pipeline than the one protocol 1.0.0 was written for:

| Stage | Candidate implementation | Logged as |
|---|---|---|
| Camera | CameraX ImageAnalysis, front camera, keep-only-latest; frames within **66 ms** of the last accepted frame are skipped | counters |
| Landmarks | MediaPipe Face Landmarker, VIDEO mode, one face | `detector` |
| Features | `LandmarkFeatures.extract`: iris displacement in both eyes, nose position, eye-span scale (**5 features**), or a rejection | `f_*`, `feature_rejection` |
| Model | `GazeModel.predict`: ridge linear map from the nine-point calibration. Returns null for non-finite features or when scale (outside 0.72–1.28 ×) or face position (> 0.12) left the calibration range. **Clamps each axis to [0, 1].** | `linear_*` (before the clamp, diagnostic), `predicted_*` (after), `prediction_rejection` |
| Smoothing | `GazeSmoother`: `s + 0.28 · (p − s)` per axis. A null prediction clears it; so does the 800 ms no-result watchdog | `smoothed_*` (**measured**), `smoothing_reset` events |
| Display | `TrackerState.publish` and `DotOverlay`, which draws the smoothed point without a further clamp | `rendered_*_px` (live preview only) |

**The measured coordinate is `smoothed_*`**: the smoothed output the candidate supplies to `TrackerState.gaze` and the overlay. It is **not an unclamped raw output**. It inherits the model's [0, 1] clamp, so it can never leave the screen.

### Questions this evaluation answers

1. **Accuracy.** How far is the smoothed gaze coordinate from a target the participant was told to look at?
2. **Stability.** How much does it move while the participant holds a fixation?
3. **Availability.** How often does a processing attempt during a fixation produce a valid coordinate?
4. **Throughput.** How many valid coordinates per second does the pipeline deliver?
5. **Processing latency.** How long does it take from accepting a camera frame to publishing its result on the main thread?
6. **Engineering targets.** Do these meet the unchanged Iteration 3 targets in [Section 3](#3-metrics-and-predeclared-passfail-criteria)?

### Ground truth

Targets are **instructed fixation points**. No independent eye tracker measures where the participant actually looked, so every spatial value is a *target-referenced gaze error*, not a ground-truth eye-direction error.

### What is not evaluated here

The candidate has no click, scroll, dictation or text-entry features. Its only gaze interaction is the 1-second dwell button of the post-calibration tutorial, which is a separate check. The tutorial belongs to the home screen and is closed while the evaluation screen is shown, so no dwell can fire during a run. Meeting the gaze targets here does not show that small buttons can be selected reliably.

---

## 2. Tested Build, Configuration, and Protocol

### Build and model identity

| Item | Value |
|---|---|
| Source base | `feature/home-screen` at `30be1cf726a47ab5751bd8e8bb4f537d0c82c810` (unchanged pipeline) |
| Evaluation reference | `feature/gaze-evaluation-iter3` at `f59bf6acb40c4c1a7eaee5547caea4a382139db1` (protocol 1.0.0, integrated main pipeline). Its infrastructure was reused selectively; the commit was not merged or cherry-picked. |
| Instrumented build | Local branch `feature/gaze-evaluation-home-screen-iter3`. Each session records the build commit (`build.git_commit`), whether the tree was dirty at build time (`build.git_dirty_at_build`), whether the source base is an ancestor (`build.source_base_is_ancestor`) and the evaluation reference. **The session APK is not the f59bf6a build.** |
| Application | `dev.gazedot`, versionCode 1, versionName 0.1.0, debug signing (as in the candidate) |
| Predictor | The candidate's **nine-point calibration** `GazeModel`, completed in the current tracking run just before the session |
| Predictor verification | The candidate keeps calibration only in memory. When a calibration completes, its exact `GazeModel.fit` inputs (nine anchors) and its normalization basis are frozen with the live model. Before a run starts, the app refits `GazeModel.fit` on the frozen inputs and requires every parameter to equal the live model's exactly. It also requires the same display basis and rotation as the calibration, and a calibration no earlier run has used. If the active model changes during a run, the run ends as incomplete. |
| Frozen model input | `model_snapshot.json`: the nine anchors, their SHA-256 (`calibration_inputs_sha256`), the calibration basis and the fitted parameters |
| Libraries | MediaPipe Tasks Vision 0.10.26, CameraX 1.4.2, `face_landmarker.task` (SHA-256 recorded per session) |
| Device and display | Recorded per session: manufacturer, model, Android version, calibration basis (`maximumWindowMetrics`) in px, rotation, density, and the evaluation, calibration and overlay view geometry |

### Conditions

Before each run the operator enters the **viewing distance** (measured with a ruler, cm), the **lighting** and **glasses** (none / glasses / contact lenses), plus optional notes. These are declared values stored in `metadata.json` → `conditions`. Keep the phone on a stable mount in a fixed portrait orientation.

### Calibration

Every session follows a **fresh nine-point calibration**, completed in the same tracking run. The app enforces this: one evaluation run per calibration, including runs that were interrupted.

### Case protocol

Full specification: [`protocol.json`](protocol.json) (protocol `notouch-gaze-eval-iter3` **version 2.0.0**). Each session records the SHA-256 of this file (computed after converting CRLF to LF), and the analyzer rejects sessions whose recorded hash differs.

| Element | Definition |
|---|---|
| Coordinates | Full-display normalized in the candidate's calibration basis: x = px / basis width, y = px / basis height, where the basis is `WindowManager.maximumWindowMetrics` bounds (API 30+, which evaluation requires). Calibration anchors, targets and predictions all use it. Origin top-left; +x right, +y down. |
| Target drawing | View-local `(x · basis width − view left, y · basis height − view top)`: the inverse of the calibration screen's own anchor normalization |
| Targets | 4 × 4 grid at 0.20, 0.40, 0.60, 0.80 on each axis (unchanged) |
| Order | Pass 1: serpentine from the top-left. Pass 2: pass 1 reversed. 32 presentations, `pass1-r0c0` … `pass2-r0c0` (unchanged). |
| Timing | 2,500 ms per presentation: 1,000 ms settling, then 1,500 ms measurement. 80 s in total (unchanged). |
| Sample assignment | By each frame's acceptance timestamp (`SystemClock.uptimeMillis`) against the schedule, never by when its result arrives (unchanged) |
| Run start | The run clock starts 100 ms after the run screen is laid out. Logging and the `run_start` smoothing reset happen before it. Results in flight at run start (accepted earlier, not logged) are dropped before smoothing and counted (`unlogged_results_dropped_pre_run`). An unlogged result accepted during the run would be a missing attempt and an integrity error. |
| Smoothing context | Every smoothing reset (run start, watchdog) is logged in main-thread order and stays logged until the session is finalized, because attempts still in flight at the end are smoothed after it. A camera error is logged as an event; it hides the pointer but does not reset smoothing in this pipeline. |
| Primary mode | `blind_measurement`, pointer hidden. `live_preview` (pointer visible) exists as a secondary mode and is always reported separately. |

### Protocol and schema changes from 1.0.0

Protocol **2.0.0** and log **schema 3** replace protocol 1.0.0 / schema 2 because the measured pipeline is different:

- **Measured output:** the candidate's smoothed coordinate (clamped by the model) instead of the integrated pipeline's unclamped `GazeSample.point`. The stages are named for what they are: `linear` (model output before its clamp, diagnostic only), `predicted` (after the clamp), `smoothed` (measured) and `rendered` (overlay pixels). Nothing is labelled "raw".
- **Features and rejections:** 5 candidate features instead of 15. Invalid attempts carry the candidate's real outcomes: `no_features` (with `feature_rejection` such as `eye_33_133_aperture_ratio_lt_0.10` or detector `no_face`) and `prediction_rejected` (with `prediction_rejection` such as `face_x_shift_gt_0.12`). `stale_delivery`, `discarded_epoch` and eye-state classes do not exist in this pipeline and are not invented.
- **Calibration:** frozen from memory (anchors) instead of a saved `ModelStore` calibration.
- **Cadence and timing:** the candidate's 66 ms cadence instead of 33 ms; there is no stale-result rule; 100 ms run-start lead; explicit accounting of in-flight results at run start and end.
- **Compatibility:** the analyzer, replay and import refuse schema 2 and any other pipeline variant. Old-main and candidate recordings are never pooled.

The grid, cases, order, schedule, windows, validity of missing coordinates, data sufficiency, all thresholds, metric formulas and aggregation rules are **unchanged** (checked by `EvaluationProtocolTest` and `ManifestTest`).

### Participant plan

- **Technical pilot (optional, before S1):** participant code `PILOT01` (any code starting with `PILOT`), session labels `T1`, `T2`, …. Pilot sessions are stored and imported **separately** under `data/pilot/` (Git-ignored) and are never official. Each still needs its own fresh calibration.
- **Initial collection:** participant **P01**, **two sessions** (S1, S2), each after a fresh nine-point calibration.
- **Further participants:** optional, added later under the same unchanged protocol (see [Section 8](#8-artifact-index-and-adding-participants)).

---

## 3. Metrics and Predeclared Pass/Fail Criteria

These Iteration 3 **engineering targets** are carried over unchanged from protocol 1.0.0, fixed before any session, and must not be changed after results are seen. They were not tuned to this candidate. They are prototype tolerances, not evidence that small buttons can be selected reliably. All comparisons use unrounded values.

### Definitions

| Metric | Definition | Unit |
|---|---|---|
| Spatial error | `e = sqrt((x - target_x)^2 + (y - target_y)^2)` of the smoothed coordinate; pixels: `sqrt(((x-target_x)·W)^2 + ((y-target_y)·H)^2)` with the session's basis W × H | normalized (not % of the diagonal, not a visual angle); px |
| Presentation mean error | Mean error of the presentation's valid measurement samples | normalized, px |
| Session mean error | Mean of presentation mean errors (each eligible presentation weighted equally) | normalized, px |
| p95 presentation error | Nearest-rank p95 (`k = ceil(0.95·n)`) of **presentation mean errors**, not of camera samples | normalized |
| Jitter RMS | Within a presentation, `sqrt(mean((x-mean_x)^2 + (y-mean_y)^2))`; session value is the mean over eligible presentations | normalized, px |
| Tracking availability | Valid measurement attempts / all measurement-phase processing attempts (invalid ones included) | fraction |
| Valid sample rate | Valid measurement samples / measurement-window time (32 × 1.5 s = 48 s per complete run) | samples/s |
| Processing latency | From acceptance of a frame for processing to publication of its result on the main thread, or its explicit discard. Includes invalid and discarded results. Not FaceLandmarker time alone, not physical eye-to-display latency. | ms |
| Diagnostics | Signed X/Y bias; off-screen fraction of the smoothed coordinate (zero by construction); **model clamp active fraction** (linear output outside [0, 1]); errors of the `predicted` and `linear` streams; invalid attempts by outcome, quality and the candidate's rejection names; cadence-skipped frames; smoothing resets by cause; logging-loss and integrity counts | — |

**Validity.** A valid measurement sample is a measurement-phase attempt with outcome `processed`, quality `tracking` and finite smoothed x and y. Invalid attempts stay in availability accounting with their real reason. They are never imputed, and missing coordinates never count as zero error.

**Clamping.** The model clamp limits every measured coordinate to the screen. A gaze estimate that overshoots an edge is recorded as being *at* the edge, which can make edge targets look more accurate than an unclamped estimate would. An off-screen fraction of zero therefore does **not** show that predictions never overshoot. The `linear` stream and `model_clamp_active_fraction` report how often and how far the clamp acted; they are diagnostics, not the measured coordinate.

**Technical eligibility** (for diagnostic spatial summaries): a fully shown presentation with ≥ 10 valid measurement samples and coverage ≥ 0.5. The p95-error and jitter criteria are `INSUFFICIENT_DATA` when fewer than 26 of 32 presentations are eligible (⌈0.8 × 32⌉).

### Predeclared targets (unchanged)

| Criterion | Target |
|---|---|
| Successful presentation | Mean error ≤ 0.10; valid-attempt coverage ≥ 0.80; ≥ 10 valid measurement samples; fully shown |
| Successful-presentation rate | ≥ 0.80 of all 32 planned presentations |
| p95 presentation error | ≤ 0.20 normalized |
| Mean per-presentation jitter RMS | ≤ 0.05 normalized |
| Session tracking availability | ≥ 0.90 |
| Session valid sample rate | ≥ 10 valid measurements per second of measurement time |
| p95 processing latency | ≤ 200 ms (over all run attempts with a measured latency) |

### Statuses and aggregation

- **Statuses:** `MET`, `NOT_MET`, `NOT_MEASURED`, `INSUFFICIENT_DATA` (too few data, or failed integrity checks), `INCOMPLETE` (the session ended early).
- **Session verdict:** `MET` only if all six criteria are `MET`. An incomplete session is always `INCOMPLETE`. Logging loss, unassigned rows, unlogged in-run results or a checksum mismatch make it `INSUFFICIENT_DATA`.
- **Participant verdict:** uses the complete sessions within one compatibility group. `MET` only if every such session is `MET`; `NOT_MET` if any is `NOT_MET`.
- **Participant summary values:** success rate, p95 error and jitter are recomputed from pooled presentations (never averaged session p95s); availability and sample rate weight sessions equally; latency is the worst session p95; nothing is frame-weighted.
- **Kept separate:** pipeline variants, blind and live modes, builds, devices, display sizes, rotations and protocol versions are never summarized together.
- **Official sessions:** complete, integrity checks passed, checksums verified, blind mode, a clean committed build descending from the candidate source base, a fresh verified calibration no other session shares, and not a `PILOT*` participant.

---

## 4. Session Registry and Results

**No real sessions have been collected yet.** All values are pending. The table is filled from `data/derived/sessions.csv` after collection.

| Participant | Session | Session ID | Build commit (dirty?) | Calibration inputs SHA-256 | Conditions (distance, light, glasses) | Success rate | p95 error | Mean jitter | Availability | Valid/s | p95 latency (ms) | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P01 | S1 | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | NOT_MEASURED |
| P01 | S2 | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | *pending* | NOT_MEASURED |

| Participant | Complete / planned sessions | Pooled success rate | Pooled p95 error | Participant verdict |
|---|---|---|---|---|
| P01 | 0 / 2 | *pending* | *pending* | NOT_MEASURED |

Incomplete and rejected sessions will be listed here with their end reasons. None exist yet. Technical-pilot sessions are not listed here.

---

## 5. Visualizations

**Later stage.** No charts have been made, and none are embedded here. Planned figures will go in [`figures/`](figures/README.md), built from `data/derived/` only, for readers unfamiliar with NoTouch.

---

## 6. Interpretation, Failure Analysis, and Limitations

### Interpretation

> *Pending real sessions. Not written yet.*

### Failure analysis

> *Pending real sessions. No failure causes are suggested before data exist.*

### Methodological limitations (known now)

- **No independent ground truth.** Targets are instructed fixation points.
- **Clamped measured coordinate.** The candidate's model clamps predictions to [0, 1] before smoothing, so overshoot at the screen edges is hidden in the measured coordinate and edge-target errors can be smaller than the model's linear estimate. The clamp's effect is reported (linear stream, clamp-active fraction) but not removed from the primary metrics, because the primary metrics measure what this pipeline delivers.
- **Small sample.** One participant (P01) and two sessions on one phone. Results describe that participant, device and set of conditions only.
- **Declared conditions.** Viewing distance, lighting and glasses are entered by the operator.
- **Screen-normalized units.** No visual-angle conversion; one normalized unit is a different physical length on each axis.
- **Latency scope.** Frame acceptance to main-thread publication only; excludes exposure and readout before acceptance, display refresh after publication, and eye-to-display time.
- **Cadence limiter.** The candidate skips frames within 66 ms of the last accepted frame (at most about 15 attempts per second). Skipped frames are counted separately and are not invalid attempts. CameraX keep-only-latest drops frames before the app sees them; those cannot be counted.
- **Run-start reset.** The smoothing state is cleared once when the run clock starts so replay has a complete context. In normal use the candidate never clears it at that moment; the effect falls inside the first 1,000 ms settling phase.
- **Display basis.** Evaluation requires Android 11 (API 30) or later, where the candidate's calibration basis is the full window metrics. On older devices the candidate normalizes calibration by the calibration view size, which is not a display basis; the app refuses to evaluate there.
- **Replay scope.** Offline replay re-runs `GazeModel.fit`, `GazeModel.predict` and `GazeSmoother` from logged features on the JVM. It does not re-run the camera or landmark detection, and its timing is not a latency measurement. Values are compared as exact strings, with no tolerance. `GazeModel.fit` uses `Math.pow`; if Android's and the JVM's `pow` ever differ in the last bit, replay reports it as a parameter mismatch in ULPs instead of hiding it.
- **Hooks not yet run on a phone.** The `TrackingService` and evaluation-screen hooks are covered by compilation, lint and a JVM driver that mirrors them, but this instrumented build has not been installed or run on a device. The technical pilot is the first on-device check.
- **Fixed engineering thresholds.** The thresholds are prototype targets. Meeting them does not establish usability or small-button accuracy.

---

## 7. Reproduction

*The offline commands below were run successfully before any session (Windows, JDK 21, Android SDK 35, Python 3.14). Phone collection, export and the on-device path are **awaiting validation on a real device**.*

### Offline build and checks

Run from the repository root with JDK 17 or 21 (`JAVA_HOME`, terminal-local) and the Android SDK (`local.properties` with `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`, Git-ignored):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python -B -m unittest discover -s tools/gaze_eval -v
```

- **APK:** `app\build\outputs\apk\debug\app-debug.apk` (debug only; the evaluation entry exists only in debuggable builds).
- **Build provenance:** taken from Git at build time. Build the session APK from a clean, committed tree so that `git_dirty_at_build` is `false`.

### Installing (awaiting phone validation)

Use the full path to `adb.exe` (for example `C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools\adb.exe`), and back up any evaluation data already on the phone first.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

If the phone has `dev.gazedot` signed with a different key or with a higher versionCode, the plain update fails. Do not uninstall before exporting: uninstalling deletes `files/evaluation/iter3`.

### Collecting a session (awaiting phone validation)

1. **Set up.** Mount the phone in portrait and keep it fixed. Enable Do Not Disturb. Measure the eye-to-screen distance with a ruler.
2. **Start the camera.** Open **NoTouch** (app label "GazeDot 시선 실험"). Under **1 카메라 시작**, tap **카메라 시작** and allow the overlay, camera and notification permissions if asked. Wait for the status line **눈 검출됨 · 9점 보정을 시작해 주세요**.
3. **Calibrate.** Under **2 시선 보정**, tap **보정 시작**. Keep your head still and look at each blue dot until its ring fills (nine points, with a 2-second blink break after point 4).
4. **Leave the tutorial.** After **시선 보정이 완료되었습니다!** the tutorial shows a **여기를 바라보세요** button. Press **Back** to return home. Do not tap **추적 중지** and do not start another calibration.
5. **Open the evaluation screen.** On the home screen, tap the small blue link **시선 정확도 평가 (개발용)** at the very bottom (below **추적 중지**). The status should read **새 9점 보정: 준비됨** and **평가를 시작할 수 있습니다.**
6. **Enter the session details.** Participant `P01` and session `S1` (or `S2`); for the technical pilot `PILOT01` and `T1`. Distance in cm (10–150), lighting, glasses (**없음** / **안경** / **콘택트렌즈**). Mode **블라인드 측정 (기본)**.
7. **Run it.** Tap **평가 시작**. The participant keeps their head still and looks at each blue dot until the screen returns to the setup form after 80 s with **평가 완료 · …**.
   - **Back**, leaving the screen, rotating, turning the screen off or stopping tracking ends the run. Such a run is saved as incomplete.
8. **Repeat for S2** after returning home (**돌아가기** or Back) and completing a **new** calibration with **보정 시작**. The app refuses a second run on the same calibration (**이 보정으로 이미 평가를 시작했습니다…**).

Files are written to app-private storage: `files/evaluation/iter3/<participant>/<session_id>/`, each with `attempts.csv`, `events.csv`, `metadata.json`, `model_snapshot.json`, and `SHA256SUMS` once finalized.

### Export and import (awaiting phone validation)

Export **one participant folder at a time** so pilot and official sessions never travel together. Use `cmd /c` for the redirection: in Windows PowerShell 5.1, `>` re-encodes output and corrupts binary data. The `cmd /c` lines below assume the `adb.exe` path contains no spaces (true for the default SDK location under `%LOCALAPPDATA%`).

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell run-as dev.gazedot ls -R files/evaluation/iter3

# Official sessions (P01)
New-Item -ItemType Directory -Force docs\evaluation\iteration3\data\incoming | Out-Null
cmd /c "$adb exec-out run-as dev.gazedot tar -cf - files/evaluation/iter3/P01 > docs\evaluation\iteration3\data\incoming\export-P01.tar"
tar -xf docs\evaluation\iteration3\data\incoming\export-P01.tar -C docs\evaluation\iteration3\data\incoming
python tools/gaze_eval/gaze_eval_data.py verify docs/evaluation/iteration3/data/incoming/files/evaluation/iter3/P01
python tools/gaze_eval/gaze_eval_data.py import docs/evaluation/iteration3/data/incoming/files/evaluation/iter3/P01 --raw docs/evaluation/iteration3/data/raw

# Technical pilot (PILOT01): separate storage, never official
New-Item -ItemType Directory -Force docs\evaluation\iteration3\data\pilot\incoming | Out-Null
cmd /c "$adb exec-out run-as dev.gazedot tar -cf - files/evaluation/iter3/PILOT01 > docs\evaluation\iteration3\data\pilot\incoming\export-PILOT01.tar"
tar -xf docs\evaluation\iteration3\data\pilot\incoming\export-PILOT01.tar -C docs\evaluation\iteration3\data\pilot\incoming
python tools/gaze_eval/gaze_eval_data.py verify docs/evaluation/iteration3/data/pilot/incoming/files/evaluation/iter3/PILOT01
python tools/gaze_eval/gaze_eval_data.py import docs/evaluation/iteration3/data/pilot/incoming/files/evaluation/iter3/PILOT01 --raw docs/evaluation/iteration3/data/pilot/raw --allow-pilot
```

- **`incoming/` and `pilot/` are Git-ignored.** Only imported official sessions under `data/raw/` are versioned.
- **What `import` does:** copies files byte for byte, never overwrites an existing session, refuses a session whose `SHA256SUMS` check fails, refuses `PILOT*` sessions without `--allow-pilot`, and with `--allow-pilot` refuses every non-pilot session.

### Freeze, replay and analyze

```powershell
python tools/gaze_eval/gaze_eval_data.py freeze docs/evaluation/iteration3/data/raw --out docs/evaluation/iteration3/data/raw-inputs.sha256
python tools/gaze_eval/gaze_eval_data.py check-frozen docs/evaluation/iteration3/data/raw docs/evaluation/iteration3/data/raw-inputs.sha256
.\gradlew.bat :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/evaluation/iteration3/data/raw -PgazeReplayOut=docs/evaluation/iteration3/data/derived/replay
python tools/gaze_eval/analyze_gaze_eval.py docs/evaluation/iteration3/data/raw --out docs/evaluation/iteration3/data/derived --source-label "<build commit / dataset note>"
```

For the pilot, use the same commands with `docs/evaluation/iteration3/data/pilot/raw` and an output folder under `docs/evaluation/iteration3/data/pilot/`.

1. **Freeze** the raw inputs before the official analysis. `check-frozen` confirms later that they have not changed.
2. **Replay** rebuilds the model with the candidate's own `GazeModel.fit` from each `model_snapshot.json`, requires identical parameters, and re-runs `GazeModel.predict` and `GazeSmoother` over the logged features with every logged reset. Every processed attempt must reproduce its logged quality, prediction rejection and linear, predicted and smoothed coordinates exactly. Without `-PgazeReplaySession` the runner is skipped.
3. **Analyze.** Writes `summary.json`, `sessions.csv`, `presentations.csv` and `report.md` (metrics only). It does not modify raw data.

### Synthetic end-to-end check (no participant data)

```powershell
$tmp = Join-Path $env:TEMP "gaze-e2e"
.\gradlew.bat :app:testDebugUnitTest --tests dev.gazedot.SyntheticSessionWriter "-PgazeSyntheticOut=$tmp\incoming"
python tools/gaze_eval/gaze_eval_data.py verify "$tmp\incoming\files\evaluation\iter3"
python tools/gaze_eval/gaze_eval_data.py import "$tmp\incoming\files\evaluation\iter3" --raw "$tmp\raw"
python tools/gaze_eval/gaze_eval_data.py freeze "$tmp\raw" --out "$tmp\raw-inputs.sha256"
python tools/gaze_eval/gaze_eval_data.py check-frozen "$tmp\raw" "$tmp\raw-inputs.sha256"
.\gradlew.bat :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner "-PgazeReplaySession=$tmp\raw" "-PgazeReplayOut=$tmp\derived\replay"
python tools/gaze_eval/analyze_gaze_eval.py "$tmp\raw" --out "$tmp\derived" --source-label "SYNTHETIC"
```

The writer refuses to write inside `docs/evaluation/iteration3/data`.

---

## 8. Artifact Index and Adding Participants

| Location | Content |
|---|---|
| [`protocol.json`](protocol.json) | Versioned protocol manifest 2.0.0: pipeline stages, cases, timing, coordinates, validity, thresholds, assertions, aggregation and version history |
| [`data/raw/`](data/raw/README.md) | Official recorded sessions, `data/raw/<participant_id>/<session_id>/`, stored byte for byte. Empty now. |
| [`data/derived/`](data/derived/README.md) | Analyzer and replay outputs (regenerated, never edited by hand). Empty now. |
| `data/pilot/` | Technical-pilot sessions and their outputs (Git-ignored, never official) |
| [`figures/`](figures/README.md) | Future figures (later stage) |
| `tools/gaze_eval/analyze_gaze_eval.py` | Metrics-only analyzer (schema v3, home-screen candidate) |
| `tools/gaze_eval/gaze_eval_data.py` | Checksum verification, import (with pilot separation), freeze |
| `tools/gaze_eval/testdata/` | **Synthetic** test fixtures only, not participant data |
| `app/src/main/java/dev/gazedot/Evaluation*.kt` | Evaluation protocol, start gate, snapshot, run screen and logger |
| `app/src/test/java/dev/gazedot/CandidateBaseline.kt`, `CandidateBehaviorTest.kt` | Frozen 30be1cf numerics and the bit-for-bit regression tests of the instrumented pipeline |
| `app/src/test/java/dev/gazedot/Evaluation*.kt`, `SyntheticEvaluation.kt` | JVM tests, synthetic driver, replay tool and runners |

### Adding participants

1. Use the unchanged protocol 2.0.0. If `protocol.json` changes, that is a new protocol version, and earlier sessions are not comparable.
2. Collect sessions with the same procedure, using a new participant code (for example `P02`), each after a fresh calibration.
3. Import with `gaze_eval_data.py import`. New sessions are appended under `data/raw/<participant_id>/<session_id>/`; existing sessions are never overwritten.
4. Re-freeze the raw inputs, then rerun replay and the analyzer. The analyzer finds participants and sessions from each `metadata.json`, and keeps different builds, devices, displays and modes in separate groups.
5. Add rows to the registry in [Section 4](#4-session-registry-and-results) only for sessions that exist.

Sessions recorded with protocol 1.0.0 (integrated main pipeline, log schema 2, `feature/gaze-evaluation-iter3`) belong to a different pipeline and are never part of this dataset.
