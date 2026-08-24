# Architecture review — cycle 49

Date: 2026-08-25

Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27` (`origin/main`)

Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`

## System inventory and architectural sweep

I read the complete committed authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`) and inventoried all 534 tracked paths. The architecture pass traced the
Activity/ViewModel unidirectional boundary; CameraEngine optics and capture orchestration;
CameraController accepted-session/fallback ownership; RendererAssists/GlPipeline/EGL generations;
processed and RAW save lanes; video pre-native/native/storage owners; exact-family MediaStore
durability, deletion, and recovery; review work pools; settings/MR normalization; capability-driven
UI projection; and immutable build/release evidence.

The final cross-boundary sweep rechecked the system's load-bearing rules: requested state versus
accepted session truth, generation-owned rollback, exact native identities, finite process queues,
producer termination before family retirement, debug diagnostics as non-functional observers, and
release behavior matching debug behavior apart from explicitly absent instrumentation.

## Finding

### A49-01 — a debug evidence payload is a required release capture dependency

- **Severity / confidence:** High / High
- **Classification:** Confirmed architecture and implementation defect.

`CaptureFamilyTraceAdmission` is modeled as runtime capture state even though its contract says it is
debug harness evidence. `CameraEngine.photoCallback` then creates the corresponding payload only
under `BuildConfig.DEBUG` (`CameraEngine.kt:4713-4725`) but consumes admission outside that boundary
(`CameraEngine.kt:4727-4743`). Because `captureFamilyTraceAdmission` admits every ordinary Single
shot and every in-REC snapshot settlement (`CameraState.kt:971-986`), release Single callback
construction dereferences a null debug payload. The enclosing public path catches the exception and
returns `PHOTO_CAPTURE_FAILED` before Camera2 dispatch (`CameraEngine.kt:4164-4183`). The settlement
variant can throw before producer-terminal publication and family-lease release
(`CameraEngine.kt:4737-4751`).

This violates two explicit architecture laws at once:

- diagnostics must be observational and absent from release without changing product control flow;
- every still-family producer must reach exactly one terminal edge before deletion/recovery may
  retire its authority.

The layering error is that build policy, diagnostic admission, diagnostic payload creation, and
capture-family lifecycle are four separate decisions. Their invalid combination is representable:
`DEBUG=false + admitted=true + payload=null`, and production uses that state on the default shutter
path. The cycle-48 test covers only the admission reducer under the debug variant, while release
assembly proves bytecode construction but executes no shutter behavior. The green authoritative
host gate therefore gives false assurance about the release architecture.

**Fix direction:** Collapse those decisions into one build-aware optional trace object (or a logger
whose release implementation is a no-op) and let capture lifecycle consume only that interface.
Registration/settlement observation must wrap no ownership mutation and cleanup must remain
unconditional. Add an explicit build-mode matrix around callback creation and producer settlement,
plus a release-variant public capture smoke test. The invariant should be structural: disabling a
diagnostic must remove output only, never data or control-flow dependencies.

## Architectural debt and final sweep

The 7,000-plus-line CameraEngine facade remains the previously recorded deferred decomposition item.
A49-01 does not justify a wholesale rewrite; it identifies a narrow extraction boundary for
capture-family tracing that should become an optional observer beside, not inside, producer
lifecycle ownership.

I also rechecked the new video-pipeline transaction packet, rollback publication, shader binding
authority, obscured gesture cancellation, modal focus ownership, release permission allowlist, and
screenshot validation against their consumers and tests. No second independent architecture
finding survived the final sweep. Open field checks remain validation risks rather than architecture
defects, and no device-only claim was promoted.
