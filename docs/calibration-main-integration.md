# Calibration integration into main — 2026-11-25

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
- The old automatic post-calibration correction screen is no longer entered. Applying a result proceeds to the existing tutorial.
- The legacy `CalibrationAudit` helper retains its explicit training-pose consistency check from the source branch; it no longer relies on runtime coordinate rejection. The new nine-point `ScreenCalibration` flow does not call this legacy helper or impose its pose gate.

## Integration boundaries

Main's current logo, version metadata, texting / API implementation, scrolling, frame delivery and display smoothing remain unchanged. The source branch's unrelated texting, scrolling and latency changes are not merged here. No new branch is created; integration is prepared in a detached worktree and pushed as a normal main commit after checks.

## Validation

Command: `gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug -PtargetAbi=arm64-v8a --offline --no-daemon --console=plain`

Results: **BUILD SUCCESSFUL**; 180 JVM tests passed, zero failures/errors/skips. Android debug compilation passed. Lint reported zero errors and 50 warnings. `git diff --check` passed. Physical-phone calibration accuracy and accessibility interaction require device validation; synthetic tests do not establish either. No APK was packaged for this source-integration request.
