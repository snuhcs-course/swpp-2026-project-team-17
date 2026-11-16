# NoTouch v11 — implemented personal models

Branch: `home-screen-control-recovery`. Base: merged `feature/home-screen` at `e8fa01e`, with the external-control recovery patch. These features are executable app code, not just the earlier proposal.

## How to use

1. Start the camera, enable NoTouch accessibility and complete the existing nine-point calibration/tutorial.
2. Return to NoTouch home and select **정밀 보정** (also available in settings). Keep the phone/head still and look at the blue targets only. Five points train an extra correction, followed by four different points that check it.
3. Read the before/after error report. A candidate is installed only if it improves the held-out mean error by at least 0.01 normalized units without worsening any held-out target by more than 0.03. Otherwise the existing model is retained. Return by one-second gaze or touch.
4. Outside the setup/tutorial screen, hold gaze to click. If visible accessible buttons are nearby, NoTouch shows up to three candidates ordered by the personal model. Look outside the candidate buttons first, then look at the intended candidate for one second, or touch it. **해당 없음 · 취소** cancels without learning. The confirmation panel expires after 12 seconds.
5. Confirmed choices update the model immediately on the phone. Settings show the confirmation count, allow resetting learned history, and can turn **개인화 버튼 추천·확인 학습** off for baseline comparison. With no suitable accessibility nodes, ordinary coordinate clicking remains available.

## Accuracy implementation

`ResidualCorrection` fits regularized independent horizontal/vertical scale and offset to five median prediction/target pairs. Raw, unclamped base predictions are used for fitting, so clipped coordinates do not hide systematic bias. Correction is applied before the existing output clamp and smoothing. Scale is bounded to 0.65–1.5, intercept to ±0.25, and the applied per-axis displacement to ±0.18 normalized units.

Each target has an 800 ms settling period, at least 12 unique fresh samples and at least 1.8 seconds total. Collection rejects unstable gaze/face samples. Insufficient input times out after eight seconds per target, leaving the previous model unchanged. The camera point is hidden during collection to avoid chasing the prediction.

The four verification positions differ from the fitting positions. The acceptance comparison uses the clamped base/candidate projections of the same held-out feature samples. It does not measure the final smoothed live pointer after applying the model, nor constitute an independent later-session benchmark. The report uses normalized Euclidean screen error, not angular error or percent screen diagonal.

Correction is attached to the exact in-memory base GazeModel, resets with a new nine-point fit, and can be removed in settings. Home-screen does not persist its base model; storing a residual by itself across restarts would risk applying it to the wrong calibration. The most recent report and raw/target anchor pairs are recorded locally (`last-accuracy-check.json`) for diagnosis, without camera images.

This addresses offset/range mismatch. It does not implement new head-pose/distance regression, guarantee phone-camera accuracy, or remove the baseline model's pose rejection limits.

## Button model implementation

`PersonalTargetRanker` is a four-feature conditional softmax model with online gradient descent on cross-entropy plus L2 regularization. Features are distance to the candidate rectangle, decayed personal selection frequency, previous-selection transition frequency, and target size. Initial weights favor proximity. Explicit confirmation updates weights and bounded history (at most 256 hashed counters). Weights/history persist in app-private SharedPreferences and can be reset. Transient previous-choice state resets when the model is restored; frequency/transition statistics persist.

Candidate generation reads up to 350 visible accessibility nodes, excludes password subtrees, does not read editable text, and selects up to three spatially nearest clickable controls within a normalized radius of 0.16. Labels appear only in the temporary panel. Stored keys are hashes of screen/target identities; coordinates and labels are not sent to a server. No GPT/API key, downloaded training dataset, or preinstalled Samsung AI is required.

Features are frozen from the original pre-panel gaze position. Looking at the confirmation panel does not become a new original-target coordinate label. A choice trains the model only after explicit user confirmation and current screen/window/target/label validation. Gesture acceptance is reported separately from learning: an accepted dispatch is not proof of the application's resulting state. Rejected, expired, cancelled or stale choices do not train.

The ranker changes candidate order, not raw gaze coordinates, scrolling coordinates, or automatic execution confidence. It does not auto-click the most probable candidate. Confirmation trades speed for reliable training labels; turn it off to compare ordinary clicking. Repeated consciously confirmed mistakes can still teach the wrong preference, and new screens start with little personal evidence.

## Verification scope and device checklist

Unit coverage includes held-out correction improvement/rejection, bounded correction, insufficient/invalid training data, corrected base-model inference and removal, normalized softmax probabilities, explicit-feedback learning and persistence, invalid-state restore, invalid-label rejection, and stale candidate/window/label invalidation. Existing gaze/gesture tests are also run.

Physical overlay interaction is not covered by JVM tests. On the phone, verify:

- target collection, cancel/background/timeout behavior and improvement-versus-rejection report;
- new nine-point calibration removes the old correction;
- external-app candidate confirmation works with gaze and touch;
- cancellation and a changed underlying screen do not click or increase the confirmation count;
- confirmed selections increase the count and affect later candidate ordering, including after app restart;
- pausing/stopping closes the panel; scrolling still uses the unsnapped gaze position;
- disabling/resetting personalization works and ordinary click behavior remains available.

No measured accuracy gain or real-user selection-speed gain is claimed until these are tested on the device. Exported prior test results from other builds do not establish v11's accuracy.

## Build evidence

2026-11-16: 64 JVM tests passed, zero failures/errors/skips. APK assembly succeeded; lint completed with zero errors and 37 warnings. APK v2 signature verified. Evidence: `docs/evidence/personal-v11/`. No ADB device was connected.

APK: `output/apk/NoTouch-home-screen-personal-v11-debug.apk`; versionCode 11; SHA-256 `CBD410C94FBEEDDB718E50B9ED82F1D2D0B0703ADFCFB58997B7E9B88FD8BFBA`.
