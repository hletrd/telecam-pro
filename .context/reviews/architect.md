# Architecture review — cycle 38

Date: 2026-08-24
Reviewed revision: `fa95299` (`origin/main`)
Workspace: `/private/tmp/find-x9-cycle38.FKvYBP`

## Coverage and architecture inventory

Examined the full 490-path repository and its cross-file boundaries: Activity/ViewModel unidirectional
state, CameraEngine orchestration, CameraController session fallback and Camera2 ownership,
RendererAssists/GlPipeline/EGL generations, VideoRecorder and process-native quarantine,
StillCapturePipeline and process-finite publication owners, MediaStore durability/recovery/delete,
capability normalization, settings/recall, Compose control projections, debug/device harnesses, and
immutable build/release evidence tooling. The committed design authorities (`CLAUDE.md`,
`docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md`) were read in full; optional `docs/BACKLOG.md` is
not present. I compared current code to the architecture rather than inheriting historical review
conclusions.

## Finding

### ARCH38-01 — finder placement retains two configuration models, but one is a phantom seam

- **Severity:** Low
- **Confidence:** High
- **Status:** Confirmed
- **Evidence:** The old bottom-relative model is explicitly declared replaced at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:363-369`, yet the shared geometry
  authority still exposes and documents `bottomMargin` at `CameraState.kt:671-686`. Its actual
  vertical policy at `CameraState.kt:722-730` is top-relative plus a minimum and measured bottom
  clearance; `bottomMargin` cannot affect the result. The shared-contract tests continue to pass the
  phantom input at `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:17-35,70-88`.
- **Architectural impact:** `finderRect` is intentionally the single geometry authority shared by
  GL pixels and Compose dp. Leaving an inert alternative policy on that boundary weakens the single-
  authority design: callers cannot tell which parameters are state and which are compatibility
  debris, and tests validate an API shape that the renderer does not consume.
- **Concrete failure scenario:** A future large-screen or chrome-layout adjustment chooses the
  documented bottom-inset seam. Both GL and Compose still agree with each other, so alignment tests
  remain green, but they agree on the unchanged and still-overlapping rectangle. The architectural
  invariant "one shared function" therefore masks the fact that its advertised configuration was
  never part of the function's policy.
- **Suggested fix:** Collapse the boundary to one vertical-placement vocabulary: remove the obsolete
  constant/parameter and make `topAnchor`, `FINDER_MIN_BOTTOM_CLEARANCE`, and measured
  `bottomClearance` the only documented inputs. Pin behavioral laws for each live input. If source or
  binary compatibility outside this application is required, isolate the old signature in an
  explicitly deprecated adapter rather than the core geometry authority.

## Known debt and final sweep

The 7,693-line `CameraEngine` facade remains the explicit deferred item from cycle 35, with its
existing exit criterion; I found no new concrete ownership defect spanning its responsibility
regions, so refiling the same broad decomposition request would violate the repository's deferred-
work rules. A final sweep of layering directions, duplicated policy derivations, process-lifetime
owners, lifecycle/reconfiguration transitions, route/capability truth, storage recovery, and
build-evidence boundaries found no other new actionable architecture issue.
