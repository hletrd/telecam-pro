# fd-code-reviewer review — 2026-10-02

Agent: feature-dev:code-reviewer (read-only; no git/Bash available to it, so it reviewed the working
tree directly).

## Findings

No new correctness bug at confidence >= 80 beyond the ones other reviewers already reported.
It independently confirmed the DNG restore loss: `CameraViewModel.applyLoaded`'s
non-`preserveChangedOptics` branch feeds `ZoomMath.restoredOptics`, which reads a persisted
lens-local ratio as unified whenever DNG forced a standalone route at save time (= AGG-2).

## Checked and judged correct
- `RotationMath`: every public function normalizes to [0,360).
- `VideoRecorder.videoStartupDeadlineExecutor.shutdownNow()` would break a second `start()` on the
  same instance, but the Engine builds one recorder per attempt, so it is unreachable.
- `ZslAdmission`, `RecordingAdmissionLatch`, `DngPreCaptureAllocation`, `SettingsStore`,
  `LatestCaptureReducer`, `CaptureFamily`, `CaptureOutputTracker`, `ControlAvailability`,
  `OpticsConstraints`, `sessionAttemptPlan`/ZSL ring, Engine REC admission rechecks (~6060-6330).

## Recommendation
Weight later effort toward device verification and the unexamined parts of `CameraEngine`
rollback paths and `GlPipeline`/`EglCore` EGL lifetime.
