# Cycle 32 aggregate review

Date: 2026-08-24
Reviewed revision: `64eff08e22f856b42f70be7f2a63581c30e265a9`
Workspace: clean isolated clone `/private/tmp/find-x9-rpf32.SEkU6E/repo`

## Review coverage

All required perspectives returned: code-reviewer, architect, critic, perf-reviewer, tracer,
debugger, security-reviewer, verifier, test-engineer, document-specialist, and native Android
designer/accessibility reviewer. Reviewers inventoried all 440 tracked paths and examined the full
Camera2/GL, capture/storage/deletion, ViewModel/UI, audio/video, settings/MR, documentation, build,
release, privacy, device-harness, and test surfaces. Browser automation was not applicable to this
native Compose application. Existing reviews and completed plans through cycle 31 were checked first.

## Deduplicated findings

### AGG32-01 — review reopens before recorder and microphone finalization is terminal

- **Severity / confidence:** Medium / High
- **Agreement:** code-reviewer, architect, and critic.
- **Evidence:** `CameraViewModel.kt:3113-3124` clears the starting/recording UI flags immediately
  after `engine.stopRecording()`, and the review gates at `CameraScreenPolicy.kt:73-76` and
  `CameraViewModel.kt:3308-3317` inspect only those flags. Native finalization remains asynchronous
  through `CameraEngine.kt:5467-5509,5651-5670` and `VideoRecorder.kt:375-420,455-458`, including
  microphone ownership and bounded drain-thread joins.
- **Failure:** pressing Stop and immediately opening prior-video review can start speaker playback
  while `AudioRecord` still owns the recording tail or native teardown is quarantined.
- **Fix:** expose an exact `recordingFinalizing` terminal from Engine ownership, include it in both
  review gates, and test held release/quarantine without falsely presenting a second Stop action.

### AGG32-02 — retirement registry capacity counts listeners while its journal counts families

- **Severity / confidence:** Medium / High
- **Agreement:** code-reviewer, architect, and critic; related capacity consequences independently
  traced by perf-reviewer, tracer, and debugger.
- **Evidence:** `RetainedStillDiscardDispatcher.kt:198-227,277-285` applies the journal's 64-family
  constant to total listener registrations, while `MediaStoreWriter.kt:494-507` bounds distinct
  family markers. Multiple listeners for one family are explicitly supported and tested at
  `RetainedStillDiscardDispatcherTest.kt:216-239`.
- **Failure:** overlapping old/replacement Engines can consume two listener slots per family; 32
  families then exhaust the registry even though the durable journal is half full, fail-closing
  still admission for the next Engine.
- **Fix:** bound distinct families in journal units and define a separate bounded per-family fan-out;
  test 64 families with multiple owners and ensure only a 65th family reaches the capacity edge.

### AGG32-03 — accepted retirement results that remain `RETAINED` have no retry owner

- **Severity / confidence:** Medium / High
- **Agreement:** perf-reviewer, tracer, and debugger.
- **Evidence:** `CameraEngine.kt:4245-4268` submits one exact retirement attempt but does not re-arm
  on `RETAINED`; `RetainedStillDiscardDispatcher.kt:110-121` arms process rescan only for executor
  overflow. `MediaStoreWriter.kt:555-565,589-596` returns `RETAINED` for several transient query or
  marker failures, while launch recovery intentionally cannot retire current-process markers at
  `MediaStoreWriter.kt:1103-1108,1168-1169`.
- **Failure:** a transient accepted-task failure strands the marker, registry slot, and a strong
  Engine-capturing listener until process death; repetition can exhaust capacity and disable stills.
- **Fix:** conflate and back off retries for retryable nonterminal results, distinguish genuinely
  live rows to avoid spinning, demote released-Engine reachability, and test transient failure to
  terminal recovery plus capacity reclamation.

### AGG32-04 — ownerless restored MediaStore rows promise deletion without consent authorization

- **Severity / confidence:** Medium / High
- **Agreement:** security-reviewer and verifier.
- **Evidence:** `MediaStoreWriter.kt:756-773,790-820` maps owner-null rows to a file-only legacy
  family, while `CameraViewModel.kt:3349-3407` invokes direct provider deletion. On scoped-storage
  spec paths, media not owned by the current app requires a user-authorized delete request rather
  than repeated direct `ContentResolver.delete` calls.
- **Failure:** after reinstall/ownership loss, review advertises Delete but every attempt can fail
  without any authorization UI, leaving an indefinite retry loop and misleading affordance.
- **Fix:** model authorization-required deletion, launch a `MediaStore.createDeleteRequest` consent
  flow from the Activity/UI boundary, reconcile its result, and cover cancel/approve/disappear races.

### AGG32-05 — recreate smoke can pass on a stale retained Ready bit

- **Severity / confidence:** Medium / High
- **Agreement:** test-engineer.
- **Evidence:** `MainActivitySmokeTest.kt:63-75,118-131` checks only that the retained Boolean is true
  after `scenario.recreate()`. It never observes Ready becoming false, a newer generation becoming
  Ready, or a real frame reaching the replacement TextureView.
- **Failure:** a black replacement surface with a stale Ready publication passes immediately.
- **Fix:** assert ordered true→false→true generation transitions and a replacement-surface frame
  oracle.

### AGG32-06 — focal-rail test does not protect viewport-relative modifier order

- **Severity / confidence:** Low / High
- **Agreement:** test-engineer.
- **Evidence:** `FocalRailOverflowComposeTest.kt:31-62` asserts `canScrollForward`, which is unchanged
  if `CameraScreen.kt:2669-2676` reverses fade and scroll modifiers.
- **Failure:** the fade can move with content again while the regression stays green.
- **Fix:** assert rendered fixed-edge fade behavior at multiple offsets, or test a pure coordinate
  seam plus one visual/device check.

### AGG32-07 — AF tests do not prove the check/cross cue is rendered

- **Severity / confidence:** Low / High
- **Agreement:** test-engineer.
- **Evidence:** `FocusReticlePresentationTest.kt:8-24` covers only enum mapping, not the actual Canvas
  geometry at `Overlays.kt:395-416`.
- **Failure:** removing or drawing an invisible/off-canvas terminal glyph leaves tests green and
  returns the reticle to a color-only distinction.
- **Fix:** extract and test nonempty in-bounds CHECK/CROSS geometry and positive outline widths;
  add a bright/dark image assertion.

### AGG32-08 — ViewModel review-defense test omits the starting-only truth-table state

- **Severity / confidence:** Low / High
- **Agreement:** test-engineer.
- **Evidence:** `CameraViewModelRobolectricTest.kt:597-613` covers `(recording=true, starting=true)`
  and `(true,false)` but not the normal admission state `(false,true)`.
- **Failure:** a future ViewModel guard reduced to `isRecording` would permit non-Compose review
  during codec/microphone startup while its direct regression remains green.
- **Fix:** table-test all four starting/recording combinations at the ViewModel boundary.

### AGG32-09 — enabled horizon level has no accessibility representation

- **Severity / confidence:** Medium / High
- **Agreement:** document-specialist and native Android designer.
- **Evidence:** `Overlays.kt:275-320` exposes live deviation only through Canvas position and color;
  its caller at `CameraScreen.kt:798-809` supplies no semantics.
- **Failure:** TalkBack/Switch Access users can enable Level but cannot inspect level/tilt state.
- **Fix:** add EN/KO coarsened level/left/right state on a stable semantic node, without a chattering
  live region, and test disabled/level/directions/locales.

### AGG32-10 — still-review pan is unbounded and can move the image fully off-screen

- **Severity / confidence:** Medium / High
- **Agreement:** document-specialist and native Android designer.
- **Evidence:** `MediaReview.kt:814-816,929-960,978-981,1227-1242` clamps scale but accumulates pan and
  point-centering offsets without fitted-content/viewport bounds.
- **Failure:** repeated pan at high zoom can leave a blank viewport with no gesture-guaranteed route
  back to visible image content.
- **Fix:** single-source fitted geometry, clamp every pan/scale/tap/size/orientation transition, and
  pure-test letterboxed portrait/landscape bounds from 1x through 12x.

### AGG32-11 — review accepts spatially unrelated or impossibly fast taps as double-taps

- **Severity / confidence:** Low / High
- **Agreement:** document-specialist and native Android designer.
- **Evidence:** `MediaReview.kt:904-915,966-985` records only first-tap uptime, with no position or
  minimum inter-tap interval.
- **Failure:** two quick taps on distant details unexpectedly zoom and recenter.
- **Fix:** use Compose/platform double-tap ownership or an equivalent time-and-distance predicate;
  test near/far, too-fast, timed-out, drag, and pinch sequences.

### AGG32-12 — tap-AF creates duplicate overlapping full-viewfinder accessibility identities

- **Severity / confidence:** Low / High
- **Agreement:** document-specialist and native Android designer.
- **Evidence:** the viewfinder node at `CameraScreen.kt:667-684` already owns focus actions, while the
  fill-size reticle at `Overlays.kt:339-362` adds another preview-sized identity and terminal results
  are announced again at `Overlays.kt:420-438`.
- **Failure:** TalkBack traverses an unstable duplicate viewfinder-sized stop and can hear the same
  result twice.
- **Fix:** keep one durable viewfinder identity with AF `stateDescription`, keep only a small change
  announcer, and make the Canvas decorative; test one focus stop across all AF states.

### AGG32-13 — architecture states the PMA110 lens list as a generic Lens-tab contract

- **Severity / confidence:** Low / High
- **Agreement:** document-specialist and native Android designer.
- **Evidence:** `docs/ARCHITECTURE.md:1164-1178` says the Lens tab contains fixed 0.6x/1x/3x/10x
  choices, while `ProSheet.kt:1141-1153` capability-filters `LensChoice.entries` and
  `CLAUDE.md:568-579` identifies unconditional choices as a prior multi-device defect.
- **Failure:** QA or maintainers can reintroduce unreachable lens choices on other devices.
- **Fix:** document device-enumerated presets, qualifying the four values as PMA110-specific, and
  add a docs contract.

### AGG32-14 — architecture presents an absent optional UX policy as a normal link

- **Severity / confidence:** Low / High
- **Agreement:** document-specialist and native Android designer.
- **Evidence:** `docs/ARCHITECTURE.md:28-31` links `docs/UX_POLICY.md`, which is absent from clean
  clones and explicitly optional under `CLAUDE.md:3-10`, without a qualifier or fallback pointer.
- **Failure:** clean-clone contributors encounter a dead normative link and may block on private
  context despite the repository's self-contained fallback contract.
- **Fix:** mark the link optional/when-present, identify `CLAUDE.md` plus the preceding paragraph as
  fallback authority, and enforce the qualifier in `tools/check_docs.py`.

## Agent failures

None. Every spawned reviewer returned and wrote its provenance report.

## Final sweep result

Fourteen actionable findings survived evidence checking: seven Medium and seven Low, all High
confidence. The related retirement findings were retained separately because one is a capacity-unit
mismatch and the other is an absent retry owner. No additional security, correctness, performance,
architecture, documentation, test, or UI/UX issue survived the final sweep, and no device/HAL
behavior was inferred from host code.
