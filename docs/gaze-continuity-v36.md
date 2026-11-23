# v36: regression correction after v35 feedback

## Confirmed code causes

- Runtime predictBase rejected image-space face displacement over 0.12 or face scale outside 72–128% of the calibration reference. Since iris features are eye-relative, those image-space checks unnecessarily removed otherwise valid predictions. Removed runtime face-position/relative-scale gates; finite features and positive scale remain mandatory. Eye detection, freshness and blink gates remain unchanged. Large posture changes during calibration remain a separate data-quality rejection in CalibrationAudit.
- TrackingStatus interpreted every non-actionable ScreenGaze as a failed mapping, including UNCERTAIN samples used to confirm a saccade or settle after a blink. These now show a non-error pending state; no face-position error banner. Pending coordinates still cannot click.
- v33/v35 fast-follow enabled a 40 ms time constant even for repeated 3.5% displacements and reduced fixation smoothing to 100 ms. Removed that shortcut and restored pre-v33 220 ms fixation / 65 ms relocation response. Retained the 2D representative instead of independent axis medians. This prioritizes stationary stability over very fast small movements.
- Main's post-calibration screen-check was unconditionally launched after the initial five targets, causing a second five-target sequence. Successful calibration now enters the tutorial directly. Additional precision training is still explicitly available from Home; the old automatic screen-offset check is not invoked.

- The within-fixation calibration pose check also treated a 10% change in projected iris diameter as head movement. Removed that iris-size criterion; actual face scale is still audited separately, and head rotation/translation checks remain.

## Regression coverage

Synthetic eye-relative inputs with changing face center and scale retain coordinates and can complete dwell. Calibration pose inconsistency, invalid eye features, eye loss and stale frames remain rejected. Paired +/-4% screen jitter is checked at 33 and 66 ms intervals; intentional motion must still converge. Alternating TRACKING/UNCERTAIN samples must not display a coordinate-error banner. Existing texting duplicate-frame, action-isolation and API tests remain in the suite.

These tests establish the logic changes, not real camera accuracy. No phone is connected. Removing a rejection gate does not estimate or compensate arbitrary head rotation or camera-to-screen geometry.

## Validation (2026-11-23)

- Final `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a --offline --no-daemon`: successful. 181 unit tests, zero failures/errors.
- APK signature v2 verified, dev.gazedot versionCode 36, arm64-v8a.
- SHA256: `2a7b9a76ca9d4b1ff88b10c5b8eef9b6157be43a91ca198bf02c6ec4b587ad01`.
- File: `output/apk/NoTouch-v36-S23-gaze-continuity-debug.apk`.
- Drive: https://drive.google.com/file/d/1qoDVVn2p6XoNX9ex3L8OIPOVjqZjb0Io/view
- Physical S23 testing is not completed; use this build to verify the reported symptoms.
