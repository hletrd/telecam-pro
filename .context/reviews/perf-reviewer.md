# Performance review — cycle 39

Date: 2026-08-24
Reviewed revision: `5ee6b2133fb4ab07fb3605fd5576b087f5f43224`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle39.feeBBZ`

## Scope and inventory

I read the complete committed authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`) and inventoried all 493 tracked paths before reviewing the implementation.
The pass covered all 101 production Kotlin files, 221 JVM/Robolectric/Compose test files,
instrumentation and debug sources, resources/manifests, Gradle and release inputs, 65 Python/shell
tool and device-harness files, and the complete review/plan history through cycle 38. Prior reports
were used as regression oracles, not copied forward as current findings.

The runtime pass followed Camera2 enumeration, open/configure/repeating request and teardown; GL
frame coalescing, preview/encoder draws, bounded analysis readback and retirement; pseudo-ZSL,
snapshot ownership and still encode/publication; recording allocation, microphone, codec/muxer,
finalization and storage tails; ViewModel tickers and input coalescers; Compose/MediaReview decode and
playback; and every process-wide provider dispatcher. Repository-wide searches covered thread and
executor creation, queue/collection bounds, blocking waits, retry loops, per-frame/per-buffer
allocation, handler ownership, synchronization, and the complete cycle-38 change surface.

## Findings

No new performance or responsiveness defect survived validation at current HEAD.

The cycle-38 stabilization fix is causally complete: `CameraEngine.setVideoStabMode` now compares the
old and new effective Camera2 values before either `applyStabilization` or `reopenForSession`
(`app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1587-1597`), and the mapping helper
treats the pre-capability restore path as storage-only
(`app/src/main/kotlin/me/hletrd/telecampro/camera/CaptureCapabilities.kt:586-604`). The focal-rail and
finder-geometry changes are render-token/API simplifications and introduce no new hot-loop or layout
work.

## Final missed-issues sweep

- Producer frame notifications remain coalesced to one draw plus one latest follow-up
  (`gl/FrameNotificationCoalescer.kt:21-50`); analysis remains single-flight with reused direct/heap
  buffers and a 256-pixel long-edge target (`gl/GlPipeline.kt:1410-1545`).
- Full-resolution ZSL ownership remains a fixed three-image ring, and streaming is disabled when the
  selected drive cannot serve it (`camera/CameraController.kt:1360-1434,1458-1475`). Still snapshots
  make one owned YUV copy and defer JPEG compression off the camera thread
  (`capture/StillSnapshot.kt:25-84,122-168`).
- REC pre-native, post-native storage, still-publication, retained-discard, review-media, and delete
  lanes all retain fixed workers and bounded queues; provider timeouts retire publication authority
  without spawning replacement threads. Recorder and GL waits retain finite deadlines or irreversible
  quarantine rather than retrying native work indefinitely.
- Zoom/control input remains coalesced or throttled; lifecycle telemetry publishes only changed
  discrete values; review images remain sampled before decode and stale results retain eager disposal.
  No unbounded worker, busy-spin, per-frame log flood, main-thread provider/native call, or growing
  production collection survived the sweep.

## Totals

- New findings: 0
- Confirmed regressions: 0
