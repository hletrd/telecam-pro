# Aggregate deep review — cycle 18

Date: 2026-08-24
Reviewed HEAD: `98cb8a7aac8453c6db1f51dd9a45dc43fef95729`

## Review provenance

Five fresh, repository-wide review lanes covered every required perspective and the registered
project QA agent:

- `code-reviewer-architect-critic.md`
- `perf-reviewer-tracer.md`
- `security-reviewer-debugger.md`
- `verifier-test-engineer-document-specialist.md`
- `designer-qa-adversary.md`

The reviewers inventoried all current production Kotlin, host/instrumented tests, build and release
tooling, resources, device harnesses, and active documentation. Historical reviews/plans and known
BACKLOG/FIELD_CHECKS residuals were treated as leads only and were not re-filed without current-source
evidence. The designer/QA lane ran the host gate successfully; device gates were blocked by directive
because `DEPLOY_MODE=none` and no current `ANDROID_SERIAL` was supplied. There were no agent failures.

## Deduplicated findings

### AGG18-01 — release authority remains forgeable

- **Severity / confidence:** High / High
- **Classification:** Confirmed release-integrity defect
- **Sources:** code-reviewer + architect + critic
- **Evidence:** `tools/build_immutable_release.py:117-156,617-637` exposes the complete unsigned
  authority-record schema and passes both path and nonce as caller-controlled Gradle properties.
  `app/build.gradle.kts:208-303` authenticates only that self-attested record and public repository
  facts. `tools/tests/test_release_source_gate.py:226-254` tests omission, not a fully valid forgery.
  `README.md:134-138`, `CLAUDE.md:84-88`, and `docs/ARCHITECTURE.md:1209-1219` consequently overclaim
  a fail-closed boundary.
- **Failure:** a direct caller can create a 0600 temporary record with a matching chosen nonce and
  public HEAD/tree/store digest, pass both `-P` properties, then mutate the live checkout after the
  provenance check so release bytes no longer match the embedded commit/tree.
- **Required fix:** make release compilation consume only a wrapper-created sealed source export
  (or use an authenticator unavailable to the caller), add an adversarial schema-valid forgery test,
  and align public/as-built claims with the actual boundary.

### AGG18-02 — review completion racing the deadline can be lost without disposal

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed ownership/liveness race
- **Cross-agent agreement:** four lanes independently filed the same root race: code/architecture/
  critic, performance/tracer, security/debugger, and verifier/tests/docs.
- **Evidence:** `LatestHeavyWorkLane.kt:147-169,208-225` stores a non-null completion, but a timeout
  followed by the separate `result.isCompleted` check can return `Retired` without claiming or
  retiring/discarding that completion. `MediaReview.kt:537-582,738-753,1020-1082` then publishes no
  terminal UI and may retain a bitmap or `MediaPlayer`/`Surface`. Existing tests cover ordinary
  completion, full saturation, and already-retired/null results, not this boundary interleaving.
- **Failure:** a decode/player setup completing around five seconds can leave review indefinitely on
  Loading while retaining expensive/native state until an unrelated future invalidation.
- **Required fix:** route every timed-out await through one atomic request terminal operation, and add
  a deterministic completion-between-timeout-and-classification regression test proving exact
  publish-or-dispose ownership and a non-Loading terminal.

### AGG18-03 — one slow active review call is falsely called capacity exhaustion

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed logic/UX defect
- **Sources:** verifier + test engineer + document specialist
- **Evidence:** `LatestHeavyWorkLane.kt:208-222` uses one five-second deadline for both a request that
  is actively running with spare pool capacity and a request that cannot start because finite
  capacity is poisoned. `MediaReview.kt:548-576,1076-1082` maps both to restart-required, and
  `docs/ARCHITECTURE.md:132,253` repeats that false equivalence.
- **Failure:** a single slow provider/decode/setup call with three process workers free retires useful
  work and tells the user to restart, removing the truthful retry path despite no exhausted capacity.
- **Required fix:** track whether the request started; distinguish active timeout from queued
  capacity exhaustion, provide a retryable active-timeout state, and test slow-active, partially
  blocked, and fully poisoned cases.

### AGG18-04 — permanently blocked launch recovery has no bounded terminal

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed liveness gap; trigger needs injected/provider fault
- **Sources:** performance + tracer
- **Evidence:** `LaunchMediaRecoveryCoordinator.kt:47-69,98-176` holds the sole process worker and
  `running=true` until `recover()` returns or throws. Returned failures have budgets, but a provider
  call that never returns has no independent deadline. `CameraEngine.kt:6095-6143` and
  `CameraViewModel.kt:1067-1096` cannot publish typed completion or run the safe latest-family query.
- **Failure:** one wedged provider Binder call suppresses recovery terminals and latest-capture restore
  for every replacement Engine until process restart.
- **Required fix:** add a process-owned deadline/exhausted state that terminally fails live subscribers
  without spawning replacement workers, and test non-return, Engine replacement, subscriber cleanup,
  and single-worker preservation.

### AGG18-05 — DISCARD paging repeatedly loads and sorts the whole journal

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed CPU/memory/cardinality defect
- **Sources:** performance + tracer
- **Evidence:** `MediaStoreWriter.kt:875-904,1261-1278` reloads `SharedPreferences.all`, filters, and
  globally sorts all remaining DISCARD keys before taking 65 entries on every page;
  `LaunchMediaRecoveryCoordinator.kt:125-176` repeats this for every cursor. Architecture calls the
  work independent bounded pages, but only output cardinality is bounded.
- **Failure:** 10,000 durable discard markers cause roughly 157 whole-map snapshots and descending
  full sorts, delaying the recovery terminal/latest-family restore and creating launch-time GC load.
- **Required fix:** move markers to genuinely cursorable ordered durable storage (or an equivalent
  bounded index), test per-page keys visited with a 10,000-marker fixture, and update architecture
  only when input work is actually bounded.

### AGG18-06 — video review announces Playing before playback exists

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed UI/accessibility state defect
- **Sources:** designer + registered QA adversary
- **Evidence:** `MediaReview.kt:782-813,943-987,1020-1072,1113-1133,1239-1253` initializes/resets
  `playing=true` before asynchronous player setup, preparation, and `start()` succeed. The visible
  pause controls and TalkBack state become actionable while `playerRef` is null/unprepared.
- **Failure:** a slow setup can show Pause/announce Playing for up to the terminal window while taps
  do nothing and the frame remains blank.
- **Required fix:** model Preparing separately, enter Playing only after successful `start()`, suppress
  transport actions while preparing, localize the preparing state, and add deterministic state/UI
  coverage.

### AGG18-07 — architecture map omits two live ownership modules

- **Severity / confidence:** Low / High
- **Classification:** Confirmed documentation defect
- **Sources:** code-reviewer + architect + critic
- **Evidence:** the module map in `docs/ARCHITECTURE.md:39-140` omits
  `camera/EngineCallbackSink.kt` (the Engine→ViewModel callback drain/lease owner) and
  `gl/FrameNotificationCoalescer.kt` (the live frame backpressure owner), although both are production
  paths with concurrency tests.
- **Failure:** maintainers can add callbacks or frame posts outside the central ownership gates because
  the as-built authority does not identify those invariants.
- **Required fix:** add both module rows, describe frame coalescing in the GL path, and extend the docs
  checker with module-map coverage or an explicit leaf allowlist.

## Accounting

- New review findings: **7**
- Deduplicated root causes: **7**
- Agent failures: **0**
- Deferred findings: **0** (all findings are scheduled for cycle-18 implementation)
