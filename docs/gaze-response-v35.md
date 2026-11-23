# v35: integrated gaze response and texting

## Included branches

- Main 57a9c18 plus feature/texting 293e2e3, integrated as 156c224.
- Gaze response cb2191a: 33 ms capture scheduling, coalescing pending open-eye results without dropping eye-closure transitions, two-dimensional stabilization and short movement response, raw samples for post-calibration screen offset checks.
- Natural calibration 43d8c7e: no forced face/iris alignment screen; collect a non-blocking open-eye baseline. Keep five-point calibration and its posture-consistency checks.
- Text-entry fix: repeated UI reads of the same camera frame no longer reset dwell or advance its timer. New samples are still required, stale/missing frames and target exit cancel, and a held button fires once.

## Preserved behavior

Texting panel and signed prediction requests, underlying-input exclusion while looking at the panel, accessibility reconnect recovery, vertical edge scroll bars, selection assistance, tutorial skip/home navigation and recalibration recovery are retained. The prediction URL is https://notouch-server.vercel.app; no provider key is packaged in the APK. iTracker is not reintroduced.

## Device checks still required

No physical phone was attached for this integration. Build/unit/lint results do not establish gaze accuracy or real-device latency. Compare fixed-head fixation jitter and target arrival with v30/v33, test five-point calibration and recovery, then open a messaging editor. Select a phrase with a one-second gaze, look away and select again; verify exactly one insertion each time. Stop camera input during dwell and verify it cannot complete. Check scrolling and tutorial exits as well.

The supplied debug APK targets arm64-v8a (S23); store submission still requires the team's release signing/build process.

## Executed validation (2026-11-23)

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a --offline --no-daemon`: successful; 178 unit tests, zero failures/errors.
- `node --test` in server/vercel: 19 tests passed.
- APK signature v2 verified; package dev.gazedot, versionCode 35, arm64-v8a.
- SHA-256: `859c9e2c1c0d0f9f3fa57354f839c0d7b72a3972ae8ed00a8ab6d94e9359532a`.
- APK: `output/apk/NoTouch-v35-S23-gaze-response-integrated-debug.apk`.
- Drive: https://drive.google.com/file/d/1g_Cp8Mtgy5PXC70vjcHpKRPyCV2aW_CX/view
- Physical-device behavior remains unverified, as noted above.
