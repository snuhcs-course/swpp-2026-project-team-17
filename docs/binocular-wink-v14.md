# Binocular calibration and wink isolation (v14)

Each eye retains its own normalized iris-centre x/y. Nine-point calibration fits separate regularized screen regressions for the left and right eyes, then fuses their screen-space predictions equally. Both fits must pass calibration checks. The existing per-session residual correction applies after fusion.

Closure/uncertain eye landmarks are excluded from coordinate prediction and calibration. During a short closure the previous display point is retained for up to 1.2 seconds, but it is not a valid ScreenGaze and cannot advance dwell, scroll direction, writing, or confirmation selection. Gesture classification still receives the true eye state, so wink toggling remains available. After both eyes reopen, gaze coordinates wait 150 ms before returning through the existing smoother. Face loss, stale frames and long closures clear the held point.

Limitations: no iris-texture registration, contour fitting, or monocular tracking. Openness thresholds remain population-wide; this does not establish independence from eyelid shape or iris size. Existing eye-width normalization and user calibration reduce geometric differences but require physical testing. MediaPipe supplies five iris landmarks per eye; only the centre is used in the current estimator. The result is not a measurement of iris rotation angle.

Use fresh nine-point calibration after installing. Online prediction configuration from v13 is preserved.
`nValidation: 93 JVM tests passed, zero failures/errors/skips; assembleDebug and lintDebug succeeded; APK signature verified. No device measurement yet.
