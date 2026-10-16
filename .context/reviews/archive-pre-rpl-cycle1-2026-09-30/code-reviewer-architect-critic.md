# Code reviewer / architect / critic review — cycle 33

Date: 2026-08-24  
Reviewed revision: `984424cb36ebe409e67bb2e69c8605ea6f41c4bb`

## Coverage

Inventoried all 442 tracked paths and re-read the complete current `CLAUDE.md`, committed
architecture/field authorities, current aggregate, completed plans through cycle 32, and recent
history. The cycle-32 baseline review already covered every pre-existing production, test, tool,
resource, build, documentation, and device-harness path at `64eff08`; this pass revalidated those
cross-file contracts and deeply traced every production delta from `64eff08` through current HEAD,
including recorder finalization, retained-family retirement/retry, preview readiness diagnostics,
review transforms/gestures, viewfinder accessibility, and ownerless MediaStore deletion. A final
missed-file sweep covered provider calls, executor/deadline ownership, callbacks, manifests,
resources, tools, and tests. No repository-local reviewer roles or `.claude/agents` were present.

`python3 tools/check_docs.py` passed 96 checks with 21 explicitly optional private-document skips;
`git diff --check` passed. Device-only behavior was not inferred or claimed.

## Findings

### C33-CODE-01 — delete-request creation performs a synchronous MediaProvider call on the UI thread

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed UI-liveness / ANR risk
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:352-373` handles the Compose
  Delete action inline and calls `MediaStore.createDeleteRequest(contentResolver, listOf(uri))`
  before returning or launching the `IntentSender`. The platform implementation of
  `MediaStore.createDeleteRequest` delegates to `createRequest`, whose terminal operation is the
  synchronous `ContentResolver.call(MediaStore.AUTHORITY, ...)` Binder transaction (AOSP
  `MediaStore.java`, `createRequest`, lines 1364-1378). This is the only MediaStore provider call in
  `MainActivity`; the repository otherwise routes provider work to finite background owners because
  a provider call can stall indefinitely.
- **Concrete failure:** on a slow, restarting, or wedged ColorOS MediaProvider, pressing Delete on an
  owner-unverified restored file blocks the main looper before Android's confirmation UI can even
  appear. The viewfinder, input dispatch, lifecycle callbacks, and ANR watchdog all wait on the same
  Binder call; `runCatching` handles an exception only after the blocking call returns.
- **Fix:** create the `PendingIntent` on a bounded background owner with an explicit terminal
  deadline, then identity-check the still-current pending URI and launch the resulting
  `IntentSenderRequest` on main. Treat rejection/timeout exactly like the existing launch-failed
  `UNKNOWN` path, and add a deterministic blocked-provider test proving the Delete callback/main
  thread returns before request creation completes.

### C33-CODE-02 — a canceled consent result can leave camera input blocked forever behind an accepted query

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed modal-liveness defect
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3402-3409` freezes the
  review and sets both `ownerlessDeleteConsentPending=true` and `cameraInputBlocked=true`. For every
  non-approved result, `CameraViewModel.kt:3428-3449` clears those flags only after an exact presence
  query returns; an immediate fallback exists only when dispatch is rejected. The accepted task calls
  `MediaStoreWriter.knownOutputPresence`, whose `ContentResolver.query` is at
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:838-860`. The shared
  single-worker executor at `app/src/main/kotlin/me/hletrd/telecampro/ui/ViewModelMediaDeleteDispatcher.kt:52-79`
  has bounded queue capacity but no task deadline, watchdog, or cancellation terminal. By contrast,
  the repository's launch-recovery provider owner explicitly terminalizes a wedged Binder after 120 s
  at `app/src/main/kotlin/me/hletrd/telecampro/camera/LaunchMediaRecoveryCoordinator.kt:214-242`.
- **Concrete failure:** if any earlier accepted delete/sweep has wedged the one process worker, a
  canceled Android confirmation enqueues successfully and therefore does not take the rejection
  fallback. Its presence query never starts, `completeOwnerlessMediaDelete` never runs, and the app
  retains a full-screen modal/input block indefinitely. The same outcome occurs if this exact query
  starts and wedges. Canceling a non-destructive system prompt can thus make every shutter, camera,
  zoom, and focus input inert until the process is killed.
- **Fix:** give the exact pending consent reconciliation a first-wins deadline. On timeout, complete
  with `KnownOutputProviderDisposition.UNKNOWN` (the already-defined truthful fallback), restore the
  frozen file handle, and clear modal ownership; identity-gate the late provider result so it cannot
  overwrite the timeout terminal. Add tests for both an accepted task queued behind a blocked worker
  and a query that itself never returns.

## Final sweep

No additional current correctness, ownership, architecture, or maintainability finding survived
cross-checking against the implemented tests, current documentation, and completed plans. In
particular, the cycle-28 exact-family admission race is fixed at current HEAD; cycle-32 recorder
finalization publication, retirement retry classification, review transform bounds, and survivor
provenance remain internally consistent.
