# Quick start and integrated fixes — v42

Branch: gaze-response-fix. This includes all v40 texting personalization and [v41 edge visibility, directional audit, edge scroll bars and English prediction](calibration-edge-visibility-v41.md).

## Without calibration
Home has “보정 없이 빠른 시작”. It starts the normal permission/camera flow and immediately uses available eye landmarks, without nine target points, a face alignment stage or an initial neutral-fixation training step. The existing nonblocking natural eyelid baseline still operates; it does not fit screen coordinates.

This is explicitly an uncalibrated heuristic: x_screen = 0.5 - 5*x_eye, y_screen = 0.5 + 8.3333*(y_eye - 0.02), for upright unmirrored camera features. Constants are engineering defaults, not measured personal parameters or a pretrained gaze model. Full numeric screen coverage does not prove that every user can physically reach all of it. It retains the regular tracking freshness, blink handling, display smoothing and interaction logic. Personal accuracy is not claimed.

“빠른 시작 끄기 · 보정 모드” restores the previous in-memory calibration if available. Successful nine-point calibration exits quick-start; an unsuccessful attempt keeps the prior mode. A stopped tracking service/process does not persist a personal calibration. The home status explicitly labels quick-start as approximate.

Also corrected the home button reference: the third “시선 테스트” button was overwriting calibrationButton, so updates for the second calibration step were applied to the wrong button.

## Validation
Debug build and lint completed successfully (zero errors, 46 warnings). Android unit tests: 202 passed, zero failures/errors/skips. Relay tests: 22 passed. APK version 42, arm64-v8a, v2 signature verified. English production smoke: six English completions, HTTP 200, duplicate nonce rejected with 409.

No physical phone is connected. Verify permissions, immediate pointer appearance, switching back to calibration, blink gestures, edge reach and the six-choice texting panel on the S23. These checks do not establish real-user gaze accuracy.

## APK
- NoTouch-v42-S23-quick-start-debug.apk (23,416,542 bytes)
- SHA-256: 77f2c78c10acbcf89bd090d54a81608c5c7eab1ddc154ab0636249736b4239d0
- [Download from the existing course Drive folder](https://drive.google.com/file/d/1QHmRmQhqbZesAmEA51Ydy4sVlx5Qp5S_/view)
- Debug-signed S23 test APK, not a store release.

