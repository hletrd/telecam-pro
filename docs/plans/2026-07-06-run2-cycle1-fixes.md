# Run-2 Cycle-1 Fix Plan — 2026-07-06

Derived from `.context/reviews/_aggregate.md` (findings R1-1..R1-15). Rule: every finding is
**scheduled** or **deferred with a recorded reason + exit criterion** — nothing silently dropped.
Deferrals preserve original severity/confidence. Repo policy (CLAUDE.md + global git rules): GPG-signed
`-S` commits, Conventional Commits + gitmoji, **no** `Co-Authored-By`, fine-grained (one concern per
commit), `git pull --rebase` before push, latest-toolchain, on-device verification for camera/GL/
orientation pixels.

Status: ☐ todo · ◐ in progress · ☑ done (build+lint+unit green) · ⏸ deferred

## Gate objective
`:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` all error-free. Baseline (measured):
build ✅ · 37 unit tests ✅ · lint ✅ **0 errors, 9 warnings**. Keep it green after every commit.

---

## Scheduled — implement this cycle (safe, non-device, high-value)

- ☐ **R1-1 (=C2-3)** [Med/High] `camera/CameraController.kt` — reject a new `capturePhoto` while one
  is in flight: at the top of the posted body, `if (pending != null) return@post cb.onError(
  IllegalStateException("capture already in progress"))`. Prevents dropped/misattributed shot + leaked
  `Image` on a rapid double-tap. Verified safe for BURST/AEB (their next shot is posted and runs after
  `tryComplete`'s `finally` clears `pending`). `[device-stress final verify]`.
- ☐ **R1-2 (=C2-6)** [Med/High] `stab/GyroEis.kt` + `gl/GlPipeline.kt` + `camera/CameraEngine.kt` —
  kill the per-frame GL-thread `FloatArray` alloc. Add `GyroEis.fillCorrection(out: FloatArray)`
  (writes yaw,pitch,roll into `out`); change `GlPipeline.eisProvider` to `((FloatArray) -> Unit)?`
  with a GL-owned reusable `FloatArray(3)` filled inside `drawFrame`; wire
  `gl.setEisProvider { out -> gyro.fillCorrection(out) }`. Keep `currentCorrection()` if still used
  elsewhere (it isn't — replace the call site). Math identical.
- ☐ **R1-3 (=UI-1)** [High/Med] `ui/controls/ProControls.kt` `LabeledSlider` — guard a degenerate
  `valueRange` (`endInclusive <= start`): render a disabled full-track (or static value row) instead of
  a live `Slider`, so a pre-caps ISO `400..400` (or a single-value zoom range) can't produce a NaN
  thumb fraction / `roundToInt` crash. Protects every call site. `[device-confirm Material3 behavior]`.
- ☐ **R1-4 (=UI-5)** [Med/Med] `ui/CameraViewModel.kt` — in `onVideoCodec` and `onToggleOpenGate`,
  after updating state, clamp `videoResolution` to the first entry of the newly-valid option set
  (mirror the resolution filtering the UI applies: `caps.availableVideoSizes`/`openGateVideoSizes`,
  AV1 → `width<=1920`), then `reconcileFrameRate()`. Removes the out-of-range chip + bogus "4K AV1"
  encoder summary. Add a small pure helper so the clamp logic is shared/testable if feasible.
- ☐ **R1-5 (=UI-6)** [Low-Med] machine-format readouts → `String.format(Locale.US, …)` in
  `ui/controls/ManualDials.kt`, `ui/controls/ProSheet.kt`, `ui/controls/ProControls.kt`,
  `ui/overlays/Overlays.kt` (zoom `x`, EV `±`, roll `°`, timecode `mm:ss`, focal `mm`, Mbps). Import
  `java.util.Locale`.
- ☐ **R1-6 (=UI-7)** [Low/Med] `ui/overlays/Overlays.kt` `WaveformOverlay` — extend the guard to
  `if (data.columns <= 0 || data.rows <= 0 || data.bins.size < data.columns * data.rows) return`.
- ☐ **R1-7 (=UI-3)** [Med/High] 48 dp touch targets — add `Modifier.sizeIn(minWidth=48.dp,
  minHeight=48.dp)` to the clickable node of `ModeLabel` (`CameraScreen.kt`), `TeleChip`
  (`CameraScreen.kt`), and `DialChip` (`ManualDials.kt`), keeping visual size compact (mirror
  `ChromeIconButton`'s existing pattern).
- ☐ **R1-8 (=UI-4)** [Med/High] `ui/controls/ManualDials.kt` `DialChip` — honor `enabled` on the
  `clickable` and add `Modifier.semantics { if (!enabled) disabled() }`; ensure the EV chip passes
  `enabled = autoExposure`-appropriate so its greyed state is a real no-interaction (not a silent
  no-op tap). Preserve the intentional "tap greyed Focus/Shutter/ISO → Manual" behavior (those pass
  `enabled=true` semantics or keep their onClick).
- ☐ **R1-9 (=DOC-1)** [Low] `video/VideoRecorder.kt` — fix the class KDoc: it DOES set a
  `MediaMuxer` orientation hint (device orientation at record start); describe that instead of "NO …".
- ☐ **R1-10 (=UI-10)** [Low] `ui/overlays/Overlays.kt` `AspectMask` KDoc — replace `AspectRatio.FULL`
  references with `W4_3` (the no-crop case the code actually early-returns on).

## Deferred — recorded, not implemented this cycle (severity preserved; exit criteria stated)

Basis: none of the below are security/correctness/data-loss defects (those — R1-1, R1-3, R1-6 — are
SCHEDULED). CLAUDE.md requires on-device verification for camera/GL/orientation/recomposition behavior
and favors focused fine-grained changes; the perf/maintainability items need device profiling or touch
device-tuned code, so they are deferred per that policy.

- ⏸ **R1-11 (=UI-2)** [Med-High/High perf] whole-`CameraUiState` recomposition of dials/top-bar/sheet
  per telemetry tick. Reason: real fix narrows child signatures or splits per-frame telemetry into a
  separate `StateFlow` — a `CameraScreen` refactor needing on-device recomposition-count profiling;
  blind refactor risks regressing tuned dial/recording UI. Exit: children take narrow stable params
  (or telemetry in its own flow) + device profile shows dials don't recompose on a meter tick.
- ⏸ **R1-12 (=UI-11)** [Low/Med perf] `VideoTab` per-recomposition list allocation. Reason: low impact,
  coupled to R1-11's frequency fix. Exit: lists `remember`ed on their inputs, folded into R1-11.
- ⏸ **R1-13 (=UI-8)** [Low/High maint] `RulerSlider` unkeyed `remember` + compose-time state write.
  Reason: dial feel is device-tuned ("iterated until it felt snapped on device"); refactor must be
  re-felt on hardware. Exit: keyed `remember` + `LaunchedEffect`/`derivedStateOf`, re-verified feel.
- ⏸ **R1-14 (=UI-9)** [Low/Med maint] `onFractionChange` needs `rememberUpdatedState`; no live bug
  today. Reason: latent-trap hardening; bundle with R1-13. Exit: `rememberUpdatedState` applied.
- ⏸ **R1-15** [Low/Med UX] top-bar `GridButton` discards a sheet-chosen grid. Reason: UX-polish micro-
  decision, batch with the deferred overlay/UX pass. Exit: top-bar toggle restores last non-NONE grid.

### Carried device-only / larger deferrals (unchanged from prior plans — still open)
- ⏸ **C2-5** [Low/High] pro audio options (source/sample-rate). Exit: `AudioSource`+`AudioSampleRate`
  enums plumbed UI→engine→`VideoRecorder`.
- ⏸ **C2-7** [Low/Med] assists set before GL `start()` dropped (not persisted → no restore impact).
  Exit: engine holds assist state, re-applies in `gl.start{}` onInputReady.
- ⏸ **A-BUG-2/A-PERF-2** [Med-High] `setCameraOverride` off the MAIN thread. Exit: off-main, no reopen
  race (device).
- ⏸ **A-PERF-1** [High] downscaled-FBO scope readback. Exit: device-profiled.
- ⏸ **UX-3/4/5/7/8/10/16/21, M6, UX-1/C2-12** — HUD achieved-config, reachable settings entry,
  gallery review, loading/error state, full a11y + strings.xml, LensFlip repurpose, overlay tokens,
  shutter re-center, pinch-zoom, metered EV needle. Exit criteria per prior plans/`designer.md`.
- ⏸ **M3/M4, H2-sign** [device-only] tap-focus crop inverse; 300 mm EIS axis/sign; muxer hint sign.
  Exit: on-device calibration.
- ⏸ **TEST-5..12** [High/Med] pure-extraction tests (`effectiveExposureNs`, `kelvinTintToRggbGains`,
  gyro orientation, stop math, AEB steps). Exit: extracted + tested progressively.
- ⏸ **L4** [Low] `OldTargetApi` targetSdk 36 — **DEFERRED BY CLAUDE.md POLICY** (targetSdk pinned to
  the sole device's OS = API 36). Exit: device ships a newer OS level or a deliberate release decision.

---

## Progress log
- 2026-07-06 — plan created from `_aggregate.md`. Gate baseline green (build ✅ / 37 tests ✅ /
  lint 0 errors, 9 warnings). Prior plans `2026-07-02` + `2026-07-03` are fully implemented → moved to
  `docs/plans/archive/`.
