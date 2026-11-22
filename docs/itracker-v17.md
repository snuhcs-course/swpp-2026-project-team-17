# NoTouch iTracker research integration (v17)

## What runs

CameraX produces an upright, unmirrored RGB frame. MediaPipe Face Landmarker supplies the face/eye regions, iris landmarks, head pose and eyelid/blink state. OpenCV DNN 4.12.0 runs the official frozen iTracker 25x Caffe checkpoint locally on CPU. Both anatomical eye images, the face image and a 25x25 face-position grid feed the CNN. The two output values are in the model's camera-centimetre coordinate convention.

Five centre/corner fixations fit a small affine screen mapping over those two outputs. This empirically adapts coordinates to the current phone/pose; it is not a physical measurement of S23 screen geometry. The CNN weights are never trained or updated on the phone. The old thirteen-pose/eighteen-step head training is not launched or offered from settings. The face/open-eye setup remains before the five targets.

Existing smoothing, wink/closure exclusion, reopening settling, accessibility click/scroll, voice input, text prediction and candidate-ranking layers remain downstream. No gaze images are sent to the text API. Existing landmark-only regressions remain for tests/legacy code; the live service supplies only ITRACKER-tagged features. There is no silent switch to landmark inference when the CNN is unavailable. Different feature sources cannot share a calibration.

The legacy narrow face-position/scale gate is not applied to CNN screen mapping: face position/scale are already inputs to iTracker. This does not guarantee head-motion invariance. Image crop visibility, fresh-frame checks and open-eye requirements still apply. The existing 250ms freshness cutoff remains; slow inference pauses actions rather than using stale coordinates. Settings -> 시선 모델 상태 · iTracker shows raw outputs and CNN processing time.

## Model/preprocessing provenance

- Official repository: https://github.com/CSAILVision/GazeCapture
- Pinned revision: e4af90da3c4c9068fbce433fa648d1f9bc7a394b
- Checkpoint: models/snapshots/itracker25x_iter_92000.caffemodel
- SHA-256: d748042ebd402a4b6418cdff44a6e64f9bca1256fd3761f7ddc68fd5519ef98a
- Official deploy graph, changed only from batch 256 to batch 1.
- Official per-image Caffe mean tensors decoded to little-endian float32 NCHW. Inputs are 224x224 BGR in 0..255 minus these per-pixel means. No RGB /255 normalization and no eye flipping.
- Face grid follows the official rounded/clamped rectangle and row-major flattening.
- Android crops use a square around the MediaPipe face extent (1.05 times the larger dimension) and eye-corner midpoint squares (1.7 times eye width), bilinear resizing and replicated edges. These are approximations to the original Apple detector crops, not identical detections. This domain mismatch and device differences can affect accuracy.
- Startup executes a deterministic zero-tensor inference and checks both outputs against the desktop reference before the camera starts. Build fails if the checkpoint is missing or its SHA-256 differs.

## Reproduce

Read the upstream research license, then run `python tools/itracker/prepare.py`. It fetches the pinned official files and records source and asset hashes in the ignored assets folder. `python tools/itracker/verify.py` checks real network execution, all four input branches and the mean-subtracted fixture (Python numpy and opencv-python required).

For Android: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. On this workstation JAVA_HOME points to JDK 17 and JAVA_TOOL_OPTIONS uses the existing Unix-domain-socket workaround described in the build logs.

## Licensing and validation limits

This is a private course/research experiment. The original model/license is research-only and restricts redistribution and commercial use. Assets are excluded from Git; do not publish this APK or its model files publicly. Upstream terms: https://github.com/CSAILVision/GazeCapture/blob/master/LICENSE.md

Attribution: Kyle Krafka, Aditya Khosla, Petr Kellnhofer, Harini Kannan, Suchi Bhandarkar, Wojciech Matusik and Antonio Torralba. *Eye Tracking for Everyone*. CVPR 2016.

Desktop smoke testing confirms that the actual checkpoint executes and each of its four inputs changes the prediction. JVM tests cover five-point mapping, feature-source isolation, grid layout, and wink suppression. These checks do not measure gaze accuracy or latency on a physical S23. Five-point mappings and eye baselines currently remain in session memory; restarts still require short setup. Accuracy and gesture responsiveness must be compared on device under the same lighting, distance and targets before claiming an improvement.

## Build result (2026-11-16)

- 105 JVM tests passed, zero failures/errors. Debug assembly and Android lint succeeded.
- Packaged checkpoint SHA-256 matches the official downloaded checkpoint; all three float means, research license and ARM64 OpenCV native library verified inside the APK.
- APK v2 signature verified. No ADB device was connected, so no physical-device run was performed.
- File: `output/apk/NoTouch-iTracker-v17-research-debug.apk` (155,533,186 bytes).
- APK SHA-256: `143286cfc703aebb7b1c4ca923d7d113c77de960032cee84a5ba27ab75856b25`.
