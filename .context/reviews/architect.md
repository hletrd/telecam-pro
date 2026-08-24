# Architecture review — cycle 39

Date: 2026-08-24
Reviewed revision: `5ee6b21` (`origin/main`)
Workspace: `/private/tmp/find-x9-cycle39.feeBBZ`

## Coverage and system inventory

Reviewed all 493 tracked paths and read the complete governing authorities: `CLAUDE.md`,
`docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md`. The optional private maintainer documents are
absent, which the committed clean-clone policy explicitly permits. The architecture inventory
covered the Activity/ViewModel unidirectional state boundary; CameraEngine optics/session
transactions; capability-enumerated rear/front/external routing; CameraController session fallback
and terminal release; RendererAssists/GlPipeline/EGL generation replay; processed-still and RAW
ownership; video pre-native admission, native teardown, storage tails, and quarantine; exact-family
MediaStore publication/delete/recovery; settings/MR normalization; Compose control projections; and
immutable build/release evidence.

Cross-file review concentrated on the system's load-bearing invariants: PMA110 quirks remain behind
`DeviceProfile` while generic hardware follows enumerated capabilities; Ready state carries accepted
controller/session/output truth; optics changes are generation-owned; GL and Camera2 replacements
require exact terminal ownership; native uncertainty quarantines rather than racing cleanup; provider
work stays in process-finite lanes; capture-family deletion and late publication share one exact-key
authority; and UI/persistence/EXIF consume one normalized optics declaration.

## Findings

No new actionable architecture finding survived the whole-repository and cross-boundary review at
the reviewed revision.

In particular, cycle 38 did not add a second stabilization authority: user-visible label
normalization remains in the capability projection while CameraEngine decides reconfiguration from
the effective HAL value. The finder geometry cleanup strengthens the existing single-authority
boundary shared by Compose and GL. The test-capacity fixes change only scheduler handshakes, not the
production latest-wins or bounded-pool design.

## Known debt and final sweep

The 7,000-plus-line `CameraEngine` facade remains the explicit deferred item AGG35-08 in
`docs/plans/2026-08-24-rpf-cycle35.md` (Medium severity, High confidence). Its exit criterion requires
a new concrete defect spanning at least two responsibility regions, or planned work that must modify
three or more regions. This review found neither, so filing the broad decomposition again would
duplicate an existing deferred record rather than identify new work.

The final sweep rechecked layering direction, duplicate policy derivations, volatile versus atomic
multi-field publication, lifecycle/reconfiguration transitions, process-singleton capacity and
callback retention, route/capability truth, accepted-session versus requested state, storage
durability and recovery, ownerless-media consent boundaries, release provenance, and documentation
alignment. `python3 tools/check_docs.py` passed all 120 applicable committed checks with zero
failures. Open field checks were kept as unverified device/provider work and were not converted into
architectural claims.
