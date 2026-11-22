# v18: fix released camera bitmap passed to iTracker

The v17 frame pipeline closed its MediaPipe MPImage immediately after face detection, then passed the wrapped Bitmap to iTracker/OpenCV. MediaPipe tasks-core 0.10.26 BitmapImageContainer.close() calls Bitmap.recycle(). This ownership behavior was verified by javap on the actual dependency cached for this build, not inferred from current documentation alone. Consequently, successful face detection could lead directly to accessing recycled bitmap pixels. Restarting recreates the same failing sequence.

The MPImage now stays alive through landmark extraction and iTracker preprocessing/inference. Cleanup runs in the outer finally block after both consumers finish; bitmap cleanup checks isRecycled and ImageProxy.close() runs in its own finally even if earlier cleanup fails. The queued main-thread result contains values only, not image references.

iTracker explicitly rejects released bitmaps. Other processing exceptions now record their stage, exception class and bounded error message in logcat and local preferences. Settings -> 최근 영상 처리 오류 displays that record. The application still stops unsafe processing; this change does not hide exceptions or substitute landmark-only gaze predictions.

An Android instrumentation regression test documents the early-close behavior and runs the packaged iTracker engine on a synthetic bitmap while its MediaPipe wrapper remains alive. Its scope is resource lifetime and actual preprocessing/inference, not human gaze accuracy. No phone is connected in this environment, so device execution remains pending.

The five-point screen mapping and removal of mandatory head-motion training remain unchanged.

Validation: 105 JVM tests passed, debug APK assembly and lint succeeded. APK v2 signature verified. Android regression test APK is built separately; the two device checks have NOT been executed without a connected phone. After installing both app and test APKs, run `adb shell am instrument -w dev.gazedot.test/dev.gazedot.FrameLifetimeInstrumentation`.

Artifact: `output/apk/NoTouch-iTracker-v18-frame-fix-debug.apk`.
SHA-256: `865f2a5751759b579f6bff33f93fd10020b704cc0beddea8e197f7277dcee5bb`.
