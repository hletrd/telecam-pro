# Cycle 1 Fix Plan — 2026-07-03

Derived from `.context/reviews/_aggregate.md` (20 findings). Rule: every finding is **scheduled** or **deferred with recorded reason** — nothing silently dropped. Deferrals preserve original severity and state an exit criterion (per repo `CLAUDE.md` + global git rules: GPG-signed `-S`, Conventional Commits + gitmoji, no `Co-Authored-By`, fine-grained, `pull --rebase` before push).

Status: ☐ todo · ◐ in progress · ☑ done (build+lint+unit green) · ⏸ deferred

## Gate objective
`:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` all **error-free**. Baseline: build+unit green, lint **1 error** (H1) + 18 warnings.

---

## Scheduled — implement this cycle

- ☐ **H1** (gate-red) — `HeifCapture.kt:22` `HeifWriter.close()` RestrictedApi. Prefer `AutoCloseable`/`.use{}`; if lint still flags, narrowly `@SuppressLint("RestrictedApi")` with justification (genuine alpha false-positive; only release path). Commit body explains per gate rule. **Must land first — unblocks the gate.**
- ☐ **H3** — `VideoRecorder.stop()` publish-vs-delete: publish only when `muxerStarted` (≥1 sample); else `MediaStoreWriter.delete`. Prevents junk 0-byte MP4s.
- ☐ **H2** — Video orientation hint. Snapshot `gyro.currentDeviceOrientation()` at record start → `VideoRecorder.start(orientationHint=…)` → `muxer.setOrientationHint()` before `start()`. Portrait unchanged; landscape corrected. `[device-verify]` final sign.
- ☐ **M1** — `CameraViewModel.onStop()`: if recording, remove `recordTicker` + set `isRecording=false` (pause() already finalized the file).
- ☐ **M5** — Bump touch targets ≥48 dp: `ChromeIconButton` 36→48, `SnapshotButton` 36→48, ProSheet `CloseButton` 32→44 (keep glyph size).
- ☐ **M6** — Pinch-to-zoom: add `detectTransformGestures` on the preview → `onZoomRatio`, clamped to `caps.zoomRatioRange`, coexisting with tap-to-focus.
- ☐ **M8** — Permission gate: when `!shouldShowRequestPermissionRationale` and denied, CTA → "Open Settings" (`ACTION_APPLICATION_DETAILS_SETTINGS`).
- ☐ **M10** — `overlayRotation` shortest-path: track an unwrapped cumulative target so rotation is ≤90°.
- ☐ **M2** — Settings persistence: `SettingsStore` (SharedPreferences) for a curated non-hardware subset (photoFormats, transfer, videoCodec/resolution/bitrate, grid, eis+strength, teleconverter, drive/timer/interval, aspect, assists toggles, exposureStep). Restore in `CameraViewModel.init`; add a round-trip **unit test** (also closes a test-coverage gap).
- ☐ **L1** — Bump deps (agp 9.2.1, coreKtx 1.19.0, activityCompose 1.13.0, composeBom 2026.06.01); verify build green, revert any breakage.
- ☐ **L2** — Add `androidx.exifinterface:exifinterface`; switch `CameraEngine`/`DngCapture` to its `ORIENTATION_*` (value-identical).
- ☐ **L3** — Remove `super.onCleared()` in `CameraViewModel`.
- ☐ **L6** — Report chosen video size to the VM on caps-ready; sync `state.videoResolution`.
- ☐ **L7** — `captureAeb`: `distinct()` the clamped EV steps.

## Deferred — recorded, not implemented this cycle

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
