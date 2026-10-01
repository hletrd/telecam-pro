# RPL cycle 3 — tracer review (TR3-), HEAD e3a2bdd4

Role: causal tracing with competing hypotheses across shutter→publish→review, REC admission,
Photo↔Video / FRONT↔rear optics, settings save/restore + MR recall, and pause/resume. Read-only;
no Gradle run. Every camera item is host-reasoned only and stays PENDING DEVICE.

Two findings, both on the carried items. The other flows were re-traced against the cycle-2 fixes
and no new defect was confirmed (see "Traced, hypothesis refuted").

---

## TR3-1 — Leaving FRONT onto a standalone route picks the camera by the retained LENS but divides the zoom by the RATIO's lens. Wrong framing for any lens-local zoom past the lens's own band, not only TELE > 3.33

- **Severity:** Medium. **Confidence:** High. **Status:** Confirmed from code (host-testable). The
  framing still needs a PMA110 check.
- **Where:**
  - `camera/CameraState.kt:606-614` `rearReturnZoom` → `localZoomOf(unified, optical)`. Its divisor is
    `opticalBaseFor(unified)`, which depends on the ratio.
  - `camera/CameraEngine.kt:3974-4000` (`setFrontCamera` transaction): this sets
    `controls.zoomRatio = rearReturnZoom(...)` and leaves `lensChoice` alone. The executor then opens
    `resolveNonTeleId(lensChoice)` (`:4029`), which picks the camera for the retained lens.
  - `ui/CameraViewModel.kt:2990-3010` (`onToggleFrontCamera`, leaving): the same mirror. `lens` is
    unchanged and the zoom comes from `rearReturnZoom`.
  - The entry snapshot (`CameraEngine.kt:3976`, `CameraViewModel.kt:2966`) uses `unifiedZoomOf(lens, …)`,
    whose divisor depends on the lens. So the entry and the exit are not inverses.
- **Hypotheses:**
  - H1: "Only a TELE past local 3.33 is affected (as carried)." **Refuted as too narrow.** The mismatch
    happens whenever `opticalBaseFor(lens.zoomPreset) != opticalBaseFor(unified)` on a standalone
    target. In Video (and Photo+DNG), a pinch does not re-band `lens`: `flushZoom`'s `lensBand` keeps
    `s.lens` on standalone routes (`CameraViewModel.kt:2269-2278`), and the engine's
    `lensBandFollowsZoom` is false there (`CameraEngine.kt:597`). So a pinch past a band boundary on
    the same standalone lens is an ordinary state.
  - H2: "Engine and VM disagree (desync)." **Refuted.** Both run the same arithmetic, so the UI
    shows the same wrong framing that is on the wire.
  - H3: "Caps reconciliation re-bands the lens after the reopen and hides it." **Refuted.**
    `lensBandFollowsZoom` is false on standalone routes, so the lens-local value stands.
- **Failure scenarios (PMA110 optics {0.6, 1, 3, 10}):**
  1. Video on the 1× lens, pinched to local 3.5. The flip-in snapshot is unified 3.5. On the flip
     back, `localZoomOf(3.5)` = 3.5 / 3 = 1.17, applied on the **1×** lens (`resolveNonTeleId(MAIN)`).
     Framing goes from 3.5× to 1.17×. This is a common gesture: a 3.5× video crop, then a selfie and
     back.
  2. Video on the 0.6× lens at local 2.0. Snapshot unified 1.2. `localZoomOf(1.2)` divides by the 1×
     lens (1.2), so the result is 1.2 on the 0.6× lens, about 0.72×.
  3. TELE at local 4.0 (Photo or Video), then FRONT, then back in Video or Photo+DNG. TC is forced
     off. Unified 12 gives `localZoomOf(12)` = 12 / 10 = 1.2 on the retained TELE3X lens, so the
     return lands at 3.6× instead of 12×. This is the carried case.
  4. A save while still in FRONT uses `retainedRearWireZoom` (`ZoomMath.kt:426-434`), whose divisor
     depends on the lens. Scenario 1 therefore persists 3.5 on MAIN, while the live flip-back lands at
     1.17. The same retained setup has two different answers depending on whether the operator
     relaunches or flips back.
- **Why tests miss it:** `PunchInResolvedTest` (`app/src/test/.../camera/PunchInResolvedTest.kt:47-85`)
  only covers TELE3X at local 2 (unified 6, base 3 = lens base) and the crop-only tablet. No case has
  a retained lens whose base differs from the ratio's base.
- **Suggested fix:** Make the return a pure function that resolves **lens and zoom together**, the
  same way `resolveTeleZoomTransition` and `remapModeOptics` already do. When `targetStandaloneRoute`
  is true, set `lens = LensChoice.forZoom(unified)` and `zoom = localZoomOf(unified, optical)`.
  Otherwise keep `unified` and let caps reconciliation re-band. The engine assigns `lensChoice` from
  it inside the transaction, before `resolveNonTeleId(lensChoice)` runs, and the VM mirrors both
  fields. The alternative is to keep the lens and divide by `opticalBaseFor(lens)`, i.e. reuse
  `retainedRearWireZoom(teleconverter = false)`. That fixes 1–3 and agrees with the save path, but
  it mishandles a lens left pinned at 3× with unified < 3 on a standalone target, so re-banding is
  the safer fix.
  - Host test: a table over (lens, local, mode/DNG) for entry and exit must round-trip
    `unifiedZoom` exactly: MAIN 3.5 video, UW 2.0 video, TELE3X 4.0 with TC, TELE3X 2.0 (regression).
  - Plus one assertion that the exit answer equals what `retainedRearWireZoom` persists for the same
    retained state, or that both go through one function.

---

## TR3-2 — "10-bit video · stills off" still appears on the 8-bit preview-only rung (carried AGG2-35 residual, re-confirmed), and the request predicate ignores the HLG10 capability the engine gates on

- **Severity:** Low. **Confidence:** High. **Status:** Confirmed from code.
- **Where:**
  - `ui/controls/ProSheet.kt:898`: `tenBitVideoWanted = tenBitSessionWanted(mode == VIDEO, transfer)`.
  - `ui/controls/ProControls.kt:1037-1048`: `noStillOutputCaption`. The RESIDUAL note is still
    present.
  - `camera/CameraController.kt:672,2749-2780`: the ladder. `tenBitVideoOnly` covers attempt 0 only.
    Attempt 1 is HLG + still, attempt 2 drops HLG, attempt 3 is preview-only: `useHlg = wantHlg &&
    streamAttempt < 2`, so attempt 3 is **8-bit**. `CameraController.kt:764` records `hlgConfigured =
    useHlg`.
  - `camera/CameraEngine.kt:844` consumes `hlgConfigured` for GL only. It is never part of
    `PhotoSessionOutputs` (`CameraState.kt:1267-1277`) or any `CameraUiState` field.
- **Hypotheses:**
  - H1: "The 8-bit preview-only rung is unreachable for a 10-bit request." **Refuted.** Attempts 1–3
    are the ordinary ladder, and TC adds more rungs. Any rejection down to rung 3 (memory pressure,
    a different HAL) produces `processed=false, raw=false, hlg=false`.
  - H2: "Only PMA110 matters, and it accepts rung 0." Partly true for PMA110. But the request
    predicate also omits the engine's `caps.supportsHlg10()` term (`CameraController.kt:672`). On a
    multi-device install without HLG10, a non-SDR request runs the ordinary ladder. A preview-only
    landing there shows "10-bit video" over an 8-bit session that was never asked for HLG10.
- **Failure scenario:** Video with an S-Log3 or HLG curve. The HLG10 still-less rung and rungs 1–2
  are rejected, and the session lands on preview-only. The Output row says "10-bit video · stills
  off", which describes a deliberate trade, while the true state is "Still capture unavailable" on
  an 8-bit session. The operator believes the clip is 10-bit and that stills are a design trade
  rather than a failure.
- **Suggested fix:** Publish the accepted fact. Add `hlg: Boolean` to `PhotoSessionOutputs`, filled
  from `useHlg` in `acceptedPhotoSessionOutputs` (`CameraController.kt:2552`). It then rides the
  existing Ready publication, generation checks and rollback (`before.photoSessionOutputs`) for free.
  Key the caption on `!outputs.hasStillTarget && outputs.hlg`. Host test: given an accepted
  preview-only output mask with `hlg=false` and a 10-bit request, `noStillOutputCaption` returns
  `status_still_capture_unavailable`.

---

## Traced, hypothesis refuted (no new finding)

- **Shutter → DNG pre-allocation → continuation (AGG2-3 fix, `DngPreCaptureAllocation.kt:170-199`).**
  H: a synchronous reject can still double-continue. Refuted. `StillContinuationHandoff` CASes one
  `claimed` flag between `settle()` and `dispatchResult()`, and a SINGLE drive (`onDone == null`)
  keeps the plain `false`.
- **Reconfigure preflight rollback (AGG2-4 fix, `CameraEngine.kt:963-981, 1072-1106, 8481-8500`).**
  H: restoring under the preflight generation could re-accept a camera that a fault already retired.
  Refuted. Any later bump moves `cameraSessionGeneration` past `preflightSessionGeneration`, so it is
  not restored.
- **REC start/stop latch (`RecordingAdmissionLatch.kt`, `CameraEngine.kt:5923-5968, 6492-6510, 6696-6700`).**
  H: a stop that lands between `recorder = rec` and `completeAttempt(ok)` is lost. Refuted.
  `inFlight` stays true until `completeAdmission`, so the stop is latched and runs after publication.
  A stop during the pre-native allocation is consumed by `retirePreNativeRecordingAllocation`.
- **DNG door versus FRONT/TC (`ZoomMath.kt:437-458`, `CameraEngine.kt:4055-4070`).** H: toggling DNG
  while FRONT corrupts the retained rear framing. Refuted. The door is not a remap on lens-local
  routes, and the canonical `preFrontRearUnifiedZoom` is converted only at exit, where TR3-1 applies.
- **Optics rollback DNG/size mirrors (`CameraEngine.kt:1013-1060`).** H: the VM keeps a rolled-back
  DNG or size. Refuted. `OpticsRollbackPublication.rawWanted` and `requestedVideoSize` publish the
  post-keep-rule engine values.
- **Pause during a queued FRONT/mode door (`CameraEngine.kt:4019-4021, 2680`).** H: a paused
  executor return strands the desired route. Refuted. The route and lens state persist, and `resume`
  resolves from `activeCameraRoute` (`:4573`).
- **Recall audio-denial (`MainActivity.kt:517-528`, `CameraPermissionPolicy.kt:101-115`).** H: a
  refused re-recall clears the reason. Refuted. The `appliedLoadCount` delta is only incremented at
  the applied exit.
- **Dial doors (AGG2-17, `ManualControls.kt:519-566`).** Consistent with `onExposureMode`. The
  handoff keeps `shutterMode`, so a HAL-AE P packet that carries ANGLE (reachable only through an old
  recalled bank) would keep its angle. This was not pursued; it is below threshold.

## Files examined

- camera: `CameraEngine.kt` (FRONT door, mode door, rollback commit, preflight rollback, REC
  admission, pause/resume, finder push, caps reconcile), `CameraState.kt` (zoom scale functions,
  `rearReturnZoom`, `tenBitSessionWanted`, `PhotoSessionOutputs`, `LensChoice.forZoom`),
  `CameraController.kt` (session ladder, `hlgConfigured`), `RecordingAdmissionLatch.kt`,
  `DngPreCaptureAllocation.kt`, `OpticsConstraints.kt`, `ManualControls.kt` (handoff).
- ui: `CameraViewModel.kt` (FRONT toggle, retained-rear save substitution, `onLens`, zoom flush,
  dial doors, recall), `ZoomMath.kt` (mode remap, DNG remap, `retainedRearWireZoom`,
  `dngDoorRemapsZoomScale`), `controls/ProControls.kt`, `controls/ProSheet.kt`.
- `MainActivity.kt`, `CameraPermissionPolicy.kt`; `res/values/strings.xml` (caption).
- tests: `camera/PunchInResolvedTest.kt` (`rearReturnZoom` and `TenBitSessionWanted` coverage).
- docs: `CLAUDE.md`, `docs/plans/2026-10-02-rpl-cycle2.md`,
  `.context/reviews/archive-rpl-cycle2-2026-10-02/_aggregate.md`; cycle-2 commits 4edd2a14, b565abae,
  3ef6e845, b5c57e8a, 5e4cc454, 475dcf1f, c6caab59, d1341513, f64417fa, 15e5f3cb.
