# v20 calibration
- Calibration accepts timestamped open-eye CNN features up to 750 ms old in a path that never dispatches actions. Live gaze retains its 250 ms safety limit.
- Brief missing frames no longer erase every calibration sample; long loss clears collected samples.
- Calibration reports eye state and inference status instead of calling every failure eye detection loss.
- Pose changes restart the current target's fixation window at the new pose. Each target has its own reference; users are not required to return to the first position. Movement checks remain active during sampling.
- Iris registration remains mandatory and unchanged.

Validation: JVM regression suite, debug build and lint. No connected physical device; eye recognition and accuracy after changing head position remain unverified. Permitting a new stable pose does not establish head-motion-invariant accuracy.
