# Code reviewer + architect + critic review — cycle 32

Date: 2026-08-24
Reviewed revision: `64eff08e` (`main`, matching `origin/main` in the isolated clone)
Workspace: `/private/tmp/find-x9-rpf32.SEkU6E/repo`

## Authority, inventory, and review method

I read `CLAUDE.md` first, then the complete committed `docs/ARCHITECTURE.md` and
`docs/FIELD_CHECKS.md`. The optional private maintainer files named by `CLAUDE.md`
(`docs/BACKLOG.md`, `docs/TESTING.md`, and `docs/UX_POLICY.md`) are absent from this clean clone, as
the committed policy explicitly permits. I also read the cycle-31 aggregate and completed plan
before reviewing current code, so fixed findings were not reopened merely under new names.

Every one of the 440 tracked paths was inventoried. The live review surface comprises:

- 98 production Kotlin files: four application/activity/policy roots; 34 camera/session/ownership
  files; five capture, two focus, 11 GL/EGL, one stabilization, five storage, 29 UI/controls/review,
  and seven audio/video files.
- 205 JVM/Robolectric/Compose/instrumented Kotlin tests, including all 72 camera-owner tests, 36 UI
  tests, 24 control tests, 18 GL tests, 13 video tests, and the four on-device probes.
- 32 Python/shell device-harness and host/release/coverage tools together with their tests; all
  manifests, Gradle/dependency-verification/R8/Compose configuration, EN/KO resources, backup/data
  extraction policy, privacy/store documents, baseline profile, and public architecture/field
  authority.
- Binary fonts, screenshots, wrapper JAR, compressed evidence, and raster Play assets were
  inventoried as artifacts, not treated as executable source. Historical plans/reviews were used
  only for provenance and duplicate checking.

The cross-file review traced Camera2 route and optics ownership; preview-window/GL/encoder lifecycle;
recording admission, audio, Stop, detach, native finalization, and storage publication; still-family
producer/publication/deletion journals and process replacement; capture tracker/review pin/delete
state; settings and MR normalization; Compose modal/input/accessibility state; and host/device/release
evidence. Because cycle 31 had already reviewed revision `a69c1274` exhaustively, I additionally
examined every source, test, tool, and authority change from that revision through current HEAD and
rechecked each changed seam against its unchanged callers and owners.

## Findings

### CAC32-01 — Stop re-enables review before the recorder and microphone are terminal

- **Severity / confidence:** Medium / High
- **Status:** Confirmed lifecycle/state-consistency defect from deterministic control flow
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt:73-76` models review
  admission with only `isRecordingStarting` and `isRecording`. The ViewModel defense at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3308-3317` uses the same two-bit
  predicate. On Stop, `CameraViewModel.kt:3113-3124` calls `engine.stopRecording()` and immediately
  publishes both flags false. The Engine does not synchronously stop native capture: it marks
  `recorderTeardownInFlight = true`, detaches ownership, and starts asynchronous EGL/native
  finalization at `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5467-5495`.
  Its own contract says the microphone remains owned throughout that interval
  (`CameraEngine.kt:5505-5509`), and the flag clears only after checked native release at
  `CameraEngine.kt:5651-5670`. `VideoRecorder.stopNative` flips `running`, calls
  `AudioRecord.stop`, joins both drain threads for up to three seconds each, and releases the input
  only on that asynchronous path (`app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:375-420,455-458`).
- **Why this is a problem:** the new review lockout covers admission and active REC but not the
  third state the recorder architecture already treats as load-bearing: finalization. UI state says
  review is safe while the encoder may still be accepting the tail of a take and `AudioRecord` may
  still be live. This reopens the exact speaker-to-microphone overlap the cycle-31 change intended
  to close, and it can also make MediaPlayer contend with a finalizing audio route. A quarantined or
  slow native owner makes the interval much longer than an ordinary frame.
- **Concrete failure scenario:** record a clip with audio, press Stop, then immediately tap the now
  enabled prior-video thumbnail. The review overlay prepares/autoplays speaker audio while encoder
  detach has not yet dispatched or completed `stopNative`; that sound can enter the recorded tail
  after the operator pressed Stop. Under a slow/wedged drain, review remains enabled throughout the
  multi-second finalization/quarantine transition.
- **Suggested fix:** publish a distinct ViewModel-visible `recordingFinalizing` state owned by the
  Engine's exact native-release terminal (including quarantine classification), and include it in
  both the Compose and defensive ViewModel review gate. Do not keep `isRecording` true merely to
  reuse the old predicate, because that would keep presenting a second Stop action after Stop has
  already won. Add a deterministic test in which Stop clears active REC, native release is held,
  review remains disabled, and release/quarantine enables it exactly once.
- **Cross-cutting implication:** the UI recording model currently represents user intent, while
  standby audio and native acquisition correctly use resource ownership. Any future audio/playback
  door must key on the latter terminal, not infer it from `isRecording == false`.

### CAC32-02 — the reconciliation bound counts listeners, but the durable bound counts families

- **Severity / confidence:** Medium / High
- **Status:** Confirmed architectural bound mismatch; the multi-owner overlap is an exceptional but
  explicitly supported process-replacement case
- **Evidence:** `RetainedStillRetirementRegistry` stores an `IdentityHashMap` of listeners per
  family, but applies `maxRegistrations` to the total listener count at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/RetainedStillDiscardDispatcher.kt:198-227`.
  Production initializes that limit from `MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS` at
  `RetainedStillDiscardDispatcher.kt:277-285`. The durable journal's corresponding capacity check,
  however, counts distinct marker keys/families (`app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:494-507`).
  Multiple listeners for one family are deliberately supported and already tested as two consumed
  registrations at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/RetainedStillDiscardDispatcherTest.kt:216-239`.
  When the mismatched listener bound rejects a live-family registration, `CameraEngine.kt:4195-4211`
  skips the otherwise-available durable commit and reports durability failure; the local owner then
  permanently closes still admission for that Engine (`RetainedStillDeletionOwner.kt:101-109,243-246`).
- **Why this is a problem:** “at most 64 durable families” does not imply “at most 64 local owners.”
  A process-replacement overlap can legitimately require more than one exact Engine listener for a
  family, so the reconciliation map can reject work while the durable journal still has family
  capacity. The failure is not just backpressure: it converts a bookkeeping-capacity mismatch into
  an Engine-lifetime shutter lockout.
- **Concrete failure scenario:** unresolved retirement work retains 32 family markers while an old
  and replacement Engine each still own reconciliation for those families. Those 32 keys consume
  all 64 listener slots. Deleting a 33rd family is refused by the registry even though the durable
  journal is only half full; for a live still, `completeDeletionDurability(false)` leaves subsequent
  still capture disabled until Engine/process replacement.
- **Suggested fix:** bound the registry by distinct families in the same unit as the journal, and
  define a separate explicit bound for per-family listener fan-out (or use one process relay per
  family that holds bounded local owner tokens). Registration capacity must not be derived from a
  differently measured store capacity. Add a matrix with 64 distinct families, multiple listeners
  per family, rollback, exact retirement, and replacement-Engine notification; assert that only a
  65th family, not the 65th listener, hits the journal-aligned capacity edge.
- **Cross-cutting implication:** architecture text currently calls both structures “equally
  bounded,” but equality of the numeric constant hides unequal units. Capacity invariants should
  name whether they count families, Engines/listeners, rows, or tasks.

## Final missed-issue and file-coverage sweep

After the findings above, I rechecked every changed cycle-31 implementation against its tests and
unchanged consumers: typed provider-vs-marker deletion, cross-Engine retirement publication,
asynchronous preview binding, optics rollback coverage, callback-drain rendezvous, review semantics,
responsive toggles, AF non-color cues, focal-rail fade ordering, and documentation contracts. I
also swept all production namespaces and tool/device sources for silent catches, unchecked nullable
provider results, executor rejection fallbacks, stale generations, unbounded ownership, unsupported
suppressions, TODO/FIXME markers, hard-coded localization, and comments/tests that contradicted
executable behavior.

No additional actionable code-quality, architecture, correctness, or maintainability finding
survived evidence checking. In particular, the new provider-deletion result correctly keeps absent
rows out of review while retaining exact cleanup metadata; preview binds now leave main without
weakening terminal-gate ordering; and the ToggleRow/AF/focal-rail changes preserve their parent
semantics and production behavior. No device/HAL behavior was inferred from host code. CAC32-01 is
host-provable as an ownership interval but its audible contamination should still be included in a
device regression; CAC32-02 is a deterministic unit-level capacity defect whose production trigger
requires overlapping Engine owners.
