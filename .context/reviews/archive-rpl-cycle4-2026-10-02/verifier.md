# RPL cycle 4 — verifier (VER4), HEAD 14767b0a, 2026-10-02

Scope: evidence check of the concrete claims in `CLAUDE.md` "Hard-won device facts" and
`docs/ARCHITECTURE.md` against the code, after the cycle-3 merge (58eb4f10..6ec34b3d plus the
merge-review fixes). Read-only; no Gradle run. Items already in
`.context/reviews/archive-rpl-cycle3-2026-10-02/_aggregate.md` (AGG3-*) or the cycle-1/2/3 plans
are excluded. Owner decisions (ZSL dark refusal, FocusDetail threshold, CameraUnit SDK,
proprietary HDR, orientation moves no control, shader code numbering) are not relitigated.

Method: direct reads for optics, zoom, settings, permission, EXIF, logging and ladder claims; three
read-only sub-lanes (recording/mic, storage/DNG, GL/Ready) whose findings I re-verified at the
cited lines before including them here.

## Findings

### VER4-1: A complete DNG from a HEIF/JPEG+DNG shot is withheld from the gallery when its processed sibling was never queued

- **Claim** (CLAUDE.md, DNG publication): "mixed-output tails queue only a lightweight transfer
  behind their processed sibling's terminal on `ioExecutor`, then publish on that same process
  owner. Capacity overflow, facade shutdown, transfer rejection, or marker exhaustion settles the
  live capture family…". Also the review claim: "A newer RAW-only success replaces an older
  thumbnail with a truthful DNG metadata placeholder."
- **Evidence**
  - `camera/CameraEngine.kt:5745-5790`: `processedQueued` becomes true only when
    `StillSnapshot.from(jpeg)` succeeded and `ioExecutor.execute` accepted the task. It stays false
    when `jpeg == null`, the snapshot copy fails (OOM, repack failure), or dispatch is rejected.
  - `camera/StillPublicationDispatcher.kt:28-32`: `dngPublicationTransfer` returns
    `AFTER_PROCESSED` whenever `formats.wantsProcessedStill`, without asking whether a sibling exists.
  - `camera/StillPublicationDispatcher.kt:66-76`: `enqueueAfterProcessed = { processedQueued && … }`
    returns false, so `onTransferRejected` runs.
  - The engine wires that to `retainCompletedDngForRecovery` (`CameraEngine.kt:5705-5714`): the row
    stays private and the status is the retained-take copy.
- **Why it diverges**: "transfer rejection" in the claim means the ordered lane refused the task.
  Here there is no sibling to wait behind. The DNG is complete, its COMPLETE marker is durable, and
  the process owner has capacity, yet the shot takes the overflow path.
- **Failure scenario**
  1. HEIF+DNG is on (the common Pro setting) and the processed half fails, e.g. the ~19 MB snapshot
     copy throws under memory pressure.
  2. The operator sees "Photo save failed" and then the retained-take copy.
  3. A valid DNG never reaches the gallery or review.
  4. It surfaces only after a full process restart; see AGG3-5 for why that restart is required.
- **Fix**: in `transferCompletedDngFromCameraCallback`, route `processedQueued == false` through
  the DIRECT branch (`dispatchToProcessOwner`), and keep `retainForRecovery` for a real
  `enqueueOrdered` rejection. Add a host test for mixed formats with `processedQueued = false`;
  the seam is already pure.
- Confidence High · **Confirmed** (code) · Severity **Medium**

### VER4-2: AGG3-7's fix leaves a bare-reopen door Not-Ready indefinitely over a still-streaming controller, with "camera unchanged" copy and no retry

- **Claims**
  - CLAUDE.md: "transient preflight failure uses the bounded retry gate while the preview surface
    remains live".
  - "A rejected same-route terminal commit converges through reconfiguration only while its
    optics intent is still current".
  - The `CAMERA_UNAVAILABLE_CAMERA_UNCHANGED` status itself ("Camera unavailable; camera unchanged").
- **Evidence**
  - `camera/CameraEngine.kt:4232` invalidates Ready before preflight.
  - When `selectCurrentLens()` or `cachedCaps()` returns null on a running camera
    (`recoverColdPreflight == false`), `CameraEngine.kt:4269-4291` calls
    `rollbackOpticsAfterPreflight`.
  - For a `currentOpticsReconfiguration()` token (`:961-970`, `baselinePrecedesMutation = false`),
    `preflightRestorableSessionGeneration` returns null (`:8562-8565`, commit c5bfd1c8). That makes
    `commitOpticsRollbackLocked` take the Not-Ready branch (`:1143-1153`): the session generation
    is bumped, `cameraReady = false`, `acceptedCameraSession = null`.
  - The controller was never closed, because preflight runs before the close.
  - No cold-start retry is scheduled (`scheduleColdStartRetry` is only for
    `startup || controller == null`).
  - Nothing else re-drives the session: `rollbackOptics` publishes the status and returns.
- **Why it diverges**: before cycle 2 this exact state was AGG2-4. Cycle 2 fixed it by
  re-accepting the outgoing controller; AGG3-7 correctly stopped that for bare doors (their
  `before` is post-mutation) but put nothing in its place.
  - The bare door's new field (stab, aspect, hi-res, high-speed fps, video size) stays written in
    the engine while the streaming session does not carry it.
  - The app sits Not-Ready over a live, moving preview until some unrelated door, a pause/resume,
    or a camera error reopens it.
- **Failure scenario**
  1. The operator toggles stabilization (or aspect, or open gate).
  2. A Binder or CameraService hiccup makes `getCameraCharacteristics` fail once.
  3. The preview keeps running and the status says "camera unchanged".
  4. Every shutter/REC press answers `CAMERA_RECONFIGURING` (`CameraEngine.kt:4996`) until the
     operator backgrounds the app.
- **Fix** (either):
  - Give bare doors a pre-mutation baseline: route them through `beginOpticsTransaction`, so the
    cycle-2 restore is valid for them too.
  - Or, for the non-restorable preflight branch on a live controller, schedule one bounded
    `reopenForSession()` retry through the existing retry gate instead of publishing a terminal
    Not-Ready.
  - Either way, add a test that a bare-door preflight failure ends Ready, or in a scheduled retry,
    never in a parked Not-Ready.
- Confidence Medium · **Likely** (code path certain; the trigger needs a transient
  characteristics or selection failure on a running camera) · Severity **Medium**

### VER4-3: The texture-acquisition failure branch does not orphan a preview EGL surface whose detach failed; recovery re-binds the poisoned surface

- **Claim** (CLAUDE.md, preview EGL health): "A draw/swap failure whose preview DETACH also fails
  applies the acquisition branch's containment: fail an active encoder owner, orphan the poisoned
  preview EGL surface …, and abandon the frame — never retain a poisoned owner for ordinary
  same-surface retries".
- **Evidence**
  - The acquisition branch is `gl/GlPipeline.kt:980-993`. When `makeCurrent` or
    `updateTexImage` fails under `FrameAcquisitionOwner.PREVIEW`, it fails the signal and runs
    `runCatching { clearPreviewOutput(core) }`. If that detach fails, it only fails the encoder.
  - `previewEgl`, `previewSurface` and `previewSignal` are reset by `clearPreviewOutput`
    (`:500-510`) only after a successful detach. They are never reset here, and nothing is put in
    `orphanedEglOutputs`.
  - The draw/swap branch (`:1209-1234`) does the full orphan-and-reset, and its comment claims "Same
    containment policy as the texture-acquisition branch above". The model branch is the one that
    lacks it.
  - The same-surface early return in `applyPreviewOutput` (`:419-427`) then installs a fresh
    pending signal on the still-bound poisoned surface.
- **Failure scenario**
  1. `makeCurrent(preview)` throws, and the detach throws as well.
  2. `CameraEngine.handlePreviewFailure` retries `bindPreviewSurface` with the same surface and size.
  3. That reaches the early return. Every later real frame again selects
     `FrameAcquisitionOwner.PREVIEW` and fails.
  4. All three recovery attempts are burned on the same EGLSurface, ending at
     PREVIEW_UNAVAILABLE_REOPEN.
  5. Because the preview stays "available", the encoder can never become the acquisition owner, so
     a REC started then cannot reach its first swap.
  6. This lasts until TextureView hands over a new surface.
- **Fix**: factor the `:1224-1233` block (retain in `orphanedEglOutputs`, clear
  `previewEgl`/`previewSurface`, cancel and null `previewSignal`, return) into one helper. Call it
  from the acquisition branch's `detachFailure != null` case and from the encoder-restore branch.
  Add a host test with failing `makeCurrent` and failing detach, asserting that the next bind
  creates a new EGLSurface.
- Confidence High (code) · **Confirmed** in code; the EGL fault injection is
  Needs-manual-validation · Severity **Low-Medium**

### VER4-4: Launch recovery's JPEG probe (trailing EOI only) can adopt a JPEG whose in-place EXIF rewrite was interrupted

- **Claim** (CLAUDE.md, pending rows): "Relaunch recovery may then adopt JPEG/DNG/video/HEIF only
  after the format's structural probe proves it complete".
- **Evidence**
  - `storage/MediaStoreWriter.kt:1791-1807`: `probeCompleteJpeg` tests only that the last two bytes
    are `FF D9`.
  - The JPEG lanes write the compressed bytes and then re-stamp EXIF in place while the row is still
    REGISTERED (`capture/StillCapturePipeline.kt:320-330, 370-375`, then `writeJpegExif` at
    `:470-486`, which opens `"rw"` and calls `ExifInterface.saveAttributes()`). That rewrite starts
    at offset 0 and makes the file longer (APP1 is inserted).
  - `orphanDisposition` maps VALID to ADOPT (`MediaStoreWriter.kt:2744`).
- **Why it diverges**: a process death mid-rewrite (a swipe-kill right after the shutter) leaves a
  new header plus shifted data followed by the old, unshifted tail. That tail still ends `FF D9`,
  so a corrupt image probes VALID and is published.
- **Distinct from AGG3-24**: that item is the live-path cost and partial-failure publish. This is
  the recovery-adoption truth claim.
- **Fix**: splice APP1 into the encoded buffer before any byte reaches the row (the AGG3-24
  direction, which removes the window), or walk the segments SOI → … → SOS → EOI and require
  consistent segment lengths.
- Confidence Medium · **Needs-manual-validation** (the FD-mode rewrite order inside androidx
  `ExifInterface`) · Severity **Medium**

### VER4-5: B.3's expiry re-arm skips two branches that also leave the row pending

- **Claim** (cycle-3 plan B.3 / AGG3-4 fix): "When launch recovery keeps a row pending, re-assert
  `IS_PENDING = 1`".
- **Evidence**
  - `MediaStoreWriter.kt:1388-1396`: a journal `UNAVAILABLE` row does `continue` before any re-arm.
  - `:1422-1427`: ADOPT whose `publish()` fails records `PUBLISH_FAILED` and leaves the row
    pending, with no `reassertPending`.
- **Failure scenario**: a structurally valid take whose publish keeps failing across launches
  (provider policy throw, volume busy) is never re-armed and reaches the provider's pending expiry.
  That is the loss AGG3-4 was meant to close.
- **Fix**: call `reassertPending` on the `PUBLISH_FAILED` and UNAVAILABLE paths too. Extend the
  fake-resolver test.
- Confidence High (code path); the expiry effect is Needs-device · **Confirmed** · Severity **Low**

### VER4-6: Recovery treats provider `SIZE <= 0` as structural INVALID before any probe or journal check

- **Claim**: same as VER4-4 ("adopt … only after the format's structural probe proves it
  complete"; "deletes only proven-invalid unfinished output").
- **Evidence**: `MediaStoreWriter.kt:1401-1403`. `sizeBytes <= 0L -> INVALID` is evaluated BEFORE
  the `COMPLETE`/`DISCARD` journal arms and before `probePendingMedia`.
  - For a REGISTERED row this deletes without opening the bytes.
  - For a COMPLETE row, `orphanDisposition` still ADOPTs (`:2743`), so a zero-SIZE COMPLETE row is
    published without any byte check.
- **Why it diverges**: `SIZE` is provider metadata, not structural proof. Whether MediaProvider
  keeps it current for a pending row after an abrupt process death is unverified.
- **Fix**: when `SIZE <= 0`, open the descriptor and use the real length (`fstat`/`channel.size()`),
  or return INDETERMINATE.
- Confidence Low · **Needs-manual-validation** · Severity **Low**

### VER4-7: CLAUDE.md says the rotation override has "exactly one caller"; the code has two

- **Claim** (`CLAUDE.md:265-266`): "this override is an explicit per-call opt-in with exactly one
  caller — never make it a settable field."
- **Evidence**:
  - The main preview draw passes `rotationOverrideDeg` (content rotation plus window term) at
    `gl/GlPipeline.kt:1065-1069`.
  - The Loupe Overview passes it at `:1123`.
  - CLAUDE.md's own large-screen bullet ("The preview term rides `FlipRenderer.draw`'s per-call
    `rotationOverrideDeg`") contradicts the "one caller" sentence.
- **Failure scenario**: a maintainer enforcing "exactly one caller" removes the preview use. Moving
  that term into `setRotationDegrees` reopens the cycle-4 overscan bug on sw600dp windows; dropping
  it draws the field sideways.
- **Fix**: say "two draws use it (preview: content + window term, only when the window is rotated;
  overview: window term only)". Keep the rule "per-call only, never renderer state".
- Confidence High · **Confirmed** · Severity **Low** (docs)

### VER4-8: A failed JPEG EXIF re-stamp is silent; the HEIF lane logs the same failure

- **Claim**: "every silent exit on the insert / registration / identity path logs a reserved
  diagnostic row … A save that fails with no app log line is the signature of THIS class of defect".
  Also the EXIF-parity claim ("ISO / exposure / 35mm focal / make / model … stay in parity across
  both processed formats").
- **Evidence**:
  - `capture/StillCapturePipeline.kt` JPEG and passthrough lanes use a bare
    `runCatching { writeJpegExif(...) }` with no `onFailure` (`~:323`, `~:372`).
  - The HEIF lane reports through `bestEffortHeifExif`/`Log.w` (`:269-277`).
- **Failure scenario**: a JPEG publishes without ISO, exposure, make/model, or (on the hi-res
  passthrough lane) the orientation tag that its uprightness depends on, and logcat has no row.
- **Fix**: add a reserved `Log.w` in `onFailure`, mirroring the HEIF lane.
- Confidence High · **Confirmed** · Severity **Low**

### VER4-9: `dd91413c` detached the KDoc of `sleepPreservingInterrupt` (new instance of the AGG3-56 pattern)

- **Evidence**: `storage/MediaStoreWriter.kt:2398-2410`. The AGG-30 interrupt-contract KDoc now
  sits on `FINALIZED_VIDEO_PARSE_RETRY_MS`'s KDoc and constant. `sleepPreservingInterrupt` itself
  has no doc.
- **Fix**: move the constant and its one-line KDoc above the AGG-30 block.
- Confidence High · **Confirmed** · Severity **Low** (docs)

### VER4-10: A refused microphone claim retires the REC attempt with no status

- **Claim** (CLAUDE.md, REC): "Do not let camera errors leave phantom REC/audio/UI state". Every
  sibling refusal in the start path reports a status (CAMERA_RECONFIGURING, MICROPHONE_BUSY,
  UNSAFE_RECORDER_RESTART…).
- **Evidence**: `camera/CameraEngine.kt:6256-6259`. When
  `standbyAudioController.beginRecording()` is not admitted, the code calls
  `retirePendingRecordingRow(…, "mic-claim-refused")` and returns `false` with no `onStatus`.
- **Failure scenario**: reachable only through a claim that was never finished or aborted
  (`StandbyMeterOwnership.beginRecording`). In that case a REC press clears its optimistic
  "starting" state and appears to do nothing.
- **Fix**: emit `MICROPHONE_BUSY` (or `RECORDING_FAILED`) there.
- Confidence Medium · **Likely** · Severity **Low**

### VER4-11: The frozen REC packet's diagnostic bitrate fallback reads the live codec

- **Claim** (CLAUDE.md, REC): "That immutable packet is carried through GL/native recorder setup
  rather than re-reading independently mutable fields".
- **Evidence**: `camera/CameraEngine.kt:6464` sets `requestedBitRate = bitRateFor(size, rate)`.
  `bitRateFor` (`:7944-7945`) reads the live `videoCodec` field.
  - The real encoder lambda `attemptBitRate` (`:6465-6473`) uses the frozen `codec`.
  - `requestedBitRate` feeds only the diagnostic fallback (`rec.configuredBitRate ?: requestedBitRate`).
- **Impact**: wrong logged bitrate after a codec edit between admission and setup. The file is
  unaffected.
- **Fix**: pass the frozen codec, or inline `attemptBitRate(size.width, size.height)`.
- Confidence High · **Confirmed** · Severity **Low** (diagnostic only)

## Claims checked and upheld (selection)

- Log quota: `RECURRING_DIAGNOSTIC_ROW_BUDGET = 180` + `RESERVED = 120` = 300. VideoRecorder,
  StillCapturePipeline and VendorTagInspector alias `DiagnosticLog as Log`. The one raw
  `android.util.Log.i("BtnDbg")` (`MainActivity.kt:796`) is DEBUG-gated, change-gated, and spends
  `processDiagnosticLogBudget`. `PREVIEW_FRAME_GAP_THRESHOLD_MS = 200`, 3A 3 s/15 s pacing.
- Constants: `HAL_SAFE_MAX_STILL_EXPOSURE_NS = 4 s`, `PREVIEW_FLUIDITY = 1/15 s`,
  `PREVIEW_SAFE = 500 ms`, gain ≤16, AE step 0.30–1.20 with slope 0.5, ZSL 1/6 stop / 2 % / 400 ms /
  depth 3, `TELE_MAX_DISPLAY_ZOOM = 60`, `FINDER_MIN_ZOOM = 3`, `FLAT_GRAVITY_THRESHOLD = 4.9` (≈½ g),
  `LEVEL_GRAVITY_THRESHOLD = 2.5`, `MACRO_HOLD_MS = 700`, `FOCUS_DETAIL_MAX_AGE_MS = 1000`, lag 32.
- Watchdog: HAL-auto 8 s floor; app-owned exposure is ceil-to-ms of the clamped value + 8 s, with
  saturating arithmetic (`ManualControls.kt:591-609`, `CameraController.kt:2034-2043`).
- Ladder: `useRaw … && !tenBitVideoOnly` on every rung (A.1 landed). `maxSessionAttempt` and the
  plan both key the TC table on `teleconverterMode && deviceProfile.vendorTcSessionType`. YUV still
  degradation is monotonic on `ladderAttempt`.
- DNG route input: restore passes `resolvedRawWanted` inside `setResolvedOptics`. The
  `rawSelectable` gate matches the documented formula. The caption keys on accepted `hlg` (A.6).
- Zoom: 16 ms trailing flush, 250 ms quiet landing, 700 ms interaction end, 40 ms controls
  throttle, 500 ms settings debounce. Caps reconciliation does not re-base a live gesture's pending
  ratio. Remap doors go through `invalidateOpticsDerivedState`.
- Settings: every SharedPreferences write uses `commit`; Remember defaults ON;
  `preserveLensSelection`/`preserveTeleconverter` default ON; no facing is persisted.
- Phone/converter: `phoneModelDetected` is re-derived on every phone write (rollback, restore,
  picker); an unrecognised phone seeds OTHER; `ZEISS_200_X300` is declared before `ZEISS_400`;
  model-string reads are confined to `detectPhone`, `DeviceProfile.resolve` and EXIF identity.
- EXIF: make/model come from the build, a blank value omits the tag, an EXTERNAL route omits host
  identity, and the lens model uses the measured equivalent with unknown tokens omitted.
- Front: `pickFrontBest` prefers a plain id, then the largest array, then the id.
- System bars use `SystemBarStyle.dark` for both bars; the portrait lock applies below sw600;
  the tally uses the unscaled `RoundedCorner` radius and is hidden while REC is starting.
- Hardware keys: 767 is the half-press, 781 the quick button with the denial-recorded audio drop,
  and 168/169 (+769) are zoom steps.
- Recording/mic (sub-lane): admission latch, frozen packet (except VER4-11), pre-native allocator
  2+4 with an 8 s deadline, 400 ms handoff → MICROPHONE_BUSY, standby `MAX_RECREATES = 3` reset by
  PCM, degrade-to-video-only, single input-Surface release, allocation-free gain with RMS on a
  100 ms cadence, explicit `KEY_COLOR_TRANSFER`, HEVC/AVC only, 3840 cap, 120 fps excluded,
  `pinAutoFps = videoMode`.
- Storage (sub-lane): REGISTERED before bytes and COMPLETE before publish; fail-closed marker;
  `external` union volume resolution with logging; DNG process owner 2+2, rejected-output owner 2+8,
  8 s allocation deadline; review ownership by monotonic id with TRACK_ONLY on eviction; B.1 idat
  probe, B.2 confirming re-parse.
- GL/Ready (sub-lane): posts before `start()` are dropped and the start callback replays the full
  `RendererAssists` snapshot; unbind→destroy; ≤3 preview retries; Ready only after a real-frame
  swap; FBO ≤256; FrameGap counts real frames only with buckets; scissor disabled in `finally`;
  monotonic Ready sequence rechecked in the VM reducer; StartupTrace owner and disarm; PROGRESS
  statuses have no timer.
- `TerminalAcquisitionGate.isOpen()` is a lock-free `@Volatile` read while `runIfOpen`/`close` stay
  synchronized.

## Final sweep: claims not verifiable statically

Device-only claims were not re-measured: the ~180 ms swap stall, HAL false-lock, `flashState` lie,
OIS profile, ColorOS quota window and cold-start budget. One minor wording mismatch is not a
defect: the "39 mm" generic-1.5× example in CLAUDE.md holds for the Lens caption, but the OSD and
EXIF round TELE focal to the nearest 10 mm (`Overlays.kt:866-869`, `CameraEngine.kt:8016-8017`),
so both read 40 mm.

**Totals**: 11 findings. Medium: VER4-1, VER4-2, VER4-4. Low-Medium: VER4-3. Low: VER4-5 to VER4-11.
