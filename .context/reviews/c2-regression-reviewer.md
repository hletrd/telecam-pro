# RPL cycle 2 post-merge regression review (c2-regression-reviewer)

Scope: every commit in `git log c2892dda..e3a2bdd4` (40 commits, all `%G? = G`, no AI trailers),
each read with `git show` and checked against its message, `docs/plans/2026-10-02-rpl-cycle2.md`,
CLAUDE.md invariants, and the code at HEAD. Read-only: no Gradle run (another lane owns the gate);
HEAD line numbers below were re-derived with grep/sed.

Finding prefix: REG3-. 7 findings (1 Medium, 6 Low), plus 1 observation.

## Per-commit verdicts

| sha | verdict | note |
|---|---|---|
| 4edd2a14 | OK | `resolvedRawWanted` published inside `setResolvedOptics`' transaction; trailing `setRawWanted` removed after the refusal `return`, so refusal semantics are unchanged. |
| b565abae | OK | `rollbackRawWanted` keeps a direct write only if it does not move the restored BACK non-TC route; profile is read after `activeCameraRoute` restore. See REG3-3 for the VM mirror half. |
| 3ef6e845 | OK | `StillContinuationHandoff` covers both orders (settle-then-return and return-then-settle); onReady path still uses raw `onDone`, exclusive with settle. |
| b5c57e8a | OK | Restorable generation = baseline or this door's own invalidation; any later bump (error/pause/newer door) stays Not-Ready. Old controller is still streaming at preflight, so restoring Ready is correct. |
| 5e4cc454 | OK | Verified `sessionAttemptPlan` takes no `rawWanted` input, so a TC DNG toggle really is a same-camera no-op; matches VM `dngDoorRemapsZoomScale(tc=true) == false`. |
| 4d0c5e3c | ISSUE (doc) | Logic OK (counted size writes, VM reads engine live). New `currentRequestedVideoSize()` was inserted between `setVideoResolution`'s KDoc and the function: two stacked KDocs, `setVideoResolution` lost its doc (REG3-5). |
| 8f84b0bc | OK | paused/recorder rechecked inside the monitor; refused candidate is `close()`d. |
| 17c162d4 | OK | `Failed` now only means "bytes complete, marker threw"; row stays REGISTERED, retained status is correct, tombstoned family goes to durable discard, `finishDng` still runs in `finally`. |
| ae359201 | OK | `retainedRearWireZoom` is the exact inverse of the FRONT-entry `unifiedZoomOf(lens, …)`; engine-side `rearReturnZoom` divergence is already carried in the plan. |
| d1341513 | OK | `""` round-trips through `parseVideoResolution` to null (auto). Recall-of-auto-bank carry noted in plan. |
| 475dcf1f | OK | `withEdit` folds only changed axes into the un-normalized request; published value still normalized. |
| e5f1fc7a | OK | Pending pre-inventory request follows the rollback DNG mirror. |
| c6caab59 | OK (partial) | DNG door now reads engine law and skips TC/lens-local routes. Same stale-state-copy pattern remains at other VM sites (observation O-1). |
| f6f25a5b | OK | `rejectIfRecording()` before any state mutation; test asserts pin and status. |
| d9ed066d | OK | Standalone answer joins the caps gate; standalone PHOTO now requires lens equality. |
| b5e8d622 | OK | Detach before purge, second purge, `cleared` guards on every self-reposting runnable. |
| 15e5f3cb | OK | Dial doors reuse `exposureModeHandoff`; non-HAL modes byte-identical (tested). |
| f64417fa | ISSUE | `appliedLoadCount` is a correct applied signal, but the policy change breaks the CLAUDE.md "operator-chosen silence is not restored" rule in a reachable sequence (REG3-4). |
| 2d391a7d | OK | Inside the span the residual is log2(1)=0, so steady-state PMA110 P is byte-identical; only out-of-span seeds change. |
| c4cc92e7 | ISSUE | Live extractor throw is now INDETERMINATE, but launch recovery maps the same throw to INDETERMINATE too, so a truly unparseable take is never judged. Combined with 49f435e1 this produces a false "will be saved" promise (REG3-1). |
| 03203d17 | ISSUE | Shared gate lets the still request's `applyMetering` spend the retry token that the same shot's `tryComplete` then needs (REG3-6). New `chars()` KDoc also stacked on `readRawCharacteristics`' KDoc (REG3-5). |
| c91d741f | OK | Flush only on the streaming true→false edge, both reads on the camera thread. |
| f4ecda3d | OK | `exhaustedCursor` skips only collections whose QUERY failed; row failures still advance through `nextCursor`; the progress guarantee holds. |
| a0a12b7d | OK | One reserved row per null decode. |
| 531060e6 | OK | Per-generation, per-class gate capped at 4 rows. |
| 56803b42 | ISSUE (doc) | `shutdown()` + `cancel(false)` is correct (STPE `onShutdown` purges cancelled delayed tasks). New KDoc'd `retireStartupDeadline` was inserted under `shouldPublishRecording`'s KDoc, leaving two stacked KDocs (REG3-5). |
| 5a49bbfd | ISSUE (minor) | Degrading instead of crashing is right, but the new catch path can leave a created-but-unbound standby AudioRecord unreleased and finish a stale publication token (REG3-7). |
| b1447900 | OK | Tint bounded to ±50; Photo shutter bounded to [1 ns, 60 s] like `exposureTimeNs`. |
| 25631385 | OK | Keys match between `warnStorageOnce` (`"$site|$uri"`) and every `clear(...)`. "interrupted" and "exhausted" share the `publish` key, so one can suppress the other for the same URI and cause (cosmetic). |
| ab862007 | OK | Interrupt now preserved; retry loops fail closed (REGISTERED retained). Behavior change to note: a stop tail interrupted by teardown timeout now retains instead of retrying the marker, which is data-safe. |
| 1f7e14b3 | OK | `onCommitFailure` declared before `missingPhoneModel`; the only trailing-lambda site (`SettingsStore(app) { … }`) still binds the phone seed. |
| c10acf91 | OK | No remaining tracked length/class/delivery phrasing (`git grep` clean). The doc scan regex does not match the "20+ characters / three character classes" policy text. |
| 1c398982 | OK | One deny-list read by Gradle, gate, helper, and checker. Gradle `doFirst` + gate state as task input. The checker now trusts the self-attested local fingerprint; that is stated in CLAUDE.md. |
| ae9007d9 | OK | EN+KO imperative a11y resources; no "?" stripping. |
| 49f435e1 | ISSUE | Correct for COMPLETE-durable rows. False for RETAINED_VALIDATION_UNAVAILABLE video, which maps to the same `VIDEO_SAVE_DELAYED` copy (REG3-1). |
| ba3ee153 | OK | Keyed on `tenBitSessionWanted(mode, transfer)`; the documented residual remains. |
| 4a1c6507 | OK | Merge resolution is consistent: B's change gate plus C's `warn` seam on all three entry points. `processWarn` is evaluated at call time, so object init order is safe. Exact-count tests use fresh URIs and budgets. |
| a1258774 | OK | Doc corrections match the code (0.35-stop slide, ladder attempt 0/1, zero-byte standby read retries). |
| b953b741 | ISSUE | Two of the three "corrected" residual regions point at unrelated code at HEAD and at b953b741 itself (REG3-2). |
| e3a2bdd4 | ISSUE (doc) | Plan marks C.8/TE2-3 done although REG3-2 shows the regions are still wrong. Otherwise accurate (39 code commits plus this one, all signed). |

## Findings

### REG3-1 — A corrupt finalized video is now kept private forever, and the user is told it will be saved

- Severity / Confidence / Status: Medium / Medium / Likely (Needs-device for how often MPEG4Writer leaves moov unwritten when `stop()` throws)
- Commits: c4cc92e7 (live verdict), 49f435e1 (copy)
- Region: `storage/MediaStoreWriter.kt` `classifyFinalizedVideoTrack` (~2675-2692, parse throw → INDETERMINATE); `probeFinalizedVideo` (~1713-1730) wrapped by `pendingProbeOutcome` (~2312-2318, any throw → INDETERMINATE); `video/VideoRecorder.kt:462-472` (validation is SKIPPED only when `muxer.stop()` THREW) and `:1983-1993` (INDETERMINATE → RETAINED_VALIDATION_UNAVAILABLE); `camera/RecordingStorageDispatcher.kt:122-125` → `RETAINED_PENDING` → `CameraEngine.kt:~7364` `VIDEO_SAVE_DELAYED`; `res/values*/strings.xml` `status_video_save_delayed`.
- Why: The live probe runs only in one case: `muxer.stop()` threw over the degraded, sample-less audio track. That is exactly when the container is most likely unfinalized (no moov). `MediaExtractor.setDataSource` on such a file throws. Before c4cc92e7 this was INVALID: delete, plus "Video save failed". Now it is INDETERMINATE: the row stays REGISTERED. Launch recovery then runs the same `MediaExtractor` probe inside `pendingProbeOutcome`, which also maps every throw to INDETERMINATE. So recovery never reaches a verdict either. The commit's claim that recovery "re-judges" the take does not hold. The row stays pending on every launch (re-probed each time) until MediaProvider's own pending-expiry runs, if it ever does. Meanwhile 49f435e1 shows "Video retained. It will be saved the next time the app starts." That promise is now false in this path.
- Failure scenario: The mic drops in the add-track window → `muxer.stop()` throws → the clip lacks moov → the toast says it will be saved at next start → the next start does not publish it, and neither does any later one. The clip is invisible in the gallery, but its bytes stay on the device.
- Fix: Keep the transient-provider protection, but bound it. (a) In recovery, count INDETERMINATE-after-successful-open probes per URI in the pending journal, and treat a parse throw on the Nth launch (e.g. 3) as INVALID. Or (b) in the live tail, when `muxer.stop()` threw AND open succeeded AND the extractor throws, classify INVALID; the stop throw is independent evidence the container is broken. Either way, give RETAINED_VALIDATION_UNAVAILABLE its own copy ("Video kept; it will be checked the next time the app starts"), not the "will be saved" promise. Host-testable: pure `classifyFinalizedVideoTrack(stopThrew = true, parse throws) == INVALID`, and a recovery attempt-counter test.

### REG3-2 — The Partition-A residual "correction" points at unrelated code at HEAD

- Severity / Confidence / Status: Low / High / Confirmed
- Commit: b953b741 (and plan e3a2bdd4 C.8 marked `[x]`)
- Region: `tools/coverage/partition-a-residuals.txt` lines for `CameraControllerKt` (`CameraController.kt:2607-2610`) and `HeifBoundedReader` (`MediaStoreWriter.kt:2968-2970`).
- Evidence: At HEAD, `CameraController.kt:2607-2610` is the KDoc of the deep-ZSL-reader rung ("Why this is a ladder rung …"). The `CameraAccessException` eviction classifier is at `:2664-2672`, with a sibling policy classifier at `:2711-2716`. `MediaStoreWriter.kt:2968-2970` is `iinf` item-count parsing. The `HeifBoundedReader.readUnsigned` width guard is at `:3048-3050`. The file contents are identical at b953b741, so the regions were wrong when committed. They were probably derived on a lane branch before the merge shifted lines by +58/+80. Only `CameraState.kt:904-906` is right. The gate checks class and count only, so it passes. TE2-3's "stale regions" problem is therefore unfixed, but the plan reports it fixed.
- Fix: Correct the two regions. Make `partition_report.py` verify each region against the JaCoCo missed-line numbers (the planned "line identity" half), or at least check that the cited lines contain a per-entry anchor substring.

### REG3-3 — The rollback DNG mirror still publishes commit-time state; the AGG2-7 "read engine live" fix was applied to video size only

- Severity / Confidence / Status: Low / Medium / Likely
- Commits: b565abae + 4d0c5e3c (asymmetric)
- Region: `CameraEngine.kt` `commitOpticsRollbackLocked` (`rawWanted = rawWanted` in `OpticsRollbackPublication`, ~1085); `ui/CameraViewModel.kt:~983-997` (state `photoFormats` and `pendingPhotoFormatsUntilInventory` mirror `rollback.rawWanted`).
- Why: b565abae keeps a direct DNG write made before the rollback commits. Nothing covers a direct write between the setup-thread commit and the main-thread handling of the posted publication. Example: a DNG tap in Video/FRONT/TC/pre-start, which is a direct write and bumps no generation. The VM then overwrites the chip and the pending request with the stale `rollback.rawWanted` and persists it, while the engine keeps the newer value. 4d0c5e3c fixed exactly this race for `requestedVideoSize` (`engine.currentRequestedVideoSize()`), but not for the other direct-write route input.
- Failure scenario: In Video, a door fails and rolls back. The user taps DNG-on before the main post runs. The chip shows off and the engine wants RAW. On the next Photo flip, the VM computes the zoom scale with DNG off while the engine opens the standalone route: the AGG-3 mixed-scale class.
- Fix: Add `internal fun currentRawWanted()` and mirror it in the rollback handler, like `currentRequestedVideoSize()`. Host test: queue the rollback post, perform a direct `setRawWanted` before draining main, then assert chip == engine.

### REG3-4 — Recall no longer clears the denial reason for a silent bank, so a later grant overrides operator-chosen silence

- Severity / Confidence / Status: Low / High / Confirmed (policy reversal of AGG-37 / tracer T9)
- Commit: f64417fa
- Region: `CameraPermissionPolicy.kt` `audioDenialReasonClearedByRecall`; `MainActivity.kt:515-530`.
- Why: CLAUDE.md: "A denial-disabled audio track is RESTORED by a later grant; operator-chosen silence is not." A bank stores `recordAudio` with no provenance. The old rule protected silent banks; the new rule protects denial-disabled banks. The new rule now fails this reachable sequence: (1) mic granted, operator switches audio off and saves MR1 (deliberate silence); (2) operator turns audio on, later revokes the mic, so `AUDIO_OFF_BY_DENIAL_KEY` is set; (3) recall MR1, and the flag survives because the bank wants silence; (4) a Settings grant forces audio ON with MR1 active.
- Fix: Persist provenance per bank (save `audioOffByDenial` alongside `recordAudio` in `putLoaded`). Then recall can set the flag exactly: set if the bank was denial-off, clear if it was operator-off or wants audio. Pure decision plus a SettingsStore round-trip test. Until then, record the trade in CLAUDE.md, since the bullet currently promises both outcomes.

### REG3-5 — New KDoc'd helpers were inserted between existing KDocs and their functions

- Severity / Confidence / Status: Low / High / Confirmed
- Commits: 4d0c5e3c, 03203d17, 56803b42
- Region: `CameraEngine.kt:~3757-3772` (`setVideoResolution`'s KDoc now sits above `currentRequestedVideoSize`'s KDoc); `CameraController.kt:2420-2431` (`readRawCharacteristics`' KDoc stacked above `chars()`'s); `VideoRecorder.kt:~2340-2364` (`shouldPublishRecording`'s KDoc stacked above `retireStartupDeadline`'s).
- Why: Two consecutive `/** */` blocks. The original function loses its documentation, and the orphan describes the wrong symbol. The `readRawCharacteristics` text is now also stale ("the lazy retry in tryComplete can run per shot": it is rate-limited). CLAUDE.md requires keeping these "why" comments attached to the code they explain.
- Fix: Move each new helper above or below the documented function as a whole, and update the stale sentence.

### REG3-6 — The shared rawChars retry gate can fail a delivered still that the old per-shot retry would have saved

- Severity / Confidence / Status: Low / Medium / Needs-device (requires a transient open-time characteristics failure)
- Commit: 03203d17
- Region: `CameraController.kt:1306-1312` (`applyMetering` → `chars()`), still request build `:2094`, `tryComplete` `:~2271`, `LazyReadRetryGate` (1 s).
- Why: Before, `applyMetering` read only the cached field and `tryComplete` retried the read on every completed shot. Now both share one token per second. The still request's own `applyMetering(this, requestControls)` runs just before capture and spends the token. If that read fails (still transient), the same shot's `tryComplete` usually arrives in under 1 s, finds the gate closed, and fails with "Missing camera characteristics". The HAL already delivered the image, and the read might now succeed.
- Fix: Give metering its own gate, or let `tryComplete` bypass the gate once per shot. That is bounded by shots, not frames, and keeps PERF2-5's per-frame protection for BURST. Host test with an injected clock and a failing-then-succeeding reader: metering read fails at t=0, tryComplete at t=0.3 s still re-reads.

### REG3-7 — The standby meter's new catch path can leak a created AudioRecord and finish a stale publication token

- Severity / Confidence / Status: Low / Low / Likely (invariant-break path only)
- Commit: 5a49bbfd
- Region: `StandbyAudioController.kt:~676-696` (`publicationOwner` `check(terminationOwner.bind(input))` throws after `audioSetup.create()` built the input) and `:~783-797` (new `catch` plus `finally`).
- Why: Before, this was a process crash, so cleanup did not matter. Now the process survives, but `audioInput` is assigned only after `create` returns. A throw inside the publication callback therefore leaves the constructed input unreleased by the `finally` (`audioInput?.let` is null). Unless `runNativeWithPublication` releases on callback failure, that input leaks, and an un-started AudioRecord still holds an input handle. The "exactly one owner of the mic" rule then sees a second AudioRecord at REC. Also, `nativeProcessGate.finish(nativePublication.get())` can finish the previous generation's token, because `nativePublication` is set only after `create` returns.
- Fix: Capture the input in a local inside the `publicationOwner` lambda before `check`, and release it in the catch path. Clear `nativePublication` at generation start. Robolectric/fake-gate test: make `bind` return false and assert release is called exactly once.

### Observation O-1 (not a regression) — c6caab59's rationale applies to other VM sites

`CameraUiState.rawForcesStandalone` still defaults to PMA110's `true` until the first route inventory. The DNG door now reads `engine.rawForcesStandalone`, but other VM sites still read the state copy: `CameraViewModel.kt:2273` (lens band), `:2604`, `:2854` (retained rear zoom), `:2890`, `:2972` (FRONT-entry snapshot), `:3001`, `:3126`. On a GENERIC device in that early window they disagree with the engine. This pre-dates cycle 2. It is the same class, so one more sweep would close it.

## Merge-conflict check (MediaStoreWriter open/publish warnings)

HEAD `MediaStoreWriter.kt:1028-1131` combines B's per-(site, URI) change gate (`storageWarningGate`, `warnStorageOnce`, success `clear`s with matching key shapes) and C's injectable `warn` seam on `openParcelFd`, `openOutputStream`, and `publish`, including the interrupted-backoff row from ab862007. No duplicate or lost log calls. `processWarn` is a call-time default, so it does not depend on object initialization order. `StorageFailureDiagnosticsTest` uses fresh URIs and budgets. The process-global gate cannot leak state between those tests.

## Files examined

- Engine/camera: `camera/CameraEngine.kt` (rollback commit, reconfigure preflight, dual-open install, setRawWanted, setResolvedOptics, DNG pre-allocation, photo callback, recording presentation), `camera/CameraController.kt` (metering, tryComplete, chars gate, pinAutoFps, sessionAttemptPlan, classifiers), `camera/DngPreCaptureAllocation.kt`, `camera/AutoExposure.kt`, `camera/ManualControls.kt`, `camera/CameraState.kt`, `camera/OpticsConstraints.kt`, `camera/StandbyAudioController.kt`, `camera/DiagnosticTelemetry.kt`, `camera/LaunchMediaRecoveryCoordinator.kt`, `camera/RecordingStorageDispatcher.kt`
- Capture/storage/video: `capture/StillCapturePipeline.kt`, `storage/MediaStoreWriter.kt`, `storage/PendingDiscardJournal.kt` (IdentityReadWarningGate), `storage/SettingsStore.kt`, `video/VideoRecorder.kt`
- UI/activity: `ui/CameraViewModel.kt`, `ui/ZoomMath.kt`, `ui/controls/ProControls.kt`, `ui/controls/ProSheet.kt`, `ui/review/MediaReview.kt`, `MainActivity.kt`, `CameraPermissionPolicy.kt`, `res/values{,-ko}/strings.xml`
- GL: `gl/GlPipeline.kt` (analysis catch)
- Tools/docs: `app/build.gradle.kts`, `tools/upload_key_policy.py`, `tools/build_immutable_release.py`, `tools/check_release_artifact.py`, `tools/check_docs.py`, `tools/coverage/partition-a-residuals.txt`, `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/play-console-submit.md`, `docs/plans/2026-10-02-rpl-cycle{1,2}.md`
- Tests read: OpticsRouteInputTransactionRobolectricTest, DngPreCaptureAllocationTest, RetainedDngStatusTest, ZoomMathTest, CameraViewModelRobolectricTest, CameraViewModelTickersRobolectricTest, ExposureModeHandoffTest, MicrophoneGrantRestoreTest, OpticsRecallTransactionRobolectricTest, AutoExposureTest, FinalizedVideoTrackProbeTest, LaunchRecoveryProgressTest, StorageFailureDiagnosticsTest, tools/tests/test_tool_contracts.py (password-property case)
