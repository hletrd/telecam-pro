# Find X9 Ultra (PMA110) — 카메라 하드웨어 맵

- 기기: OPPO **PMA110** (Find X9 Ultra), Android 16 / ColorOS `PMA110_16.0.8.306(CN01)`
- 출처: 실기기 `adb shell dumpsys media.camera` (113,873줄), 2026-07-01 캡처
- 카메라 provider: `android.hardware.camera.provider/vendor_qti/0-0` (Qualcomm), **7개 물리 디바이스**

## 카메라 매핑

35mm 환산 = 실초점 × 43.267 / 센서대각선. 센서대각선은 `SENSOR_INFO_PHYSICAL_SIZE`로 계산.

| HAL dev | 실초점 | **35mm 환산** | 센서(mm) | facing | 정체 |
|---|---|---|---|---|---|
| 0 | 7.73mm | **~23mm** | 11.42 × 8.58 (1/1.12") | BACK | **메인 와이드** 200MP f/1.5 |
| 1 | 3.25mm | (전면) | 5.24 × 3.93 | FRONT | 전면 |
| 2 | 7.73mm | ~23mm | 11.42 × 8.58 | BACK | 메인 와이드(2차 모드/로지컬 중복) |
| 3 | 2.63mm | **~14mm** | 6.55 × 4.92 (1/1.95") | BACK | **울트라와이드** 50MP f/2.0 |
| **4** | **20.10mm** | **~70mm** | 10.03 × 7.52 (1/1.28") | BACK | **★ 3x 페리스코프 200MP f/2.2 — 텔레컨버터 부착 대상** |
| 5 | 34.80mm | **~230mm** | 5.24 × 3.93 (1/2.75") | BACK | 10x 페리스코프 50MP f/3.5 |
| 6 | 1.934mm | ~37mm | 1.79 × 1.34 | BACK | 보조 소형 센서(멀티스펙트럴/보조 추정) |

> dev0 과 dev2 는 동일 초점거리·센서 → 같은 메인 센서를 2개 ID(전체/크롭 또는 logical+physical)로 노출한 것으로 보임.

## 텔레컨버터(Explorer)

- Hasselblad "Earth Explorer" 300mm 텔레컨버터는 **dev4(3x, 70mm)** 에 afocal 로 부착 → **~300mm(약 4.28×)**, 13x 상당.
- 즉 우리 앱이 타겟해야 하는 렌즈는 **dev4 (70mm-equiv)**.

## 앱 셀렉터에 대한 함의 (중요)

- **"최장 초점거리"로 고르면 안 됨** → 그러면 dev5(230mm, 10x)를 잡음.
- 올바른 로직: 후면 카메라(및 물리 서브카메라) 중 **35mm 환산이 70mm에 가장 가까운 것**을 선택 (= dev4).
- 앱에 노출되는 `getCameraIdList()` ID 는 HAL dev 순서와 다를 수 있음 → 런타임에 환산 초점거리로 매칭. 실제 노출 ID/물리 ID 는 앱 내 `VendorTagInspector`(온디바이스)로 확인.
- 구현: `CameraSelector2` 는 목표 환산초점(기본 70mm)에 가장 가까운 렌즈 선택 + 수동 오버라이드.

## 손떨방 관련(요약; 상세는 vendor-tags-catalog.md / oplus-camera-explorer-analysis.md)

- 스톡앱은 텔레컨버터에서 **수동으로 모드 진입**(자동감지 아님). 컨버터의 전자칩(`explorer.chip.state`)은 손상/온도/무선충전 안전체크·인증용으로 보임.
- 손떨방은 Qualcomm EIS(`org.quic.camera.eisrealtime`, `sessionParameters.EISMode`) + OPPO 벤더태그(`com.oplus.ois.control.mode`, `sois.custom.info`, `video.stabilization.mode`)로 제어. 유효 초점거리에 맞춘 프로파일 전환이 핵심.
