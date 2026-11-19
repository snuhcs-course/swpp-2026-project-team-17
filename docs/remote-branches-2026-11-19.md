# Remote branch comparison, 2026-11-19
Compared with gaze-tracking-improvements at 9180830 plus the pending edge-scroll implementation. Source review only, not execution of every branch.

| Remote branch | Distinct functionality absent from current runtime |
| --- | --- |
| feature/retargeting (5d7b91b) | Five post-calibration targets; median coordinates/MAD; global x/y bias correction capped at 8%, requires 3 valid targets. No held-out post-correction validation. |
| feature/assisting-interaction (6eb4be9) | Easy-control overlay with enlarged accessible target cards, detection of repeated unsuccessful fixations, manual launcher, back/refresh/collapse actions and source-window validation. Current learned candidate confirmation is a different implementation. |
| feature/gaze-evaluation-home-screen-iter3 (72411ec) | Formal gaze evaluation, raw logs, analysis scripts and two recorded sessions for the older home-screen candidate. Current AccuracyActivity is not the same evaluation system. Report verdict NOT_MET; one participant/device. |
| experiment/calibration-a-three-pass (91994ea) | Repeat 9 targets three times at one head position; 27 anchors and three-position evaluation. Recorded preliminary results NOT_MET. |
| experiment/calibration-b-three-poses (802a707) | 9 targets at each of neutral/higher/lower head positions; 27 anchors and evaluation. One recorded session, all position blocks NOT_MET on accuracy. Same old linear iris model, not current CNN head compensation. |
| gazecapture-experiment (1f1d7e3) | Alternative PyTorch iTracker to ONNX pipeline, RGB 0–1 and embedded means, 9-point calibration. Current version uses original Caffe/OpenCV checkpoint with 5-point mapping. Not demonstrated to be better. |
| main / home-gaze-integration (fd387cc), codex/gpt54-gaze-context (a4fa46c) | Older experimental architecture with model persistence/import, labelled capture/export/training and cloud gaze/context experiments. Not drop-in additions to current iTracker path. |

feature/texting, feature/recovering-and-error and feature/home-screen are ancestors of the current branch; core writing, recovery and home functionality is already included. feature/gaze-dwell-click is an older dwell-click implementation. feature/gaze-accuracy-evaluation-v0 and feature/evaluation-home-gaze-integration-version are earlier evaluation variants; choose a compatible evaluation port rather than merging every variant.

No other branch was merged as part of the scroll-bar request. Priority to consider: evaluation tooling, then validated residual bias correction, then selectively borrowed easy-control UX.
