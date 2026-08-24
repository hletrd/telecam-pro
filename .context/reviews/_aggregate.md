# Aggregate deep review — cycle 25

Date: 2026-08-24
Reviewed HEAD: `eca8df1`

## Current-cycle review provenance

The global agent pool exposed one child-reviewer slot because prior cycle agents still occupied the
remaining thread capacity. That reviewer performed one complete 418-path inventory and applied all
required specialist perspectives, writing separate `code-reviewer`, `architect`, `perf-reviewer`,
`tracer`, `security-reviewer`, `debugger`, `critic`, `verifier`, `test-engineer`,
`document-specialist`, `designer`, and repository-specific `qa-adversary` artifacts. No perspective
was omitted, and there were no agent failures.

Coverage included all 95 production Kotlin modules, 189 host-test files, four instrumented probes,
two debug hosts, 30 Python files, two shell files, manifests, resources, Gradle/release inputs, and
active documentation. Production source, tools, and configuration are byte-identical to cycle 24's
reviewed and gated `d04df18`; only cycle 24's review aggregate and completed plan changed. The final
sweep re-traced exact Camera2 teardown, still-family producer/publication/delete authority,
recording/GL/review ownership, obscured-touch rejection, legacy-media provenance, release sealing,
UI/accessibility, and documentation claims.

## Current-cycle deduplicated findings

No new confirmed, likely, or actionable manual-validation finding survived aggregation. All twelve
review perspectives reported a clean current HEAD. Existing device-only residuals, accepted product
decisions, and structural debt remain explicitly governed by `docs/BACKLOG.md` and
`docs/FIELD_CHECKS.md`; they were not relabeled as new findings or silently treated as passed.

## Current-cycle verification evidence

- `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug`: passed.
- 1,854 JVM/Robolectric/Compose tests: passed with zero failures/errors.
- 73 tool/release tests, 182 device-harness self-tests, and 111 documentation checks: passed.
- Python compilation, XML parsing, tracked-secret/network/model-seam scans, and
  `git diff --check`: passed.
- Device gates were blocked by directive (`DEPLOY_MODE=none`) and the absence of a current
  `ANDROID_SERIAL`; no historical device result was promoted to current evidence.

## Current-cycle accounting

- New review findings: **0**
- Deduplicated root causes: **0**
- Agent failures: **0**
- Deferred findings: **0**

## Archived cycle-24 aggregate

Date: 2026-08-24
Reviewed HEAD: `d04df18`

## Current-cycle review provenance

The global agent pool exposed one child-reviewer slot because earlier cycle agents still occupied
the remaining slots. The available reviewer performed the required full-repository inventory once
and applied every specialist perspective, writing separate `code-reviewer`, `architect`,
`perf-reviewer`, `tracer`, `security-reviewer`, `debugger`, `critic`, `verifier`, `test-engineer`,
`document-specialist`, `designer`, and repository-specific `qa-adversary` artifacts.

Coverage included all 417 tracked paths: all 95 production Kotlin modules, 189 host tests, four
instrumented probes, two debug hosts, 30 Python files, two shell files, manifests, resources,
Gradle/release inputs, and active documentation. The final sweep re-traced the cycle-23 Camera2
teardown, process-wide still-family ownership, independent-family publication, obscured-input
rejection, sealed release provenance, and legacy-media disclosure changes. There were no agent
failures.

## Current-cycle deduplicated findings

No new confirmed, likely, or actionable manual-validation finding survived aggregation. All twelve
review perspectives reported a clean current HEAD. Existing device-only residuals and accepted
structural debt remain explicitly governed by `docs/BACKLOG.md` and `docs/FIELD_CHECKS.md`; they
were not relabeled as new findings or silently treated as passed.

## Current-cycle verification evidence

- `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug`: passed.
- 1,854 JVM/Robolectric/Compose tests: passed with zero failures/errors.
- 73 tool/release tests, 182 device-harness self-tests, and 111 documentation checks: passed.
- Python compilation, XML parsing, tracked-secret/network/model-seam scans, and
  `git diff --check`: passed.
- Device gates were blocked by directive (`DEPLOY_MODE=none`) and the absence of a current
  `ANDROID_SERIAL`; no historical device result was promoted to current evidence.

## Current-cycle accounting

- New review findings: **0**
- Deduplicated root causes: **0**
- Agent failures: **0**
- Deferred findings: **0**

## Archived cycle-23 aggregate

Date: 2026-08-24
Reviewed HEAD: `b5a433169b3fbdfdd52a8012937dd7a18efc12ee`

## Review provenance

Three fresh concurrent review agents covered every required specialist perspective plus the
repository's registered QA adversary. The environment exposed three child-agent slots, so closely
related roles were grouped without dropping a perspective:

- `code-reviewer.md`, `architect.md`, `critic.md`, and `verifier.md`
- `perf-reviewer.md`, `debugger.md`, `designer.md`, and `qa-adversary.md`
- `security-reviewer.md`, `tracer.md`, `test-engineer.md`, and `document-specialist.md`

The reviewers inventoried all 95 production Kotlin files, 187 host-test files, four instrumented
tests, two debug hosts, resources/manifests/build inputs, all Python/shell tools and device harnesses,
and active documentation. Historical reports and completed plans were leads only; every candidate
was revalidated against current HEAD. Debug assembly and 1,831 JVM/Robolectric/Compose tests passed
on the authoritative retry, as did 111 documentation checks and `git diff --check`. Device gates
were blocked by directive (`DEPLOY_MODE=none`, no current `ANDROID_SERIAL`). There were no agent
failures.

## Deduplicated findings

### AGG23-01 — Camera2 strict release is published before the exact device reports `onClosed`

- **Severity / confidence:** High / High
- **Classification:** Confirmed correctness and native-resource ownership defect
- **Cross-agent agreement:** code reviewer, architect, critic, verifier; test/document review confirms
  the missing platform-boundary coverage
- **Evidence:** `CameraController.close()` calls asynchronous `session?.close()` and
  `device?.close()` at `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:2174-2175`,
  then immediately calls `closeTerminal.strictlyReleased()` at `:2184`. No device/session callback
  at `:298-358`, `:692-751`, or `:807-825` participates in the terminal. Engine replacement doors
  consume that result at `CameraEngine.kt:1841-1849` and `:3638-3655`.
- **Failure:** `CameraDevice.close()` can return while the HAL graph is still shutting down. The
  replacement then opens before the exact old device's `StateCallback.onClosed`, reintroducing
  `CAMERA_IN_USE`, broken-pipe, or dead-preview overlap despite the cycle-22 quarantine design.
- **Required fix:** bind close terminality to exact callback-device identity, arm ownership before
  requesting close, publish strict release only from `onClosed(expectedDevice)`, quarantine on the
  bounded timeout, and test delayed/wrong/late callback schedules through the production orchestration.

### AGG23-02 — error/disconnect before `onOpened` leaks the callback-supplied CameraDevice

- **Severity / confidence:** High / High
- **Classification:** Confirmed lifecycle/resource leak
- **Cross-agent agreement:** code reviewer, architect, critic, verifier
- **Evidence:** the controller stores `device = camera` only in `onOpened` at
  `CameraController.kt:299-303`. `onDisconnected` and `onError` at `:324-356` may occur instead of
  `onOpened`, but call only the controller's no-arg close. Teardown reaches `device?.close()` at
  `:2175`, which is null in this documented callback shape; the callback parameter is never closed.
- **Failure:** bounded recovery can retry while retaining each failed-open native handle, converting
  a transient policy/provider/camera-in-use event into persistent camera-budget exhaustion.
- **Required fix:** install or retire every exact callback-supplied device before notifying recovery,
  with exactly-once identity ownership across normal, error-before-open, late-after-close, and racing
  callback paths. Add callback-seam regressions for both failure callbacks.

### AGG23-03 — a camera-handler losing close caller can await teardown queued behind itself

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed race-only availability defect
- **Cross-agent agreement:** code reviewer, architect, critic, verifier
- **Evidence:** only the CAS winner at `CameraController.kt:2149` arranges teardown, but every loser
  awaits the terminal at `:2218`. Camera callbacks run on the same handler through `:68-82` and call
  close at `:329-330`/`:356`; an off-thread winner can post teardown behind that callback at
  `:2201-2202`.
- **Failure:** the callback thread waits for work queued behind itself, hits 1.5 seconds, and falsely
  quarantines the process even though teardown would run immediately after the callback returned.
- **Required fix:** separate close initiation from off-handler terminal awaiting, or return a terminal
  handle/future. A camera-handler observer must never block on its own queue. Add a deterministic
  posted-teardown/losing-callback regression.

### AGG23-04 — a current-process family tombstone can retire before a future old-Engine sibling exists

- **Severity / confidence:** High / High
- **Classification:** Confirmed correctness/privacy and delete-integrity defect
- **Source:** debugger review
- **Evidence:** launch recovery preserves markers owned by this process at
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:914-981`, but explicit
  retirement at `:433-472` checks only provider rows and publication claims already registered.
  Restored families lack live producer identity (`CaptureOutputTracker.kt:74-105,229-236`), so a
  replacement Engine treats them as producer-terminal at `CameraEngine.kt:4178-4195`. Sequential
  sibling writers do not create/register the later row until `StillCapturePipeline.kt:201-349,384-402`.
- **Failure:** Engine A publishes HEIF and is replaced before creating JPEG. Engine B restores and
  deletes HEIF, sees no later row/claim, retires the current-process marker, and announces Delete.
  A then creates and publishes JPEG, resurrecting the family in system Gallery.
- **Required fix:** make producer terminality process-wide per family with a lease registered before
  Camera2 receives the shot and released only after all processed/DNG lanes are terminal. Retirement
  must require no producer lease and no publication claim. Add deterministic two-Engine future-sibling
  tests.

### AGG23-05 — unrelated family publication waits behind the global preferences commit lock

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed cross-family liveness/performance regression
- **Source:** performance review
- **Evidence:** `markFamilyDeletedResult` holds `familyJournalMetadataLock` while reading preferences
  and synchronously committing at `MediaStoreWriter.kt:395-415`. Cycle 22 made publication acquire
  that same global lock only to read `contains(key)` at `:524-549`, even though exact-family monitors
  already serialize same-key operations and the fast read at `:506-509` is thread-safe.
- **Failure:** a slow family-A preference fsync delays family-B Gallery visibility, saved callback,
  review update, and retained-snapshot release across otherwise independent Engine tails.
- **Required fix:** perform the marker read under only the exact-family authority; retain the global
  metadata lock for capacity-changing mark/retire transactions. Add a blocked-commit/unrelated-family
  publication test.

### AGG23-06 — partial-overlay tapjacking reaches capture and destructive Compose actions

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed Android interaction/security gap
- **Cross-agent agreement:** security reviewer, tracer, test engineer, document specialist
- **Evidence:** `MainActivity.kt:198-200` configures only
  `window.decorView.filterTouchesWhenObscured = true`. No Activity dispatch checks
  `MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED` or hides overlay windows. Destructive review actions
  flow through `MediaReview.kt:1374-1420` to `CameraViewModel.kt:3310-3395`; the exported launcher is
  declared at `AndroidManifest.xml:69-105`.
- **Failure:** a partial/spatial overlay produces a partially obscured event that the existing full-
  obscuration filter accepts, allowing redirected Shutter/REC/settings/permission/delete taps.
- **Required fix:** reject both full and partial obscuration at the Activity dispatch boundary (or
  use the product-approved overlay-hiding policy), and add real Activity/instrumented MotionEvent
  coverage for ordinary and both flagged cases.

### AGG23-07 — one terminal `git status` process is not an atomic live-worktree authority

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed release-integrity and operator-contract defect; cycle-22 root cause
  persists under a different schedule
- **Cross-agent agreement:** security reviewer, tracer, test engineer, document specialist
- **Evidence:** `tools/check_release_artifact.py:416-441,789-810` calls one porcelain-v2 status
  process a coordinated terminal snapshot. Git combines record types in one stream but does not
  freeze worktree files while walking them. Tests at `tools/tests/test_release_artifact.py:620-882`
  inject finished fake streams and cannot mutate an already-scanned path during the real process.
- **Failure:** a concurrent writer changes a tracked or protected ignored path after Git has visited
  it but before status exits; the checker can still return `upload-ready` for a dirty return-time tree.
- **Required fix:** define readiness against the wrapper's sealed immutable source owner or hold a
  writer lock honored by every supported mutation path through verdict consumption. Do not claim
  live-worktree atomicity from repeated scans. Add a real-process race regression or narrow the
  documented contract to the immutable authority.

### AGG23-08 — owner-null restored media has filename syntax, not verifiable app provenance

- **Severity / confidence:** Low / High
- **Classification:** Confirmed local trust-boundary and privacy/Play-language overclaim
- **Cross-agent agreement:** security reviewer, tracer, test engineer, document specialist
- **Evidence:** owner-null rows are admitted by directory, public filename grammar, collection, and
  MIME at `MediaStoreWriter.kt:191-292` and `LatestCaptureReducer.kt:83-208`. `PRIVACY.md:15-19` and
  `docs/play-data-safety.md:46-83` describe them absolutely as captures/files this app itself saved.
- **Failure:** an imported or other-app owner-null lookalike can become latest review media and enter
  the platform decoder/player. File-only deletion prevents a false family delete, but authorship,
  local-media disclosure scope, and parser trust are still overstated.
- **Required fix:** implement verifiable future provenance, require system-picker selection, or label
  the legacy heuristic honestly in UI/public copy. Add provider/device coverage for adversarial
  owner-null lookalikes according to the chosen contract.

### AGG23-09 — cycle-22 completion evidence claims CameraController/Engine tests that do not exist

- **Severity / confidence:** Low / High
- **Classification:** Confirmed test-coverage and documentation mismatch
- **Cross-agent agreement:** test engineer and document specialist; code/architecture findings show
  the behavior the missing tests failed to exercise
- **Evidence:** `docs/plans/2026-08-24-rpf-cycle22.md:27-43,128-132` marks handler-latch and
  reconfiguration coverage complete. `CameraTeardownTerminalTest.kt:13-111` exercises the extracted
  terminal and pure `cameraReplacementMayAcquire` predicate only; it does not call production
  `CameraController.close()`, drive HandlerThread posting/inline/failure/timeout branches, or observe
  Engine replacement acquisition.
- **Failure:** green pure tests are presented as integration evidence while the false onClosed proof,
  failed-open handle leak, and self-wait survive in production orchestration.
- **Required fix:** add the claimed production-orchestration seams/tests while implementing
  AGG23-01 through AGG23-03, and correct cycle-22 evidence wording to distinguish state-machine unit
  coverage from Camera2/Engine integration coverage.

## Accounting

- New review findings: **9**
- Deduplicated root causes: **9**
- Agent failures: **0**
- Deferred findings: **0** (all findings are scheduled for implementation in cycle 23)
