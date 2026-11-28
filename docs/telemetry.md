# NoTouch telemetry

Based on Tutorial 09, pages 16–29: Firebase Analytics custom events and DebugView.
This is observation only; it does not alter calibration, prediction, smoothing or dwell decisions.

## Setup

Team project: `notouch-team-17`, Android package: `dev.gazedot`.
Console: https://console.firebase.google.com/project/notouch-team-17/overview
The configuration is installed on the maintainer's machine. It is intentionally
ignored by Git: teammates must also download/copy `app/google-services.json`
before building a telemetry-connected APK. Pulling main alone is not sufficient.

1. Create a team Firebase project with Google Analytics enabled.
2. Register the exact Android package `dev.gazedot`.
3. Download `google-services.json` to `app/google-services.json` (ignored by Git).
4. Run `./gradlew :app:assembleDebug`. A build without the JSON is allowed, but does **not** upload telemetry.
5. In the existing developer settings, enable **테스트 사용 기록 수집 (Firebase)** and accept the disclosure.
6. Enable DebugView on the test device:

```sh
adb shell setprop debug.firebase.analytics.app dev.gazedot
```

Open Firebase Console → Analytics → DebugView. Start tracking, calibrate, click and scroll.
Disable debug mode after testing:

```sh
adb shell setprop debug.firebase.analytics.app .none.
```

No OpenAI API key or prediction-server change is involved. Firebase configuration presence alone does not prove server receipt; verify a real event in DebugView.

## Events

| Event | Meaning |
| --- | --- |
| `telemetry_enabled` | Explicit opt-in in settings |
| `tracking_start`, `tracking_stop` | Camera tracking service lifecycle |
| `gaze_sample` | At most one delivered sample/second: normalized displayed x/y, pre-filter mapped raw_x/raw_y when available, eye state, quality, phase, latency |
| `gaze_window` | Delivered frames per 10-second window, open/stale/missing-point counts, p95 processing/delivery latency, maximum capture timestamp gap, successive point jumps >0.15 normalized units |
| `tracking_wait` | Watchdog entered waiting state |
| `frame_error` | Exception class only, never exception message or image |
| `calibration_start`, `calibration_review`, `calibration_apply` | Calibration stages, fit availability/internal validation RMS/application result |
| `control_result` | Click/scroll requested, blocked, rejected, completed or cancelled; direction and duration |

Each event has app_build, trace_session (random per process/opt-in), sequence, and monotonic elapsed_ms. Firebase also supplies its standard device/app metadata. No user ID is set.

`completed` means Android finished dispatching a gesture, **not** that the user selected their intended target. No arbitrary other-app UI taps are monitored. Text-entry contents, labels, app package names, camera images, audio and biometric landmark features are not sent.

## Analysis and limitations

- Gaze coordinates are **estimates**, not known actual fixation targets. No accuracy percentage is inferred from normal use.
- Large jump counts include intentional saccades. Missing points may be expected during calibration. Analyze `phase` and eye state; these metrics alone do not diagnose a bug.
- `internal_rms` is existing same-fixation calibration validation, not independent width-normalized target accuracy.
- `gaze_window` measures frames delivered to the main thread, not every camera frame. A watchdog event catches stalls with no delivered sample.
- Compare app_build, eye/quality, raw vs displayed coordinates and latency to investigate freezes followed by jumps.
- Snapshot sampling does not retain every transition. Existing six-second GazeTrace diagnostics remain available for higher-resolution investigation.
- Local fallback keeps only the latest 300 events in memory; use **최근 테스트 기록 공유** before closing the process. This is not durable storage. Firebase upload/report display may be delayed and requires connectivity/configuration.
- Collection is off by default. Turning it off stops future collection and clears local events/SDK local analytics state; it does not delete events already uploaded to the team's Analytics property.
- No advertising ID or automatic screen-name collection is requested. Update the team's store/privacy disclosure before a public telemetry-enabled release.

## Real-device acceptance checklist (pending)

Local verification (2026-11-28): `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
passed with the team's Firebase Android configuration: 212 tests / 0 failures / 0 errors,
lint 0 errors. v62 (`0.16.2-telemetry`) is an arm64 debug APK with the existing signing
certificate. Generated Firebase project/app resources match the supplied configuration;
the packaged manifest keeps Analytics collection off by default. Actual DebugView
receipt and device overhead are not yet tested (no device connected).
Firebase BoM is pinned to 33.16.0 for the existing Kotlin 2.1 compiler; Fragment
1.8.6 prevents Analytics' transitive dependency from breaking Activity Result compatibility.

1. Fresh install: no custom telemetry before opt-in.
2. Opt in; confirm `telemetry_enabled` and matching app_build in DebugView.
3. Start camera, calibrate, run 20 seconds: check gaze_sample/window and calibration stages.
4. Execute click and all four scroll directions: inspect requested and terminal callback separately.
5. Briefly close eyes/cover camera: inspect eye/quality and waiting events; do not label this accuracy failure.
6. Disable collection; perform actions: no new custom events locally or remotely (previously queued events may appear later).
7. Missing config/offline: app must keep operating; share bounded local trace, and never claim remote receipt.

Sources: https://firebase.google.com/docs/android/setup and https://firebase.google.com/docs/analytics/android/configure-data-collection
