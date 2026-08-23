# Code reviewer + architect + critic + designer review — cycle 10

Date: 2026-08-23  
Repository: `/Users/hletrd/flash-shared/find-x9-ultra-camera`  
Reviewed HEAD: `a714d56b` (`main`, matching `origin/main` at review start)

## Scope, authority, and inventory

I read the current authorities in the required order: `CLAUDE.md`, `docs/BACKLOG.md`,
`docs/ARCHITECTURE.md`, `docs/UX_POLICY.md`, `.context/README.md`, and the applicable testing and
field-evidence rules in `docs/TESTING.md` and `docs/FIELD_CHECKS.md`. Current cycle-9 reviews and
`docs/plans/2026-08-23-rpf-cycle9.md` were checked before filing anything, so completed findings were
not reopened under a new name. Historical reviews/plans and binary assets were inventoried as
provenance, not treated as current executable truth.

The review inventory came from `git ls-files` and covered every live review-relevant file:

- 86 production Kotlin files (45,497 lines), including the complete Camera2/session/route engine,
  capture and storage owners, GL/EGL renderer, stabilization, audio/video recorder, ViewModel,
  Compose camera screen/controls/overlays/review, Activity/Application, and every pure policy/math
  seam.
- 170 host Kotlin tests (30,336 lines), four instrumented tests/probes (547 lines), two debug preview
  sources (560 lines), all manifests/resources (including EN/KO parity), and the baseline profile.
- All 11 device-harness Python modules (6,174 lines), all eight host/release/coverage tool modules
  (1,640 lines), their tests, Gradle/version/dependency-verification configuration, ProGuard and
  Compose stability configuration.
- Current public/privacy/release/architecture/testing/field/UX documents. Generated build output,
  archived review snapshots, compressed device evidence, fonts, and Play raster assets were
  inventoried but were not mistaken for source behavior.

Cross-file passes traced route inventory and hot-plug events through Engine optics ownership,
Camera2 session admission and recording teardown; still completion through durable markers,
publication, family deletion, and launch recovery; persisted/MR state through capability
normalization; preview/encoder orientation and zoom scales; and every visible Compose door through
semantics, modal ownership, responsive layout, localization, and the quiet-viewfinder policy.

## Findings

### CACD10-01 — A topology event during REC permanently strands the camera Not Ready

- **Severity / confidence:** High / High
- **Status:** Confirmed correctness/lifecycle defect from deterministic control flow
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:878-931` treats every
  camera-ID-set change as topology requiring convergence while `started && !paused`, with no
  recording guard. `convergeAfterRouteTopologyChange` at `CameraEngine.kt:976-984` immediately
  calls `beginOpticsTransaction`, whose contract clears `cameraReady`, `readyController`, and
  `acceptedCameraSession`. `reconfigureCamera` publishes another Not-Ready edge at
  `CameraEngine.kt:3135-3150`, but its queued task silently returns when `recorder != null` at
  `CameraEngine.kt:3155-3164` (and again at 3185-3191), without rollback or a deferred retry.
  `stopRecording` at `CameraEngine.kt:4859-4890` tears down only recorder/EGL ownership; it does not
  recreate the accepted camera session. The shutter state explicitly remains stoppable during REC
  but requires `cameraReady` afterward (`camera/CameraState.kt:1476-1486`).
- **Why this is a problem:** a hot-plug event is allowed to consume the accepted-session token even
  though the same method then refuses to perform the replacement. This violates the central optics
  invariant that every Not-Ready generation must finish in commit, rollback, or owned recovery.
- **Concrete failure scenario:** while recording from the built-in rear camera, connect or remove a
  USB camera (or otherwise change `cameraIdList`). The availability callback observes the changed ID
  set and starts topology convergence. REC continues and can still be stopped, but the setup task
  exits on `recorder != null`. After Stop, the live controller has no accepted-session owner and the
  shutter remains disabled until another lifecycle/reopen event happens to rebuild it.
- **Suggested fix:** make topology convergence recording-aware. Update inventory/caches immediately,
  but either (a) avoid opening an optics transaction while the active route remains valid and defer
  a required route change until recorder teardown completes, or (b) attach a generation-owned
  pending-topology action that teardown must run. If the active device itself disappears, let the
  recorder failure owner terminate REC before convergence. Add production-composition tests for
  attach, irrelevant detach, and active-route removal during Starting, Recording, and teardown.
- **Prior-cycle cross-reference:** cycle-9 `AGG9-02` introduced this availability owner and required
  generation-owned convergence, but neither its plan nor current tests cover the recorder guard.

### CACD10-02 — Same-ID external-camera replacement bypasses cache invalidation

- **Severity / confidence:** Medium / Medium
- **Status:** Likely correctness defect; same-ID replacement needs manual external-camera validation
- **Evidence:** `scheduleRouteAvailabilityRefresh` at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:951-960` reduces topology identity
  to `cameraIdList.toSet()` and returns early whenever it equals `knownCameraIds`. Cache invalidation
  at `CameraEngine.kt:884-893` is reachable only after that equality gate and similarly keys
  `topologyChanged` solely on set inequality. Yet the callback receives per-ID available,
  unavailable, and removed edges at `CameraEngine.kt:964-968`. The replacement regression in
  `app/src/test/kotlin/me/hletrd/telecampro/camera/CameraSelector2Test.kt:222-258` tests only
  `{usb-a} -> {usb-b}`; it explicitly classifies `{usb-b} -> {usb-b}` as “busy only,” so it cannot
  represent a different camera reusing one provider/port ID.
- **Why this is a problem:** external camera providers may reuse the same Camera2 ID/device node for
  a different USB camera on the same port. Callback coalescing makes the issue sharper: by the time
  the setup executor reads `cameraIdList`, removal and replacement may already have completed and
  the set is unchanged. `cachedExternalSelection`, caps, stream-size, orientation, focal/EXIF, and
  lens caches then remain those of the removed device.
- **Concrete failure scenario:** replace USB camera A with camera B on the same adapter/provider ID.
  The callback runs, sees the old ID set, and returns. Reopen configures B with A's cached sizes and
  sensor facts; this can reject the session, distort/rotate output, or mislabel focal metadata until
  process restart.
- **Suggested fix:** track availability/removal epochs per ID, not only membership. A definite
  `onCameraRemoved(id)` must invalidate that ID's selection/capability/EXIF state even if a later
  `onCameraAvailable(id)` restores the same set. Distinguish the app's ordinary open-generated
  unavailable edge from remove/replace epochs, and add a same-ID A→B fixture with changed caps.
- **Prior-cycle cross-reference:** this is the untested same-identity half of cycle-9 `AGG9-02`, not
  a duplicate of its fixed different-ID attach/detach case.

### CACD10-03 — Failed durable deletion defeats both ownership bounds and restart safety

- **Severity / confidence:** Medium / High
- **Status:** Confirmed resource/data-lifecycle defect
- **Evidence:** `RetainedStillDeletionOwner` declares a bounded tombstone capacity but retains every
  unresolved URI in an unbounded `LinkedHashMap` at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/RetainedStillDeletionOwner.kt:43-49`.
  `markCaptureDeleted` deliberately breaks out without eviction whenever all tombstones are active
  or unresolved (`RetainedStillDeletionOwner.kt:62-74`), so the nominal 32-entry Engine bound
  (`CameraEngine.kt:5967-5970`) no longer applies. Each failed discard is inserted at
  `RetainedStillDeletionOwner.kt:161-170`; retries are bounded per call but the retained collection
  is not (`RetainedStillDeletionOwner.kt:174-196`). The current regression test explicitly proves
  over-capacity growth (`RetainedStillDeletionOwnerTest.kt:213-240`) but tests only recovery on the
  next manual retry. Production retries the set again only at Engine release
  (`CameraEngine.kt:5738-5751`) and merely logs any survivors. An `UNRESOLVED` result means neither
  the provider delete nor the durable DISCARD journal commit succeeded, so process death loses the
  in-memory veto and launch recovery may adopt the still.
- **Why this is a problem:** the implementation cannot simultaneously claim bounded ownership and
  preserve an unlimited number of failed rows. Under a persistent provider/preferences fault,
  repeated capture/delete cycles grow URI publications, unresolved rows, and tombstones without a
  ceiling; memory pressure can kill the process, which then destroys the only remaining record that
  these otherwise valid rows belonged to deleted families.
- **Concrete failure scenario:** storage/provider operations and the SharedPreferences journal fail
  (full/corrupt/unmounted storage), while the operator continues taking and deleting captures after
  each “could not delete” status. Every output consumes permanent Engine memory beyond the 32-family
  bound. An eventual OOM or normal process restart loses those vetoes, allowing structurally complete
  pending rows to be recovered as live media.
- **Suggested fix:** define an explicit bounded fail-closed state. Once unresolved ownership reaches
  capacity, block further still capture/deletion admission with persistent actionable status until
  a retry succeeds, or move ownership to a genuinely durable bounded journal independent of the
  failing preference path. Schedule bounded background retries while the Engine remains alive, not
  only at release, and test persistent failure beyond 32 distinct families plus process restart.
- **Prior-cycle cross-reference:** cycle-9 `AGG9-04` correctly added typed `UNRESOLVED` ownership;
  this is the missing bounded-capacity/restart disposition in that fix.

## Designer / UI and final missed-issue sweep

The native Compose UI was reviewed directly; browser tooling is inapplicable because there is no web
surface. The sweep covered information architecture, Sony/Xperia quiet-viewfinder policy, 48 dp hit
targets, fixed/scrolled chrome ownership, Fn/My Menu/MR organization, settings/Fn/review modal input
blocking, timer cancellation, keyboard/TalkBack semantics and state descriptions, focus order,
loading/empty/error states, phone/tablet rotated layouts, dark-only camera theme, EN/KO resources,
RTL-absolute finder placement, reduced-animation exposure, and perceived-response paths for zoom and
camera switching. The cycle-9 external-route additions provide both visual OSD identity and
TalkBack state identity, hide rear-only TELE/focal controls, retain the switch only when both
destinations exist, and keep local zoom labels consistent. I found no additional designer-specific
defect beyond CACD10-01's user-visible permanently disabled post-REC shutter.

The final missed-issue sweep rechecked every production owner and corresponding test namespace,
all callback/executor admission and teardown boundaries, Camera2 route/capability caches, still and
video publication durability, settings/MR normalization, GL/encoder rotation and zoom truth,
manifest/resource localization parity, suppressions/ignored tests/TODO markers, build/release
provenance, device-harness mutation ownership, and current-vs-historical documentation. No live
source, test, build configuration, resource authority, or current policy document was skipped.
Device/HAL and TalkBack speech behavior were not inferred from host source; the external-camera
replacement scenario remains explicitly marked for manual validation.
