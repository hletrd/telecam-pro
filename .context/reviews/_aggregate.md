## Review-plan-fix cycle 28 aggregate

Date: 2026-08-24
Reviewed HEAD: `7e44ab07ddc243c06d916406db699f91bfe0893f`

### Review provenance and coverage

Five fresh concurrent agents covered every required specialist perspective plus the repository's
QA-adversary role:

- code-reviewer, architect, and test-engineer;
- perf-reviewer and tracer;
- security-reviewer and debugger;
- critic, designer, and QA adversary; and
- verifier and document-specialist.

Each perspective inventoried and swept the complete 422-path committed repository: all 95
production Kotlin files, 190 host-test files, four instrumented probes, two debug hosts, 30 Python
files, two shell tools, manifests/resources, Gradle and immutable-release inputs, committed
documentation, and packaged assets. Review ran in a clean clone of `origin/main`; the shared main
worktree and its unrelated incorrect `CameraEngine.kt` delta remained untouched. There were no
agent failures.

### Deduplicated findings

#### AGG28-01 — family claim/producer registration has a retirement linearization gap

- **Severity / confidence:** High / High
- **Classification:** Confirmed correctness and deleted-media durability defect
- **Cross-agent agreement:** code reviewer, architect, critic, verifier, document specialist,
  test engineer, and QA adversary
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:101-108`
  increments the authority user count under `familyAuthorityRegistryLock`, releases it, and only
  afterward increments `publicationClaims` under `claimMonitor`; producer admission has the same
  split at `:133-138`. Retirement at `:505-543` sees only the later claim counters and can remove
  the durable marker while a registrant is paused in that gap. Current tests at
  `app/src/test/kotlin/me/hletrd/telecampro/storage/DeletedFamilyJournalTest.kt:479-550` begin only
  after the vulnerable increment.
- **Failure:** retirement can erase a family's deletion veto before an admitted publication or
  producer becomes visible, after which a late sibling may take the live publication path. The
  current still pipeline's outer producer lease masks the publication form, but the documented
  authority abstraction and producer admission are not self-contained or linearizable.
- **Required fix:** make registry membership and the corresponding claim/lease one atomic admission
  transition without holding the global registry across preference/provider I/O; add deterministic
  publication and producer regressions for the exact admission boundary.

#### AGG28-02 — stale partial-delete delivery promises an in-app retry for a superseded capture

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed asynchronous presentation/correctness defect
- **Cross-agent agreement:** code reviewer, architect, and test engineer
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3365-3391`
  rechecks survivor ownership before updating state, but chooses retry copy from the earlier
  worker-local `restored != null`. `CaptureOutputTracker.kt:378-388` can return a survivor before a
  newer capture becomes owner, and no test covers supersession between restoration and main-thread
  delivery.
- **Failure:** capture B correctly remains the last-capture owner, yet the UI tells the user to open
  the app's capture and retry deletion for capture A; the app now opens B, while A is reachable only
  in the system Gallery.
- **Required fix:** derive survivor publication and retry destination from one delivery-time
  ownership decision, choosing the Gallery message when the survivor was superseded; add both race
  and ordinary-path tests.

#### AGG28-03 — committed-export docs gate skips a public screenshot/runbook invariant

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed release-gate fail-open defect
- **Cross-agent agreement:** code reviewer, architect, and test engineer
- **Evidence:** `tools/check_docs.py:129-190` nests checks for the tracked
  `docs/play-console-submit.md` under availability of private `docs/play-store-listing.md`.
  `tools/tests/test_tool_contracts.py:146-201` proves only a successful exported-tree run, not that
  mutations of public screenshot readiness are rejected.
- **Failure:** a fresh clone can pass when the committed console runbook contradicts
  `docs/assets/play/screenshots/asset-validity.json`, including telling an operator to upload stale
  assets, solely because the unrelated private listing is absent.
- **Required fix:** always validate the committed console runbook against the manifest and make
  only listing-specific checks optional; add exported-tree negative fixtures for both readiness
  directions.

#### AGG28-04 — non-unity recording gain computes and allocates discarded RMS levels per buffer

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed hot-path inefficiency; device-visible magnitude needs profiling
- **Cross-agent agreement:** performance reviewer and tracer
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:773-880` calls
  `applyGainAndLevel` on every PCM buffer whenever gain differs from `1f`, although level delivery
  is admitted only every 100 ms. The helper at `:2029-2072` creates buffer views, a `DoubleArray`,
  and a `FloatArray` and accumulates RMS even when the caller discards the result.
- **Failure:** a long 4K take at non-unity gain incurs continuous avoidable CPU/allocation pressure
  on the audio encoder lane, increasing GC/contention risk without changing encoded samples or the
  10 Hz meter.
- **Required fix:** separate allocation-free in-place gain from optional level measurement (or make
  measurement explicitly conditional), preserve clamp/partial-frame/channel semantics, and test
  measured and unmeasured paths.

#### AGG28-05 — published privacy page contradicts owner-null legacy restore behavior

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed public privacy-copy inconsistency
- **Source:** document specialist
- **Evidence:** `privacy-policy/index.html:201-207` accurately discloses owner-null TeleCam-format
  candidates, matching `PRIVACY.md:19`, `docs/play-data-safety.md:21-24`, and
  `storage/LatestCaptureReducer.kt:58-65,184-205`; the same page at `:243-245` instead says the app
  looks only for captures it saved itself.
- **Failure:** users and Play reviewers receive mutually exclusive descriptions of the exact media
  library scan the app performs.
- **Required fix:** replace the absolute sentence with the shared current-package plus owner-null
  unverified/file-only wording and add a documentation parity assertion for this obsolete claim.

#### AGG28-06 — Privacy Policy and permission-recovery navigation failures are silent

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed missing error-state UX; triggering device policy needs manual validation
- **Source:** designer
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:1563-1573,1834-1838`
  and `MainActivity.kt:751-759,880-894` discard `ACTION_VIEW` and app-settings launch failures.
  There is no localized failure copy, in-app privacy fallback, or regression for an absent/blocked
  handler.
- **Failure:** on a managed/kiosk device, the only policy link or primary permission-recovery CTA
  can appear tappable yet do nothing, trapping touch, keyboard, and switch-access users without an
  explanation.
- **Required fix:** centralize external-intent outcomes, show localized assertive feedback while
  retaining focus, provide an in-app privacy-policy fallback, and test unresolved and
  `SecurityException` paths.

### Prompt-1 verification evidence

- `ANDROID_HOME=/Users/hletrd/Library/Android/sdk python3 tools/verify_host.py`: passed, including
  debug assembly, lint, 1,855 JVM/Robolectric/Compose tests, 99.82% Partition-A coverage, tool and
  device-harness self-tests, public-clone documentation checks, Python compilation, and diff checks.
- No physical-device check was run and no historical device result was promoted to current evidence.

### Current-cycle accounting

- Raw specialist findings: **17**
- Deduplicated root causes: **6**
- Agent failures: **0**
- Deferred findings: **0**

## Archived cycle-27 aggregate

# Aggregate deep review — cycle 27

Date: 2026-08-24
Reviewed HEAD: `5486239`

## Current-cycle review provenance

Six concurrent review lanes covered every required perspective plus the repository's registered
QA adversary. Closely related roles shared agents but wrote separate provenance files:

- code reviewer, architect, critic, and feature-development reviewer
- performance reviewer and causal tracer
- security reviewer and debugger
- verifier and test engineer
- documentation specialist
- native Compose designer and QA adversary

The reviewers inventoried all 420 tracked paths: 95 production Kotlin files, 189 host-test files,
four instrumented probes, two debug hosts, 30 Python files, two shell files, manifests, resources,
Gradle/release inputs, and active documentation. Every candidate was checked against committed HEAD.
The unrelated dirty `CameraEngine.kt` line was excluded with `git show HEAD:<path>`. There were no
agent failures. Final sweeps covered camera/media ownership, independent-family liveness, release
reproducibility, provenance, security/privacy, responsive accessibility, documentation, and tests.

## Current-cycle deduplicated findings

### AGG27-01 — the authoritative host gate depends on ignored, uncommitted documents

- **Severity / confidence:** High / High
- **Classification:** Confirmed release/test reproducibility defect
- **Cross-agent agreement:** verifier and test engineer
- **Evidence:** `.gitignore:63-69` ignores top-level `docs/*.md`, while HEAD omits
  `docs/play-store-listing.md`, `docs/BACKLOG.md`, and `docs/TESTING.md` even though
  `tools/check_docs.py:38-59,314,377-384,1028-1036` opens them unconditionally and
  `tools/verify_host.py:68-72` always invokes that checker. A `git archive HEAD` reproduction fails
  immediately with `FileNotFoundError`.
- **Failure:** a fresh clone cannot run the documented authoritative host gate; local success relies
  on private ignored files that may differ from the reviewed commit.
- **Required fix:** make private-document checks explicitly optional when absent while keeping
  committed/public checks authoritative, and add an exported-HEAD contract test proving the gate's
  tracked prerequisites are self-contained.

### AGG27-02 — family retirement holds the global registry across synchronous marker removal

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed cross-family liveness regression
- **Cross-agent agreement:** verifier and test engineer
- **Evidence:** publication and producer admission acquire `familyAuthorityRegistryLock` at
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:97-101,122-129`.
  Retirement retains that lock at `:516-530` while `SharedPreferencesFamilyMarkerStore.remove`
  performs synchronous `Editor.commit()` at `:1279`.
- **Failure:** a delayed family-A fsync blocks unrelated family-B publication, saved callback,
  review update, retained-snapshot release, and new producer registration.
- **Required fix:** serialize final claims recheck and marker removal with a same-family authority
  lock; keep the global registry lock limited to short map/user-count bookkeeping. Add deterministic
  blocked-remove/unrelated-family and same-family ordering tests.

### AGG27-03 — file-only sibling promotion retains the deleted output's provenance

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed provenance/presentation correctness defect
- **Cross-agent agreement:** verifier and test engineer
- **Evidence:** `storage/LatestCaptureReducer.kt:319-345` assigns provenance per output, but
  `ui/CaptureOutputTracker.kt:25-43,75-108,259-280` drops it. After FILE_ONLY deletion promotes a
  sibling, `CameraViewModel.kt:3378-3388` updates URI/scope without `lastMediaProvenance`.
- **Failure:** a mixed owned/owner-null family can promote an unverified DNG while still displaying
  and announcing app-owned provenance, or retain the inverse false warning.
- **Required fix:** carry per-output `MediaProvenance` through tracking and deletion plans, then
  publish survivor URI, provenance, and scope as one state packet. Test both mixed-ownership orders.

### AGG27-04 — output-format chips become unreachable on narrow large-font layouts

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed responsive-accessibility defect
- **Cross-agent agreement:** designer and QA adversary
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:954-977`
  renders HEIF/JPEG/DNG in the only plain non-scrollable settings chip row. The fixed tab rail and
  page padding at `ProSheet.kt:269-299` leave roughly 212 dp on a 320 dp screen, while existing 2x
  font coverage does not exercise this row.
- **Failure:** the trailing DNG choice can be clipped with no scroll/bring-into-view path, blocking
  low-vision users from selecting a supported capture format.
- **Required fix:** reuse the RTL-aware horizontal-scroll/overflow affordance without exclusive
  `selectableGroup` semantics, bring selected chips into view, and add a 320 dp, 2x-font Compose
  reachability test.

## Current-cycle accounting

- New review findings: **4**
- Raw specialist findings: **8** (four deduplicated pairs)
- Deduplicated root causes: **4**
- Agent failures: **0**
- Deferred findings: **0**

## Archived cycle-26 aggregate

Date: 2026-08-24
Reviewed HEAD: `242c805`

## Current-cycle review provenance

The global agent pool exposed one fresh child-reviewer slot because prior cycle agents still
occupied the remaining thread capacity. That reviewer performed one complete 419-path inventory and
applied all required specialist perspectives, writing separate `code-reviewer`, `architect`,
`perf-reviewer`, `tracer`, `security-reviewer`, `debugger`, `critic`, `verifier`, `test-engineer`,
`document-specialist`, `designer`, and repository-specific `qa-adversary` artifacts. No perspective
was omitted, and there were no agent failures.

Coverage included all 95 production Kotlin modules, 189 host-test files, four instrumented probes,
two debug hosts, 30 Python files, two shell files, manifests, resources, Gradle/release inputs, and
active documentation. Production source, tools, configuration, and active product documentation are
byte-identical to cycle 25's reviewed/gated `eca8df1`; only cycle 25's aggregate and completed
convergence plan changed. The final sweep re-traced exact Camera2 teardown, still-family producer/
publication/delete authority, recording/GL/review ownership, obscured-touch rejection, legacy-media
provenance, release sealing, UI/accessibility, and documentation claims.

## Current-cycle deduplicated findings

No new confirmed, likely, or actionable manual-validation finding survived aggregation. All twelve
review perspectives reported a clean current HEAD. Existing device-only residuals, accepted product
decisions, and structural debt remain explicitly governed by `docs/BACKLOG.md` and
`docs/FIELD_CHECKS.md`; they were not relabeled as new findings or silently treated as passed.

## Current-cycle verification evidence

- `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug`: passed.
- Documentation checks: 111/111 passed; `git diff --check` passed.
- The byte-identical cycle-25 production/tooling baseline additionally passed 1,854 host tests, 73
  tool/release tests, 182 device-harness self-tests, Python compilation, XML parsing, and configured
  static scans.
- Device gates were not run because there was no current `ANDROID_SERIAL` and no device-mutation
  authorization; no historical device result was promoted to current evidence.

## Current-cycle accounting

- New review findings: **0**
- Deduplicated root causes: **0**
- Agent failures: **0**
- Deferred findings: **0**

## Archived cycle-25 aggregate

Date: 2026-08-24
Reviewed HEAD: `eca8df1`

## Current-cycle review provenance

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
