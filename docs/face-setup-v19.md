# v19 iris setup repair

The iris measurement remains required. There is no skip or timeout-success path.

## Changes
- During face setup, run MediaPipe without the downstream iTracker CNN. Restore the existing CNN gaze pipeline on exit, rejecting results from the previous phase.
- Allow setup measurements up to 750 ms old; actionable gaze retains its 250 ms cutoff.
- Retain good measurements across brief blinks and duplicate UI polls. Reset after prolonged loss or a significant pose change.
- Preserve acceptance conditions: 30 distinct valid frames over at least 1800 ms, with at least 90% of bilateral iris diameters within 8% of the medians. Existing eye-open, framing and frontal-pose thresholds remain unchanged.
- Show distinct failure hints, collected count, iris pixel sizes and head angles. Restart clears the collection; it does not bypass measurement.
- Build ARM64 only for the S23, reducing the APK from approximately 155.5 MB to 81.8 MB.

## Validation, 2026-11-17
- 112 JVM tests passed, zero failures/errors/skips. Added seven regression tests for blinking, duplicate polls, delayed setup frames, long loss, pose changes, invalid eyes/irises, stability and restart.
- assembleDebug and lintDebug passed.
- APK v2 signature verified; only arm64-v8a native libraries packaged.
- Official iTracker checkpoint SHA-256 verified inside the APK; face_landmarker.task present.
- git diff --check passed.

## Remaining verification
No ADB device is attached. Physical camera capture, the user's open-eye/frontal-pose rejection, on-device latency, and end-to-end completion on the S23 have NOT been verified. The automated tests exercise supplied measurements, not actual MediaPipe recognition of the user's face. This repair addresses repeated collection resets and inference coupling; it does not establish that the reported eye/pose rejection is resolved. Do not describe it as a confirmed real-device fix.
