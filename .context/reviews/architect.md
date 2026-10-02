# Architect review — RPL cycle 5

Scope: coupling and layering, duplicated sources of truth (VM vs Engine predicates), ownership and
generation consistency, model-string branching outside the two sanctioned seams, god-object growth,
and missing single seams that cause real divergence. Baseline: `ea7d4374` (after the cycle-4 merge
and deslop). I read CLAUDE.md, the cycle-4 plan (`docs/plans/2026-10-02-rpl-cycle4.md`, including its
"later cycle", "carried" and "Deferred" lists) and the archived cycle-4 architect review. Items those
lists already track (AGG4-12/13/17/19/27/33, the AGG3/AGG2/AGG carried sets, MRG4-5, and the
`MemoryBankAudioProvenance` deslop note) are not reported again here.

Summary: 0 Critical, 0 High, 0 Medium, 2 Low, 3 Info.

The cycle-4 VM/Engine fixes I traced hold up: A.7 rear-only refusal, A.10 `rearReturnLens`, A.18
route-inventory fold, A.19 pre-inventory optical set, A.20 one effective focal, A.24 momentary holds,
and the A.2/MRG4-2/3 bare-retry disposition. I found no new model-string branching. The two findings
below are what is left of the "two copies of one fact" bug class on the zoom/lens axis.

---

## Findings

### AR5-1: The Engine's lens band never follows a seamless zoom, so every rollback publishes a stale band to the VM, and the band predicate exists in three hand-written copies

- Severity: Low · Confidence: High · Status: Confirmed (code path); visible symptom needs device validation
- Cites:
  - `camera/CameraEngine.kt:4764-4810` (`setZoomRatio`): writes `controls.zoomRatio` and never re-bands `lensChoice`.
  - `camera/CameraEngine.kt:4842-4863` (`commitZoomForBoost`): same, never re-bands.
  - `camera/CameraEngine.kt:600, 2848, 3017`: the only zoom-derived re-band sites. They run at caps
    install and door commit, never on a pinch.
  - `camera/CameraEngine.kt:605-614` (`lensBandFollowsZoom`): a private engine copy of the predicate.
  - `ui/CameraViewModel.kt:2334-2343` (`flushZoom`) and `ui/CameraViewModel.kt:3194-3200`
    (`reconcileZoomToCaps`): the two VM copies, written inline as
    `!teleconverterMode && activeCameraRoute == BACK && !standaloneRouteFor(s)`.
  - `camera/CameraEngine.kt:759-764` (`currentOpticsSnapshot`): `lens = lensChoice` becomes the
    rollback baseline.
  - `ui/CameraViewModel.kt:999-1003`: the rollback publication writes `lens = rollback.lens` into UI
    state.
- Why it matters: on the logical photo route the VM re-bands `lens` from the unified zoom on every
  flush (`flushZoom`). The Engine only writes the ratio, so after a pinch from 1× to 5× the VM holds
  `TELE3X` and the Engine still holds `MAIN`. Most doors hide this because they carry the VM's lens
  into the Engine transaction (`setVideoMode(resolvedLens)`, the DNG remap packet,
  `setResolvedOptics`). On the logical route `unifiedZoomOf` and `resolveNonTeleId` also ignore the
  lens. But `OpticsSnapshot.lens` is the stale engine value, and every `rollbackOptics` publishes it
  back to the VM.
- Failure scenario (PMA110):
  1. In Photo, pinch to 5× without backgrounding the app. The VM holds `TELE3X`; the Engine holds
     `MAIN`.
  2. Run any door that rolls back. Examples: Video with the standalone lens unavailable
     (`CAMERA_UNAVAILABLE_MODE_UNCHANGED`), TC with the 3× lens unavailable
     (`TELE_LENS_UNAVAILABLE_UNCHANGED`), a recording that wins the race against a mode/lens door
     (`STOP_RECORDING_*_UNCHANGED`), or a failed DNG reopen.
  3. The rollback publishes `lens = MAIN, zoom = 5.0`. The rail and lens caption now highlight 1×
     while the frame is at 5×. This lasts until the next zoom movement re-bands it.

  A second path is latent today. If DNG were toggled while FRONT, `onSetPhotoFormats` passes no
  remap packet (`dngDoorRemapsZoomScale` is false on lens-local routes). On leaving FRONT the Engine
  would then compute `rearReturnZoom(..., lensPreset = MAIN.zoomPreset)` = 5.0 on the main standalone
  lens, while the VM computes 5/3 = 1.67 on `TELE3X`. That is a real wire/UI zoom split, and the
  next `updateControls` would jump the frame to 1.67× on the main lens. It is unreachable from the UI
  right now only because `rawSelectable(frontFacing = true)` disables the DNG chip
  (`camera/OpticsConstraints.kt:97-103`, `ui/controls/PhotoFormatChips.kt:76-84`). Any future
  DNG-capable front route or a new DNG entry point would expose it.
- Suggested fix:
  - Hoist the predicate into one pure top-level function in `CameraState.kt`:
    `lensBandFollowsZoom(video, teleconverter, route, rawWanted, rawForcesStandalone)`.
  - Call it from the Engine's three re-band sites and the two VM sites.
  - Inside the existing monitor in `setZoomRatio` and `commitZoomForBoost`, add
    `if (lensBandFollowsZoom(...)) lensChoice = LensChoice.forZoom(z)`.
  - Host test: drive `setZoomRatio(5f)` on a logical-route engine, then a failing door. The rollback
    publication must carry `TELE3X`. Also add a table test that the VM and Engine predicates agree
    over (mode × DNG × law × TC × route).
- PMA110 behaviour change: no wire change. On the logical route the Engine lens feeds only
  rollback/snapshot publication and lens-ignoring conversions; standalone routes do not re-band.
  Only the rail after a rollback changes, and it becomes correct.

### AR5-2: "Unified zoom" has two formulas, the UI's and the Engine's, which disagree on lens-local routes and read two different copies of the RAW law

- Severity: Low · Confidence: High · Status: Confirmed (code); visible effect masked today
- Cites:
  - `camera/CameraState.kt:590-602` (`CameraUiState.unifiedZoom`): short-circuits lens-local routes
    (FRONT/EXTERNAL) to the raw ratio, and reads `state.rawForcesStandalone`. That field defaults to
    `true`, the PMA110 law, until the first route-inventory publication
    (`camera/CameraState.kt:1771-1777`).
  - `camera/CameraEngine.kt:7824-7838` (`pushTeleFinder`): calls
    `unifiedZoomOf(lensChoice, zoom, standaloneRouteWanted(videoMode, rawWanted, activeDeviceProfile().rawRequiresStandalone), acceptedOpticalPresets)`
    with NO lens-local short-circuit. It reads the live law and the Engine's lens.
  - Consumers of the UI form: `ui/CameraScreen.kt:774-781`, `ui/overlays/Overlays.kt:723-730, 1088-1095`.
    The Engine form feeds the GL `teleFinderResolved` flag.
- Why it matters: CLAUDE.md says the finder gate is "ONE shared unit-tested predicate … resolved in
  one place". The predicate is shared, but its zoom INPUT is computed by two different functions.
  On FRONT or EXTERNAL in VIDEO, `standaloneRouteWanted` is true, so the Engine multiplies the
  front/external lens-local ratio by the retained REAR band's optical base. With a retained `TELE3X`
  band, a FRONT local 1.0 becomes 3.0 and GL resolves the overview ON while the UI gate says OFF.
  - FRONT: masked because `pushPunchIn` suppresses the loupe there (`camera/CameraEngine.kt:7781-7785`).
  - EXTERNAL: masked in practice because the optical set is normally empty there (base falls back
    to MAIN). The pre-inventory window is the exception, since `acceptedOpticalPresets` is seeded
    to `LensInventory.ALL.optical` (A.19).
  - GENERIC devices before the first route inventory: the UI form assumes the PMA110 RAW law and
    the Engine form does not, so a restored DNG selection reads the logical ratio as lens-local in
    the UI only.
  - Under TC, neither form includes the converter in its standalone argument. That is consistent
    between the two forms and covered by the predicate's own `teleconverter ||` term.
- Failure scenario: any future change that lets punch-in run on FRONT or EXTERNAL, or a converter
  plus EXTERNAL combination, makes the GL overview draw with no Compose border or OSD tag (or the
  reverse). That is the same symptom class as the 2026-08-04 "transparent rectangle" report.
- Suggested fix: one pure `routeUnifiedZoom(route, lens, zoomRatio, videoMode, rawWanted, rawForcesStandalone, optical)`
  that applies the `lensLocalZoom` short-circuit. `CameraUiState.unifiedZoom` and `pushTeleFinder`
  both call it. The UI should read the RAW law from one place: either always `state.rawForcesStandalone`,
  or have the VM's `standaloneRouteFor` read the state copy rather than `engine.rawForcesStandalone`.
  Host table test: both call sites agree for every (route × mode × DNG × law × band).
- PMA110 behaviour change: none on BACK. On FRONT/EXTERNAL the GL flag changes only where it is
  currently masked.

### AR5-3 (Info): God-object growth continued through cycle 4

- Severity: Info · Confidence: High · Status: Confirmed
- Line counts, `887d39fb` to `ea7d4374`:

  | File | Before | After | Change |
  |---|---|---|---|
  | `camera/CameraEngine.kt` | 8935 | 9142 | +207 |
  | `ui/CameraViewModel.kt` | 4729 | 4877 | +148 |
  | `storage/MediaStoreWriter.kt` | 3350 | 3550 | +200 |
  | `camera/CameraController.kt` | 2962 | 3014 | +52 |

- Most of the additions are correct fixes. Several of them are, however, a third or fourth
  restatement of a predicate that already exists. Examples are AR5-1's band predicate, and the
  EXIF effective-focal formula in `exifShotOf` (`camera/CameraEngine.kt:8100-8106`), which restates
  `effectiveEquivFocalMm` (`camera/CameraState.kt:2062-2071`) inline instead of calling it. The
  AGG4-14 KDoc says "One number now feeds all three", but the EXIF number is still a separate copy.
  Today they agree numerically: the same terms, plus the result zoom.
- The VM still performs provider mutations directly (`MediaStoreWriter.deleteKnownOutput`,
  `deleteUntrackedFamilySiblings`, `discardPendingOutput` at `ui/CameraViewModel.kt:4061-4071, 4170`)
  while the Engine owns the family tombstone. This is the existing design and is not a defect. It is
  the main reason storage ownership is split across the UI/engine layer boundary.
- Suggested direction (later cycle): extract the zoom/lens scale seam (`unifiedZoomOf`, band
  predicate, rear return, remap functions) into one `RouteScale` module that both the VM and Engine
  call. Make `exifShotOf` call `effectiveEquivFocalMm`.

### AR5-4 (Info): Fix-off defaults survive on route/scale seams that AGG4-47 did not cover

- Severity: Info · Confidence: High · Status: Confirmed (no current caller omits them)
- Cites:
  - `ui/ZoomMath.kt:387-399` `remapModeOptics(frontFacing = false, lensLocalRoute = frontFacing, photoIsStandalone = false, optical = LensChoice.entries.toSet())`
  - `ui/ZoomMath.kt:476-484` `remapRouteScaleOptics(optical = LensChoice.entries.toSet())`
  - `ui/ZoomMath.kt:521-533` `restoredOptics(photoStandalone = false)`
  - `camera/CameraState.kt:664-693` `teleFinderResolved/teleFinderVisible(zoomRatio = 1f)`
  - `camera/Teleconverter.kt:149-153` `teleconverterDeclaration(measuredOtherHostEquivMm = 70)`
- Why it matters: each default is the PMA110 or "fix-off" answer. AGG4-47 removed exactly this
  pattern from `standaloneRouteWanted`, `hlgSessionAccepted` and `chars(shot)`, because an omitted
  argument compiles into the pre-fix bug. For example, `optical = entries` divides by a lens a
  one-camera tablet does not have, which is the TB336ZU 27 mm vs 81 mm class of defect. Every
  production caller passes these arguments today (`ui/CameraViewModel.kt:2462-2472, 2575-2582, 1408`),
  so this is latent.
- Suggested fix: remove the defaults and let the compiler enforce the arguments, as AGG4-47 did.

### AR5-5 (Info): `onPhoneModel`/converter declaration refuse on FRONT through the rear-optics door

- Severity: Info · Confidence: Medium · Status: Confirmed (behaviour); design question
- Cites: `ui/CameraViewModel.kt:2699, 2712, 2724` call `rejectBackOnlyOpticsDoor()` for the phone,
  profile and custom magnification. `ui/CameraViewModel.kt:1899-1909` is that function.
- Why it matters: the converter DECLARATION is a persisted Setup fact, not a route change. Refusing
  it with "Switch to rear first" while the Lens tab is open on FRONT/EXTERNAL is consistent between
  the VM and Engine (no divergence). It does, however, couple a settings edit to the live route.
  This is not a bug. It is recorded so a future "declaration while FRONT" request does not
  reintroduce an optimistic VM write the Engine then refuses (the AGG4-16 shape).

---

## Already tracked (not re-reported)

- AGG4-17 (DeviceProfile resolved independently in Engine and Controller). Still true at
  `camera/CameraEngine.kt:1336` and `camera/CameraController.kt:67`. No new evidence.
- AGG4-13 (per-size FPS gate). `reconcileFrameRate` still reads `s.caps`, which can be the outgoing
  route's caps during a reopen (`ui/CameraViewModel.kt:3160-3166`). Same item, no new failure seen.
- MRG4-5 (program target moving during a gesture). The handheld target now tracks zoom by design
  (A.20).

## Final sweep (commonly-missed checks)

- **Model strings.** `Build.MODEL` is read in `DeviceProfile.resolve` (Engine:1336, Controller:67),
  `detectPhone` (VM:402-409), and EXIF identity labels (`exifShotOf`, which labels and does not
  branch). No capability, route, or request decision branches on a model string. Clean.
- **Layering.** `ui/` does not import `CameraController`, `GlPipeline`, or `VideoRecorder`, and no
  `camera/gl/video/storage/capture` file imports `ui.*`. The only cross-layer reach is the VM's use
  of `MediaStoreWriter` (AR5-3).
- **Rear-only refusal (A.7).** The VM and Engine now both feed `activeCameraRoute`. One residual: the
  VM gates on `isRecording` while the Engine gates on `recorder != null`. The VM is stricter during
  admission (optimistic `isRecording = true`). After a stop latched mid-admission, the Engine briefly
  holds a published recorder while the VM reads false. A lens tap in that window gets an Engine
  `STOP_RECORDING_FIRST` with the VM's optimistic lens already published and no rollback. The window
  is milliseconds, so I am not raising a finding; noted for whoever touches `RecordingAdmissionLatch`.
- **Momentary holds and the focus-ruler loupe assist.** I traced both orders (hold, then ruler; and
  ruler, then hold). The assist only engages when `!state.punchIn` (`ui/controls/ManualDials.kt:257-264`),
  so the two snapshots never capture each other's transient value. No defect.
- **Route-inventory fold (A.18).** The VM's `routeChanged` compares against its own
  `activeCameraRoute`, and the Engine compares against its own. Both are written synchronously by
  the same doors, so they cannot disagree on a republish.
- **EXIF vs OSD focal.** Numerically identical today (AR5-3 notes the duplicated formula).
- **Converter declaration seed.** The Engine starts from `FIND_X9_ULTRA`, and the VM re-pushes
  `detectPhone ?: OTHER` during construction (`seedPhoneModel`, VM:2871-2895) before start. No stale
  window.

## Files examined

- `camera/CameraEngine.kt`: optics transactions, `setLens`, `setFrontCamera`, `setRawWanted`,
  `setVideoMode`, `setZoomRatio`/`commitZoomForBoost`/`setZoomInteraction`, `pushTeleFinder`,
  `pushPunchIn`, `applyResolvedCameraRoute`, `resolveNonTeleId`, `selectCurrentLens`,
  `handlePreflightFailure`, `exifShotOf`, `stopRecording`, recorder admission publish,
  `resolveLensOpticsIntent`.
- `camera/CameraState.kt`: `unifiedZoomOf`, `localZoomOf`, `resolveTeleZoomTransition`,
  `standaloneRouteWanted`, `rearReturnZoom`/`rearReturnLens`, `teleFinderResolved`/`Visible`,
  `hiResAdmitted`, `LensChoice.forZoom`, `CameraUiState` derived focal/zoom properties,
  `effectiveEquivFocalMm`.
- `camera/OpticsConstraints.kt`, `camera/ControlAvailability.kt`, `camera/DeviceProfile.kt`,
  `camera/Teleconverter.kt`, `camera/CaptureCapabilities.kt` (focal derivation),
  `camera/DeviceExifLabels.kt`.
- `ui/CameraViewModel.kt`: optics doors, `flushZoom`, `applyZoomRatio`, `onHardwareZoomStep`,
  `reconcileZoomToCaps`, rollback publication, `applyLoaded`, `onSetPhotoFormats`,
  `applyEncoderInventory`, momentary holds, `onAutoPunchIn`, delete paths,
  `programHandheldShutterNs`, `cameraRoutePublishedState`.
- `ui/ZoomMath.kt`, `ui/MomentaryHold.kt`, `ui/controls/PhotoFormatChips.kt`,
  `ui/controls/ProSheet.kt` (format row), `ui/controls/ManualDials.kt` (ZoomRuler, loupe assist),
  `ui/CameraScreen.kt` and `ui/overlays/Overlays.kt` (finder gate consumers).
- Cycle-4 diffs: `69d58550`, `54c90d3a`, `992ec720`, `597c40df`, `6e75d245`, `b1f7869e`.
