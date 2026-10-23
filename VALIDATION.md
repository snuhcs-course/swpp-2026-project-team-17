# 홈·시선 통합 검증 — 2026-10-23

통합 브랜치: `home-gaze-integration`. 시작점은 `origin/feature/home-screen` (`30be1cf`), 병합 대상은 정확히 `origin/codex/gpt54-gaze-context` (`a4fa46c`)이다. `gazecapture-experiment`나 로컬 `codex/gpt54-gaze-content`는 통합 소스로 사용하지 않았다.

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline`: **BUILD SUCCESSFUL**. 초기 실행에서 원래 선언된 ONNX Runtime 1.20.0 의존성을 받은 뒤 최종 검증은 오프라인으로 통과했다. 의존성 버전 변경 없음.
- Android JVM 단위 테스트: **64개, 실패·오류 0개**. 양쪽 브랜치의 기존 테스트 46개와 새 통합 테스트 18개를 포함한다.
- 새 테스트: 안정화 후 좌표·원래 표본 시간·품질 전달, 원시/클릭 고정/GPT 후보 좌표와의 구분, 화면 밖 좌표 보존, 표시줄·뷰 오프셋 좌표 변환, 1초 응시 선택, 무효·오래된·미래·누락 표본, 눈 감김/불확실/소실 시 응시 초기화, 예측이 거절된 자세에서도 보정용 특징 수집, 보정 성공·실패·취소·중단·저장 실패·늦은 콜백의 모델 보존을 확인한다.
- Android lint: **오류 0개, 경고 37개**. 경고는 주로 문자열 지역화, Kotlin API 권장, 기존 SDK/의존성 안내이며, 실패를 판별하는 보정 저장은 동기 저장을 사용한다. 이 작업에서 의존성을 업그레이드하거나 기존 경고 전반을 정리하지 않았다.
- `node --test server/core.test.mjs`: 기존 서버 테스트 **8개 통과**. 모의 요청으로 인증·요청 제한·오류 처리를 확인하며 실제 GPT 호출은 수행하지 않는다.
- 별도 임시 Python 환경에서 `python -m unittest discover -s training -v`: 기존 학습 테스트 **5개 통과**. 저장소의 의존성 파일은 변경하지 않았다.
- 최종 디버그 APK의 `apksigner verify --verbose`: **APK v2 서명 검증 통과**.
- APK 자산 확인: MediaPipe 모델, 기존의 비활성 MobileGaze 모델·고지, Geologica 폰트·라이선스 포함. GazeCapture/itracker 자산 없음. `PretrainedGaze` 인스턴스 생성과 `cloud.gaze()`의 라이브 포인터 적용 없음.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (81,019,419 bytes). 생성 파일은 커밋하지 않는다.
- `adb devices -l`: **연결된 기기 없음**. 설치, 전면 카메라 시작·실제 검출, 실사용 9점 보정과 중간 휴식, 튜토리얼 선택, 접근성 제스처·받아쓰기, 다른 앱 위 포인터는 실기기에서 검증하지 못했다.
- 실제 GPT API 검증: **미수행**. 서버 URL과 NoTouch 접속 토큰이 제공되지 않았다. 빌드·자동 테스트는 API 자격 증명 없이 수행했으며 실제 자격 증명을 출력하거나 커밋하지 않았다.

자동 테스트는 수치·상태 전달을 검증할 뿐 실제 시선 정확도, 카메라 성능, 제조사별 권한/백그라운드 동작을 입증하지 않는다. 저장 모델은 기존과 같이 기기·전체 화면 크기·회전·특징 형식이 맞을 때 재사용한다. API 26–29의 좌표 계산도 전체 디스플레이 크기로 통일했으므로, 이전의 창 내부 크기로 저장된 모델은 크기 키가 다르면 기존 검증 규칙에 의해 사용하지 않는다. 저장값 자체는 삭제하지 않는다.

실기기 후속 확인: 마이크 거부·GPT 미설정 상태의 로컬 추적 → 저장 모델 재사용 → 보정 네 번째 목표 뒤 휴식 → 보정 완료·튜토리얼 1초 응시(접근성 비활성) → 무효/누락 시 응시 취소 → 회전/화면 꺼짐/보정 취소 뒤 모델 보존 → 접근성 활성 후 연습 화면·다른 앱의 포인터·기존 클릭/스크롤/받아쓰기.

---

# 검증 기록 — 2026-09-16

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: BUILD SUCCESSFUL.
- JVM 단위 테스트: 3개, 실패 0개. 보정 후 미관측 좌표 예측, 무효 보정 거부, 위치 변화 거부, 중앙값 이상값 처리를 확인.
- Android lint: 오류 0개, 경고 8개. 경고는 의존성 새 버전, 백업 설정, Kotlin 편의 API, 문자열 지역화 안내.
- `apksigner verify --verbose`: APK v2 서명 검증 통과.
- 최종 APK 권한 확인: CAMERA, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, POST_NOTIFICATIONS, ACCESS_NETWORK_STATE 및 앱 내부 수신기 권한. INTERNET 권한 없음.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (23,184,338 bytes).
- `adb devices -l`: 연결된 기기 없음. **실기기 설치, 카메라 추론, 다른 앱 위 표시, 실제 시선 정확도는 아직 검증하지 않음.** README의 실기기 확인 항목으로 후속 테스트 필요.

Windows의 긴 임시 경로 때문에 Gradle JVM의 Unix-domain loopback 생성이 실패하면, 빌드 세션의 `JAVA_TOOL_OPTIONS`에 `-Djdk.net.unixdomain.tmpdir=<짧은 기존 임시 폴더 경로>`를 설정할 수 있다. 검증 당시 이 방법을 사용했으며 시스템 전역 설정은 변경하지 않았다.
