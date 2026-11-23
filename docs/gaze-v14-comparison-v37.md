# v37: restoring v14 mapping and display behavior

## Historical comparison

Verified Git revisions: v12 1506ccc, v14 3ed7b5e (MediaPipe binocular), v15 9b11a18 (head setup), v16 638d08e, v17 c2f7d82 through v19 852f316 (iTracker). The phrase “v1x” does not identify one APK; v14 is the last binocular MediaPipe baseline before the head-setup/iTracker additions, so it is used for this restoration. The repository cannot establish which version the user personally found best.

| Area | v14 | v36 | v37 |
| --- | --- | --- | --- |
| Calibration | 9 targets, one median per target | 5 targets, all early raw frames fitted | 9 targets, one early-frame median per target; late samples still validate noise/error |
| Display | EMA alpha 0.28 per ~66 ms | displacement rejection + representative + variable response | EMA equivalent to v14 at 66 ms, time-normalized at 33 ms |
| Large movement | display follows | can remain at old point until subsequent raw samples corroborate within 0.08 | display always follows; uncorroborated large raw jumps cannot authorize actions |
| Pose rejection | absolute face-position/scale limits | removed in v36 | removal retained |

The old displacement gate could indefinitely hold a cursor when large raw moves failed its corroboration test. Raw-regressor noise can also bias a fitted slope toward a smaller range; median anchors avoid that mechanism. These are code mechanisms, not proof of the cause of a particular real-device recording, which was not available.

No arbitrary sensitivity multiplier or endpoint stretching is added. The existing model regularization and held-out quality checks remain. The user must perform the new single nine-point calibration to get a mapping trained by the restored method. Texting, scroll bars, wink handling, freshness checks, API integration and the v36 UI fixes remain.

## Verification

Synthetic tests compare EMA output exactly with the v14 formula, check jitter damping and stale/invalid action rejection, and replay all four near-corners through nine-point calibration + the complete pipeline at 33/66 ms cadence. Physical camera accuracy/stability still requires an S23 check; these tests do not substitute for it.

## Executed validation (2026-11-23)

- Final `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a --offline --no-daemon`: successful, 184 unit tests, zero failures/errors.
- APK v2 signature verified; dev.gazedot versionCode 37, arm64-v8a.
- SHA256: `336e3d0250e6763c4d2fc5eaf1acdb7fdeb023e5b2de8a12e1d995f61ef17ba1`.
- File: `output/apk/NoTouch-v37-S23-v14-gaze-mapping-debug.apk`.
- Drive: https://drive.google.com/file/d/1im30SM7tSh53BKrkq4bsuVoZUzjNRSQq/view
- No physical S23 verification in this session.
