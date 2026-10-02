# Architect review: RPL cycle 4 (HEAD 14767b0a)

Angle: duplicated sources of truth between the ViewModel, the Engine, the controller, and the
capability layer; seams that bypass the optics-transaction model; model-string and profile seams;
singleton and god-object pressure. I read the cycle-3 aggregate, `architect.md`, and the cycle 1–3
plans first. AGG3-11/12/14/15/16/22 (rollback-publication ownership, `RouteInputs` stamping) are
still scheduled for later cycles, and I found no new evidence on them, so they are not repeated
here. Every finding below is new.

Model-string sweep: `Build.MODEL` is read at `camera/DeviceProfile.kt:84` (seam 2),
`camera/Teleconverter.kt:41` (`detectPhone`, seam 1), `ui/CameraViewModel.kt:393` (feeds
`detectPhone` only), `camera/CameraEngine.kt:1272` and `camera/CameraController.kt:67` (both call
`DeviceProfile.resolve`), and `CameraEngine.kt:8001-8009` (EXIF make/model labels, which the
CLAUDE.md DeviceExifLabels bullet allows). No capability, route, or request decision branches on a
model string outside the two seams. ARCH4-5 covers the one structural weakness I found here: the
profile is resolved twice and memoized in a cache whose key does not include it.

Layering sweep: no file under `ui/` other than `CameraViewModel.kt`, and not `MainActivity.kt`,
references `CameraEngine`, `CameraController`, `GlPipeline`, `MediaStoreWriter`, or
`SettingsStore`. The UI→VM→Engine direction holds. One exception is by design: the Activity owns
the audio-denial provenance in its own preferences (see the final sweep).

## Findings

### ARCH4-1: The app-side PROGRAM line and the focus-detail exposure gate use a third focal-length value that ignores zoom. At telephoto digital zoom, the "handheld-safe" shutter is several times too slow

- Severity: Medium. Confidence: High (code). Status: Confirmed (code). The blur effect needs a
  device shot to confirm.
- Where: `ui/CameraViewModel.kt:1872-1887` (`preferredProgramShutterNs(s)` takes
  `s.lens.targetEquivMm`, the PRESET's nominal 23/70/230 mm, times the converter magnification; it
  never multiplies by `controls.zoomRatio`); `ui/CameraViewModel.kt:4722-4729` (KDoc: "the
  1/(35mm-equivalent focal) rule at the effective focal length"); consumers at
  `ui/CameraViewModel.kt:4167` (`AutoExposure.driveProgram`) and `:578` (`handheldShutterNs` for
  `frameDefocusCandidate`). The OSD computes the effective focal a different way, at
  `ui/overlays/Overlays.kt:864-876`: `teleconverterFocalMm × zoomRatio` for TELE and
  `caps.equivalentFocalMm × zoomRatio` otherwise. EXIF uses `effectiveFocalMm`
  (`camera/Teleconverter.kt:223`) together with the measured host focal.
- Why: the app now has three focal truths. They are the OSD/EXIF effective focal (measured lens ×
  converter × zoom), the program line (preset nominal × converter, no zoom), and the FRONT/EXTERNAL
  branch (measured `caps.equivalentFocalMm`, no zoom). CLAUDE.md describes PROGRAM as "shutter held
  at the handheld 1/(effective focal) rule". The OSD the operator reads states a focal length that
  the exposure loop does not use.
- Failure scenario: PMA110 with TC on, local zoom 4.0×. The OSD reads about 1200 mm
  (300 × 4). The program line holds 1/300 s, four times (two stops) slower than its own rule, and
  this is the app's main use case: handheld at the long end. The same thing happens on the logical
  route at unified 9.5×: the band is still TELE3X (70 mm nominal), so the shutter is 1/70 s while
  the OSD shows about 220 mm. On an OTHER-phone tablet with a generic 1.5× converter, the program
  uses 70 × 1.5 = 105 mm while the OSD and EXIF say 39 mm, so the error goes the other way and
  costs ISO for nothing. The focus-detail "16× the handheld rule" exposure gate moves by the same
  factor, so it admits shake-prone frames at high zoom.
- Fix: derive one `effectiveEquivFocalMm(state)` value, the same value `Overlays.kt` displays
  (measured `caps.equivalentFocalMm` or `teleconverterFocalMm`, times the route's zoom on its own
  scale), and pass it to `preferredProgramShutterNs`, `frameDefocusCandidate`, and the OSD. Pin it
  with a table test: {logical 9.5×, TC local 4×, DNG-standalone 3×, FRONT 2×, OTHER+1.5×} must give
  the OSD mm and the program 1/mm from the same number. PMA110 behavior changes by design, so this
  is a deliberate exposure change and needs an owner nod plus a device A/B.

### ARCH4-2: Video frame rates are offered and pinned without any per-size check. The "gated against the selected size" contract exists only in documentation

- Severity: Medium (GENERIC devices); Low on PMA110 until measured. Confidence: Medium-High.
  Status: Confirmed (code). Needs-manual-validation for the per-device effect.
- Where: `camera/CameraState.kt:1099-1124` (`VideoFrameRate.availableFor`: `normalFps` is
  `caps.availableFps`, and `size` is used only for the `height >= 4320` rule;
  `codec` is accepted and then ignored); `camera/CaptureCapabilities.kt:238-239`
  (`availableFps` = fixed `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`, which is size-independent);
  `:351-358` (video sizes filtered by aspect only); `getOutputMinFrameDuration` is queried only for
  the ZSL YUV reader (`:438-442`); `camera/ManualControls.kt:1059` pins `fixedFpsRange(c.fps)`;
  `ui/CameraViewModel.kt:3086-3092` (`reconcileFrameRate`, the VM's only gate);
  `camera/CameraEngine.kt:3391-3395` (`setVideoFrameRate` does no validation of its own). The
  contract this breaks is in `CLAUDE.md:742` ("Standard and NTSC drop-frame rates are gated
  against the selected size").
- Why: a target-FPS range says what AE may run at. It does not say what a given stream size can
  deliver; that is `StreamConfigurationMap.getOutputMinFrameDuration(SurfaceTexture/PRIVATE, size)`.
  The VM's rate list and the engine's request pin both trust the AE list. The encoder is never
  asked either (`VideoCapabilities.areSizeAndRateSupported`), even though `availableFor` takes the
  codec as if it did.
- Failure scenario: on a GENERIC device whose AE list includes `[60,60]` (common for 1080p60) but
  whose 3840×2160 PRIVATE min frame duration is 33.3 ms, 4K + 60 fps can be selected. The request
  pins `[60,60]`. Camera2 clamps the frame duration to the stream minimum and delivers 30 fps. The
  encoder is configured for 60 (`KEY_FRAME_RATE`, bitrate from `videoBitRate(…, 60, …)`), so the
  operator gets a clip labelled and bit-budgeted as 60 fps that holds 30 real frames per second.
  This is the same class as the documented "29.97 selection produced a real 25 fps file" defect,
  in a new place. AVC at 4K60 on encoders that do not support it would also fail only at
  configure time, after REC is pressed.
- Fix: carry `minFrameDurationNs` per video size in `CameraCaps`, read once at the caps seam
  (`getOutputMinFrameDuration(SurfaceTexture::class.java, size)`). `availableFor` then admits
  `r.fps` only when `1e9 / minFrameDuration >= r.fps` (allowing for drop-frame rounding) and the
  selected codec's `VideoCapabilities` accept size × rate. Make the engine apply the same predicate
  in `setVideoFrameRate` and after `applyVideoSize`, so a route-driven size re-pick cannot leave an
  unachievable rate pinned. Add a pure-core table test, and record the PMA110 4K/60 answer as a
  field check.

### ARCH4-3: Every non-forced route-inventory republish resets the VM's FRONT/EXTERNAL zoom to 1× without touching the Engine. A FRONT zoom silently diverges from the wire after every background→foreground return

- Severity: Low-Medium. Confidence: Medium. Status: Likely (code). A Robolectric test or device
  check would settle it.
- Where: `camera/CameraEngine.kt:1477-1479` (`resolveInitialCameraRouteAvailability`: when the
  inventory is already resolved and `!force`, it only republishes `(inventory, activeCameraRoute)`);
  this runs on every resume (`:7573`, after `pause` nulls the controller at `:7486`) and on every
  GL-generation start (`:1927`); `ui/CameraViewModel.kt:776-784` → `cameraRoutePublishedState`
  (`:4398-4414`), which sets `controls.zoomRatio = 1f` whenever `activeRoute.lensLocalZoom`; the
  Engine's `controls` keep the operator's zoom, and the following `reconfigureCamera` reopens with
  it. Caps reconcile (`:3165-3168`) pushes to the Engine only when the normalized controls differ
  from the VM's (already reset) value, so nothing converges.
- Why: the inventory callback is a discovery publication, but the VM fold treats it as a route
  transition and applies the transition's zoom side effect. On the Engine side, the same side
  effect lives in `applyResolvedCameraRoute` (`:1437-1455`), which runs only on the resolving
  path. A second variant of the problem: on the forced, complete, no-topology-change path
  (`:1547-1553`, for example a route-inventory retry that completes after a partial first read),
  `applyResolvedCameraRoute(FRONT)` writes `controls.zoomRatio = 1f`, `overrideId = null`, and
  `userCameraPin = null` straight into the Engine. This happens outside any optics transaction and
  without a request rebuild, so the Engine field says 1× while the wire still carries the old zoom
  until the next rebuild. That breaks the "every optics change owns a generation" rule.
- Failure scenario: FRONT, pinch to 2×, press Home, return. The OSD and the zoom readout show 1×.
  The preview is still at 2× because the Engine reopened with its own `controls`. The next pinch
  compounds from the VM's 1× base (`currentZoomBase`) and the framing jumps from 2× to about 1.1×.
  A still taken before any gesture is framed at 2× and recorded in the UI as 1×.
- Fix: make the inventory fold pure discovery. `cameraRoutePublishedState` should update
  `cameraRoutes`, `activeCameraRoute`, and `facing` only, and reset zoom/TC only when
  `activeRoute != current.activeCameraRoute`. In the Engine, run `applyResolvedCameraRoute`'s
  optics side effects only through `convergeAfterRouteTopologyChange`'s transaction, or only when
  the route actually changes. Test: publish the same `(inventory, FRONT)` twice with zoom 2× and
  assert that the VM zoom equals the Engine zoom.

### ARCH4-4: The rear-only optics refusal is a "shared decision", but the VM and the Engine feed it different inputs. On EXTERNAL with a back camera present, the VM publishes an optimistic lens/TC change that the Engine refuses

- Severity: Low (narrow reachability). Confidence: High (code). Status: Confirmed (code).
- Where: `ui/CameraViewModel.kt:1824-1828` (`frontFacing = facing == FRONT || !cameraRoutes.back`)
  vs `camera/CameraEngine.kt:3862-3866` (`frontFacing = activeCameraRoute != CameraRoute.BACK`);
  `camera/CameraState.kt:36-37` (`CameraRoute.EXTERNAL.facing == BACK`); the comment at
  `CameraEngine.kt:3858-3860` claims the two "cannot drift" because they share the predicate.
- Why: sharing the predicate's body does not help when the arguments come from different state.
  When the active route is EXTERNAL and `cameraRoutes.back` is true, the VM passes `false`
  (`facing` is BACK and a back route exists) and the Engine passes `true`. This state is reachable:
  `cameraRouteTopologyDecision` keeps a still-valid EXTERNAL route when a later complete inventory
  adds BACK (`CameraSelector2.kt:209-217`), for example after a partial first enumeration on a
  device with a USB camera attached.
- Failure scenario: the operator taps 3× or TC. The VM publishes the new lens, the remapped zoom,
  and `teleconverterMode`, then persists them. The Engine shows "Switch to the rear camera first"
  and returns before `beginOpticsTransaction`, so no rollback publication corrects the VM. The OSD
  shows the TELE converter focal on an external webcam, and the next settings save writes TC=on.
- Fix: both sides pass `activeCameraRoute != CameraRoute.BACK`. The VM reads its own
  `state.activeCameraRoute`, which the inventory fold already maintains. Add one test that runs the
  VM gate and the Engine gate on the same `(route, inventory)` table.

### ARCH4-5: The DeviceProfile is resolved independently by the Engine and by each controller, and is memoized route-dependently in an id-keyed, process-lifetime caps cache

- Severity: Low. Confidence: Medium (the structure is confirmed; reachability on PMA110 is narrow).
  Status: Confirmed (structure). Needs-manual-validation (reachability).
- Where: `camera/CameraEngine.kt:1272-1275` (`deviceProfile` plus `activeDeviceProfile()`, which
  depends on the mutable `activeCameraRoute`); `:1277-1293` (`capsCache.getOrPut("$logical:$physical")`
  reads `stillExposureCeilingNs = activeDeviceProfile()…` at first read and keeps it until a
  topology change); `:1355-1373` (`publishLensInventoryOnce` reads the BACK home caps whenever
  `cameraRouteInventory.back`, whatever the active route is); `camera/CameraController.kt:67-73`
  (each controller calls `DeviceProfile.resolve(Build.MODEL)` again and is switched to GENERIC
  by a mutator, `useGenericDeviceProfile()`, at `CameraEngine.kt:2373-2375`).
- Why: there are two owners of one fact. The controller decides the RAW law, YUV-still law, vendor
  TC session type, and oplus hints from its own copy. The Engine decides route, zoom scale, and the
  still ceiling from `activeDeviceProfile()`. They agree today only because `wireController` reads
  `activeCameraRoute` at construction. Separately, a value derived from the ROUTE is cached under a
  key derived from the CAMERA ID. If PMA110's logical back caps are first read while the route is
  EXTERNAL (the ARCH4-4 topology state, during the once-only lens-inventory publication), they are
  cached without the 4 s ceiling for the life of the process. A later BACK still at 5–6 s is
  exactly the shot CLAUDE.md records as fatal (`CAMERA_ERROR(3)`). In the other direction, an
  external camera read while BACK is active inherits PMA110's 4 s ceiling, which is harmless but
  untruthful.
- Fix: decide the profile per camera id, not per active route. "External" is a property of the
  id (`LENS_FACING_EXTERNAL` in its characteristics). Compute `profileFor(id)` once on
  `setupExecutor`, and pass the resolved profile into `CameraController`'s constructor (remove the
  `var` and the mutator). If route-dependence must stay, include the profile in the cache key.

## Final sweep

- **Root pattern (cycle 4):** each finding is a second derivation of something that already has an
  owner. ARCH4-1 re-derives the focal (the OSD/EXIF effective focal is the owner). ARCH4-2
  re-derives the deliverable frame rate (the stream configuration map is the owner). ARCH4-3 lets a
  discovery publication re-apply transition side effects (the optics transaction is the owner).
  ARCH4-4 feeds a shared predicate with a parallel input (the active route is the owner). ARCH4-5
  re-resolves the profile per object (the camera id is the owner). This continues cycle 3's
  "per-field rule" pattern, now outside the rollback code.
- **God-object pressure:** `CameraEngine.kt` is 8935 lines (8611 before RPL cycle 1), with 87
  `@Volatile` fields, about 126 mutable fields, and 90 non-private functions.
  `CameraViewModel.kt` is 4729 lines (4538 before), with 51 mutable fields and 97 distinct
  `engine.*` members used. Every RPL fix so far has added a field, a counter, or a helper to one of
  these two files. Route/optics state (`rawWanted`, `requestedVideoSize`, `preTeleUnifiedZoom`,
  declaration, route, zoom, and the direct-write counters) is the obvious first extraction: an
  `OpticsState` owner with the writer-stamp design already scheduled as AGG3-11/12. Without that
  extraction, ARCH4-3's "side effect outside a transaction" class will keep reappearing.
- **Audio-denial provenance split:** `recordAudio` lives in VM/SettingsStore. Its denial reason
  lives in the Activity's permission preferences (`MainActivity.kt:517-563`, three duplicated
  hardware-key blocks at `:718-745`, `:815-834`, `:859-875`). The VM's own `onStoreMemorySlot`
  default (`ui/CameraViewModel.kt:3532`, `audioOffByDenial = null`) is reached only if a caller
  bypasses the Activity wrapper. I found no such caller today (`CameraScreen` and ProSheet go
  through `actions`), so this is not a finding. Any new in-VM REC/audio path would skip the policy
  without notice. Moving the denial bit into `CameraUiState` and SettingsStore would make that
  impossible.
- **Process-global owners** (`Process*Owner`, `ProcessProcessedSnapshotBudget`,
  `DebugCameraControlMailbox`, `DiagnosticLog`, `StartupTrace`): I checked them for per-Engine state.
  Each one is either explicitly multi-generation (leases, subscriptions) or debug-only. None is new.
- **Checked, not findings:** the AGG3-13 helper (`standaloneRouteFor`) is now used at every VM
  route site, and the only remaining `CameraUiState.rawForcesStandalone` writer is the inventory
  fold (display only). Accepted-HLG truth (`PhotoSessionOutputs.hlg`, `rendererAssists.setSourceHlg`
  inside `commitOpticsReady`) has one owner. The rollback-restore Ready path re-uses the same
  controller, so `sourceHlg` cannot go stale there.

## Files examined

`camera/CameraEngine.kt` (route inventory and topology 1340-1650, profile/caps cache 1265-1300,
Ready/rollback 540-1160, preview health 2225-2330, wireController 2365-2400, lens door 3850-3880,
frame rate 3385-3420, setRawWanted 4098-4140, resume/pause 7440-7580, chooseVideoSize 7887-7930),
`camera/CameraController.kt` (profile 60-80, session plan 2750-2830), `camera/DeviceProfile.kt`,
`camera/CameraState.kt` (CameraRoute, back-optics refusal, `standaloneRouteWanted`,
`VideoFrameRate`), `camera/CaptureCapabilities.kt` (fps and size reads), `camera/CameraSelector2.kt`
(topology decision), `camera/OpticsConstraints.kt`, `camera/ProcessedSnapshotBudget.kt`,
`ui/CameraViewModel.kt` (inventory fold, zoom, program line, caps reconcile, frame-rate reconcile,
front toggle, hardware actions, recording, MR store), `ui/ZoomMath.kt`,
`ui/overlays/Overlays.kt` (focal OSD), `ui/controls/ProSheet.kt` (format chips), `MainActivity.kt`
(actions wrapper, key paths, audio provenance), `storage/SettingsStore.kt` (`ExtraSettings`),
`DebugCameraControlCommand.kt`; `CLAUDE.md`, `docs/ARCHITECTURE.md` (frame rates), the cycle-3
aggregate, the architect review, and the plans for cycles 1–3.
