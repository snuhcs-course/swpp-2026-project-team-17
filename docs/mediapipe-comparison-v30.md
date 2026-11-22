# v30: MediaPipe-only comparison branch

Branch: `mediapipe-gaze-accuracy`, based on v29 (`0b65352`).

This is an alternative pipeline, not proof that iTracker caused the reported jitter.

## Implementation

- Remove iTracker execution and OpenCV dependency. Exclude iTracker assets from the APK.
- Keep MediaPipe Face Landmarker, face/iris setup and open-eye classification.
- Use each iris centre relative to its own eye corners. Eye-width normalization and an eye-local coordinate basis remove ideal image translation, uniform scale and in-plane rotation from the features; they do not solve full 3D head motion.
- Fit left and right affine mappings separately using five targets, then average the two screen predictions. Partial eye closure is excluded rather than substituting a distorted wink coordinate.
- Preserve v29 temporal quality audit and v28 pointer stabilization. No new smoothing filter or head-movement training was added.
- Reject collection from before the target's settling interval using frame processing timestamps. Clear partial collection after invalid input or a long frame gap. A fixed target deadline prevents endless resets.
- Reject calibration cleanly if a landmark model cannot predict collected samples because of large pose/scale variation, instead of dereferencing a null prediction.
- Existing tutorial controls, scroll bars, candidate selection and dictation remain on the inherited branch. Texting remains excluded as previously requested.

## Comparison procedure

1. Use the same phone support, portrait orientation, lighting and viewing distance as v29.
2. Stop the old tracking service, install v30 and restart tracking. Confirm the model status says MediaPipe. Complete face/iris measurement and five-point calibration again.
3. First fixate the screen centre, then each quadrant. Record steady offset separately from jitter, disappearing pointer and delay. Do not follow the moving pointer.
4. Run the six-second fixed-target diagnostic and share its numeric report, even if calibration fails.
5. Then check a wink/blink, click, scroll bar and tutorial return path separately. Automated regression tests cannot replace this physical interaction check.

The APK uses the same package and signing identity as v29 so it is an upgrade, not a side-by-side app. Android normally rejects reinstalling an older versionCode over v30; preserve the v29 APK but do not uninstall or clear data just to compare without considering saved settings.

## Limitations

Real-device accuracy is unverified. Eye shape, landmark quality, occlusion, yaw/pitch and pose changes can still affect the result. The landmark mapping retains the previous pose/scale validity checks. A better result would support this alternative, but is not a controlled causal isolation of iTracker because the calibration input and sample handling also differ.

## Recorded verification (2026-11-22)

- 130 JVM tests passed; zero failures/errors/skips. Includes four new comparison tests for eye-local geometry, separate-eye calibration at an unseen synthetic target, safe rejection of large pose changes, and wink sample exclusion.
- Final debug build and lint completed successfully (`build-mediapipe-v30-final.log`). Existing warnings remain.
- APK signature verified, versionCode 30, arm64-v8a. Package inspection confirms the MediaPipe model is present and iTracker assets/OpenCV libraries are absent.
- No ADB device connected; installation, camera operation and real-world accuracy remain for the phone comparison.
