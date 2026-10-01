# Critic review: RPL cycle 3 (2026-10-02, HEAD e3a2bdd4)

Scope: the cycle-1 and cycle-2 change surface (`git log c2892dda~40..HEAD`). I checked each one
against what its commit message and the cycle-2 plan claim, and judged it from five viewpoints:
operator UX, maintainer, Play reviewer, a multi-device user on a non-PMA110 handset, and QA.

This was a read-only review. I did not run Gradle and did not touch a device. Owner decisions
(ZSL dark refusal, FocusDetail threshold, CameraUnit/OCS, proprietary HDR, orientation-keyed
layouts, shader code numbering, history rewrite) are treated as settled.

I read the cycle-2 aggregate (`archive-rpl-cycle2-2026-10-02/_aggregate.md`) and
`docs/plans/2026-10-02-rpl-cycle2.md` first. Items that were already fixed are not repeated. Two
items are carried because I have new evidence for them (CRIT3-1, CRIT3-3).

## Verdict on cycle 2

Most cycle-2 fixes do what they claim. I verified each of the following from code:

| Commit | AGG2 item | What I verified |
|---|---|---|
| `4edd2a14` | AGG2-2 | Recall DNG now rides one transaction. |
| `b565abae` | AGG2-1 | Route-aware rollback keep rule. |
| `3ef6e845` | AGG2-3 | `StillContinuationHandoff` is exactly-once in both orders. |
| `b5c57e8a` | AGG2-4 | Preflight Ready restore. Only the door's own invalidation generation is restorable. |
| `5e4cc454` | AGG2-10 | The TELE reader plan really never reads `rawWanted`. `sessionAttemptPlan` has no such input (`CameraController.kt:2725-2738`). |
| `4d0c5e3c` | AGG2-7 | Size direct-write counter. |
| `c4cc92e7` | AGG2-18 | Extractor throw maps to INDETERMINATE. |
| `c6caab59` | AGG2-11 | The DNG door now reads the engine's RAW law. |
| `2d391a7d` | AGG2-16 | The P seed clamp residual is carried into ISO. |
| `15e5f3cb` | AGG2-17 | Dial doors use `exposureModeHandoff`. |
| `8f84b0bc` | AGG2-14 | Dual-open pause recheck. The refused candidate is closed. |
| `b5e8d622` | AGG2-15 | Detach-then-purge plus `cleared` guards. |

EN/KO string parity also holds: no translatable EN key is missing from `values-ko`.

Three fixes are incomplete or move the bug somewhere else:

- **CRIT3-1:** the AGG2-5 zoom fix covered only the persistence twin. The live FRONT→rear return
  still misframes, and over a wider range than the plan note says.
- **CRIT3-3:** the AGG2-26 audio fix re-opens AGG-37.
- **CRIT3-2:** the new DES2-2 copy, and the AGG2-18 "retain, never delete" choice, make a promise
  that a platform rule breaks. MediaProvider deletes pending rows on its own after about 7 days.

---

## CRIT3-1: Leaving FRONT still divides by a ratio-picked lens base. The AGG2-5 fix made persisted and live framing disagree.

- **Severity:** Medium. **Confidence:** High. **Status:** Confirmed by reading (pure arithmetic).
  The visible framing needs a device check.
- **Where:**
  - `camera/CameraState.kt:606-614` (`rearReturnZoom` → `localZoomOf(unified)`).
  - Engine call site `camera/CameraEngine.kt:3991-3998`.
  - VM mirror `ui/CameraViewModel.kt:2997-3006`.
  - Compare with the cycle-2 fix `ui/ZoomMath.kt` `retainedRearWireZoom`, which is lens-based and
    used only for save/MR store.
- **Why:** the FRONT-entry snapshot (`CameraEngine.kt:3976-3981`, VM `:2969-2978`) converts
  lens-local to unified using the lens's own optical base: `opticalBaseFor(lens.zoomPreset)`.
  Leaving FRONT converts back with `localZoomOf(unified)`, whose base is picked from the ratio. The
  lens is not changed on return (`lensChoice` and `it.lens` are kept), so the wire ratio is
  interpreted on the old lens with the wrong divisor.
- **Scope is wider than the plan's lane note.** The note says "past 3.33". In fact it fails
  whenever the unified value crosses the next optical preset:
  - **Video, WIDE lens at local 2.0** (unified 1.2): `localZoomOf(1.2)` divides by 1×, giving 1.2
    on the 0.6× lens. Framing goes from 1.2× to 0.72×.
  - **Video or Photo+DNG, MAIN lens at local 4.0** (unified 4): the divisor is 3×, giving 1.33 on
    the 23 mm lens. Framing goes from 4× to 1.33×.
  - **TELE (TC on) at local 4.0** (unified 12). TC does not survive the trip. In Video the lens
    stays `TELE3X` and `localZoomOf(12)` divides by 10, giving 1.2 on the 3× lens. Framing goes
    from 12× to 3.6×.
- **Why cycle 2 made it worse in one respect:** a save made WHILE in FRONT now persists the correct
  lens-local value via `retainedRearWireZoom`. If the operator instead flips back and then
  backgrounds, the wrong live value is what gets persisted. The two paths that are supposed to be
  "the same rear setup" now restore different framings.
- **Failure scenario:** in Video on the 1× lens, pinch to 4×, tap selfie, tap back. The finder
  lands at about 1.33×, and the next background save persists 1.33.
- **Fix:** replace `rearReturnZoom`'s `localZoomOf(unified)` with the lens-based inverse, i.e.
  `retainedRearWireZoom(unified, lens = lensChoice, teleconverter = false, targetStandalone, optical)`.
  Use it at both the engine and the VM call site so they share one pure function.
- **Host test:** for each optical lens L and local z ∈ {1.0, 2.0, 4.0}, check
  enter(FRONT) → leave(FRONT) returns z on Video and on Photo+DNG.

## CRIT3-2: "Retained" pending rows are deleted by MediaProvider after about 7 days. The new copy promises a save the app cannot guarantee.

- **Severity:** Medium. **Confidence:** High for the platform fact, Medium for the impact.
  **Status:** Likely. Needs a device check of `date_expires` on a retained row.
- **Where:**
  - `res/values*/strings.xml` `status_dng_save_delayed`, `status_output_saved_pending`,
    `status_video_save_delayed` (49f435e1).
  - The whole retained-row design in `storage/MediaStoreWriter.kt`.
  - `c4cc92e7`, which now retains every extractor-throw take.
  - `grep -rn DATE_EXPIRES app/src/main` finds no match, and neither ARCHITECTURE.md nor
    FIELD_CHECKS.md mentions expiry.
- **Platform fact:** `MediaStore.MediaColumns.DATE_EXPIRES` is computed automatically when
  `IS_PENDING` is set. The pending default is 7 days. Pending items are kept only until they are
  published or until they expire. MediaProvider deletes expired items during the next device-idle
  maintenance. Sources:
  [MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns),
  [MediaProvider MediaStore.java](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/84583bc1a0936aeb3e348fd9d2f2ab63d5657066/apex/framework/java/android/provider/MediaStore.java).
- **Why it matters:** every "fail closed, keep it private for launch recovery" branch assumes
  pending rows are durable until the app comes back. That covers: COMPLETE-marker exhaustion,
  transient publish failure, INDETERMINATE video probe, identity-uncertain rows, and DNG
  marker-failed rows. The platform does not keep them that long.
  - The copy now says "It will be saved the next time the app starts". A user who does not reopen
    the app within the expiry window (the device just has to idle and charge once) loses the take.
    Nothing is logged by the app, and the gallery never showed it.
  - Recovery that keeps a row INDETERMINATE or REGISTERED on every launch also lets its expiry run
    out, because nothing re-arms it.
- **Fix:**
  1. When launch recovery leaves a row pending (INDETERMINATE or REGISTERED retained), re-arm its
     expiry, either by writing `DATE_EXPIRES` explicitly or by re-asserting `IS_PENDING=1` (device
     check which one MediaProvider honors for the owner).
  2. Add an in-session publish retry for rows that are complete but unpublished, so the common
     transient case does not depend on a relaunch.
  3. Soften the copy to the truth ("…will be saved when the app next opens") only once (1) is in
     place.
  4. Document the expiry rule in ARCHITECTURE.md beside the durable-states bullet.
- **Host test:** recovery that retains a row issues the re-arm update (fake resolver).

## CRIT3-3: f64417fa fixes AGG2-26 by re-opening AGG-37, because a bank's audio-off has no provenance

- **Severity:** Medium. **Confidence:** Medium. **Status:** Confirmed by reading.
- **Where:**
  - `MainActivity.kt:517-530`.
  - `CameraPermissionPolicy.kt` `audioDenialReasonClearedByRecall`.
  - `MicrophoneGrantRestoreTest.kt:88-93`, which pins `recallApplied=true, recordAudio=false → keep`.
- **Why:** the old rule (clear on every applied recall) protected a deliberately silent bank from a
  later grant forcing audio back on (AGG-37 / tracer T9). The new rule (clear only when the bank
  wants audio) protects a denial-snapshot bank. Neither rule can tell the two apart, because
  `ExtraSettings.recordAudio` is a bare Boolean.
- **Failure scenario:** the mic is denied, so `AUDIO_OFF_BY_DENIAL_KEY = true`. The operator
  recalls bank B, which they saved silent on purpose for a wind-noise setup. The key stays true.
  They later grant the mic in Settings. `audioRestoredByMicrophoneGrant` turns audio ON over the
  silent bank, which is exactly the AGG-37 report.
- **Fix:** persist provenance per bank. Either add `recordAudioOffByDenial` to the bank, or save
  `recordAudio = true` plus a denial flag when the off state came from denial. Recall then sets the
  key from the bank's own provenance. Replace the 4-row truth-table test with a round-trip test:
  save while denied, recall, grant → audio on; save silent deliberately, recall while denied,
  grant → audio stays off.

## CRIT3-4: Recall resolves the restored zoom scale with the CURRENT route's RAW law, but recall can change the route

- **Severity:** Low. **Confidence:** Medium. **Status:** Likely. It is reachable only where an
  external camera route exists on a device whose base profile has `rawRequiresStandalone`, which
  today is PMA110 only.
- **Where:**
  - `ui/CameraViewModel.kt:1341-1345` (`restoredRouteStandalone` uses `engine.rawForcesStandalone`).
  - `camera/CameraEngine.kt:1740` (`rawForcesStandalone` → `activeDeviceProfile()`).
  - `camera/CameraEngine.kt:1250-1251` (EXTERNAL route → `DeviceProfile.GENERIC`).
  - `camera/CameraSelector2.kt:79-89` (`recalledCameraRoute` returns BACK whenever a back camera
    exists, even from EXTERNAL).
  - Engine side: `setResolvedOptics` sets `activeCameraRoute = BACK` first, so its band predicate
    uses the BACK law (`CameraEngine.kt:2830, 2912`).
- **Failure scenario:** the operator is on the EXTERNAL route and recalls a PMA110 bank saved as
  Photo + DNG, lens 3×, lens-local 1.0.
  - The VM computes `standaloneRouteWanted(false, true, GENERIC=false) = false` and builds a
    unified packet, so lens and zoom are derived as a logical 1.0.
  - The engine resolves BACK with the PMA110 law (`true`) to a standalone lens, so the recalled 3×
    bank lands at 1×.
  - `restoredRouteUsesCurrentCaps` has the same split.
- **Fix:** expose `engine.rawForcesStandaloneFor(route)` (or a pure
  `deviceProfileForRoute(base, route == EXTERNAL)` in the VM). Evaluate it for the restored route,
  not the active one. AGG2-11 fixed the analogous default-copy split for the DNG door. This is the
  route-change variant of the same split.

## CRIT3-5: Route-aware rollback silently discards the operator's DNG pick

- **Severity:** Low. **Confidence:** High. **Status:** Confirmed by reading. This is UX, not
  corruption.
- **Where:** `camera/CameraEngine.kt` `rollbackRawWanted` (b565abae) together with the VM mirror at
  `ui/CameraViewModel.kt:984-1000`.
- **Why:** the rule correctly refuses to pair the restored session with a route-moving intent.
  However, the operator's explicit choice is dropped with only the generic
  "camera unchanged" status.
- **Example:** Photo, DNG off, on the logical camera. The operator taps TELE. While that door is
  in flight they turn DNG on, which counts as a direct write because TC is the desired state. The
  TC open fails. Rollback restores logical Photo and drops DNG. The chip turns itself off, and the
  next shots have no DNG.
- **Fix (cheap):** when `rollbackRawWanted` returns the baseline while a newer direct write
  existed, publish a DNG-specific status, EN and KO. Example: "DNG turned off; camera unchanged". An
  alternative is to re-issue `setRawWanted(true)` after the rollback commits, so the intent is
  applied as its own transaction on the restored route.

## Notes, not new findings

- The AGG-30 tail is still open at `camera/CameraEngine.kt:7573`. The launch-recovery backoff is
  still `runCatching { Thread.sleep(...) }`, so it clears the interrupt flag. Plan item B.11 says
  "retry sleeps preserve the interrupt flag". That is true for `MediaStoreWriter` only. Use
  `sleepPreservingInterrupt` here too.
- `15e5f3cb` dial handoff in Video P with `ShutterMode.ANGLE`: `exposureModeHandoff` seeds
  `exposureTimeNs`, which ANGLE ignores, so an ISO-dial takeover keeps the stored angle. This is
  the same pre-existing behavior as `onExposureMode`, not a regression.
- `5e4cc454`: a TELE session that came up on a degraded no-RAW rung is no longer re-laddered by a
  DNG toggle. Before this commit, the reopen was a second chance. This is acceptable, but the chip
  should stay gated by accepted outputs on TELE.
- The SettingsStore `save()`/`savePreset()` durability results are still ignored by callers (lane
  note). The MR "saved" UI shows success on a non-durable commit.

## Files examined

- **Engine/camera:** `camera/CameraEngine.kt` (optics transactions, rollback, setResolvedOptics,
  setRawWanted, setFrontCamera, reconfigureCamera, dual-open, cleanupOrphans),
  `camera/CameraState.kt`, `camera/CameraSelector2.kt`, `camera/DeviceProfile.kt`,
  `camera/CameraController.kt` (sessionAttemptPlan, capture readers),
  `camera/DngPreCaptureAllocation.kt`, `camera/AutoExposure.kt`, `camera/ManualControls.kt`,
  `camera/LaunchMediaRecoveryCoordinator.kt`.
- **UI/VM:** `ui/CameraViewModel.kt` (rollback handler, applyLoaded, onSetPhotoFormats,
  applyEncoderInventory, front flip, dial doors, onCleared, recallMemorySlot), `ui/ZoomMath.kt`,
  `ui/review/MediaReview.kt`, `MainActivity.kt`, `CameraPermissionPolicy.kt`.
- **Storage/video:** `storage/MediaStoreWriter.kt` (retry backoff, finalized-video probe),
  `storage/SettingsStore.kt`, `video/VideoRecorder.kt` (startup deadline, storage tail).
- **Resources/build/tests:** `res/values/strings.xml`, `res/values-ko/strings.xml` (parity check),
  `app/build.gradle.kts` (release signing gate), `MicrophoneGrantRestoreTest.kt`,
  `KoreanLocalizationRobolectricTest.kt`.
- **Docs:** `CLAUDE.md`, `docs/plans/2026-10-02-rpl-cycle2.md`, the cycle-2 aggregate and critic
  reviews.
