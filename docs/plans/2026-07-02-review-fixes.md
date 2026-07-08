# Review Fix Plan — 2026-07-02

Consolidate and deduplicate findings from 4 reviews (code-reviewer, perf-reviewer, architect, security-reviewer, `.context/reviews/`) and track resolution status. Rule: every finding is either (a) fixed or (b) deferred with recorded reason. No silent omissions.

Status: ☐ not started · ◐ in progress · ☑ completed · ⏸ deferred (reason)

## High (crash / black screen / recording corruption / near-ANR)

- ☐ H-CFG (code H1 / arch F2): `CameraController.onConfigureFailed` unhandled → permanent black on session failure. Add onError call + fallback ladder (remove RAW → remove HLG10).
- ☐ H-EGL (code H2/M10): `GlPipeline.setPreviewOutput` recreates without releasing existing EGLSurface → EGL_BAD_ALLOC crash/leak. Release before recreate + skip if same surface/size.
- ☐ H-EOS (code H3): video drain breaks early on `!running` → tail frame loss. Break only at EOS, clarify stop order.
- ☐ H-MUX (code H4): muxer not started when audio track not added → `writeSampleData` crash. Guard awaitMuxerStart return + wrap write in runCatching + isolate startAudio failure.
- ☐ H-MAIN (perf F1): surfaceCreated (main thread) reads camera selection+capabilities → near-ANR. Run on background.
- ☐ H-DEB (perf F2): repeating request rebuilt per slider tick → preview stutter. Debounce (~80ms).
- ☐ H-ENC (perf F3 / code M1 / arch F5): photo encoding (JPEG→Bitmap→rotate→HEIF) synchronous on camera thread + 2× full-bitmap OOM. Offload to IO thread + bitmap recycle / publish only on success / delete on failure + OOM guard.
- ☐ H-LEAK (perf F4 / arch F4): `CameraController` HandlerThread not quit → leak. Call quitSafely() in close().
- ☐ H-VOL (arch F1 / perf F10): `CameraEngine` / `GlPipeline.inputSurface` unsynchronized shared state → capture loss / torn read. Add @Volatile.
- ☐ H-LIFE (arch F3 / code M8): camera/gyro/GL kept in background. Tie camera and gyro lifecycle to preview surface via pause/resume.

## Medium

- ☐ M-IMG (code M2): `onCaptureFailed` doesn't close acquired image → ImageReader exhausted. Close on failure/partial completion.
- ☐ M-LVL (code M4 / arch F8): spirit level missing roll (always level). Expose accelerometer-based roll as state.
- ☐ M-PUNCH (code M5 / arch F8): punch-in non-functional. Wire GL preview center crop/zoom.
- ☐ M-TMR (code M6): self-timer re-entrant + not cancelled on mode/recording switch. Add guard + cancel.
- ☐ M-OK (arch F7): reports "saved" even if all saves fail. Report success only if at least 1 succeeds.
- ☐ M-FLASH (code M7): flash AUTO no-op, ON uses preview FLASH_MODE_SINGLE. Implement via AE mode.
- ☐ M-WB (code M11): manual WB is TRANSFORM_MATRIX but transform not set. Use COLOR_CORRECTION_MODE_FAST + gains.
- ☐ M-GYRO (perf F5): gyro oversampled at SENSOR_DELAY_FASTEST. Set ~200Hz (samplingPeriodUs) + retune alpha.
- ☐ M-OVR (code M3 / perf F10): setCameraOverride doesn't update videoSize/preview size + reopen race. Recalculate videoSize + call setCameraPreviewSize.
- ☐ M-ALLOC (perf F8): GL loop reallocates FloatArray + Runnable per frame. Use reusable array + call drawFrame directly.

## Low / Hardening

- ☐ L-CAPLOG (sec 1 / perf F9): camera capability logging always exposed in release + runs on GL thread. Gate with BuildConfig.DEBUG + move to background.
- ☐ L-BACKUP (sec 2): set allowBackup=false.
- ☐ L-PEAK (perf F12 / code L3): focus peaking duplicates texel fetch/rotation. Reuse base sample.
- ⏸ L-MINIFY (sec 3): R8 not applied in release — enabling without on-device verification risks regression. Enable after on-device verification (exit: confirm build/run on real device). Severity LOW maintained.
- ⏸ L-HEIF-ALPHA (sec 4): heifwriter 1.2.0-alpha01 — user explicitly requested "always latest." Severity LOW maintained; replace when stable release available (exit: stable release shipped).
- ⏸ L-HEIF-TRANSCODE (code L4): HEIF has double compression JPEG→bitmap→HEIF + jpegQuality not reflected — direct HEIC capture is major structural change requiring on-device stream-combination verification. Prioritize H-ENC recycle/offload only; direct capture as follow-up (exit: on-device stream combination confirmed).

---

## Resolution Summary (2026-07-02, assembleDebug + testDebugUnitTest passed)

### Fixed ☑
High: H-CFG (session fallback ladder), H-EGL (EGL surface release/skip), H-EOS (drain to EOS), H-MUX (muxer guard + audio degrade), H-MAIN (setup background), H-DEB (80ms debounce), H-ENC (encoding IO offload + recycle + publish on success + OOM guard), H-LEAK (HandlerThread quit), H-VOL (@Volatile engine/inputSurface), H-LIFE (onStart/onStop → resume/pause).
Medium: M-IMG (onCaptureFailed close), M-LVL (accelerometer roll → level), M-PUNCH (GL preview crop/zoom), M-TMR (timer guard/cancel), M-OK (report success only on ≥1 success), M-FLASH (AE mode flash), M-WB (COLOR_CORRECTION_MODE_FAST), M-GYRO (200Hz + alpha), M-OVR (videoSize recalc), M-ALLOC (drawFrame direct call).
Low: L-CAPLOG (BuildConfig.DEBUG gate + background), L-BACKUP (allowBackup=false), L-PEAK (base sample reuse).

### Deferred ⏸ (reason recorded, severity maintained)
- L-MINIFY (LOW): Enable R8 after on-device verification. Exit: confirm build/run on real device.
- L-HEIF-ALPHA (LOW): User explicitly requires "always latest" — heifwriter alpha maintained. Exit: replace when stable released.
- L-HEIF-TRANSCODE (LOW): Direct HEIC capture requires on-device stream-combination verification. Prioritize H-ENC offload/recycle. Exit: on-device stream combination confirmed.
