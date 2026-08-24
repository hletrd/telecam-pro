# Critic review — cycle 39

Date: 2026-08-24

Reviewed revision: `5ee6b2133fb4ab07fb3605fd5576b087f5f43224`

Role: skeptical multi-perspective critique

## Coverage

Inventoried all 493 tracked paths: 101 production/debug Kotlin sources, 224 JVM/Compose/
instrumented test sources, Python and shell build/device tooling, Gradle configuration,
manifests/resources, privacy/store assets, and committed documentation/review history. Read the
complete clean-clone authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`) before tracing implementation.

The cycle-38 change surface was checked line by line: stabilization label reconciliation from
`CameraViewModel.reconcileZoomToCaps` through `CameraEngine.setVideoStabMode`, accepted-route
capability fallback in `CaptureCapabilities`, live request and session publication in
`CameraController`, deterministic shared-pool ownership tests, focal-rail rendering, and the shared
GL/Compose finder geometry. The wider critique also swept route/session generations, exposure and
zoom remaps, capture-family durability and deletion, recording admission/native quarantine,
MediaProvider recovery, GL ownership, permissions/navigation, localization, and immutable build
evidence.

Host-side independent evidence was green: 120 documentation checks, 99 tooling tests, nine
coverage-tool tests, 184 device-harness self-tests, and `git diff --check`. No device action was
taken and no open field check was promoted to implementation evidence.

## Findings

No new confirmed findings.

The previous phantom finder input is gone: `finderRect` now exposes only `sideMargin`, `topAnchor`,
and measured `bottomClearance`, and `FinderGeometryTest` independently asserts their actual axes and
lower-bound behavior. The stabilization optimization also preserves accepted truth: before caps it
stores intent only; after caps it skips work only when the before/after labels resolve to the same
Camera2 value, while a real OFF/ON/PREVIEW transition retains request rebuild plus session reopen.
The selected-disabled focal chip now keeps the common `HudPlate` floor and the rendered bright/dark
matrix exercises all selected/enabled combinations.

## Final missed-issue sweep

Rechecked model-string boundaries, capability availability and normalization, Camera2/GL terminal
ownership, capture and recording exactly-once publication, late provider results, parser bounds,
UI action guards, EN/KO parity, privacy claims, and release/debug provenance. Potential concerns
without a reproducible invariant violation or concrete failure path were omitted.
