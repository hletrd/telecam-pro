# Security-reviewer and debugger deep review — cycle 55

Date: 2026-08-27
Reviewed revision: `121fcdf09265262ea1c5d2710bddb61b12c3a38f`
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle55.32UR9V`

## Inventory and method

I inventoried all 562 tracked paths and examined all 459 current review-relevant files after
excluding historical `.context/reviews/**` provenance and completed `docs/plans/**` records. I read
the complete committed authorities, traced manifest/export/permission boundaries, URI/provider and
filesystem identity, durable deletion/recovery, release tooling, native-resource terminals, and the
cycle-54 DNG/audio/review changes through their production callers and tests. The authoritative host
gate passed in full. No secret, unsafe exported release component, network-permission regression,
unbounded parser input, or destructive provider action survived source verification.

## Finding

### SEC55-01 — DNG preallocation has no terminal deadline and can wedge process admission

- **Severity / confidence:** Medium / High; confirmed ownership gap. The provider wedge itself is a
  platform/fault-injection precondition, not claimed device evidence.
- **Evidence:** `CameraEngine.kt:4411-4423` acquires both the process-global DNG lease and one finite
  rejected-output cleanup reservation before dispatching provider work at `:4465-4522`.
  `DngPreCaptureAllocation.kt:53-96` retires only on allocation return, dispatch rejection, or an
  explicit lifecycle/optics `cancel()`; it owns no scheduled deadline. The shared dispatcher uses
  two workers and four queued tasks and explicitly cannot interrupt a running provider call
  (`RecordingPreNativeAllocation.kt:27-80`). The blocked-allocation test itself demonstrates that
  admission stays closed until an external cancel at
  `DngPreCaptureAllocationTest.kt:23-60`; it does not model a shutter left current while the Binder
  call never returns.
- **Failure scenario:** MediaStore insert/identity capture wedges while the route remains current.
  The shutter receives no timeout terminal, the global DNG lease and cleanup reservation remain
  occupied forever, and Camera2 never receives the shot. Repeated cancel/retry after lifecycle or
  optics changes can leave both shared provider workers wedged and fill the four-entry queue,
  denying later recording allocation as well. This is a bounded thread count but an unbounded
  availability failure.
- **Suggested fix:** arm an exact first-wins DNG allocation deadline before dispatch, retire the
  attempt and release DNG/cleanup/UI ownership on timeout, and route any late row through exact
  recovery cleanup. Make scheduler rejection fail closed immediately. Add a production-wiring test
  for current-route timeout, late return, cancellation race, and shared-lane availability.

## Final sweep

The final pass rechecked URI reassignment/null-safe deletion, spool no-follow cleanup, packaged
permissions, debug-only DUMP components, backup exclusion, native mic quarantine, release artifact
TOCTOU, parsing limits, callback identity, and error terminals. No second current security/debugger
finding survived. No file in the 459-file current inventory was omitted from the applicable review
surface. No device, deployment, provider mutation, or destructive action was performed.
