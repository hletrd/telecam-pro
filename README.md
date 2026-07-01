# Find X9 Ultra Tele Camera

OPPO Find X9 Ultra 전용 프로 카메라앱. 3x 페리스코프 망원 렌즈 + afocal 텔레컨버터(300mm 상당) 촬영용.

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
| AGP | 9.2.0 |
| Gradle | 9.6.1 |
| Kotlin | 2.3.20 |
| Compose BOM | 2026.06.00 |
| compile/target/min SDK | 36 (Android 16) |
| JDK | 21 (aarch64) |

## 빌드

```bash
./gradlew assembleDebug        # 디버그 APK
./gradlew installDebug          # 기기에 설치
```

JDK 21 + Android SDK(API 36, build-tools 36.0.0) 필요. 설계 문서: [`docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md`](docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md)

## 구현 상태

초기 전체 스캐폴드 + 코어 구현 완료(Camera2 망원 선택·수동제어, GL 180° 반전 프리뷰/인코더, HEIF+DNG 사진, HEVC/Rec.2020/HLG·LOG 동영상, Compose 프로 UI). **아직 온디바이스 빌드/실행으로 검증되지 않았음** — 개발기에 JDK/SDK 미설치. 다음이 필요:

- 툴체인 설치 후 `./gradlew assembleDebug`로 컴파일 검증 및 오류 수정.
- 실제 Find X9 Ultra에서 망원 물리렌즈의 수동초점/RAW/10-bit 지원 확인 및 반전/초점/컬러 육안 검증.
- 10-bit HDR EGL 경로, LOG 커브, 물리렌즈+RAW+10bit 스트림 조합은 하드웨어 검증/튜닝 필요(설계 문서 §10 참고).
