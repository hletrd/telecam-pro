# Review-plan-fix cycle 17 — aggregate review

Date: 2026-08-24
Reviewed HEAD: `2071ec8`
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

The environment exposed five worker slots beneath this cycle, so adjacent specialist roles were
grouped across five concurrent workers while each required role wrote its own provenance report.
The repository's registered `qa-adversary` was included; no other reviewer-style agent was present.
The reports produced 23 raw finding entries, deduplicated here into seven current-HEAD findings.
Six aggregate findings had independent cross-agent agreement. Static review validation passed
`:app:assembleDebug :app:testDebugUnitTest` and all 107 documentation checks. Device evidence was
not attempted because no current `ANDROID_SERIAL` was supplied.

## Merged findings

### AGG17-01 — exhausted review-worker capacity leaves the latest request pending forever

- **Severity / confidence:** Medium / High.
- **Agreement:** code-reviewer, architect, critic, perf-reviewer, tracer, debugger, verifier,
  test-engineer, document-specialist, designer, and qa-adversary.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLane.kt:109-121,
  175-201,227-239`; `ui/review/MediaReview.kt:185-205,491-525,561-599,691-725,982-1042`;
  `LatestHeavyWorkLaneTest.kt:173-240`; `docs/plans/2026-08-24-rpf-cycle16.md:52-64`.
- **Failure:** two permanently blocked calls consume one progressive lane, or four mixed calls
  consume its shared process pool. The conflated pending slot bounds growth but has no deadline or
  typed exhaustion result, so every later healthy review remains Loading until process death.
- **Required fix:** make process/lane admission progress-aware, complete latest pending work with a
  typed restart-required result after bounded saturation, preserve stale-result disposal, surface
  bilingual terminal UI, and test same-lane plus cross-lane exhaustion without releasing blockers.

### AGG17-02 — one wedged still decode blocks every later full-screen still review

- **Severity / confidence:** Medium / High.
- **Agreement:** perf-reviewer and tracer.
- **Evidence:** `MediaReview.kt:301-309,385-431,532-545,704-725`;
  `LatestHeavyWorkLane.kt:55-99`; `LatestHeavyWorkLaneTest.kt:270-355`.
- **Failure:** full-screen bitmap acquisition remains on a one-mutex serial lane. A synchronous
  provider or decoder wedge holds that mutex permanently; Back invalidates publication but healthy
  replacement stills can never start.
- **Required fix:** move still acquisition behind finite progress-capable execution while keeping a
  strict decoded-bitmap memory bound. Test permanent A, progressing B, immediate Back, and exact
  stale-bitmap disposal/active-capacity limits.

### AGG17-03 — process-lifetime review lanes retain destroyed Activity contexts

- **Severity / confidence:** Medium / High.
- **Agreement:** code-reviewer and architect.
- **Evidence:** `MediaReview.kt:207-221,290-304,443-504,580-593,690-724,979-989`;
  `LatestHeavyWorkLane.kt:64-74,138-152,180-200`.
- **Failure:** requests stored on process-owned blocked workers carry `LocalContext`/AndroidView
  Activity contexts. Cancellation cannot remove a request already on a synchronous worker stack, so
  each poisoned slot can retain a destroyed Activity/window/composition graph until process death.
- **Required fix:** canonicalize every process-lane request to `applicationContext` or a process-owned
  resolver at construction, and test the lifetime invariant with a tagged context.

### AGG17-04 — forgeable Gradle properties bypass immutable release ownership

- **Severity / confidence:** High / High.
- **Agreement:** verifier and test-engineer.
- **Evidence:** `app/build.gradle.kts:189-215,421-431,650-663`;
  `tools/build_immutable_release.py:572-582`; `tools/tests/test_release_source_gate.py:109-126,
  217-224`; `tools/tests/test_immutable_release.py:67-85`; `docs/ARCHITECTURE.md:1209-1217`.
- **Failure:** a direct Gradle caller can reproduce the exact commit/tree/store-file `-P` values and
  pass the release gate from the mutable live checkout. Wrapper sealing, immutable private input
  ownership, post-build verification, and frozen export are bypassed while provenance still names
  the pre-check identities.
- **Required fix:** add wrapper-created, single-invocation authority binding the private checkout
  and sealed signing input; treat commit/tree as provenance only. Add a real direct-release negative
  test and mutation-after-gate coverage, then align the architecture claim.

### AGG17-05 — launch-recovery cancellation loses authority after callback snapshot

- **Severity / confidence:** Medium / High.
- **Evidence:** `camera/LaunchMediaRecoveryCoordinator.kt:25-49,58-69`;
  `CameraEngine.kt:6103-6142,6239-6244,6338-6344`; `CameraViewModel.kt:1067-1096,3586-3607`;
  `LaunchMediaRecoveryCoordinatorTest.kt:45-119`.
- **Failure:** terminal delivery snapshots bare callbacks and clears the subscriber map before
  invocation. A later `cancel()` removes nothing and cannot invalidate the copied callback, so a
  detached Engine/ViewModel can still start provider work or retain its graph.
- **Required fix:** snapshot per-subscription first-wins claim tokens rather than bare callbacks;
  cancellation must retire the token after map removal and delivery must claim immediately before
  invocation. Test cancellation after snapshot and between callbacks.

### AGG17-06 — active architecture and hot-path zoom guidance describe superseded owners

- **Severity / confidence:** Low / High.
- **Agreement:** code-reviewer, architect, and document-specialist.
- **Evidence:** `docs/ARCHITECTURE.md:131-133,253`; `camera/CameraEngine.kt:3206-3209`;
  `ui/ZoomMath.kt:314-331`; `ui/CameraViewModel.kt:2039-2049`.
- **Failure:** Architecture still says review uses `Dispatchers.IO` with one active call, and three
  zoom-adjacent comments summarize Photo as unified and Video as local. Both descriptions conflict
  with the current progressive process pool and route/RAW-owned zoom law, inviting recurrence of
  the cycle-16 Photo+DNG framing bug.
- **Required fix:** document the serial/progressive lane graph and its terminal exhaustion behavior;
  rewrite zoom comments around logical-versus-standalone route truth and add source/docs guards.

### AGG17-07 — active tablet asset guidance revives the deleted operator rail

- **Severity / confidence:** Low-Medium / High.
- **Agreement:** critic, document-specialist, and designer.
- **Evidence:** `docs/play-store-listing.md:332-343`; current authority at `CLAUDE.md:417-425`,
  `docs/ARCHITECTURE.md:477-487`, and the same listing's current v1.0.2 notes.
- **Failure:** release operators are told that sw600dp+ controls occupy a separate operator rail even
  though v1.0.2 deleted it and only the responsive side settings sheet remains.
- **Required fix:** describe the one-layout camera-control rule and optional side settings sheet,
  and include the listing in the retired-rail documentation guard.

## Deferred findings

None. All seven aggregate findings are scheduled for implementation. Existing owner-approved field
checks and durable backlog deferrals remain unchanged and were not re-filed.

## Agent failures

None.
