# Find X9 Ultra 텔레컨버터 카메라앱 — 설계 문서

- 날짜: 2026-07-01
- 대상 기기: OPPO Find X9 Ultra (단일 기기), Android 16 (API 36)
- 목적: 3x 페리스코프 망원 렌즈 + afocal 텔레컨버터(300mm 상당) 촬영용 프로 카메라앱

## 1. 배경 / 문제 정의

- 텔레컨버터가 **afocal**(정립계 없는 반사/굴절 망원)이라 이미지가 **180° 회전**되어 들어온다.
  상하 반전 + 좌우 반전 = 180° 회전(패리티 불변, 거울상 아님). 프리뷰와 저장 결과 모두 180° 되돌려야 한다.
- afocal 부착물의 출사광은 대략 시준(collimated)이므로 폰 렌즈는 **무한대 근처에서 초점**이 맞는다.
  → 수동 초점(무한대 근처 미세조정)이 핵심.
- 단일 기기 전용이므로 범용 호환성 계층은 불필요. 저수준 제어를 위해 Camera2 직접 사용.

## 2. 목표 / 비목표

### 목표
- 후면 3x 망원 물리렌즈로 촬영. 프리뷰/사진/동영상 모두 180° 반전 적용.
- 풀 수동 제어: 초점(LENS_FOCUS_DISTANCE), ISO(SENSITIVITY), 셔터(EXPOSURE_TIME), WB, EV.
- 사진: **HEIF + RAW(DNG)** 동시 저장. HEIF는 픽셀 실제 180° 회전, DNG는 orientation 태그.
- 동영상: **10-bit HEVC, Rec.2020, HLG / Log 선택**, 180° 반전이 실제로 인코딩됨. 오디오 포함.
- 수동초점/노출 보조: 포커스 피킹, 제브라, 그리드, 수평계.

### 비목표
- 다기종 호환, 하위호환(minSdk 낮춤), CameraX 사용.
- 자동 장면 인식/AI 보정/필터, 클라우드 동기화.
- 벤더 전용 LOG 프로파일 비트-완전 재현(스톡앱 전용이라 불가). 우리는 GL Log OETF로 근사.

## 3. 빌드 / 툴체인 (전부 최신, deprecated 배제)

| 구성요소 | 버전 |
|---|---|
| AGP | 9.2.0 (Kotlin **built-in** — `kotlin.android` 플러그인 미사용) |
| Gradle | 9.6.1 (wrapper) |
| Kotlin | 2.3.10 (AGP 9.2 내장) |
| Compose Compiler plugin | 2.3.10 (`org.jetbrains.kotlin.plugin.compose`, 내장 Kotlin에 맞춤) |
| Compose BOM | 2026.06.00 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 36 |
| Build Tools | 36.0.0 (+37.0.0 설치됨) |
| JDK | 21 (aarch64 / Apple Silicon) |

- Kotlin DSL(`build.gradle.kts`) + Version Catalog(`gradle/libs.versions.toml`).
- Java/Kotlin toolchain 21. `kotlin { jvmToolchain(21) }`.
- AGP 9부터 Kotlin이 내장되어 별도 `org.jetbrains.kotlin.android` 플러그인을 적용하면 안 됨.
- compileSdk 37은 최신 AndroidX(lifecycle 2.11.0) 요구사항; targetSdk 36(Android 16)과 분리.
- 개발기: ARM Mac. 앱은 기기에서 실행. 빌드는 Android Studio 또는 CLI(`./gradlew`).

## 4. 아키텍처

단방향 데이터 흐름. Camera2가 하드웨어, GL 렌더러가 180° 변환, Compose가 UI.

```
CameraController (Camera2)
   ├─ opens 후면 논리 카메라, 망원 물리렌즈로 스트림 라우팅
   ├─ CaptureRequest 빌드(수동 focus/ISO/shutter/WB/EV, DynamicRangeProfile)
   ├─ 출력 타겟:
   │    ├─ SurfaceTexture → GlPipeline (프리뷰 + 비디오 인코더 공용)
   │    ├─ ImageReader(HEIF용 YUV/JPEG)
   │    └─ ImageReader(RAW_SENSOR → DNG)
   │
GlPipeline (OpenGL ES / EGL)
   ├─ 카메라 SurfaceTexture 입력을 풀스크린 쿼드로 그림 (180° 회전 행렬)
   ├─ 10-bit 경로: RGBA1010102 / FP16, Rec.2020
   ├─ Transfer OETF: HLG 또는 Log 커브(셰이더에서 적용)
   ├─ 출력 A: 화면 프리뷰 Surface(SurfaceView)
   └─ 출력 B: MediaCodec(HEVC) 입력 Surface (녹화 시)
   │
CapturePipeline (사진)
   ├─ HEIF: YUV/RGBA → Bitmap → 180° 회전 → HeifWriter 인코딩
   └─ DNG: RAW_SENSOR → DngCreator.setOrientation(ROTATE_180) → 파일
   │
VideoRecorder
   ├─ MediaCodec HEVC(Main10) + AAC, MediaMuxer(MP4)
   ├─ 컬러: Rec.2020 primaries, HLG or Log transfer, 10-bit
   └─ 입력: GlPipeline 출력 B (이미 180° 반전됨)
   │
Storage (MediaStore) — DCIM/Pictures 저장, 메타데이터
   │
UI (Compose) — 프리뷰 + 프로 컨트롤 오버레이 + 설정
```

### 모듈/파일 경계 (각 단위는 단일 책임)
- `camera/CameraController.kt` — Camera2 세션 수명주기, 요청 빌드, capability 검출.
- `camera/CameraSelector2.kt` — 망원 물리렌즈 탐지(최대 초점거리) + 수동 오버라이드.
- `camera/CaptureCapabilities.kt` — MANUAL_SENSOR/RAW/DynamicRange/focus 범위 조회.
- `gl/GlPipeline.kt`, `gl/FlipRenderer.kt`, `gl/shaders/*` — EGL + 180° + OETF.
- `capture/HeifCapture.kt`, `capture/DngCapture.kt` — 사진 인코딩.
- `video/VideoRecorder.kt`, `video/ColorProfiles.kt` — 동영상 인코딩/컬러.
- `storage/MediaStoreWriter.kt` — 저장.
- `ui/CameraScreen.kt`, `ui/controls/*`, `ui/overlays/*` — Compose.
- `ui/CameraViewModel.kt` — 상태(초점/노출/모드/포맷) 관리.
- `focus/FocusMapping.kt` — 슬라이더 ↔ LENS_FOCUS_DISTANCE(무한대 근처 미세) 매핑.
- `MainActivity.kt`, 권한.

## 5. 180° 반전 상세

- **프리뷰/동영상**: GL 셰이더에서 텍스처 좌표를 `1-uv`로 뒤집어(=180° 회전) 렌더. 한 렌더 패스로 프리뷰 Surface와 인코더 Surface 양쪽에 그린다.
- **HEIF 사진**: 캡처 버퍼를 Bitmap으로 변환 후 `Matrix.postRotate(180)`으로 **픽셀 실제 회전** 후 HEIF 인코딩.
- **DNG(RAW)**: Bayer 픽셀 회전은 CFA를 깨므로 하지 않고 `DngCreator.setOrientation(ExifInterface.ORIENTATION_ROTATE_180)`으로 방향 태그(RAW 표준). 현상 시 자동 정립.

## 6. 컬러 파이프라인 (동영상)

- Camera2 `OutputConfiguration.setDynamicRangeProfile(HLG10)`(또는 지원 프로파일)로 10-bit 스트림 확보.
- GL 파이프라인은 10-bit 서피스(RGBA1010102) 사용, Rec.2020 색공간 태깅.
- Transfer 선택:
  - **HLG**: 표준 HLG OETF. HDR 재생 호환.
  - **Log**: 플랫한 Log형 커브(그레이딩용). 셰이더에서 적용, 컨테이너는 Rec.2020로 태깅.
- 인코더: `MediaCodec` HEVC `profile=HEVCProfileMain10`(HDR10/HLG 대응), `MediaMuxer` MP4, `COLOR_STANDARD_BT2020` + 대응 transfer.
- 오디오: AAC LC.

## 7. 수동 초점 UX

- 슬라이더 → `LENS_FOCUS_DISTANCE`(디옵터, 0=무한 ~ `LENS_INFO_MINIMUM_FOCUS_DISTANCE`).
- afocal 특성상 무한대 근처가 중요 → 비선형 매핑으로 무한대 부근 해상도 강화.
- 현재 초점거리(m), 피사계심도 힌트 표시.
- 보조: **포커스 피킹**(GL/셰이더 엣지 검출 하이라이트), **제브라**(노출 클리핑), 확대(1:1 punch-in).

## 8. UI (Compose)

- 풀스크린 프리뷰(반전됨).
- 하단 셔터/모드(사진↔동영상)/갤러리 썸네일.
- 접이식 프로 패널: Focus / ISO / Shutter / WB / EV, 오토↔매뉴얼 토글.
- 상단 인디케이터: 포맷(HEIF+RAW), 컬러(HLG/Log), 카메라 ID, 히스토그램(옵션).
- 토글: 피킹, 제브라, 그리드, 수평계, punch-in.
- 설정 화면: 카메라 ID 오버라이드, 저장 포맷, 컬러 프로파일, 오디오 on/off.

## 9. 권한 / 저장

- `CAMERA`, `RECORD_AUDIO`(동영상). 런타임 권한 요청.
- MediaStore로 DCIM/Pictures(사진), DCIM(동영상) 저장. Scoped storage.
- EXIF/메타데이터 기록(렌즈/초점/노출).

## 10. 리스크 / 검증 필요 (온디바이스)

1. 망원 물리렌즈가 수동초점/RAW/10bit HDR를 모두 지원하는지 — capability로 검출, 미지원 시 폴백/비활성.
2. 물리렌즈 + RAW + 10-bit 동시 스트림 조합의 하드웨어 제약 — 스트림 구성별 검증.
3. 진짜 벤더 LOG 미재현 — GL Log 커브로 근사(정직하게 표기).
4. HEIF 10-bit 인코딩 경로 — v1은 HEIF 8-bit(픽셀 회전) + DNG로 데이터 보존, 10-bit HEIF는 후속.
5. ✅ 툴체인 설치(JDK 21 + SDK 36/37 + build-tools) 후 `assembleDebug`/`testDebugUnitTest` 통과 — 컴파일·단위테스트 검증 완료. 카메라 하드웨어 동작은 온디바이스 검증 남음.

## 11. 테스트 전략

- 순수 로직 단위테스트: 회전 행렬, `FocusMapping`(슬라이더↔디옵터), 카메라 선택(최대 초점거리), DNG orientation 값, 컬러 프로파일 파라미터.
- 하드웨어 의존부는 온디바이스 수동 검증(캡처 결과 방향/초점/컬러 육안 확인).

## 12. 마일스톤

1. 프로젝트 스캐폴딩(Gradle/매니페스트/권한/빈 화면 실행).
2. Camera2 프리뷰 + 망원 선택 + GL 180° 반전 프리뷰.
3. 수동 초점/노출 컨트롤.
4. HEIF + DNG 사진 캡처(180° 처리).
5. 10-bit HEVC Rec.2020 HLG/Log 동영상.
6. 피킹/제브라/설정/마무리.
