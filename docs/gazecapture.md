# GazeCapture / iTracker 도입

공식 GazeCapture 저장소의 **이미 학습된 PyTorch iTracker 체크포인트**를 ONNX로 변환해 기기 안에서 실행합니다. 이 작업에는 데이터셋 다운로드, 모델 학습, 파인튜닝, 서버 호출이 없습니다.

## 적용 흐름

`기존 CameraX 프레임 → 기존 MediaPipe 눈 유효성 검사 → iTracker 추정 → 기존 9점 개인 보정 → 기존 평활화·오버레이·튜토리얼`

- `TrackingService.kt`에서 기존 눈 검출이 유효한 프레임에만 `ITrackerEstimator`를 호출합니다. 얼굴 인식이 없거나 눈을 감으면 추정값을 전달하지 않습니다.
- `ITrackerPreprocessing.kt`가 회전 보정된 미러링 없는 이미지에서 얼굴과 양쪽 눈을 잘라 RGB `[0,1]`, CHW 224×224 입력과 25×25 얼굴 격자를 만듭니다. 왼쪽·오른쪽은 해부학적 기준입니다.
- 학습 때의 RGB 평균 이미지는 ONNX 파일 안에 포함되어 있습니다. 공식 PyTorch 데이터 로더의 격자 위치 오프셋과 검은 패딩을 유지합니다.
- 출력은 **카메라 기준 cm 좌표**입니다. 이 값을 화면 픽셀이나 정규화된 화면 좌표로 바로 사용하지 않습니다. 기존 `EyeFeatures.x/y`를 추정값으로 바꿔 9개 목표점과 대응시키고, 기존 개인 보정이 실제 정규화 화면 좌표를 학습합니다. 얼굴 위치·거리 검사는 유지합니다.
- 기존 4점 → 2초 휴식 → 5점 보정, 보정 성공 후 튜토리얼, 연속 1초 응시, 추적 중지 처리는 유지합니다.
- 초기화·추론 오류는 기존 실패 처리로 전달합니다. 이전 홍채 기반 추정으로 자동 전환하지 않으므로 서로 다른 입력으로 보정한 모델이 섞이지 않습니다.

## 파일과 출처

- 공식 저장소: https://github.com/CSAILVision/GazeCapture
- 고정 커밋: `e4af90da3c4c9068fbce433fa648d1f9bc7a394b`
- 원본: `pytorch/checkpoint.pth.tar`, `ITrackerModel.py`, `mean_*_224.mat`
- 앱 모델: `app/src/main/assets/itracker.onnx`
- 입력 파일·변환 결과 SHA-256 및 수치 검증: `app/src/main/assets/itracker-manifest.json`
- 모델 실행 의존성: `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`

이번 모델은 공식 **PyTorch 재구현** 체크포인트입니다. 논문의 Caffe 모델과 동일한 성능을 주장하지 않습니다. 원본 PyTorch README도 미세 조정·증강을 생략한 재구현이며 성능 보장이 없다고 설명합니다.

## 다시 변환하는 방법

모델 가중치는 원본 라이선스의 재배포 제한 때문에 Git에 포함하지 않습니다. 각 사용자는 원본 조건에 따라 아래 명령으로 공식 저장소에서 받아야 합니다. 현재 작업 폴더에는 이미 모델이 준비되어 있습니다. 새로 clone한 환경에서 모델을 준비하지 않고 빌드하면 안내와 함께 중단합니다.

Python 3.12 환경에서 프로젝트 루트 기준으로 실행합니다.

```sh
python3.12 -m venv .tools/gazecapture-venv
.tools/gazecapture-venv/bin/python -m pip install -r tools/gazecapture-requirements.txt
.tools/gazecapture-venv/bin/python tools/export_gazecapture.py
```

이미 받은 원본 파일만으로 재생성할 때는 마지막 명령에 `--offline`을 붙입니다. 처음 실행할 때만 공식 GitHub에서 필요한 파일을 받습니다. 원본 파일과 가상환경은 `.tools/`에 있으며 버전 관리에서 제외됩니다. 앱 실행 시 인터넷은 필요하지 않습니다.

변환 스크립트는 원본 클래스와 가중치를 엄격하게 대응시킵니다. ONNX 내보내기가 가능한 동등한 LRN 구현으로 교체한 뒤, 교체 전 PyTorch 출력과 ONNX 출력이 4개 테스트 입력에서 `atol=1e-4, rtol=1e-4` 이내인지 검사합니다. 이 검사는 **변환의 수치적 일치**를 확인하며, 실제 사용자의 시선 정확도를 평가하는 검사는 아닙니다.

## 실기기에서 확인할 내용

Android Studio에서 앱을 실행하고 기존과 같이 카메라 시작 → 9점 보정을 진행합니다. 보정에 쓰지 않은 위치와 튜토리얼 버튼을 바라보며 오차, 흔들림, 눈 감기 시 초기화, 추적 중지를 확인해야 합니다.

MediaPipe로 정한 얼굴·눈 잘라내기 영역은 원본의 Apple 검출 영역을 근사한 것입니다. 얼굴은 랜드마크 경계의 긴 변에 10% 여유를 둔 정사각형, 눈은 양 끝점 거리의 두 배 크기 정사각형을 사용합니다. 이 검출 방식 차이와 기종·카메라 위치 차이 때문에 **현재 구현보다 정확해진다고 보장할 수 없습니다**. 목표 사용자와 실제 기기에서 비교가 필요합니다. 모델 실행 시간이 긴 기기에서는 기존 튜토리얼의 250ms 시선 신선도 조건을 만족하지 못할 수 있으며, 이 경우 입력 단절로 초기화됩니다.

## 이용 조건

GazeCapture 데이터·가중치·코드는 연구 전용 라이선스입니다. 원본 라이선스 전체를 `app/src/main/assets/licenses/GazeCapture-LICENSE.md`에 보존했습니다. 상업용 앱 및 학습된 파생 모델의 상업 이용이 허용되는 것으로 해석하면 안 됩니다. 모델 파일의 재배포도 원본 조건을 따라야 합니다.

인용: Kyle Krafka, Aditya Khosla, Petr Kellnhofer, Harini Kannan, Suchendra Bhandarkar, Wojciech Matusik, Antonio Torralba. *Eye Tracking for Everyone*. CVPR 2016.
