# Aggregate deep review — cycle 19

Date: 2026-08-24
Reviewed HEAD: `5af3501342f745564999eb33492001c4457d2955`

## Review provenance

Four fresh concurrent review agents covered every required specialist perspective and the
repository's registered QA adversary. Agent-slot exhaustion was explicit, so complementary roles
were grouped without dropping any required review:

- `code-reviewer.md`, `architect.md`, `critic.md`, and `document-specialist.md`
- `perf-reviewer.md` and `tracer.md`
- `security-reviewer.md` and `debugger.md`
- `verifier.md`, `test-engineer.md`, `designer.md`, and `qa-adversary.md`

The reviewers inventoried the current production Kotlin, JVM/Robolectric/Compose and instrumented
tests, build/release tooling, device harnesses, resources, and active documentation. Historical
reviews and completed plans were treated only as leads. The QA lane passed the debug build and unit
gate, all 110 documentation checks, and English/Korean key parity. Device gates were blocked because
deployment is disabled and no current `ANDROID_SERIAL` was supplied. There were no agent failures.

## Deduplicated findings

### AGG19-01 — DISCARD-journal failure can resurrect delete-owned media

- **Severity / confidence:** High / High
- **Classification:** Confirmed correctness and privacy defect
- **Cross-agent agreement:** tracer, verifier, test-engineer, and QA-adversary
- **Evidence:** `PendingDiscardJournal.kt:23-59` moves exact DISCARD ownership into SQLite but
  collapses every open/query/schema failure to `false`, indistinguishable from authoritative
  absence. `MediaStoreWriter.kt:664-682,733-775,913-922,1368-1382` then falls back to removed
  legacy preferences and may classify a structurally valid row as UNKNOWN and ADOPT/publish it
  before the dedicated DISCARD pass. `PendingDiscardJournalTest.kt:133-151` incorrectly calls the
  unsafe false result fail-closed, while `OrphanSweepTest.kt:127-136` separately proves
  UNKNOWN + VALID adopts.
- **Failure:** a transient SQLite failure during launch recovery can make an exact rejected/deleted
  row gallery-visible and leave it visible when the later DISCARD query also fails.
- **Required fix:** make lookup tri-state (PRESENT, ABSENT, UNAVAILABLE), permit legacy fallback and
  adoption only after authoritative absence, turn UNAVAILABLE into a retryable fail-closed recovery
  terminal, and integration-test a valid SQLite-only DISCARD row under injected DB failure.

### AGG19-02 — failed legacy cleanup repeats the full journal migration per page

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed performance/liveness defect
- **Cross-agent agreement:** code-reviewer, architect, critic, performance, and document-specialist
- **Evidence:** `PendingDiscardJournal.kt:85-164` runs legacy migration before every cursor page.
  When SharedPreferences cleanup fails, the migration-completion bit stays unset even though the
  imports succeeded, so every later page rematerializes all legacy keys and retries every insert.
  Existing coverage handles a cleanup failure followed by success, not persistent failure across
  pages. `docs/ARCHITECTURE.md:71,106,249` therefore overstates total per-page work as bounded.
- **Failure:** 10,000 legacy markers over about 157 pages can cause roughly 1.57 million insert
  attempts plus repeated whole-map allocations and consume the 120-second recovery budget.
- **Required fix:** transactionally record durable import completion separately from idempotent
  best-effort preference cleanup, preserve bounded SQL paging, add persistent-cleanup-failure tests,
  and keep the architecture's bounded claim only after the implementation enforces it.

### AGG19-03 — asynchronous MediaPlayer preparation has no terminal deadline

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed review liveness/resource-ownership gap
- **Source:** debugger
- **Evidence:** `MediaReview.kt:253-281,1030-1101,1130-1149` clears the progressive-lane owner
  after synchronous `setDataSource`; `prepareAsync()` then owns PREPARING with success/error
  listeners but no generation deadline. The five-second boundary in
  `LatestHeavyWorkLane.kt:139-159,185-220,246-300` no longer applies.
- **Failure:** a corrupt/provider/decoder path that never calls either listener leaves the UI in
  PREPARING and retains player/surface state until Back or disposal.
- **Required fix:** add an exact-handle, lifecycle-owned prepare deadline; cancel it on matching
  prepared/error/release; on expiry release exactly once and publish the retryable timeout state.
  Test success, error, never-callback, Back-before-timeout, and stale timeout after replacement.

### AGG19-04 — attestation verification hashes and parses different opens

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed release-integrity TOCTOU
- **Cross-agent agreement:** verifier, test-engineer, and QA-adversary
- **Evidence:** `tools/check_release_artifact.py:337-345` hashes the attestation path and then opens
  it again to parse JSON. Unlike the AAB and release evidence at `:427-465,595-612`, the attestation
  and sidecar lack no-follow snapshots and final identity revalidation. Mutation tests at
  `tools/tests/test_release_artifact.py:397-457` do not cover this boundary.
- **Failure:** a concurrent rename/replacement can authenticate document A with the sidecar while the
  checker parses document B and still prints upload-ready.
- **Required fix:** snapshot attestation and sidecar once without following links, hash and parse the
  same private bytes, revalidate both source identities before success, and add deterministic
  path-swap plus in-place A→B→A tests.

### AGG19-05 — immutable producer and upload verifier disagree on output depth

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed release-workflow correctness defect
- **Cross-agent agreement:** code-reviewer, architect, critic, and document-specialist
- **Evidence:** `build_immutable_release.py:587-594` accepts every descendant of
  `app/build/immutable-release`, although its message and `README.md:138-143` /
  `docs/ARCHITECTURE.md:1220-1225` promise one direct child. The upload checker at
  `check_release_artifact.py:408-425` enforces that exact depth. Focused fixture evidence confirmed
  a nested `nested/candidate` output succeeds in the producer.
- **Failure:** an expensive signed cut can be reported successful but can never pass the authoritative
  upload checker.
- **Required fix:** require `output_root.parent == evidence_namespace`, retain the checker's
  direct-child grammar, and test direct-child success plus grandchild rejection.

### AGG19-06 — release-board synchronization metadata is stale

- **Severity / confidence:** Low / High
- **Classification:** Confirmed active-document metadata defect
- **Sources:** critic and document-specialist
- **Evidence:** `docs/BACKLOG.md:3-6` says it was last synced at cycle 8, although it includes
  2026-08-24 closure material and completed plans through cycle 18.
- **Failure:** readers can reasonably infer that the active release authority has missed ten cycles
  even though current work is already present.
- **Required fix:** remove the manually maintained date/cycle marker and retain `git log` as the
  durable recency source, or mechanically enforce the marker during cycle closeout.

## Accounting

- New review findings: **6**
- Deduplicated root causes: **6**
- Agent failures: **0**
- Deferred findings: **0** (all findings are scheduled for cycle-19 implementation)
