# fd-code-reviewer (FD3) — RPL cycle 3, HEAD e3a2bdd4

(Saved by the orchestrator from the agent's message; the agent type cannot write files.)

Scope: storage/{CaptureFamily,LatestCaptureReducer,PendingDiscardJournal,SettingsStore,MediaStoreWriter}.kt,
capture/{DngCapture,HeifCapture,HeifExif,StillCapturePipeline,StillSnapshot}.kt, video/VideoRecorder.kt,
camera/CameraController.kt (~11,500 lines), five focused passes, skipping items in the cycle-2 aggregate.

Result: zero new high-confidence findings (confidence-filtered reviewer).

Confirmed fixed on main: AGG2-9, AGG2-18 (c4cc92e7), AGG2-19 (03203d17), AGG2-20 (c91d741f),
AGG2-21 (f4ecda3d), AGG2-22 (a0a12b7d), AGG2-24 (56803b42), AGG2-27 (b1447900), AGG2-29 (25631385).

Ruled out (traced, not findings): `cleanupOrphanedPendingBatch` preflight-abort is deliberate; double
`close()` of the same fd in `probeCompleteHeif`/`probeCompleteJpeg` is a no-op; VideoRecorder
`muxerLock` re-entrancy and quarantine ordering race-free; CameraController pending/watchdog, stale
image vs new pending, ladder monotonicity, ZSL ring teardown OK; capture bitmap aliasing, HeifExif
marker scan, NV21 chroma copy, EXIF orientation ordering OK.

Sub-confidence observations (not numbered by the agent; the orchestrator records them as FD3-1..3):

- FD3-1 (Low / Low): on ladder exhaustion, `jpegReader`/`rawReader` close depends on Engine caller
  behaviour; leak unconfirmed.
- FD3-2 (Low / Low): `applyMetering` sizes regions off `chars()` keyed `physicalId ?: logicalId`
  while the request is built against the logical device; only matters if `physicalId` is non-null
  on a live route (not confirmed reachable).
- FD3-3 (Low / High): `capture/StillSnapshot.kt` `Nv21.jpegBytes()` (~46-52) nulls the ~19 MB
  `pixels` array only after `check(ok)` succeeds; a failed `compressToJpeg` retains it for the
  snapshot's lifetime, contradicting the class KDoc. Memory-only.
