# Cycle 50 debugger review

Date: 2026-08-25
Reviewed revision: `2388819d` (`origin/main` at review start)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`

## Authority, inventory, and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md`, inventoried all 535 tracked
files, and reviewed every failure-relevant production/debug/tooling file plus its tests and
cross-file owners. The implementation inventory is 102 production Kotlin files, one Java file,
three debug Kotlin files, four instrumented-test Kotlin files, 237 JVM/Robolectric/Compose tests, 33
Python files, and two shell scripts; the remaining 153 tracked docs/build/resources/assets/licenses
were included in configuration, release, evidence, and regression checks.

The direct debugger pass traced Camera2 route inventory/open/session generations, deferred and
fallback session configuration, pseudo-ZSL correlation/watchdogs, still snapshot/family leases,
MediaStore publication/recovery/deletion, GL/EGL outputs and shader initialization, codec/muxer/audio
setup and teardown/quarantine, bounded executors/timers, Activity/ViewModel/Compose lifecycle,
settings/MR rollback, obscured and hardware input, immutable build/release tooling, and the complete
cycle-49 delta. Tests and comments were checked against production control flow rather than accepted
as proof. A final sweep covered release/debug divergence, stale callbacks, partial construction,
exception cleanup, duplicate terminals, queue rejection, timeout ownership, bounds, and data-state
divergence.

## Finding

### DBG50-01 — failed shader initialization leaks every partially created GL object until context teardown

- **Severity / confidence:** Low / High.
- **Classification:** Confirmed native-resource cleanup defect; observing its driver-specific memory
  impact is manual validation.
- **Evidence:** `gl/FlipRenderer.kt:114-165` mutates the long-lived renderer fields progressively:
  program, then VBO, then external texture. `buildProgram` at `FlipRenderer.kt:322-344` deletes the
  shader objects only after a successful link. A fragment compile failure leaks the already-compiled
  vertex shader and the failed fragment shader; a link failure leaks both shaders and `prog` because
  the assignment to field `program` has not completed. Failures after `program`, `quadVbo`, or
  `oesTextureId` are installed are also unsafe on retry: the next `init()` overwrites those fields,
  and `release()` at lines 307-314 can delete only the newest ids. The preview owner intentionally
  retries the same GL generation up to its bounded budget (`CameraEngine.kt:1891-1955`), so this is
  not merely a constructor that is immediately followed by context destruction.
- **Concrete failure scenario:** A transient driver/compiler/link/location/buffer/texture failure
  occurs during the first preview attachment. Preview recovery calls `FlipRenderer.init()` again in
  the same EGL context. A later attempt succeeds and the camera continues, but objects from the
  failed attempt are no longer reachable and remain allocated for the live context's lifetime. If
  all attempts fail, they remain until lifecycle teardown; on a constrained or already-failing GL
  driver this compounds the memory-pressure condition that triggered recovery.
- **Suggested fix:** Make initialization transactional. Keep shader/program/VBO/texture ids in local
  owners, delete each in `finally` on every compile/link/location/upload/setup failure, and publish
  fields only after the complete renderer is ready. `compileShader` must delete its own failed shader;
  `buildProgram` must always delete both shaders and delete `prog` unless ownership is transferred.
  Add a fake-GL seam that injects failure after each acquisition and asserts exact deletion, plus a
  retry test proving no previous ids are overwritten while live.

## Additional cross-role risk

The security report records a separate Low/Medium MediaStore/review risk at
`MediaReview.kt:437-449`: bounds and pixels come from two URI opens, so an owner-null lookalike whose
bytes change between opens can bypass the intended review-size decision. It is not counted again as
a debugger finding because real MediaProvider rewrite semantics still require manual validation.

## Validation evidence and limitations

- Focused cycle-49 regression suites for camera-state/recording admission, pipeline rollback,
  viewfinder keyboard activation, review modal focus, ownerless delete, and all video tests passed.
- PNG mutation tests and the complete committed documentation gate passed; the security report's
  additional illegal chunk-code fixture demonstrates a remaining false-green parser edge.
- No device, EGL fault-injection harness, camera HAL, MediaProvider rewrite race, deployment, or
  production signing operation was used. GL allocation impact and the open A3/A4/A5/D1/E1/E2 field
  obligations remain manual evidence, not inferred success or failure.

## Final missed-issue sweep

The final sweep rechecked all cycle-49 changes (trace admission, serialized video-pipeline rollback,
PNG validation, key-repeat suppression, delete focus return, obscured-gesture evidence, and production
REC admission), then revisited every Camera2/GL/codec/audio terminal, partial native construction,
resource lease, stale generation, provider mutation, still/video family settlement, queue/scheduler
rejection, timeout, lifecycle transition, release/debug branch, and false-green test boundary. No
additional current debugger defect survived source validation.

**New debugger finding count: 1 — Low severity, High confidence, confirmed.**
