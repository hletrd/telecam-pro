# 리버스 엔지니어링 — 지식베이스

Find X9 Ultra 순정 카메라(`com.oplus.camera`)와 카메라 HAL 을 분석해, 텔레컨버터("Explorer") 손떨방/줌 메커니즘과 프로 제어를 우리 앱에서 재현하기 위한 자료 모음. (소유자 본인 기기 + 합법 취득 APK 대상의 정당한 분석)

## 문서

- [`camera-hardware-map.md`](camera-hardware-map.md) — 7개 카메라 매핑(초점거리/센서/역할), 텔레컨버터 대상 렌즈(dev4, 70mm), 셀렉터 함의.
- [`vendor-tags-catalog.md`](vendor-tags-catalog.md) — 손떨방/OIS/자이로/줌/Explorer/프로제어 벤더태그 큐레이션.
- [`oplus-camera-explorer-analysis.md`](oplus-camera-explorer-analysis.md) — 순정앱 디컴파일 심층 분석(Explorer 감지·손떨방 전환·줌 override 코드 근거). *(에이전트 분석 결과)*
- [`raw/vendor_tags.txt`](raw/vendor_tags.txt) — dumpsys 벤더태그 정의 원문(1,509개).

## 캡처 방법 (재현용)

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
ADB="$ANDROID_HOME/platform-tools/adb"
$ADB connect 172.30.50.127:38189            # 무선 adb (기기 슬립 시 재연결 필요)
$ADB shell dumpsys media.camera > dumpsys_camera.txt   # 카메라 특성 + 벤더태그
$ADB shell pm path com.oplus.camera         # → /product/app/OplusCamera/OplusCamera.apk
$ADB pull <path> OplusCamera.apk            # 순정앱 (169MB, 단일 base.apk)
jadx -j 6 --no-res -d jadx-out OplusCamera.apk   # 디컴파일 (16,697 java)
```

## 핵심 결론 (요약)

1. **텔레컨버터 대상 = 70mm 3x 페리스코프(dev4, 실초점 20.10mm)**. 최장초점(230mm/10x)이 아님 → 셀렉터는 70mm-equiv 타겟.
2. **텔레컨버터 모드는 수동 진입**(자동감지 아님). 컨버터 칩은 안전/인증 체크용으로 보임 → 우리 앱도 **수동 토글**.
3. **손떨방은 Qualcomm EIS(`EISMode`, `eisrealtime.*`) + OPPO 벤더태그(`ois.control.mode`, `sois.custom.info`, `video.stabilization.mode`)** 로 제어. 유효초점(300mm)에 맞춘 OIS/EIS 프로파일 전환이 핵심.
4. **줌/FOV 는 `custom.zoom.range`/`expert.zoom.range`/`fov.Angle` override** 로 3x→13x(300mm) 범위를 만듦.
5. `gyroFromAP` 존재 → 순정도 **앱이 자이로를 EIS 에 주입**. 우리 gyro EIS 접근이 방향적으로 동일.

## 우리 앱 전략

- **표준 Camera2 경로(확실)**: 70mm 렌즈 선택 + 우리 GL gyro EIS(유효초점 300mm 스케일) + HAL EIS off + OIS 토글. 벤더태그 없이도 동작.
- **벤더태그 경로(잠재적, 검증필요)**: `ois.control.mode`/`sois.custom.info`/`video.stabilization.mode`/`custom.zoom.range` 등을 CaptureRequest 로 세팅해 **순정 수준 네이티브 손떨방**을 시도. 서드파티 쓰기 허용 여부는 온디바이스 검증(앱 내 `VendorTagInspector` + 실촬영).

## 남은 작업(재연결 필요)

- 엔지니어 카메라 앱(`com.oplus.engineercamera`), 센서/코덱/카메라 config XML 덤프.
- 순정앱에서 텔레컨버터 모드 토글하며 `logcat` 라이브 캡처 → 실제 세팅되는 태그/값 확인.
- 우리 앱 온디바이스 설치·촬영 검증.
