# Calibration comparison — Candidate B: three passes, three head positions

| | |
|---|---|
| Candidate ID | `calibration_b_three_pass_positions` |
| Branch | `experiment/calibration-b-three-poses` |
| `BASE_SHA` | `e8fa01ef1e397f508aa5a3b3a595539b4ef4490a` (`origin/feature/home-screen`, fetched 2026-11-14) |
| Sibling candidate | A, `calibration_a_three_pass_static` on `experiment/calibration-a-three-pass`, same `BASE_SHA` |
| Status | Implemented and checked offline. **Not run on a phone.** |
| Accuracy | **NOT_MEASURED** |

## 1. Purpose and hypothesis

The Iteration 3 evaluation of the nine-point home-screen calibration (branch
`feature/gaze-evaluation-home-screen-iter3`) did not meet its accuracy targets. Most of the error was a systematic
offset, and it grew during each session. This experiment asks one question about calibration data:

> With the same amount of calibration data, 27 anchors (three passes through the existing nine targets), is it
> better to repeat the calibration in one head position (A) or to spread it over several head positions (B)?

- **Candidate A** repeats the nine targets three times without deliberately moving the head.
  Expected effect: three independent medians per target should average out per-point noise, so the fit describes
  the participant's eyes in this one position more reliably. It does not cover head-position changes.
- **Candidate B (this branch)** collects one pass in an ordinary, a slightly higher and a slightly lower head
  position. Expected effect: the fit sees how small head-position changes shift the iris features, which might
  reduce offsets when the head drifts. The cost may be a worse fit in any single position.
- Neither effect is established. "No difference" is a possible result.

`GazeModel` regresses screen coordinates on the two iris features only (`x`, `y`). Face position and scale are used
only for its runtime validity check, against the mean of all anchors. Head position therefore reaches the fit only
through its effect on the iris features. In B, the validity reference is the mean over the three positions.

## 2. Participant procedure (Candidate B)

Operator setup, unchanged from the app:

1. Fix the phone on the same stand in portrait orientation. **The phone must not move until calibration ends**; only
   the head moves.
2. In NoTouch, tap **카메라 시작** (Start camera) and complete the existing permission steps.
3. Seat the participant facing the screen at about 30–50 cm (the home screen's existing guidance).
4. When the eyes are detected (**보정 시작** is enabled), tap **보정 시작** (Start calibration).

Participant:

1. **Pass 1, ordinary position.** Adopt a comfortable, front-facing head position. Complete the nine targets.
2. **First inter-pass break (3 s).** Move the head slightly **upward** relative to the phone. Stay approximately
   front-facing, then hold still.
3. **Pass 2, higher position.** Complete the nine targets in that higher position.
4. **Second inter-pass break (3 s).** Move the head slightly **below the original (ordinary) position**. Stay
   approximately front-facing, then hold still.
5. **Pass 3, lower position.** Complete the nine targets in that lower position.

During every pass: keep the head stable, look at each blue dot until it moves on (the ring fills as samples are
collected), and do not move while a point is being collected. Movement happens only during the inter-pass breaks.
The 2 s blink break after the 4th point of each pass keeps the current position. The required position is shown
under the title throughout each pass.

"Slightly higher" and "slightly lower" are qualitative. This stage defines no centimetre or angle values and adds no
automatic pose check. Reproducible physical positions will be defined and documented before formal collection.
After pass 3, a successful calibration opens the existing tutorial. A failure returns to the home screen with a
message. **Back** cancels at any time.

## 3. Targets, schedule and collection rules

**Targets.** The existing nine targets, in the existing order, in every pass. They are fractions of the calibration
view: centre (.5, .5), top-left (.15, .15), top-centre (.5, .15), top-right (.85, .15), middle-right (.85, .5),
bottom-right (.85, .85), bottom-centre (.5, .85), bottom-left (.15, .85), middle-left (.15, .5).

**Coordinates.** Unchanged. When an anchor is accepted, its target is converted to normalized display coordinates:
the view's on-screen origin plus fraction × view size, divided by `DisplaySpace.bounds`
(`WindowManager.maximumWindowMetrics` on API 30+).

**Schedule.**

```
pass 1 (ordinary): P1 P2 P3 P4 [blink break 2 s] P5 P6 P7 P8 P9 [inter-pass break 3 s: move slightly up]
pass 2 (higher):   P1 P2 P3 P4 [blink break 2 s] P5 P6 P7 P8 P9 [inter-pass break 3 s: move below the original]
pass 3 (lower):    P1 P2 P3 P4 [blink break 2 s] P5 P6 P7 P8 P9 -> one GazeModel.fit on all 27 anchors
```

- Each pass's nine anchors are kept separately, labelled with pass, point and head position (`ordinary`, `higher`,
  `lower`; `CalibrationAnchor`). All 27 go to the fit in collection order. Passes are not averaged into nine anchors.
- Each anchor keeps its genuine median features, including face position and scale. Nothing is normalized to make
  the three positions look alike.
- No model is fitted or activated after pass 1 or 2. The model is installed only if all 27 anchors were collected,
  the single fit succeeds and the calibration session is still current.
- No samples are collected during any break. Frames delivered during a break are ignored entirely. The next
  target's settling and timeout clocks start when the end of the break is noticed (by the next frame or by the
  100 ms tick).

**Per-point rules, unchanged from `BASE_SHA`** (verified in the baseline `MainActivity.CalibrationView`, now
`CalibrationRules`):

| Rule | Value |
|---|---|
| Settling | Samples within 1,000 ms of the point start are not collected |
| Completion | ≥ 22 accepted samples **and** ≥ 2,500 ms since the point start |
| Aggregation | Per-feature median (`medianFeatures`) of the point's samples |
| Sample freshness | Capture timestamp newer than the last accepted capture; age 0–250 ms; quality TRACKING or OUT_OF_RANGE (`GazeSample.calibrationFeatures`, unchanged) |
| Blinks / missing input | No sample is added and the hint changes. Collected samples are kept and the clocks keep running |
| Within-point movement | If any sample's face x or y differs from the point median by > 0.045, the samples are discarded and the same point restarts (new settling and timeout) |
| Timeout | A point not complete after > 9,000 ms fails the whole calibration (checked every 100 ms, never during a break) |
| Mid-pass break | 2,000 ms after the 4th point, now in every pass |
| Cancellation | Back, leaving the screen (`onPause`) or tracking stopping closes the calibration; the session token prevents a late result from being installed |
| Model replacement | The previous model stays active until a new calibration succeeds; failure or cancellation keeps it |

New in this experiment: three passes, the 3,000 ms inter-pass break after passes 1 and 2, the head-position
instructions and the labels. No between-anchor geometry filter and no pose-acceptance gate were added.

**On-screen text.**

| Moment | Korean (shown) | English |
|---|---|---|
| Collecting (title) | 시선 보정 · 2/3회차 · 4/9 | Gaze calibration · pass 2/3 · point 4/9 |
| Collecting (position), pass 1 / 2 / 3 | 자세: 기본 위치 · 정면을 보고 그대로 유지 / 자세: 조금 높은 위치 · … / 자세: 조금 낮은 위치 · … | Position: ordinary / slightly higher / slightly lower · face the front and hold still |
| Hint | 4 / 9 · 파란 점을 계속 바라보세요 | 4 / 9 · keep looking at the blue dot |
| Hint, no usable eyes | 눈 검출 대기 · 눈을 뜨고 얼굴을 카메라로 향해 주세요 | Waiting for eyes · open your eyes and face the camera |
| Hint, movement retry | 머리가 움직였습니다 · 같은 점에서 다시 측정합니다 | Your head moved · measuring the same point again |
| Blink break | 2/3회차 · 4/9 완료 · 잠시 쉬어가기 / 눈을 편하게 깜빡여 주세요 / 2초 후 나머지 5개를 이어서 진행합니다 / 머리는 조금 높은 위치 그대로 · 기기는 고정 | Pass 2/3 · 4/9 done · short break / blink comfortably / the remaining 5 continue in 2 s / keep your head in the slightly higher position · phone fixed |
| Break after pass 1 | 1/3회차 완료 · 잠시 쉬어가기 / 다음 자세: 조금 높은 위치 · 정면을 보고 그대로 유지 / 머리를 조금 위로 옮겨 주세요 / 기본 위치보다 살짝 높게 · 정면 유지 / 3초 후 2/3회차를 시작합니다 / 기기는 움직이지 마세요 · 눈은 편하게 깜빡이세요 | Pass 1/3 done · short break / next position: slightly higher · face the front and hold still / move your head slightly up / slightly above the ordinary position · keep facing the front / pass 2/3 starts in 3 s / do not move the phone · blink comfortably |
| Break after pass 2 | 2/3회차 완료 · 잠시 쉬어가기 / 다음 자세: 조금 낮은 위치 · … / 머리를 조금 아래로 옮겨 주세요 / 기본 위치보다 살짝 낮게 · 정면 유지 / 3초 후 3/3회차를 시작합니다 / 기기는 움직이지 마세요 · 눈은 편하게 깜빡이세요 | Pass 2/3 done · short break / next position: slightly lower · … / move your head slightly down / slightly below the ordinary position · keep facing the front / pass 3/3 starts in 3 s / do not move the phone · blink comfortably |
| Always | 중단하려면 뒤로 가기 | Press Back to cancel |

The candidate ID is drawn in small grey text at the bottom of the calibration screen, so the operator can check
which build is installed.

## 4. What changed and what did not

Changed (calibration presentation, scheduling and collection only):

| File | Change |
|---|---|
| `app/src/main/java/dev/gazedot/CalibrationController.kt` | New. The three-pass schedule, the original per-point rules (moved from `MainActivity`), labelled anchors, the single final fit and the screen text. Identical in A and B |
| `app/src/main/java/dev/gazedot/CalibrationPlan.kt` | New. Candidate ID, head position per pass and position instructions. **The only main-source file that differs between A and B** |
| `app/src/main/java/dev/gazedot/MainActivity.kt` | `CalibrationView` delegates to the controller. Same tick, display-coordinate conversion, `TrackerState` session handling and result messages. New drawing of progress, position and break text. The interruption message no longer says "9점" (nine-point). Identical in A and B |
| `app/src/test/java/dev/gazedot/CalibrationControllerTest.kt`, `CalibrationTestHarness.kt` | New schedule and state-transition tests with a fake clock. Identical in A and B |
| `app/src/test/java/dev/gazedot/CalibrationPlanTest.kt` | New candidate-specific tests (ID, positions, instructions, genuine per-position features) |
| `docs/calibration-comparison/README.md` | This file |

Unchanged: eye and iris feature extraction (`LandmarkFeatures`, `EyeObservations`), eye-state classification,
`GazeModel.fit`, its model family, ridge term and rejection thresholds, `GazeModel.predict` and its runtime validity
checks, `GazePipeline` smoothing and staleness rules, display-coordinate conventions (`DisplaySpace`,
`DisplayCoordinates`), clicking, scrolling, text entry, the tutorial, permissions, `TrackingService`, `TrackerState`,
dependencies, Gradle and AGP versions, the application ID (`dev.gazedot`) and signing. All existing tests are kept
unmodified. The fitted personal coefficients change only because the calibration data change.

## 5. Build and test

Requires JDK 17 or 21 and Android SDK 35. Set `sdk.dir` in the ignored `local.properties`. Set `JAVA_HOME` only for
the build process, not globally. From the repository root (PowerShell):

```powershell
$env:JAVA_HOME = "<path to JDK 17 or 21>"   # this shell only
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Its SHA-256 is recorded with the build report, not in this
file, because the file is part of the commit the APK is built from.

## 6. Automated-check results

Run on 2026-11-14 on Windows 11 with Microsoft OpenJDK 21.0.12.1, Gradle 8.13 (wrapper) and AGP 8.9.2. No phone was
connected or used.

| Check | `BASE_SHA` (before changes) | Candidate B |
|---|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 49 tests, 0 failures, 0 errors, 0 skipped | 73 tests (49 existing + 19 `CalibrationControllerTest` + 5 `CalibrationPlanTest`), 0 failures, 0 errors, 0 skipped |
| `:app:lintDebug` | 0 errors, 30 warnings | 0 errors, 30 warnings, identical to the baseline by issue ID and file (no new warnings) |
| Kotlin compiler | 1 warning: deprecated `setDecorFitsSystemWindows` (`MainActivity.kt:51`) | The same pre-existing warning only |

The baseline's 30 lint warnings are DataExtractionRules 1, GradleDependency 5, ImplicitSamInstance 2,
ObsoleteSdkInt 1, OldTargetApi 1, SetTextI18n 14, SwitchIntDef 2, TypographyFractions 1, UnusedAttribute 1 and
UseKtx 2.

The tests cover: exactly three passes and 27 anchors; target order and labels across pass boundaries; no fit or
model activation after 9 or 18 anchors; one fit after 27; the three blink breaks and two inter-pass breaks with their
durations; frames ignored during breaks; settling and timeout clocks restarting at the end of a break; the original
freshness, duplicate, quality, settling, 22-sample and 2.5 s rules; missing input; within-point movement retries;
timeouts; fit rejection; cancellation; preservation of the previous model; genuine per-pass features reaching the
fit; and Candidate B's ID, the ordinary/higher/lower order, the position shown throughout each pass, and movement
instructions that appear only in the inter-pass breaks. During development, deliberately broken controllers
(intermediate fit, no clock reset after a break, blink break only in pass 1, frames used during a break, fit on the
last pass only) and plans (higher and lower swapped, missing move instruction) each failed at least one of these
tests.

## 7. Known limitations and items for phone validation

- **Not run on a phone.** Layout and legibility of the new text, the real per-point duration, break timing, frame
  rate, and the participant's experience are untested. With the ~15 fps cadence a point needs at least about 2.5 s,
  so a calibration takes at least about 1.5 minutes. This has not been timed.
- **Repositioning within 3 s is unvalidated.** If the participant is still moving when a pass starts, the first
  point's samples may be discarded by the unchanged within-point movement rule (> 0.045 face shift) and the point
  restarts. If the participant never settles, that point times out after 9 s and the calibration fails.
- **Positions are qualitative** and not measured. How much "slightly" moves the face in the image, and whether
  participants reproduce it, is unknown until physical positions are defined.
- The unchanged runtime validity check accepts predictions only while the face stays within 0.12 (normalized image
  coordinates) of the mean anchor face position and within 0.72–1.28 × the mean scale. In B that mean combines three
  positions. Whether higher and lower positions stay inside this range, during calibration review or evaluation, is
  untested. The check was deliberately not loosened.
- One linear map of the iris features must fit all three positions. The unchanged fit thresholds (iris-feature
  spread sx ≥ 0.008, sy ≥ 0.006; RMS fit error ≤ 0.18) may reject more B calibrations than A calibrations. If so,
  that is a result to report, not a reason to change the thresholds.
- Each of the 27 points has the unchanged 9 s timeout, and one timeout fails the whole calibration. That gives three
  times as many chances to fail as the nine-point flow.
- The labelled anchors and the candidate ID are reported in `CalibrationOutcome`. After calibration, only the
  model is kept, as before. Nothing is logged or stored yet; the evaluation integration must add this (section 8).
- Candidates A, B and the baseline all use application ID `dev.gazedot`. On a phone, installing one replaces the
  other. They cannot be installed side by side.
- The root `README.md` still describes the nine-point flow. One `TrackingService` status message still says
  "9점 보정을 시작해 주세요" (start the nine-point calibration). Both are outside the change boundary.
- Retained baseline behaviour: missing input does not clear a point's samples, so a point's median can combine
  samples from before and after a blink. A break's end can be noticed up to 100 ms late.

## 8. Planned evaluation integration (not done in this stage)

The existing evaluation tools on `feature/gaze-evaluation-home-screen-iter3` (inspected at `72411ec`, based on
`30be1cf`, older than `BASE_SHA`) will be ported next. They will not be merged wholesale, and their metrics will be
kept. Known adaptations:

- **27-anchor snapshots.** Record `CalibrationOutcome` when a model is installed. Extend the snapshot's anchors with
  pass, point and position labels in collection order (a new snapshot schema version). Replay can refit any number
  of anchors.
- **Candidate identifiers.** `home_screen_candidate` and `candidate_9_point_calibration` are hard-coded in the app,
  `protocol.json`, the replay and the analyzer, which marks other predictor sources unofficial. Record `CalibrationPlan.ID`, update
  the source base to `BASE_SHA`, and keep A and B in separate compatibility groups.
- **Ordinary, higher and lower evaluation positions.** This needs a declared head-position field and per-position
  reporting, which protocol 2.0.0 does not have. That requires a new protocol version.
- **One run per calibration.** The app gate and the analyzer allow exactly one evaluation run per calibration.
  Evaluating three positions needs either a fresh calibration per run or a declared protocol extension.

Accuracy remains **NOT_MEASURED** until those sessions are recorded.
