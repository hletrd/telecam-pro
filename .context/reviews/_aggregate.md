# Aggregate Deep Review — Run-2 Cycle 1 (2026-07-06)

**Scope:** Full inline re-review of all 31 Kotlin sources under `app/src/main/kotlin/**` + the 4 JVM
test suites + `AndroidManifest.xml`, done directly by the cycle lead (no background agent fan-out —
the environment kills nested review swarms). One synchronous helper (`code-reviewer`) covered the
~2900 LOC Compose UI layer; its findings are merged here and preserved in `designer.md` /
`code-reviewer.md`.

**Provenance:** The prior `_aggregate.md` was Run-1 Cycle-2 (findings `C2-*`, `H*/M*/UX-*/TEST-*`).
Since then several of its flagship items shipped and are now **CLOSED** (verified in source this
cycle) — see the CLOSED list below. This cycle keeps stable IDs prefixed **`R1-`** (net-new or
re-validated-still-open). Where an item maps to a prior ID it is noted `(=C2-x)`.

**Gate baseline (measured this cycle):** `:app:assembleDebug` ✅ · `:app:testDebugUnitTest` ✅
(37 tests) · `:app:lintDebug` ✅ **0 errors, 9 warnings** (`OldTargetApi` L4 deferred-by-policy,
AGP 9.2.0→9.2.1 hint, heifwriter -alpha01, a few Compose hints). Gate is GREEN at cycle start.

Severity: **High** = crash / gate-red / data-loss / corrupt output / pro-blocking correctness ·
**Medium** = wrong behavior, pro-UX gap, state desync · **Low** = polish / hygiene / docs.
Confidence: High / Med / Low. Device-only items flagged `[device-verify]` (AVD has a synthetic
camera — no tele/RAW/HLG/EIS/mic-through-converter).

---

## CLOSED since Run-1 Cycle-2 (verified in source this cycle)
- **C2-1 fileName() burst collision** — CLOSED. `CameraEngine.fileName()` now stamps
  `yyyyMMdd_HHmmss_SSS` + a process-monotonic `AtomicInteger` sequence (`CameraEngine.kt:703-709`).
- **C2-2 settings persistence** — CLOSED. `storage/SettingsStore.kt` + `ExtraSettings` + a
  "Remember settings" toggle (default ON), restored in `CameraViewModel.init` and saved on
  `onStop` (`CameraViewModel.kt:98-157, 386-411`).
- **C2-4 mode→PHOTO while recording orphans the clip** — CLOSED. `onModeChange` stops recording
  first when leaving VIDEO (`CameraViewModel.kt:217-223`).
- **L7 AEB duplicate frames** — CLOSED (`.distinct()` on clamped EV steps, `CameraEngine.kt:361`).

---

## Cycle summary table (net-new + re-validated open)

| ID | Sev | Conf | Area | One-liner | Disposition |
|----|-----|------|------|-----------|-------------|
| R1-1 (=C2-3) | Med | High | camera race | `CameraController.capturePhoto` has no busy guard → a 2nd shutter tap overwrites the single `pending` slot, dropping/misattributing the 1st shot and leaking its `Image` | SCHEDULE |
| R1-2 (=C2-6) | Med | High | perf/GC | Per-frame `FloatArray` alloc on the GL render thread (`eisProvider` → `GyroEis.currentCorrection()`) | SCHEDULE |
| R1-3 (=UI-1) | High | Med | UI crash | Degenerate `Slider` valueRange (ISO `400..400` before caps) → NaN thumb fraction → `roundToInt` throws | SCHEDULE |
| R1-4 (=UI-5) | Med | Med | UI state | AV1 codec / Open-Gate toggle doesn't reconcile `videoResolution` → no chip selected + bogus "4K AV1" encoder summary | SCHEDULE |
| R1-5 (=UI-6) | Low-Med | High | i18n | Locale-sensitive `String.format` on machine-format readouts (timecode/focal/shutter/zoom/EV) | SCHEDULE |
| R1-6 (=UI-7) | Low | Med | UI crash | `drawWaveform` indexes `bins[col*rows+row]` without checking `bins.size` → AIOOBE on a resize race | SCHEDULE |
| R1-7 (=UI-3) | Med | High | UX/touch | Touch targets < 48 dp: `ModeLabel` (~25 dp), `TeleChip` (36 dp), `DialChip` (~38 dp) | SCHEDULE |
| R1-8 (=UI-4) | Med | High | UX/a11y | `DialChip.enabled` is cosmetic — the greyed EV chip in manual is a fully-clickable no-op & mis-announced to TalkBack | SCHEDULE (EV-chip slice) |
| R1-9 (=DOC-1) | Low | High | docs | `VideoRecorder` class KDoc says "NO MediaMuxer orientation hint is set"; the code sets one | SCHEDULE |
| R1-10 (=UI-10) | Low | High | docs | `Overlays.AspectMask` KDoc references the removed `AspectRatio.FULL` | SCHEDULE |
| R1-11 (=UI-2) | Med-High | High | perf | `CameraUiState` is unstable & passed whole to `TopBar`/`ManualDialCluster`/`ProSheet` → every telemetry tick recomposes them | DEFER |
| R1-12 (=UI-11) | Low | Med | perf | `VideoTab` recomputes/re-allocates option lists every recomposition | DEFER |
| R1-13 (=UI-8) | Low | High | maintainability | `RulerSlider` unkeyed `remember` + snapshot-state write during composition | DEFER |
| R1-14 (=UI-9) | Low | Med | maintainability | `onFractionChange` captured in `pointerInput` without `rememberUpdatedState` (latent trap) | DEFER |
| R1-15 | Low | Med | UX | Top-bar `GridButton` toggles only `NONE⇄THIRDS`, silently discarding a sheet-chosen GOLDEN/SQUARE/CENTER | DEFER |

Genuinely net-new this cycle: **R1-3, R1-4, R1-5, R1-6, R1-9, R1-10, R1-15**. Re-validated
still-open from prior cycles: **R1-1, R1-2, R1-7, R1-8, R1-11..R1-14**.

---

## SCHEDULED findings (detail)

### R1-1 (=C2-3) — `capturePhoto` has no in-flight guard → rapid double-tap corrupts a capture [Med/High]
`camera/CameraController.kt:413-457`. `capturePhoto` unconditionally assigns `pending = Pending(...)`
(`:426`). A second shutter tap that lands before the first capture resolves its images overwrites
`pending`; the first capture's `onImage`/`onCaptureCompleted` then find a mismatched slot → the first
shot is dropped/misattributed and any `Image` it already acquired leaks (`ImageReader` `maxImages=2`,
so two leaks starve the reader and freeze still capture).
**Why BURST/AEB are NOT affected:** their next shot is fired from inside `onPhoto` (→ `onDone`), which
runs inside `tryComplete`'s `try` *before* the `finally` sets `pending = null` — but `capturePhoto`
`handler.post {…}` only *queues* the next capture, which the handler runs *after* the current
`tryComplete` returns, i.e. after `pending` is cleared. Verified by tracing the completion order.
**Fix:** at the top of the posted body, if `pending != null` call `cb.onError("capture already in
progress")` and return — rejects a genuine rapid double-tap while leaving BURST/AEB chains intact.
Confidence High (mechanism); device-stress final verify.

### R1-2 (=C2-6, A-PERF-3) — per-frame `FloatArray` alloc on the GL thread [Med/High]
`camera/CameraEngine.kt:118` `gl.setEisProvider { gyro.currentCorrection() }` and
`stab/GyroEis.kt:69` `currentCorrection(): FloatArray = floatArrayOf(corrYaw, corrPitch, corrRoll)`.
The provider is invoked once per rendered frame (30–60 Hz) in `GlPipeline.drawFrame` (`:209`),
allocating a fresh 3-element array each time on the latency-critical GL thread → steady short-lived
garbage → periodic GC that can hitch the preview exactly when EIS matters (hand-held 300 mm).
`GlPipeline` already documents the alloc and defers the fix to the provider owner (`:205-208`).
**Fix:** fill-in-place contract. Add `GyroEis.fillCorrection(out: FloatArray)`; change
`GlPipeline.eisProvider` to `((FloatArray) -> Unit)?` with a GL-owned reusable `FloatArray(3)`; wire
`CameraEngine` to `gl.setEisProvider { out -> gyro.fillCorrection(out) }`. Zero per-frame alloc; math
identical. Confidence High.

### R1-3 (=UI-1) — degenerate `Slider` valueRange → NaN thumb fraction → crash risk [High/Med]
`ui/controls/ProControls.kt:177-184` (`LabeledSlider`) fed by `ui/controls/ProSheet.kt:391-399` — ISO
uses `isoRange = caps?.isoRange ?: Range(controls.iso, controls.iso)`, so before the camera opens
`caps == null` ⇒ `valueRange = 400f..400f` (start == endInclusive). Material3 `Slider` computes
`fraction = (value-start)/(end-start) = 0f/0f = NaN`; thumb placement does `(track*fraction)
.roundToInt()`, and `Float.roundToInt()` throws `IllegalArgumentException("Cannot round NaN value.")`.
A disabled slider still lays out its thumb, so `autoExposure=true` (ISO disabled) doesn't protect it.
The Zoom slider (`ProSheet.kt:317-324`) is only non-null-guarded, not `lower < upper`-guarded.
**Failure:** cold launch → open the gear before caps arrive → Exposure/Color tab → ISO row → crash or
mis-placed thumb. **Fix:** in `LabeledSlider`, when `valueRange.endInclusive <= valueRange.start`
render a static/disabled full-track row instead of an interactive `Slider` (protects every call site).
Confidence Med-High; device-confirm the exact Material3 behavior.

### R1-4 (=UI-5) — AV1 / Open-Gate toggle leaves `videoResolution` out of range [Med/Med]
`ui/CameraViewModel.kt:242-268` — `onVideoCodec` and `onToggleOpenGate` call `reconcileFrameRate()`
but never reconcile `videoResolution`. Picking AV1 filters `resolutionOptions` to `width <= 1920`
(`ProSheet.kt:512`) while `state.videoResolution` can still be `3840×2160`, so (1) no resolution chip
is highlighted and (2) the summary computes `videoBitRate(3840, 2160, …)` and displays "AV1 · 4K · N
Mbps" for a config AV1 is explicitly gated against. Same mismatch toggling Open Gate (options flip to
4:3 while the selection stays 16:9). **Fix:** after `onVideoCodec`/`onToggleOpenGate`, clamp
`videoResolution` to the first entry of the newly-valid option set (or drive the summary from an
in-range size). Confidence Med (needs a device glance at the engine's `onVideoSizeChosen`).

### R1-5 (=UI-6) — locale-sensitive number formatting on machine-format readouts [Low-Med/High]
`ManualDials.kt:164,187,279,418`; `ProSheet.kt:319,421,428,548-554`; `ProControls.kt:412-417,428`;
`Overlays.kt:203,276-277`. Every `"%.1fx"/"%+.1f"/"%.0f°"/"%02d:%02d"/"%.0fmm".format(...)` resolves
the default locale → German shows `1,5x`, Arabic/Farsi render Arabic-Indic digits. Display-only (never
parsed back) so no crash, but for a worldwide Play release the machine values (timecode, focal,
shutter, K, EV) should be locale-stable. **Fix:** `String.format(Locale.US, …)` for those. Confidence
High.

### R1-6 (=UI-7) — `drawWaveform` unchecked `bins` length → AIOOBE [Low/Med]
`ui/overlays/Overlays.kt:349-371`. `WaveformOverlay` guards only `columns>0 && rows>0` before indexing
`bins[col*rows+row]` across the whole grid; if a producer ever emits `bins.size != columns*rows` (a
resize race between GL analysis config and readback) it throws `ArrayIndexOutOfBoundsException` on the
UI thread. **Fix:** add `if (data.bins.size < data.columns * data.rows) return` to the guard.
Confidence Med.

### R1-7 (=UI-3) — primary touch targets below 48 dp [Med/High]
`CameraScreen.kt:576-600` (`ModeLabel` Photo/Video, ~24-26 dp tall), `:517-531` (`TeleChip`, 36 dp);
`ManualDials.kt:195-225` (`DialChip`, ~38 dp). The most-used control (mode switch) and the TELE chip
miss the WCAG/Material 48 dp minimum on a 3168 px panel → frequent one-handed mis-taps. (Chrome
buttons/snapshot/lens-flip are already 48-52 dp — keep that pattern.) **Fix:** add
`Modifier.sizeIn(minWidth=48.dp, minHeight=48.dp)` to the clickable node, visual size unchanged.
Confidence High.

### R1-8 (=UI-4) — `DialChip.enabled` cosmetic; greyed EV chip is a clickable no-op [Med/High]
`ManualDials.kt:195-225` always applies `.clickable(onClick)` regardless of `enabled` (which only
tints). Focus/Shutter/ISO greyed chips are *intentionally* actionable (a tap flips to Manual), but the
EV chip in manual (`:116`) is a pure no-op that still consumes the tap and is announced by TalkBack as
an enabled button. **Fix (this cycle):** make the EV chip non-clickable when `!autoExposure`
(`clickable(enabled=…)`) + `Modifier.semantics { disabled() }`; broader a11y semantics pass stays
deferred. Confidence High.

### R1-9 (=DOC-1) — `VideoRecorder` KDoc contradicts the code [Low/High]
`video/VideoRecorder.kt:28-30` class KDoc: "therefore NO MediaMuxer orientation hint is set." But
`start()` calls `muxer?.setOrientationHint(...)` (`:88-90`) — the hint was added for landscape holds.
Per the repo's "keep the why-comments accurate" rule, fix the KDoc to describe the device-orientation
hint. Confidence High.

### R1-10 (=UI-10) — `AspectMask` KDoc references removed `AspectRatio.FULL` [Low/High]
`ui/overlays/Overlays.kt:110-118` docs say "non-[AspectRatio.FULL]" / "Draws nothing for
[AspectRatio.FULL]", but the enum is only `W4_3`/`W16_9` and the code early-returns on `W4_3`. Broken
doc links. **Fix:** reference `W4_3` as the no-crop case. Confidence High.

---

## DEFERRED findings (recorded — reason + exit criterion; severity/confidence NOT downgraded)

Deferral basis per repo policy: `CLAUDE.md` mandates on-device verification for camera/GL/orientation
behavior ("Compilation ≠ correct pixels"), pins targetSdk, and favors focused fine-grained changes;
none of the deferred items below are security, correctness, or data-loss defects (those — R1-1, R1-3,
R1-6 — are all SCHEDULED). Perf/maintainability/device-tuning deferrals are permitted.

- **R1-11 (=UI-2)** [Med-High/High perf] — `CameraUiState` unstable + passed whole → per-frame
  recomposition of the dial cluster/top bar/pro sheet while a telemetry field (`audioLevel`/
  `histogramData`/`waveformData`/`recordElapsedMs`) ticks. **Reason:** the real fix narrows the child
  signatures or splits per-frame telemetry into a separate `StateFlow` — a `CameraScreen` refactor
  that needs on-device recomposition-count profiling to verify (device-only per CLAUDE.md); doing it
  blind risks regressing the carefully-tuned dial/recording UI. **Exit:** children take narrow
  stable params (or telemetry lives in its own flow) and a device profile shows the dials no longer
  recompose on a meter tick.
- **R1-12 (=UI-11)** [Low/Med perf] — `VideoTab` re-allocates `allSizes`/`resolutionOptions`/
  `availableFor`/`videoBitRate` each recomposition. **Reason:** low impact and coupled to the R1-11
  recomposition-frequency fix; best done together. **Exit:** the lists are `remember`ed keyed on
  their inputs, folded into the R1-11 pass.
- **R1-13 (=UI-8)** [Low/High maintainability] — `RulerSlider` unkeyed `remember` + snapshot write in
  composition. **Reason:** the dial feel is device-tuned ("iterated until it felt snapped on device",
  CLAUDE.md); refactoring the state reconciliation risks changing the haptic-detent behavior and must
  be re-felt on hardware. **Exit:** keyed `remember` + `LaunchedEffect`/`derivedStateOf`
  reconciliation, re-verified on device that snapping still feels identical.
- **R1-14 (=UI-9)** [Low/Med maintainability] — `onFractionChange` not `rememberUpdatedState`;
  currently no live bug (callers co-vary with the `pointerInput` keys). **Reason:** latent-trap
  hardening, no current defect; bundle with R1-13's ruler pass. **Exit:** `rememberUpdatedState`
  applied when the ruler is next touched.
- **R1-15** [Low/Med UX] — top-bar `GridButton` collapses to `NONE⇄THIRDS`, discarding a
  sheet-chosen grid. **Reason:** UX-polish micro-decision (round-trip the last non-NONE type) better
  batched with the deferred overlay/UX-token pass. **Exit:** the top-bar toggle restores the last
  non-NONE grid type.

### Carried device-only / larger deferrals (unchanged from prior cycles — still open)
- **C2-5** [Low/High] pro audio options (source/sample-rate) — `VideoRecorder.kt:198-204` hardcodes
  CAMCORDER/48 k. Deferred (own feature; audio quality is device-verify). Exit: `AudioSource` +
  `AudioSampleRate` enums plumbed UI→engine→recorder.
- **C2-7** [Low/Med] assist toggles set before GL `start()` are dropped (`GlPipeline.post` no-ops until
  `start()`); assists aren't persisted so restore isn't affected today. Exit: engine holds assist
  state and re-applies in the `gl.start{…}` onInputReady callback.
- **A-BUG-2/A-PERF-2** [Med-High/High] `setCameraOverride` runs camera IPC + `CameraCaps.read` on the
  MAIN thread (`CameraEngine.kt:307-324`). Exit: off-main with preserved reopen ordering, device-verify
  no reopen race.
- **A-PERF-1** [High/High-mech] full-res scope `glReadPixels` (~preview-size × 4 B) every 12th frame
  (`GlPipeline.kt:248-288`). Exit: downscaled-FBO readback, device-profiled.
- **UX-3** HUD reports requested-not-actual capture config (fallback ladder silently drops RAW/HLG).
  **UX-4** reachable bottom settings entry. **UX-5/M7** gallery/last-shot review. **UX-7** loading/
  hard-error state. **UX-8** full accessibility semantics + `strings.xml`. **UX-10** repurpose
  `LensFlipButton`. **UX-16** overlay color tokens. **UX-21** shutter re-center on snapshot. **M6**
  pinch-to-zoom. **UX-1/C2-12** metered-manual EV needle. All `[device-verify]`/larger — unchanged
  exit criteria in prior plans.
- **M3/M4, H2-sign** [device-only] tap-focus EIS/punch-in crop inverse; 300 mm gyro-EIS axis/sign;
  muxer orientation-hint sign. Exit: on-device calibration.
- **TEST-5..12** [High/Med] pure-extraction unit tests for `effectiveExposureNs`,
  `kelvinTintToRggbGains`, `GyroEis.currentDeviceOrientation`, iso/shutter stop math, AEB steps.
  Exit: progressively extracted + tested. (`videoBitRate`/`VideoFrameRate.availableFor`/RotationMath/
  CameraSelector2/FocusMapping already covered — 37 tests.)
- **L4** [Low/High] `OldTargetApi` targetSdk 36 — **DEFERRED BY CLAUDE.md POLICY** (targetSdk pinned
  to the sole device's OS = Android 16/API 36; bumping opts into API-37 runtime behavior on a 36
  device). Exit: device ships a newer OS level or a deliberate release decision.

---

## Security / correctness sweep (this cycle)
- **Permissions:** only CAMERA + RECORD_AUDIO, both requested at runtime with a permanent-denial
  Settings deep-link (`MainActivity.kt:62-111`). `AndroidManifest.xml` declares nothing extra. No
  network permission → no exfiltration surface.
- **Storage:** scoped MediaStore with `IS_PENDING`, publish-on-success / delete-on-failure across
  HEIF/JPEG/DNG/MP4 (`CameraEngine` + `MediaStoreWriter` + `VideoRecorder.stop`). Sound.
  `allowBackup=false` — no capture/settings leakage via backup.
- **No secrets, no reflection** beyond the documented heifwriter-alpha `RestrictedApi` suppression
  (`HeifCapture.kt:21`) and `VendorTagInspector` which is `BuildConfig.DEBUG`-gated
  (`CameraEngine.kt:131-134`).
- **Concurrency:** `@Volatile` seams between UI/GL/camera/audio threads verified for the fields
  touched this cycle; `Pending` is `synchronized`; the video/audio muxer is `muxerLock`-guarded.
  R1-1 is the one shared-state hazard found (single `pending` slot, unguarded on entry).

## Verified sound (no change needed)
- `RotationMath`, `CameraSelector2.pickBest`, `FocusMapping`, `VideoFrameRate.availableFor`,
  `videoBitRate`, `kelvinTintToRggbGains` (R,Geven,Godd,B order correct) — logic + tests correct.
- Session fallback ladder, `closed`/`paused`/`starting` guards, `Pending` close-on-failure/partial,
  HandlerThread `quitSafely`, `AudioRecord` STATE guard (both `startAudio` + `runAudio`), empty-MP4
  delete, record-stop off the main thread, HEIF/JPEG OOM-guarded recycle/publish paths — all correct.

## AGENT FAILURES
None. One synchronous helper (`code-reviewer`, UI layer) completed and returned full findings.
