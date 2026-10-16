# RPL cycle 6 — code-reviewer

- Agent: code-reviewer (CR6-)
- HEAD: 30970c9e (cycle-5 range ea7d4374..30970c9e, 65 commits)
- Scope inventory: I read every cycle-5 source change in `app/src/main/kotlin` and `tools/` (42 files,
  +2596/-685): `camera/` (CameraEngine, CameraController, CameraState, CameraStatus,
  DiagnosticTelemetry, DngPreCaptureAllocation, ManualControls, RecordingStorageDispatcher,
  RetainedStillDeletionOwner, StandbyAudioController, StartupTrace, VendorTagInspector), `capture/`
  (StillCapturePipeline, HeifExif), `gl/GlPipeline`, `storage/MediaStoreWriter`, `ui/`
  (CameraViewModel, CameraScreen, MomentaryHold, ZoomMath, controls/FnQuickActions, ManualDials,
  PhotoFormatChips, ProControls, ProSheet), `video/` (EncoderCaps, VideoRecorder), and
  `tools/build_immutable_release.py`, `run_scoped_signed_release.py`, `verify_host.py`,
  `check_docs.py`. I also traced the unchanged code each change depends on: the Engine Ready
  publication paths, resume/GL input-ready, the recall/rollback fold, the zoom glide runnables,
  `resolveNonTeleId`/`setLens`, CaptureOutputTracker delete planning, the rejected-output owner's
  permit/retry accounting, and AudioDenialReason. Read-only: no Gradle run and no state-changing git.
- The cycle-5 aggregate and plan were used as the known-issues baseline. Nothing below repeats a
  listed item without new evidence.

## Findings

| ID | Sev | Conf | Status | Location | Summary |
|---|---|---|---|---|---|
| CR6-1 | Medium | High | confirmed | `video/EncoderCaps.kt:123-142`, `ui/CameraViewModel.kt:3058-3090` | A failed codec walk is now "not latched", but the VM still applies EMPTY as truth. It persists HEIF→JPEG and log/HLG→SDR, and video stays dead for the VM's life. |
| CR6-2 | Medium | High | confirmed | `ui/CameraViewModel.kt:1168-1177`, `:1728`, `:1748`, `:3431-3471` | The MRG5-8 rollback restores only the VM stab/fps REQUESTS. The engine keeps the rolled-back bank's stabilization and frame rate on the wire, and the display shows the bank's values. |
| CR6-3 | Medium | Medium | needs manual validation | `ui/ZoomMath.kt:65`, `camera/CameraEngine.kt:4272-4280` | AGG5-49's `!standaloneRoute -> 1f` uses the mode/RAW law, not the actual route. On a rear setup with no logical multi-camera, Photo lens presets land on standalone lenses, so the readout now says 3.0× over a 9× crop. |
| CR6-4 | Low | High | confirmed | `camera/CameraEngine.kt:870-896`, `:2395-2418`; `ui/CameraViewModel.kt:1009` | RAW-loss facts ride only `commitOpticsReady`. A Ready that comes from `handlePreviewReady` (cold start, resume, preview recovery) has `rawLoss = null`, so the drop-RAW notice is never announced on those paths. |
| CR6-5 | Low | Medium | likely | `ui/CameraViewModel.kt:876-892`, `:2650-2657` | The route-inventory remap is posted asynchronously. If a new pinch starts first, the post cancels `zoomInteractionEnd` and `zoomQuietLanding` without telling the engine, which leaves the engine's zoom interaction stuck active. |
| CR6-6 | Low | Medium | likely | `camera/RetainedStillDeletionOwner.kt:117`, `:131-134` | `producerTerminalIds` is capped at 32 entries, oldest first. Deleting an older live capture (for example a pinned review during a long timelapse) when the marker fails closes still admission for the whole process: the AGG5-2 symptom through the eviction door. |
| CR6-7 | Low | Medium | likely | `capture/StillCapturePipeline.kt:866-904` | The passthrough privacy strip omits `TAG_XMP` (and the free-text Artist/UserComment/ImageDescription tags). A HAL APP1 can carry GPS or identifiers in XMP. |
| CR6-8 | Info | High | confirmed | `ui/CameraViewModel.kt:4055-4062` | Rebinding one key ends the momentary hold of ANOTHER key that is bound to the same action and still physically held. |

---

### CR6-1: A failed codec walk is applied and persisted as device truth (Medium, High, confirmed)

**Where.** `CodecInventoryLoader.load()` (`video/EncoderCaps.kt:123-142`) now returns
`CodecInventory.EMPTY` without latching after 3 failed walks. Its KDoc says "the next load (the next
ViewModel) walks again". The only caller, `loadEncoderInventoryAsync`
(`ui/CameraViewModel.kt:~3043-3055`), still posts that EMPTY into
`applyEncoderInventory` (`:3058`), which:

- clears `pendingCodecUntilInventory`, `pendingTransferUntilInventory` and
  `pendingPhotoFormatsUntilInventory`;
- normalizes `photoFormats` with `heifEncodeAvailable = false` (HEIF→JPEG) and `transfer` with
  `tenBitEncodeAvailable = false` (HLG and S-Log3/LogC3→SDR);
- publishes `availableVideoCodecs = []` and `encoderInventoryLoaded = true`.

**Why it matters.** AGG5-30 aimed to keep a transient mediaserver/Binder hiccup from becoming
process-permanent. The loader no longer latches, but the VM consumes EMPTY exactly as before:

1. Nothing in this VM retries, because `loadEncoderInventoryAsync` runs once per VM. Video
   recording is refused for the VM's life, since `VideoRecorder.start` now logs "no encoder
   candidate".
2. The degraded values are now the state, and the pending mirrors are gone. The next settings save
   (background `onStop` always saves) persists JPEG and SDR over the operator's HEIF and S-Log3. The
   "next load walks again" recovery then finds the operator's request already overwritten.

**Failure scenario.** Cold start while mediaserver restarts. All three walks throw. The user has
HEIF+DNG and S-Log3 selected. They background the app, and settings save `heif=false, jpeg=true,
transfer=SDR`. On the next launch the walk succeeds, but the restored request is already JPEG/SDR.

**Fix.** Make the loader's answer three-valued (for example `Result<CodecInventory>` or a `failed`
flag). On failure the VM must keep the `pending*UntilInventory` requests and leave
`encoderInventoryLoaded = false`, and retry later (on the next `onStart`, or with a bounded
backoff). Only a successful walk should normalize and persist.

### CR6-2: A recall rollback restores the stab/fps REQUEST but not the wire or the display (Medium, High, confirmed)

**Where.** `applyResolvedSettings` writes the bank's values to three places:

- the engine, outside the optics transaction: `engine.setVideoStabMode(e.videoStabMode)` at `:1728`
  and `engine.setVideoFrameRate(safeFrameRate)` at `:1748`;
- the displayed state (`videoStabMode` and `videoFrameRate` at `:1784` and `:1804`);
- the new requests (`:1687-1688`).

On an asynchronous optics rollback (`:1168-1177`, MRG5-8), only `requestedVideoStabMode` and
`requestedVideoFrameRate` go back to their prior values. `CameraEngine.setVideoStabMode` and
`setVideoFrameRate` (`CameraEngine.kt:2182`, `:3561`) are plain fields that the engine's rollback
snapshot does not cover. The rollback's `state.copy` also does not restore `videoStabMode` or
`videoFrameRate`.

Since AGG5-4, `reconcileZoomToCaps` (`:3431-3471`) no longer calls `engine.setVideoStabMode`. It only
narrows the displayed value from the VM request. After a rollback, nothing pushes the restored
request back to the engine.

**Failure scenario.** The operator is on 30 fps and Standard stabilization, and recalls a bank with
60 fps and Active. The recall's reopen fails ("camera unchanged" rollback). The VM persists 30 fps
and Standard. The engine keeps recording at the bank's 60 fps target with ENHANCED stabilization,
and the Fn and sheet show 60 fps and Active. After the next caps reconcile the display flips to
Standard while the wire stays ENHANCED. This is the same three-way request/display/wire split the
AGG5-4 and MRG5-8 work set out to remove.

**Fix.** In the rollback leg, when a request was restored, push it to the engine
(`engine.setVideoStabMode(requestedVideoStabMode)`), run `reconcileFrameRate()` (it applies the
request through `applyVideoFrameRate`), and re-derive the displayed `videoStabMode` from the
request. Alternatively, put stab and fps into the engine's optics transaction snapshot.

### CR6-3: The logical-route 1:1 readout assumes every non-video, non-RAW Photo route is the logical camera (Medium, Medium, needs manual validation)

**Where.** `zoomDisplayMultiplier` returns `1f` for `!standaloneRoute` (`ui/ZoomMath.kt:65`), and
`standaloneRoute` is `standaloneRouteWanted(video, raw, rawForcesStandalone)` (`:91-104`).

The engine's actual route choice is `resolveNonTeleId` (`camera/CameraEngine.kt:4272-4280`):
`cachedLogicalBack() ?: cachedIdForFocal(choice.targetEquivMm)`. On a rear setup with no
logical multi-camera, a Photo lens preset therefore opens a STANDALONE lens.
`setLens`/`resolveLensOpticsIntent` (`:4058-4072`) and the VM preset handler (`:3204-3213`) still
treat that route as unified and keep `zoomRatio = choice.zoomPreset`.

**Why it matters.** The wire-scale mismatch on such devices predates cycle 5. Before AGG5-49,
though, the display multiplied by `equivalentFocalMm / 23`, so the readout at least showed the real
crop (~9×). The new 1:1 branch makes the pill, Fn chip, ruler and Shoot-tab slider all read "3.0×"
over a 3× digital crop of the 70 mm lens. This misreport sits on the very handsets the multi-device
decision targets.

**Failure scenario.** A non-PMA110 phone exposes standalone main and tele cameras with no logical
multi-camera, so LensInventory lists 3× as optical. In Photo the user taps 3×. The engine opens the
tele at wire zoom 3.0 (about 9× effective), and every zoom surface shows "3.0×".

**Fix.** Key the standalone law on the route the engine actually resolves. For example, include
"no logical back camera" in the predicate both `unifiedZoomOf`/`localZoomOf` and
`zoomDisplayMultiplier` use, or publish route-is-standalone from the engine at Ready. Then the wire
conversion and the display agree. This needs validation on a device without a logical camera.

### CR6-4: The once-per-shape RAW-loss notice never fires when Ready arrives from the preview edge (Low, High, confirmed)

**Where.** `RawLossReadyFacts` is attached only in `commitOpticsReady`
(`camera/CameraEngine.kt:870-896`). That publication is `ready = previewReady`. When the preview has
not yet swapped a real frame (cold start, resume rebind, preview recovery), it publishes
Not-Ready. The actual Ready then comes from `handlePreviewReady` (`:2395-2418`) through
`nextCameraReadyPublication(ready = true, ...)` with the default `rawLoss = null`. The VM fold
(`ui/CameraViewModel.kt:1009`) evaluates `rawLossAnnouncementAtReady` only on Ready publications, and
only when `publication.rawLoss != null`.

**Failure scenario.** DNG is persisted. Cold start lands on the drop-RAW ladder rung, so the session
has no RAW. That is exactly the "RAW-capable route that lost RAW" AGG5-10 promises to announce once,
and the plate stays silent. The same happens after any preview-recovery Ready.

**Fix.** Keep the last accepted session's `RawLossReadyFacts` on `acceptedCameraSession` and attach
them in every Ready publication (handlePreviewReady and the fast-commit restore at `:1220-1236`), or
let the VM fold fall back to the last Not-Ready facts for the same session generation.

### CR6-5: The posted route-change invalidation can kill a live gesture's end edge (Low, Medium, likely)

**Where.** `onCameraRouteInventory` runs on the setup thread and posts
`invalidateOpticsDerivedState()` to main when `activeRoute` changed (`ui/CameraViewModel.kt:876-892`).
That function (`:2650-2657`) calls `zoomGlide.invalidateForRemap()` (`interacting = false`) and
removes `zoomInteractionEnd` and `zoomQuietLanding`, but it never calls
`engine.setZoomInteraction(false)`. At the synchronous VM doors this is harmless because the same
door reopens the engine, and `wireController` resets its interaction state. Here the post can land
AFTER a new pinch began on the new route.

**Failure scenario.** A route topology change (an external camera unplugged, or a startup route
correction) reopens on the new route. The operator starts pinching before the main-queue post runs.
The post removes the pending end and landing runnables. The engine stays in
`setZoomInteraction(true)`: the fps boost and brightness trade stay engaged, HAL zoom submits stay
suppressed, the exact quiet landing never runs, and focus confidence refuses. This lasts until the
next zoom input. A clip recorded meanwhile keeps the wide-aimed edge framing.

**Fix.** Either run the invalidation only if no gesture began after the route publication (stamp a
gesture generation), or make `invalidateOpticsDerivedState` end an active interaction explicitly
(`if (zoomGlide.interacting) engine.setZoomInteraction(false)`) before clearing it.

### CR6-6: A per-id cap of 32 re-opens the process-wide admission close for older captures (Low, Medium, likely)

**Where.** `completeDeletionDurability(durable = false)` adds the id to `nonDurableLiveDeletions`
unless it is in `producerTerminalIds` (`camera/RetainedStillDeletionOwner.kt:117`).
`producerTerminalIds` is trimmed to `maxTombstones` (32) oldest-first (`:131-134`). An evicted id has
no future terminal edge, so a failed marker for it closes `canAdmitCapture()` until process death.

**Failure scenario.** A long timelapse or several BURSTs run while review is pinned on an earlier
frame. Pinned review lives outside the 8-entry tracker history, so `liveStillCaptureId` is still
set. More than 32 captures later, the disk is full, which is AGG5-2's own motivating case. The user
deletes the pinned frame to free space. The marker commit fails. The id was evicted from
`producerTerminalIds`, and every still is refused for the rest of the process.

**Fix.** Treat an id at or below the oldest retained terminal id (ids are monotonic per Engine) as
terminal, or record the "can still produce" answer as a set of in-flight ids (bounded by admission)
instead of a ring of terminal ids.

### CR6-7: The passthrough privacy strip misses XMP and free-text identity tags (Low, Medium, likely)

**Where.** `PASSTHROUGH_PRIVACY_STRIPPED_TAGS` (`capture/StillCapturePipeline.kt:866-904`) blanks the
GPS directory, serials, owner name, unique id and MakerNote. It keeps `ExifInterface.TAG_XMP`
(0x02BC, IFD0), which vendor HALs use for location and device metadata, and it keeps `TAG_ARTIST`,
`TAG_USER_COMMENT` and `TAG_IMAGE_DESCRIPTION`. The lane is dormant on PMA110 (hi-res only), so
impact is limited to capable devices.

**Fix.** Add `TAG_XMP`, `TAG_ARTIST`, `TAG_USER_COMMENT` and `TAG_IMAGE_DESCRIPTION` to the list, and
add a test with an XMP-carrying source APP1.

### CR6-8: A rebind ends another key's hold of the same action (Info, High, confirmed)

`endMomentaryHoldOnRebind` (`ui/CameraViewModel.kt:4055-4062`) releases the action-wide
`momentaryAeLock`/`momentaryPunchIn` when ANY key leaves AEL or PUNCH_IN. If volume and half-press are
both bound to PUNCH_IN and the half-press is held, rebinding volume ends the half-press hold early.
Its later release then restores nothing. This is benign but not "the old action's hold". Track the
owning key on press if per-key truth is wanted.

## Final sweep (no finding)

- **StatusPlate arbitration** (`CameraStatus.kt:393-494`) and the VM timer re-arm: I traced the
  response-covers-event, resolution, condition-end and expire paths. The deferred event and
  deferred progress invariants hold.
- **DNG admission:** the early `releaseDngAdmission()` placement is exactly-once through the
  lease's boolean `release()`.
- **`invalidateCameraReady` reorder** (AGG5-5) and the BURST/AEB head-result plumbing:
  `dispatchAebStep` restores base controls on refuse or throw.
- **`failStill`** (buffer loss, sequence abort): token-guarded and done-guarded. Buffer loss
  compares Surfaces by identity, and the targets are the reader Surfaces.
- **`stillCompletionMissingCharacteristics`:** a processed-only shot with null characteristics
  never reaches `saveDng`.
- **`exifSplicePlan`/`writeJpegOnce`** with a `length` prefix: every write is bounded by the
  plan ranges or `length`.
- **`probeCompleteJpegStructure`:** the APP1-ends-in-thumbnail-EOI case resolves INVALID.
- **`completeMarkerLengthVerdict`** and the `keptRowReassertsPending` COMPLETE re-arm: no immortal
  rows except repeated transient failures.
- **`recoveryVideoVerdict`** fresh-descriptor reparse: an open failure versus a parse failure is
  classified correctly.
- **RejectedOutputCleanupCapacityOwner:** the queue sizing matches the permits, and retries
  acquire permits.
- **VideoRecorder `codecCleanupDecision`:** only an `IllegalStateException` on a latched codec skips
  to `release()`. Release failures still quarantine.
- **Resume rebind** (`resumePreviewRebindWanted`): inert on the ordinary foreground return.
- **Release tooling:** the jvmargs allowlist tokenization, Gradle `PathAssembler` hash replication,
  `keytool_environment`, and `-I -S` isolation are correct. The repository's own
  `org.gradle.jvmargs` passes the allowlist.
