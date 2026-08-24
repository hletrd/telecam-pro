# Architect report — cycle 36

Date: 2026-08-24
Reviewed revision: `1f45887` (`origin/main`)
Workspace: isolated detached worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Scope and architecture inventory

I read the three required authorities completely, inventoried all 486 tracked paths, and examined
the full implementation/test/tool/configuration surface. The production graph contains 101 Kotlin
files across top-level application ingress; camera, capture, focus, GL, stabilization, storage,
video; and UI/control/review/theme packages. I traced module direction and lifecycle from
MainActivity through CameraViewModel/CameraEngine into Camera2, EGL, codecs/audio, MediaStore, and
the process-lifetime recovery/dispatcher owners. Every current plan and review was checked so the
already-deferred broad CameraEngine decomposition is not re-filed as a new finding.

## Finding

### ARCH36-01 — dual-open cleanup projects nullable ownership into contradictory booleans

- **Severity:** High
- **Confidence:** High
- **Class:** Likely cross-thread correctness defect with a source-proved legal state.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3580,
  3667-3674,3746-3765,7040-7049`; test abstraction at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/DualOpenWaitTest.kt:102-135`.
- **Architectural problem:** The cycle-35 extraction improved terminal ownership, but its API accepts
  three caller-derived booleans instead of the identities/presence from which ownership is defined.
  That erases the distinction between an absent outgoing owner and an outgoing owner equal to the
  slot. At the current call site, `old == null && controller == null` simultaneously means
  `slotVacant` and `outgoingOwnsSlot`; the reducer rejects this as impossible even though nullable
  Engine state makes it legal. The unit tests validate an idealized Boolean algebra rather than the
  production projection that feeds it.
- **Concrete failure scenario:** A controller-less cold/recovery route installs a candidate; native
  refusal clears the candidate while a newer optics generation supersedes it. Once acquisition is
  available again, supersession cleanup derives `(candidate=false, vacant=true, outgoing=true)` and
  throws from `require`. Camera convergence is aborted by an uncaught setup-lane exception and may
  terminate the app process instead of reaching the latest route.
- **Suggested fix:** Make the cleanup boundary identity-based: pass a non-null candidate, nullable
  outgoing, and current slot (or a sealed slot state), derive presence and identity exactly once, and
  return a typed terminal action. If the minimal repair stays Boolean, gate outgoing equality on
  `old != null`. Add an exhaustive production-projection test, including nullable outgoing owner,
  rather than only direct Boolean tuples. This is a bounded correction to the already-identified
  CameraEngine structural residual, not a request for wholesale facade decomposition.

## Final architecture sweep

I rechecked dependency direction, transaction boundaries, native identity terminals, executor and
queue ownership, lifecycle/optics generation ordering, route/profile isolation, immutable state
publication, Camera2/GL/recorder teardown, capture-family durability, review ownership, and
build/evidence authority across every tracked file. Aside from ARCH36-01, no new architectural risk
with a concrete present failure mode survived the sweep. Previously completed findings and the
explicitly deferred broad facade decomposition remain historical/plan state, not new cycle-36
findings.

## Totals

- New findings: 1
- Severity: 1 High
- Confidence: 1 High
