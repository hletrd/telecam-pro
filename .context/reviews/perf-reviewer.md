# Performance review — cycle 38

Date: 2026-08-24
Reviewed revision: `fa95299562d52f6b4ddd200f6d410ebd00a54c1d`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Scope and inventory

I read `CLAUDE.md`, the complete as-built authority in `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`, then inventoried all 490 tracked paths. The pass covered all 101 production
Kotlin files, 220 JVM/Robolectric/Compose tests, Android instrumentation/debug sources, resources and
manifests, Gradle/release inputs, Python and shell tooling, the device harness and its tests, and the
review/plan history through completed cycle 37. Prior performance reports were used as regression
oracles rather than copied forward as current findings.

The runtime trace covered Camera2 discovery/open/session/repeating-request/teardown paths; GL frame
coalescing, preview/encoder/analysis work and generation retirement; ZSL and still snapshot/encode/
publication lanes; recording allocation, microphone, codec/muxer, finalization and storage tails;
ViewModel timers, StateFlow and Compose hot paths; media review; and every process-wide provider
dispatcher. Repository-wide searches checked executor/thread construction and shutdown, queue and
collection bounds, blocking waits, retry loops, per-frame/per-buffer allocation and logging, and the
cycle-37 change surface. The focused stabilization capability and quick-Fn tests pass, but they cover
the value projection only, not the Engine side effect described below.

## Findings

### PERF38-01 — capability-only stabilization normalization performs an unchanged request rebuild and a full camera reopen

- **Severity:** Medium
- **Confidence:** High
- **Status:** Confirmed call path; user-visible duration requires device validation on a route that
  lacks `PREVIEW_STABILIZATION`.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:2878-2925`
  normalizes the current label when route caps arrive, then calls the ordinary user-change setter.
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CaptureCapabilities.kt:551-583` proves the relevant
  cases are wire-equivalent: requested `ENHANCED` already maps to HAL `ON` on an OFF+ON route and to
  HAL `OFF` on an OFF-only route; normalizing the label to `STANDARD`/`OFF` does not change that HAL
  value. Nevertheless `CameraEngine.setVideoStabMode` at `CameraEngine.kt:1587-1594` invokes
  `applyStabilization`, whose controller call at `CameraEngine.kt:1561-1564` posts a complete
  `startPreview()` request rebuild, and then calls `reopenForSession()`. That path invalidates Ready
  and queues another full `reconfigureCamera` on the sole setup executor
  (`CameraEngine.kt:2844-2885`). Caps are deliberately published before the candidate's deferred
  session is started (`CameraEngine.kt:3782-3810`), so the normalization callback can enqueue this
  second pass while the first route transition is still completing.
- **Failure scenario:** launch or switch from an Active-capable camera to a generic camera that
  advertises only OFF+ON. The first transition already configures HAL `ON`; the caps callback changes
  only the UI/persisted label from Active to Standard, yet it pays a repeating-request swap and then
  closes/reopens the camera again. On this project's measured HAL a repeating-request swap stalls
  preview and a full route reconfiguration is a visible blackout; on another device the exact delay
  is unmeasured, but the redundant native work is unconditional.
- **Suggested fix:** normalize stabilization inside the generation-owned Engine caps/route commit
  before session configuration, and publish the resolved label/choice list back to the ViewModel; or
  add a side-effect-free reconciliation setter that updates the desired enum only when old and new
  `videoStabControlMode` are identical. Keep the existing reopen behavior for an operator change that
  genuinely changes the HAL mode/session class. Add a test with an Engine spy proving an OFF+ON caps
  callback maps Active→Standard with zero request rebuilds and zero extra reconfigure submissions.

## Final missed-issues sweep

- Frame notification and analysis work remain coalesced/single-flight and generation-owned; the
  analysis readback remains bounded to a 256-pixel long edge with reused buffers.
- Zoom/control input remains coalesced or throttled, and moving pinch gestures still submit no
  repeating requests. ZSL rings, capture snapshot admission, provider queues, review lanes, and
  recording storage remain explicitly bounded.
- Camera/GL/recorder teardown retains deadlines or irreversible quarantine; no new unbounded worker,
  busy-spin, per-frame log flood, main-thread provider/native call, or growing production collection
  survived the repository-wide sweep.
- The remaining cycle-37 changes are small capability projections, rendering-token selection, docs,
  and host-gate checks; no second CPU, memory, responsiveness, or queueing defect survived validation.

## Totals

- New findings: 1
- Severity: 1 Medium
- Confidence: High in the redundant-work path; device timing intentionally unclaimed.
