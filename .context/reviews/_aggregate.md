# Review-plan-fix cycle 16 — aggregate review

Date: 2026-08-24
Reviewed HEAD: `987033a`
Mode: host-only; deployment and device interaction disabled

## Review provenance

- `code-reviewer.md`
- `architect.md`
- `critic.md`
- `perf-reviewer.md`
- `tracer.md`
- `security-reviewer.md`
- `debugger.md`
- `verifier.md`
- `test-engineer.md`
- `document-specialist.md`
- `designer.md`
- `qa-adversary.md`

The environment exposed five worker slots beneath this cycle rather than named specialist agent
types, so adjacent roles were grouped across five concurrent workers while each required role wrote
its own provenance report. The repository's registered `qa-adversary` was included; no other
reviewer-style agent was registered under `.claude/agents/`. The reports produced 22 raw finding
entries, deduplicated here into eight current-HEAD findings. Five aggregate findings had independent
cross-agent agreement. The authoritative host gate passed during review: 1,757 Android host tests,
47 tool/release tests, nine coverage-tool tests, 182 device-harness self-tests, 105 documentation
checks, debug assembly/lint, and exact Partition-A coverage at 99.80% (6,492/6,505). Device evidence
was not attempted or inferred.

## Merged findings

### AGG16-01 — TELE and FRONT transitions still reconstruct zoom units from mode or the selected band

- **Severity / confidence:** Medium / High.
- **Agreement:** code-reviewer, architect, and critic.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:450-487,535-553`;
  `camera/CameraEngine.kt:3232-3254,7046-7058`;
  `ui/CameraViewModel.kt:2295-2340,2552-2561,2660-2701`;
  `docs/BACKLOG.md:103-121`; `docs/ARCHITECTURE.md:631-647`.
- **Failure:** three live doors bypass the route-aware `unifiedZoomOf`/`localZoomOf` law. Photo+DNG
  at the optical 3x lens can return from TELE with Engine local 1x but ViewModel local 3x (roughly
  9x displayed framing); a crop-only Video 3x band can be mistaken for a physical 3x multiplier
  when entering TELE; and Video -> FRONT -> Photo+DNG -> rear can restore 3x as a lens-local value
  on the standalone 3x camera. The last case makes Engine and ViewModel agree on the same wrong
  packet, so parity alone cannot detect it.
- **Required fix:** snapshot framing in canonical unified coordinates, derive standalone truth from
  the actual route/RAW intent, convert with the physical optical inventory, and share pure transition
  policy between Engine and ViewModel. Add PMA110 Photo+DNG and crop-only Video TELE round trips plus
  both FRONT mode-switch directions.

### AGG16-02 — valid signing configuration can select a keystore outside the immutable release owner

- **Severity / confidence:** High / High.
- **Agreement:** code-reviewer, architect, critic, security-reviewer, verifier, test-engineer, and
  document-specialist.
- **Evidence:** `app/build.gradle.kts:386-409,521-530`;
  `tools/build_immutable_release.py:367-406,443-476`;
  `tools/tests/test_immutable_release.py:139-281`; `keystore.properties.example:14-22`;
  `docs/plans/2026-08-24-rpf-cycle15.md:25-34,92-106`; `README.md:129-145`.
- **Failure:** Gradle accepts Java `Properties.load` syntax (`=`, `:`, whitespace, escapes and
  continuation) and the `TELECAMPRO_STORE_FILE` fallback, including absolute paths. The wrapper
  recognizes only an equals-form `storeFile` from the copied properties bytes and otherwise lets the
  inherited environment choose an external mutable keystore. A release can therefore sign from
  bytes absent from `ReleaseSnapshotSeal` while reporting an immutable build.
- **Required fix:** resolve the effective signing file once with Java-compatible properties
  semantics, require one repository-relative no-follow regular keystore, copy/seal it into the
  private checkout, clear ambient store-file overrides for Gradle, and fail closed on unsupported or
  ambiguous forms. Cover alternate separators/escapes, environment precedence, absolute/symlink
  paths, permanent mutation, and A -> B -> A mutation without exposing secrets; align docs and add a
  source/docs contract for file-path versus secret-value environment inputs.

### AGG16-03 — review MIME, metadata, video-info, and thumbnail work can multiply blocked calls

- **Severity / confidence:** Medium / High.
- **Agreement:** perf-reviewer, tracer, and qa-adversary.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:433-460,
  472-546,648-679`; contrast `MediaReview.kt:182-307,866-1013`;
  `app/src/test/kotlin/me/hletrd/telecampro/ui/review/MediaReviewOwnershipTest.kt:32-114`.
- **Failure:** canceled Compose coroutines cannot interrupt synchronous ContentResolver,
  MediaMetadataRetriever, ExifInterface, stream-decode, or thumbnail calls already running on the
  shared `Dispatchers.IO`. Repeated review close/reopen or capture replacement can start more
  blocked calls, retain provider/native graphs, and starve unrelated process I/O.
- **Required fix:** coalesce URI kind/video-info/metadata behind explicit process-finite,
  identity-owned latest-wins execution, place gallery thumbnail acquisition behind a separate
  finite owner, dispose every stale retriever/stream/Bitmap, and test permanently blocked A plus
  replacements against exact active/backlog ceilings and stale-publication refusal.

### AGG16-04 — one wedged player data-source open head-of-line blocks all later video reviews

- **Severity / confidence:** Medium / High.
- **Agreement:** perf-reviewer and tracer.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLane.kt:49-89`;
  `MediaReview.kt:182-254,876-985`; `MediaReviewOwnershipTest.kt:32-105`.
- **Failure:** the process-global playback setup lane holds one mutex across synchronous
  `MediaPlayer.setDataSource`. Retiring the coroutine/surface prevents stale publication but cannot
  release a permanently wedged call, so every later healthy video waits forever until process death.
- **Required fix:** separate publication identity from bounded execution capacity: use a small
  process-wide pool with a latest-only finite pending slot, allow a healthy replacement to progress
  while one worker is abandoned, and publish a bounded terminal/restart result when capacity is
  exhausted. Test permanently blocked A, successful B, saturation, stale-result disposal, and exact
  Surface/MediaPlayer release ownership.

### AGG16-05 — a pending durable DISCARD row can prevent launch recovery reaching later pages

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:633-799,
  874-900,1207-1243,1277-1317,1375-1392`;
  `camera/LaunchMediaRecoveryCoordinator.kt:87-148`;
  `LaunchMediaRecoveryCoordinatorTest.kt:91-161`; `OrphanSweepTest.kt:49-89,143-150`.
- **Failure:** Images/Video pagination still classifies a pending row with a durable `DISCARD`
  journal entry as `DELETE`. Its generic page has no continue-after-exhaustion policy, so one
  permanently failing delete consumes the launch budget before the independent progress-capable
  DISCARD stage runs. Later media pages and later markers can remain private indefinitely.
- **Required fix:** make the dedicated DISCARD stage the sole terminal-deletion owner for exact
  durable markers while generic pages advance past them, or equivalently carry safe progress only
  when every failed operation is journal-owned. Add an integrated multi-page test with a permanently
  failing early DISCARD row and later media/marker progress.

### AGG16-06 — unexpected launch-recovery exceptions drop completion and suppress gallery restore

- **Severity / confidence:** Medium / High.
- **Agreement:** verifier, test-engineer, and document-specialist.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/
  LaunchMediaRecoveryCoordinator.kt:16-19,41-51`; `ui/CameraViewModel.kt:1065-1070`;
  `LaunchMediaRecoveryCoordinatorTest.kt:15-64`; `docs/ARCHITECTURE.md:245`.
- **Failure:** the coordinator clears every subscriber and invokes completions only on successful
  recovery. An escaped exception is silent, and the ViewModel never runs the non-destructive latest
  published-family query, leaving valid gallery media undiscovered for the process.
- **Required fix:** terminally deliver typed success/failure exactly once to every live subscriber,
  log failure, permit later requests, and run safe latest-family restore after either outcome. Test
  throwing recovery with multiple/canceled subscribers plus the ViewModel restore edge, then align
  Architecture wording.

### AGG16-07 — held-landscape Fn compaction infers identities from English localized text

- **Severity / confidence:** Medium / High.
- **Agreement:** designer and qa-adversary.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/FnQuickActions.kt:48-95`;
  `ui/CameraScreenPolicy.kt:482-524`; `ui/CameraScreen.kt:2375-2463`;
  `app/src/main/res/values-ko/strings.xml:329-344,371`;
  `FnOverlayPolicyTest.kt:157-206`; `BilingualPresentationComposeTest.kt:83-300`.
- **Failure:** production localizes a Fn value before an English-literal compactor matches `Auto`,
  WB, drive, stabilization, and audio-scene names. Korean values bypass compact aliases in the
  constrained rotated 2x4 tray and can ellipsize away automation or distinguishing state even while
  the accessibility description stays complete.
- **Required fix:** compact from typed value identity plus `autoDriven`, resolve explicit EN/KO
  compact resources at composition time, preserve full semantics, and add production-composition
  tests for Korean 90/270 layouts, affected controls, and maximum font scale.

### AGG16-08 — active source comments still claim sideways handset windows rotate

- **Severity / confidence:** Low / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreenPolicy.kt:39-59`;
  `ui/CameraScreen.kt:2233-2240`; `ui/controls/ManualDials.kt:339-350`;
  `MainActivity.kt:147-174`; `CLAUDE.md:376-406`; `docs/ARCHITECTURE.md:482-501`.
- **Failure:** three maintenance comments describe the superseded all-device rotation model even
  though handsets (`sw < 600`) are portrait-locked and only large-screen windows may follow the
  device. A maintainer could delete residual 90/270 handset adaptation as redundant.
- **Required fix:** qualify the comments with the handset/large-screen split and extend the existing
  negative documentation guard against unqualified active-source claims that handset windows turn.

## Deferred findings

None. All eight aggregate findings are scheduled for implementation. Existing owner-approved field
checks and durable deferrals in `docs/BACKLOG.md` remain unchanged and were not re-filed.

## Agent failures

None.
