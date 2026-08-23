# Review-plan-fix cycle 15 — aggregate review

Date: 2026-08-24
Reviewed HEAD: `2e66146`
Mode: host-only; deployment and device interaction disabled

## Review provenance

- `code-reviewer-architect-critic-cycle15.md`
- `perf-reviewer-tracer-cycle15.md`
- `security-reviewer-debugger-cycle15.md`
- `verifier-test-engineer-document-specialist-cycle15.md`
- `designer-qa-adversary-cycle15.md`

The environment exposed generic subagents rather than named specialist types, so adjacent roles were
grouped across five concurrent workers. Every required role plus the repository's registered
`qa-adversary` was covered. The reports produced 12 raw findings, deduplicated here into nine
current-HEAD findings. Three findings had independent cross-agent agreement. Focused Python tool,
coverage, device-harness, and documentation suites passed during review. The recorded Android JUnit
XML contained 1,747 passing tests; concurrent Gradle processes interfered with shared output cleanup,
so the authoritative gates are rerun serially during implementation.

## Merged findings

### AGG15-01 — release-local compiler and signing inputs are outside the immutable owner

- **Severity / confidence:** High / High.
- **Agreement:** code/architecture/critic and verifier/test/document specialist.
- **Evidence:** `tools/build_immutable_release.py:23-35,217-315,365-398`;
  `tools/tests/test_immutable_release.py:20-137`.
- **Failure:** `local.properties`, `keystore.properties`, and a repository-relative keystore are
  copied into the private checkout but omitted from the release seal. A permanent or transient
  mutation can change SDK or signing inputs while tracked-source verification remains green.
- **Required fix:** descriptor-read and validate the local inputs, resolve the keystore from frozen
  properties, include every copied input and replacement-relevant ancestor in the release owner,
  verify it through export, and add permanent plus A -> B -> A mutation tests without exposing
  secrets.

### AGG15-02 — immutable debug and release wrappers reopen mutable generated outputs

- **Severity / confidence:** High / High.
- **Agreement:** code/architecture/critic and security/debugger; the latter independently confirmed
  the release path.
- **Evidence:** `tools/build_immutable_debug.py:349-367`;
  `tools/build_immutable_release.py:365-399`; `tools/tests/test_immutable_debug.py:54-147`;
  `tools/tests/test_immutable_release.py:35-159`.
- **Failure:** after source proof, both wrappers rediscover generated artifacts by pathname and copy
  writable output trees without one no-follow, content-verified owner. Replacement, A -> B -> A,
  torn-copy, or cross-member races can publish bytes other than the finalized build result.
- **Required fix:** own the explicit expected output set with retained regular-file identities and
  digests, export from the owned bytes into exclusively created staging files, verify the complete
  owner before atomic publication, reject symlinks/special/unexpected members, and adversarially
  test both wrappers and the release APK/AAB set.

### AGG15-03 — video review opens its content URI synchronously on the main thread

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:782-838`;
  `app/src/test/kotlin/me/hletrd/telecampro/ui/review/MediaReviewSizingTest.kt`.
- **Failure:** `MediaPlayer.setDataSource(Context, Uri)` runs in a `TextureView` UI callback and
  synchronously opens the MediaProvider descriptor before `prepareAsync()`. A slow or wedged
  provider freezes Back/lifecycle/rendering and can cause an ANR.
- **Required fix:** move descriptor/player acquisition into an identity-owned latest-wins setup
  lane, publish and attach only the current player on main, release stale owners, and add a forced
  blocked-provider/responsive-disposal test.

### AGG15-04 — published review bitmaps can be recycled before Compose stops drawing them

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:177-219,
  539-558,888-896`; `app/src/test/kotlin/me/hletrd/telecampro/ui/review/
  LatestHeavyWorkLaneTest.kt:19-161`.
- **Failure:** state replacement schedules recomposition and immediately recycles the old published
  Bitmap, while the current render node may still draw its `ImageBitmap`, risking corrupt output or
  a recycled-bitmap exception.
- **Required fix:** retain explicit disposal for never-published stale work but transfer published
  bitmaps to composition-safe retirement (or GC ownership), with a real Bitmap/Compose replacement
  and removal test.

### AGG15-05 — the launch discard journal is unbounded and replayed for every media page

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:624-650,
  748-758,833-850`; `app/src/main/kotlin/me/hletrd/telecampro/camera/
  LaunchMediaRecoveryCoordinator.kt:85-117`; `OrphanSweepTest.kt:35-56`;
  `LaunchMediaRecoveryCoordinatorTest.kt:65-105`.
- **Failure:** every bounded 64-row Images/Video batch first loads and retries every durable
  `PENDING_DISCARD` marker. A large unresolved journal makes each page unbounded and repeatedly
  performs the same provider calls.
- **Required fix:** give discard recovery its own stable bounded cursor/batch and independent
  progress, never restart the complete journal per media page, and test multi-page failed entries
  for per-attempt bounds plus eventual progress.

### AGG15-06 — teardown-rejected deleted-still cleanup can multiply blocked workers

- **Severity / confidence:** Low-Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:128-132,
  4160-4177,6328-6334`; `RetainedStillDeletionOwner.kt:220-260`;
  `MediaStoreWriter.kt:328-349,498-523`.
- **Failure:** each Engine owns a fallback executor whose accepted ContentResolver call survives
  `shutdown()`. Replacing Engines through the narrow delete/late-output/teardown race can accumulate
  blocked daemon threads and captured Engine graphs.
- **Required fix:** route fallback discard work through one process-lifetime finite dispatcher;
  overflow may leave the durable tombstoned row for launch recovery. Test blocked provider work
  across repeated Engine replacement and stale completion.

### AGG15-07 — active verification instructions still install a forbidden mutable APK

- **Severity / confidence:** Medium / High.
- **Agreement:** code/architecture/critic and verifier/test/document specialist.
- **Evidence:** `CLAUDE.md:75-100`; `docs/FIELD_CHECKS.md:1-21,185-190`;
  `device-tests/run.py:630-638,1512-1517,1786-1800`; corrected authority at
  `docs/ARCHITECTURE.md:1172-1227` and `device-tests/README.md:15-24,72-79`;
  `tools/check_docs.py:607-641`.
- **Failure:** the highest-precedence build loop, field-check runbook, default harness path, and
  missing-install diagnostic direct operators to a mutable development APK that the same evidence
  contract rejects, producing unusable or misattributed device evidence.
- **Required fix:** capture and propagate the exact immutable-debug wrapper output everywhere,
  require or derive an immutable harness APK, make diagnostics quote that path, label any mutable
  install developer-only, and extend docs guards across every active verification authority.

### AGG15-08 — active UI authorities use superseded Shoot-tab labels

- **Severity / confidence:** Low / High.
- **Evidence:** `docs/ARCHITECTURE.md:1091-1108`; `docs/UX_POLICY.md:15-20`;
  `app/src/main/res/values/strings.xml:13-20,253-261`; `tools/check_docs.py:101-137,590-641`.
- **Failure:** Architecture and UX policy say `Shooting` / `JPEG quality` while current resources
  and the stale-screenshot contract say `Shoot` / `Still Quality`, inviting old copy to be accepted
  or reintroduced.
- **Required fix:** align the active prose with resource truth and add a resource-backed exact-label
  documentation check.

### AGG15-09 — CameraScreen comments still describe the deleted wide operator rail

- **Severity / confidence:** Low / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:560-566,642-655,
  975-992,1173-1185,1488-1505`; `docs/UX_POLICY.md:12-19`;
  `docs/BACKLOG.md:1201-1207,1375-1386`.
- **Failure:** comments beside the live full-width layout still describe two reserved operator
  columns, a menu column, a capture rail, and a constrained TopBar, contradicting code and the
  owner-approved one-layout contract.
- **Required fix:** replace the stale guidance with the one-layout/full-width contract and add a
  checked negative guard against reintroducing reserved-rail implementation guidance.

## Deferred findings

None. All nine findings are scheduled for implementation. Existing owner-approved device checks,
wide-finder limits, and structural debt in `docs/BACKLOG.md` remain unchanged and were not re-filed.

## Agent failures

None. Android task-finalization interference during concurrent read-only review is recorded above
and is not an agent or repository failure; all configured gates will run serially in PROMPT 3.
