# Calibration integration into main — 2026-11-25

## Follow-up: restore the v46 runtime and horizontal bars

The initial selective integration below left main's old frame delivery, filter and vertical-only bar controller in place. This did not reproduce the full v46 tracking behavior. The follow-up copies `GazePipeline`, `GazeStabilizer`, `TrackingService`, `TrackingStatus`, `LatestFrameDelivery`, `EdgeScrollBar`, `DotOverlay`, `EyeGestures` and `ControlAccessibilityService` directly from `a02db2b`. Calibration acquisition, per-eye models and eye classification also match that revision. No new tracking algorithm is introduced.

Main retains its existing retargeting navigation and UI. Retargeting sampling now calls v46's `calibrationCheckPoint`, using fresh pre-display-filter coordinates instead of learning display lag. Texting/server code and main's logo remain unchanged. The four edge strips are the existing v46 implementation, including the longer horizontal swipe from `fix/horizontal-scrolling`.

Validation: 206 JVM tests passed with zero failures/errors/skips; debug compilation and lint passed. Physical-device accuracy remains unverified. No APK was packaged for this integration.

## Initial selective integration (historical)

Source: `gaze-response-fix` at `a02db2b` (v46). Target baseline: `main` at `06f3899`.
This selectively ports calibration, not the whole source branch.

## Implemented behavior

- Camera start leads directly to nine-point calibration. No mandatory face-alignment / iris-size registration and no uncalibrated quick-start mode.
- Center, four cardinal targets and four corners at 8% / 92% of the calibration view. Positions are converted to full-display coordinates.
- Each target waits 600 ms, then collects 24 distinct, fresh, finite, open-eye MediaPipe samples. A brief blink pauses collection; a gap longer than 1.5 seconds clears only the current target.
- Each eye gets its own affine ridge regression, fitted to the median of the first 16 samples per target. Screen-space predictions are averaged; there is no redundant third fit on averaged eye features.
- The remaining eight samples report internal RMS / worst-target error. These are same-target temporal holdouts, not independent real-world accuracy measurements.
- Results can be applied, the worst target remeasured, or all nine targets restarted. Failed or cancelled acquisition preserves the prior model.
- Open-eye baselines are collected automatically without blocking setup. Independent MediaPipe blink scores allow visible narrowed eyes at edge targets; real blinks / winks remain excluded.
- Missing optional head-pose geometry does not block calibration. Ordinary face translation / distance changes no longer invalidate otherwise finite eye-relative mapping. This is not full head-motion compensation.
- Follow-up: applying a successful nine-point calibration now opens the existing five-target retargeting screen again. Its existing bounded bias correction and result button are unchanged. Normal setup proceeds from that screen to the tutorial; recovery recalibration returns to the previous app after the check. No layout, drawing, labels or button designs were changed.
- The legacy `CalibrationAudit` helper retains its explicit training-pose consistency check from the source branch; it no longer relies on runtime coordinate rejection. The new nine-point `ScreenCalibration` flow does not call this legacy helper or impose its pose gate.

## Integration boundaries

Main's current logo, version metadata, texting / API implementation, scrolling, frame delivery and display smoothing remain unchanged. The source branch's unrelated texting, scrolling and latency changes are not merged here. No new branch is created; integration is prepared in a detached worktree and pushed as a normal main commit after checks.

## Validation

Command: `gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug -PtargetAbi=arm64-v8a --offline --no-daemon --console=plain`

Results: **BUILD SUCCESSFUL**; 180 JVM tests passed, zero failures/errors/skips. Android debug compilation passed. Lint reported zero errors and 50 warnings. `git diff --check` passed. Physical-phone calibration accuracy and accessibility interaction require device validation; synthetic tests do not establish either. No APK was packaged for this source-integration request.

Retargeting reconnection validation: the same command passed with 181 tests, zero failures/errors/skips and zero lint errors (50 warnings). The added integration test checks that screen-space correction reaches runtime prediction and is not inherited by a newly fitted calibration. Physical navigation and accuracy have not been tested on a phone.
