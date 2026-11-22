# Calibration regression investigation (v16)

User reported that v15 head calibration did not recognize movement and gaze accuracy appeared worse. No physical-device trace was available, so the exact on-device cause and accuracy improvement remain unverified.

Code findings and changes:

- Head training used absolute angle limits, including the initial neutral pose. A tilted phone/user could be rejected before a reference was established. Training now measures changes relative to the captured neutral pose, with circular angle differences. Rotation tolerances allow incidental motion in the other axis.
- Previously the head screen displayed a generic instruction for eye loss, out-of-range base predictions and incorrect pose. It now displays live yaw/pitch/roll, translation and iris-scale changes, distinguishes eye/face/base-model rejection, allows 60 seconds per stage, and offers retry after failure. Fresh open-eye data and the existing base-model position/scale safety gate are still required.
- Each new head target has a one-second settling period. Interrupted eye tracking clears the pending sample window.
- Basic nine-point calibration previously checked face translation within each target but not head rotation across targets. It now checks yaw/pitch/roll, translation and relative iris scale against the session's first centre fixation. Eye loss or rejected pose restarts the target sample collection; the old model remains until fitting succeeds.
- Head validation adds a fifth, neutral-centre target to the four rotated targets. A candidate must reduce mean normalized error by .01 and cannot worsen any checked target by more than .005 (previously .03). These are software acceptance thresholds, not measured guarantees of accuracy throughout the screen.

Flow: face/iris setup -> nine gaze targets with head fixed -> thirteen head-training conditions -> five validation conditions. Back skips head training. Failure leaves the previous model unchanged. If head training never completed in v15, its head correction was never installed; the accuracy complaint cannot then be attributed to an applied head model.

Device checks still needed: verify live angles change on S23, identify any rejected stage from its on-screen reason, and compare errors at the same targets/lighting/distance before and after head correction. Synthetic tests do not establish physical gaze accuracy.

Build verification: 101 JVM tests passed (zero failures/errors); debug assembly and lint succeeded. APK v2 signature verified. Artifact: `output/apk/NoTouch-calibration-fix-v16-debug.apk`. SHA-256: `5A0184F1FB3FF3C08D0B6DAB55B5E7B5F8101A382F839C3AB64E0759497872EC`.
