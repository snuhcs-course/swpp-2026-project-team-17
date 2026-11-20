# v29: distinguish model-output noise from calibration error

## Confirmed defect
The previous calibration collected >=22 observations per target but discarded the distributions, fit five median anchors and accepted using training-anchor error alone. Intra-fixation variation and temporal drift were not validated. The new regression test constructs noisy fixations whose medians pass the old model but whose full samples fail the new audit.

## Change
At each of five targets retain the last 22 valid samples. Use the first 14 per target for the affine mapping and the later eight for temporal validation. Equal numbers give equal target weights. Ridge regularization scales with row count (0.006*n), preserving the previous 0.03 at five anchors. Acceptance requires worst-target mapped RMS jitter <=0.08, temporal-holdout RMS error <=0.12 and P90 <=0.20 normalized screen units. These are explicit engineering limits, not demonstrated usability guarantees. This is temporal validation at the same targets, NOT held-out spatial validation. Rejecting a candidate retains the prior in-memory calibration as before.

Record raw output range, raw within-target RMS, mapping spectral gain, unclipped mapped jitter and temporal validation error. No additional gaze filter is added or tuned in v29.

## Phone diagnosis without USB
Home > settings/developer > 시선 원인 진단 · 6초 고정 응시. One second settling plus five seconds looking at the stationary center target. Pointer/actions are suppressed during measurement. Share the numeric report using 진단 결과 공유. Can run after a failed calibration to inspect raw output alone. Save last-calibration-audit.json and last-gaze-diagnostic.json in app-private files, and expose the most recent summary via settings. Logs contain numbers, head pose, timing and derived gaze only; no camera images. The JSON explicitly separates raw cm, mapped_unclipped and filtered values. Calibration is not automatically changed by diagnosis.

## Model input audit
Compared with official CSAILVision/GazeCapture revision e4af90da3c4c9068fbce433fa648d1f9bc7a394b: left means subject anatomical left; face grid is row-major; model has separate left/right/face means and mirror:false. Existing Android BGR tensors, distinct means and face-grid flattening did not show a confirmed permutation bug. The official training crops came from Apple detections; the MediaPipe-derived crop geometry remains an unvalidated domain difference. Do not change crop ratios speculatively without paired input/output evidence.

Sources:
- https://github.com/CSAILVision/GazeCapture/blob/e4af90da3c4c9068fbce433fa648d1f9bc7a394b/README.md
- https://github.com/CSAILVision/GazeCapture/blob/e4af90da3c4c9068fbce433fa648d1f9bc7a394b/code/faceGridFromFaceRect.m
- https://github.com/CSAILVision/GazeCapture/blob/e4af90da3c4c9068fbce433fa648d1f9bc7a394b/models/itracker_train_val.prototxt

## Evidence limits
No phone was attached. The model CPU smoke test verifies weight loading, expected fixture output and all four input connections, not gaze accuracy. Synthetic calibration and diagnostic tests are not human accuracy tests. The user's root cause remains unconfirmed until numeric phone recordings are available.
