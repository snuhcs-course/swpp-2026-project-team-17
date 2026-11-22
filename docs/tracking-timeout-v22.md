# v22 first-stage timeout experiment

Requested first step: raise the limit before further accuracy work.

- Runtime GazePipeline and service result rejection now allow 750 ms, previously 250 ms.
- Watchdog and tracking-status expiry use 750 ms consistently.
- Coordinate timestamps remain the original analyzer timestamps; no timestamp refresh or synthetic points.
- Actions, local dwell, blink gestures and writing retain existing freshness limits. Results over 250 ms old can be visualized but cannot trigger click, scroll or dictation through the pipeline.
- Home status now displays total processing duration and CNN duration, including a delayed-action notice.
- Iris measurement, calibration, tutorial skip/home buttons and blink selection remain present.

Regression checks cover 400 ms delay with coordinates but no actions, rejection above 750 ms, future/duplicate samples, and face loss, alongside the existing suite.
Physical S23 latency and accuracy are unverified. This is not an accuracy fix or proof of flicker elimination. Preprocessing comparison, held-out target evaluation and time-based filtering remain subsequent work.

References informing the broader investigation:
- https://developer.android.com/media/camera/camerax/analyze
- https://github.com/CSAILVision/GazeCapture
- https://github.com/CSAILVision/GazeCapture/blob/master/code/generateCrops.m
- https://gery.casiez.net/1euro/
