# Comprehensive Review — Find X9 Ultra Teleconverter Camera

**Date:** 2026-07-03 · **Scope:** whole app (~7.5k LOC Kotlin, Camera2 + Compose) ·
**Method:** 4 parallel subsystem reviews (camera-core / GL·EIS·video / UI·storage / pro-feature-gap)
cross-verified against a hands-on read of the record→file pipeline.

**Focus of this pass:** (1) professional feature completeness, and (2) robustness of the
**record → encode → mux → MediaStore** pipeline across edge cases (lens switch, settings change
mid-record, exposure longer than the frame interval, force-kill, backgrounding).

Overall: the app is unusually strong for its size — faithful HAL workarounds, a real manual triangle,
RAW/DNG, gyro-EIS scaled to 300 mm, and a full scope suite. The gaps that matter cluster in two
places: **record-pipeline edge-case safety** and **precision focus/exposure feedback** for the
300 mm manual rig.

---

## 1. Record / Save pipeline — edge-case coverage

Verdict legend: ✗ broken/unsafe · ⚠ works-but-fragile · ✓ handled well.

| # | Edge case | Current behavior | Verdict | Sev |
|---|---|---|---|---|
| 1 | **Lens switch while recording** | `CameraEngine.setCameraOverride` closes+reopens the camera but never stops the recorder; UI gates only the shutter, not the Pro sheet. Encoder input surface is left dangling under a torn-down session. | ✗ | HIGH |
| 2 | **fps change crossing high-speed boundary while recording** | `setVideoFrameRate → reopenForSession()` mid-record → same teardown-under-recorder as #1. | ✗ | HIGH |
| 3 | **Open Gate toggle while recording** | `setOpenGate` changes `videoSize` + GL camera size (+ maybe reopen); encoder stays at the old size. | ✗ | HIGH |
| 4 | **Transfer HLG↔Log while recording** | `setTransfer → gl.setTransfer` applies immediately; the encoder MediaFormat is tagged with the *start* transfer → second half of the clip has a different OETF than its container tag. | ✗ | MED |
| 5 | **Resolution change while recording** | `setVideoResolution → gl.setCameraPreviewSize`; encoder fixed → recorded frame scales/letterboxes. | ✗ | MED |
| 6 | **Manual exposure longer than 1/fps** | `applyExposure` sets `SENSOR_FRAME_DURATION = 1e9/fps` whenever `fps>0` and never queries `SENSOR_INFO_MAX_FRAME_DURATION`; per Camera2 the frame duration must be ≥ exposure, so a >1/fps shutter is silently clamped to 1/fps. Long-exposure astro/night is effectively impossible. | ✗ | HIGH |
| 7 | **Force-kill / crash mid-record** | `VideoRecorder.stop()` never runs → MediaMuxer never stopped → **moov atom never written = unplayable MP4**, and the MediaStore row stays `IS_PENDING=1`. Nothing scans/cleans orphaned pending entries on next launch. | ✗ | HIGH |
| 8 | **Background (onStop) while recording** | `CameraEngine.pause()` runs `recorder.stop()` on the **main thread**; `stop()` joins video+audio drains up to ~6 s → ANR risk during `onStop`. (The file does finalize.) | ⚠ | HIGH |
| 9 | **Audio+video A/V sync** | Video PTS = `SurfaceTexture.getTimestamp()` (boot-based sensor clock); audio PTS = 0-based sample counter. No rebase → every audio clip is desynced by a large constant. | ✗ | HIGH |
| 10 | **"10-bit HLG" recording** | GL EglCore is built `tenBit=false` (8-bit), and the encoder input surface shares that context, yet the HEVC format is tagged Main10 / BT2020 / HLG → 8-bit pixels mistagged as 10-bit HDR (banding). | ✗ | HIGH |
| 11 | **Encoder config failure at start** | `rec.start()` returns null → engine reports failure but the pending video MediaStore row is never deleted → 0-byte orphan. | ✗ | MED |
| 12 | **Instant stop (before muxer start)** | `awaitMuxerStart()` returns false → samples skipped → `muxerStarted=false` → uri deleted. No 0-byte file. | ✓ | — |
| 13 | **Rapid stop→start** | `recorder=null` set synchronously; old `stop()` drains on ioExecutor while a new recorder is created on main → two recorders + GL encoder-output posts race. | ⚠ | MED |
| 14 | **Drain join timeout (>3 s)** | After the join times out, `muxer.stop()/release()` can run while the drain thread still calls `addTrack(muxer!!)` → NPE (writeSampleData is guarded, addTrack is not). | ⚠ | LOW |
| 15 | **Encoder EGL teardown vs codec release on stop** | `setEncoderOutput(null)` (GL, async) and `rec.stop()` (ioExecutor) have no handshake → GL can draw to a released input surface (EGL_BAD_NATIVE_WINDOW). | ⚠ | MED |
| 16 | **Audio-track wait back-pressure** | `awaitMuxerStart()` sleeps the video drain until the audio track is added → encoder back-pressure → GL `swapBuffers` blocks → preview freeze at record start. | ⚠ | MED |
| 17 | **AV1 at 4K** | `startRecording` clamps AV1 to ≤1080p/≤30 (encoder self-heals), but UI state still shows 4K with no chip selected (`reconcileResolution` missing). | ⚠ | MED |

**Handled well:** 0-byte prevention (#12), audio-failure degrade-to-video-only, video-setup-failure
cleanup, and EOS-tail preservation in `drainVideo`.

**Root cause of #1–5:** no `recorder != null` guard on any engine video setter, and the UI only gates
the shutter/mode on `isRecording`, not the Pro sheet. During a recording, every video setting and the
lens switcher remain live and reconfigure the camera/GL underneath the encoder.

---

## 2. Severity-ranked findings (whole app)

### HIGH
- **AEB silently no-ops in manual exposure** — `captureAeb` brackets via `exposureCompensation`, which
  the manual branch of `applyExposure` ignores → 3 identical frames. (`CameraEngine.kt:357`,
  `ManualControls.kt:116`)
- **`CameraController.controls` mutated from two threads, non-volatile** — main (ViewModel) + camera
  handler (AEB/BURST `fire()`) → data race, wrong params / lost restore. (`CameraController.kt:388`)
- Record-pipeline #6, #7, #8, #9, #10 above.
- **HLG double-OETF + range mismatch** — HLG OETF applied to an already display-referred preview
  signal and tagged standards-HLG; HLG tagged `COLOR_RANGE_LIMITED` while GL renders full-range.
  (`Shaders.kt`, `ColorProfiles.kt:55`)

### MEDIUM
- Scope readback runs `glReadPixels` **after** `swapBuffers` → undefined buffer → unreliable
  histogram/waveform. (`GlPipeline.kt:224`)
- `setCameraOverride` runs heavy `getCameraCharacteristics` IPC on the **main thread** (jank/ANR),
  unlike the startup path which uses `setupExecutor`.
- Flash AUTO/ON has no AE precapture sequence → mis-metered flash stills.
- "Remember Settings" omits `audioGain`, `driveMode`, `intervalSec`, `recordAudio`, `timer`, and all
  viewfinder assists — contradicts the documented "full pro state".
- High-frequency fields (`levelRoll`, `audioLevel`, scopes, `recordElapsedMs`) live in one
  `CameraUiState` → whole-screen recomposition at ~10 Hz during framing.
- AV1/resolution reconciliation gap (#17): invalid 4K-AV1 UI state reachable.
- `setVideoResolution` can desync session type from `desiredHighSpeedFps()` (no reopen on the boundary
  flip, unlike `setVideoFrameRate`).

### LOW (selected)
- HEIF ignores `jpegQuality` (always 95) and is an 8-bit JPEG re-encode, not the "10-bit-capable"
  container the docs imply.
- Tap-to-focus mapping ignores EIS/punch-in crop, zoom, and 16:9 crop → region lands off-subject at
  300 mm.
- Unused `MediaStoreWriter.savePhotoBytes` leaks a pending row on a null stream (dead code).
- Self-timer countdown can't be cancelled from the shutter.
- Mic-denied-but-recordAudio-ON has no UI cue (degrades to video-only silently).
- EIS roll can rotate corners past the crop guard band → edge smear; per-frame `FloatArray` alloc on
  the GL thread; luma weights sum to 0.9997; `capturePhoto` has no completion timeout (chained
  BURST/AEB/timelapse can stall the pending slot); `pickBest` can pick the HAL-crashing routed path if
  the standalone's equiv focal is unreadable; `close()` racing `onOpened` can leak a CameraDevice
  (narrow window, severe if hit).

### Positive
- Session fallback ladder + standalone-vs-physical RAW/HLG gating faithfully encode the HAL
  constraints, comments preserved.
- Still-capture image lifecycle (`Pending` sync, failure closes images, `done` prevents
  double-delivery) is careful and leak-free on the happy path.
- Manual WB uses `COLOR_CORRECTION_MODE_FAST` + gains (AWB_OFF), RGGB order correct.
- HLG OETF *curve* math matches Rec.2100 (issue is input domain, not the curve).
- EIS magnitude scaling is dimensionally correct (×300/70 at the effective FoV).
- Defensive settings load, atomic filename sequence, `rememberUpdatedState` for the long-lived
  surface listener.

---

## 3. Feature completeness gaps (for "most complete")

Benchmarked against mcpro24fps / ProCam / Halide / Blackmagic / FiLMiC and the specific 300 mm
manual-focus afocal use case (moon / wildlife / astro).

**P0**
- **In-app review + 100% pixel-zoom on last shot** — `GalleryThumbPlaceholder` is a dead placeholder;
  you cannot confirm critical focus before the subject moves. (`CameraScreen.kt:628`)
- **Movable focus loupe** — punch-in is a fixed center 2.5× crop, not tied to `tapPoint`; useless for
  an off-center subject at 300 mm. (`GlPipeline.kt:219`, `setPunchIn` takes no position)

**P1**
- Live "what AE chose" readout (ISO/shutter/EV) — values are read then only `Log.i`'d.
  (`CameraController.kt:318`)
- Log→Rec.709 monitoring LUT — Log is monitored as a flat SDR image.
- Bulb / long-exposure via sub-exposure stacking (also blocked by pipeline #6).
- Adjustable peaking (level+color) and zebra (%/dual); numeric focus-distance + hyperfocal readout
  (`hyperfocalDiopters` is read but never used).
- Teleconverter-specific: clamp-centering + corner-vignette check; moon framing + lunar exposure
  preset — unique differentiators for this product.
- Spot-meter numeric readout; fix AEB to bracket by shutter/ISO in manual + configurable count/step.

**P2**: focus bracketing/stacking, configurable burst depth, timelapse→assembled clip, custom WB from
gray card, geotagging, custom folder/filename, storage/free-space readout, 2-axis level, All-I (GOP)
option, RGB parade/vectorscope, mic direction/source options.

---

## 4. Test & environment status

- **JDK** was missing from the machine (documented `openjdk@21` path gone) — reinstalled.
- **Android SDK** was missing (no `ANDROID_HOME`, no `local.properties`, no `~/Library/Android/sdk`) →
  even JVM unit tests fail because `:app` won't compile against `android.jar`. Installed
  cmdline-tools + platform-37/36 + build-tools and wrote `local.properties`.
- New unit test `ExposureMathTest.kt` (9 cases: cine-angle↔speed, fps=0 guard, angle clamp, and the
  "exposure longer than the frame interval" invariant).
- Device verification (record→file at each codec/res/fps, lens switch, long exposure, force-kill) is
  pending a live wireless-ADB port (changes per session) and a fresh build.

Pure-logic pipeline decisions (frame duration vs exposure, AV1 clamp, orientation-hint normalization,
publish-vs-delete) are currently entangled with Android types; extracting them into pure helpers is
the path to maximal deterministic coverage and is bundled with the fixes below.

---

## 5. Prioritized remediation plan

1. **Record-pipeline P0** — lock/stop settings & lens changes during recording (#1–5); orphan-pending
   cleanup on launch (#7); move `pause()`'s `recorder.stop()` off the main thread (#8); rebase A/V
   timestamps (#9); long-exposure frame-duration fix (#6); delete the pending uri on start failure
   (#11). Extract pure helpers + unit tests; device-verify each.
2. **AEB-in-manual + controls thread-safety** (the two non-pipeline HIGHs).
3. **10-bit HDR / color accuracy** — decouple the encoder EGL to true 10-bit (#10); HLG range/OETF
   correctness (#4); Log→709 monitoring LUT.
4. **Pro feature P0/P1** — in-app review + pixel zoom; movable loupe; live AE readout; peaking/zebra
   tuning; numeric focus/hyperfocal readout.
5. **MEDIUM hygiene** — scope-readback-before-swap; `setCameraOverride` off main; persistence
   completeness; recomposition split; AV1/resolution reconcile.

Each camera/GL/orientation fix must be confirmed on the physical device (compilation ≠ correct
pixels), per `CLAUDE.md`.
