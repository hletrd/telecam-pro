# 리뷰 수정 계획 — 2026-07-02

4개 리뷰(code-reviewer, perf-reviewer, architect, security-reviewer, `.context/reviews/`)의 findings를 통합·중복제거하고 처리 상태를 추적한다. 규칙: 모든 finding은 (a) 수정 또는 (b) 사유 기록 후 유예. 조용한 누락 금지.

상태: ☐ 미착수 · ◐ 진행 · ☑ 완료 · ⏸ 유예(사유)

## High (크래시 / 블랙스크린 / 녹화손상 / near-ANR)

- ☐ H-CFG (code H1 / arch F2): `CameraController.onConfigureFailed` 무처리 → 세션 실패 시 영구 블랙. onError 호출 + 폴백 래더(RAW 제거 → HLG10 제거).
- ☐ H-EGL (code H2/M10): `GlPipeline.setPreviewOutput` 기존 EGLSurface 미해제 재생성 → EGL_BAD_ALLOC 크래시/누수. 재생성 전 해제 + 동일 surface/size면 스킵.
- ☐ H-EOS (code H3): 비디오 drain `!running`에서 조기 break → 꼬리 프레임 손실. EOS에서만 break, stop 순서 정리.
- ☐ H-MUX (code H4): 오디오 트랙 미추가 시 muxer 미시작 → `writeSampleData` 크래시. awaitMuxerStart 반환 가드 + 쓰기 runCatching + startAudio 실패 격리.
- ☐ H-MAIN (perf F1): surfaceCreated(메인스레드)에서 카메라 selection+caps read → near-ANR. 백그라운드 실행.
- ☐ H-DEB (perf F2): 슬라이더 틱마다 repeating request 재빌드 → 프리뷰 스터터. 디바운스(~80ms).
- ☐ H-ENC (perf F3 / code M1 / arch F5): 사진 인코딩(JPEG→Bitmap→회전→HEIF)이 카메라 스레드에서 동기 + 2× 풀비트맵 OOM. IO 스레드로 오프로드 + 비트맵 recycle/성공시에만 publish/실패시 delete + OOM 가드.
- ☐ H-LEAK (perf F4 / arch F4): `CameraController` HandlerThread 미종료 누수. close()에서 quitSafely.
- ☐ H-VOL (arch F1 / perf F10): `CameraEngine`/`GlPipeline.inputSurface` 비동기화 공유상태 → 캡처 유실/torn read. @Volatile.
- ☐ H-LIFE (arch F3 / code M8): 백그라운드에서 카메라/자이로/GL 유지. 프리뷰 surface 수명에 카메라·자이로를 묶어 pause/resume.

## Medium

- ☐ M-IMG (code M2): `onCaptureFailed`가 획득 이미지 미close → ImageReader 고갈. 실패/부분 완료 시 close.
- ☐ M-LVL (code M4 / arch F8): 수평계에 roll 미공급(항상 수평). 가속도계 기반 roll을 상태로 노출.
- ☐ M-PUNCH (code M5 / arch F8): punch-in 무동작. GL 프리뷰 중앙 크롭줌 배선.
- ☐ M-TMR (code M6): 셀프타이머 재진입 + 모드/녹화 전환 시 미취소. 가드 + 취소.
- ☐ M-OK (arch F7): 저장 전부 실패해도 "저장됨" 오보고. 최소 1개 성공 시에만 성공 보고.
- ☐ M-FLASH (code M7): 플래시 AUTO no-op, ON이 preview에 FLASH_MODE_SINGLE. AE 모드로 구현.
- ☐ M-WB (code M11): 수동 WB TRANSFORM_MATRIX인데 transform 미설정. COLOR_CORRECTION_MODE_FAST + gains.
- ☐ M-GYRO (perf F5): 자이로 SENSOR_DELAY_FASTEST 과샘플링. ~200Hz(samplingPeriodUs) + alpha 재조정.
- ☐ M-OVR (code M3 / perf F10): setCameraOverride videoSize/preview size 미갱신 + 재오픈 레이스. videoSize 재계산 + setCameraPreviewSize.
- ☐ M-ALLOC (perf F8): GL 루프 프레임당 FloatArray + Runnable 재할당. 재사용 배열 + drawFrame 직접호출.

## Low / Hardening

- ☐ L-DUMP (sec 1 / perf F9): VendorTagInspector 릴리스 상시 노출 + GL 스레드 실행. BuildConfig.DEBUG 게이트 + 백그라운드.
- ☐ L-BACKUP (sec 2): allowBackup=false.
- ☐ L-PEAK (perf F12 / code L3): 피킹 중복 텍셀 페치/회전 texel. 베이스 샘플 재사용.
- ⏸ L-MINIFY (sec 3): 릴리스 R8 미적용 — 온디바이스 검증 없이 활성화 시 회귀 위험. 온디바이스 검증 후 활성화(exit: 실기기 빌드/실행 확인). 심각도 LOW 유지.
- ⏸ L-HEIF-ALPHA (sec 4): heifwriter 1.2.0-alpha01 — 사용자가 "무조건 최신" 명시 요구. 심각도 LOW 유지, stable 릴리스 나오면 교체(exit: 안정판 출시).
- ⏸ L-HEIF-TRANSCODE (code L4): HEIF JPEG→bitmap→HEIF 이중압축 + jpegQuality 미반영 — 직접 HEIC 캡처는 큰 구조변경, 온디바이스 스트림조합 검증 필요. H-ENC에서 recycle/오프로드만 우선, 직접캡처는 후속(exit: 온디바이스 스트림조합 확인).

---

## 처리 결과 (2026-07-02, assembleDebug + testDebugUnitTest 통과)

### 수정 완료 ☑
High: H-CFG(세션 폴백 래더), H-EGL(EGL surface 해제/스킵), H-EOS(EOS까지 drain), H-MUX(muxer 가드+오디오 degrade), H-MAIN(setup 백그라운드), H-DEB(80ms 디바운스), H-ENC(인코딩 IO 오프로드+recycle+성공시 publish+OOM 가드), H-LEAK(HandlerThread quit), H-VOL(@Volatile 엔진/inputSurface), H-LIFE(onStart/onStop→resume/pause).
Medium: M-IMG(onCaptureFailed close), M-LVL(가속도계 roll→level), M-PUNCH(GL 프리뷰 크롭줌), M-TMR(타이머 가드/취소), M-OK(성공시에만 보고), M-FLASH(AE모드 플래시), M-WB(COLOR_CORRECTION_MODE_FAST), M-GYRO(200Hz+alpha), M-OVR(videoSize 재계산), M-ALLOC(drawFrame 직접호출).
Low: L-DUMP(BuildConfig.DEBUG 게이트+백그라운드), L-BACKUP(allowBackup=false), L-PEAK(베이스 샘플 재사용).

### 유예 ⏸ (사유 기록, 심각도 유지)
- L-MINIFY(LOW): R8 온디바이스 검증 후 활성화. exit: 실기기 빌드/실행 확인.
- L-HEIF-ALPHA(LOW): 사용자 "무조건 최신" 요구로 heifwriter alpha 유지. exit: 안정판 출시 시 교체.
- L-HEIF-TRANSCODE(LOW): 직접 HEIC 캡처는 온디바이스 스트림조합 검증 필요. H-ENC에서 오프로드/recycle만 우선. exit: 온디바이스 스트림조합 확인.
