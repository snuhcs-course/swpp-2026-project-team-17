# v25 edge-scroll bars

Runtime and tutorial use top/bottom gaze bars instead of wink-based scroll mode. The legacy controller remains available for regression coverage, but the app service explicitly enables bars.

- Gaze x: 10–90% of screen. Upper band y: 4–14%; lower: 86–96%.
- Reveal after 300 ms; additional 500 ms hold starts a swipe. Repeat interval 1100 ms while held.
- Visible strip is 6 dp, drawn at y=9%/91%, with wider gaze hit bands. Existing overlay fits system bars and passes physical touches through.
- Reserved gaze bands suppress dwell clicks/candidate suggestions, including before the strip appears.
- Leaving the band stops new swipes immediately and hides the strip after 500 ms. A swipe already dispatched can finish.
- Invalid/stale/closed-eye input resets bar engagement. Iris registration, 500 ms action age, text entry and blink candidate selection remain.
- Tutorial teaches the bars and completes after actual movement up and down. Duplicate UI polls do not reset its controller.

Cannot resize other applications; bars temporarily overlay them. Device usability and gaze accuracy remain unverified without a phone.

## Reviewed branch: feature/retargeting
Commit 5d7b91b adds a post-calibration 5-target check to an older 9-point landmark pipeline. Each target waits 900 ms, collects at least 12 samples for at least 2 seconds, times out after 8 seconds. It calculates coordinate medians and median absolute deviation. With at least three valid targets it shifts the entire calibrated output by negative mean x/y bias, capped at 8% of each screen dimension. This is translation correction, not model retraining, scale/rotation correction or semantic button targeting. It reports pre-correction measurements without a separate post-correction validation pass. Reviewed only; not merged into the current iTracker branch.
