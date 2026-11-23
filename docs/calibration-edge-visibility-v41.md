# Edge calibration visibility — v41

The v40 code could reject narrowed but visible eyes at edge targets, and a single missing sample erased the entire current fixation. These are code-level failure paths, not a confirmed diagnosis from a recorded phone trace.

## Changes
- With a personal open-eye baseline and available, low MediaPipe blink scores (both <=0.20), both eyes at least 50% of their baseline opening can supply landmarks. Previously the OPEN threshold was about 68%. The geometric visibility floor and actual closure/wink handling remain. Without blink evidence or a personal baseline the conservative classifier remains.
- A missing frame/blink pauses calibration; existing samples survive a gap up to 750 ms. Reopening waits 150 ms before accepting new frames. Missing frames are never invented or added to the training set. Longer gaps still reset the fixation.
- Progress during missing frames reports the number of retained samples. Nine targets at 8–92%, 22 valid frames, pose checks and held-out quality checks remain; no calibration stage is skipped.
- The visibility rule is shared by calibration and runtime to avoid accepting a calibration pose that runtime immediately rejects. Actual impact on gaze accuracy and gesture detection must be tested on a phone.
- All v40 texting and preference-learning features remain included.

## Additional requests
- Per-target validation error must be <=0.12 normalized screen units, in addition to existing aggregate limits. One inaccurate corner can no longer hide in a good overall average. The report identifies the worst target. This detects a bad calibration; it does not guarantee accuracy after a user changes posture.
- Scroll strips now sit 4 dp from the top/bottom display edge. Activation regions include the exact edges (top 0–6%, bottom 94–100%); the old 9%/91% interior areas no longer consume clicks. Android may inset the fallback untrusted overlay, in which case drawing remains within the permitted overlay surface. The accessibility overlay uses the full display.
- The prediction prompt now follows the unfinished token language, including English and mixed-language drafts. Client prefix/cache matching ignores letter case while retaining Hangul decomposition and earlier-text protection.

## Validation
Intermediate v41: 199 Android tests passed; 22 relay tests passed. English production request returned HTTP 200 with six English words in 1999 ms; replay rejected with 409. No physical phone connected. The final v42 includes quick-start and is validated separately.

## Phone check
Run a fresh nine-point calibration in normal light. Check each corner and bottom edge with natural eye opening, without exaggerated widening. Blink once during a target: progress should pause briefly rather than reset. Close an eye or both eyes: no new samples should be collected. A long tracking loss should reset the current fixation. After calibration, check edge reach, dwell clicks and blink gestures. Report the displayed eye state and retained-sample count if a target still stalls.
