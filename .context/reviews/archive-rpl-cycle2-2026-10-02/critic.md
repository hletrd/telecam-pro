# Critic review — RPL cycle 2 (2026-10-02)

Scope: the 51 commits `ba5b16e7..HEAD` (RPL cycle 1), checked against `CLAUDE.md`,
`docs/ARCHITECTURE.md`, `docs/plans/2026-10-02-rpl-cycle1.md` and the archived cycle-1 aggregate,
plus the code those commits touch (`CameraEngine` optics doors and rollback, `CameraViewModel` DNG,
exposure and restore paths, `VideoRecorder` storage tail and admission gate, `MediaStoreWriter`
probes, `CaptureOutputTracker`, `CameraController`, `SettingsStore`, release tooling).

This was a read-only review. I did not build anything or touch a device. Owner decisions were
treated as settled and are not reopened here: ZSL dark refusal, the FocusDetail threshold, the
declined CameraUnit SDK, proprietary HDR formats, and "orientation moves no control".

Verdict on cycle 1: most fixes are correct and narrowly scoped. Phase-1 PMA110 behavior is
unchanged except at the intended DNG-door seams. Two fixes are incomplete in the destructive or
divergent direction (CRIT2-1, CRIT2-2). One fix carries an older bug class into persistence
(CRIT2-3). Several "device-verified" claims now rest on code that changed after the verification
(CRIT2-9).

---

## CRIT2-1 — Optics rollback keeps a newer DNG direct write even when it changes the RESTORED route's answer

- **Where:** `camera/CameraEngine.kt:1011`
  (`if (rawWantedDirectWrites == before.rawWantedDirectWrites) rawWanted = before.rawWanted`).
  Direct-write branch: `camera/CameraEngine.kt:4005-4013`. Introduced by 3ec126e1.
- **Why:** the counter answers only one question: did a direct write happen? It does not check
  whether that write is route-neutral under the packet being restored. A direct write is
  route-neutral under the *desired* packet, for example a Video, EXTERNAL or not-started desired
  state. Rollback then restores a *different* packet (BACK Photo), and in that packet
  `standaloneRouteWanted(false, rawWanted, law)` does depend on DNG.
- **Failure scenario:** baseline is BACK Photo, DNG off, logical camera. The operator starts a door
  to the EXTERNAL route. The chip stays selectable there because `rawSelectable` only excludes FRONT
  and video/hi-res. The operator toggles DNG on, which is a direct write (route != BACK), so the
  counter becomes 1. The external open fails, and rollback restores BACK Photo on the logical
  session (`restoreSession == true`) while keeping `rawWanted = true`. This is the exact AGG-4
  state cycle 1 set out to remove:
  - The engine wants RAW over a logical session that has no RAW reader.
  - `lensBandFollowsZoom` and `standaloneRouteWanted` now read the restored unified ratio as
    lens-local.
  - The VM mirrors `dngRaw = true` (`ui/CameraViewModel.kt:973-977`).
  - `setRawWanted`'s change gate (`if (rawWanted == enabled) return`) refuses to repair it. The
    shutter writes no DNG, and the zoom band and readout misread the scale until the operator
    toggles DNG off and on again.
  - If `restoreSession` is false, the recovery reopen resolves a standalone lens but carries the
    restored *unified* zoom: the original AGG-1 "3× becomes a 9× crop" bug.
- **Reachability (honest):** the chip disables itself on FRONT and in non-Ready Video. That makes
  the scenario 3ec126e1 was written for (Video/FRONT toggles during an in-flight door) mostly
  UI-unreachable. The EXTERNAL door path is reachable, and so is any future non-UI caller. 3ec126e1
  therefore traded a mostly-unreachable revert for a reachable route divergence in the same space.
- **Fix:** in `commitOpticsRollbackLocked`, keep the newer value only when it is route-neutral for
  the restored packet. Restore `before.rawWanted` (and publish it) whenever
  `restored.route == BACK && standaloneRouteWanted(restored.mode == VIDEO, rawWanted, law) !=
  standaloneRouteWanted(restored.mode == VIDEO, before.rawWanted, law)`. Add a unit test: baseline
  Photo/BACK/DNG off, direct write DNG on under an EXTERNAL or Video desired packet, rollback. The
  test should assert `rawWanted == false` and that the publication carries false.
- **Confidence:** Medium (logic confirmed by reading; reachability is narrow). **Status:** likely.

## CRIT2-2 — The live finalized-video probe is still stricter than launch recovery in the destructive direction

- **Where:** `storage/MediaStoreWriter.kt:2579-2601` (`classifyFinalizedVideoTrack`: an extractor
  exception means `INVALID`) compared with `storage/MediaStoreWriter.kt:1657-1675` plus `:2255-2261`
  (recovery: `probeFinalizedVideo` throws, and `pendingProbeOutcome` maps the throw to
  `INDETERMINATE`, which keeps the row). Consumer: `video/VideoRecorder.kt:1966-1993`.
- **Why:** 1edb68c6 states that the live path "must not be the stricter one in the DESTRUCTIVE
  direction". It aligned only the *open* failure. A `MediaExtractor.setDataSource` or
  `getTrackFormat` exception after a successful open is still `INVALID` on the live path, and the
  stop tail then deletes the take. Launch recovery classifies the identical exception as
  `INDETERMINATE` and keeps the row.
- **Failure scenario:** the tolerated empty-audio stop (`muxer.stop()` throws over a sample-less AAC
  track) reaches the live reopen. The extractor throws an `IOException`, either transiently (fd/IO
  hiccup) or because of a container quirk that recovery would also refuse to judge. The live tail
  deletes a take that a relaunch would have kept pending. The opposite drift also exists: a
  genuinely unparseable container is deleted live but sits `REGISTERED` forever in recovery,
  occupying the finite recovery capacity.
- **Fix:** use one classifier for both paths. Either route recovery's VIDEO probe through
  `classifyFinalizedVideoTrack`, or make the live parse failure `INDETERMINATE`, which matches the
  documented rule that "an unknown answer never destroys user media". Pin the parse-failure case in
  the storage-tail test. The current test only distinguishes open from no-track.
- **Confidence:** High (code). **Status:** confirmed (by reading).

## CRIT2-3 — The rollback restores the requested video size over a later operator pick, and cycle 1 now persists the revert

- **Where:** `camera/CameraEngine.kt:974,1008` (the rollback restores `before.requestedVideoSize`),
  `camera/CameraEngine.kt:3692-3706` (`setVideoResolution` writes `requestedVideoSize` with no
  transaction and no write counter, and in Photo mode it does not reopen),
  `ui/CameraViewModel.kt:951` (b476d1dd mirrors the rollback into `requestedVideoResolution`), and
  `:1623` (that value is what gets saved).
- **Why:** this is the bug class 3ec126e1 fixed for DNG. A non-transactional operator write that
  lands while an older optics transaction is in flight is reverted when that transaction rolls back.
  Before b476d1dd the revert was engine-internal. It is now mirrored and persisted, so the picked
  size is lost across relaunch.
- **Failure scenario:** in Photo, a lens or TC door is in flight. The operator opens the video size
  picker and picks 1080p. The engine sets `requestedVideoSize`, applies the size without a reopen,
  and the VM stores `requestedVideoResolution = 1080p`. The door fails, rollback restores 4K, the VM
  mirror overwrites it, and the debounced save persists 4K.
- **Fix:** apply the same "newer write survives" rule as DNG, using a write counter captured in the
  snapshot. Size is not a route input, so it can be kept unconditionally once a newer write exists.
- **Confidence:** Medium. **Status:** likely. Needs a host test of the rollback ordering.

## CRIT2-4 — The VM DNG door does remap side effects even when the engine does not reopen

- **Where:** `ui/CameraViewModel.kt:2436-2475`.
- **Why:** `routeOptics` is non-null whenever the VM's standalone answer flips. That includes
  EXTERNAL (`lensLocalRoute` is true, so the remap is a no-op) and TC. Either way, the VM runs
  `cancelPendingControls()`, `invalidateOpticsDerivedState()` and `clearTapFocusUi()`.
  - **EXTERNAL:** `setRawWanted` only records the field (`route != BACK`) and nothing reopens. The
    engine's tap-focus owner (`tapFocusOwner`, bound to the accepted session) therefore keeps the
    `AF_MODE_AUTO` hold on the wire, while the UI drops `tapPoint`/`tapFocusHeld`. The OSD says
    nothing is held while the lens is locked.
  - **TC (BACK):** the route answer "flips" even though `cachedIdForFocal(TELE3X)` returns the
    same camera. The engine performs a full same-id reopen, which costs a black dip and drops tap
    focus, purely because of the DNG chip. This reopen predates cycle 1, but cycle 1 is where the
    door was formalized.
- **Fix:** gate the VM side effects on the same condition the engine uses to reopen:
  `route == BACK && started && flips`. In the engine, skip the reconfigure when the resolved camera
  id is unchanged (TC or a user pin) and only publish `rawWanted`.
- **Confidence:** Medium. **Status:** likely (EXTERNAL needs a manual check; TC reopen confirmed by
  reading).

## CRIT2-5 — The VM decides the DNG zoom remap with a state copy of the RAW law that defaults to PMA110's answer

- **Where:** `ui/CameraViewModel.kt:2436-2446` uses `s.rawForcesStandalone`. The default is `true`
  (`camera/CameraState.kt:1658`), and it is published only by `onCameraRouteInventory` (`:774`) and
  `applyEncoderInventory` (`:2725`). The engine uses `activeDeviceProfile().rawRequiresStandalone`,
  which is `false` on GENERIC (`camera/DeviceProfile.kt:79`).
- **Failure scenario:** on a GENERIC device, a DNG toggle before route inventory publishes makes the
  VM remap lens and zoom (unified 3.0 becomes `TELE3X` at local 1.0). The engine sees
  `routeFlips == false`, so it records only the field and ignores the resolved packet. The VM now
  shows a lens-local packet over the logical session, and the next throttled control apply pushes
  zoom 1.0 to the logical camera, snapping it to 1×. `applyLoaded` already avoids this by reading
  `engine.rawForcesStandalone` directly (`:1324`).
- **Fix:** use `engine.rawForcesStandalone` here, or better, have `setRawWanted` compute the remap
  itself from the engine's own `rawWanted` and law. Then the VM and engine cannot disagree about
  whether the door moved.
- **Confidence:** Medium (narrow window, non-PMA110 only). **Status:** likely.

## CRIT2-6 — The rollback DNG mirror misses the pre-inventory pending formats

- **Where:** `ui/CameraViewModel.kt:973-977` (rollback updates `photoFormats` only), `:1582-1584`
  (`currentExtras` persists `pendingPhotoFormatsUntilInventory` while inventory is pending), and
  `:2695,2715` (`applyEncoderInventory` replays the pending formats into `setRawWanted`).
- **Failure scenario:** a DNG door rolls back during the cold-start window before the encoder
  inventory lands. The UI chip reverts, but the pending copy still says DNG on. Any save persists
  DNG on (this is the AGG-34 path), and when the inventory lands, `setRawWanted(true)` re-flips the
  route that rollback just restored.
- **Fix:** when inventory is pending, also set `pendingPhotoFormatsUntilInventory =
  pending.copy(dngRaw = rollback.rawWanted)` in the rollback handler.
- **Confidence:** Medium. **Status:** likely (needs a VM test).

## CRIT2-7 — The characteristics retry (AGG-19) covers stills but not metering regions

- **Where:** `camera/CameraController.kt:1308`. `applyMetering` still reads
  `rawChars?.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return`. The lazy retry exists only in
  `tryComplete` (`:2266`).
- **Failure scenario:** the transient open-time read failure that 1c5a0060 targets also makes every
  tap-AF, AE region and spot/center metering request silently omit its regions. The tap reticle
  appears, but the HAL never receives a region. This lasts until the first still re-reads the
  characteristics.
- **Fix:** route `applyMetering` through the same `rawChars ?: readRawCharacteristics()` helper,
  ideally a single `chars()` accessor.
- **Confidence:** High (code). **Status:** confirmed (by reading). Low severity.

## CRIT2-8 — The recall exposure clamp ignores DNG as a route input

- **Where:** `ui/CameraViewModel.kt:1367-1382`. `restoredRouteUsesCurrentCaps` compares mode, lens,
  TC, override and facing, but not the DNG route answer.
- **Failure scenario:** recalling a Photo/DNG-on bank on the same lens band from a Photo/DNG-off
  (logical) state treats the outgoing logical camera's caps as authoritative. The saved photo
  exposure is then clamped against camera 0's range before the standalone route's caps exist. The
  code comment itself forbids this ("Outgoing caps are not authoritative across mode/lens
  recalls"). The real impact depends on how far the two ranges differ on PMA110 (both share the
  4 s ceiling), so this may be invisible there and wider on GENERIC devices.
- **Fix:** add `currentStandalone` and `targetStandalone` to `restoredRouteUsesCurrentCaps`, and
  return false when they differ.
- **Confidence:** Medium. **Status:** needs-manual-validation (range delta on device).

## CRIT2-9 — Device evidence now predates the code it vouches for, and the progress log does not flag it

- **Where:** `video/VideoRecorder.kt:1242-1314` (4e57fff2) and `CLAUDE.md` "five of five takes carry
  AAC". The plan log lines 167-176 do not mark lane B items PENDING DEVICE.
- **Why:** the TB336ZU 5/5 AAC result was measured with 0ab5c1ba's semantics, in which the pending
  token's *owner* was admitted. Under that rule, the same Engine's GL/EGL and Camera2 acquisitions
  were also admitted during pending setup. 4e57fff2 restores the pre-0ab5c1ba refusal for those
  and admits only the token's workers. The design reasoning is sound, but two things are
  unverified:
  - Whether anything on the MediaTek path relied on Engine-owned acquisitions during pending setup,
    such as a preview rebind or an encoder-candidate bind outside `runPendingNative`.
  - Whether the audio worker still always reaches `startRecording` through the token door.
  
  The progress log marks only Phase 1 and 2 camera/GL items PENDING DEVICE. Lane B items, including
  the token door (P3.5) and the characteristics retry (P3.4), are recorded as done with no device
  caveat. That breaks the plan's own rule ("every such item stays PENDING DEVICE").
- **Fix:** mark P3.5 PENDING DEVICE (TB336ZU: 5 takes with a slow first swap, checking AAC presence
  and `AudioRecord start` in logcat) and P3.4 PENDING DEVICE. Until then, re-qualify the CLAUDE.md
  sentence as evidence for 0ab5c1ba, not for the current door.
- **Confidence:** High (process). **Status:** confirmed.

## CRIT2-10 — The upload-key gate is self-attested and does not refuse the known blocked certificate

- **Where:** `tools/build_immutable_release.py:524-660`. Approval and fingerprint are both read from
  the same `keystore.properties`. `tools/check_release_artifact.py:45-47` still pins the blocked
  key's certificate as the EXPECTED signer.
- **Why:** writing `uploadKeyRotationApproved=true` plus the current (blocked) key's SHA-256 into the
  local properties passes the gate, and the artifact checker then *requires* the blocked
  certificate. After a real rotation, the checker fails every correctly signed bundle. The plan
  already lists the checker pin as a "SEC-02 tail"; the gate half is new.
- **Fix:** add an explicit denylist entry for the blocked fingerprint in the gate. Make the checker
  read the approved fingerprint from the same source the gate verified.
- **Confidence:** High (code). **Status:** confirmed. Low severity (owner-controlled tooling).

---

## Claims in the cycle-1 log that I checked and accept

- **AGG-1/2/3 (89bb7aab, 99e7af87):** scale conversion in both directions, the restore lens-local
  branch, and the single `lensBandFollowsZoom` predicate are correct. The recall split (setResolvedOptics
  then setRawWanted) is safe because the second transaction supersedes the first before its
  `setupExecutor` task runs, and the recalled controls are already in the target scale.
- **AGG-8/9/10 (1e79810e):** handoff seeding is correct. The ISO+ANGLE restore carries the applied
  exposure. App-side P hands over its own still exposure.
- **AGG-16/17/18 (1edb68c6, a1fee383, fc8a458c):** open-failure retention (see CRIT2-2 for the
  parse gap). The cursor-advance condition cannot loop. The prior-family output denylist is bounded
  and cleared for survivors.
- **AGG-26 (98164b9e):** the LRU-bounded gate is correct. A reason that flaps between two values
  still spends one row per flip, which is acceptable.
- **AGG-45 (d255afbc), AGG-55 (87932ced), AGG-66 (81a0b55a), AGG-33 (8780b494), AGG-34/35
  (7c76e7bc):** correct as written.

## Final sweep notes (no separate finding)

- fe7d0578 clears the audio-denial flag when `activeMemorySlot == slot` after the call. A refused
  re-recall of an already-active slot would also pass this check. It is harmless unless a denial can
  occur without clearing `activeMemorySlot`. Not verified, so not filed.
- PMA110 byte-identity: the only intentional PMA110 behavior changes are the DNG door (remap plus
  transaction), exposure handoffs, aspect refusal mid-REC, and the debug-only nativelog session
  change. I found no unintended PMA110 path change, apart from CRIT2-4's TC reopen, which predates
  cycle 1.
