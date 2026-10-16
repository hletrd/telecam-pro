# Test-engineer review: RPL cycle 4 (HEAD 14767b0a, 2026-10-02)

Scope: whether the cycle-3 tests (`git log e3a2bdd4..HEAD -- app/src/test tools/tests`, 29 fix
commits plus follow-ups) exercise the production paths they claim to cover, new gaps and vacuous
assertions, flakiness, the coverage gate (`tools/coverage/partition_report.py` with its residual
manifest), and the Python tool tests. This was a read-only pass: I ran no Gradle, edited no files,
and verified everything by reading the code and git history. Findings already recorded in
`archive-rpl-cycle3-2026-10-02/` (AGG3-*, TE3-*) are not repeated unless cycle 3 changed them.

## Cycle-3 fix → regression-test audit

| Commit | Fix | Does reverting the production line turn a test red? |
|---|---|---|
| 58eb4f10 | RAW off every rung of a 10-bit session | **Yes.** It is an exhaustive loop over both ladders. |
| 6ba15217 | FRONT return on the retained lens base | Engine: **yes** (`FacingRollbackPunchInRobolectricTest`). VM `onToggleFrontCamera`: **no** (TE4-3). The "live == persisted" assertion is tautological (TE4-3). |
| c5bfd1c8 | Bare-reopen preflight stays Not-Ready | **Yes.** It drives `currentOpticsReconfiguration` and `rollbackOpticsAfterPreflight` by reflection. |
| f32a5bbd | Per-shot characteristics re-read | Pure helper: yes. The `chars(shot = true)` call site: **no**, and it is silent because of a `= false` default (TE4-2). |
| f603a208 | Live DNG intent in the rollback mirror | **Yes.** It is a real VM and engine sequence. |
| 04fad2e8 | Caption keyed on accepted HLG | Caption mapping: yes. The controller's `hlgSessionAccepted = useHlg`: **no**, because the test rebuilds that wiring itself, and it is silent because of the defaults (TE4-2). |
| 176e2199 | Engine-law standalone helper | 1 of the 8 converted sites is tested (TE4-7). |
| 9cfd288a | Fix-off defaults removed | Yes, enforced at compile time. The same cycle added three new ones (TE4-2). |
| 0b2a6e99 | Shared DNG settle wiring (AGG3-39) | **No.** The line that carried the AGG2-3 bug is still engine-only (TE4-1). |
| ce60c1a8 | Interrupt kept across recovery backoff | Coordinator: yes. Engine lambda: no (a one-liner, low risk). |
| 2295ea79 | Gridded idat HEIF adopted | Synthetic fixture only, and its field widths are not the measured ones (TE4-8). |
| 01c42176 / dd91413c | Delete after a muxer-stop throw plus an unparseable file | Pure classifier: yes. But the premise is likely wrong in production (TE4-4). |
| 5ebe8b07 | Kept rows re-assert IS_PENDING | **Yes.** It runs through the real `cleanupOrphanedPendingBatch` with a fake provider. |
| a36c5889 | MR bank audio provenance | Pure functions, store round trip, and VM storage: yes. The MainActivity wiring: **no** (TE4-6). |
| 5a3b89e1, 6cae37ed, 928745b3 | Format request / readout | Yes. |
| cb62f4ea, 6abb57ac | Standby release, NV21 drop | Yes. |
| c70af129 | Residual line identity | Gate: yes. The documented regeneration step defeats it (TE4-5). |
| 638b666a, 4e603375 | Injected-signing refusal, packageRelease refusal | Source-text assertions only (TE4-9). |
| 8d0d6f11, 5398acd8 | Fixture repos, tracked-docs scan | Yes. |

## Findings

### TE4-1: The AGG3-39 "shared wiring" still leaves the AGG2-3 bug line engine-only, so reverting it keeps the suite green (Medium, High). Confirmed.

- **Where:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4876-4891` and
  `DngPreCaptureAllocation.kt` `dispatchDngPreCaptureAllocation`; test at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/DngPreCaptureAllocationTest.kt:266-277`.
- **Why:** The AGG2-3 fix (3ef6e845) made one change at the bug site. Inside
  `settleBeforeCamera()`, `settleRegisteredStillShot(onDone = onDone)` became
  `onDone = continuation::settle`. 0b2a6e99 extracted only the four-line handoff
  (`StillContinuationHandoff` → build → register → `dispatchResult(start())`). The `build` lambda
  stays in each caller:
  - The engine's `build` routes `onRetired` → `settleBeforeCamera()` →
    `settleRegisteredStillShot(onDone = settle)`.
  - The test's `build` passes `onRetired = settle` directly (line 274).
  So the line where the original defect lived is still covered by nothing.
- **Failure scenario:** someone changes `onDone = settle` back to `onDone = onDone` at
  `CameraEngine.kt:4890`, for example while refactoring `settleBeforeCamera`. Every test passes. A
  saturated allocator then forks timelapse ticks again (2^n live tasks), and AEB resets controls in
  the middle of a bracket.
- **Fix:** extract the engine's owner factory, the settle-before-camera part, into an internal
  function that takes `settle`, the release hooks, and `settleRegisteredStillShot` as injectable
  effects, and have the test drive that function. Alternatively, add a Robolectric engine test:
  saturate `ProcessPreNativeMediaAllocator` and assert exactly one of {`onDone` invoked,
  `dispatchStillCapture` returned false} per tick.

### TE4-2: Cycle 3 reintroduced the AGG3-41 "fix-off default" pattern in three new parameters, and the caption test rebuilds the controller wiring itself (Low-Medium, High). Confirmed.

- **Where:**
  - `CameraController.kt:2430`: `private fun chars(shot: Boolean = false)`. The fix lives only in
    the call `chars(shot = true)` at :2282.
  - `CameraController.kt:2572`: `acceptedPhotoSessionOutputs(hlgSessionAccepted: Boolean = false)`.
    The fix lives only in `hlgSessionAccepted = useHlg` at :793.
  - `ProControls.kt:1070`: `PhotoFormatToggles(hlgSessionAccepted: Boolean = false)`, fed by
    `ProSheet.kt` `state.photoSessionOutputs.hlg`.
  - Test: `NoStillOutputCaptionTest.kt:17-31` calls `acceptedPhotoSessionOutputs(...,
    hlgSessionAccepted = plan.useHlg)` itself.
- **Why:** Cycle 3 removed exactly this pattern (9cfd288a) because "dropping an argument is
  silent". The new tests cover the pure helpers (`lazyCharacteristicsRead`, `noStillOutputCaption`)
  but not the arguments at the call sites. The caption test even supplies the very argument whose
  omission would be the bug.
- **Failure scenario:** a refactor of `tryComplete` writes `chars()`, so AGG3-9 returns: a
  metering build spends the gate and the next still fails "Missing camera characteristics" after
  the HAL delivered it. Or the `hlgSessionAccepted = useHlg` line is lost in a ladder edit, and
  every real 10-bit session reads "Still capture unavailable" instead of the designed "10-bit
  video · stills off". All tests stay green in both cases.
- **Fix:** remove the defaults (`shot`, `hlgSessionAccepted` in both functions) so omission fails
  to compile, as 9cfd288a did. For the caption, have `CameraController` build its outputs through
  one function that takes the `SessionAttemptPlan`, for example
  `acceptedPhotoSessionOutputs(plan, readers…)`, and have the test call that function instead of
  copying the field mapping.

### TE4-3: The FRONT round-trip test has a tautological "live == persisted" assertion, and the ViewModel half of AGG3-1 is still unexercised (Low, High). Confirmed.

- **Where:** `app/src/test/kotlin/me/hletrd/telecampro/camera/PunchInResolvedTest.kt:93-99`;
  `ui/ZoomMath.kt:438-452`; `ui/CameraViewModel.kt:2956-3011` (`onToggleFrontCamera`).
- **Why:** 6ba15217 made `retainedRearWireZoom` delegate to `rearReturnZoom`. The new assertion
  `retainedRearWireZoom(entry, lens, …) == rearReturnZoom(true, entry, lens.zoomPreset, …)`
  therefore compares a function with itself. It can only fail if the delegation is undone.
  Separately, `grep onToggleFrontCamera app/src/test` finds a single reference, in
  `PerformQuickFnTest`, and none that asserts zoom. The VM computes its own snapshot from
  `before.lens` and `standaloneRouteFor(before)`, and its own return from `it.lens` and
  `standaloneRouteFor(it)`, independently of the engine. Only the engine half is tested. The plan
  (A.2) says "Engine and VM keep calling the one function", but nothing pins the VM's arguments.
- **Failure scenario:** a VM-side argument drifts. Examples: passing `it.lens.zoomPreset` for a TC
  route where the engine uses TELE3X, or using the pre-flip state for `targetStandaloneRoute`. The
  UI then shows and persists a zoom that differs from what the engine landed on. This is the
  AGG3-1 symptom class ("live return disagrees with the save path"), and no test catches it.
- **Fix:** delete the tautological assertion, or replace it with an independent oracle such as
  literal expected locals. Add a VM Robolectric test: Video, TELE3X at local 4 → flip to FRONT →
  flip back. Assert that `vm.state.controls.zoomRatio == 4f` and that it equals the engine's
  `controls.zoomRatio`. Repeat for Photo with DNG on (standalone) and off (logical, unified).

### TE4-4: AGG3-6's "a muxer.stop() throw proves the moov was never written" is likely false for the only path that reaches it, so the live tail can delete a good take (Low-Medium, Medium). Likely; needs manual validation.

- **Where:** `storage/MediaStoreWriter.kt:2690-2722` (`classifyFinalizedVideoTrack`, `INVALID`
  after a second parse throw); `video/VideoRecorder.kt:451-472` (the only place that sets
  `FinalizedRecordingValidation.SKIPPED`); `VideoRecorder.kt:2052` (hard-coded
  `muxerStopThrew = true`); test `FinalizedVideoTrackProbeTest.kt:77-136`.
- **Why:** The live validation runs only when `muxerStopFailureIsTerminal(...)` is false. That is
  the TR4-2 corner: audio degraded mid-REC, the audio track was added but holds zero samples, and
  the video track is complete. The recorder's own comment (VideoRecorder.kt:452-456) says stop()
  throws there "over the empty audio track while the video track is complete".
  In AOSP `MPEG4Writer`, a track with no samples reports `ERROR_MALFORMED`. `reset()` writes the
  moov anyway for that error ("Do not write out movie header on error except malformed track"),
  then returns the error, and `MediaMuxer.stop()` throws. In the one case that reaches this probe,
  the stop throw is therefore expected with a finalized container. It is not independent evidence
  of corruption, as the KDoc claims.
  The new INVALID verdict deletes the take after two extractor throws. That is exactly the
  transient-FUSE destructive direction AGG2-18 removed. The retry also reuses the same
  `ParcelFileDescriptor` (`hasVideoTrack(descriptor)` twice), so a stale or poisoned FUSE fd fails
  identically both times.
- **Failure scenario:** the mic drops mid-REC before its first sample, and the provider is briefly
  busy (media scan) when the tail probes. Two extractor throws on the same fd lead to INVALID,
  `FAILED`, and the good clip is deleted. Before cycle 3 it was retained for recovery.
  `FinalizedVideoTrackProbeTest` asserts the contested premise, so the suite cannot reveal this.
- **Fix:** keep INDETERMINATE (retain) on parse throws, and solve AGG3-6's "retained forever" half
  in recovery. Either persist the stop-threw fact in the REGISTERED journal so launch recovery can
  apply a bounded "INVALID after N failed launches", or at minimum re-open a fresh descriptor for
  the confirming parse. Before choosing, check on device: force the sample-less audio path and see
  whether `MediaExtractor` parses the file after the stop throw. If it does, the "independent
  evidence" wording must go.

### TE4-5: The documented `--write-regions` step launders exactly the TE3-1 scenario the line-identity gate was added to catch (Medium, High). Confirmed.

- **Where:** `tools/coverage/partition_report.py:342-360` (`regenerated_manifest`: `reason =
  previous[1]` keyed by class name only); the docstring at :39-44; the manifest header in
  `tools/coverage/partition-a-residuals.txt`.
- **Why:** c70af129 makes the gate fail when a class's missed lines leave the cited region, the
  case of "a reviewed guard became covered while a new miss appeared elsewhere in the same class".
  The prescribed remedy for any line drift is to rerun with `--write-regions`, which:
  1. recomputes the region from whatever lines are now missed;
  2. carries the old rationale forward whenever the class name and count still match;
  3. marks a row `UNREVIEWED` only for a brand-new class.

  So a same-class, same-count substitution is re-explained by a rationale about a different line,
  and the result looks reviewed. Cycle 3 itself regenerated three times (c70af129, 6ec34b3d,
  777b0ee1) with no recorded content check. I re-checked all nine current regions against their
  rationales and they are correct today, but nothing in the tool enforces that.
- **Failure scenario:** a commit covers the `LensChoice.entries` `?: continue` fallback and adds a
  new unreachable-looking miss in another `CameraStateKt` function. The gate goes red, the author
  runs `--write-regions` as documented, and the new miss ships under "LensChoice.entries is
  statically nonempty". No reviewer is prompted.
- **Fix:** store a content fingerprint per row in the manifest, for example the SHA-1 of the
  whitespace-normalized source text of the cited lines. On regeneration, carry the rationale only
  when the fingerprint of the new region matches the old one. Otherwise write `UNREVIEWED:`, which
  the loader already refuses. The gate can also verify the fingerprint, which catches "lines moved
  and content changed" between regenerations. Add a fixture test for the substitution case.

### TE4-6: AGG3-8's provenance wiring lives in MainActivity, which no test executes, and the VM's public `onStoreMemorySlot` silently records "unknown" (Low, High). Confirmed.

- **Where:** `MainActivity.kt:517-545` (`onStoreMemorySlot` → `bankAudioOffByDenial(...,
  permissionPreferences.getBoolean(AUDIO_OFF_BY_DENIAL_KEY))`; `onRecallMemorySlot` →
  `audioDenialReasonAfterRecall` → `putBoolean`); `ui/CameraViewModel.kt:3532`
  (`override fun onStoreMemorySlot(slot) = storeMemorySlot(slot, audioOffByDenial = null)`); tests
  `SettingsStoreTest.kt` (new AGG3-8 cases) and `MicrophoneGrantRestoreTest.kt`.
- **Why:** The new SettingsStore tests reproduce the Activity's sequence by hand. They call
  `bankAudioOffByDenial`, then `audioDenialReasonAfterRecall`, then `audioRestoredByMicrophoneGrant`
  themselves. The real composition (which preference key is read, which value is written, and that
  the Activity overrides `onStoreMemorySlot` at all) is never executed. Meanwhile the VM still
  exposes `onStoreMemorySlot` through `CameraActions`, and that path stores `null`. Any caller that
  reaches the VM override instead of the Activity wrapper records "unknown", which falls back to
  the pre-provenance rule.
- **Failure scenario:** someone removes the Activity's `onStoreMemorySlot` override as
  "redundant", or reads the wrong preference key. Every new bank then records `null`, and recall
  silently reverts to the AGG2-26 rule. With that rule an operator-silenced bank is forced back to
  audio by a later grant: AGG-37 reopens with all tests green.
- **Fix:** move the Activity's two handlers into a small class (inputs: the permission
  `SharedPreferences` and the VM) and test it with Robolectric for both sequences. Make the VM's
  `onStoreMemorySlot` override require provenance, or have it throw or log in debug when it is
  reached without the wrapper.

### TE4-7: AGG3-13 converted eight route decisions to the engine law, but only one is pinned (Low, Medium). Confirmed.

- **Where:** `ui/CameraViewModel.kt` `standaloneRouteFor` callers at :1408, :2277, :2404, :2604,
  :2863, :2897, :2974, :2999, :3122; test
  `CameraViewModelRobolectricTest.kt` "zoom band follows the engine RAW law…".
- **Why:** The single new test covers the zoom-band site (:2277). The recall caps gate, the TC
  transition, the save substitution (`retainedRearZoomRatio`), the FRONT entry and return, the
  photo-standalone answer in mode flip (:2404), and :3122 can each be reverted to
  `state.rawForcesStandalone` without a failure. That is the exact divergence AGG3-13 was raised
  for: a GENERIC device before its inventory publishes.
- **Fix:** one parameterized Robolectric test. Use a GENERIC engine, set
  `state.rawForcesStandalone = true`, turn DNG on, drive each door (TC on/off, FRONT round trip,
  save-while-FRONT, MR recall, mode flip), and assert that the resulting zoom scale and lens follow
  the logical-route answer.

### TE4-8: The idat-HEIF VALID verdict is pinned only by a synthetic fixture whose field widths are not the measured layout (Low, Medium). Needs manual validation.

- **Where:** `MediaDurabilityPolicyTest.kt` `gridHeif()` (iloc size bytes `0x44, 0x80`, meaning
  base_offset_size = 8); production `MediaStoreWriter.kt:2716-2823` and the parser below it.
- **Why:** The KDoc says the fixture is "measured on the app's own device output". AOSP
  `MPEG4Writer::writeIlocBox` writes `0x44` and then base_offset_size = 0 (to my knowledge, not
  re-verified here), with an iloc version chosen by whether idat is used. The parser accepts any
  legal width, so today's verdict is probably right. But the one real-bytes check
  (`capture_then_kill_survives/IMG_…0001.heic`) was a manual one-off (plan B.1) and is not
  reproducible from the repository. A future `MPEG4Writer` change, or a parser regression on
  width 0 combined with construction 1, is invisible to the host gate.
- **Fix:** commit a header-only fixture captured from a real app HEIF: the `ftyp` + `meta` bytes
  verbatim, with `mdat` replaced by a sized zero payload whose length matches the real one. Add a
  test asserting VALID, plus INVALID for the same bytes truncated by one byte.

### TE4-9: The injected-signing and no-signing refusals are tested only as source text (Low, Medium). Confirmed.

- **Where:** `tools/tests/test_upload_key_policy.py`
  (`test_injected_signing_properties_refuse_every_release_package_task`,
  `test_missing_signing_refuses_every_release_package_task`); `app/build.gradle.kts:775-806`.
- **Why:** The assertions are `assertIn('tasks.matching { … }', source)` and similar. They pin
  spelling, not behaviour. Two examples:
  - `gradlePropertiesPrefixedBy` might not see a property supplied through
    `ORG_GRADLE_PROJECT_android.injected.signing.*` or `GRADLE_USER_HOME/gradle.properties`, while
    AGP's project-options reader does.
  - A `configureEach` doFirst might not attach to a task AGP registers lazily under a different
    name in a later AGP.

  Either would pass these tests.
- **Fix:** add one Gradle TestKit-free behavioural check to the release gate (not the unit
  suite): `./gradlew :app:packageRelease -Pandroid.injected.signing.store.file=/dev/null --dry-run`
  must fail with the refusal text, and the same check with the property supplied through the
  environment. Keep the source assertions as the cheap layer.

## Flakiness and harness sweep (no new defects)

- `Thread.sleep` in `app/src/test`: three sites. All are bounded polls with nanoTime deadlines
  (`StandbyAudioControllerTest.kt:1612-1630`, `FamilyDeletionMarkerIntegrationRobolectricTest.kt:179-183`)
  or a 1 ms deliberate sleep in an interrupt test (`MediaDurabilityPolicyTest.kt:41`). This is
  acceptable.
- The new tests (`LaunchRecoveryPendingExpiryTest`, the `StandbyAudioControllerTest` refusal case,
  `LazyReadRetryGateTest`, `RouteInputRollbackTest`) use injected clocks or synchronous fakes, with
  no real time dependency. `LaunchRecoveryPendingExpiryTest` registers a uniquely named provider
  per test, so there is no cross-test leakage under Robolectric's per-test sandbox.
- The reflection-built engine tests (`FacingRollbackPunchInRobolectricTest`) fail loudly
  (`NoSuchField`/`single()`) on a rename. This is fragile but not silent.
- `tools/tests/fixture_git.py` covers every fixture-repo `git init` site (5 files). The production
  `git clone --shared` snapshots in `build_immutable_{debug,release}.py` run no command that
  triggers auto-gc in tests, so I see no residual ENOTEMPTY race.
- `HeifBoundedReader` residual: the "fixed or validated width" rationale still holds after the new
  `readUnsignedLenient` calls, because iloc nibble sizes are restricted to {0, 4, 8} at
  `MediaStoreWriter.kt:3085-3090`.

## Files examined

`git show` for every commit in `e3a2bdd4..HEAD`.

Main sources:
- `camera/CameraEngine.kt` (FRONT door, rollback preflight, DNG dispatch, recovery backoff)
- `camera/CameraController.kt` (`sessionAttemptPlan`, `tryComplete`/`chars`,
  `acceptedPhotoSessionOutputs`)
- `camera/CameraState.kt` (`rearReturnZoom`, `unifiedZoomOf`/`localZoomOf`, `withEdit`,
  `effectiveFor`)
- `camera/DngPreCaptureAllocation.kt`, `camera/LaunchMediaRecoveryCoordinator.kt`,
  `camera/StandbyAudioController.kt`
- `storage/MediaStoreWriter.kt` (HEIF probe, `reassertPending`, `classifyFinalizedVideoTrack`)
- `video/VideoRecorder.kt` (stop tail, storage effects), `capture/StillSnapshot.kt`
- `ui/CameraViewModel.kt` (rollback mirror, `onToggleFrontCamera`, `standaloneRouteFor`, MR
  store/recall), `ui/ZoomMath.kt`, `ui/controls/ProControls.kt`, `ui/controls/ProSheet.kt`
- `MainActivity.kt`, `CameraPermissionPolicy.kt`, `storage/SettingsStore.kt`

Tests:
- `SessionFallbackLadderTest`, `PunchInResolvedTest`, `FacingRollbackPunchInRobolectricTest`,
  `RouteInputRollbackTest`, `LazyReadRetryGateTest`
- `OpticsRecallTransactionRobolectricTest`, `NoStillOutputCaptionTest`,
  `CameraViewModelRobolectricTest`, `DngPreCaptureAllocationTest`,
  `LaunchMediaRecoveryCoordinatorTest`
- `MediaDurabilityPolicyTest`, `FinalizedVideoTrackProbeTest`, `LaunchRecoveryPendingExpiryTest`
- `MicrophoneGrantRestoreTest`, `SettingsStoreTest`, `StandbyAudioControllerTest`,
  `StillSnapshotYuvTest`, `RecordingStorageDispatcherTest`

Tools:
- `tools/coverage/partition_report.py`, `tools/coverage/partition-a-residuals.txt` (every region
  checked against the source)
- `tools/tests/fixture_git.py`, `tools/tests/test_upload_key_policy.py`, `tools/check_docs.py`,
  `tools/build_immutable_release.py`, `app/build.gradle.kts`
