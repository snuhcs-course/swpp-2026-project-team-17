# Texting personalization — v40

Branch: gaze-response-fix. Version: 40 / 0.15.3-personal-completions.

## Behavior
The relay requests five or six distinct complete alternatives, with a maximum of six; fewer are allowed when uncertain. The app displays word choices in two columns, up to three rows. Compatible cached choices can appear immediately. Older APKs still truncate the response to their own candidate limit.

The app counts words/expressions only after the editor confirms successful insertion. Hovering, rejected writes and failed writes do not train preferences. Up to two matching learned expressions can appear before a network response; frequency also ranks the returned choices, with six total slots. Prefix and editor-version checks still apply. Append-only sentence continuations are not saved.

This is local frequency-based personalization, not retraining the LLM. It stores at most 100 chosen expressions and their counts in private notouch_writing preferences, survives app restarts, and excludes these preferences from cloud backup and device transfer. It does not store the surrounding draft or upload the preference table. Prediction requests still send the current text under the existing consent flow. The settings button “학습한 추천 초기화” clears learned frequencies.

Existing v38 gaze mapping, candidate hold/freeze, native-editor write verification and signed v2 authentication remain in place. No provider API key is embedded in the APK.

## Validation
- Android unit tests: 193 passed, zero failures/errors/skips.
- Relay tests: 21 passed, zero failures.
- Debug assembly and lint succeeded; lint: zero errors, 46 warnings.
- APK signature verified (v2); package dev.gazedot, version 40, arm64-v8a.
- Production signed /v2/predictions request for synthetic input “도”: HTTP 200, six full alternatives, empty continuation, 3065 ms for this single request. Replaying the same request returned 409 as intended. This is not a latency benchmark.
- No physical phone was connected; dwell usability and panel fit must still be checked on the S23.

## Artifact
- Filename: NoTouch-v40-S23-personal-completions-debug.apk
- SHA-256: 9e9b73647a44974620fcfb5dc3e0992c2d71ae220cbda7eb582f677bd00fbbe0
- Size: 23,416,542 bytes. Debug-signed S23 test build, not a store release.
- [Google Drive download](https://drive.google.com/file/d/1BUnKhbUFF9Jpt78GZK49dK6_z3jfA2dZ/view)

