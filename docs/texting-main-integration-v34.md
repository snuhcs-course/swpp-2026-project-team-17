# Main + texting integration (v34)

Merged feature/texting into the existing main gaze pipeline. Preserved five-point calibration, screen correction, selection assistance and vertical edge scroll bars. Restored text-entry overlays and signed /v2 prediction requests; the provider key remains on the server.

## Stable-button dwell restart

NativeWritingPanel reads gaze both on camera notifications and on its 150 ms heartbeat. TutorialDwell previously reset on sampleAt <= lastSampleAt, so a heartbeat reading the same frame discarded the accumulated fixation. The entry prompt and other local dwell controls shared this helper.

Equal timestamps now do nothing: they neither reset progress nor extend the last-received deadline. Older timestamps, stale/future samples, leaving the target and a stream gap still reset selection. A newly captured sample is required to complete the one-second dwell. Regression tests cover duplicate heartbeat calls, a frozen camera, target exit and single activation.

## Verification

Android unit tests, APK assembly and lint are run after the final changes. Physical-device verification is still required; this change fixes a reproducible state-machine defect, not a measured improvement in gaze accuracy. Test a phrase and a prediction button with a stationary gaze, then look away, pause the camera and try again. Confirm one insertion per fixation and no click through to the keyboard beneath the panel.
