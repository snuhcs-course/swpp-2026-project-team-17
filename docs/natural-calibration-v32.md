# v32: remove required face alignment

Based on main/v30 (9695ca3). Branch: calibration-without-face-alignment. This is a gaze comparison build, not the separate feature/texting API build.

## Behavior

Camera start -> five screen targets -> quality audit -> tutorial/home. FaceSetupActivity and its manifest entry are removed. Cancellation, recovery recalibration and tutorial recalibration all use the same direct screen-calibration path. No face-center target, prescribed iris diameter or head-angle target is required before calibration.

The original registered EyeProfile was used to normalize eyelid openness, not as the trained screen-coordinate mapping. To preserve support for different eyelid shapes, NaturalEyeBaseline collects 20 distinct frames spanning at least one second with valid low blink scores. Samples expire after five seconds. Collection never blocks the UI or gates coordinate availability; existing default classification remains available until the baseline exists. Once established, the baseline is frozen for the service instance and cannot learn a wink as a new open-eye baseline. Missing blink scores do not silently count as open eyes.

The five-point binocular mappings, their pose-consistency audit, 500ms freshness budget, gestures and edge scrolling are unchanged. Start in a comfortable posture with both eyes visible and keep it during calibration. This does not make the model invariant to large later head/camera movements.

## Validation

- 133 JVM unit tests passed, zero failures/errors/skips. New tests cover off-center/angled faces and small irises, narrow open eyelids, wink recognition, duplicates, missing blink scores, closed-eye samples, stale samples and non-finite input.
- Debug APK built for arm64-v8a, versionCode 32. APK v2 signature verified. Removed activity is absent from the packaged manifest.
- APK SHA256: 52e1cec54bd566a2e6450b4f712fa8482f2320fd6c5d0d496cac454b7f01ae40.
- Existing 소개원실 Drive folder upload and size verified: https://drive.google.com/file/d/1SBklrgRulLB8YJZanG-EVMDjftSjmcpg/view?usp=drivesdk
- No physical S23 run was performed. Startup baseline quality and actual gaze accuracy require the user's device comparison; automated tests do not establish them.

Gradle unit test / assembleDebug / lintDebug completed successfully. Lint: 0 errors, 35 warnings.
