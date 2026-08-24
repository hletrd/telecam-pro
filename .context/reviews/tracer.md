# Causal-tracing review — cycle 49

Date: 2026-08-25
Reviewed revision: `69c9c64af89e57ce98408a0a16c8545bfabf69d8`
Workspace: isolated clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`
Mode: Prompt 1 review; no production source, plan, shared-main, device, deployment, or git mutation

## Scope and method

I read the complete committed authorities and inventoried all 534 tracked paths before tracing the
runtime graph. The causal pass covered route inventory; optics intent, baseline, commit and rollback
generations; Camera2 controller/session/preview health; GL/output/analysis identities; AF/custom-WB/
ZSL/capture correlation; still family production, deletion and recovery; REC allocation, microphone,
native finalization and storage; review/player and ownerless-delete deadlines; lifecycle replacement;
UI publication; release/debug variants; tests; and host/device evidence tooling. The final sweep
rechecked the complete cycle-48 change surface and every production executor, delayed task, retry,
mutable owner, debug diagnostic, and cross-thread read/write seam.

## Findings

### TRACE49-01 — release SINGLE capture follows a debug trace decision to a null payload

- **Severity / confidence:** High / High.
- **Classification:** **Confirmed causal failure.** This is the same root cause as PERF49-01 and
  should be deduplicated once.
- **Exact evidence:** `captureFamilyTraceAdmission` is build-independent and admits both SINGLE log
  edges (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:971-986`), but `traceText`
  exists only when `BuildConfig.DEBUG` (`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4713-4725`).
  Registration and settlement consult only the admission flags and force the nullable payload
  (`CameraEngine.kt:4727-4743`). Ordinary capture catches the registration throw before Camera2
  dispatch and reports failure (`CameraEngine.kt:4152-4178`); an in-REC snapshot reaches the same
  throw at settlement, before producer-terminal ownership and continuation cleanup.
- **Concrete scenario:** Any release ordinary shutter press takes the path
  `SINGLE admission -> traceText=null -> registration=true -> traceText!!`. The causal edge that was
  intended only as debug evidence becomes the terminal owner of release capture admission. Debug
  tests cannot reproduce it because their generated `BuildConfig.DEBUG` is true.
- **Suggested fix:** make the trace plan carry its effective build admission, keep payload creation
  and use in one nullable-safe branch, and test the same callback lifecycle with explicit debug false.

### TRACE49-02 — `setVideoPipeline` derives a supposedly atomic packet outside the optics monitor, so rollback can install Photo + active HLG without a generation or reopen

- **Severity / confidence:** Medium / High.
- **Classification:** **Confirmed data race; user manifestation is timing-dependent.** The mutable
  fields and writers are present on different lanes, and no test controls this interleaving.
- **Exact evidence:**
  - Background optics failure restores `videoMode`, active `transfer`, requested transfer, codec and
    candidates under the Engine monitor (`CameraEngine.kt:764-793`).
  - The new `setVideoPipeline` reads `videoMode` and `transfer` to derive `activeTransfer` and
    `tenBitChanged` before acquiring that monitor (`CameraEngine.kt:2511-2536`). It takes the monitor
    only later, either through `beginOpticsTransaction` or the plain publish branch
    (`CameraEngine.kt:2537-2549`).
  - Mode changes intentionally publish desired Video state before asynchronous setup can later
    rollback (`CameraEngine.kt:2224-2237,2264-2282`), creating a substantial overlap window in which
    a transfer/codec command or late encoder-inventory reconciliation can execute.
  - Architecture claims the codec/candidates/requested transfer/active transfer are one packet and
    that UI HLG cannot coexist with an accepted SDR session (`docs/ARCHITECTURE.md:308-314`), but
    there is no direct concurrency test for `setVideoPipeline`; current rollback tests call it
    serially through the ViewModel.
- **Concrete failure interleaving:**
  1. Photo→Video publishes desired `videoMode=true, transfer=HLG` and starts generation *g*.
  2. A pipeline command reads those values, derives `activeTransfer=HLG`, and computes
     `tenBitChanged=false`, then pauses before its publish branch.
  3. Setup failure rolls *g* back under the Engine monitor to the accepted Photo/SDR packet and
     queues the UI rollback.
  4. The pipeline command acquires the monitor and performs the no-generation branch computed from
     the stale pre-rollback snapshot, writing `transfer=HLG` while `videoMode=false`; it then pushes
     HLG into GL. Because its stale `tenBitChanged` was false, it neither creates a newer optics
     intent nor reopens Camera2.

  The result is a standard SDR Photo session with Engine/GL active HLG truth. The viewfinder is
  transformed with the wrong curve, the next snapshot/rollback baseline records a hybrid packet,
  and there is no convergence edge until another mode/transfer action happens.
- **Competing hypotheses checked:**
  - ViewModel actions originate on main, but rollback mutates the Engine on `setupExecutor`; the
    relevant writers are therefore concurrent even when all UI calls are serialized.
  - Volatile fields make individual reads visible but cannot make the multi-read derivation atomic.
  - The later `synchronized(this) { publish() }` protects only writes; it does not revalidate the
    mode/transfer values that selected the branch and packet.
  - The optics generation protects only the `tenBitChanged=true` branch. This exact interleaving
    selected the plain branch before rollback, so no generation exists for rollback/Ready ordering
    to reject.
- **Suggested fix:** normalize candidates outside the lock if desired, but derive active transfer,
  boundary-change decision, baseline and publication from current Engine state inside the one
  optics/Engine monitor. If the boundary changes, increment/publish the generation in that same
  critical section; otherwise revalidate current mode before the fast packet commit. Add a
  deterministic latch-based test that pauses pipeline derivation, forces owned mode rollback, then
  resumes and asserts Photo remains active SDR or that a newer owned generation converges.

## Competing-hypothesis and missed-issues sweep

I rechecked capture save-lane exactly-once completion, family producer leases and deletion
tombstones, latest-capture ordering, route/preview/GL/controller identities, recorder admission and
stop-during-start latches, native detach/quarantine, finite provider dispatchers, ownerless delete,
review decode/player ownership, microphone handoff, zoom/control coalescers, lifecycle ticker
cancellation, and all current test/comment/document claims. Outside the two findings above, no stale
rollback, nullable-owner alias, wrong-clock comparison, double terminal, lost continuation,
use-after-retire publication, unbounded owner, or conflicting source/test/document contract survived
validation. The remaining A3/A4/A5/D1/E1/E2 ledger entries are explicit manual/device evidence gaps,
not host-proven causal defects.

**New finding count: 2 (one shared with the performance review).**
