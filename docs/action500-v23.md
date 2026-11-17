# v23 500 ms actions and ambiguity-only suggestions

Live gaze tracking and action capture-age limit are now 500 ms. Dwell and scroll accept receipt/capture gaps up to 500 ms. Camera timestamps remain intact. Fine-grained blink gesture continuity retains its stricter cutoff to avoid inventing gestures across missing frames. Iris setup and screen-calibration sampling keep their existing separate registration windows.

Selection assistance now bypasses confirmation for a single innermost hit with a 10% relative interior margin. A clickable ancestor containing a child does not count as a competing peer. Overlapping peers, edge hits and misses retain the existing suggestion panel and explicit confirmation learning. Direct hits do not train on guessed labels.

Tutorial navigation and blink-remote confirmation are retained. No physical device was connected: interaction behavior is checked with deterministic unit tests, not a claim of measured phone accuracy.
