## v45: simple per-eye mapping and merged horizontal scrolling

Nine targets → one screen mapping per eye → average screen coordinates. Removed an unused third regression that could reject valid per-eye data. Includes fix/horizontal-scrolling. See [changes and validation](docs/simple-binocular-v45.md).

## v44: rebuilt nine-point calibration

Separate acquisition state machine, median anchors, explicit result review and single-point remeasurement. Includes quick-start geometry and four-direction edge scrolling. See [design, references and validation](docs/calibration-rebuild-v44.md).

## v42: optional quick start without calibration

Home offers an explicitly approximate, uncalibrated pointer mode and can restore the previous calibration. Includes edge visibility, edge scroll bars and English predictions. See [behavior and validation](docs/quick-start-v42.md).

## v41: edge-target eye visibility

Calibration preserves samples through short blinks and uses personal eye opening plus available blink evidence for narrowed eyes at edge targets. See [changes and phone checks](docs/calibration-edge-visibility-v41.md). Includes v40 texting personalization.

## v40: six suggestions and local preference learning

Texting offers up to six complete alternatives in two columns and learns the frequency of successfully inserted choices on this device. See [implementation and validation](docs/texting-personalization-v40.md).

## v39: complete texting alternatives

Texting predicts complete alternatives such as 도와줘 / 도와줄게 and reuses compatible predictions while typing. See [implementation and validation](docs/texting-completions-v39.md). Includes the v38 edge-mapping fixes.

## v38: edge calibration and clipping order

Nine-point calibration now samples 8–92% of the screen. Predictions are smoothed before final screen clipping to avoid inward boundary bias. See [implementation and validation](docs/edge-mapping-v38.md). Run the new calibration after installing.

## v37: v14-based screen mapping and continuous pointer

Restores nine-point median-anchor calibration and v14's 0.28 display interpolation (time-normalized). Large displacement no longer locks the pointer; click protection and v36 fixes remain. See [historical comparison and verification](docs/gaze-v14-comparison-v37.md). Run the new nine-point calibration after installing.

## v36 gaze continuity regression fix

The latest gaze-response-fix build removes runtime face-position/relative-size rejection, restores the pre-v33 fixation response, separates transient gaze confirmation from coordinate errors, and no longer auto-launches a second calibration. See [changes and limits](docs/gaze-continuity-v36.md). Texting integration remains included.

## v35 integrated gaze-response-fix

Includes main + texting (signed online predictions and duplicate-frame dwell fix), the v33 gaze response/calibration-check fixes, and v32 natural eye baseline without the forced face-alignment screen. See [integration and validation](docs/gaze-response-v35.md).

# NoTouch — integrated main + texting (v34)

Merged feature/texting 293e2e3 into main 57a9c18. Main's MediaPipe binocular mapping, calibration verification, selection assistance and vertical edge-scroll bars remain. Native messaging/IME prediction, per-installation API authentication, accessibility reconnect fixes and scroll-window cancellation fixes are integrated. Tutorial completion badges follow the actual vertical bar controls; wink-only instructions do not replace the bar workflow.

Prediction relay defaults to https://notouch-server.vercel.app. No API key belongs in the APK. See server/vercel/README.md for the deployed v2 protocol and currently removed prediction count limits.

## Prior main reference

# NoTouch — main (MediaPipe-only v30)

Main now integrates `mediapipe-gaze-accuracy` at `4b3c525` with the former main history (`fd387cc`). The active Android application tree is the tested v30 implementation.

- Front-camera capture → MediaPipe face/iris landmarks → separate left/right personal mappings → averaged screen position and stabilization.
- Face/iris setup followed by **five-point calibration** with quality checks. No iTracker inference, weights or OpenCV dependency in this APK.
- Existing dwell interaction, edge scroll bars, tutorial navigation, selection assistance and voice dictation are retained from v30.
- The **texting composer is not included**. `feature/texting` and `feature/retargeting` are separate branches and are not implicitly integrated by this merge.
- Calibration is session-local. Actual phone accuracy remains unverified for v30; automated checks do not establish accuracy.
- See [v30 behavior, comparison procedure and verification](docs/mediapipe-comparison-v30.md).

Open the repository root in Android Studio. Configure the Android SDK locally, then build with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a
```

The existing `server/`, `training/` and earlier project documents are retained for history and separate experiments; their presence does not mean the v30 gaze pipeline invokes them. The Android tree matches v30; obsolete main-only depth/imported-model application modules are superseded by that implementation and remain available in Git history.

## Historical prototype notes (not the current v30 specification)

The text below documents an earlier prototype, including its nine-point workflow and feature limitations. Use the v30 reference above for current behavior.

# GazeDot — 안드로이드 시선 추정 시제품

전면 카메라에서 눈·홍채 위치를 검출하고 **사용자별 9점 보정**을 거쳐 화면의 추정 시선 위치에 빨간 점을 표시합니다. 홈 화면이나 다른 앱으로 이동한 뒤에도 카메라 포그라운드 서비스와 터치가 통과하는 오버레이가 동작합니다.

## 현재 상태

Android Studio에서 저장소 루트 폴더를 열 수 있습니다. APK는 로컬에서 빌드하며 빌드 산출물은 저장소에 포함하지 않습니다.

첫 실기기 사용에서 좌우 시선 이동이 잘 반영되지 않는 문제가 보고되었습니다. 보정값 저장·재사용, 머리 각도·거리 변화 보완은 아직 구현하지 않았습니다. 현재 버전은 매번 시작할 때 9점 보정이 필요한 실험용 시제품입니다.

## 문자 입력 모듈 분리

이 브랜치에서는 texting 작성 화면·저장 문장·한글 자판·온라인 자동완성을 제거했습니다. 해당 기능은 `feature/texting`에서 관리합니다. 기존 음성 받아쓰기와 클릭·스크롤·버튼 선택 보조는 유지합니다.

## 설치와 사용

1. `app/build/outputs/apk/debug/app-debug.apk`를 안드로이드 기기로 옮겨 설치합니다. Android 8.0/API 26 이상, 전면 카메라가 필요합니다. USB 연결 시 `adb install -r app/build/outputs/apk/debug/app-debug.apk`를 사용할 수 있습니다.
2. GazeDot에서 **권한 허용 및 카메라 시작**을 누릅니다. ‘다른 앱 위에 표시’, 카메라, 알림 권한을 허용합니다. 오버레이 설정 화면에서 앱으로 돌아옵니다.
3. 기기를 고정하고 얼굴을 정면에 둡니다. **눈 검출됨** 상태가 되면 **9점 시선 보정**을 누릅니다.
4. 머리는 움직이지 않고 파란 점을 눈으로 따라갑니다. 점마다 약 2.5초 이상, 유효 표본 22개를 모읍니다. 보정은 약 25~40초가 걸릴 수 있습니다.
5. 보정 후 빨간 점을 확인하고 **홈 화면에서 빨간 점 확인**을 누릅니다. 설정 앱 등 일부 화면에서는 안드로이드 보안 정책에 의해 오버레이가 숨겨질 수 있습니다.
6. 앱의 **추적 중지** 또는 알림의 **중지**를 누르면 카메라와 오버레이가 종료됩니다.

앱을 처음 실행했을 때 카메라 권한을 거부했다면 다시 허용해야 합니다. 영구 거부한 경우 시스템 앱 설정에서 허용합니다. 화면 꺼짐, 회전, 화면 크기 변경 시 서비스를 종료하며 앱에서 다시 시작·보정합니다. 시스템에 의해 앱 프로세스가 종료된 경우에도 자동으로 카메라를 켜지 않습니다.

## background process인가?

일상적인 의미로는 앱 화면을 떠난 뒤에도 동작하는 백그라운드 기능이지만, Android 구현은 **camera 유형 Foreground Service**입니다. 앱 화면이 보일 때 사용자가 시작하고, 지속 알림을 표시하면서 카메라를 유지합니다. 빨간 점은 `TYPE_APPLICATION_OVERLAY` 창입니다. 접근성 서비스나 화면 캡처 권한은 사용하지 않습니다. 다른 앱을 클릭하는 기능은 포함하지 않습니다.

## 실제 구현과 한계

- CameraX ImageAnalysis: 전면 카메라, RGBA 프레임, 센서 회전 보정, 최신 프레임만 처리, 추론 최대 약 15fps.
- MediaPipe Face Landmarker: CPU에서 얼굴·눈·홍채 랜드마크 478개 검출.
- 양쪽 눈의 모서리 축을 기준으로 홍채의 상대 수평/수직 변위를 계산. 눈이 감기거나 랜드마크가 없으면 점을 숨김.
- 9개 목표의 표본 중앙값으로 정규화된 ridge 선형 회귀를 학습해 화면 좌표로 변환.
- 보정 시 시선 변화가 너무 작거나 적합 오차가 크면 실패 처리. 얼굴 위치·거리 변화가 큰 경우 점을 숨김. 미세한 고개 회전까지 완전히 보정하는 모델은 아님.
- 화면 좌표에 지수 평활화 적용. 800ms 넘게 결과가 없으면 점을 숨김.
- 전체 화면 오버레이는 터치를 받지 않고 창 불투명도를 0.7로 설정해 Android의 터치 통과 조건을 고려함.

**홍채 검출 모델 자체가 화면 시선 좌표를 출력하는 것은 아닙니다.** 이 앱은 개인 보정 기반의 초기 시선 추정 실험입니다. 작은 버튼을 정확하게 선택할 수 있다는 보장이 없으며 기기, 조명, 안경, 눈 형태, 머리 위치에 영향을 받습니다. 보정 데이터의 적합 오차는 실제 사용 정확도가 아닙니다. 별도의 목표점 검증이 필요합니다.

카메라를 사용하는 다른 앱과 동시에 전면 카메라를 공유하지 못할 수 있습니다. 제조사의 배터리 관리, 카메라 정책, 분할 화면·폴더블 화면 전환에 따라 실기기 확인이 필요합니다.

## 개인정보·네트워크

이 브랜치에는 문자 초안 전송 및 온라인 자동완성 연결이 없습니다. 기존 시스템 받아쓰기의 데이터 처리는 해당 음성 인식 제공자 정책을 따릅니다. 이전 설치에 남은 작성 설정의 백업 제외 규칙은 유지합니다.

## 빌드

Android Studio에서 이 폴더를 열고 SDK 35를 설치합니다. JDK 17 또는 21을 사용합니다. `local.properties`의 `sdk.dir`는 각 PC의 SDK 경로를 사용합니다(버전 관리 제외).

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Gradle 8.13 / AGP 8.9.2 / Kotlin 2.1.20 / CameraX 1.4.2 / MediaPipe Tasks Vision 0.10.26을 고정해 사용합니다. 첫 빌드에는 라이브러리 다운로드를 위한 인터넷이 필요합니다. 기기 내 시선 추정은 오프라인으로 실행됩니다.

## 실기기 확인 항목

- 보정 후 보정에 쓰지 않은 화면 위치를 바라보며 오차·반응 지연을 측정.
- 머리를 고정한 상태에서 눈만 좌우·상하로 이동했을 때 점이 따라오는지 확인.
- 홈/브라우저로 전환하고 2분 동안 점·알림·터치 통과 확인.
- 눈 감기/얼굴 가리기 시 점 숨김, 복귀 시 추적 재개 확인.
- 회전/화면 끄기/중지 버튼 후 카메라 표시와 점이 사라지는지 확인.
- 오버레이/카메라 권한 거부 및 취소, 다른 카메라 앱 실행 시 확인.

## 파일 안내

- `MainActivity.kt`: 권한, 상태 화면, 9점 보정 UI.
- `TrackingService.kt`: 카메라 포그라운드 서비스, 추론, 생명주기·종료.
- `LandmarkFeatures.kt`: 홍채의 눈 내부 상대좌표 추출.
- `GazeMath.kt`: 보정 회귀, 품질·얼굴 위치 검사.
- `DotOverlay.kt`: 다른 앱 위의 빨간 점.
- `GazeMathTest.kt`: 미관측 좌표 예측, 무효 보정·얼굴 이동·이상값 검사.

## 모델 출처

Google MediaPipe Face Landmarker float16 v1:
https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task

SHA-256: `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`

MediaPipe는 Apache-2.0 라이선스이며 배포 모델의 모델 카드와 관련 고지를 함께 확인하세요:
https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker

Android camera foreground service 문서:
https://developer.android.com/develop/background-work/services/fgs/service-types#camera
