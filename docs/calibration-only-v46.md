# Calibrated tracking only — v46

Removed quick-start from the existing gaze-response-fix branch: home button/help text, mode switching and saved prior-model state, heuristic coordinate mapping, quick-only eyelid geometry and diagnostics. Removed tests specific to the deleted feature; retained the eye-visibility tests and regular calibration tests.

The flow is camera start → nine-point calibration → use the computed model. A new camera session has no fabricated default pointer. Existing binocular fitting, calibration retry, horizontal-scrolling merge, four edge bars and texting features remain.

Validation: 216 unit tests passed, zero failures/errors/skips. Build and lint succeeded (zero errors, 50 warnings); APK v2 signature verified. Quick-start references are absent from app main/test sources. No physical-device accuracy test was performed.

APK: NoTouch-v46-S23-calibration-only-debug.apk, version 46, arm64-v8a, 23,432,930 bytes. SHA-256: b9845de0b3d35555a3fd9dd6b1aae8f3a60486c2bb681e463d2746b853266d25.

[Course Drive download](https://drive.google.com/file/d/1_60IqeS8M4G6OHkZbXBIL8AxSpkOilgx/view). Debug-signed S23 test build, not a store release. This removal does not establish real-device gaze accuracy.
