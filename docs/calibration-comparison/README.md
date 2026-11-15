# Calibration comparison — Candidate B: three passes, three head positions

| | |
|---|---|
| Candidate ID | `calibration_b_three_pass_positions` |
| Branch | `experiment/calibration-b-three-poses` |
| `BASE_SHA` | `e8fa01ef1e397f508aa5a3b3a595539b4ef4490a` (`origin/feature/home-screen`, fetched 2026-11-14) |
| Calibration implementation | `752d1743b7e79f7deb11a86e0dcd345da2a16ceb` (unchanged by the evaluation integration) |
| Evaluation integration | Ported selectively from candidate A: `188898b5444015f9d4f07b44dba9c2e323760d7b` (evaluation) and `f3b224c4801570c57a192d354eb848b30c90b3c3` (home-screen layout fix) on `experiment/calibration-a-three-pass` |
| Evaluation protocol | `notouch-calibration-comparison-eval` 1.0.0 ([`protocol.json`](protocol.json)), **the same file as candidate A** (SHA-256 after CRLF→LF `651e4402c8208538ccca1d7e204141315e649c8f15cd3e835f795c689046c547`), log schema 4, snapshot schema 2 |
| Collection plan | [`collection-plan-b.json`](collection-plan-b.json) 1.0.0: ANNA / B1, ANNA / B2 (official), PILOT01 / TB1 (technical pilot) |
| Sibling candidate | A, `calibration_a_three_pass_static` on `experiment/calibration-a-three-pass`, same `BASE_SHA` |
| Status | Calibration and evaluation integration **checked offline only**. On-device behaviour is **awaiting validation**. No candidate B evaluation session has been recorded. |
| Accuracy | **NOT_MEASURED** |

## 1. Purpose and hypothesis

The Iteration 3 evaluation of the nine-point home-screen calibration (branch
`feature/gaze-evaluation-home-screen-iter3`) did not meet its accuracy targets. Most of the error was a systematic
offset, and it grew during each session. This experiment asks one question about calibration data:

> With the same amount of calibration data, 27 anchors (three passes through the existing nine targets), is it
> better to repeat the calibration in one head position (A) or to spread it over several head positions (B)?

- **Candidate A** repeats the nine targets three times without deliberately moving the head.
- **Candidate B (this branch)** collects one pass in an ordinary, a slightly higher and a slightly lower head
  position. Expected effect: the fit sees how small head-position changes shift the iris features, which might
  reduce offsets when the head drifts. The cost may be a worse fit in any single position.
- Neither effect is established. "No difference" is a possible result.

`GazeModel` regresses screen coordinates on the two iris features only (`x`, `y`). Face position and scale are used
only for its runtime validity check, against the mean of all anchors; in B that mean combines the three positions.
Both candidates are evaluated with the same protocol in the same three instructed head positions (section 5).

## 2. Calibration procedure (Candidate B, unchanged since `752d174`)

Operator setup:

1. Fix the phone on the stand in portrait orientation. **The phone must not move until the evaluation ends**; only
   the head moves.
2. In NoTouch, tap **카메라 시작** (Start camera) and complete the permission steps (accessibility service
   **NoTouch 눈 조작**, display over other apps, camera).
3. Seat the participant facing the screen at about 30–50 cm (the home screen's guidance).
4. When the eyes are detected (**보정 시작** is enabled), tap **보정 시작** (Start calibration).

Participant:

1. **Pass 1, ordinary position.** A comfortable, front-facing head position. Nine targets.
2. **Break (3 s).** Move the head slightly **upward** relative to the phone, approximately front-facing, then hold still.
3. **Pass 2, higher position.** Nine targets.
4. **Break (3 s).** Move the head slightly **below the original (ordinary) position**, then hold still.
5. **Pass 3, lower position.** Nine targets. A successful calibration then opens the existing tutorial.

```
pass 1 (ordinary): P1 P2 P3 P4 [blink break 2 s] P5 P6 P7 P8 P9 [inter-pass break 3 s: move slightly up]
pass 2 (higher):   P1 P2 P3 P4 [blink break 2 s] P5 P6 P7 P8 P9 [inter-pass break 3 s: move below the original]
pass 3 (lower):    P1 P2 P3 P4 [blink break 2 s] P5 P6 P7 P8 P9 -> one GazeModel.fit on all 27 anchors
```

- The existing nine targets in the existing order in every pass: centre (.5, .5), (.15, .15), (.5, .15), (.85, .15),
  (.85, .5), (.85, .85), (.5, .85), (.15, .85), (.15, .5) as fractions of the calibration view.
- Movement instructions appear **only in the two inter-pass breaks** ("머리를 조금 위로 옮겨 주세요" / "머리를 조금
  아래로 옮겨 주세요"). The required position is shown under the title throughout each pass ("자세: 기본 위치 /
  조금 높은 위치 / 조금 낮은 위치 · 정면을 보고 그대로 유지"); the blink break keeps it. Positions are qualitative;
  no pose gate exists.
- Each of the 27 anchors is kept separately, labelled pass, point and position (`ordinary`, `higher`, `lower`), with
  its genuine median features (face position and scale included, nothing normalized across passes). They go to one
  `GazeModel.fit` in collection order. **No model is fitted or activated after pass 1 or 2.**
- Per point, unchanged from `BASE_SHA`: 1,000 ms settling; ≥ 22 samples **and** ≥ 2,500 ms; per-feature median;
  samples 0–250 ms old, newer than the last, quality TRACKING or OUT_OF_RANGE; missing input pauses collection without
  resetting the clocks; a face shift > 0.045 from the point median restarts the point; > 9,000 ms fails the whole
  calibration (never checked during a break). No samples during any break; the next point's clocks start when a break
  ends. Back, leaving the screen or stopping tracking cancels; a failed or cancelled calibration keeps the previous
  model, as before.

The candidate ID is drawn in small grey text at the bottom of the calibration screen.

## 3. What this branch changes

| Stage | Files |
|---|---|
| Three-position calibration (commit `752d174`) | `CalibrationController.kt`, `CalibrationPlan.kt`, `MainActivity.kt` (calibration view), `CalibrationControllerTest.kt`, `CalibrationPlanTest.kt`, `CalibrationTestHarness.kt` |
| Evaluation integration (this stage) | below |

The evaluation is candidate A's committed evaluation, ported file by file (not merged: candidate A's calibration
commit, its uncommitted plotting work and its recorded data and results were not copied). Of the 44 files ported, 29
are identical to candidate A's `f3b224c` apart from line endings, including `TrackingService.kt`, `TrackerState.kt`,
`EvaluationLog.kt`, `GazeMath.kt`, `MainActivity.kt`, `DotOverlay.kt`, `ControlAccessibilityService.kt`,
`DeveloperSettingsActivity.kt`, `AndroidManifest.xml`, `protocol.json`, `analyze_calibration_eval.py` and the
Iteration 3 regression fixture. So reset policy, in-flight-frame handling, logging, finalization and metrics are the
same code as in candidate A.

| File | Role (as in candidate A unless noted) |
|---|---|
| `Evaluation.kt` (new) | Protocol constants, head positions and block orders, the session timeline (preparation → Ready → countdown → 80 s block, ×3), evaluation state and guards, the start gate, the 27-anchor `CalibrationRecord` and model snapshot. **B:** `CALIBRATION_ID` = this build's candidate only; the gate also requires each pass's calibration position (`ordinary`, `higher`, `lower`); `EvaluationPlan` lists the three plan sessions; NEUTRAL instruction names calibration pass 1 |
| `EvaluationActivity.kt` (new) | Setup, preparation/Ready, countdown and target screens; every exit finalizes the record. **B:** the setup offers exactly the plan's sessions (no free-text participant, label or order, nothing preselected) |
| `EvaluationLog.kt`, `EvaluationDiagnostics.kt` (new) | Schema 4 logger (`attempts.csv`, `events.csv`, `metadata.json`, `model_snapshot.json`, `SHA256SUMS`), block-start admission, counters; read-only feature-rejection names |
| `GazeMath.kt` | Additions only: read-only `rejection`, `linearOutput`, `parameters`; `fit` and `predict` unchanged |
| `CalibrationController.kt` | Observation only: start/finish times, per-point movement retries, acceptance times. Schedule, rules and the single fit unchanged |
| `TrackerState.kt`, `TrackingService.kt` | Calibration record and serials; named, logged pipeline resets; block-start admission; pointer and action guards around the unchanged `pipeline.process(...)` |
| `MainActivity.kt` | Records the `CalibrationRecord` after a successful calibration installs its model; layout fix keeps **설정 · 개발자 기능** above the navigation bar (from `f3b224c`) |
| `DotOverlay.kt`, `ControlAccessibilityService.kt` | Read-only overlay geometry; `allowed()` = original condition + evaluation guard |
| `DeveloperSettingsActivity.kt`, `AndroidManifest.xml` | Debug-only entry **시선 정확도 평가 · 보정 비교 (개발용)**; `EvaluationActivity` (not exported) |
| `app/build.gradle.kts` | `BuildConfig` provenance (commit, dirty flag, base ancestry, protocol and model hashes); test inputs and replay properties; test-only `org.json:json:20240303` (not packaged). **B:** the plan file is a test input |
| `collection-plan-b.json` (new, B only) | Candidate B's collection plan (section 4) |
| `tools/calib_eval_data.py` | **B:** additive, opt-in `import --calibration-id` (section 7); without it, behaviour is candidate A's |
| `tools/testdata/synthetic_session/` | Golden session regenerated by B's logger (section 8) |
| Tests | Candidate A's evaluation tests, adapted to B's identity and labels, plus B checks (section 8) |

**Preserved.** Unchanged against `BASE_SHA`: `GazePipeline.kt` (smoothing, stale rule), `LandmarkFeatures.kt`,
`EyeObservation.kt`, `EyeGestures.kt`, `InteractionEngine.kt`, `DisplaySpace.kt`, `ScreenGaze.kt`, text entry, the
tutorial, dependencies of the app, Gradle/AGP versions, application ID and signing. `CandidateBehaviorTest` requires
`GazeModel.fit`/`predict`, feature extraction and `medianFeatures` to equal frozen `BASE_SHA` copies bit for bit. No
feature extraction, fit mathematics, regression parameter, prediction, validity gate, smoothing, coordinate mapping,
clicking, scrolling or text entry was changed for the head positions. The 24 calibration tests of `752d174` are
unchanged and pass. Outside a session nothing is logged and the guards are inactive.

## 4. Evaluation protocol and collection plan

**Protocol.** `protocol.json` 1.0.0 was declared before any session and already names both candidates:
`calibration_b_three_pass_positions` is an allowed calibration ID and ANNA / B1 (`nhl`) and ANNA / B2 (`lhn`) are
official sessions. It is therefore used **unchanged**, byte for byte the manifest of candidate A's build, so A and B
sessions carry the same protocol hash and are analyzed by the same tool in one run, side by side (never pooled: the
calibration ID is part of the compatibility group). Candidate A's protocol and the status of its recording are not
touched.

**What was A-specific, and how B handles it.** The protocol's only technical-pilot entry is PILOT01 / T1 with
candidate A's calibration. Rather than change the hashed manifest, candidate B's plan is declared in
[`collection-plan-b.json`](collection-plan-b.json), committed before any B session:

| Entry | Participant / label | Order | Role |
|---|---|---|---|
| B1 | ANNA / B1 | NEUTRAL → HIGHER → LOWER (`nhl`) | official (as in protocol 1.0.0) |
| B2 | ANNA / B2 | LOWER → HIGHER → NEUTRAL (`lhn`) | official (as in protocol 1.0.0) |
| Pilot | PILOT01 / TB1 | `nhl` | technical pilot; never official (protocol rule: every `PILOT*` participant) |

The B app's setup screen offers exactly these three choices; participant, label and order come from the entry and
nothing is preselected, so no session can be recorded under a custom label outside the plan. Tests require the app's
choices, the plan file and the protocol's B entries to agree, and the plan to name the embedded protocol hash.

**Session** (identical to candidate A). One fresh, successful 27-anchor calibration of this candidate, then one
session of three head-position blocks with the same model throughout.

| | |
|---|---|
| Blocks | NEUTRAL (기본 위치), HIGHER (조금 높은 위치), LOWER (조금 낮은 위치), in the planned order |
| Each block | The unchanged Iteration 3 schedule: 16 targets at x, y ∈ {0.2, 0.4, 0.6, 0.8}, pass 1 serpentine, pass 2 reversed, 32 presentations of 1.0 s settling + 1.5 s measurement, 80 s |
| Case IDs | `<position>-pass<p>-r<row>c<col>`, e.g. `higher-pass1-r0c0` (96 unique per session) |
| Before each block | Preparation screen naming the position, **준비 완료** (Ready), then a 3 s countdown; logged, never part of a block metric or denominator |
| Filter reset | A logged `pipeline_reset` at the session start and at every block start; results accepted before a block start and handled after it are dropped before smoothing (`discarded_block_start`) |
| Eligibility | One session per fresh calibration. Starting a session consumes it, even if interrupted. A new calibration attempt (failed, cancelled or successful) voids the previous record, so a failed recalibration cannot make the retained model eligible. Stopping tracking voids it too |
| Interaction | Blind mode hides the pointer for the whole session; gaze clicks, scrolling and dictation are suppressed by explicit guards during preparation, countdown and measurement. The accessibility service stays enabled; completion or any interruption restores normal interaction |
| Pose | Instructed only; `f_face_x`, `f_face_y`, `f_scale` are logged per attempt and summarized per block and phase as diagnostics; never an acceptance filter |

**Acceptance criteria** (protocol 1.0.0, unchanged from Iteration 3, evaluated per block):

| Criterion | Target |
|---|---|
| Successful presentations (complete, ≥ 10 valid samples, coverage ≥ 0.80, mean error ≤ 0.10) / 32 | ≥ 0.80 |
| Nearest-rank p95 of presentation mean errors (technically eligible presentations: complete, ≥ 10 valid, coverage ≥ 0.5; INSUFFICIENT_DATA if fewer than 26) | ≤ 0.20 |
| Mean per-presentation jitter RMS (eligible presentations; same insufficient-data rule) | ≤ 0.05 |
| Tracking availability: valid / all measurement-phase attempts, invalid outcomes included | ≥ 0.90 |
| Valid sample rate: valid measurement samples / 48 s | ≥ 10 /s |
| p95 processing latency over all block attempts (settling and measurement, invalid and discarded included; no preparation/countdown) | ≤ 200 ms |

A block is MET only if all six are MET; a session is MET only if all three blocks are MET; block verdicts are always
reported separately. Missing coordinates never count as zero error. Incomplete sessions are INCOMPLETE; logging loss,
unassigned rows or checksum mismatches make a session INSUFFICIENT_DATA. Pilot sessions, sessions outside the plan or
with another order, shared calibrations, dirty builds and live-preview sessions are never official.

**Records** (app-private `files/evaluation/calibration-comparison/<participant>/<session_id>/`): `attempts.csv`,
`events.csv`, `metadata.json` (build commit and dirty flag, protocol hash, candidate ID and
`calibration_positions_by_pass` = `["ordinary", "higher", "lower"]`, session identity and block timeline, device,
display, declared conditions, calibration timing and retries, frame and attempt counters, invalid reasons, logging
accounting), `model_snapshot.json` (all 27 anchors with pass, point, `calibration_position`, retries, acceptance time
and features, plus fitted parameters) and `SHA256SUMS`. Replay refits `GazeModel.fit` on the 27 anchors in recorded
order, requires every parameter bit for bit, and re-runs the unchanged `GazePipeline.process` over the logged inputs.

## 5. Differences from candidate A's evaluation build

| Item | Candidate A (`f3b224c`) | Candidate B (this branch) |
|---|---|---|
| Calibration ID / positions by pass | `calibration_a_three_pass_static` / `same, same, same` | `calibration_b_three_pass_positions` / `ordinary, higher, lower` |
| Protocol manifest and hash | 1.0.0, `651e4402…c547` | identical file and hash |
| Collection-plan choices in the app | presets ANNA A1/A2, PILOT01 T1 plus free-text participant, label and order (remembered between sessions) | exactly ANNA B1, ANNA B2, PILOT01 TB1; no free text; nothing preselected or remembered |
| Candidate check | gate: own ID and in the protocol's allowed list; replay: any allowed ID | gate and replay: own ID only (that the protocol declares it is a test), plus the per-pass calibration positions |
| NEUTRAL block instruction, 2nd line | 보정할 때와 같은 기본 위치입니다 (as during calibration) | 보정 1회차와 같은 기본 위치입니다 (as in calibration pass 1) |
| Setup intro | 새 보정(3회차 × 9점) | 새 보정(3회차 × 9점: 기본 → 조금 높게 → 조금 낮게) |
| Data tool | `import` files every declared candidate | same, plus opt-in `--calibration-id` |
| Golden synthetic session | A identity | `attempts.csv` and `events.csv` byte-identical to A's; metadata/snapshot differ only in calibration ID, the 27 position labels and the inputs hash that covers them |

Schedules, timings, thresholds, criteria, eligibility rules, insufficient-data handling, log and snapshot schemas,
resets, guards and metric code are the same.

## 6. Phone procedure: fresh calibration and one session

1. Start tracking (**카메라 시작**) with the phone fixed on the stand; measure the eye–screen distance at the neutral
   position with a ruler.
2. **보정 시작**: complete all three passes (ordinary → higher → lower, moving only in the 3 s breaks) until "보정
   완료" and the tutorial appear. Do not choose recalibration in the tutorial.
3. Leave the tutorial with Back, tap **설정 · 개발자 기능**, then **시선 정확도 평가 · 보정 비교 (개발용)**.
4. Check the status lines: "이 빌드의 보정 후보: calibration_b_three_pass_positions", "새 보정: 준비됨 · 27점 · …" and
   "평가를 시작할 수 있습니다." If it says 시작 불가 (cannot start), follow the message (usually: calibrate again).
5. Under **세션 선택**, choose exactly one: **PILOT01 · TB1 · 기술 시험 (공식 아님)** for the technical pilot, or
   **ANNA · B1 · 기본 → 위 → 아래 (공식)** / **ANNA · B2 · 아래 → 위 → 기본 (공식)**. Fill in 눈–화면 거리 (cm),
   조명, 안경 and **자세 기준 메모** (the same physical markers as candidate A's sessions). Keep **블라인드 측정
   (기본)**. Tap **평가 시작**.
6. For each block: "블록 k/3 준비 · <position>" with the instruction (NEUTRAL: "편한 정면 자세를 잡아 주세요 / 보정
   1회차와 같은 기본 위치입니다"). The participant moves (phone untouched), the operator taps **준비 완료** gently, a
   3-2-1 countdown follows, then 32 blue dots (80 s). No pointer; gaze clicks, scrolling and dictation are off.
7. After block 3: "평가 완료 · ANNA B1 · 3블록 96개 목표" and the session folder. Back, leaving the screen,
   screen-off, rotation or stopping tracking end the session as incomplete; the calibration is then used up.

Each official session (B1, then B2) needs its own fresh calibration. Keep stand, distance, lighting and markers the
same as for candidate A.

## 7. Export, verify, import, freeze, replay, analyze

From the repository root (PowerShell). Set `JAVA_HOME` only in the build shell; the SDK path comes from the ignored
`local.properties`.

```powershell
# 1. Read-only export of all sessions on the phone (adb exec-out run-as ... tar), then verify checksums.
python -B docs\calibration-comparison\tools\calib_eval_data.py export --adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" --serial <serial> --out docs\calibration-comparison\data\incoming
python -B docs\calibration-comparison\tools\calib_eval_data.py verify docs\calibration-comparison\data\incoming\export-<UTC>

# 2. Import candidate B sessions only. Both candidates use app ID dev.gazedot, so if B was installed over A the export
#    also holds A's sessions; --calibration-id lists them as skipped and never copies them into this branch.
python -B docs\calibration-comparison\tools\calib_eval_data.py import docs\calibration-comparison\data\incoming\export-<UTC>\files\evaluation\calibration-comparison\PILOT01 --raw docs\calibration-comparison\data\pilot --allow-pilot --calibration-id calibration_b_three_pass_positions
python -B docs\calibration-comparison\tools\calib_eval_data.py import docs\calibration-comparison\data\incoming\export-<UTC>\files\evaluation\calibration-comparison\ANNA --raw docs\calibration-comparison\data\raw --calibration-id calibration_b_three_pass_positions

# 3. Freeze the official inputs before analysis, and check them later.
python -B docs\calibration-comparison\tools\calib_eval_data.py freeze docs\calibration-comparison\data\raw --out docs\calibration-comparison\data\raw-inputs.sha256
python -B docs\calibration-comparison\tools\calib_eval_data.py check-frozen docs\calibration-comparison\data\raw docs\calibration-comparison\data\raw-inputs.sha256

# 4. Exact numerical replay with the app's own GazeModel.fit / GazePipeline.process.
.\gradlew.bat :app:testDebugUnitTest --tests dev.gazedot.EvaluationReplayRunner -PgazeReplaySession=docs/calibration-comparison/data/raw -PgazeReplayOut=docs/calibration-comparison/data/derived/replay

# 5. Analysis by candidate and head position (select sessions in data\analysis-inputs.json: {"included": [{"session_id": "..."}]}).
python -B docs\calibration-comparison\tools\analyze_calibration_eval.py docs\calibration-comparison\data\raw --out docs\calibration-comparison\data\derived --selection docs\calibration-comparison\data\analysis-inputs.json --source-label "<build commit, selection>"
```

For the pilot, run steps 4 and 5 on `data\pilot` with `--out` outside `data\derived`. Keep the export archive and its
`.sha256` outside the repository as the backup. Data layout: [`data/raw/`](data/raw/README.md) (official, byte-exact),
[`data/pilot/`](data/pilot/README.md) and [`data/incoming/`](data/incoming/README.md) (Git-ignored),
[`data/derived/`](data/derived/README.md) (generated), [`figures/`](figures/README.md) (none yet for B). Synthetic
fixtures live only in [`tools/testdata/`](tools/testdata/README.md).

## 8. Build and automated checks

```powershell
$env:JAVA_HOME = "<path to JDK 17 or 21>"   # this shell only
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python -B -m unittest discover -s docs\calibration-comparison\tools -v
```

Results on 2026-11-15 (Windows 11, Microsoft OpenJDK 21.0.12.1, Gradle 8.13, AGP 8.9.2, Python 3.14), offline, all
data synthetic, no phone involved:

| Check | Result |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 129 tests, 0 failures, 0 errors, 2 skipped (the opt-in `EvaluationReplayRunner` and `SyntheticSessionWriter`). The 73 tests of `752d174` (49 base + 19 `CalibrationControllerTest` + 5 `CalibrationPlanTest`) are unchanged and pass |
| `:app:lintDebug` | 0 errors, 30 warnings, identical to `752d174` by issue ID and file |
| Kotlin compiler | only the pre-existing `setDecorFitsSystemWindows` deprecation warning in `MainActivity.kt` |
| Python tool tests | 49 tests OK (analyzer 38, data tool 11) |
| End-to-end, synthetic | Kotlin writer → export layout (plus a sibling-candidate session) → verify → `import --calibration-id` (B imported, sibling skipped) → freeze/check-frozen → analyze → Kotlin replay: replay `ok`, parameters bit-identical, 0 mismatches (3,783 and 1,777 processed attempts). Candidate A's committed analyzer gives byte-identical outputs on the same input |

What the B checks cover, beyond candidate A's evaluation tests:

- **Calibration.** Three passes × nine targets in the original order, `ordinary`/`higher`/`lower` per pass,
  movement instructions only in the inter-pass breaks, original per-point rules, and exactly one fit after the 27th
  anchor (`752d174` tests); the real controller's outcome is accepted by the evaluation gate and its snapshot refits
  exactly (`EvaluationStateTest`).
- **Gating.** Fresh calibration only; one session per calibration; a failed or cancelled recalibration cannot pass
  off the retained model; the sibling candidate's ID and wrongly labelled passes are refused.
- **Protocol and plan.** Kotlin constants equal the shared manifest; the plan's official entries are the protocol's
  B entries, its pilot is `PILOT*` and disjoint; the setup offers exactly the plan; the plan names the embedded
  protocol hash.
- **Equivalence with A.** B's logger writes the golden session with `attempts.csv` and `events.csv` byte-identical to
  candidate A's committed fixture; the Python tests analyze equivalent synthetic sessions (and the golden session)
  under both candidate IDs and require identical metrics, verdicts and aggregates.
- During development, injected faults (positions ignored by the eligibility check, B2's order changed, the identity
  check removed) each made at least one of these tests fail.

## 9. Known limitations and on-device checks still needed

- **No evaluation session has run on a phone, and B's calibration has not run on a phone.** Layout and legibility of
  the calibration and evaluation screens, real per-point and break timing, repositioning within the 3 s breaks, the
  setup radio list, preparation/Ready/countdown, block transitions, Back/background/screen-off handling, file
  finalization and `export` (`run-as` + `tar`) on the device are verified offline only. The technical pilot
  (PILOT01 / TB1) is the first check.
- Whether the higher and lower positions stay within the model's unchanged validity range (face within ±0.12 of the
  mean of the three calibration positions, scale 0.72–1.28) is unknown; out-of-range frames count as invalid
  (`out_of_range`). The rule is deliberately not loosened. The unchanged fit thresholds may reject more B than A
  calibrations; that would be a result, not a reason to change them.
- One timeout among 27 points fails the whole calibration; repositioning that is still under way when a pass starts
  restarts or times out the first point.
- Candidates A, B and the baseline share application ID `dev.gazedot` and the debug signature. Installing B over A
  (`adb install -r`) keeps A's app data, including A's session files and remembered conditions (B reads only distance,
  lighting, glasses and markers from them); uninstalling A deletes A's on-phone sessions, so A's export and backup must
  be complete first. Only one candidate can be installed at a time.
- The Ready button is tapped on the fixed phone; tap gently.
- The root `README.md` still describes the nine-point flow, and some status texts still say "9점 보정".

Accuracy remains **NOT_MEASURED**. No candidate B session results, figures or conclusions exist.
