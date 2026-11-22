# v28 fixation filtering and missing-pointer status

Changes from v27:
- Replace fixed 0.28 interpolation with a three-sample median and time-based smoothing (220 ms fixation time constant, 65 ms relocation time constant).
- A displacement greater than 0.15 normalized screen units must be corroborated by the next sample within 0.08 units before movement is accepted. Unconfirmed samples cannot click or scroll.
- Reset filter state on model change, loss, stale frames and invalid coordinates; preserve the existing bounded blink hold.
- Show a status reason when no point is available, including start, pause and eye-state waiting. Navigation action gating no longer suppresses these explanations. Dedicated calibration/setup/help screens retain their own UI.
- Hide expired points explicitly; keep the existing 500 ms action/tracking limit.

Limits: these are robustness changes, not demonstrated improvements in gaze accuracy. Synthetic tests cover noise attenuation, isolated spikes, sustained relocation, reset and missing-pointer messages. Real S23 fixation, head-motion and interaction testing is still required. This does not replace iTracker, change calibration or integrate retargeting. Texting remains excluded; scroll bars and target assistance remain.
