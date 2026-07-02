# Aggregate Deep Review — Cycle 1 (2026-07-03)

**Scope:** Full inline review of all 29 Kotlin sources under `app/src/main/kotlin/**`, `app/src/test/**`, `AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`. Judged against the run goal: *perfect this as a professional camera app for photographers migrating from Sony (BIONZ XR2) and Google Pixel Camera — pro ergonomics, correct manual controls, discoverability, polish.*

**Method note (AGENT FAILURES):** The planned multi-agent review fan-out (code-reviewer, perf-reviewer, security-reviewer, critic, verifier, test-engineer, tracer, architect, debugger, document-specialist, designer) was spawned but the nested background agent batch did not survive in this environment and wrote zero files. Per the orchestrator's adaptation, the review was performed **inline** by the cycle lead covering every specialist angle. Findings below are consolidated (no per-agent provenance files this cycle). The prior cycle's per-angle files (`architect.md`, `code-reviewer.md`, `perf-reviewer.md`, `security-reviewer.md`) are retained unchanged for history.

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

## Notes on what was verified sound (no change needed)
- `CameraController` session fallback ladder, `closed`/`paused` lifecycle guards, `Pending` image close-on-failure, HandlerThread `quitSafely` — all present and correct in code.
- `FocusMapping` is pure and well unit-tested; `kelvinTintToRggbGains` RGGB channel order is correct (R, Geven, Godd, B).
- `saveHeifAsync` bitmap recycle / publish-on-success / delete-on-failure / OOM guard — correct.
- `CameraEngine.startRecording` passing `transfer` to AVC is harmless — `ColorProfiles.avcFormat` ignores it (SDR BT.709); `gl.setTransfer(glTransfer=null)` drives the SDR curve. **Not a bug.**
