# Calibration comparison — Candidate A: three passes, one head position

| | |
|---|---|
| Candidate ID | `calibration_a_three_pass_static` |
| Branch | `experiment/calibration-a-three-pass` |
| `BASE_SHA` | `e8fa01ef1e397f508aa5a3b3a595539b4ef4490a` (`origin/feature/home-screen`, fetched 2026-11-14) |
| Calibration implementation | `94f1f8c90f09d10763d4a054481888b10f42cd1e` |
| Evaluation protocol | `notouch-calibration-comparison-eval` 1.0.0 ([`protocol.json`](protocol.json)), log schema 4, snapshot schema 2 |
| Sibling candidate | B, `calibration_b_three_pass_positions` on `experiment/calibration-b-three-poses`, same `BASE_SHA` (evaluation not yet ported) |
| Status | Calibration A completed once on the phone (basic operation only). Evaluation tooling integrated and checked offline; **no evaluation session recorded yet**. |
| Accuracy | **NOT_MEASURED** |

## 1. Purpose and hypothesis

The Iteration 3 evaluation of the nine-point home-screen calibration (branch
`feature/gaze-evaluation-home-screen-iter3`) did not meet its accuracy targets. Most of the error was a systematic
offset, and it grew during each session. This experiment asks one question about calibration data:

> With the same amount of calibration data, 27 anchors (three passes through the existing nine targets), is it
> better to repeat the calibration in one head position (A) or to spread it over several head positions (B)?

- **Candidate A (this branch)** repeats the nine targets three times without deliberately moving the head.
  Expected effect: three independent medians per target should average out per-point noise, so the fit describes
  the participant's eyes in this one position more reliably. It does not cover head-position changes.
- **Candidate B** collects one pass in an ordinary, a slightly higher and a slightly lower head position.
  Expected effect: the fit sees how small head-position changes shift the iris features, which might reduce offsets
  when the head drifts. The cost may be a worse fit in any single position.
- Neither effect is established. "No difference" is a possible result.

`GazeModel` regresses screen coordinates on the two iris features only (`x`, `y`). Face position and scale are used
only for its runtime validity check, against the mean of all anchors. Head position therefore reaches the fit only
through its effect on the iris features. Both candidates are evaluated in the same three instructed head positions
(section 5), so the comparison asks how each calibration holds up when the head is not where it was.

## 2. Calibration procedure (Candidate A, unchanged since `94f1f8c`)

Operator setup:

1. Fix the phone on a stand in portrait orientation. It must not move until the evaluation ends.
2. In NoTouch, tap **카메라 시작** (Start camera) and complete the permission steps (accessibility service
   **NoTouch 눈 조작**, display over other apps, camera).
3. Seat the participant facing the screen at about 30–50 cm (the home screen's guidance) in the neutral position.
4. When the eyes are detected (**보정 시작** is enabled), tap **보정 시작** (Start calibration).

Participant: adopt a comfortable, front-facing head position and keep it, at the same distance, for all three
passes and the breaks. Look at each blue dot until it moves on (the ring fills). Blink during the breaks: 2 s after the
4th point of every pass, 3 s after passes 1 and 2. A successful calibration opens the existing tutorial.

**Schedule and rules.** The existing nine targets, in the existing order, three times (27 anchors). Per point,
unchanged from `BASE_SHA`: 1,000 ms settling; ≥ 22 accepted samples and ≥ 2,500 ms; per-feature median; samples
0–250 ms old, newer than the last, quality TRACKING or OUT_OF_RANGE; missing input pauses collection without resetting
the clocks; a face shift > 0.045 from the point median restarts the point; > 9,000 ms fails the calibration. No
samples during breaks; the next point's clocks start when a break ends. One `GazeModel.fit` on all 27 anchors in
collection order, no intermediate model. On-screen text: "시선 보정 · 2/3회차 · 4/9" (pass 2/3, point 4/9),
"자세: 처음 자세 그대로 · 정면, 같은 거리" (keep the starting position). The candidate ID is shown in small grey text.

## 3. What this branch changes

| Change | Files |
|---|---|
| Three-pass calibration (commit `94f1f8c`) | `CalibrationController.kt`, `CalibrationPlan.kt`, `MainActivity.kt` (calibration view), tests |
| Evaluation integration (this stage) | see below |

Evaluation integration, adapted selectively from `feature/gaze-evaluation-home-screen-iter3` at `72411ec` (that
branch predates `BASE_SHA` and was not merged; its recorded sessions were not copied):

| File | Change |
|---|---|
| `Evaluation.kt` (new) | Protocol constants, head positions and block orders, the session timeline (preparation → Ready → countdown → 80 s block, ×3), evaluation state, the start gate, the 27-anchor `CalibrationRecord` and model snapshot |
| `EvaluationLog.kt` (new) | Schema 4 logger: `attempts.csv`, `events.csv`, `metadata.json`, `model_snapshot.json`, `SHA256SUMS`; block-start admission; counters |
| `EvaluationActivity.kt` (new) | Setup, preparation/Ready, countdown and target screens; every exit finalizes the record |
| `EvaluationDiagnostics.kt` (new) | Read-only name of the check that left a face without features |
| `GazeMath.kt` | Additions only: read-only `rejection`, `linearOutput`, `parameters` (predict and fit unchanged) |
| `CalibrationController.kt` | Observation only: start/finish times, per-point movement retries and acceptance times |
| `TrackerState.kt` | Calibration record and serials; reset/overlay hooks; guard functions = original conditions + evaluation guard; a new calibration attempt or tracking stop voids the record |
| `TrackingService.kt` | Evaluation hooks around the unchanged `pipeline.process(...)` call: attempt logging, named and logged pipeline resets, block-start admission, pointer/label guard, explicit `perform` guard; constants named (same values) |
| `MainActivity.kt` | Records the `CalibrationRecord` after a successful calibration installs its model |
| `DotOverlay.kt` | Read-only `drawnAt` and `bounds` |
| `ControlAccessibilityService.kt` | `allowed()` uses `TrackerState.gazeControlAllowed()` (same condition + evaluation guard) |
| `DeveloperSettingsActivity.kt` | Debug-only entry **시선 정확도 평가 · 보정 비교 (개발용)** |
| `AndroidManifest.xml` | `EvaluationActivity` (not exported) |
| `app/build.gradle.kts` | Build provenance (`BuildConfig`: commit, dirty flag, base ancestry, protocol and model hashes); test inputs and replay properties; **test-only** `org.json:json:20240303` (not packaged) |
| `docs/calibration-comparison/` | Protocol, tools, synthetic fixtures, data layout, this README |
| `.gitattributes`, `.gitignore` | Raw data and fixtures stored byte for byte; pilot and incoming exports never committed |

**Preserved and checked.** Unchanged files: `GazePipeline.kt` (smoothing, stale rule), `LandmarkFeatures.kt`,
`EyeObservation.kt`, `EyeGestures.kt`, `InteractionEngine.kt`, `CalibrationPlan.kt`, the tutorial, coordinates,
dependencies of the app, Gradle/AGP versions, application ID and signing. `CandidateBehaviorTest` requires fit,
predict, feature extraction and `medianFeatures` to equal frozen copies from `BASE_SHA` bit for bit, and the new
diagnostics to agree with them exactly. The calibration tests from `94f1f8c` are unchanged and pass. Outside a session,
behaviour is unchanged: the evaluation guards are false, nothing is logged, and the extra block-start drop applies only
while a session is current.

## 4. Evaluation protocol (`notouch-calibration-comparison-eval` 1.0.0)

**Session.** One fresh, successful 27-anchor calibration of this candidate, then one session of three head-position
blocks with the same model throughout (no recalibration, model change or tracking stop between blocks).

| | |
|---|---|
| Blocks | NEUTRAL (기본 위치), HIGHER (조금 높은 위치), LOWER (조금 낮은 위치) |
| Planned orders | ANNA / A1: NEUTRAL → HIGHER → LOWER (`nhl`); ANNA / A2: LOWER → HIGHER → NEUTRAL (`lhn`); future B1/B2 the same; technical pilot PILOT01 / T1: `nhl` |
| Each block | The unchanged Iteration 3 schedule: 16 targets at x, y ∈ {0.2, 0.4, 0.6, 0.8}, pass 1 serpentine, pass 2 reversed, 32 presentations of 1.0 s settling + 1.5 s measurement, 80 s |
| Case IDs | `<position>-pass<p>-r<row>c<col>`, e.g. `higher-pass1-r0c0` (96 unique per session) |
| Before each block | Preparation screen naming the position, explicit **준비 완료** (Ready), then a 3 s countdown; preparation and countdown are logged and never count toward a block |
| Filter reset | As protocol 2.0.0 at its run start: a logged `pipeline_reset` (`GazePipeline.reset`) at the session start and at every block start; results accepted before a block start and handled after it are dropped before smoothing (`discarded_block_start`) |
| One session per calibration | Starting a session consumes the calibration, even if interrupted; a new calibration attempt voids the previous record |
| Pose | Instructed only; `f_face_x`, `f_face_y`, `f_scale` logged per attempt and summarized per block and phase; no pose gate, nothing discarded on pose |

**Metrics and verdicts** (definitions and thresholds unchanged from protocol 2.0.0, evaluated per block):

| Criterion | Target |
|---|---|
| Successful presentations (complete, ≥ 10 valid samples, coverage ≥ 0.80, mean error ≤ 0.10) / 32 | ≥ 0.80 |
| Nearest-rank p95 of presentation mean errors (technically eligible presentations; INSUFFICIENT_DATA if fewer than 26) | ≤ 0.20 |
| Mean per-presentation jitter RMS (eligible presentations) | ≤ 0.05 |
| Tracking availability: valid / all measurement-phase attempts, invalid outcomes included | ≥ 0.90 |
| Valid sample rate: valid measurement samples / 48 s | ≥ 10 /s |
| p95 processing latency over all block attempts (settling and measurement, invalid and discarded included; no preparation/countdown) | ≤ 200 ms |

A block is MET only if all six are MET; a session is MET only if all three blocks are MET. Block results are always
listed separately; pooled per-position summaries recompute p95 from pooled presentation means and never average
percentiles. Missing data are never zero error. Incomplete sessions are INCOMPLETE (every block too). Fit error is
reported as a calibration diagnostic only. Candidates A and B are never pooled: the calibration ID is part of the
compatibility group. Pilot sessions, sessions outside the collection plan or with another block order, shared
calibrations, dirty builds and live-preview sessions are never official.

**Records** (app-private storage `files/evaluation/calibration-comparison/<participant>/<session_id>/`):
`attempts.csv` (one row per processing attempt: block, position, phase, case, features, eye state, linear/predicted/
smoothed coordinates, outcome, timings), `events.csv` (resets, preparation, Ready, camera errors), `metadata.json`
(build commit and dirty flag, protocol hash, candidate ID, participant/session, block order and timeline with
preparation times, device and display, declared conditions incl. head-position markers, calibration identity,
duration, retries and age, counters), `model_snapshot.json` (the 27 anchors with pass, point, calibration position,
retries and acceptance time, plus fitted parameters), `SHA256SUMS`. Replay refits all 27 anchors in recorded order,
requires every parameter bit for bit, and re-runs the unchanged `GazePipeline.process` over the logged inputs.

## 5. Phone procedure: fresh calibration and a session

1. Start tracking (**카메라 시작**) with the phone fixed on the stand; measure the eye–screen distance at the neutral
   position with a ruler.
2. **보정 시작** and complete all three passes until "보정 완료" (calibration complete) and the tutorial appear. Do not
   choose recalibration in the tutorial.
3. Leave the tutorial with Back, tap **설정 · 개발자 기능**, then **시선 정확도 평가 · 보정 비교 (개발용)**.
4. Check the status lines: "이 빌드의 보정 후보: calibration_a_three_pass_static" and "새 보정: 준비됨 · 27점 · …" and
   "평가를 시작할 수 있습니다." If it says 시작 불가 (cannot start), follow the message (usually: calibrate again).
5. Choose a preset: **PILOT01 · T1 · 기술 시험 (공식 아님)** for the technical pilot, or **ANNA · A1 · 기본 → 위 → 아래**
   / **ANNA · A2 · 아래 → 위 → 기본** for official sessions. Fill in 눈–화면 거리 (cm), 조명, 안경 and
   **자세 기준 메모** (see below). Keep **블라인드 측정 (기본)**. Tap **평가 시작**.
6. For each block: the screen shows "블록 k/3 준비 · <position>" with the instruction. The participant moves to that
   position (phone untouched), the operator taps **준비 완료** gently, a 3-2-1 countdown follows, then 32 blue dots
   (80 s). No pointer is shown and gaze clicks, scrolling and dictation are off for the whole session.
7. After block 3 the setup screen reports "평가 완료 · <participant> <session> · 3블록 96개 목표" and the session
   folder. Back, leaving the screen, screen-off, rotation or stopping tracking end the session as incomplete; the
   calibration is then used up and a new one is needed.

**Recording head positions (repeat them for candidate B).** Before formal collection, define reproducible physical
markers and write them in **자세 기준 메모** for every session, for example: the neutral chin height against a
marked scale or chin rest and the measured eye–screen distance; HIGHER and LOWER as the same small vertical
displacement up and down from that mark (the value chosen by the team, not validated here), approximately
front-facing, phone never moved. Keep the same markers, stand, distance and lighting for A and B. The logged face
position and scale per block can later show how consistently the positions were reproduced; they are not used to
accept or reject data.

## 6. Export, verify, import, freeze, replay, analyze

Run from the repository root (PowerShell). Set `JAVA_HOME` only in the build shell; the Android SDK path comes from the
ignored `local.properties`.

```powershell
# 1. Read-only export of all sessions from the phone (adb exec-out run-as ... tar); verifies checksums.
python -B docs\calibration-comparison\tools\calib_eval_data.py export --adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" --serial R3CW80G6PGW --out docs\calibration-comparison\data\incoming
python -B docs\calibration-comparison\tools\calib_eval_data.py verify docs\calibration-comparison\data\incoming\export-<UTC>

# 2a. Technical pilot (never official): import PILOT* sessions only, into data\pilot (Git-ignored).
python -B docs\calibration-comparison\tools\calib_eval_data.py import docs\calibration-comparison\data\incoming\export-<UTC>\files\evaluation\calibration-comparison\PILOT01 --raw docs\calibration-comparison\data\pilot --allow-pilot
# 2b. Official sessions: import into data\raw\<calibration_id>\<participant>\<session_id> (never overwrites).
python -B docs\calibration-comparison\tools\calib_eval_data.py import docs\calibration-comparison\data\incoming\export-<UTC>\files\evaluation\calibration-comparison\ANNA --raw docs\calibration-comparison\data\raw

# 3. Freeze the official inputs before analysis, and check them later.
python -B docs\calibration-comparison\tools\calib_eval_data.py freeze docs\calibration-comparison\data\raw --out docs\calibration-comparison\data\raw-inputs.sha256
python -B docs\calibration-comparison\tools\calib_eval_data.py check-frozen docs\calibration-comparison\data\raw docs\calibration-comparison\data\raw-inputs.sha256

# 4. Exact numerical replay with the app's own GazeModel.fit / GazePipeline.process.
.\gradlew.bat :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/calibration-comparison/data/raw -PgazeReplayOut=docs/calibration-comparison/data/derived/replay

# 5. Analysis by candidate and head position (select sessions by ID in data\analysis-inputs.json: {"included": [{"session_id": "..."}]}).
python -B docs\calibration-comparison\tools\analyze_calibration_eval.py docs\calibration-comparison\data\raw --out docs\calibration-comparison\data\derived --selection docs\calibration-comparison\data\analysis-inputs.json --source-label "<build commit, selection>"
```

For the pilot, run steps 4 and 5 on `data\pilot` with `--out` pointing outside `data\derived`. An export that holds
both pilot and official sessions is imported per participant folder, as above. Keep the export archive and its
`.sha256` outside the repository as the backup before deleting it from `data\incoming`.

Data layout: [`data/raw/`](data/raw/README.md) (official, byte-exact), [`data/pilot/`](data/pilot/README.md) and
[`data/incoming/`](data/incoming/README.md) (Git-ignored), [`data/derived/`](data/derived/README.md) (generated),
[`figures/`](figures/README.md) (future). Synthetic fixtures live only in [`tools/testdata/`](tools/testdata/README.md).

## 7. Build and automated checks

```powershell
$env:JAVA_HOME = "<path to JDK 17 or 21>"   # this shell only
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python -B -m unittest discover -s docs\calibration-comparison\tools -v
```

Results on 2026-11-15 (Windows 11, Microsoft OpenJDK 21.0.12.1, Gradle 8.13, AGP 8.9.2, Python 3.14), no phone involved:

| Check | Result |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 123 tests, 0 failures, 0 errors, 2 skipped (the opt-in `EvaluationReplayRunner` and `SyntheticSessionWriter`, which run only with `-P` properties). Includes the 71 tests of `94f1f8c` unchanged |
| `:app:lintDebug` | 0 errors, 30 warnings, identical to `BASE_SHA` by issue ID and file |
| Kotlin compiler | only the pre-existing `setDecorFitsSystemWindows` deprecation warning in `MainActivity.kt` |
| Python tool tests | 42 tests OK (analyzer 34, data tool 8) |
| End-to-end, synthetic | Kotlin writer → `export` layout → verify → import → freeze/check-frozen → analyze → Kotlin replay: replay `ok`, 0 mismatches (3,783 and 1,777 processed attempts) |

The regression test `ReferenceRegressionTest` feeds identical synthetic attempts to the frozen Iteration 3 analyzer
(`tools/testdata/reference_iter3`, unmodified from `72411ec`) and to the per-block code, and requires identical
presentations, criteria and diagnostics. Deliberately broken metric rules (eligibility and success boundaries, the
10-sample minimum, the latency population, the p95 rank) each made it fail. The golden session
(`tools/testdata/synthetic_session`) pins the file format shared by the Kotlin logger and the Python tools.

## 8. Known limitations and on-device uncertainties

- **No evaluation session has run on the phone.** The setup, preparation, Ready, countdown and target screens, the
  3 s countdown, block transitions, Back/background/screen-off handling, file finalization on the device, and the
  `export` command (`run-as` + `tar`) are verified offline only. The technical pilot (PILOT01 / T1) is the first check.
- The Ready button is tapped on the fixed phone; tapping can move it. Tap gently or use a stand that stays put.
- Calibration plus a session takes about 1.5–2 min of calibration and at least 3 × (preparation + 3 s + 80 s); a
  long preparation is allowed and recorded but adds time since calibration (logged as `age_ms_at_session_start` and
  per-block times).
- Instructed positions are not measured. Whether HIGHER and LOWER stay within the model's unchanged validity range
  (face within ±0.12 of the mean calibration position, scale 0.72–1.28) is unknown; out-of-range frames count as invalid
  (`out_of_range`) and lower availability. The rule is deliberately not loosened.
- Processing errors are recorded from the camera worker; in the rare case that one races with the main thread setting
  a block start, its logged phase could disagree with the timeline, which the analyzer flags as an integrity problem.
- The evaluation entry is in **설정 · 개발자 기능**, not on the home screen, so the home layout and its gaze targets
  are unchanged. Release builds hide it.
- Candidate B is untouched; its evaluation needs the same integration (with B's calibration ID and labels) before
  B sessions. Candidates A, B and the baseline share application ID `dev.gazedot`, so only one is installed at a time.
- The root `README.md` still describes the nine-point flow, and some status texts still say "9점 보정".

Accuracy remains **NOT_MEASURED**. No session results, figures or conclusions exist yet.
