# Aggregate Deep Review — Cycle 1 (2026-07-03)

**Scope:** Full inline review of all 29 Kotlin sources under `app/src/main/kotlin/**`, `app/src/test/**`, `AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`. Judged against the run goal: *perfect this as a professional camera app for photographers migrating from Sony (BIONZ XR2) and Google Pixel Camera — pro ergonomics, correct manual controls, discoverability, polish.*

**Method note:** The multi-agent review fan-out was spawned; a background-poll race made it *look* like the agents died, so an inline review was also done by the cycle lead (findings H1–L7 below). In fact **5 agents completed and wrote substantial provenance files** — fold their findings in (see "Agent findings" section). Provenance files present this cycle:
- `perf-reviewer.md` (307 lines) — PERF-1..N
- `debugger.md` (329 lines) — BUG-1..5 + L-1
- `designer.md` (521 lines) — **UX-1..UX-30** (the run's pro-UX focus)
- `test-engineer.md` (468 lines) — TEST-1..16 + a critical test-infra finding
- `tracer.md` (638 lines) — TRACE-1..5c
The read-only agents (code-reviewer, security-reviewer, architect, critic, verifier, document-specialist) did not surface return files this cycle; their angles are covered by the inline review + the above. Prior-cycle files (`architect.md`, `code-reviewer.md`, `security-reviewer.md`) retained for history.

**Gate baseline (this cycle, measured):** `:app:assembleDebug` ✅ · `:app:testDebugUnitTest` ✅ · `:app:lintDebug` ❌ **1 error** + 18 warnings.
- The lint **error** is the only thing making the gate red (see H1).

Severity legend: **High** = crash / gate-red / data-loss / corrupt output / pro-blocking correctness · **Medium** = wrong behavior, pro-UX gap, state desync · **Low** = polish/hygiene/warnings.
Confidence: High / Med / Low. Device-only items are flagged `[device-verify]` (emulator has synthetic camera hardware — no tele/RAW/HLG/EIS).

---

## Summary table

| ID | Sev | Conf | Area | One-liner |
|----|-----|------|------|-----------|
| H1 | High | High | build/lint | `HeifWriter.close()` RestrictedApi → `lintDebug` fails (gate red) |
| H2 | High | Med | video | Video ignores G-sensor orientation → landscape clips save rotated `[device-verify]` |
| H3 | High | High | video | `VideoRecorder.stop()` publishes empty/corrupt MP4 if muxer never started |
| M1 | Med | High | lifecycle | Recording UI state desyncs when app backgrounds mid-record |
| M2 | Med | High | pro-UX | No settings persistence across process death (Sony/Pixel remember) |
| M3 | Med | Med | focus | Tap-to-focus mapping ignores EIS/punch-in crop+offset `[device-verify]` |
| M4 | Med | Med | stab | 300mm gyro-EIS axis/sign needs on-device tuning `[device-verify]` |
| M5 | Med | High | a11y/ergo | Touch targets < 48dp (top-bar 36dp, snapshot 36dp, close 32dp) |
| M6 | Med | High | pro-UX | No pinch-to-zoom gesture on the viewfinder |
| M7 | Med | Med | pro-UX | Dead gallery thumbnail — no way to review the last shot |
| M8 | Med | Med | permissions | Permanently-denied permission has no Settings deep-link (dead button) |
| M9 | Low | Med | permissions | RECORD_AUDIO never re-requested if only CAMERA granted |
| M10| Med | High | polish | `overlayRotation` animates the long way at 270↔0 wrap |
| L1 | Low | High | deps | Stale dependency versions (agp/coreKtx/activity/composeBom) |
| L2 | Low | High | lint | `android.media.ExifInterface` used instead of androidx (×5 warnings) |
| L3 | Low | High | lint | `EmptySuperCall` — `super.onCleared()` |
| L4 | Low | High | policy | `OldTargetApi` warning (targetSdk 36) — intentional, defer |
| L5 | Low | Med | pro-UX | No live exposure readout while in Auto |
| L6 | Low | Med | state | `videoResolution` default (4K) ≠ engine default (1080p) until user picks |
| L7 | Low | Low | drive | AEB clamps -2/0/+2 to duplicate steps on a narrow EV range |

Total findings this cycle: **20**.

---

## HIGH

### H1 — `lintDebug` fails: `HeifWriter.close()` RestrictedApi (gate red) [High/High]
`app/src/main/kotlin/com/hletrd/findx9tele/capture/HeifCapture.kt:22` — `writer.close()`.
Lint: `WriterBase.close can only be called from within the same library (androidx.heifwriter:heifwriter) [RestrictedApi]` → **error** → `:app:lintDebug` fails, so the whole gate set is red.
`HeifWriter` implements `AutoCloseable`; `close()` is the intended resource-release path but heifwriter `1.2.0-alpha01` annotates the inherited `WriterBase.close()` `@RestrictTo(LIBRARY_GROUP)`, so a direct call trips lint. This is a genuine lint false-positive on an alpha AndroidX API (there is no non-restricted way to release the writer).
**Failure scenario:** any lint-gated CI/release build fails; a `-Werror`-style gate blocks all further work.
**Fix:** narrowly-scoped `@android.annotation.SuppressLint("RestrictedApi")` on `writeHeif` (or a scoped `lint.xml` ignore) with a comment justifying it as the AutoCloseable release path, documented in the commit body per the gate rule (false-positive gate → fix + explain). Prefer trying Kotlin `.use{}`/`AutoCloseable` first if it silences lint without suppression.

### H2 — Video encode ignores device orientation → landscape clips save rotated [High/Med] `[device-verify]`
`camera/CameraEngine.kt` `startRecording()` + `video/VideoRecorder.kt` + `gl/GlPipeline.kt`.
Stills bake device orientation via `captureRotationDegrees()` (= sensor + afocal180 + `gyro.currentDeviceOrientation()`), but the video path encodes the GL output which applies only the **fixed preview rotation** (afocal 180°, no device term), and `VideoRecorder` sets **no** `MediaMuxer` orientation hint. A clip framed in landscape saves portrait-rotated.
**Failure scenario:** user shoots a landscape video → plays back rotated 90° in every player.
**Fix:** snapshot device orientation at record-start (`gyro.currentDeviceOrientation()`), thread it to `VideoRecorder.start()`, and call `muxer.setOrientationHint(deg)` before `muxer.start()`. Portrait (0°) is unchanged (no regression); landscape is corrected. Sign may need `(360-deg)%360` — confirm on device.

### H3 — `VideoRecorder.stop()` publishes an empty/corrupt MP4 when muxer never started [High/High]
`video/VideoRecorder.kt:143` — `uri?.let { MediaStoreWriter.publish(context, it) }` runs unconditionally.
If recording is stopped before the encoder emitted `INFO_OUTPUT_FORMAT_CHANGED` (very short tap, or immediate background), `muxerStarted` stays false, no sample is written, but the pending MediaStore video is still **published** → a 0-byte / unplayable MP4 appears in the gallery.
**Failure scenario:** double-tap the record button quickly → junk video files accumulate in DCIM.
**Fix:** in `stop()`, publish only when `muxerStarted` was true (ideally: at least one sample written); otherwise `MediaStoreWriter.delete(context, uri)`.

---

## MEDIUM

### M1 — Recording UI state desyncs on background-while-recording [Med/High]
`ui/CameraViewModel.kt` `onStop()` → `engine.pause()`.
`pause()` stops+nulls the recorder and finalizes the video, but the ViewModel keeps `isRecording = true` and the `recordTicker` running (posting every 200 ms while backgrounded). On return the UI still shows "recording"; the next stop-tap is a no-op that then clears it.
**Failure scenario:** background during a recording → return → timer still ticking, red indicator up, but nothing is recording; confusing + wasted main-thread work.
**Fix:** in `onStop()`, if `_state.value.isRecording`, remove `recordTicker` and set `isRecording=false` (mirror `stopRecording()`'s UI teardown), since `pause()` already finalized the file.

### M2 — No settings persistence across process death [Med/High] (pro-UX)
`ui/CameraViewModel.kt` — all `CameraUiState` is in-memory; a cold start resets ISO/shutter/WB/format/EIS/grid/drive/assists to defaults.
Sony and Pixel both persist shooting settings between sessions; a pro expects the camera to come back exactly as left. Known task-list item ("settings persistence").
**Fix:** persist a curated subset of non-hardware settings (photoFormats, transfer, videoCodec/resolution/bitrate, grid, eis+strength, teleconverter, drive/timer/interval, assists toggles, aspect) to `SharedPreferences`/DataStore; restore in `init`. Pure logic — unit-testable.

### M3 — Tap-to-focus ignores EIS/punch-in crop+offset [Med/Med] `[device-verify]`
`camera/CameraEngine.kt:212 setTapPoint()` — inverts the sensor+afocal rotation but not the EIS `(sx,sy)`/`crop` or the punch-in zoom applied in `GlPipeline.drawFrame`, so the AF/AE region lands off the tapped point whenever EIS or punch-in is active. Comment already flags this. Needs on-device calibration (axis signs / mirror).
**Fix:** apply the inverse of the preview crop-zoom + EIS shift before forwarding to `controller.setMeteringPoint`. Defer the sign/axis tuning to device.

### M4 — 300mm gyro-EIS axis/sign needs on-device tuning [Med/Med] `[device-verify]`
`stab/GyroEis.kt` + `gl/GlPipeline.kt drawFrame` — `currentCorrection()` returns `[yaw,pitch,roll]` mapped to `sx,sy,roll`; a wrong sign amplifies shake instead of cancelling it. Magnitude scaling is exact; axis mapping is not. Cannot validate on AVD. **Keep as device-verify backlog item.**

### M5 — Touch targets below 48 dp [Med/High] (a11y / one-handed ergonomics)
`ui/CameraScreen.kt` `ChromeIconButton` = 36 dp (flash/timer/aspect/grid/gear), `SnapshotButton` = 36 dp; `ui/controls/ProSheet.kt` `CloseButton` = 32 dp. WCAG 2.2 / Material minimum is ~44–48 dp. A telephoto app is often used one-handed and braced — small targets are a real miss-tap risk.
**Fix:** expand the clickable/min-touch area to ≥48 dp (keep the drawn glyph small; grow the hit box).

### M6 — No pinch-to-zoom on the viewfinder [Med/High] (pro-UX)
`ui/CameraScreen.kt` — the preview only wires `detectTapGestures → onTapFocus`. Zoom (`onZoomRatio` / `CONTROL_ZOOM_RATIO`) is reachable only via a slider buried in the Shooting tab. Every Sony/Pixel migrant reaches for pinch-zoom first.
**Fix:** add `detectTransformGestures` on the preview → `onZoomRatio`, clamped to `caps.zoomRatioRange`, coexisting with tap-to-focus.

### M7 — Dead gallery thumbnail; no last-shot review [Med/Med] (pro-UX)
`ui/CameraScreen.kt` `GalleryThumbPlaceholder` is decorative and unwired. There is no way to review what you just shot without leaving the app.
**Fix:** at minimum, make the thumbnail launch the system gallery / last captured item via `ACTION_VIEW`; ideally show the real last-capture thumbnail.

### M8 — Permanently-denied permission → dead "Grant" button [Med/Med]
`MainActivity.kt` `PermissionGate` re-launches the permission request; once the user picked "don't ask again", the system shows no dialog and the button does nothing, with no path forward.
**Fix:** when `!shouldShowRequestPermissionRationale`, switch the CTA to "Open Settings" (`ACTION_APPLICATION_DETAILS_SETTINGS`).

### M9 — RECORD_AUDIO not re-requested if only CAMERA granted [Low→Med/Med]
`MainActivity.kt` — the launch-time request only fires when `!hasCamera`; if CAMERA is granted but RECORD_AUDIO denied, audio silently never records and is never re-prompted.
**Fix:** track audio permission and offer a re-request when the user enables Record Audio.

### M10 — `overlayRotation` animates the long way at wrap [Med/High] (polish)
`ui/CameraScreen.kt:117` — `animateFloatAsState(targetValue = -state.deviceOrientation.toFloat())`. Going 270°→0° animates −270→0 (a 270° spin) instead of the shortest 90°.
**Fix:** track an unwrapped cumulative target (add the shortest-path delta) so scopes/readouts always rotate ≤90°.

---

## LOW / hygiene

### L1 — Stale dependency versions [Low/High] (warnings + global "latest" policy)
`gradle/libs.versions.toml` — lint `GradleDependency`/`AndroidGradlePluginVersion`: agp 9.2.0→9.2.1, coreKtx 1.16.0→1.19.0, activityCompose 1.12.4→1.13.0, composeBom 2026.06.00→2026.06.01.
**Fix:** bump and re-verify the build stays green; revert any that break.

### L2 — `android.media.ExifInterface` instead of androidx [Low/High] (×5 warnings)
`camera/CameraEngine.kt:528-531`, `capture/DngCapture.kt:6,25`. Lint `ExifInterface`. The `ORIENTATION_*` constants are numerically identical to `androidx.exifinterface.media.ExifInterface`, so switching is value-safe.
**Fix:** add `androidx.exifinterface:exifinterface` and use its constants.

### L3 — `EmptySuperCall` [Low/High]
`ui/CameraViewModel.kt:302` — `super.onCleared()` on a `@EmptySuper` method.
**Fix:** delete the `super.onCleared()` call.

### L4 — `OldTargetApi` (targetSdk 36) [Low/High] — DEFER (policy)
`app/build.gradle.kts:15`. Intentional: `CLAUDE.md` pins targetSdk 36 = Android 16 (the only target device's OS). Bumping to 37 would enable API-37 runtime behavior changes on a device that ships 36. **Deferred** — exit criterion: the device ships a newer OS API level, or a release decision to opt into 37 behaviors.

### L5 — No live exposure readout in Auto [Low/Med] (pro-UX)
`ui/CameraScreen.kt` — in Auto the dial chips show "Auto" with no resolved ISO/shutter/EV. Pixel/Sony surface the metered values. Would require reading AE result state from capture results (device path). **Schedule** as a follow-up (needs `TotalCaptureResult` → UI plumbing).

### L6 — `videoResolution` state vs engine default drift [Low/Med]
`camera/CameraEngine.kt` default `videoSize=1920x1080` while `CameraState.CameraUiState.videoResolution=3840x2160`; `chooseVideoSize()` recomputes on open but never pushes the chosen size back to UI state, so the Video tab selection can mismatch the actual encode size until the user picks one.
**Fix:** report the chosen video size to the ViewModel on caps-ready and sync `videoResolution`.

### L7 — AEB duplicate steps on narrow EV range [Low/Low]
`camera/CameraEngine.kt captureAeb()` — `listOf(-2,0,2).map{coerceIn(range)}` can collapse to identical values on a tight `evRange`, firing 3 identical frames.
**Fix:** `distinct()` the clamped steps (or scale to the available range).

---

## Cross-cutting / already-known (carried from BACKLOG, not re-counted above)
- HLG/Log color is an approximation (honestly labeled) — device-verify. (`Shaders.kt`)
- 10-bit HDR **preview** deferred (HAL rejects HLG10+JPEG+RAW). SDR live session. `[device-verify]`
- Manual WB Kelvin→RGGB is a Tanner-Helland approximation — verify neutral grey at 5200K. `[device-verify]`
- R8/minify disabled for release (deferred, on-device verification required).
- heifwriter `-alpha01` (per "always latest" policy; replace when stable).

---

## Agent findings (folded in from provenance files — net-new beyond H1–L7)

Cross-agent agreement raises signal; IDs kept from source files.

### Crash / ANR / correctness (High)
- **A-TRACE-2a** [High/High] — **Record stop blocks the UI thread up to ~6 s → ANR.** `CameraViewModel.onToggleRecording` → `engine.stopRecording()` → `VideoRecorder.stop()` runs `videoThread.join(3000)` + `audioThread.join(3000)` synchronously on the main thread. **Fix:** move `rec.stop()` teardown off the main thread (e.g. `ioExecutor`), update `isRecording=false` immediately. *(tracer; also perf-adjacent)* — **SCHEDULED this cycle.**
- **A-BUG-1** [High/High] — **Shutter silently does nothing and breaks BURST/AEB chains** when both HEIF+DNG are off or the session fell back to preview-only. `CameraController.capturePhoto` early-`return@post` without invoking the callback, so `onDone` never chains the next shot. **Fix:** call `cb.onError(...)` on the no-target path so the chain continues + a message shows. *(debugger)* — **SCHEDULED this cycle.**
- **A-BUG-4** [Med-High/High] — **Unchecked `AudioRecord` state → crash on the audio thread.** `runAudio` calls `record.startRecording()` without checking `state == STATE_INITIALIZED`; an unusable mic throws uncaught. **Fix:** guard state; degrade to video-only. *(debugger)* — **SCHEDULED this cycle.**
- **A-BUG-2 / A-PERF-2** [Med-High/High] — **`setCameraOverride` runs 6–12 camera-service IPCs + `CameraCaps.read` synchronously on the MAIN thread** (jank + uncaught-throw crash risk). **Fix:** run selection/caps read on `setupExecutor`, hop back for engine mutation. *(debugger + perf; cross-agent agreement)* — **DEFERRED** (needs care to keep the reopen ordering correct; exit: moved off-main + verified no reopen race).
- **A-BUG-3 / A-TRACE-2b** [Med/Med] — **`VideoRecorder.stop()` may release `videoCodec`/`muxer` while a stalled drain thread still touches `muxer!!`** (join has a 3 s timeout, not a guarantee) → race/crash; also races the GL encoder draw. **Fix:** null-guard muxer access in drain loops; ensure `setEncoderOutput(null)` completes before release. *(debugger + tracer)* — **DEFERRED** (device-repro; exit: rapid start/stop stress on device).
- **A-BUG-5** [Med/Med] — **Startup can bind the GL pipeline to a stale `Surface`** if the TextureView surface is recreated during the async `starting` window. *(debugger)* — **DEFERRED** (device-repro).
- **A-TRACE-4** [High/High] — **Rapid shutter taps in SINGLE race the single `pending` slot** → dropped photo, misattributed image, or leaked `Image`. `CameraController` tracks one `pending`; a second `capturePhoto` overwrites it mid-resolve. **Fix:** reject a new capture while one is pending (or queue). *(tracer)* — **DEFERRED** (moderate; exit: guard `pending`-busy + device stress).
- **A-TRACE-3a** [Med/High] — **Switching to PHOTO mode while recording orphans the recording** (shutter becomes photo; no stop affordance). `onModeChange` doesn't stop recording. *(tracer)* — **DEFERRED** (bundle with M1 follow-up).

### Performance (High/Med)
- **A-PERF-1** [High/High-mech] — **Full-preview-res `glReadPixels` (~18 MB at 1440×3168) + copy + GPU sync on the GL thread every 12th frame** for scopes; the per-pixel compute is subsampled but the *readback* is full-res. **Fix:** read back a small downscaled FBO (e.g. 256-wide) or a reduced region. *(perf)* `[device-profiling]` — **DEFERRED** (needs device profiling to size the win; exit: profiled + downscaled readback).
- **A-PERF-3** [Med/High] — **Per-frame `FloatArray` allocation on the GL render thread** (EIS provider returns a new array each frame). **Fix:** reusable buffer via a fill-in-place provider contract. *(perf)* — **DEFERRED** (touches the provider contract across CameraEngine/GyroEis/GlPipeline; exit: contract updated + no per-frame alloc).

### Pro-UX (designer — the run's explicit focus; 30 findings, top ones here)
- **UX-1** [High] — No metered-manual exposure indicator (no M.M. scale / EV needle); histogram off by default. **DEFERRED** (needs metered-EV from `TotalCaptureResult` → state; device-verify). Highest-impact pro gap.
- **UX-2** [High] — No capture confirmation (no shutter animation, flash, or haptic). **PARTIAL this cycle** (shutter press animation + haptic); full flash/thumbnail deferred.
- **UX-3** [High] — HUD reports *requested* not *actual* capture config (fallback ladder silently drops RAW/HLG → status bar lies). **DEFERRED** (needs engine to surface achieved streams to state; exit: `activeFormats/fallbackLevel` on state + amber warning chip).
- **UX-4** [High] — Settings gear unreachable one-handed on a 3168 px screen. **DEFERRED** (swipe-up / bottom-cluster gear; exit: reachable settings entry).
- **UX-5 / M7** [High] — Dead gallery thumbnail; no shot review (critical for chimping focus at 300 mm). **DEFERRED** (exit: thumbnail opens last capture).
- **UX-6 / M8** [High] — Permission dead-end on permanent denial; over-asks RECORD_AUDIO up front. **DEFERRED** to a permissions pass (exit: Settings deep-link + in-context audio request).
- **UX-7** [High] — No loading/hard-error state; camera failure is a transient toast over live controls. **DEFERRED** (exit: `CameraStatus{Initializing,Ready,Error}` + Retry).
- **UX-8** [High] — Zero accessibility semantics (no `contentDescription`/`role`); TalkBack gets nothing; fails Play pre-launch a11y. **DEFERRED** (exit: semantics on every custom control + `strings.xml`).
- **UX-9 / M5** [High] — Touch targets 30–36 dp across chrome/dials/mode/close. **SCHEDULED this cycle** (bump chrome + snapshot + close to ≥44–48 dp; broader dial/mode work deferred).
- **UX-10** [High] — `LensFlipButton` impersonates a camera-flip but is a 3rd duplicate of TELE. **DEFERRED** (repurpose as reachable settings/scopes/review entry).
- **UX-16** [High] — Overlay colors hardcoded off-token; single-tone grid vanishes on bright scenes. **DEFERRED** (dual-tone HUD strokes + token unification).
- **UX-21** [High] — Shutter jumps off-center when the snapshot button appears mid-recording. **DEFERRED** (fixed side-slot for snapshot).
- **UX-22** [High] — Disabled dial chips are still clickable and open inert rulers (EV in manual). **SCHEDULED this cycle** (respect `enabled`; EV hint).
- **UX-25 / M10** [Med] — Overlay rotation animates the long way at 270°→0°. **SCHEDULED this cycle.**
- UX-11..UX-30 (focus magnifier auto-engage, WB dial UX, AE/AF lock indicators, tabular figures, haptic detents, partial-sheet preview, tab-rail glyphs, overflow affordance, battery/storage, reduced-motion, strings.xml, ruler scrim) — **DEFERRED**, all recorded in `designer.md`.

### Test coverage (test-engineer)
- **Infra finding** [High] — JVM unit tests can't read `android.*` getters (`Method … not mocked`); `isReturnDefaultValues=true` returns 0/false/null (useless for value assertions). ⇒ **the correct pattern is to extract pure math with zero android types** (the `FocusMapping` precedent), or add Robolectric where the android type IS the contract.
- **TEST-1** [High] — `CameraSelector2` closest-to-70 mm + standalone tie-break untested. **SCHEDULED this cycle** (extract `pickBest` pure fn + test).
- **TEST-2/3/4** [High] — `previewRotationDegrees` / `captureRotationDegrees` / `exifOrientationFor` untested. **SCHEDULED this cycle** (extract `RotationMath` pure object + test).
- **TEST-5..14** [High/Med] — `GyroEis.currentDeviceOrientation`, `kelvinTintToRggbGains`, `isoStops/shutterStops`, `effectiveExposureNs`, `applyGainAndLevel`, `equivFocalOf`, `fileName` collision, AEB steps. **DEFERRED** (schedule; several need pure-extraction or Robolectric — `fileName` BURST same-second collision is a real latent bug worth its own fix).
- **TEST-13** [Med] — `fileName()` can collide within the same wall-clock second during BURST (duplicate DISPLAY_NAME). MediaStore may append `(1)` but worth a monotonic suffix. **DEFERRED** (exit: uniqueness guard).

### Docs
- **A-TRACE-5a / DOC** [Low/High] — `docs/ARCHITECTURE.md` (~L190-201) still documents the old `-sensorOrientation + 180` preview-rotation formula; code simplified to `if (teleconverterMode) 180 else 0`. **SCHEDULED this cycle** (doc fix).

---

## Notes on what was verified sound (no change needed)
- `CameraController` session fallback ladder, `closed`/`paused` lifecycle guards, `Pending` image close-on-failure, HandlerThread `quitSafely` — all present and correct in code.
- `FocusMapping` is pure and well unit-tested; `kelvinTintToRggbGains` RGGB channel order is correct (R, Geven, Godd, B).
- `saveHeifAsync` bitmap recycle / publish-on-success / delete-on-failure / OOM guard — correct.
- `CameraEngine.startRecording` passing `transfer` to AVC is harmless — `ColorProfiles.avcFormat` ignores it (SDR BT.709); `gl.setTransfer(glTransfer=null)` drives the SDR curve. **Not a bug.**
