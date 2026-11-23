# v33 gaze response fixes — 2026-11-23

Base: course/main 57a9c18. Branch: gaze-response-fix. No feature/texting or v32 face-registration-removal merge is included.

## Diagnosed behavior and changes

1. TrackingService rejected frames within 66ms of the last analyzed frame, limiting analysis to about 15Hz even on a faster device. The interval is now 33ms (up to about 30Hz). CameraX still keeps only the latest image and uses a single analyzer worker; actual FPS depends on device inference speed.
2. Every analysis result was posted to the UI queue. LatestFrameDelivery now coalesces consecutive open-eye results so a busy UI does not replay an avoidable backlog. Closed/uncertain states and the first subsequent open frame are retained at original timestamps to preserve blink/wink duration. Existing 500ms staleness/action guards are unchanged.
3. Independent x/y medians could form a target point never observed in the input (an axis-only detour on diagonal motion). The existing three-sample history now selects an actual 2D representative. Single large jumps still need corroboration. Corroborated moves use a bounded 150ms fast-follow interval with 40ms smoothing time constant; ordinary fixation uses 100ms instead of the former 220ms slow tail. No additional stacked filter or extrapolation was introduced.
4. Main's new post-calibration check sampled the filtered display coordinate using wall-clock settle time. It now samples the model prediction and requires the capture timestamp to be after the target's settling interval. Median aggregation, correction bounds and the rest of the main verification screen remain. Instructions explicitly ask users to look at the target rather than chase the red cursor.

## Verification

- Final command: gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a --offline --no-daemon --console=plain. BUILD SUCCESSFUL.
- 139 JVM tests passed, zero failures/errors/skips. Deterministic replay covers a small repeated step reaching within 0.5% of the screen target by 200ms at 15Hz, diagonal geometry, sustained motion, stationary noise attenuation, isolated outlier rejection, queued-frame replacement, closure/reopening preservation, and post-settle raw calibration samples. These are algorithm tests, not measured phone end-to-end latency.
- APK v2 signature verified. dev.gazedot, versionCode 33, versionName 0.13.3-gaze-response, arm64-v8a.
- APK SHA256: 5e8fdc7662d90244fefa63782f19fe230665a2afb628a5420dcb8fe20219ab1e; 23,565,659 bytes.
- Drive upload metadata verified: https://drive.google.com/file/d/1VWlLa484eUmRC24bof0Gw7LycDMTO_f4/view?usp=drivesdk
- Physical S23 tracking accuracy, heat/battery impact of the higher frame-rate ceiling, and subjective smoothness have not been tested. This fixes identified processing issues, not all possible landmark/calibration/head-motion error.

## Phone comparison

Use the same phone position, lighting and calibration posture as the latest-main APK. Alternate looking at two nearby targets, then a diagonal pair; compare arrival delay and stationary jitter. Check a single isolated glance does not trigger a click, and confirm existing wink/blink/scroll actions. Recalibrate before comparing because the post-calibration bias sampling changed. Report remaining lag separately from persistent positional offset.

Final lint: 0 errors, 40 warnings.
