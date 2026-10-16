# RPL cycle 4 — critic review (HEAD 14767b0a, 2026-10-02)

Scope: the whole change surface, focused on the RPL cycle 3 fixes (`828b796f..14767b0a`, 29 source
commits) read against CLAUDE.md, `docs/ARCHITECTURE.md`, the cycle-3 aggregate
(`.context/reviews/archive-rpl-cycle3-2026-10-02/_aggregate.md`) and the cycle 1–3 plans. Read-only.
Owner decisions in CLAUDE.md are not relitigated. Each entry below is NEW, or is a carried item that is
more severe than its scheduling says. Perspectives: user (U), operator (O), maintainer (M), Play
reviewer (P).

Method: I diffed every cycle-3 main-source change (`CameraEngine`, `CameraController`, `CameraState`,
`OpticsConstraints`, `ZoomMath`, `CameraViewModel`, `MainActivity`, `CameraPermissionPolicy`,
`SettingsStore`, `MediaStoreWriter`, `VideoRecorder`, `RecordingStorageDispatcher`,
`StandbyAudioController`, `LaunchMediaRecoveryCoordinator`, `app/build.gradle.kts`, the release tools),
then traced each fix's new contract to every caller and to the documented invariant it serves.

## Findings

### CRIT4-1 — AGG3-4's `reassertPending` makes an INDETERMINATE row live forever. A crashed recording becomes a permanent, invisible storage leak, or the fix does nothing.
- **Where:** `storage/MediaStoreWriter.kt:1141-1156` (`reassertPending`), `:1436-1443` (KEEP_PENDING
  branch), `:1717-1734` (`probeFinalizedVideo`), `:2321-2327` (`pendingProbeOutcome` maps any throw to
  INDETERMINATE), `:2732-2744` (`orphanDisposition`).
- **Why:** MediaProvider's ~7-day pending expiry was the only garbage collector for rows that recovery
  can never judge. B.3 now re-arms that expiry on every launch for EVERY `KEEP_PENDING` row that is not
  DISCARD. Several row classes can never leave INDETERMINATE:
  - **Any recording interrupted by process death.** This is the most common orphan: an OOM kill, a
    crash, a force-stop, or a dead battery. MediaMuxer writes the `moov` only at `stop()`, so
    `MediaExtractor.setDataSource` throws on that file at every launch, and `pendingProbeOutcome`
    turns the throw into INDETERMINATE. B.2 fixed only the LIVE tail (`muxerStopThrew = true`).
    Launch recovery has no such evidence and stays INDETERMINATE forever.
  - **Unknown MIME** (`PendingMediaProbeKind.KEEP_PENDING`).
  - **Any HEIF layout the probe declines**: construction 2, a data reference, a whole-source extent.
- **Failure scenario (U/P):** The operator records a 4K HLG clip and the phone dies at 18 minutes,
  leaving several GB of pending MP4. Before cycle 3, MediaProvider deleted it after about a week. Now
  every launch re-asserts `IS_PENDING=1`, so the bytes never go away. The row is hidden from every
  gallery, the app has no UI that lists or purges it, and each launch spends a FUSE open plus an
  extractor attempt on it inside the 120 s recovery deadline. The copy shown for it
  (`status_video_kept_unverified`: "It is checked after the app is fully closed and opened again")
  promises a verdict that never arrives. Storage keeps shrinking with no attributable cause, which is
  the kind of thing a Play "app uses lots of storage" review surfaces.
- **The other branch is just as bad:** suppose MediaProvider does NOT re-stamp `DATE_EXPIRES` on an
  owner's `IS_PENDING=1` update. That is unproven: the plan says PENDING DEVICE, yet the item is
  checked `[x]`. Then AGG3-4 is still open and the copy still over-promises.
- **Fix:** Re-arm only rows that recovery can still eventually adopt:
  - journal `COMPLETE`;
  - `REGISTERED` with a probe that was transiently unavailable (open failure, provider error);
  - the fail-closed marker-unavailable rows.
  For a row whose bytes were read and could not be parsed (extractor throw after a successful open,
  unknown MIME, an unsupported HEIF variant), do NOT re-arm. Either let MediaProvider expire it, or
  record a per-row "indeterminate since" stamp in the journal and DELETE after N launches or D days.
  Separately, measure `DATE_EXPIRES` before and after the update on PMA110 and on one mainline-provider
  tablet before calling AGG3-4 closed.
- **Confidence:** High for the code path; Medium for the magnitude, which depends on MediaProvider.
  **Confirmed** (code) / **Needs-manual-validation** (expiry re-arm). **Severity: Medium.**

### CRIT4-2 — AGG3-7 brings back the AGG2-4 dead-shutter state on every bare `reopenForSession()` door, and nothing recovers it.
- **Where:** `camera/CameraEngine.kt:963-971` (`currentOpticsReconfiguration`, `baselinePrecedesMutation =
  false`), `:993-1006`, `:8562-8565` (`preflightRestorableSessionGeneration`), `:1009-1179`
  (non-restorable branch publishes Not-Ready with no retry), preflight sites `:4259-4295`. Callers:
  - `:2065` (stabilization);
  - `:3394` (frame rate / high-speed);
  - `:3717`, `:3778` (hi-res / aspect);
  - `:3833` (video size);
  - `:3613` (camera-error recovery).
- **Why:** Cycle 2 (b5c57e8a) fixed "old camera keeps streaming behind 'camera unchanged' while the
  shutter and REC stay dead until some other door reopens". Cycle 3 restricted that restore to
  `beginOpticsTransaction` doors and deliberately restored the pre-cycle-2 Not-Ready outcome for every
  bare door. The non-restorable branch schedules no retry: `rollbackOpticsAfterPreflight` has no
  `scheduleColdStartRetry` analogue for a live controller. The rollback also does not roll anything
  back for these doors. Their `before` is a post-mutation snapshot, so `videoSize` /
  `previewStreamSize` / aspect stay at the NEW, never-configured values, while the outgoing controller
  streams the OLD ones.
- **Failure scenario (O):** In Video, the operator picks a new resolution and `selectCurrentLens()` or
  `cachedCaps()` fails once, for example a transient CAMERA_DISABLED or a characteristics read during
  HAL contention. Then:
  - the VM's `switchCover.onOpticsRollback` drops the dip ("camera unchanged");
  - the toast reads "Camera unavailable; camera unchanged";
  - the live preview keeps running;
  - REC is dimmed indefinitely.
  Nothing tells the operator to do anything. Only an unrelated mode/lens flip or a
  background/foreground cycle recovers. The same applies to aspect / hi-res in Photo, which leaves the
  shutter dead, and to stabilization. Cycle 3 swapped "Ready over the wrong fields" for "Not-Ready
  forever", and the second is worse for the operator. It is the exact symptom AGG2-4 was opened for.
- **Fix:** Pick one:
  - (a) Snapshot the bare doors' baseline BEFORE their write. Move them onto `beginOpticsTransaction`;
    this is the AGG3-12 `RouteInputs` redesign, which is currently "later cycle".
  - (b) Until then, on a non-restorable preflight rollback whose outgoing controller is still open and
    streaming, schedule the same bounded retry the cold path uses (same transaction, same generation
    check), so the door converges by itself.
  - (c) Have the bare door record its own pre-write value, so rollback can revert that one field and
    then legitimately restore Ready.
  Add a test that a bare-door preflight failure eventually reaches Ready without any other door.
- **Confidence:** High. **Confirmed** (code). Device reachability needs a transient preflight failure:
  **Needs-manual-validation** for frequency. **Severity: Medium.** It is more severe than AGG3-12's
  "later cycle" scheduling implies, because cycle 3 re-opened a fixed user-visible defect.

### CRIT4-3 — Recalling a bank that is silent because of denial, while the microphone is already granted, records silent clips until the next `onResume`. Then a "microphone allowed" status appears that nobody triggered.
- **Where:** `MainActivity.kt:530-548` (recall sets `AUDIO_OFF_BY_DENIAL_KEY` from the bank's
  provenance), `MainActivity.kt:985-1003` (`refreshPermissionState`, the only consumer of
  `audioRestoredByMicrophoneGrant`, runs only at `:388` onCreate, `:423` the camera permission result,
  and `:681` onResume), `CameraPermissionPolicy.kt:121-129`.
- **Why:** The AGG3-8 fix correctly records provenance. However, setting the key to true does nothing
  when the grant has ALREADY happened. The rule is "A denial-disabled audio track is RESTORED by a
  later grant", and the grant is already in force at recall time. `microphonePermissionRequired` is
  conditional on `recordAudio`, so REC never prompts either.
- **Failure scenario (O):**
  1. Bank M2 was saved while the microphone was denied.
  2. The operator later granted the microphone in Settings and returned. Audio came back for the live
     state.
  3. The operator recalls M2. `recordAudio = false` and the key is set to true.
  4. The operator presses REC and gets a silent clip with no prompt.
  5. On the next background/foreground, audio flips on with `MICROPHONE_ALLOWED_AUDIO_ON`, a status
     describing a grant the operator did not just make.
- **Fix:** After writing the recall's reason, evaluate `audioRestoredByMicrophoneGrant` immediately.
  Call the same block, or call `refreshPermissionState()`, in `onRecallMemorySlot`. A recall of a
  denial-silent bank under a live grant then lands audio-on. Add a pure test:
  `(applied, recordAudio=false, offByDenial=true, granted=true)` → audio on, reason cleared.
- **Confidence:** High. **Confirmed** (code). **Severity: Low-Medium.**

### CRIT4-4 — After AGG3-18 the format sheet shows the REQUEST while the session writes something else. On FRONT a DNG-only request shows NO format selected, and on a hi-res session it shows HEIF while JPEG is written.
- **Where:** `ui/controls/ProSheet.kt:878` (`formats = state.photoFormats`), `ProControls.kt`
  `PhotoFormatToggles` (`processedSelected = processedAvailable && formats.wantsProcessedStill`,
  `rawSelected = rawAvailable && formats.dngRaw`), `CameraState.kt:1479-1496` (`normalizedFor`, hi-res
  collapse), `:1512-1513` (`effectiveFor`). The OSD uses `effectivePhotoFormats` (`Overlays.kt:762, 835,
  991`).
- **Why:** Before cycle 3 the accepted session normalized the request, so the sheet and the OSD agreed.
  Now the OSD shows the session's answer and the sheet shows the request. They disagree exactly where
  the session substitutes:
  - **FRONT, request {DNG}:** `rawAvailable` is false (`rawSelectable`, `!frontFacing`) and the request
    has no processed axis. Every chip renders unselected. The OSD and the shutter write HEIF.
  - **Hi-res session (dormant on PMA110, live on capable devices), request {HEIF}:** the sheet shows
    HEIF selected. `normalizedFor` writes passthrough JPEG, and the OSD says JPEG.
  - **Drop-RAW rung, request {DNG}:** DNG is shown selected and no processed format is selected, yet
    HEIF is written. A caption explains only the RAW half.
- **Failure scenario (U):** The operator opens the sheet in a selfie trip and sees an empty format row.
  They tap HEIF "to fix it", which permanently turns a DNG-only rear workflow into HEIF+DNG. That is the
  very outcome AGG3-18 was meant to prevent. A Play reviewer sees a control that reports no selection
  while files are being saved.
- **Fix:** Render each chip's selected state from `effectivePhotoFormats`, and keep the request as the
  edit base (post-inventory edits already fold against what was displayed). Alternatively, mark the
  requested-but-not-in-force chip with a distinct non-colour cue plus the existing caption, so "your
  choice" and "what this route writes" are both visible. Test the FRONT {DNG} and hi-res {HEIF} cases.
- **Confidence:** High. **Confirmed** (code). **Severity: Low.**

### CRIT4-5 — B.2 deletes the tolerated muxer-stop take on two extractor throws, but the tolerated path exists because that very file is believed playable.
- **Where:** `video/VideoRecorder.kt:451-475` (the tolerated throw sets SKIPPED), `:2052`
  (`muxerStopThrew = true` always), `storage/MediaStoreWriter.kt:2696-2726`
  (`classifyFinalizedVideoTrack`: a second throw → INVALID → delete).
- **Why:** The KDoc says "a `muxer.stop()` throw is independent evidence that the moov was never
  written". That contradicts the only path that reaches this probe. `muxerStopFailureIsTerminal` lets
  the throw through precisely BECAUSE a sample-less degraded audio track makes MediaMuxer throw "even
  though the video track is playable". AOSP MPEG4Writer still writes the movie header on
  ERROR_MALFORMED. So the remaining extractor-throw causes are:
  - transient FUSE / provider failures (AGG2-18's case; now two within 250 ms delete the take);
  - an extractor that rejects the empty audio `trak`.
  Neither of these is "moov never written". Nobody has measured how MediaExtractor treats an MP4 with a
  registered-but-empty audio track on PMA110.
- **Failure scenario (O):** The mic drops in the add-track window, MediaMuxer throws at stop, and a
  playable video-only take sits in a busy provider. Two parse throws later the take is deleted, the
  exact loss the degrade path was built to prevent.
- **Fix:** Before relying on INVALID here, reproduce the tolerated case on device:
  1. Inject a mic failure before the first audio sample.
  2. Check `ffprobe` and `MediaExtractor` on the result.
  If the file parses, keep B.2 but correct the KDoc premise. If it does not parse, the tolerated path
  should either strip the empty track (not possible with MediaMuxer) or retain the take with a
  bounded-age delete (see CRIT4-1), not delete it on the first launch.
- **Confidence:** Medium. **Needs-manual-validation.** **Severity: Low.**

### CRIT4-6 — The release wrapper's "sealed" claim is still bypassable through Gradle user-home and environment inputs that it never scrubs.
- **Where:** `tools/build_immutable_release.py:690-708` (argv grammar, comment: "`-I`/`--init-script`
  (unsealed build logic inside the "sealed" build)"), `:120-125` (`run` copies `os.environ` wholesale),
  `:953` (`os.environ.update(...)`).
- **Why:** The argv check removes `-I`, `-P`, `-D` and `-x` from the command line. However, the same
  effects still arrive through channels the wrapper inherits:
  - `~/.gradle/init.d/*.gradle(.kts)`;
  - `$GRADLE_USER_HOME/gradle.properties`;
  - `GRADLE_OPTS=-Dorg.gradle.project.*`;
  - `ORG_GRADLE_PROJECT_*`;
  - `JAVA_TOOL_OPTIONS=-javaagent:`.

  The Gradle-side `android.injected.signing.*` refusal (`app/build.gradle.kts:775-806`) still catches
  injected SIGNING from every source, so signer integrity holds. But the evidence file's "sealed export"
  claim does not cover build logic.
- **Fix:** Run the child Gradle with a wrapper-owned empty `GRADLE_USER_HOME` (or `--init-script` none
  plus an init.d presence refusal). Strip `GRADLE_OPTS`, `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and
  `ORG_GRADLE_PROJECT_*`. Alternatively, narrow the comment and evidence wording to what is actually
  sealed (sources and signer), not build logic.
- **Confidence:** High. **Confirmed.** **Severity: Low** (local-maintainer trust model, see AGG3-33).

### CRIT4-7 — `standaloneRouteWanted(..., rawForcesStandalone = true)` keeps exactly the "fix-off default" that AGG3-41 removed elsewhere.
- **Where:** `camera/CameraState.kt:539-543`. Display consumer: `CameraState.kt:577-590`
  (`CameraUiState.unifiedZoom` reads the state copy that AGG3-13 says must not decide routes; it feeds
  the Loupe Overview gate `teleFinderResolved` through `CameraScreen.kt:780` and `Overlays.kt:739,
  1085`).
- **Why:** AGG3-41's rule is that omitting an argument must not compile into the PMA110 behaviour. The
  root route predicate itself still defaults to the PMA110 law. Separately, `unifiedZoom` still uses
  `CameraUiState.rawForcesStandalone`, so on a generic device before the first inventory the Loupe
  Overview gate and the OSD focal can read lens-local as unified.
- **Fix:** Drop the default and pass the engine law (or the synced copy) explicitly at every call. Note
  in AGG3-13's closure that `unifiedZoom` remains a display-only exception, or route it through the
  engine law too.
- **Confidence:** High. **Confirmed.** **Severity: Low.**

## Carried items I rate above their scheduling

- **AGG3-12 / AGG3-11 (rollback publication ownership, "later cycle"):** CRIT4-2 is a direct
  consequence. Until bare doors snapshot their baseline before mutating, every preflight-failure policy
  is a choice between two wrong outcomes. I would schedule the `RouteInputs` redesign in cycle 4, or
  ship CRIT4-2 fix (b) as the interim.
- **AGG3-4 marked `[x]` while PENDING DEVICE:** see CRIT4-1. The checkbox overstates closure of a
  data-retention behaviour whose only proof is a fake resolver.

## Checked and found sound (no finding)

- AGG3-1 `rearReturnZoom` lens-base inverse. On the logical route the lens band tracks
  `forZoom(unified)` (`CameraViewModel.kt:2273-2281`, `CameraEngine.kt:599`), and `forZoom` and
  `opticalBaseFor` band identically above 0.6. So a logical source and a standalone target round-trip,
  and the live path and the persisted value now share one function.
- AGG3-2 `!tenBitVideoOnly` on `useRaw` covers every rung, including the TELE plan-0 rung.
- AGG3-9 `lazyCharacteristicsRead(shot = true)` runs once per completed shot after `p.done`.
- AGG3-17 `hlg` is threaded from `useHlg` into the accepted outputs and into rollback restores
  (`before.photoSessionOutputs`). High-speed and preview-only report `hlg = false`.
- AGG3-20: the unbound-input release cannot double-release. `runNativeWithPublication` propagates
  the `publicationOwner` throw after its admission decrement without touching the input.
- AGG3-30 interrupt-preserving backoff, and the early return in `executeLaunchMediaRecovery`.
- AGG3-3 idat HEIF verdict: the bounds arithmetic in `isWhollyInsideRelative` and the
  overflow/unreadable sentinels are consistent, and a truncated tail still fails the top-level tiling
  check.
- The AGG3-10 cross-thread read of the non-volatile `rawWanted` (`CameraEngine.kt:3323`) is ordered by
  the setupExecutor monitor plus the `Handler` post, so the main-thread read sees the committed value.

## Totals

7 new findings: 0 High, 2 Medium (CRIT4-1, CRIT4-2), 1 Low-Medium (CRIT4-3), 4 Low (CRIT4-4..7).
Two carried items are flagged as under-scheduled.
