# Find X9 Ultra — 카메라 벤더태그 카탈로그 (큐레이션)

- 출처: `dumpsys media.camera` 의 "Vendor tags" 섹션 (1,509개 정의) + 순정 `OplusCamera.apk` 디컴파일에서 실제 사용 확인
- 목적: 텔레컨버터 손떨방/줌 + 프로 제어를 우리 앱에서 재현하기 위한 참조

> ⚠️ 벤더태그는 `new CaptureRequest.Key<>("com.oplus...", T.class)` / `CameraCharacteristics.Key` 로 접근. 서드파티 앱에서 **읽기**는 대체로 가능하나, **쓰기**는 HAL 이 시스템앱(서명/권한)에만 허용할 수 있음 → 온디바이스 검증 필요.

## 1) 손떨방 / OIS (com.oplus)

| tag id | 이름 | type | 용도(추정) |
|---|---|---|---|
| 0x811900fc | `ois.control.mode` | int32 | **OIS 제어 모드** — 렌즈/유효초점 프로파일 전환의 핵심 후보 |
| 0x81190000 | `sois.custom.info` | byte | **Smart OIS 커스텀 파라미터** |
| 0x8119009e | `video.stabilization.mode` | int32 | OPPO 비디오 손떨방 모드(앱이 세팅) |
| — | `eis.supersteady.enable` | byte | 슈퍼스테디 EIS |
| — | `tele.eis.active` | byte | 망원 EIS 활성(앱이 read) |
| 0x81190056 | `camera.dualois.value.oistoeis` | byte | OIS→EIS 핸드오프 데이터 |
| 0x8119015b | `camera.ois.value.oistoeis` | byte | OIS→EIS |
| — | `superEis.available.target.fps.ranges` | int[] | Super EIS 지원 fps |
| — | `eis.workon` / `eis.record.state` | byte/int | EIS 동작/녹화 상태 |

## 2) 자이로 (com.oplus) — 앱이 EIS용 자이로를 주입 가능

| tag id | 이름 | type | 용도 |
|---|---|---|---|
| 0x8119004f | `gyroFromAP` | float | **AP(앱)가 주입하는 자이로** — 우리 gyro EIS 접근과 동일 개념 |
| 0x81190049 | `gyroSqrCutom` | float | 자이로 커스텀 |
| 0x81190131 | `gyro.data` | float | 자이로 데이터 |
| 0x81190153 | `camera.gyro.value.gyrotoeis` | int32 | 자이로→EIS |
| 0x81190164 | `camera.gyro.common.enable` | int32 | 자이로 공통 enable |
| 0x811900c7 | `EISPreviewGyro` | byte | 프리뷰 EIS 자이로 |

## 3) 줌 / FOV (com.oplus) — 3x를 300mm/13x 범위로 override

| tag id | 이름 | type | 용도 |
|---|---|---|---|
| 0x81190007 | `custom.zoom.range` | float[] (Chars) | 사진 줌 범위 override |
| 0x81190008 | `custom.video.zoom.range` | float[] | 동영상 줌 범위 |
| 0x8119000a | `custom.operation.mode.zoom.range` | float[] | 모드별 줌 범위 |
| 0x8119000c | `expert.zoom.range` | float[] | 전문가(Pro) 줌 범위 |
| 0x8119000f | `fov.Angle` | float | FOV 각도 |
| — | `com.oplus.recording.zoom.active` | int (Req) | 녹화 줌 활성 |

## 4) 텔레컨버터 "Explorer" (앱 config/state, 카메라 벤더태그 아님)

수동 진입(자동감지 아님). 칩은 손상/온도/무선충전 안전·인증 체크로 보임.

- `com.oplus.feature.explorer.support`, `com.oplus.configure.explorer.enable`
- `com.oplus.explorer.switch.current.state`, `com.oplus.explorer.chip.state`, `com.oplus.explorer.hw.capacity`
- `explorer_chip_damage_tip`, `explorer_disable_by_wireless_charge_tip`, `isMeetExplorerTemperatureThreshold`
- engineer: `engineercamera.explorer.status`, `engineercamera.explorercamera.mipi.bypass.sensormode`

## 5) QTI(Qualcomm) EIS 스택 (org.quic / org.codeaurora / com.qti)

| section.tag | type | 용도 |
|---|---|---|
| `org.codeaurora.qcamera3.sessionParameters.EISMode` (0x8020003a) | int32 | EIS 모드(세션 파라미터) |
| `org.quic.camera.eisrealtime.EISOISMode` (0x80060006) | byte | EIS+OIS 결합 모드 |
| `org.quic.camera.eisrealtime.RequestedMargin/StabilizationMargins/MinimumMargin` | byte | EIS 크롭 마진(초점거리↑ → 마진↑ 필요) |
| `org.quic.camera.eisrealtime.MotionIndication` | byte | 모션 표시 |
| `org.quic.camera.eis3enable.EISV3Enable` (0x80980000) | byte | EIS v3 |
| `org.quic.camera2.usecase_ife.PreviewPathEISCorrection` | byte | 프리뷰 EIS 보정 |
| `com.qti.chi.stabilizationmode.imageStabilizationMode` (0x80ec0000) | byte | 이미지 손떨방 모드 |
| `org.codeaurora.qcamera3.sessionParameters.ExtendedMaxZoom` | int32 | 확장 최대 줌 |

## 6) 보너스 — 추가 프로 제어 벤더태그 (앱이 사용, 우리 앱에서 시도 가능)

디바이스가 노출하는 프로 제어들 (표준 Camera2 밖):

- **매뉴얼 WB/틴트**: `manualWB.color_tone`, `manualWB.color_tone_range`, `manualTone.cold.warm`, **`manualTone.cyan.magenta`**(틴트), `manualTone.contrast`, `manualTone.saturation`
- **프로 톤**: `controlEdgeLevel`, `controlVignetteLevel`, `controlClarityLevel`, `controlHueLevel`, `controlContrastHighlightLevel`, `controlContrastShadowLevel`, `controlSaturationLevel`
- **ISO 확장**: `pro.extension.iso.range/support`, `movie.extension.iso.range`, `log.extension.iso.range`
- **LOG/HDR 비디오**: `log.video.mode`, `movie.log.enable`, `movie.hdr.enable`
- **모드**: `supernight.mode`, `ultra.resolution.mode`, `night.*`, `ai.scene.*`, `portrait.bokeh.state`, `bokeh.level`

> 이들은 표준 Camera2 로는 없는 제어라, 벤더태그 쓰기가 허용되면 순정 수준의 프로 제어(틴트·클래리티·비네팅·Log 등)를 재현할 수 있음. 미허용 시 표준 Camera2 + 우리 GL/EIS 로 대체.
