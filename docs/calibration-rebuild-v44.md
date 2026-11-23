# Calibration rebuilt in the existing branch — v44

Branch: gaze-response-fix. No new branch. Includes v42 texting personalization/English, four-direction edge scroll bars and revised quick-start geometry.

## New flow

ScreenCalibration owns acquisition and fitting; the Android view supplies display coordinates and renders progress/results. Nine targets span 8–92% of the screen. Each new target settles for 600 ms, then collects 24 distinct, finite, fresh binocular eye-feature frames. An unavailable head matrix or iris diameter does not prevent collection. A brief blink pauses collection and adds a 150 ms reopening interval. A gap longer than 1.5 s clears only the current point. No timer discards the other eight points.

The first 16 frames at each target produce a median anchor with equal target weights; the last eight estimate temporal holdout error. The existing affine mappings for the two eyes are retained. No new smoothing is added to calibrated runtime coordinates. The result screen reports errors and offers use of a computable mapping, remeasurement of the worst target, or all targets. It does not silently label a large error as accurate, and does not discard the session at a universal RMS threshold. Degenerate or contradictory features still cannot produce a fabricated model: those results have no apply button.

Same-target holdout is not independent validation or proof of user accuracy. No actual S23 trace is available. An affine mapping can still be inadequate for some users or head positions.

## Other fixes retained

- Missing blink outputs must not act as fully closed eyes. Only available blink evidence is consumed. Low-blink visible eyes can start before a personal baseline is ready. Blink scores are read independently of optional head-pose extraction. Actual closure/wink evidence still excludes samples.
- Quick-start uses iris position relative to the visible eyelid opening for vertical position. Smooth bounding avoids hard-clipping a fixed neutral offset to a constant edge. This remains an approximate geometric pointer, not calibrated gaze.
- LEFT and RIGHT edge bars join UP and DOWN. Bars are drawn on their corresponding display edge. Corners are neutral to avoid competing directions. Horizontal gestures require an app that supports horizontal scrolling.

## Reference principles

- [Tobii calibration procedure](https://developer.tobiipro.com/commonconcepts/calibration.html): allow fixation before collecting each target, cover the usable display, support recalculation from target samples, distinguish calibration from validation. The SDK and hardware are not included in NoTouch.
- [Google MediaPipe Iris](https://research.google/blog/mediapipe-iris-real-time-iris-tracking-depth-estimation/): iris landmarks alone do not infer where a person is looking. NoTouch still needs screen mapping or its explicitly approximate quick-start mode.

## Validation

- Android unit tests: 210 passed, zero failures/errors/skips. Includes all-nine-target acquisition and mapping, no-head input, duplicate/stale/closed samples, one-point retry preserving eight points, stationary-data rejection, median resistance to an outlier, quick-start movement through the pipeline and horizontal scroll without click-through.
- Debug APK assembly and lint succeeded; lint has zero errors and 49 warnings (including draw-time allocation recommendations). APK v2 signature verified; version 44, arm64-v8a.
- `adb devices` reported no connected device. Device testing remains necessary: all nine points, short blink pause, isolated retry, quick-start movement and four-direction scrolling in appropriate apps. Synthetic tests do not measure real-user gaze accuracy.

## Artifact

- Filename: NoTouch-v44-S23-calibration-rebuild-debug.apk
- Size: 23,432,926 bytes
- SHA-256: 29b29f4e78652585068a40d9ae290985aeefc87af6ed0107f48e93c82e92520b
- [Download from the course Drive folder](https://drive.google.com/file/d/1E2aIb0RnLLGfZbnRWY1uhpYlk0gSjdIG/view)
- Debug-signed S23 test build, not a store release.
