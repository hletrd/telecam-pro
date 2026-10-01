# Architect review — 2026-09-30 (RPL cycle 1)

Scope: layering, ownership boundaries, model-string seams, god-object seams, duplicated predicates,
process-wide singletons across Engine generations, generation/ownership bypasses.
Read-only pass over `app/src/main/kotlin/me/hletrd/telecampro/**` at HEAD `ba5b16e7` plus
`CLAUDE.md` and `docs/ARCHITECTURE.md`. Nothing was device-verified; every item below that touches
pixels or HAL behavior stays PENDING DEVICE until an on-device check confirms it.

## Inventory summary

| Package | Notable owners | Size signal |
|---|---|---|
| `camera/` | `CameraEngine` (8,611 lines, 268 funs, 62 plain `var` + 85 `@Volatile` fields, 59 `synchronized(this)` sites, 14 `beginOpticsTransaction` doors), `CameraController` (2,848), `CameraState` (1,893), `ManualControls`, 11 `Process*` singleton owners | Engine is the god object |
| `ui/` | `CameraViewModel` (4,538 lines, 209 funs), `CameraScreen` (3,403), `controls/*`, `review/*` | ViewModel is the second god object |
| `gl/`, `video/`, `capture/`, `storage/`, `stab/`, `focus/` | `GlPipeline` (2,239), `VideoRecorder` (2,380), `MediaStoreWriter` (2,992, `object`) | |

Layering checks that passed:
- `camera/ gl/ video/ capture/ storage/` import nothing from `ui/` and no Compose API beyond the
  `@Immutable` annotation in `CameraState.kt:4` and `CameraStatus.kt:3`.
- `ui/` never imports `android.hardware.camera2`. `CameraViewModel` reaches into `video/`
  (`EncoderCaps`, `AudioInputInspector`) and `gl/` (`MotionInversionConfidence`) directly. That is a
  mild facade bypass, but the calls are pure or inventory reads, so it is not a bug source.
- The engine calls back only through `EngineCallbackSink` leases (`CameraEngine.kt:1606+`). I found
  no raw lambda field that escapes teardown.
- The process-lifetime capacity owners (`ProcessPreNativeMediaAllocator` and its
  `ProcessRecordingPreNativeAllocator` facade, `ProcessStillPublicationOwner`,
  `ProcessRecordingStorageOwner`, `ProcessRetainedStillDiscardOwner`,
  `ProcessFamilyDeletionMarkerOwner`, `ProcessViewModelMediaDeleteOwner`,
  `ProcessProcessedSnapshotBudget`, `ProcessDngPreCaptureAdmission`) each hold exactly one bounded
  dispatcher. The recording-named allocator is a pure delegate
  (`RecordingPreNativeAllocation.kt:296-299`), so capacity is not doubled.

## Model-string seam audit

`Build.MODEL` / `Build.MANUFACTURER` reads in main code:

| Site | Use | Verdict |
|---|---|---|
| `ui/CameraViewModel.kt:381` → `detectPhone` | Preselects the phone dropdown (seam 1) | Sanctioned |
| `camera/CameraEngine.kt:1171` → `DeviceProfile.resolve` | Quirk profile (seam 2) | Sanctioned |
| `camera/CameraController.kt:67` → `DeviceProfile.resolve` | Second, independent resolution of seam 2 | Sanctioned, but duplicated (see A4) |
| `camera/CameraEngine.kt:7778-7786` | EXIF make/model labels via `DeviceExifLabels` | Label only, no branch. Allowed by the EXIF bullet |

No capability, route, or request decision branches on a model string outside `DeviceProfile`.
**No constraint violation found.** The two seams do disagree about which devices are the same phone
(A3), and the ViewModel comment that claims a single read is false (A6).

---

## Findings

### A1 — Turning DNG on or off switches the zoom scale, but zoom is never remapped. The framing jumps to 3× the intended zoom (for example 9× instead of 3×)
- **Where:** `ui/CameraViewModel.kt:2370-2393` (`onSetPhotoFormats`),
  `camera/CameraEngine.kt:3959-3984` (`setRawWanted`), and the reconcile at `CameraEngine.kt:589`.
- **Why:** On PMA110 (`rawRequiresStandalone = true`), DNG is a route input. Turning it on moves
  Photo from the logical seamless camera, which reads `zoomRatio` main-relative, to a standalone
  lens, which reads it lens-local. The other scale-changing doors all remap zoom through
  `unifiedZoomOf`/`localZoomOf` and call `invalidateOpticsDerivedState()`: mode flip
  (`remapModeOptics`, VM `:2300`), lens preset (`resolveLensOpticsIntent`), TELE, FRONT
  (`rearReturnZoom`), and MR recall (VM `:1300`). The DNG toggle does neither. `onSetPhotoFormats`
  writes only `photoFormats`. `setRawWanted` opens an optics transaction that changes only
  `overrideId`, and the reconcile at `:575-593` only clamps the value and re-derives the band.
- **Scenario (PMA110, Photo):** The operator pinches to 3× (wire 3.0 on logical camera 0, band
  `TELE3X`) and turns DNG on in the Shoot tab. `resolveNonTeleId(TELE3X)` opens standalone cam 4,
  and wire 3.0 is now 3× digital on the 70 mm lens, which is 9× total. The OSD then shows about
  208 mm and the readout 9.1×. This is the same symptom the 2026-08-04 scale fix removed for lens
  taps. The reverse also happens: at 3× on the standalone lens (wire 1.0), turning DNG off lands
  the logical camera at unified 1.0, and the rail collapses to 1×. At 10×, turning DNG on asks the
  230 mm lens for 10× local zoom (clamped by caps). Any in-flight coalesced pinch or hardware glide
  also lands in the wrong scale, because `invalidateOpticsDerivedState()` is not called.
- **Fix:** Treat the DNG toggle as an optics door, like `onModeChange`. In the ViewModel, compute
  `unifiedZoomOf(before)` and then `localZoomOf` or unified for the new
  `standaloneRouteWanted(...)`. Call `invalidateOpticsDerivedState()`. Pass the resolved controls
  into the engine, for example `setRawWanted(enabled, resolvedControls)`, so they publish inside
  the same `beginOpticsTransaction` that changes the route. Add a host test: logical 3.0 with DNG on
  gives local 1.0 on the 70 mm lens, and the round trip back gives unified 3.0.
- **Confidence:** Medium-High. The code path is unambiguous, and no test covers the toggle (the
  `DeviceRouteLawsTest` and `ReconfigurationGenerationTest` DNG cases cover lens taps and round
  trips, not the toggle itself). **Status:** OPEN, PENDING DEVICE.

### A2 — `rawWanted` is a route input that sits outside the optics transaction, its rollback, and the MR-recall packet
- **Where:** `camera/CameraEngine.kt:3205` (field), `:3959-3984` (written before and outside
  `beginOpticsTransaction`), `:629-654` (`OpticsSnapshot` has no `rawWanted`),
  `camera/OpticsConstraints.kt:23-40` (`OpticsRollbackPublication` has no photo formats), VM
  rollback handler `ui/CameraViewModel.kt:915-960`, and VM recall order `:1400` then `:1461`.
- **Why:** CLAUDE.md requires every reopen to own one complete optics generation, and requires
  MR/settings recall to be one normalized packet. `rawWanted` decides which camera
  `resolveNonTeleId` opens and which zoom scale every consumer uses (`:589, :3739, :3864, :3946,
  :7488`), yet:
  1. **Rollback leaves it stranded.** Suppose the DNG-triggered reopen fails, for example
     standalone cam 4 returns `CAMERA_IN_USE` or `selectCurrentLens()`/`cachedCaps` return null at
     `:4063-4088`. `rollbackOptics` then restores the logical `overrideId`, lens, and controls, but
     `rawWanted` stays `true` and the UI's `photoFormats.dngRaw` stays `true`. The engine's route
     answer is now standalone while the installed session is logical. Every
     `unifiedZoomOf(..., standaloneRoute = true)` reads the logical camera's main-relative wire as
     lens-local, so the Loupe gate, band, and OSD focal are wrong. The next plain
     `reopenForSession()` (aspect, fps, hi-res) reuses the restored logical `overrideId`. The change
     gate `if (rawWanted == enabled) return` then blocks re-selecting DNG. This is exactly the
     permanent divergence documented as DNG bug #2, now reachable through rollback.
  2. **Recall is split into two transactions.** `applyLoaded` publishes T1 with
     `setResolvedOptics` (`:1400`), whose zoom is computed for the recalled DNG route, and 17
     setters later calls `setRawWanted` (`:1461`). That opens T2, whose `before` baseline is T1's
     unaccepted desired packet. Between the two calls, `setResolvedOptics → pushTeleFinder`
     (`CameraEngine.kt:2750`) converts the recalled zoom with the old `rawWanted`. If T2 fails, the
     rollback "restores" T1's recalled lens and controls onto the outgoing session, which violates
     the rule that a rejected recall restores the accepted state.
- **Fix:** Add `rawWanted` to `OpticsSnapshot` and restore it in `rollbackOpticsState`. Add
  `photoFormats.dngRaw` (or the whole `PhotoFormats`) to `OpticsRollbackPublication` and have the
  VM rollback handler restore it. Carry `rawWanted` as a parameter of `setResolvedOptics` so a recall
  is one generation, and delete the trailing `setRawWanted` call from `applyLoaded`. In
  `setRawWanted`, write the field inside the `beginOpticsTransaction { }` block.
- **Confidence:** Medium. The rollback trigger is uncommon but real (another app holding the tele
  lens, or a transient characteristics failure). The recall split is deterministic.
  **Status:** OPEN.

### A3 — The two sanctioned model seams disagree on hardware identity. The global Find X9 Ultra (`CPH2841`) gets the Hasselblad kit and none of the crash guards
- **Where:** `camera/Teleconverter.kt:41` (`FIND_X9_ULTRA` matches `"PMA110", "CPH2841"`) versus
  `camera/DeviceProfile.kt:83-84` (`resolve` matches only `"PMA110"`).
- **Why:** Seam 1 declares that CPH2841 is the same phone. It preselects the X9 Ultra and the
  300 mm Hasselblad kit, and the caption says "Detected OPPO Find X9 Ultra". Seam 2 treats the same
  handset as `GENERIC` and trusts every advertised range. On the PMA110 HAL each of these quirks is
  fatal or silently loses output. If CPH2841 shares that HAL (same SoC and vendor stack; unmeasured):
  - `stillExposureCeilingNs = null`: the ruler offers 5–20 s, and a 5 s or longer M-mode still
    raises `CAMERA_ERROR(3)` and the shot is lost.
  - `rawRequiresStandalone = false`: DNG stays on logical camera 0, which on PMA110 errors the
    whole device about 5 s after the shot.
  - `logicalStillRequiresYuv = false`: the ladder may take HAL JPEG on the logical camera, which
    fails gralloc on PMA110. Only the watchdog rescues the shutter.
  - `frontStreamPreMirrored = false`: the front preview gets double-mirrored and front video files
    are recorded mirrored.
  The two failure directions are not symmetric. Giving PMA110 quirks to a spec HAL mostly costs
  conservatism (a 4 s ceiling, standalone RAW). Giving GENERIC to a PMA110-class HAL costs crashes.
- **Fix:** Put one `KnownHandset` identity table (model strings → phone plus measured-quirk key)
  behind both seams, so they cannot drift. Then record an explicit decision for each variant model:
  measured, or deliberately GENERIC. CLAUDE.md says "no speculative quirks", so the minimum fix is
  to document CPH2841 as knowingly GENERIC and add a test asserting that both seams agree on each
  `deviceModels` entry. The better fix, after one on-device check, is to map CPH2841 to the PMA110
  profile.
- **Confidence:** Medium. The divergence is certain; whether the CPH2841 HAL matches is
  unmeasured. **Status:** OPEN, needs an owner decision and ideally a device.

### A4 — `DeviceProfile` is resolved twice and specialized for the route in two ways; the UI mirror reads a live getter rather than the published route
- **Where:** `CameraEngine.kt:1171-1174` (`activeDeviceProfile()` derives the profile from the
  volatile `activeCameraRoute` on every read), `CameraController.kt:67-73` (its own
  `DeviceProfile.resolve` plus `useGenericDeviceProfile()`, frozen at `wireController`,
  `CameraEngine.kt:2272`), `CameraEngine.kt:1663` (`rawForcesStandalone` getter), and VM
  `:764`/`:1305` (`engine.rawForcesStandalone` read from main).
- **Why:** The controller's profile is fixed when it is wired, while the engine's changes the moment
  `setActiveCameraRoute` runs inside a newer transaction. They can therefore disagree for a whole
  reconfigure window: the outgoing BACK controller carries PMA110 hints while the engine already
  answers GENERIC for an EXTERNAL target. At VM `:764`, `cameraRoutePublishedState` pairs the
  published `activeRoute` argument with `engine.rawForcesStandalone`, which reflects the engine's
  *current* route and not the published one. The restore path at VM `:1305` computes the recalled
  zoom scale from the pre-recall route's profile, although `setResolvedOptics` may move EXTERNAL
  to BACK. The effect today is only transient, because the value differs only for EXTERNAL, where
  `lensLocalZoom` dominates. It is the same "two copies of one answer" shape that produced the
  zoom-scale bugs.
- **Fix:** Resolve the base profile once (engine), pass it into the `CameraController` constructor,
  and make route specialization a pure `deviceProfileForRoute(base, route)` that callers evaluate
  against the route they are reasoning about. Publish the *base* `rawRequiresStandalone` once to
  the UI and derive the per-route value from `CameraUiState.activeCameraRoute`.
- **Confidence:** Medium (design). Low that it produces a visible bug today. **Status:** OPEN.

### A5 — The diagnostic budget is process-LIFETIME and applies to every device. After 120 warnings or errors, save and camera failures log nothing
- **Where:** `camera/DiagnosticTelemetry.kt:13-66`. `DiagnosticLog` is imported `as Log` in
  `CameraEngine`, `StillCapturePipeline`, `VideoRecorder`, `VendorTagInspector`, and
  `MediaStoreWriter`.
- **Why:** `ProcessDiagnosticLogBudget` is a monotonic counter with no window. Once 120 `w`/`e`
  rows have been emitted, every later `Log.e("StillCapturePipeline", "HEIF save failed", t)`,
  camera fault, and MediaStore identity error in that process is silently dropped, on every device
  and in release builds. CLAUDE.md's own triage rule for the 2026-09-09 tablet defect is "a save
  that fails with no app log line is the signature of this class of defect". After a long session
  (recovery loops, repeated reopen warnings) that rule gives a false positive for every failure.
  The quota was measured on ColorOS only. The Lenovo tablets, where the last three field bugs were
  found, get the cap without any benefit. ColorOS `LOG_FLOWCTRL` appears to be rate-windowed rather
  than lifetime (the CLAUDE.md evidence, "spent the whole quota in about 20 s", fits a window too).
  A lifetime cap turns a rate protection into permanent blindness.
- **Fix:** Replace the lifetime counter with a token bucket (for example 120 rows/min for faults and
  180 rows/min for recurring rows), or at minimum reset it per Engine resume. Keep the executable
  inventory test. Gate the reduced budget on the measured ColorOS quirk, which could be a
  `DeviceProfile` flag or a platform-property probe, instead of applying it globally.
- **Confidence:** Medium. The lifetime behavior is certain; the ColorOS window semantics should be
  confirmed with one logcat run. **Status:** OPEN.

### A6 — Documentation drift: the ViewModel claims a single `Build.MODEL` read
- **Where:** `ui/CameraViewModel.kt:378-381` says "The ONE android.os.Build.MODEL read in the app".
  There are four more (`CameraEngine.kt:1171, :7779, :7786` and `CameraController.kt:67`).
- **Why:** This comment is exactly the kind of statement a future reviewer uses to skip the seam
  audit, and it hides A3 and A4.
- **Fix:** Reword it to "seam 1 of 2 (catalog preselection); DeviceProfile is seam 2; EXIF labels
  read the build for identity only".
- **Confidence:** High. **Status:** OPEN (trivial).

### A7 — God-object seam: 14 optics doors re-implement the same scale-transition checklist by hand
- **Where:** `CameraEngine.kt` has 14 `beginOpticsTransaction` sites. Each door must remember to
  (a) convert zoom between scales, (b) publish the full packet under the monitor, (c) extend
  `OpticsSnapshot` and rollback for any new route input, and (d) have its VM twin call
  `invalidateOpticsDerivedState()`.
- **Why:** A1 and A2 are two missed checklist items on the one door (DNG) that was added after the
  pattern existed. The next route input (hi-res on a capable device, an external-camera option, a
  second-stream wide finder) will hit the same trap. The 8.6k-line engine hides the checklist from
  review because the doors are thousands of lines apart.
- **Fix:** Extract a pure `RouteInputs` value (mode, rawWanted, TC, facing/route, lens, pin) and one
  `resolveOpticsTransition(before: RouteInputs, after: RouteInputs, controls, optical)` that returns
  the complete packet, including the zoom in the target scale. Have every door, and the VM, call it.
  Because `OpticsSnapshot` then embeds `RouteInputs` whole, a new input cannot be left out of
  rollback. This is the natural first cut for splitting `CameraEngine` into an optics-transaction
  owner and a session and capture facade.
- **Confidence:** Medium (design recommendation with two concrete bugs as evidence).
  **Status:** OPEN, refactor, do after A1 and A2.

---

## Count

7 findings: 2 bug-class (A1, A2), 1 constraint-consistency risk (A3), 1 observability defect (A5),
2 design/duplication (A4, A7), 1 doc drift (A6). No `Build.MODEL` constraint violation.

## Top 5
1. **A1** Turning DNG on or off switches the zoom scale without remapping it. On PMA110, 3× jumps to
   9× (and back to 1×).
2. **A2** `rawWanted` is outside the snapshot, rollback, and recall packet. A failed DNG reopen
   recreates the documented permanent DNG divergence, and MR recall is split into two transactions.
3. **A3** `detectPhone` treats CPH2841 as the X9 Ultra while `DeviceProfile` gives it GENERIC, so
   the HAL crash guards are off on that variant.
4. **A5** The lifetime 120-row warning/error budget on every device silently drops save and camera
   failure logs after a long session.
5. **A4/A7** Profile specialization for the route is duplicated between engine and controller, and
   the 14 optics doors re-implement the scale-transition checklist by hand, which is the structural
   root of A1 and A2.
