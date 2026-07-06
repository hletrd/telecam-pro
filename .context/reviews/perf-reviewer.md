# Perf-Reviewer — Run-2 Cycle 1 (2026-07-06)

Concurrency / CPU / memory / UI responsiveness. Full detail in `_aggregate.md`.

- **R1-2 (=C2-6, A-PERF-3) [Med/High]** `camera/CameraEngine.kt:118` + `stab/GyroEis.kt:69` — the EIS
  provider allocates a fresh `FloatArray(3)` every rendered frame (30-60 Hz) on the GL thread
  (`GlPipeline.drawFrame:209`) → steady GC pressure that hitches preview when EIS matters (hand-held
  300 mm). Fix: fill-in-place `GyroEis.fillCorrection(out)` + a GL-owned reusable array; change
  `eisProvider` to `((FloatArray)->Unit)?`. **SCHEDULED.**
- **R1-11 (=UI-2) [Med-High/High]** `ui/CameraScreen.kt:265-333` — unstable `CameraUiState` passed
  whole to `TopBar`/`ManualDialCluster`/`ProSheet`; every telemetry tick recomposes all three.
  **DEFERRED** (CameraScreen refactor; device recomposition profiling per CLAUDE.md).
- **R1-12 (=UI-11) [Low/Med]** `ui/controls/ProSheet.kt:507-551` — `VideoTab` re-allocates option
  lists each recomposition. **DEFERRED** (fold into R1-11).
- **A-PERF-1 [High/High-mech]** `gl/GlPipeline.kt:248-288` — full-preview-resolution `glReadPixels`
  every 12th frame with scopes on. **DEFERRED** (downscaled-FBO readback; device-profile).
- **A-BUG-2/A-PERF-2 [Med-High/High]** `CameraEngine.setCameraOverride` runs camera IPC on the MAIN
  thread. **DEFERRED** (off-main w/ reopen-order care; device-verify).

Verified: gyro sampling capped ~200 Hz, analysis single-in-flight guard (`analysisBusy`), record-stop
off the main thread, `drawFrame` direct call (no per-frame Runnable) — all correct.
