# Simplified binocular calibration — v45

Existing branch: gaze-response-fix. Includes merge 985da43 from fix/horizontal-scrolling.

The normal path is: acquire nine targets, fit one affine screen mapping per eye, average the two screen positions. Each target still uses a median anchor and a separate later sample block for the result report. Brief blinks pause collection; individual targets can be remeasured. No additional filter, neural network, head-position training or correction layer was introduced.

Removed the redundant third regression on averaged eye features. It was required to succeed although runtime coordinates came from the two per-eye regressions. Opposing feature changes could therefore cancel in the unused fit and reject otherwise valid binocular mappings. Mapping-gain diagnostics now report the larger per-eye gain rather than the unused regression's gain.

Future-dated frames no longer advance the acquisition timestamp and lock out subsequent camera data. Existing freshness and duplicate-frame checks remain.

Horizontal edge bars now dispatch the merged longer/faster stroke: half the screen width in 300 ms. Vertical strokes retain their previous parameters. Real devices and apps may differ in gesture handling.

## Validation

- Android unit tests: 220 passed, zero failures/errors/skips. New cases cover cancellation in averaged eye features despite valid separate mappings, and future timestamps followed by normal camera frames.
- Debug APK assembly and v2 signature verification succeeded. Version 45 / 0.16.1-simple-binocular, arm64-v8a.
- Lint completed: zero errors, 50 warnings.
- Automated fixtures do not establish physical-phone gaze accuracy. No physical-device test was performed in this turn.

## APK

- Filename: NoTouch-v45-S23-simple-calibration-debug.apk
- Size: 23,432,930 bytes
- SHA-256: cba33a828dac8c33c66153c7814cc51ae40e5ae560e5fd25f99b75d02ffaaa40
- [Download from the course Drive folder](https://drive.google.com/file/d/15AOd-rHWttYmvplomdD_7kIoUV0hYEwX/view)
- Debug-signed S23 testing artifact, not a store release.
