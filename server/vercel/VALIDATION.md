# Public texting authentication validation — 2026-11-22

Base: course/feature/texting 4fbc3ac (latest scrolling fix retained). Changes are limited to prediction connection, build URL/version, server and documentation. main/gaze pipeline is unchanged.

- Node: `node --test server/vercel/*.test.mjs` — 18 passed, 0 failed.
- Android: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:bundleRelease -PtargetAbi=arm64-v8a --offline --no-daemon --console=plain` — BUILD SUCCESSFUL. 101 unit tests, 0 failures/errors/skips. Debug lint: 0 errors, 30 warnings.
- Independent JVM SHA256withECDSA/SPKI proof verified by Node's verifyProof: PASS. Android canonical UTF-8 and byte sensitivity unit tests: PASS.
- Production deployed to https://notouch-server.vercel.app, deployment dpl_HqyxkJrP9xZZb7io6ChgG5SgY2Gw.
- Live smoke: /v2/config 200; unsigned POST 401; modified signed body 401; signed prediction 200 with version 1; exact replay 409; legacy /v1/config 200. Valid test used synthetic greeting only and made one real provider call.
- Tests cover stale/future clocks, wrong EC curve, malformed/oversized request, stale consent, replay/quotas/Redis outage and preservation of v1 authentication.
- APK: dev.gazedot, versionCode 31, versionName 0.1.31, arm64-v8a. apksigner v2 verification passed. Compiled DEX includes production URL and /v2/predictions; provider secret pattern scan found none.
- APK SHA256: 7237bc482581d1dfad1e2f33e67d779c5fdf7a7107b262dc3672c37afb5260ff (23,318,130 bytes).
- APK uploaded and metadata verified in the existing 소개원실 Drive folder: https://drive.google.com/file/d/1wQenWduVxxvOWw0sHXbrLqyagx9eDbbl/view?usp=drivesdk

## Verification limits

No physical Android device was attached. Real Android Keystore creation and end-to-end UI interaction on S23 still need a device test. JVM interoperation and actual server tests do not replace that test. Anonymous proof does not attest an official app; global limits bound call count but can be exhausted by abuse. Current quotas are conservative and not a promise of large-scale availability.

APK is debug-signed for testing. Release AAB builds successfully and is unsigned (no META-INF signature entries); use the team's existing release/upload signing key and verify store versionCode before submission. Store publication/review was not performed.
