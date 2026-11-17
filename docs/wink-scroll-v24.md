# v24 wink and scroll-mode regression

Root cause: GazePipeline translated OUT_OF_RANGE (coordinate conversion failure) into EyeState.LOST. InteractionEngine consequently reset the selected scroll mode. The pipeline now keeps observed eye state separate from coordinate availability. No coordinates means no swipe; an existing scroll mode remains selected. Actual face loss still resets interaction.

Wink detector accepted at most 250 ms between samples while live tracking/actions now accept 500 ms. Wink continuity now permits up to 500 ms. Sparse samples suppress rapid double-blink detection for one second; they do not invent a double blink. Wink duration limits remain 100–800 ms, and both eyes must reopen before toggling.

Regression scenario: 300 ms sample spacing, wink on, coordinate rejection, no action while invalid, recovery without another wink, true face loss resets mode. Retains v23 500 ms action budget and ambiguity-only suggestions.

No connected-device test: this verifies logic with synthetic observations, not real-camera wink classification or gaze accuracy.
