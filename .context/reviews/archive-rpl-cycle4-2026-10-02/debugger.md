# Debugger review, RPL cycle 4: latent failure modes, swallowed errors, regressions

Reviewer: debugger lane (read-only). Date: 2026-10-02. HEAD `14767b0a`. Finding prefix `DBG4-`.

## Scope and method

1. Read the complete cycle-3 main-source and tools diff (`e3a2bdd4..HEAD`, 30 files, +1007/-448)
   hunk by hunk, and traced each fix's callers for a new failure edge: the HEIF `idat` grid probe
   and lenient iloc reader, `reassertPending`, the muxer-stop parse retry, the DNG dispatch wiring,
   `rearReturnZoom`/`retainedRearWireZoom`, `baselinePrecedesMutation`, the per-shot
   characteristics read, `effectiveFor`/`effectivePhotoFormats`, `withEdit`, the MR audio
   provenance, the standby unbound-input release, the `StillSnapshot.Nv21` single-use drop, the
   10-bit `useRaw` term, the recovery backoff, and the release-tool task grammar / secret floor /
   alias precedence / tracked-docs scan.
2. Sweeps over `app/src/main/kotlin/**` (the source tree is `kotlin/`, not `java/`) and
   `tools/*.py`:
   - `String.format` / `.format(` without a `Locale`: only two DEBUG log lines
     (`CameraViewModel.kt:699,725`); every user-facing and filename formatter pins `Locale.US` or
     `Locale.ROOT`.
   - Wall vs monotonic clock: every `currentTimeMillis()` is an epoch stamp (EXIF, filename,
     `processStartEpochSecs`), never a duration. ZSL age uses the sensor clock with the
     `TIMESTAMP_SOURCE_REALTIME` check.
   - `roundToInt`/`roundToLong` on NaN (throws): 43 sites checked; the inputs are guarded, finite,
     or bounded. `diagnosticStopBucket` filters values that are not positive.
   - Ns/ms/Int overflow: shutter-angle math runs in Double, timelapse seconds are normalized, and
     persisted exposure/ISO/fps/zoom are clamped at load. `jpegQuality` is not clamped at load, but
     every consumer clamps (`ManualControls.kt:689`, `CameraEngine.kt:5553`).
   - Resource closes: `Image` (`onImage`/`tryComplete`), cursors (`use`), the HEIF writer
     (`finally close`), the spool files, and the MediaExtractor (`finally release`).
   - Ring buffers: the gyro history `rotationBetweenSamples` and the ZSL ring.
   - Enum persistence: `enumOr` throughout, plus the `"LOG"` alias.
   - Subprocess handling in `tools/*.py`.

I did not re-report items open in the cycle-1/2/3 aggregates (AGG3-11..16, AGG3-21/23/29, AGG-39,
and so on). Nothing here is device-verified.

**Summary: 4 findings** (1 Medium, 1 Low-Medium, 2 Low), plus a cycle-3 regression verdict.

---

## DBG4-1. AGG3-6 deletes a playable take on two transient extractor throws; its premise ("the stop throw is evidence the moov was never written") is false for the only path that reaches it [Medium]

- **Severity:** Medium (destructive direction: a good clip is deleted). **Confidence:** Medium.
  **Status:** Needs-manual-validation (AOSP-source reasoning plus the repo's own comments; needs a
  device extractor-throw injection).
- **Where:**
  - `storage/MediaStoreWriter.kt:2685-2726` (`classifyFinalizedVideoTrack`): with
    `muxerStopThrew`, the second parse throw is `INVALID`.
  - `:1747-1771` (`finalizedVideoTrackProbe`, retry after `sleepPreservingInterrupt(250)`).
  - `video/VideoRecorder.kt:2050-2052` hardwires `muxerStopThrew = true`.
  - `video/VideoRecorder.kt:452-472` and `:2392-2406` (`muxerStopFailureIsTerminal`): validation
    is SKIPPED, and so the probe runs, ONLY for `wroteVideoSample && audioDegradedMidRec &&
    !wroteAudioSample`.
- **Why:** the new KDoc says "a `muxer.stop()` throw is independent evidence that the moov was
  never written". That is not true for the single combination that reaches this probe.
  - The recorder's own comment at `VideoRecorder.kt:452-457`, and the `muxerStopFailureIsTerminal`
    KDoc, describe this exact case: `stop()` "may throw over the registered-but-empty audio track
    **while the video track is complete**".
  - In AOSP `MPEG4Writer::reset()`, a track with zero samples reports `ERROR_MALFORMED`, and the
    writer still writes the movie header for that error ("Do not write out movie header on error
    except malformed track"). It then returns the error, so `MediaMuxer.stop()` throws
    `IllegalStateException` over a finalized, playable file.

  The stop throw therefore carries no information about the container, and an extractor throw on
  that opened file is the same transient FUSE/provider class AGG2-18 was written to protect.
  Two more details make it worse:
  - The retry re-parses the **same** `ParcelFileDescriptor` (`descriptor` is reused at
    `:2717`), so the second attempt is correlated with the first rather than independent.
  - If the storage worker is interrupted, `beforeParseRetry` returns at once, so the retry is
    immediate.
- **Failure scenario:**
  1. The mic route drops right after REC starts, before any AAC sample: a BT headset disconnects,
     or another app grabs the mic. The recorder degrades to video-only and the clip keeps
     recording.
  2. On stop, `muxer.stop()` throws `ERROR_MALFORMED` over the empty audio track. The moov is
     written.
  3. The provider is busy (media scan of the just-closed file) and `MediaExtractor.setDataSource`
     throws twice, 250 ms apart, on the same fd.
  4. The verdict is `INVALID`, then `FAILED`, so the pending row is deleted and the user sees
     "Video save failed" for a take that would have played. Before cycle 3 this retained the row
     and launch recovery re-judged it.
- **Fix:** revert the `INVALID` mapping for parse throws, which restores the AGG2-18 tri-state.
  Address the original AGG3-6 worry (a corrupt take retained forever) on the recovery side
  instead: let recovery judge an unparseable MP4 structurally. A bounded ISO-BMFF walk can tell
  "no `moov` box" (INVALID) from "unreadable" (INDETERMINATE), as the HEIF probe already does. If
  the live retry stays, open a **fresh** descriptor for the second parse and keep `INDETERMINATE`
  when that also throws. Device check: inject a degrade before the first audio sample, confirm
  the file plays (`ffprobe`), and confirm the live verdict is `PASSED`/`INDETERMINATE`, never
  `FAILED`.

## DBG4-2. `reassertPending` removes the platform's last-resort garbage collection: a permanently undecidable pending row is re-armed on every launch and never expires [Low-Medium]

- **Severity:** Low-Medium (a hidden, unbounded storage leak). **Confidence:** Medium.
  **Status:** Likely. The code path is traced. The re-arm effect itself is PENDING DEVICE per B.3.
- **Where:** `storage/MediaStoreWriter.kt:1141-1156` (`reassertPending`) and `:1436-1443` (called for
  every `KEEP_PENDING` that is not `DISCARD`). The rows that are INDETERMINATE for good are:
  - `:1713`: `PendingMediaProbeKind.KEEP_PENDING`, an unknown MIME, is always INDETERMINATE.
  - `:1717-1735` with `:2321-2326`: any throw from `openReadableParcelFd` (for example a row
    whose backing file is gone, which gives `FileNotFoundException`) or from
    `MediaExtractor.setDataSource` is INDETERMINATE.
  - `:2741`: `PendingJournalState.UNAVAILABLE` keeps the row.
  - The undecidable HEIF items (`idatDerivedVerdict`).

  `orphanDisposition` (`:2732-2747`) has no age or attempt term.
- **Why:** before B.3 these rows were KEPT but not immortal: MediaProvider's ~7-day
  `DATE_EXPIRES` deleted them in idle maintenance. B.3 deliberately re-arms that expiry on every
  launch that keeps a row, which is the right call for a complete take whose marker failed. It
  applies the same treatment to rows that no future launch can ever judge. Their verdict is a
  constant of the bytes, or of a missing file, not of a transient provider state. The result:
  - Each such row lives forever.
  - It stays invisible to Gallery, because pending rows are hidden.
  - The user cannot delete it from the app.
  - It costs one Binder `update` plus one probe on every launch.

  A crash-truncated or otherwise unparseable multi-GB 4K clip is the expensive case. If
  `MediaExtractor` throws on it rather than returning zero tracks, it now occupies storage
  permanently, where it was previously collected after a week.
- **Failure scenario:** the process dies during a 10-minute 4K HEVC take (LMK, battery, or
  force-stop). Recovery opens the moov-less MP4, the extractor throws, and the verdict is
  INDETERMINATE, then KEEP_PENDING, then `reassertPending`. That repeats at every launch, so about
  4 GB of hidden storage is never reclaimed and no UI ever names it.
- **Fix:** bound the re-arm.
  - Re-assert only for rows whose journal says the bytes were finished (`REGISTERED` with a
    structurally VALID-or-COMPLETE history), or only while `DATE_ADDED` is younger than a cap
    (for example 30 days).
  - Or persist a per-URI keep count in the journal and stop re-arming after N launches, so
    MediaProvider's expiry becomes the terminal again.
  - Separately, give `probeFinalizedVideo` the structural "no `moov`" INVALID answer from DBG4-1,
    so the commonest crash artifact is judged instead of kept.

  Host test: a fake resolver row that is permanently INDETERMINATE gets no `IS_PENDING` update
  once past the cap.

## DBG4-3. Recalling a denial-silent MR bank while the microphone is ALREADY granted leaves audio off until the next resume; the next REC is silent [Low]

- **Severity:** Low. **Confidence:** High (code-traced). **Status:** Confirmed by trace.
- **Where:**
  - `MainActivity.kt:530-547`: `onRecallMemorySlot` writes `AUDIO_OFF_BY_DENIAL_KEY = reason`.
  - `CameraPermissionPolicy.kt:120-129` (`audioDenialReasonAfterRecall`).
  - The only restorer is `refreshPermissionState` (`MainActivity.kt:985-1004`,
    `audioRestoredByMicrophoneGrant`), which runs from the lifecycle path, not after a recall.
- **Why:** AGG3-8 makes a recalled bank saved silent by denial set the denial reason, "so a later
  grant restores audio". When the grant is not later but already in force, nothing re-evaluates:
  - `recordAudio` stays `false`.
  - The reason is `true`.
  - `hasMicrophonePermission` is `true`.

  These are exactly `audioRestoredByMicrophoneGrant`'s restore conditions, but the check runs only
  on the next `onStart`/`onResume`. Until then the bank behaves as if the operator chose silence.
- **Failure scenario:**
  1. The operator saves MR1 while the mic is denied.
  2. They later grant the mic in Settings and return; audio is restored.
  3. They recall MR1: `recordAudio=false`, reason `true`.
  4. They press REC immediately and get a video-only clip with no prompt, even though the mic is
     granted and the bank's silence was recorded as a denial consequence, not a choice.
- **Fix:** after the recall writes the reason, run the same restore predicate once. Extract the
  `audioRestoredByMicrophoneGrant` block from `refreshPermissionState` into a helper and call it
  from both sites. Pure test: recall (denial-silent bank, permission granted) gives audio on.

## DBG4-4. `check_docs.py` tracked-docs scan: new docs that are not yet `git add`ed are invisible to the password-property rule, and a git listing that is empty for the wrong repository passes vacuously [Low]

- **Severity:** Low (security-gate coverage). **Confidence:** High for the untracked half; Low-Medium
  for the nested-repo half. **Status:** Confirmed (untracked) / Needs-manual-validation (nested).
- **Where:** `tools/check_docs.py:1548-1574` (`tracked_markdown`, `password_property_scan_paths`),
  added in cycle 3 (G.2, `5398acd8`).
- **Why:**
  - **Untracked files.** `git ls-files` lists the index only. A `docs/new-runbook.md` that contains
    a password property passes `verify_host.py` until someone stages it. The gate is meant to run
    BEFORE commit, so it greenlights exactly the state the developer is about to commit. The
    ignored `.context/` notes that motivated G.2 are excluded by `.gitignore`, so
    `git ls-files --others --exclude-standard` would add new public docs without re-admitting the
    private notes.
  - **Wrong repository.** `ls-files` exits 0 with empty output when ROOT sits inside a different
    git work tree that does not track these paths (an export unpacked under another checkout).
    `tracked == []` then skips every `docs/**` and `.context/**` file rather than falling back to
    the glob.
- **Fix:**
  - Scan `git ls-files -z --cached --others --exclude-standard -- docs .context`.
  - Fall back to the glob when `git rev-parse --show-toplevel` is not ROOT.
  - Fixture test: an unstaged new doc with a password property fails the check, and an ignored
    `.context/` note still does not.

---

## Cycle-3 regression verdict

43 commits checked. Apart from DBG4-1 (AGG3-6 / `dd91413c`), DBG4-2 (AGG3-4 / B.3) and DBG4-3
(AGG3-8 / B.4), no regression found:

- **HEIF probe (B.1).** A false INVALID would delete user media, so this is the destructive
  direction that matters. `idatDerivedVerdict` returns INVALID only for:
  - a missing `idat`,
  - a descriptor outside `idat`,
  - an unrepresentable (overflowing) location,
  - a construction-0 extent outside every `mdat` payload,
  - or zero coded items.

  None of these occurs in a complete `MPEG4Writer` file. A truncated file is already INVALID at
  the top-level walk (`offset != fileSize` / no `meta`). The lenient reader's
  `UNREADABLE`/`OVERFLOW` sentinels (-2/-1) cannot collide with parsed values (non-negative, and
  overflow is checked). An unreadable non-primary field rides the undecidable lane. The
  construction-0 primary path keeps its old verdicts.
- **Identity after `reassertPending`.** The frozen discard identity (`PendingDiscardJournal.kt:757-766`)
  does not include `_data`/`DATE_EXPIRES`, so a provider-side `.pending-<expiry>` rename cannot turn
  a later DISCARD into an identity mismatch.
- **Standby REG3-7 (B.7).** The `error()` thrown inside `publicationOwner` propagates out of
  `runNativeWithPublication`'s lock after `nativeAcquisitions--`. The unbound input is released
  once in `finally`. `nativePublication` is still null, so `finish(null)` is a no-op. There is no
  double release with the termination owner.
- **`StillSnapshot.Nv21` (B.8).** Its single caller (`CameraEngine.kt:5751`) calls it once per
  snapshot, so dropping the pixels before compressing cannot starve a retry.
- **`rearReturnZoom` (A.2).** Engine (`CameraEngine.kt:4021`) and VM (`CameraViewModel.kt:2998`)
  both pass the RETAINED lens, which is the exact inverse of `unifiedZoomOf(lens, …)`. The
  `coerceAtLeast(1f)` floor holds for UW (0.6/0.6). The upper bound is left to caps normalization,
  as before.
- **`effectiveFor` (B.6).** The only lossy case is a `{heif}` request on a RAW-only session, where
  the readout is empty while capture writes DNG. `sessionAttemptPlan` sets `useJpeg` on every rung
  below 3 regardless of the request, so that session shape is unreachable.
- **10-bit `useRaw` (A.1).** TELE ladder rung 3 (stream plan 0) now has `useRaw=false`, and
  `wantHlg`/`tenBitVideoOnly` stay gated on `supportsHlg10()`.
- **Per-shot characteristics read (A.4).** It is bounded by shutter rate, not frame rate.
  Metering is still rate-limited.
- **Release tools (C.2-C.4).** `validate_gradle_tasks` rejects every `-` element; the
  `keyAlias`/`keyPassword` precedence mirrors `signingValue` (trim, `CHANGE_ME`, file over env).
  The key password falls back to the store password exactly as Gradle's `?:` does.
- **Interrupt-preserving backoff (A.10).** It is correct, but nothing currently interrupts the
  recovery worker (no `shutdownNow`/`cancel(true)` on that path). The new early-exit branch is
  therefore reachable only from tests, and the KDoc's "stops at once" applies to the backoff path
  only; success pages never check the flag. Informational, not a finding.

## Files examined (grouped)

- **storage/:** `MediaStoreWriter.kt` (reassert, KEEP path, probes, HEIF parser incl. lenient
  reader, finalized-video classifier, orphan disposition, warning gate), `SettingsStore.kt` (load
  clamps, provenance key), `PendingDiscardJournal.kt` (identity projection/reader),
  `CaptureFamily.kt`, `LatestCaptureReducer.kt`.
- **camera/:** `CameraEngine.kt` (cycle-3 hunks, FRONT entry/return, DNG dispatch, timelapse,
  storage terminal status, recovery backoff, shotSpec), `CameraController.kt` (session plan,
  `onImage`/`tryComplete`, ZSL serve, `chars`), `CameraState.kt`, `OpticsConstraints.kt`,
  `StandbyAudioController.kt`, `LaunchMediaRecoveryCoordinator.kt`, `AutoExposure.kt`,
  `DiagnosticTelemetry.kt`, `ManualControls.kt`, `DngPreCaptureAllocation.kt`,
  `RecordingStorageDispatcher.kt`, `CameraStatus.kt`.
- **video/:** `VideoRecorder.kt` (stop/muxer-stop tolerance, drain loop, rendezvous, storage
  tail, native publication gate), `ColorProfiles.kt`.
- **capture/:** `StillCapturePipeline.kt`, `HeifCapture.kt`, `StillSnapshot.kt`.
- **gl/ and stab/:** `GlPipeline.kt` (encoder PTS), `GyroEis.kt` (registration, ring),
  `MotionInversion.kt` header.
- **ui/:** `CameraViewModel.kt` (cycle-3 hunks, format door, FRONT toggle, MR store/recall),
  `ZoomMath.kt`, `ProControls.kt`, `ProSheet.kt`, `Overlays.kt`, `CameraScreen.kt`,
  `review/MediaReview.kt` (decode/EXIF line), `review/LatestHeavyWorkLane.kt` (spool),
  `controls/ControlLabels.kt`, `CaptureOutputTracker.kt` (trim).
- **App:** `MainActivity.kt` (MR provenance, permission refresh, key handling),
  `CameraPermissionPolicy.kt`.
- **tools/:** `build_immutable_release.py`, `run_scoped_signed_release.py`,
  `upload_key_policy.py`, `check_docs.py`, `verify_host.py`, `app/build.gradle.kts` `signingValue`.
- **Prior state:** `.context/reviews/archive-rpl-cycle3-2026-10-02/{_aggregate,debugger}.md`,
  `docs/plans/2026-10-02-rpl-cycle3.md`, `CLAUDE.md`.
