# Face/iris setup and validated head-motion compensation (v15)

## User flow
Start camera, then calibration. FaceSetupActivity shows a mirrored live face contour over a face-shaped guide. Align face size/position, face forward, and keep eyes naturally open. Thirty or more fresh frames spanning at least 1.8 seconds must pass alignment, blink, diameter and stability checks. Both iris diameters (image pixels and ratio to eye width) and independent open-eye baselines are measured. This does not measure anatomical iris diameter in mm.

Then nine-point binocular calibration runs. HeadMotionActivity requests thirteen conditions while fixating the centre: neutral, opposite yaw rotations, opposite pitch rotations, opposite lateral translations, opposite rolls, opposite vertical translations, nearer and farther. Each needs 18 fresh stable samples. Four separate off-centre targets test unseen yaw/pitch magnitudes. Back skips the stage and retains the basic model.

## MediaPipe outputs
478 face/iris landmarks, both iris-boundary axes, eye corners, eyelids, face outline, face transformation matrix, and eyeBlinkLeft/Right blendshapes. Matrices are decoded column-major. Matrix translation is not presented as measured metric depth. Unrelated mouth/expression categories are not used.

Openness is normalized against each eye's measured baseline; blink scores provide an additional closure veto. Wink rejection, 150 ms reopening delay, and independent-eye regressions remain.

## Head model and paper relation
Reference: Zhang and Yin (2025), Robust Camera-Based Eye-Tracking Method Allowing Head Movements and Its Application in User Experience Research: https://pmc.ncbi.nlm.nih.gov/articles/PMC12734114/

This adapts the separation of eye motion, head pose and reference geometry. It does NOT reproduce the paper's solvePnP pipeline or physical head-pointing equations, and does not claim its reported accuracy. MediaPipe rotation replaces a second pose solver. Eye-corner normalization is already in the binocular predictor.

A six-feature ridge residual uses changes in yaw, pitch, roll, image face x/y and log normalized iris diameter. The latter is a relative-distance cue, not cm. No generic anatomical iris-size assumption or unverified phone DPI is used to claim physical distance. All six feature dimensions must have adequate training range; independent validation checks yaw/pitch at four off-centre targets, not every combined pose.

Validation must reduce mean normalized 2D error by at least .01 and worsen no point by more than .03. Outside 130% of measured feature ranges (with narrow floors), or above .25 per-axis correction, predictions are rejected. Existing face position/scale limits remain. This supports bounded head motion, not unrestricted movement.

No self-training from unlabelled movement. Replacing the head model clears the old precision residual, which can be re-run. Baselines/models remain in process memory; reports persist locally. Face images are not sent to GPT. Text completion remains the only API use.

## Validation scope
Synthetic tests cover measurement stability, unequal eyelid baselines/winks, matrix decoding, held-out improvement and extrapolation rejection. Actual S23 accuracy and UI thresholds require device testing.

Build verification (2026-11-16): 98 JVM tests passed, zero failures/errors; assembleDebug and lintDebug succeeded. APK v2 signature verified. Artifact: output/apk/NoTouch-face-iris-head-v15-debug.apk (23,711,431 bytes), SHA-256 64E50419DFADBCEE69923EC7C6A22D2BF0A2136C09E63E704E8C89174BFDD6C6. No physical-device accuracy result is claimed.
