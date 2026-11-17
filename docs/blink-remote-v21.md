# v21 tutorial navigation and blink selection
Tutorial has a persistent skip button. Completion stays visible until the main-screen button is pressed. Neither button skips iris measurement or screen calibration.
Selection presents up to four frozen candidates plus cancel. Short bilateral closures (80–600 ms) advance cyclically; a bilateral closure of 1000–2500 ms confirms on reopening. The highlighted row is the selected candidate. Touch selection remains supported. Timeout is 60 seconds. Winks, unknown eye states, stale frames and prolonged sampling gaps cannot confirm.
While the selection panel owns input, iTracker inference is omitted and the existing action engine is disabled. Eye states come from MediaPipe. This keeps selection independent of gaze-coordinate accuracy; it is not a general live-gaze latency fix.
Validation: 117 JVM tests passed, debug build/lint/signature verification passed. No physical phone validation. Existing live-gaze flicker and inaccurate estimates remain unresolved; this APK is for testing the requested interface changes.
Includes the preceding v20 calibration timing and per-target stable-pose adjustments; see calibration-v20.md.
