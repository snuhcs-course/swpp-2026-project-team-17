# v38: boundary clipping bias and calibration coverage

## Code finding

The v37 path clipped each prediction to [0,1] before the EMA. Noise straddling a screen boundary was therefore asymmetric: the outside half was clipped while the inside half survived, pulling the filtered cursor inward. For example, inputs 0.96 and 1.04 became 0.96 and 1.00 before smoothing. Synthetic full-pipeline regression tests now compare this previous path with the correction at all four boundaries.

## Changes

- GazeModel.predict remains clipped for callers that need a direct screen point; predictForFiltering exposes the finite corrected prediction before clipping.
- GazePipeline filters with modest overscan [-0.25,1.25], then clips the final display/action point to [0,1]. Overscan bounds prevent extreme extrapolation from leaving a long filter tail. Both interaction and hit testing use the final bounded point; rawPoint records the unclipped prediction for diagnostics.
- Nine calibration targets now cover 8–92% on each axis rather than 15–85%, bringing actual samples closer to the boundary instead of assuming the central fit extrapolates accurately. No sensitivity multiplier, snapping or new smoothing layer is added.
- v14-derived interpolation, current dwell/click protection, texting and scrolling remain unchanged.

## Verification scope

Tests replay symmetric edge noise, compare inward error with the old clip-before-filter path, bound output during extreme offscreen predictions, confirm recovery to center, and exercise all near-corners through the updated nine-target calibration at 33/66 ms cadence. This identifies a mathematical source of inward bias, not proof that it explains all of the user's device error. No physical phone is attached; remaining anatomical/nonlinear mapping error requires device measurements.

Install v38 and run the new nine-point calibration once for the wider target coverage to take effect.

## Executed validation (2026-11-23)

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a --offline --no-daemon`: successful, 186 tests, zero failures/errors.
- After moving only calibration instruction labels away from the outer targets, final `:app:assembleDebug :app:lintDebug` succeeded.
- APK v2 signature verified; dev.gazedot versionCode 38, arm64-v8a.
- SHA256: `1e0051fd70f0b666484bfcda1cb459321c56a5742721e5ebf16cfeca5b134c00`.
- File: `output/apk/NoTouch-v38-S23-edge-mapping-debug.apk`.
- Drive: https://drive.google.com/file/d/1P7dLTGdVDQo_pnLh6gMzVa49R12ULD3C/view
- Physical S23 edge accuracy remains unverified.
