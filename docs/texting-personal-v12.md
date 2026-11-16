# Texting + personal gaze models v12

Base: course/feature/texting 68340c4. Integrated personal-model changes from 9d3fd6a, preserving texting, recovery and server code.

- Five-point residual fitting and four held-out verification targets; retain previous correction when validation fails.
- On-device confirmed-button ranking updates and persistent history.
- Precision measurement blocks external actions, recovery prompts and writing gaze actions. Writing and candidate selection do not compete for gaze.
- Version code 12; version 0.9.0-texting-personal.

## Validation
90 JVM tests passed, zero failures/errors/skips. Gradle testDebugUnitTest, assembleDebug and lintDebug succeeded. APK signature verified. No phone validation performed.

## API configuration
Existing WritingPredictions and Python server remain unchanged. The local server environment is ignored by Git. The supplied API credential returned HTTP 429, insufficient_quota / credit_balance_exhausted. No successful model response was obtained. Vercel authentication/deployment remains pending. This APK has no prediction server URL configured and therefore does not enable online completion. No provider key is embedded in the APK.

Artifact: output/apk/NoTouch-texting-personal-v12-debug.apk
SHA256: 81F1AE9BBE58B9D30857E5F0B3BA7B2452F777A6F73CDB5505BE3165264D7C8E
