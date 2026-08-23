# Aggregate deep review — cycle 21

Date: 2026-08-24
Reviewed HEAD: `b405dd9dbeb1497fd364d50683036de1b591b24c`

## Review provenance

Three fresh concurrent review agents covered every required specialist perspective and the
repository's registered QA adversary. The environment exposed only three child-agent slots, so
closely related roles were grouped without dropping a required perspective:

- `code-reviewer.md`, `critic.md`, `verifier.md`, and `tracer.md`
- `perf-reviewer.md`, `architect.md`, `test-engineer.md`, and `qa-adversary.md`
- `security-reviewer.md`, `debugger.md`, `document-specialist.md`, and `designer.md`

The reviewers inventoried current production Kotlin, host and instrumented tests, build/release
tools, device harnesses, resources, and active documentation. Historical reviews and completed plans
were treated only as leads. The host debug gate passed with 1,814 tests and zero failures, the
focused release suite passed 23/23, and documentation checks passed 110/110. A few overlapping
focused Gradle invocations collided in the shared generated test-results directory, but the later
whole debug gate completed successfully. Native-device checks were not run because deployment is
disabled and no current `ANDROID_SERIAL` was supplied. There were no agent failures.

## Deduplicated findings

### AGG21-01 — a blocked family-retirement query prevents unrelated delete intent from becoming durable

- **Severity / confidence:** High / High
- **Classification:** Confirmed correctness, privacy, and process-liveness defect
- **Cross-agent agreement:** code-reviewer, critic, verifier, and tracer
- **Evidence:** `MediaStoreWriter.kt:71,358-439` uses one process-global `familyJournalLock` for
  every family mark and retirement. `retireFamilyDeletionMarker` invokes `exactFamilyAbsent` while
  holding that lock, and the production callback performs a synchronous, deadline-free
  `ContentResolver.query`. `DeletedFamilyJournalTest.kt:84-131` checks serial truth but not blocked-A
  versus mark-B progress.
- **Failure:** if old Engine A blocks inside its provider absence query, current Engine B cannot
  commit the durable tombstone for a different deleted family. Process death then loses B's
  in-memory veto, allowing a valid late private sibling to be adopted and resurrected on launch.
- **Required fix:** keep global preference/capacity work short, run provider work outside it, and
  serialize/revalidate retirement by exact family and marker version. Add deterministic unrelated-
  family progress plus same-family mark-versus-retire ordering tests and document the ownership
  protocol.

### AGG21-02 — upload verification ignores packageable source hidden by `.gitignore`

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed release-integrity defect
- **Source:** security-reviewer and document-specialist
- **Evidence:** `check_release_artifact.py:449-456,663-684` defines repository cleanliness with
  `git status --porcelain --untracked-files=all`, which omits ignored files. `.gitignore:34-38`
  contains broad secret-safety patterns, and `git check-ignore` confirms ignored Kotlin/resource
  paths under `app/src/main`; Android packages those roots. `app/build.gradle.kts:102-110,162-177,
  407-427` already rejects ignored packageable inputs during release builds, so the upload checker
  implements a weaker source-identity contract.
- **Failure:** an ignored Kotlin, resource, or manifest input added after the immutable cut can
  change live effective source while HEAD and porcelain status remain clean, yet the checker can
  print `upload-ready` for the older AAB.
- **Required fix:** perform NUL-safe ignored-file enumeration for protected packageable source roots
  at both initial and terminal repository boundaries, preferably through one shared authority with
  the Gradle gate. Add real-Git tests for ignored Kotlin, resource, and manifest inputs introduced
  initially and during verification.

### AGG21-03 — substantial identity hashing follows the checker's last repository observation

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed release-integrity race
- **Cross-agent agreement:** every non-designer review perspective
- **Evidence:** `check_release_artifact.py:663-684` performs the second HEAD/status observation, then
  lines 685-726 re-read and hash the source-version file, private/source AAB, evidence, attestation,
  and sidecar without another Git observation. Multiple reviewers reproduced a clean HEAD change
  immediately after the second status call with the existing valid fixture; the checker returned
  `failures=[]`. Existing late-boundary tests mutate before this interval.
- **Failure:** a normal clean commit can land during final AAB/input hashing and the checker still
  returns `upload-ready` for the previous live branch state, contradicting the operator contract
  that any source change invalidates the result.
- **Required fix:** complete all long artifact/input identity work before the final HEAD, exact
  status, and ignored-source observations, leaving no external or long-running operation afterward.
  Add exact regressions for clean HEAD, dirty status, and ignored-source mutations during the current
  final identity scan. Document the verdict around the immutable attested snapshot if literal
  atomic-at-return live-repository ownership cannot be guaranteed without coordination.

### AGG21-04 — legacy preference cleanup holds the global discard-journal database monitor

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed process-liveness and ownership-isolation defect
- **Cross-agent agreement:** performance, architect, test-engineer, QA adversary, debugger, and
  document-specialist
- **Evidence:** `PendingDiscardJournal.kt:110-141` holds process-global `databaseLock` across
  `removeLegacyEntries(keys.toSet())`; the production callback at lines 14-20 uses synchronous
  SharedPreferences `commit = true`. Every exact-URI `mark`, `lookup`, and `remove` also needs this
  monitor. This contradicts `docs/ARCHITECTURE.md:107`, which says the SQLite monitor covers database
  work only. Current tests cover immediate cleanup failure and blocked provider I/O, not a cleanup
  callback that never returns.
- **Failure:** stalled legacy preference storage during launch recovery freezes unrelated media
  publication and durable DISCARD authority despite the exact-URI design, and can exhaust the
  recovery watchdog even though the indexed SQLite page already completed.
- **Required fix:** freeze the page and cleanup keys under the database lock, release it, then run
  best-effort bounded preference cleanup. Add a deterministic blocking-cleanup test proving
  unrelated mark, lookup, remove, and publication lookup progress while cleanup remains blocked.

## Accounting

- New review findings: **4**
- Deduplicated root causes: **4**
- Agent failures: **0**
- Deferred findings: **0** (all findings require implementation this cycle)
