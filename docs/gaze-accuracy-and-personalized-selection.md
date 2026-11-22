# NoTouch: accuracy, personalized target selection, and interaction recovery

Date: 2026-11-16. This is the original design proposal. A bounded residual correction and online personal target ranker are now implemented; see [v11 implementation and usage](personal-models-v11.md). The larger head-aware regression roadmap below is still a proposal.

## Baselines inspected

- Remote `main` and `home-gaze-integration`: `fd387cc`. The recovery branch `gaze-interaction-recovery` starts here. This is not the latest code across all team branches.
- `feature/home-screen`: `e8fa01e`, including accessibility setup and system-navigation click fixes.
- `feature/assisting-interaction`: `6eb4be9`; `GazeAssistDetector` detects repeated unsuccessful fixation using a short gaze history, and `EasyControlPanel` offers assistance. It is rule-based, not a learned personal ranking model.
- `feature/retargeting`: `5d7b91b`; measures multiple targets and applies a bounded global screen offset. Offset correction cannot fix scale compression or pose-dependent error. Do not merge this branch wholesale into main: it also removes/replaces substantial pipeline code.
- Calibration experiments A `91994ea` and B `802a707` include recorded evaluation reports based on the home-screen pipeline, not remote main.

The inspected A final run is explicitly non-official. Its neutral p95 error was 0.4466, higher-position p95 was 0.8403, and the lower block had no valid coordinates. B's single official recorded session had p95 errors 0.3074 / 0.2803 / 0.2784 (neutral / higher / lower), against a 0.20 threshold. Both reports say NOT_MET. The sessions differ and do not establish a causal A/B improvement or population-level result. They support testing pose-sensitive bias/range errors before adding more smoothing.

## Proposed runtime architecture

CameraX -> MediaPipe landmarks -> timestamped eye/head/distance features -> personal gaze regression -> quality gate -> bounded residual correction -> adaptive smoothing -> screen gaze estimate.

In parallel: accessibility node snapshot -> visible enabled target bounds and stable IDs -> candidate generation around the gaze estimate -> personal target ranker -> visible suggestion / explicit confirmation -> existing dwell state machine -> accessibility action -> execution feedback.

Keep raw gaze coordinates separate from suggested target coordinates. Ranking must not overwrite the gaze stream, fabricate accurate calibration results, or drive edge scrolling with a snapped button center. Recheck target identity, window generation and bounds immediately before execution. Cancel stale candidates on window/content changes.

## Accuracy: recommended order

1. Establish the exact production candidate with the team; keep measurements tied to APK hash, display geometry and model version. Check image rotation, mirror convention, inset conversion, timestamps and clipping before model changes.
2. Add a short residual-calibration phase at targets distinct from the initial nine points. Start with a robust constant offset, then compare a regularized affine correction for horizontal/vertical scale and offset. Use sufficient spatially distributed points; do not fit an affine map to one fixation.
3. Validate on a fresh third set or new session. Points used to fit residual correction are training data, not a held-out test. Retain the previous model if error worsens, coverage is inadequate, or correction exceeds a bounded region. Persist correction with the base model/version and orientation; invalidate it when either changes.
4. Train a head-aware ridge regressor on repeated target observations at multiple head positions/distances. Inputs: separate left/right iris coordinates, face center/scale, head yaw/pitch/roll and estimated distance. Distance and pose estimates are noisy; reject invalid values and compare models with/without them. Nine median anchors alone are not enough evidence for many new parameters or a neural network. Main already contains depth-training/import interfaces; evaluate those rather than introducing a second incompatible model store.
5. Split by recording session and participant as appropriate, not adjacent video frames. Compare baseline, offset-only, affine and pose-aware models using target error, p95 error, signed bias, range compression, availability, jitter and latency. Retain unclamped predictions in diagnostics. Strong smoothing can reduce jitter while preserving systematic error and adding delay.

## Personal button-selection learning

Use a small on-device conditional softmax/logistic ranker over the current candidate set. A practical score is `w·features(candidate)`; softmax converts scores into relative candidate probabilities. Initial weights should favor spatial proximity. Candidate-set confidence is not proof of intent: include a none-of-the-above outcome or an explicit abstention rule.

Candidate features: distance from gaze distribution to the button rectangle (normalized by screen dimensions), fraction of recent valid gaze inside the rectangle, fixation duration, variance, size, current enabled state, and decayed frequency/transition history for that app/screen. Use stable resource IDs plus screen identity where available; never reuse an old rectangle as a live target. If the screen exposes no useful accessibility nodes, preserve ordinary gaze control and abstain from learned assistance.

Learning labels must come from explicit confirmation in the assistance panel or deliberate correction in a controlled training screen. A dispatched gesture, dwell event, app launch, or absence of undo is not automatically a correct-intent label. Collect a pre-confirmation gaze window: looking at an enlarged confirmation panel must not be mistaken for looking at the original button.

For an explicitly confirmed candidate, minimize cross-entropy with regularization and bounded learning steps. A corrected choice supplies a positive label for the intended candidate and a negative label for the rejected suggestion. Keep cancellation distinct from negative labels unless intent is known. Begin with offline replay; enable personal updates only after comparison with nearest-target selection. Store versioned local weights and allow reset/rollback.

Low confidence, a small top-two score margin, distant candidates, or stale/invalid gaze should produce no automatic selection. Show a small number of enlarged candidates for confirmation. Freeze candidate identity during confirmation and revalidate before acting. Do not automatically explore by clicking random controls; reinforcement learning is unnecessary for the first version.

Suggested event schema: session/time, model version, screen fingerprint, candidate IDs/bounds, pre-confirmation gaze summary, features/scores, proposed target, explicit confirmed/corrected target, and action callback outcome. Keep raw camera frames and editable text out of the default log. Execution success and user-intent confirmation are separate fields.

Evaluate four conditions on comparable tasks: baseline gaze; gaze correction only; rule-based assistance; learned ranking plus confirmation. Report intended-target success, unintended actions, abstention rate, time, confirmation burden and fatigue. Report raw gaze error separately. Start with shadow ranking and a small controlled practice screen; do not call a model improved merely because training loss falls.

## Recovery implemented in this branch

In remote main, the tutorial processes gaze locally while external clicks/scrolls require `ControlAccessibilityService.instance`. Main did not guide users to enable that service. Also, a paused running tracker disabled the camera-start button and `startTracking()` returned immediately, so the home screen could not resume controls.

Changes: accessibility setup guidance from the main startup path; a home button that can restore missing permission or resume paused controls; external-control readiness messages on the overlay; a direct practice-screen entry. A shared pure readiness evaluator is regression-tested for missing service, tutorial-to-external transition, explicit pause, calibration and voice input. Existing gesture dispatch, calibration and dwell timing remain in place.

The latest home-screen branch already contains part of the permission guidance. Port only missing fixes if that branch is chosen as the production baseline; do not replace team work with this main-based checkout.

Android requires an enabled accessibility service with gesture capability for external gesture dispatch: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,android.accessibilityservice.AccessibilityService.GestureResultCallback,android.os.Handler)

## Physical verification still required

1. With accessibility off, start from home: verify guidance; enable NoTouch and return. With fresh gaze, complete calibration/tutorial, open an external counter/calculator, dwell and verify an actual state change.
2. Wink to enable scroll, look in the intended region, and verify actual list movement; wink off and verify it stops.
3. Pause using the notification; verify no actions occur. Return home, select resume, and repeat the external-app test.
4. Disconnect/re-enable accessibility; verify a clear blocked state and explicit recovery, without silently undoing a deliberate pause.
5. Return to home/settings/calibration: external actions must remain suppressed there. Record tracking readiness and dispatch results separately from pointer movement.

No connected ADB device was available during this work. Code/build results cannot establish that the user's exact installed APK is repaired; test the delivered build and record any remaining failure.
