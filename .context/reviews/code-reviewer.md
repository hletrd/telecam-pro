# Code review — cycle 49

Date: 2026-08-25

Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27` (`origin/main`)

Workspace: isolated clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`

## Scope and coverage

I read `CLAUDE.md` first, followed by the complete current `docs/ARCHITECTURE.md` and
`docs/FIELD_CHECKS.md`. I inventoried all 534 tracked paths: all 103 production Kotlin/Java modules,
237 JVM/Robolectric/Compose tests, four instrumented tests, manifests/resources/build inputs, host
tools, device harness, current documentation, historical plans, and prior review provenance. The
source pass covered every production declaration/import and its test references, then traced the
Activity input/lifecycle boundary, ViewModel reducers, CameraEngine optics/session generations,
CameraController request/session truth, GL generation/bindings, still/video/storage ownership, and
Compose focus/input paths. Tests and completion notes were treated as claims, not proof.

## Finding

### C49-CR-01 — held activation keys retrigger viewfinder autofocus on every repeat DOWN

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed input-policy defect. The production handler executes every
  `KeyDown`; physical repeat timing remains device-dependent.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:364-385` maps Enter,
  Numpad Enter, Space, and DPAD-center directly to `onFocusAtCenter()` whenever the event type is
  `KeyDown`, with no repeat-count gate and no pressed-key owner. Android hardware long-press emits
  repeated DOWN events. `app/src/test/kotlin/me/hletrd/telecampro/ui/ViewfinderAccessibilityComposeTest.kt:171-215`
  uses `pressKey`, which supplies one down/up pair per activation and never exercises repeated DOWNs.
- **Failure scenario:** a keyboard or remote user rests on Enter/Space/DPAD-center. The viewfinder
  reissues identical tap-focus commands at the platform repeat cadence, repeatedly restarting or
  superseding Camera2 AF work instead of performing the single button-like activation the user
  expects. The reset branch is mostly idempotent, but it is governed by the same unbounded edge.
- **Concrete fix:** accept only the initial DOWN (`nativeKeyEvent.repeatCount == 0`) or own the key
  from first DOWN through UP and fire once. Add production modifier tests for initial DOWN,
  repeated DOWNs, matching UP, cancellation/focus loss, and a fresh second press for every mapped
  key family.

## Verification and limits

- Focused `ViewfinderAccessibilityComposeTest` and `ModalFocusComposeTest` passed; their green result
  confirms the missing repeat case is outside current coverage.
- `python3 tools/check_docs.py` passed 151 checks with 24 declared private-file skips.
- No device, camera, keyboard/remote, TalkBack service, or MediaProvider flow was run. Field checks
  A3/A4/A5/D1/E1/E2 remain manual risks, not findings from this review.

## Final missed-issue sweep

No second code-quality or invariant failure survived cross-file checking. In particular, the new
video codec/candidate/requested-transfer/active-transfer packet restores all four Engine fields on
optics rollback, and REC admission filters candidates against the current transfer. The broad
CameraEngine size remains previously recorded debt rather than a new concrete defect.

---

## Archived prior review

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
