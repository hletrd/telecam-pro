<div align="center">

<img src="docs/assets/logo.svg" width="112" alt="Find X9 Ultra Tele Camera logo" />

<h1>Find X9 Ultra Tele Camera</h1>

<p><b>OPPO Find X9 Ultra 전용 프로 카메라앱</b><br/>
3x 페리스코프 망원 + afocal 텔레컨버터(300mm) · 풀 수동제어 · afocal 180° 반전 · gyro EIS</p>

<p>
<img src="https://img.shields.io/badge/Android-16%20(API%2036)-3DDC84?logo=android&logoColor=white" alt="Android 16" />
<img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
<img src="https://img.shields.io/badge/Jetpack%20Compose-2026.06-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
<img src="https://img.shields.io/badge/Camera2-Pro%20manual-FF7043" alt="Camera2" />
<img src="https://img.shields.io/badge/Gradle-9.6.1-02303A?logo=gradle&logoColor=white" alt="Gradle" />
<img src="https://img.shields.io/badge/Device-Find%20X9%20Ultra%20(PMA110)-000000" alt="Find X9 Ultra" />
</p>

</div>

## 특징

- **단일 기기 전용**: Android 16 (API 36), 최신 툴체인만 사용 (하위호환 없음).
- **afocal 180° 반전**: 텔레컨버터가 afocal이라 이미지가 180° 뒤집혀 들어옴 → 프리뷰/사진/동영상 모두 되돌림.
- **풀 수동 제어**: 초점(무한대 근처 미세조정), ISO, 셔터, WB, EV.
- **사진**: HEIF + RAW(DNG) 동시 저장. HEIF는 픽셀 실제 180° 회전, DNG는 orientation 태그.
- **동영상**: 10-bit HEVC, Rec.2020, HLG / Log 선택. 오디오 포함.
- **촬영 보조**: 포커스 피킹, 제브라, 그리드, 수평계, punch-in.

## 툴체인

| 구성요소 | 버전 |
|---|---|
| AGP | 9.2.0 (Kotlin 내장) |
| Gradle | 9.6.1 |
| Kotlin | 2.3.10 (AGP 9.2 built-in) |
| Compose BOM | 2026.06.00 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 36 |
| JDK | 21 (aarch64) |

> compileSdk는 최신 AndroidX(lifecycle 2.11.0)가 요구하는 API 37로 컴파일하고, targetSdk/minSdk는 Android 16(API 36) 런타임에 맞춤 (compileSdk와 targetSdk는 분리됨). AGP 9부터 Kotlin이 내장되어 `kotlin.android` 플러그인은 쓰지 않음.

## 빌드

```bash
./gradlew assembleDebug        # 디버그 APK
./gradlew installDebug          # 기기에 설치
```

JDK 21 + Android SDK(API 36, build-tools 36.0.0) 필요. 설계 문서: [`docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md`](docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md)

## 구현 상태

전체 스캐폴드 + 코어 구현 완료(Camera2 망원 선택·수동제어, GL 180° 반전 프리뷰/인코더, HEIF+DNG 사진, HEVC/Rec.2020/HLG·LOG 동영상, Compose 프로 UI).

- ✅ **`./gradlew assembleDebug` 성공** — `app-debug.apk` 빌드됨. `compileDebugKotlin` 통과.
- ✅ **`./gradlew testDebugUnitTest` 성공** — FocusMapping 단위 테스트 통과.
- ⏳ **온디바이스 실행/카메라 동작은 미검증** — 실제 Find X9 Ultra에서 망원 물리렌즈의 수동초점/RAW/10-bit 지원 확인 및 반전/초점/컬러 육안 검증 필요.
- ⏳ 10-bit HDR EGL 경로, LOG 커브, 물리렌즈+RAW+10bit 스트림 조합은 하드웨어 검증/튜닝 필요(설계 문서 §10 참고).
