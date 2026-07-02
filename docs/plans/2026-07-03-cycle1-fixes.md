# Cycle 1 Fix Plan — 2026-07-03

Derived from `.context/reviews/_aggregate.md` (20 findings). Rule: every finding is **scheduled** or **deferred with recorded reason** — nothing silently dropped. Deferrals preserve original severity and state an exit criterion (per repo `CLAUDE.md` + global git rules: GPG-signed `-S`, Conventional Commits + gitmoji, no `Co-Authored-By`, fine-grained, `pull --rebase` before push).

Status: ☐ todo · ◐ in progress · ☑ done (build+lint+unit green) · ⏸ deferred

## Gate objective
`:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` all **error-free**. Baseline: build+unit green, lint **1 error** (H1) + 18 warnings.

---

> **Re-scoped after the agent reviews landed** (perf/debugger/designer/test-engineer/tracer). They surfaced several **crash/ANR** bugs that outrank the net-new features, so `M2` (settings persistence, large) is **deferred to next cycle** in favor of the crash/ANR/correctness fixes below. All agent findings are recorded in `_aggregate.md` and deferred with exit criteria (see that file + `designer.md`/`debugger.md`/etc.).

## Scheduled — implement this cycle

- ☑ **H1** (gate-red) — `HeifCapture.kt:22` `HeifWriter.close()` RestrictedApi → narrowly `@SuppressLint("RestrictedApi")` (genuine alpha false-positive; only release path). **DONE — gate green.**
- ☑ **L3** — Remove `super.onCleared()` (lint EmptySuperCall). **DONE.**
- ☑ **L7** — `captureAeb`: `distinct()` the clamped EV steps. **DONE.**
- ☐ **A-TRACE-2a** (High/ANR) — Move `VideoRecorder.stop()`'s blocking `join()`s off the main thread (`ioExecutor`); set `isRecording=false` immediately.
- ☐ **A-BUG-1** (High) — `CameraController.capturePhoto`: call `cb.onError` on the no-target path so BURST/AEB chains continue and a message shows.
- ☐ **A-BUG-4** (Med-High) — Guard `AudioRecord.state == STATE_INITIALIZED` before `startRecording`; degrade to video-only.
- ☐ **H3** — `VideoRecorder.stop()`: publish only when `muxerStarted`; else `MediaStoreWriter.delete`. No junk 0-byte MP4s.
- ☐ **H2** — Video orientation hint: snapshot `gyro.currentDeviceOrientation()` at record start → `VideoRecorder.start(orientationHint)` → `muxer.setOrientationHint()` before start. Portrait unchanged; landscape corrected. `[device-verify]` final sign.
- ☐ **M1** — `CameraViewModel.onStop()`: if recording, remove `recordTicker` + set `isRecording=false` (pause() already finalized the file).
- ☐ **M5 / UX-9** — Touch targets ≥48 dp: `ChromeIconButton` 36→48, `SnapshotButton` 36→48, ProSheet `CloseButton` 32→44 (via `sizeIn(minWidth/minHeight)`, keep glyph size).
- ☐ **UX-22** — Disabled dial chips non-interactive (`clickable(enabled=enabled)`); EV chip shows a hint in manual instead of an inert ruler.
- ☐ **UX-2 (partial)** — Shutter press feedback: scale animation + `performHapticFeedback(CONFIRM)` on fire. (Full flash/thumbnail deferred.)
- ☐ **M10 / UX-25** — `overlayRotation` shortest-path: unwrapped cumulative target so rotation is ≤90°.
- ☐ **M8** — Permission gate: on permanent denial (`!shouldShowRequestPermissionRationale`), CTA → "Open Settings" (`ACTION_APPLICATION_DETAILS_SETTINGS`).
- ☐ **L1** — Bump deps (agp 9.2.1, coreKtx 1.19.0, activityCompose 1.13.0, composeBom 2026.06.01); verify build green, revert breakage.
- ☐ **L2** — Add `androidx.exifinterface:exifinterface`; switch `CameraEngine`/`DngCapture` to its `ORIENTATION_*` (value-identical).
- ☐ **L6** — Report chosen video size to the VM on caps-ready; sync `state.videoResolution`.
- ☐ **TEST-1** — Extract `CameraSelector2.pickBest(candidates)` pure fn + `CameraSelector2Test` (closest-to-70 mm, standalone tie-break, filters non-positive, empty/single).
- ☐ **TEST-2/3/4** — Extract `camera/RotationMath` (preview/capture/exif) pure object + `RotationMathTest`.
- ☐ **DOC** — Fix `docs/ARCHITECTURE.md` stale preview-rotation formula (`-sensorOrientation + 180` → `if (teleconverterMode) 180 else 0`).

## Deferred — recorded, not implemented this cycle

- ⏸ **M2** [Med/High] — Settings persistence (`SettingsStore`/SharedPreferences). Re-prioritized to next cycle: the agent reviews surfaced higher-priority crash/ANR fixes, and persistence is a sizeable self-contained feature better done as its own focused change (test-engineer's infra finding also means its test must use pure, non-`android.*` serialization). Exit: `SettingsStore` restores a curated subset on cold start; round-trip unit test green.
- ⏸ **A-BUG-2 / A-PERF-2** [Med-High/High] — `setCameraOverride` off the main thread. Needs care to preserve the reopen ordering; device-verify no reopen race. Exit: selection/caps read on `setupExecutor`, no race.
- ⏸ **A-BUG-3 / A-TRACE-2b** [Med/Med] — VideoRecorder teardown vs drain-thread race. Exit: null-guarded muxer access + rapid start/stop stress on device.
- ⏸ **A-BUG-5** [Med/Med] — stale-Surface bind in the async startup window. Exit: device-repro fixed.
- ⏸ **A-TRACE-4** [High/High] — rapid-shutter `pending`-slot race. Exit: guard pending-busy (reject/queue) + device stress.
- ⏸ **A-TRACE-3a** [Med/High] — mode switch to PHOTO while recording orphans the recording. Exit: stop recording on mode change (bundle with M1 follow-up).
- ⏸ **A-PERF-1** [High/High-mech] — full-res scope `glReadPixels` (~18 MB) each analysis frame. Exit: downscaled-FBO readback, profiled on device.
- ⏸ **A-PERF-3** [Med/High] — per-frame FloatArray alloc on GL thread (EIS provider). Exit: fill-in-place provider contract, no per-frame alloc.
- ⏸ **UX-1** [High] — metered-manual exposure indicator (M.M./EV needle). Needs metered-EV from `TotalCaptureResult`; device-verify. Exit: needle driven by metered delta.
- ⏸ **UX-3** [High] — HUD reports requested-not-actual capture config (fallback ladder). Exit: engine surfaces achieved streams → state + amber warning.
- ⏸ **UX-4** [High] — reachable settings entry on a 3168 px screen. Exit: swipe-up / bottom-cluster gear.
- ⏸ **UX-5 / M7** [High/Med] — gallery/last-shot review (chimp focus at 300 mm). Exit: thumbnail opens last capture.
- ⏸ **UX-6 / M8-extra / M9** [High/Med] — permission over-ask (RECORD_AUDIO up front) + in-context audio request. (M8 CAMERA deep-link ships this cycle.) Exit: in-context audio request + re-request path.
- ⏸ **UX-7** [High] — loading / hard-error state (`CameraStatus{Initializing,Ready,Error}` + Retry). Exit: no live controls over a dead preview.
- ⏸ **UX-8** [High] — accessibility semantics on every custom control + `strings.xml`. Exit: TalkBack labels + Play a11y clean.
- ⏸ **UX-10** [High] — repurpose the `LensFlipButton` (duplicate TELE) as a reachable settings/scopes/review entry. Exit: slot repurposed.
- ⏸ **UX-16** [High] — overlay colors off-token + single-tone grid invisible on bright scenes. Exit: token unification + dual-tone HUD strokes.
- ⏸ **UX-21** [High] — shutter re-centers when the snapshot button appears mid-record. Exit: fixed snapshot side-slot.
- ⏸ **UX-11..UX-30 (remainder)** [Med/Low] — focus magnifier auto-engage, WB dial inline, AE/AF lock indicators + long-press lock, tabular figures, haptic detents, partial-sheet live preview, tab-rail glyph legibility, overflow affordance, battery/storage readout, reduced-motion, ruler scrim. All recorded in `designer.md`; exit criteria per item there.
- ⏸ **TEST-5..14** [High/Med] — GyroEis orientation, kelvin→RGGB, iso/shutter stops, effectiveExposureNs, applyGainAndLevel, equivFocalOf, AEB steps; **TEST-13** `fileName()` BURST same-second collision (real latent bug). Exit: pure-extraction (or Robolectric) + tests; fileName gets a uniqueness suffix.
- ⏸ **M3** [Med/Med] — Tap-to-focus EIS/punch-in/"cover"-crop inverse (also A-TRACE-5b). Device-only sign/axis. Exit: on-device tap-lands calibration.
- ⏸ **M4** [Med/Med] — 300 mm gyro-EIS axis/sign tuning. Device-only. Exit: hand-held steadies.
- ⏸ **M6** [Med/High] — pinch-to-zoom (UX-13). Deferred with the gesture pass (pinch/tap/swipe conflict handling). Exit: two-finger zoom composed with tap-focus.
- ⏸ **H2 (sign confirmation only)** [High/Med] — the orientation-hint *plumbing* ships this cycle; the exact hint sign (`deg` vs `(360-deg)%360`) is **device-only**. Exit: confirm a landscape clip plays upright on the Find X9 Ultra.
- ⏸ **M3** [Med/Med] — Tap-to-focus EIS/punch-in crop inverse. Applying the inverse blind risks worsening the mapping; the axis/mirror signs are device-only. Exit: on-device tap-lands-on-point calibration with EIS + punch-in active.
- ⏸ **M4** [Med/Med] — 300 mm gyro-EIS axis/sign tuning. Emulator has no tele/real shake. Exit: hand-held 300 mm test shows the preview steadies (not amplifies).
- ⏸ **M7** [Med/Med] — Gallery/last-shot review. Needs last-saved-URI tracking + `ACTION_VIEW` (and ideally a real thumbnail). Scoped as its own feature to avoid bloating this cycle. Exit: thumbnail opens the last capture; severity preserved.
- ⏸ **M9** [Med/Med] — RECORD_AUDIO re-request path. Bundled with the permissions UX pass (M8 ships the CAMERA path this cycle). Exit: enabling Record Audio while denied re-prompts / deep-links.
- ⏸ **L4** [Low/High] — `OldTargetApi` (targetSdk 36). **Policy**: `CLAUDE.md` pins targetSdk 36 = Android 16 (the sole target device's OS); bumping to 37 opts into API-37 runtime behavior on a 36 device. Exit: device ships a newer OS API level, or a deliberate release decision.
- ⏸ **L5** [Low/Med] — Live exposure (ISO/shutter/EV) readout in Auto. Needs `TotalCaptureResult` AE-state → UI plumbing (device path to verify). Exit: metered values shown and confirmed correct on device.

## Carried device-verify items (from BACKLOG, not new findings)
HLG/Log color approximation; 10-bit HDR preview deferred; Kelvin→RGGB neutral-grey; R8/minify; heifwriter alpha. All remain per existing BACKLOG/prior plan — unchanged.

---

## Progress log
(updated during PROMPT 3 implementation)
