# Aggregate Deep Review — Cycle 2 (2026-07-03)

**Scope:** Full inline re-review of all 29 Kotlin sources under `app/src/main/kotlin/**` plus
`app/src/test/**`, done directly by the cycle-2 lead (no background agent fan-out — the environment
kills large nested review swarms). Judged against the run goal: *perfect this as a professional
camera app for photographers migrating from Sony (BIONZ XR2) and Google Pixel Camera — pro
ergonomics, correct manual controls, discoverability, polish.*

**Provenance:** Cycle 1 landed 21 commits fixing the gate + a crash/ANR/pro-UX subset and DEFERRED a
large remainder (see `docs/plans/2026-07-03-cycle1-fixes.md`). Cycle-1 findings kept their IDs
(H*/M*/L*/A-*/UX-*/TEST-*). Cycle-2 net-new / re-validated findings are prefixed **C2-**. Items
already fixed in cycle 1 are NOT re-listed except where re-validated as still-open.

**Gate baseline (this cycle, measured):** `:app:assembleDebug` ✅ · `:app:testDebugUnitTest` ✅
(26 tests) · `:app:lintDebug` ✅ **0 errors, 9 warnings**. Gate is GREEN at cycle start.

Severity: **High** = crash/gate-red/data-loss/corrupt output/pro-blocking correctness · **Medium** =
wrong behavior, pro-UX gap, state desync · **Low** = polish/hygiene/warnings.
Confidence: High / Med / Low. Device-only items flagged `[device-verify]` (AVD has synthetic camera
hardware — no tele/RAW/HLG/EIS/mic-through-converter).

---

## Cycle-2 summary table (net-new + re-validated open)

| ID | Sev | Conf | Area | One-liner |
|----|-----|------|------|-----------|
| C2-1 | Med | High | data/naming | `fileName()` second-resolution stamp collides in BURST/AEB/timelapse → overwritten/duplicate files |
| C2-2 (=M2) | Med | High | pro-UX | No settings persistence across process death (Sony/Pixel remember settings) |
| C2-3 (=A-TRACE-4) | Med | Med | camera race | Rapid shutter overwrites the single `pending` slot → dropped/misattributed/leaked Image |
| C2-4 (=A-TRACE-3a) | Med | High | lifecycle | Switching to PHOTO mode while recording orphans the recording (no stop affordance) |
| C2-5 | Low | High | pro-audio | Audio source + sample rate hardcoded (CAMCORDER/48k) — no pro audio options |
| C2-6 (=A-PERF-3) | Med | High | perf | Per-frame `FloatArray` alloc on the GL render thread (EIS provider) → GC pressure |
| C2-7 | Low | Med | pro-UX | Assist toggles (peaking/zebra/…) set before GL start are silently lost (no-op `post`) |
| C2-8 (=UX-4) | High | Med | discoverability | Settings gear only in the top chrome bar — hard to reach one-handed at 3168 px |
| C2-9 (=UX-5/M7) | High | Med | pro-UX | Dead gallery thumbnail; no last-shot review (chimping focus at 300 mm) |
| C2-10 (=UX-8) | High | Med | a11y | Zero accessibility semantics on custom controls; fails Play a11y |
| C2-11 (=UX-3) | High | Med | pro-UX | HUD reports requested-not-actual capture config (fallback ladder silently drops RAW/HLG) |
| C2-12 (=UX-1) | High | Med | pro-UX | No metered-manual exposure indicator (M.M./EV needle) `[device-verify]` |
| C2-13 (=M6) | Med | High | pro-UX | No pinch-to-zoom on the viewfinder |

Net-new this cycle: **C2-1, C2-5, C2-6-revalidated, C2-7** (4 genuinely new); the rest are cycle-1
deferred items re-validated against source as still-open and re-prioritized.

---

## NET-NEW findings (validated from source this cycle)

### C2-1 — `fileName()` collides within one wall-clock second → lost/duplicate captures [Med/High]
`camera/CameraEngine.kt:575-578` — `fileName()` formats `yyyyMMdd_HHmmss` (1-second resolution).
BURST fires `BURST_COUNT = 5` stills and AEB fires up to 3, all chained within well under one second,
so every frame in the burst gets the **same** `DISPLAY_NAME` (e.g. `IMG_X9TELE_20260703_142530.heic`).
TIMELAPSE at `intervalSec` ≥ 1 s is usually safe but a 1 s interval can also collide.
**Failure scenario:** a 5-frame burst produces one file plus MediaStore-appended `(1)…(4)` *iff* the
provider dedups; if it doesn't (or for the DNG+HEIF of different frames), frames overwrite or clobber
each other → the photographer loses shots from the burst. This is the cycle-1 **TEST-13** latent bug,
now confirmed and promoted to a fix.
**Fix:** extract a pure `capture/CaptureNaming` object with a process-monotonic counter (or ms + a
short suffix) so consecutive names are unique; unit-test uniqueness across a simulated burst.
Confidence High.

### C2-5 — Audio source and sample rate hardcoded — no pro audio options [Low/High] `[device-verify audio]`
`video/VideoRecorder.kt:199` pins `MediaRecorder.AudioSource.CAMCORDER`; `video/ColorProfiles.kt:26`
pins `AUDIO_SAMPLE_RATE = 48_000` and `AUDIO_BIT_RATE = 192_000`. The Video tab exposes only a
Record-Audio toggle and a software gain slider.
BACKLOG explicitly lists "more pro audio options in settings." Pros want to pick the input route —
CAMCORDER (zoom-aware, default), MIC (raw), UNPROCESSED (no AGC/NS, best for external processing),
VOICE_RECOGNITION — and a sample rate (48 kHz vs 44.1 kHz for delivery matching).
**Fix:** add `AudioSource` + `AudioSampleRate` enums, plumb UI → state → engine → `VideoRecorder`
(AudioRecord source + rate) → `ColorProfiles.aacFormat(rate)`. Use the selected rate consistently for
`getMinBufferSize`, the `AudioRecord` ctor, and the PTS math. Build-verifiable; actual capture is
device-verify. Confidence High (implementation), device-verify (audio quality).

### C2-6 — Per-frame `FloatArray` allocation on the GL render thread [Med/High]  (re-validated A-PERF-3)
`camera/CameraEngine.kt:116` `gl.setEisProvider { gyro.currentCorrection() }` and
`stab/GyroEis.kt:63` `currentCorrection(): FloatArray = floatArrayOf(corrYaw, corrPitch, corrRoll)`.
The provider is invoked once per rendered frame (30–60 Hz) and allocates a fresh 3-element array each
time on the **latency-critical GL thread**, creating steady short-lived garbage → periodic GC that can
cause preview hitches, worst exactly when EIS matters (hand-held 300 mm).
**Fix:** convert the provider to fill-in-place — GL owns a reusable `FloatArray(3)`, passes it to a
`GyroEis.fillCorrection(out)`; no per-frame alloc. Confidence High.

### C2-7 — Viewfinder-assist toggles set before GL start are silently dropped [Low/Med]
`gl/GlPipeline.kt:368` `post` = `handler?.post{…}` (null-safe no-op until `start()`), and
`camera/CameraEngine.kt` forwards `setPeaking/setZebra/setFalseColor/setPunchIn/setAnalysis/setTransfer`
straight to GL with no engine-side memory. Any assist toggled before the preview surface arrives (and,
critically, any assist **restored from persisted settings at ViewModel init** — see C2-2) is lost
because the GL handler doesn't exist yet, and nothing re-applies it once GL starts.
**Fix:** hold the assist/transfer state as engine `@Volatile` fields and re-apply them to GL in the
`gl.start{…}` onInputReady callback (next to `applyStabilization()`), so toggles made before start —
and restored settings — take effect. Also unblocks C2-2 restore. Confidence Med-High.

---

## RE-VALIDATED cycle-1 deferred (still open, source-confirmed)

### C2-2 = M2 — No settings persistence across process death [Med/High] (pro-UX flagship)
`ui/CameraViewModel.kt:42` seeds `MutableStateFlow(CameraUiState())`; every field is in-memory. A cold
start resets ISO/shutter/WB/format/EIS/grid/drive/assists/audio to defaults. Sony and Pixel both
persist shooting settings between sessions.
**Fix:** pure `settings/PersistedSettings` (enums + primitives only, no `android.*`) + pure
`SettingsCodec` (`toMap`/`fromMap`, robust to missing/garbage keys), unit-tested; an android
`SettingsStore` (SharedPreferences) bridge; a **"Remember settings"** toggle (default ON) in the pro
sheet; restore in VM `init` and debounced save on change. Restore of GL-thread assists depends on C2-7.
Confidence High. AVD-verifiable (unit round-trip + relaunch renders restored state).

### C2-3 = A-TRACE-4 — Rapid shutter overwrites the single `pending` slot [Med/Med]
`camera/CameraController.kt:322` — `capturePhoto` unconditionally assigns `pending = Pending(...)`. A
second tap posted before the first capture resolves its Images overwrites `pending`, so the first
capture's `onImage` finds a mismatched slot → the first shot is dropped/misattributed and its acquired
`Image` can leak the reader (maxImages=2). BURST/AEB chaining is NOT affected (the chain posts its next
shot from inside `onPhoto`, which runs before `tryComplete`'s `finally` sets `pending = null`, so by the
time the posted capture runs the slot is free — verified by reading the completion order).
**Fix:** reject a new `capturePhoto` when `pending != null` via `cb.onError("capture in progress")`.
Device-stress final verify. Confidence Med (mechanism High; timing device-only).

### C2-4 = A-TRACE-3a — Mode→PHOTO while recording orphans the recording [Med/High]
`ui/CameraViewModel.kt:150` `onModeChange` only cancels the countdown and sets `mode`. If the user
switches to PHOTO while `isRecording`, the recorder keeps running but the shutter becomes a photo
shutter — there is no stop affordance and the clip only finalizes on background/exit.
**Fix:** in `onModeChange`, if leaving VIDEO while `isRecording`, stop recording first (mirror
`onToggleRecording`'s stop path). AVD-verifiable (pure UI logic). Confidence High.

### C2-8 = UX-4 — Settings gear reachable only in the top chrome bar [High/Med] (discoverability)
`ui/CameraScreen.kt` mounts the gear in the top bar; on a 1440×3168 panel that is a long thumb-stretch
one-handed. **DEFERRED** (needs a reachable bottom/edge entry or swipe-up; larger layout change).
Exit: a settings entry reachable in the bottom thumb zone.

### C2-9 = UX-5/M7 — Dead gallery thumbnail; no last-shot review [High/Med]
`ui/CameraScreen.kt` `GalleryThumbPlaceholder` is decorative/unwired. **DEFERRED** (needs last-saved
URI tracking + `ACTION_VIEW`; own feature). Exit: thumbnail opens the last capture.

### C2-10 = UX-8 — Zero accessibility semantics [High/Med]
No `contentDescription`/`role`/`stateDescription` on the custom Canvas/Box controls; TalkBack gets
nothing; fails Play pre-launch a11y. **DEFERRED** (broad pass across every control + `strings.xml`).
Exit: semantics on every interactive control; a11y scan clean.

### C2-11 = UX-3 — HUD reports requested-not-actual capture config [High/Med]
The fallback ladder in `CameraController.configureSession` can silently drop RAW/HLG, but the UI still
shows them enabled. **DEFERRED** (engine must surface achieved streams to state + an amber warning
chip). Exit: `activeFormats`/`fallbackLevel` on state, warning shown.

### C2-12 = UX-1 — No metered-manual exposure indicator [High/Med] `[device-verify]`
No M.M. scale / EV needle; the manual shooter is flying blind vs the meter. **DEFERRED** (needs metered
EV from `TotalCaptureResult` → state; device path). Exit: needle driven by metered delta.

### C2-13 = M6 — No pinch-to-zoom on the viewfinder [Med/High]
`ui/CameraScreen.kt` wires only `detectTapGestures → onTapFocus`; zoom lives in a buried slider.
**DEFERRED** (needs `detectTransformGestures` composed with tap-to-focus without gesture conflict).
Exit: two-finger zoom clamped to `caps.zoomRatioRange`, coexisting with tap-focus.

---

## Still-deferred from cycle 1 (unchanged severity; see cycle-1 plan for full exit criteria)
- **A-BUG-2/A-PERF-2** [Med-High/High] — `setCameraOverride` runs camera-service IPC + `CameraCaps.read`
  on the MAIN thread. Off-main move needs care to preserve reopen ordering. **DEFERRED.**
- **A-BUG-3/A-TRACE-2b** [Med/Med] — VideoRecorder teardown vs stalled drain-thread `muxer!!` race.
  **DEFERRED** (device rapid start/stop stress). Note: writes already `runCatching`-guard `muxer?`.
- **A-BUG-5** [Med/Med] — stale-Surface bind in the async startup window. **DEFERRED** (device-repro).
- **A-PERF-1** [High/High-mech] — full-res scope `glReadPixels` (~18 MB) each analysis frame.
  **DEFERRED** (needs device profiling + downscaled-FBO readback).
- **UX-7** [High] — no loading/hard-error state (`CameraStatus{Initializing,Ready,Error}` + Retry).
  **DEFERRED.**
- **UX-10** [High] — `LensFlipButton` duplicates TELE; repurpose as settings/scopes/review entry.
  **DEFERRED** (pairs with C2-8).
- **UX-16** [High] — overlay colors off-token; single-tone grid vanishes on bright scenes. **DEFERRED.**
- **UX-21** [High] — shutter re-centers when the snapshot button appears mid-record. **DEFERRED.**
- **UX-11..UX-30 (remainder)** [Med/Low] — focus-magnifier auto-engage, WB dial inline, AE/AF lock
  indicators + long-press lock, tabular figures, haptic detents, partial-sheet preview, tab-rail glyph
  legibility, overflow affordance, battery/storage readout, reduced-motion, ruler scrim. **DEFERRED.**
- **TEST-5..12,14** [High/Med] — pure-extraction tests for `GyroEis.currentDeviceOrientation`,
  kelvin→RGGB, iso/shutter stops, `effectiveExposureNs`, `applyGainAndLevel`, `equivFocalOf`, AEB steps.
  **DEFERRED** (progressively; C2-1 lands the `CaptureNaming`/TEST-13 slice this cycle).
- **M3** [Med/Med] — tap-to-focus EIS/punch-in crop inverse. **DEFERRED** device-only sign/axis.
- **M4** [Med/Med] — 300 mm gyro-EIS axis/sign tuning. **DEFERRED** device-only.
- **H2 sign** [High/Med] — muxer orientation-hint sign. Plumbing shipped cycle 1; **DEFERRED** device-only.
- **L4** [Low/High] — `OldTargetApi` targetSdk 36 — **DEFERRED by CLAUDE.md policy** (targetSdk pinned
  to the device OS; quoted in the plan).
- **L5** [Low/Med] — live exposure readout in Auto. **DEFERRED** (needs `TotalCaptureResult` plumbing;
  overlaps C2-12).

---

## Security / correctness sweep (this cycle)
- Permissions: CAMERA + RECORD_AUDIO requested at runtime; permanent-denial deep-link present. No new
  gaps. Adding pro `AudioSource` options does **not** add permissions (all covered by RECORD_AUDIO).
- Storage: scoped MediaStore with `IS_PENDING`, publish-on-success / delete-on-failure — sound. C2-1 is
  a naming-uniqueness issue, not a scoped-storage issue.
- No secrets, no network, no unsafe reflection beyond the documented heifwriter alpha suppression.
- `@Volatile` seams between UI/GL/camera threads verified consistent for the fields touched this cycle.

## Verified sound (no change needed)
- Cycle-1 fixes hold: capture callback on no-target path, `AudioRecord` STATE guard (both `startAudio`
  and `runAudio`), empty-MP4 delete, record-stop off the main thread, recording UI sync on background,
  muxer orientation-hint plumbing, RotationMath + CameraSelector2 extraction & tests, 48 dp touch
  targets, shortest-path HUD rotation, permission Settings deep-link.
- `CameraController` fallback ladder, `closed`/`paused` guards, `Pending` close-on-failure,
  HandlerThread `quitSafely` — correct.
- `FocusMapping`, `kelvinTintToRggbGains` (R,Geven,Godd,B order), `saveHeifAsync` recycle/publish/OOM —
  correct.
