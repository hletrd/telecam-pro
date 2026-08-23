# Aggregate deep review — cycle 20

Date: 2026-08-24
Reviewed HEAD: `0d1005dad5dfec6eb705e8a5070486d80be72775`

## Review provenance

Four fresh concurrent review agents covered every required specialist perspective and the
repository's registered QA adversary. The environment's agent-slot limit was explicit, so closely
related roles were grouped without dropping any required perspective:

- `code-reviewer-architect-critic.md` plus `document-specialist-designer.md`
- `perf-tracer-debugger.md`
- `security-reviewer.md`
- `verifier-test-engineer-qa-adversary.md`

The reviewers inventoried the complete current production Kotlin, host and instrumented tests,
build/release tools, device harnesses, resources, and active documentation. Historical reviews and
completed plans were treated only as leads. The QA lane passed
`:app:assembleDebug :app:testDebugUnitTest`, documentation checks passed 110/110, and device gates
were blocked by the no-deploy directive and absence of a current authorized serial. There were no
agent failures.

## Deduplicated findings

### AGG20-01 — a blocked publication holds global DISCARD authority for every media URI

- **Severity / confidence:** High / High
- **Classification:** Confirmed correctness, durability, and process-liveness defect
- **Cross-agent agreement:** code-reviewer, architect, critic, document-specialist, performance,
  tracer, and debugger
- **Evidence:** `PendingDiscardJournal.kt:25-56,80-104,222` protects every `mark`, `lookup`,
  `remove`, `page`, and `withLookupAuthority` call with one process-wide `databaseLock`.
  `MediaStoreWriter.publish` holds that monitor across as many as three synchronous
  `ContentResolver.update` Binder calls and retry sleeps (`MediaStoreWriter.kt:474-505`). The same
  monitor is needed to commit a rejected/deleted output's exact DISCARD marker
  (`MediaStoreWriter.kt:329-339`), and `BoundedRejectedOutputOwner` records in-memory retry ownership
  only after that marker attempt returns (`MediaStoreWriter.kt:1139-1168`).
- **Failure:** if publication of URI A never returns from MediaProvider, URI B cannot durably record
  that deletion/rejection won. Process death can then leave B without durable or in-memory discard
  authority, allowing a structurally valid pending row to be adopted on relaunch. Without process
  death, otherwise independent still, recording-storage, retained-discard, and launch-recovery lanes
  all freeze behind A.
- **Required fix:** keep the global journal monitor around SQLite work only; serialize publication
  against `mark`/`remove` by exact URI or fixed URI stripe, never across unrelated URIs. Provider I/O
  and sleeps must run outside the global monitor. Add deterministic same-URI ordering tests and a
  two-URI barrier test proving B can durably mark/page while A's provider call remains blocked, then
  document the actual short-lock ownership protocol.

### AGG20-02 — RAW-only single shots bypass the still-tail budget and queue without a bound

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed resource-retention and liveness defect
- **Source:** performance, tracer, and debugger
- **Evidence:** each Engine owns an unbounded single-thread `ioExecutor`
  (`CameraEngine.kt:117-120`). SINGLE-shot admission acquires `ProcessedSnapshotBudget` only when
  the selection wants a processed still (`CameraEngine.kt:3860-3883`), while RAW-only is a supported
  normalized selection. Every completed DNG synchronously writes bytes and its durable COMPLETE
  marker, then unconditionally submits the publication continuation to that executor
  (`CameraEngine.kt:4414-4446`). CameraController closes the RAW `Image` and clears the reusable
  pending slot (`CameraController.kt:2094-2138`), so another RAW-only SINGLE shot can enqueue even
  while the first publication is stuck. Existing output-admission checks cover rejected outputs,
  not complete DNG publication tails.
- **Failure:** if MediaProvider accepts insert/write but the first `IS_PENDING=0` update blocks,
  shots B, C, and later shots keep writing complete DNG rows and enqueueing capture-family terminal
  closures behind A. Queue cardinality, retained completion ownership, and pending rows grow without
  a bound, and Engine recreation can create another unbounded queue while the old worker remains
  wedged.
- **Required fix:** put DNG publication behind a finite process-wide still-tail owner or a unified
  processed/RAW publication budget. Because DNG bytes and COMPLETE truth are durable before enqueue,
  saturation should terminally release live family ownership, report delayed save, and leave the row
  for launch recovery rather than deleting data. Test active+queued saturation, overflow recovery,
  repeated Engine replacement, and a hard queue-cardinality bound.

### AGG20-03 — upload-ready verification does not remain bound to final repository state

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed release-integrity race
- **Source:** security-reviewer
- **Evidence:** `check_release_artifact.py:428-451` reads HEAD, worktree cleanliness, and mutable
  `app/build.gradle.kts` once before certificate, JAR-signature, bundle, manifest, provenance, and
  receipt checks. Its final boundary revalidates the AAB, release evidence, attestation, and checksum
  sidecar (`:653-683`) but never rechecks HEAD, status, or build-script identity. A focused fixture
  trace confirmed one HEAD call and one status call. This contradicts the operator contract that any
  source change invalidates the result (`docs/play-console-submit.md:694-700`).
- **Failure:** an ordinary concurrent signed commit or working-tree edit can land during external
  tool validation, after which the checker prints `upload-ready` for artifact A while the repository
  already represents source B. A security or data-loss fix can therefore be absent from the binary
  under a fresh success verdict.
- **Required fix:** snapshot the complete initial repository owner and revalidate it after every
  external tool call, immediately before success: rerun HEAD and exact porcelain status checks and
  require equality; no-follow snapshot and finally revalidate `app/build.gradle.kts`, or remove it as
  a second authority by deriving version truth from attested source. Add clean-commit, dirty-tree,
  path-swap, and in-place mutation regressions at early and late verification boundaries.

### AGG20-04 — the automatic ISO ruler announces English-only state in Korean

- **Severity / confidence:** Low-Medium / High
- **Classification:** Confirmed accessibility and localization defect
- **Source:** verifier, test-engineer, and QA-adversary
- **Evidence:** `ManualDials.kt:1135-1158` builds the adjustable ISO ruler value as the hard-coded
  string `Auto ISO ${controls.iso}` and forwards it to `RulerSlider`, which publishes the string
  verbatim as `stateDescription` (`ManualDials.kt:1281-1300,1363-1369`). The adjacent visible
  readout and shutter ruler already use localized `a11y_auto_value` resources
  (`values/strings.xml:396`, `values-ko/strings.xml:379`). No production Compose test opens the real
  ISO ruler under a Korean configuration.
- **Failure:** with Korean selected and Program or Shutter priority driving ISO, TalkBack announces
  the focused adjustable node as `Auto ISO 9100` even though the adjacent node says
  `자동 ISO 9100`, switching languages inside one control.
- **Required fix:** derive the ISO slider state through `R.string.a11y_auto_value`, preserving the
  normal `ISO N` branch, and add real `ManualDialCluster` Compose assertions for Korean auto ISO,
  English auto ISO, and non-auto ISO.

## Accounting

- New review findings: **4**
- Deduplicated root causes: **4**
- Agent failures: **0**
- Deferred findings: **0** (all findings are scheduled for cycle-20 implementation)
