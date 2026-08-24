# Code review — cycle 39

Date: 2026-08-24
Reviewed revision: `5ee6b21` (`origin/main`)
Workspace: `/private/tmp/find-x9-cycle39.feeBBZ`

## Coverage

Inventoried all 493 tracked paths: 101 production Kotlin files, 224 JVM/Robolectric/Compose and
instrumented Kotlin tests, 32 Python files across build/release tooling and the device harness,
Android manifests/resources, Gradle/version/signing configuration, shell utilities, and the
committed documentation/review/plan corpus. I read the complete `CLAUDE.md`, current
`docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md`; the optional private `docs/BACKLOG.md`,
`docs/TESTING.md`, and `docs/UX_POLICY.md` are absent as permitted by the clean-clone policy.

The review followed the complete request path from `MainActivity` and `CameraViewModel` through
`CameraEngine`, route/capability normalization, `CameraController`, GL generation ownership,
processed/RAW capture, video admission/finalization, MediaStore durability/recovery/deletion, and
review publication. It also checked settings/MR restore, front/rear/external route behavior,
PMA110-only `DeviceProfile` gates, renderer-state replay, lifecycle teardown, process-finite worker
owners, permission fallbacks, localization/resource usage, and immutable debug/release evidence.
Tests and documentation were treated as claims to verify against source rather than as proof by
themselves. Historical reviews were used only as leads; previously fixed or explicitly deferred
items were not refiled.

## Findings

No new actionable code-quality, correctness, or maintainability finding survived evidence checking
at the reviewed revision.

The cycle-38 changes were examined specifically. The stabilization-label fast path compares the
resolved Camera2 value before suppressing reconfiguration and still stores the normalized intent;
all real HAL-mode transitions retain request/session reconfiguration. The selected-disabled focal
chip retains the shared live-frame contrast foundation, the latest-work capacity tests now establish
the required start ordering, and the removed finder-margin seam has no remaining production caller.

## Verification and final missed-issue sweep

- `python3 tools/check_docs.py` completed 120 checks with zero failures (24 clean-clone private-file
  checks skipped), including production-module inventory and the review-critical ownership map.
- `git diff --check` passed and the worktree was clean before these two review reports were written.
- I swept TODO/FIXME/suppression sites, ignored `runCatching` results, blocking and executor creation,
  callback publication, compare-and-set/monitor ownership, route/model branching, persistence
  normalization, accepted-session output truth, late media callbacks, native teardown/quarantine,
  manifest permissions/features, and build provenance boundaries.
- The known broad `CameraEngine` decomposition debt remains explicitly deferred in
  `docs/plans/2026-08-24-rpf-cycle35.md` with its original Medium/High classification and exit
  criterion. No new defect crossed enough responsibility regions to trigger that criterion.
- The remaining field checks in `docs/FIELD_CHECKS.md` require real scene/device/provider evidence;
  no host-only result was promoted to device proof.
