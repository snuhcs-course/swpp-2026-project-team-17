# NoTouch 예측 중계 서버

FastAPI 중계 서버와 Android 클라이언트 연결 코드는 구현되어 있습니다. **AI 제공자·모델은 미정이며 실제 API 키는 입력하지 않았습니다.** 기본 설정으로 유료 API를 호출하지 않습니다. 테스트는 전부 가짜 제공자 응답을 사용합니다.

## 나중에 채울 설정

`server/.env.example`을 `server/.env`로 복사합니다. 이 작업 공간에는 빈 `server/.env`도 만들어 두었습니다. `.env`는 Git에서 제외하며, 각 위치에 미정·미입력 주석이 있습니다.

| 변수 | 용도 |
|---|---|
| `AI_PREDICTION_ENABLED` | 기본 `false`. 나머지 설정이 모두 준비된 뒤 사용 여부 결정 |
| `AI_PROVIDER` | 아직 미정. 현재 구현된 어댑터는 `openai`; 다른 제공자는 어댑터 추가 필요 |
| `AI_MODEL` | 아직 미정. 사용할 API 모델 ID를 나중에 입력. 기본 모델 없음 |
| `AI_API_KEY` | 아직 미입력. 서버에서만 사용하며 APK·문서·예제에 실제 키를 넣지 않음 |
| `AI_API_BASE_URL` | 제공자의 HTTPS API 기본 주소. OpenAI 어댑터는 뒤에 `/responses`를 붙임 |
| `AI_REASONING_EFFORT` | 모델이 지원하는 경우에만 입력. 미정이므로 기본 비움 |

프로세스 환경변수는 `.env`보다 우선합니다. 설정 변경 후 서버를 재시작해야 합니다. 필수 값이 비어 있거나 지원하지 않는 제공자이면 `/v1/config`는 비활성 상태를 반환하고 예측 요청은 503으로 종료합니다.

Android에서는 저장소 루트의 `online-prediction.properties.example`을 `online-prediction.properties`로 복사해 `PREDICTION_SERVER_URL`에 유효한 인증서가 있는 HTTPS 중계 서버 주소만 입력한 뒤 다시 빌드합니다. 이 파일도 Git에서 제외합니다. 모델·제공자 키는 Android 설정에 넣지 않습니다. 빈 주소, 동의 거부, 서버 설정 미완료 상태에서는 온라인 예측 없이 작성합니다.

## 로컬 실행과 테스트

저장소 루트에서 실행합니다(Python 3.10 이상).

```sh
python3 -m venv server/.venv
server/.venv/bin/python -m pip install -r server/requirements.txt
server/.venv/bin/python -m uvicorn server.main:app --host 127.0.0.1 --port 8000 --no-access-log
```

개발 서버는 기본으로 루프백에만 바인딩합니다. 실제 Android 연결에는 신뢰할 수 있는 HTTPS 종단이 필요합니다. 배포·도메인·운영 키 설정은 이 저장소 변경에서 수행하지 않았습니다. 공개 서비스로 운영할 때는 배포 환경의 접근 제어와 공유 요청 제한 저장소를 구성해야 합니다. 현재 제한은 서버 프로세스 하나를 기준으로 합니다.

```sh
server/.venv/bin/python -m unittest discover -s server -p 'test_server.py' -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## 데이터·요청 정책

- `GET /v1/config`: 활성 상태·제공자·모델·동의 정책 ID·보관 안내만 반환합니다. 제공자 호출이 없습니다.
- `POST /v1/predictions`: NoTouch 초안 `text`, 입력 `version`, 동의한 `policy_id`만 허용합니다. 대화 내역·카메라·시선 등 추가 필드는 거부하며 오류에 본문을 되돌려 보내지 않습니다.
- 입력 최대 2,000자, HTTP 본문 최대 16 KiB. 단어 최대 2개, 단어당 최대 80자, 이어쓰기 최대 1개·160자. 단어 범위와 한글 자모 접두어는 앱에서 다시 검증합니다. 이어쓰기는 초안 끝에만 추가합니다.
- 앱은 350ms 지연으로 연속 입력을 묶습니다. 이전 입력 버전 응답은 버리고, 응시·터치 중인 후보는 그대로 유지합니다. 새 입력·종료 시 이전 요청을 취소하고, 5초가 지나면 오류를 안내합니다. 실패·거부·시간 초과 시 본문을 바꾸거나 자동 재시도하지 않습니다.
- 서버 제한: IP당 분당 60회, 세션당 동시 1회, 전체 동시 4회, 프로세스당 하루 1,000회. 클라이언트 세션을 바꾸어도 같은 IP의 분당 제한을 우회할 수 없습니다. 요청 제한의 IP·세션 메타데이터는 메모리에서만 다룹니다.
- 모델·제공자·보관 안내가 바뀌면 정책 ID도 바뀝니다. 기존 동의의 요청은 412로 거부하며 새 제공자에 초안을 보내지 않습니다. 앱에서 안내를 다시 확인해야 합니다.
- 제공자 키는 서버에서만 사용합니다. HTTPS 제공자 연결의 리다이렉트를 따르지 않으며 요청을 재시도하지 않습니다. 입력·후보·예외 본문·요청 헤더는 로그에 남기지 않고 상태·처리 시간·토큰 사용량만 기록합니다. Uvicorn도 위 명령처럼 접근 로그를 끕니다.
- OpenAI 요청은 `store:false`이며 이전 응답 ID나 대화 저장소를 사용하지 않습니다. API 데이터는 기본적으로 학습에 쓰이지 않지만 악용 방지 로그는 기본 최대 30일 보관될 수 있고 정책상 예외가 있습니다. [공식 데이터 정책](https://developers.openai.com/api/docs/guides/your-data)
- 한국어 미완성 자모·단어 후보의 유용성, 실제 응답 시간, 비용은 모델과 키를 정한 뒤 별도로 측정해야 합니다. 서버 단위 테스트는 이 성능을 입증하지 않습니다.
