# RPL cycle 6 — verifier review (VF6)

Agent: verifier (c6). HEAD `30970c9e`. Read-only; no Gradle, no state-changing git.

Angle: evidence-based check of STATED behaviour. Concrete claims in the ON-DISK `CLAUDE.md`,
`docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, the cycle-5 plan `[x]` items and the KDoc of the seams
they name were checked against production code. Each finding says whether the CODE is wrong (bug) or
the DOC is wrong (drift).

Note: the CLAUDE.md copy injected into agent context is older than the file on disk (e.g. it still says
`gl.setFrontStreamPreMirrored` and "tap-AF needs NO un-flip"). Every claim below was checked against the
on-disk file, which already corrects those.

## Scope inventory

- Docs: `CLAUDE.md` (on disk; the hard-won facts, the log-quota, status, front-camera, encoder and
  settings bullets, plus every claim changed in cycle 5, `git diff ea7d4374..HEAD`); `docs/ARCHITECTURE.md`
  (threading table, camera selection, DeviceProfile table, zoom pipeline, every claim changed in cycle 5);
  `docs/FIELD_CHECKS.md` (header exhaustiveness claim, A9, F1–F8); `README.md` device-support paragraph;
  `docs/plans/2026-10-02-rpl-cycle5.md` (`[x]` items and progress log).
- Code: `camera/{DiagnosticTelemetry, StartupTrace, VendorTagInspector, CameraStatus, CameraEngine
  (resume, completeGlInputReady, invalidateCameraReady, capturePhoto RAW notice, tap mapping,
  applyStabilization, REC mic handoff), CameraController (open, still callbacks, applyAfOverrides,
  sessionAttemptPlan), RecordingStorageDispatcher, Teleconverter, CameraState (finder gate,
  punchInResolved, codec enum), OpticsConstraints, CaptureCapabilities}`; `gl/{GlPipeline (FrameGap,
  analysis size), FlipRenderer (transfer codes), FrontMirrorConvention}`; `video/{VideoRecorder, EncoderCaps}`;
  `capture/StillCapturePipeline` (EXIF composer, privacy strip); `storage/{MediaStoreWriter (COMPLETE
  descriptor verdict), SettingsStore}`; `ui/{CameraViewModel (status plate timers, RAW-loss Ready
  notice, stab/fps requests, punch-in ownership, route-inventory fold, phone seed), ZoomMath,
  CameraScreen (tally), CameraScreenPolicy, controls/ControlLabels}`; `MainActivity`;
  `stab/GyroEis`; `AndroidManifest.xml`; `proguard-rules.pro`.
- Systematic sweeps: every back-ticked identifier in CLAUDE.md + ARCHITECTURE.md exists in code (all
  absentees are removed-by-design or platform names); every raw `android.util.Log.i/d` sits under a
  recurring gate; no raw `Log.w/e` bypasses the reserved owner; no aliased `DiagnosticLog` call sits
  under a gate (AGG5-9 fix holds); all process worker/backlog constants in the ARCHITECTURE threading
  table match code.

## Summary

| ID | Severity | Confidence | Kind | Status | One line |
|---|---|---|---|---|---|
| VF6-1 | Medium | Medium | CODE bug (vs ARCHITECTURE + inline comment) | likely | An audio-encoder setup failure calls `stop()` on a possibly errored AAC codec through the unclassified `nativeCleanup`, so a "degrade to video-only" becomes a process-wide recorder quarantine |
| VF6-2 | Medium | High | CODE bug (vs CLAUDE.md + KDoc) | confirmed | `FrameGapAccumulator` zeroes its counts BEFORE the log gate; a refused periodic summary loses the stalls, and the terminal "evidence" row can be silenced, which is the false pass the reserve exists to prevent |
| VF6-3 | Medium | High | DOC drift (FIELD_CHECKS vs plan) | confirmed | FIELD_CHECKS says it is exhaustive but has no entry for cycle-5 PENDING DEVICE items A1.4, A1.11, A2.1, A2.4, and records A1.6 and B.4 as HOST-ONLY while the plan lists them as PENDING DEVICE |
| VF6-4 | Low | Medium | CODE (AGG5-33 VM fix incomplete) | likely | The route-inventory fold resets zoom on the setup thread but POSTS the glide invalidation, so a pinch or flush already queued on main still compounds from the old-scale `pendingRatio` |
| VF6-5 | Info | High | DOC drift | confirmed | VendorTagInspector is described as dumping request/session keys and as a "vendor-tag scan". The code logs only facing, focals, sensor size, physical ids and the concurrency probes |
| VF6-6 | Info | High | comment drift | confirmed | GlPipeline's FrameGap comment still says 200 ms "catches the ~180 ms setRepeatingRequest stalls", which the corrected CLAUDE.md now says is false |
| VF6-7 | Info | High | consistency | confirmed | AGG5-50 left fix-off defaults on `mapTapFocusGeometry` (`mirrorX`, `meteringMirrorX`, `windowRotationDeg`) and `CameraController.open` (`pinAutoFps`, `teleconverterMode`, `videoStabHalMode`) |
| VF6-8 | Info | Medium | DOC arithmetic | confirmed | CLAUDE.md's "no more than 201 [3A rows] under continuous tuple changes" is larger than the whole 168-row shared pool, so 3A alone can silence every other shared producer in a ten-minute soak |

Critical 0 · High 0 · Medium 3 · Low 1 · Info 4.

## Findings

### VF6-1: audio-encoder setup failure still quarantines the process (AGG5-3 sibling the fix missed)
- Severity Medium · Confidence Medium · CODE bug · likely (MediaCodec state semantics; the code's own
  reasoning elsewhere in the same file asserts the precondition)
- Cites:
  - `video/VideoRecorder.kt:424-437`. The `startAudio()` failure handler runs
    `nativeCleanup { audioCodec?.stop() }` (`:432`), then `nativeCleanup { audioCodec?.release() }`.
    On failure it does `return null` with `nativeCleanupFailure` set.
  - `video/VideoRecorder.kt:795-806`. `audioCodec` is assigned before `codec.configure(...)` (`:799`)
    and `codec.start()` (`:806`), so a configure/start throw reaches that cleanup with a codec that
    may be in MediaCodec's Error or Uninitialized state.
  - Contrast `video/VideoRecorder.kt:352-369`. The VIDEO ladder handles the same throw by setting
    `owner.errorLatched = true` ("A component that refused configure/start may now be in the Error
    state; its stop() then throws, which must not end the ladder (AGG5-3)") and routes `stop()`
    through `codecCleanup(...)`.
  - `camera/CameraEngine.kt:6790-6797`: `rec.unsafeStartupFailure()` non-null →
    `enterUnsafeRecorderQuarantine`.
  - Doc: `docs/ARCHITECTURE.md:432-435` ("An `IllegalStateException`/`CodecException` from
    `signalEndOfInputStream()`/`stop()` on a codec whose drain **or setup** already threw ... skips to
    `release()`"). Inline comment `VideoRecorder.kt:426-427` ("degrade to video-only instead of
    aborting the whole recording").
- Why: the AGG5-3 classification covers the video ladder and the finalizer only. The audio-setup path
  still uses the plain `nativeCleanup`, which reads any throw from `stop()` as "native release unproven".
  `MediaCodec.stop()` on a codec left Uninitialized or in the Error state by a failed `start()` returns
  INVALID_OPERATION, which surfaces as `IllegalStateException`.
- Failure scenario: the operator starts REC with audio on. The HW AAC encoder's `start()` fails, which
  is the resource-contention case this degrade path exists for. `stop()` throws, `codecStopped` is false,
  and `start()` returns null with an unsafe failure. The engine enters process-wide recorder quarantine
  (`UNSAFE_RECORDER_RESTART`), and no further recording works until the app process restarts. The
  documented outcome was a video-only clip.
- Fix: mirror the video ladder. Wrap AAC `configure`/`start` in a try/catch that sets
  `audioCodecErrorLatched` (any non-revocation throw), and in the handler use
  `codecCleanup(CodecCleanupCall.STOP, audioCodecErrorLatched.get()) { audioCodec?.stop() }`. Keep
  `release()` on `nativeCleanup`. Add a fake-native-graph test in which AAC `start()` throws and `stop()`
  then throws an ISE, and assert that `start()` returns the input Surface with `expectedTracks == 1` and no
  `unsafeStartupFailure`. Host-testable. Behaviour is byte-identical on PMA110 whenever AAC setup
  succeeds.

### VF6-2: FrameGap counts are reset before the log gate; a refused summary loses the stalls and can silence the terminal evidence row
- Severity Medium (evidence integrity: a FIELD_CHECKS FrameGap read can be a false PASS) · Confidence
  High · CODE bug · confirmed (DEBUG-only)
- Cites:
  - `camera/DiagnosticTelemetry.kt:213-241`. `record()` calls `takeSummary()` (which zeroes `count`,
    `maximumMs` and all buckets) whenever the first gap arrives or 15 s have passed. `finish()` returns
    null when `count == 0`.
  - `gl/GlPipeline.kt:972`:
    `producerGapMs?.let { frameGapAccumulator.record(now, it) }?.let(::emitFrameGapSummary)`. The summary
    is taken (and zeroed) first, then `emitFrameGapSummary` (`:906-922`) may refuse it on the SHARED
    budget only (`recurringDiagnosticAllowed`, non-terminal) and returns silently.
  - `gl/GlPipeline.kt:1805`: the terminal summary is `finish()` → evidence reserve.
  - Claims: CLAUDE.md log-quota bullet ("a 12-row EVIDENCE reserve that only the cold-start line and the
    TERMINAL FrameGap summary may spend ... so a chatty soak must not be able to silence them"),
    `DiagnosticTelemetry.kt:8-16`, and the comment `GlPipeline.kt:902-905` ("a soak whose chatty
    producers drained the shared recurring rows must not turn a missing line into a false pass").
- Why: the reserve protects only the terminal ROW, not the DATA. Once the shared 168 rows are spent,
  every periodic summary is refused, but its counts have already been discarded. The terminal summary then
  covers only the gaps after the last refused periodic summary.
- Failure scenario: during a FIELD_CHECKS A6/F5 soak, the 3A heartbeat, ZoomTrace and FocusConfidence
  rows drain the shared pool (see VF6-8). A burst of 400 ms stalls during a pinch triggers a periodic
  summary, which is refused, and the counts are zeroed. No later gap occurs, so at pause `finish()` returns
  null and NO FrameGap line is printed. The checker reads "no FrameGap row" as "no stall > 200 ms" and
  records a false PASS. With later gaps the terminal row prints, but under-reports count and max.
- Fix (host-testable): keep the counts until a summary is actually admitted. Either make `record()`
  return a candidate and add `commit()`, called only after the gate admits (otherwise the counts carry
  forward), or keep a cumulative since-start total that the terminal summary always reports (count,
  max and buckets), with the periodic rows as deltas. Add a test in which the shared budget is
  exhausted, one gap is recorded, the periodic emit is refused, and `finish()` still yields count=1.
  PMA110 behaviour: none (DEBUG logging only).

### VF6-3: FIELD_CHECKS "exhaustive" claim misses cycle-5 PENDING DEVICE items; host-only vs pending-device conflict
- Severity Medium (same class as AGG5-57; the plan's C.7 `[x]` says it was fixed) · Confidence High · DOC
  drift · confirmed
- Cites: `docs/FIELD_CHECKS.md:3-7` ("This ledger is exhaustive ... A change whose device effect has NO
  field procedure is listed too (section F)"), the status line `:11`, and section F `:510-597`. Plan
  `docs/plans/2026-10-02-rpl-cycle5.md:122` (C.7 "FIELD_CHECKS entries/F-rows for the cycle-4 PENDING
  DEVICE items **and this cycle's**", `[x]`) and `:175-176` (progress log: "PENDING DEVICE: A1.1, A1.2,
  A1.4 (visible half), A1.6, A1.11 (visible half), A2.1, A2.4, B.4, B.1/B.2 (E4) ...").
- Mismatch:
  - No FIELD_CHECKS entry, open or ⊘, exists for **A1.4** (a cancelled BURST/AEB no longer continues onto
    a dying session; visible half), **A1.11/M.1** (FRONT+DNG is quiet; RAW loss is announced once per
    session shape), **A2.1** (the stab/fps REQUEST survives Photo-logical/FRONT trips; the device-visible
    effect is that the tele Video route records with Active/ENHANCED and 60 fps again), or **A2.4** (a
    PMA110-visible readout change: the logical chip, ruler and pill read "3.0×", no longer "3.1×").
    A grep of FIELD_CHECKS for any of these returns nothing beyond an unrelated C3 line.
  - **A1.6** and **B.4** are F7/F8 `⊘ HOST-ONLY` ("no device procedure") in FIELD_CHECKS but "PENDING
    DEVICE" in the plan's progress log. One of the two status records is wrong.
- Failure scenario: an owner reading the ledger as the complete open list never checks the A2.1
  recording-profile restore or the A2.4 readout change on PMA110, both of which the plan says are not yet
  device-proven. The "Fourteen remain" count is understated.
- Fix: add A-section entries (or `⊘` F-rows with a "Reopen when") for A1.4, A1.11/M.1, A2.1 and A2.4 and
  update the status line and count. Reconcile A1.6/B.4: either drop them from the plan's PENDING DEVICE
  list or turn F7/F8 into open entries.

### VF6-4: the route-inventory fold resets zoom immediately but invalidates the glide later (AGG5-33 VM half incomplete)
- Severity Low · Confidence Medium · CODE · likely
- Cites: `ui/CameraViewModel.kt:878-892`. The fold's `_state.update` runs on the setup thread and
  writes `controls.zoomRatio = 1f` on a lens-local route change (`cameraRoutePublishedState`,
  `:4849-4868`). The remap hygiene is only `mainHandler.post { invalidateOpticsDerivedState() }`. Compare
  every other remap door, which invalidates synchronously on main (`:2701`, `:2821`, `:2934`, ...).
- Why: between the state write and the posted runnable, main can still run an already-queued
  `zoomTrailingFlush`, ease tick, or pinch event. Each compounds from `currentZoomBase()` =
  `ZoomGlideState.pendingRatio` in the OLD scale, writes it to the engine on the NEW route, and overwrites
  the reset. That is the exact symptom AGG5-33/DB5-14 fixed ("the next pinch compounded from it and
  jumped").
- Failure scenario: an external camera or a front route is re-classified while the operator's fingers are
  on the screen (or within the 16 ms flush window). A main-relative 3.0 lands on a lens-local route as 3×
  digital zoom, and the preview snaps back away from the 1× the fold just published.
- Fix: hop the whole fold to main (state update plus invalidation in one main task), or invalidate the
  glide before publishing, under one main-confined owner. Add a VM test that queues a trailing flush,
  delivers a route-changing fold, and asserts that the flushed wire value is not the old-scale ratio.
  FIELD_CHECKS F6 already covers "zoom snapping"; reopen it if this is fixed.

### VF6-5: VendorTagInspector is documented as a request/session-key or vendor-tag dump; it is no longer one
- Severity Info · DOC drift · confirmed
- Cites: KDoc `camera/VendorTagInspector.kt:16-18` ("records camera characteristics plus available
  capture-request and session keys"); `docs/ARCHITECTURE.md:84` ("for device-specific request/session
  keys"); `CLAUDE.md:1247-1248` ("broad capability and vendor-tag scan"). Code `:268-277` logs only
  facing, focal lengths, sensor size and physical ids, plus the concurrent/physical-pair probes
  (`:46-62`). `git log -S availableSessionKeys` shows the key dump left in 3e389c06/640532e7.
- Fix: describe it as "camera inventory + concurrent/physical finder feasibility probes". This matters
  because a reader looking for vendor key evidence in this dump will find none and may conclude that the
  keys are absent.

### VF6-6: stale FrameGap comment contradicts the corrected CLAUDE.md (VF5-3 follow-through)
- Severity Info · comment drift · confirmed
- Cites: `gl/GlPipeline.kt:964-971` ("200 ms still catches what this line exists for: the ~180 ms
  setRepeatingRequest stalls"). CLAUDE.md (on disk, log-quota bullet) now states that the threshold is
  strict and that a swap stall at or under 200 ms is NOT counted.
- Fix: reword the comment to match CLAUDE.md.

### VF6-7: fix-off defaults survive on two safety seams AGG5-50 did not list
- Severity Info · Confidence High · consistency · confirmed (no current mis-call)
- Cites: `camera/CameraEngine.kt:9296-9318` `mapTapFocusGeometry(mirrorX = false, meteringMirrorX =
  false, windowRotationDeg = 0)`. `meteringMirrorX = false` is exactly the cycle-6 debugger F2 front
  metering bug. `camera/CameraController.kt:279-283` `open(videoStabHalMode = OFF, teleconverterMode =
  false, pinAutoFps = false, ...)`; `pinAutoFps = false` is the 29.97→25 fps low-light defect. The only
  production callers (`CameraEngine.kt:3355-3384`, `:2632-2645`, `:4573-4584`) pass every argument,
  so there is no live defect.
- Fix: remove the defaults (compile-enforced, as A1.10/A2.13 did elsewhere), or record why these two
  seams are exempt.

### VF6-8: the 3A pacing claim exceeds the shared pool it draws from
- Severity Info · Confidence Medium · DOC arithmetic · confirmed
- Cites: CLAUDE.md log-quota bullet ("41 rows over the ten-minute A5 soak; no more than 201 under
  continuous tuple changes") and `SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET = 168`
  (`camera/DiagnosticTelemetry.kt:37-39`).
- Why: under continuous changes, 3A alone can spend the whole shared pool. Every other shared producer is
  then refused, including periodic FrameGap summaries, which with VF6-2 also loses their data. The
  sentence reads as a quota-safe bound when it is not one.
- Fix: say "capped by the 168-row shared pool; can exhaust it under continuous changes", or give 3A its
  own sub-budget.

## Claims verified as MATCHING code (no finding)

- AGG5-9 fix: single charging point. Pre-gated producers emit through `android.util.Log`, with no aliased
  call under a gate (sweep), and the 168 + 12 evidence + 120 reserved split is as documented
  (`DiagnosticTelemetry.kt:37-49, 55-75`). StartupTrace and the terminal FrameGap use the evidence path
  (`StartupTrace.kt:39`, `GlPipeline.kt:906-922`). All are DEBUG-gated at their callers (release R8 keeps
  `Log.i`, so this matters).
- Status plate (AGG5-11): RESPONSE / RESOLVED_BY / `endsCondition` family split, ERROR-severity PROGRESS
  ranked at ERROR, remaining-time re-arm on expiry (`CameraStatus.kt:248-475`, `CameraViewModel.kt:1992-2099`).
- `codecCleanupDecision` for the video ladder and the finalizer, and the EOS-failure mic stop before
  quarantine (`VideoRecorder.kt:211-231, 466-477, 2160-2195`). See VF6-1 for the audio-setup gap.
- `recordingStoragePresentationFor`: an older tail forwards media to the tracker and announces every
  non-SAVED terminal (`RecordingStorageDispatcher.kt:161-171`).
- COMPLETE row decided on descriptor length (`MediaStoreWriter.kt:1421-1440, 2968-2978`).
- RAW-loss Ready notice: once per shape, latched only when shown, never on structurally RAW-less routes,
  with no per-press error (`CameraViewModel.kt:1008-1076`, `CameraEngine.kt:5205-5219`).
- FRONT: `rawSelectable` `!frontFacing`, `sessionAttemptPlan` `!frontRoute`, `punchInResolved`, the
  `FrontMirrorConvention` four-seam derivation and its push from `applyStabilization`
  (`OpticsConstraints.kt:97-103`, `CameraController.kt:2911`, `CameraState.kt:416`,
  `FrontMirrorConvention.kt`, `CameraEngine.kt:2149-2152, 3355-3384`).
- Stills: `afOverrideForRequest` from the frozen packet; buffer-lost / sequence-aborted terminals;
  processed-only shot survives a missing characteristics read (`CameraController.kt:2109-2131, 2201-2225,
  2280-2305, 2343-2349`).
- `invalidateCameraReady` clears Ready before the sealed DNG cancel (`CameraEngine.kt:552-577, 8886-8887`).
- Resume rebind predicate and the GL input-ready split (`CameraEngine.kt:2063-2102, 7849-7865, 8937-8938`).
  This matches FIELD_CHECKS A9.
- Loupe gate (`FINDER_MIN_ZOOM = 3f`, Photo needs 4:3, Video ignores the aspect; `CameraState.kt:399, 664-695`);
  REC tally uses the unscaled platform radius (`CameraScreen.kt:1072-1095`); `SystemBarStyle.dark` for both
  bars (`MainActivity.kt:210-211`); analysis FBO long edge ≤ 256 (`GlPipeline.kt:1849`).
- `pinAutoFps = videoMode` at both open sites; photo AUTO `autoFpsRange` (`CameraEngine.kt:2645, 4584`;
  `CaptureCapabilities.kt:263-265`).
- Encoders: HEVC + AVC offered, APV defined but never offered, 120 fps unselectable, scan latches only on
  success (`EncoderCaps.kt:28-31, 115-149`; `CameraState.kt:1120-1160`). Transfer codes 0/1/2/4/5 with 3
  vacant (`FlipRenderer.kt:633-643`, `Shaders.kt:227-247`). `nativelog` is the DEBUG-only 10-bit EGL gate
  (`CameraEngine.kt:8047-8050`). `"LOG"` migrates to `SLOG3_CINE` (`SettingsStore.kt:327-330`).
- Teleconverter: `seedPhoneModel` falls back `detectPhone ?: OTHER`; `phoneModelDetected` is re-derived
  on every `phoneModel` write; `defaultConverterFor` / `reconcileConverter`; `TELE_MAX_DISPLAY_ZOOM = 60f`.
- Gyro thresholds 4.9 / 2.5; `gl.setEis(false, 0f, 0f)`; the 400 ms standby-mic handoff
  (`CameraEngine.kt:6614`); visual-media trio plus no location or network permission (manifest); aspect is
  4:3 or 16:9 only; Remember/preserve toggles default ON; the portrait lock is below sw600 via
  `lockPortraitOnHandsets`.
- Worker/backlog constants in the ARCHITECTURE threading table: delete 1+8, marker 1+31, still-publication
  2+2, rejected 2+8, retained discard 2+8 with a 250 ms–30 s backoff, recording storage 2+8, pre-native 2+4,
  review pool 4, launch-recovery watchdog 120 s, 64-row pages and markers.
- `zoomDisplayMultiplier` logical-route 1:1 and the standalone nominal-23-mm-within-10 % divisor
  (`ZoomMath.kt:51-85`); punch-in ownership across assist, hold, toggle and recall (`CameraViewModel.kt:3621-3641, 3727-3729`);
  stab/fps request vs displayed value, persisted from the request (`CameraViewModel.kt:857-858, 1864, 1891, 3412-3436, 3505-3520`).
- Passthrough EXIF privacy strip, applied only when a HAL APP1 seeds the composer (`StillCapturePipeline.kt:741-746, 861-895`).

## Final sweep (commonly missed)

Order-of-reset vs gate in accumulators (found VF6-2); sibling call sites of a narrowly scoped fix (found
VF6-1); cross-thread "fix then post" orderings (VF6-4); stale comments beside corrected docs (VF6-6);
default arguments on safety seams (VF6-7); quota arithmetic vs pacing claims (VF6-8); release-build
logging of the info doors (`DiagnosticLogDoors.i` passes `debugEnabled = true`; every caller is
DEBUG-gated, so no leak); identifier-existence sweep of both docs (clean).
