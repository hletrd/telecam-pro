# Critic report — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Review frame

I read the complete project instructions and required architecture/field authorities, inventoried all
471 tracked paths, and examined the full implementation/test/tool/document surface from product,
operator, maintainer, correctness, and evidence perspectives. I specifically challenged recent
cycle-34 “complete/green” claims against current executable behavior and did a final pass for issues
that remain invisible to compile, lint, ordinary unit tests, or one-off documentation assertions.

## Findings

### CRIT35-01 — the green documentation gate gives false confidence about governing-authority consistency

- **Severity:** Medium
- **Confidence:** High
- **Status:** Confirmed.
- **Exact regions:** `tools/check_docs.py:310-339`; stale AGP reference at
  `docs/ARCHITECTURE.md:1246-1253` versus `gradle/libs.versions.toml:1-8` and `CLAUDE.md:61-68`;
  stale ZSL references at `CLAUDE.md:210-224`, `docs/ARCHITECTURE.md:68`, and
  `CameraEngine.kt:4066-4074` versus `ZslAdmission.kt:25-42`.
- **Problem:** The checker advertises that version facts match the build, but it verifies only the
  Compose BOM across selected documents. It does not check AGP/Kotlin/Gradle or the ZSL contract.
  Running `python3 tools/check_docs.py` on this exact HEAD reports `107 checks, 0 failed` while two
  active governing facts are demonstrably stale.
- **Concrete failure scenario:** A release/contributor trusts the green gate and uses Architecture's
  AGP 9.3.1 quick reference when debugging dependency verification, or follows the mandatory 250 ms
  ZSL promise and “fixes” valid 300–400 ms serves. The formal evidence says green even as the
  authority gives wrong instructions.
- **Suggested fix:** Correct the current facts, then make the checker declarative and comprehensive
  for all duplicated machine-verifiable values. A negative fixture must prove each consumer fails
  when independently stale.

### CRIT35-02 — photo refusal tells the operator a file deletion failed

- **Severity:** Medium
- **Confidence:** High
- **Status:** Confirmed.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4006-4012`,
  `app/src/main/kotlin/me/hletrd/telecampro/ui/LocalizedStatus.kt:37-40,71-76`, and
  `app/src/main/res/values/strings.xml:209,247` (with Korean counterparts).
- **Problem:** A press rejected by the still-admission safety gate emits `COULD_NOT_DELETE_FILE`.
  This is internally explainable—the gate includes retained/rejected-output cleanup capacity—but it
  is externally false: the attempted action was capture, and the user did not ask to delete a file.
  It violates the repository's emphasis on truthful OSD state.
- **Concrete failure scenario:** A hardware shutter edge lands during cleanup-capacity saturation.
  No photo is taken, and the OSD says “Could not delete file.” The operator may retry or inspect the
  gallery rather than wait for capture cleanup, and support logs classify the failure under deletion.
- **Suggested fix:** Use the existing still-unavailable status or introduce quiet, explicit cleanup
  copy. Test the status through the real Engine-to-ViewModel localization path.

### CRIT35-03 — cycle-34's dual-open improvement lacks an adversarial terminal test at the real ownership boundary

- **Severity:** High
- **Confidence:** High
- **Status:** Likely current race.
- **Exact regions:** production transition `CameraEngine.kt:3574-3814`; pure helper tests
  `app/src/test/kotlin/me/hletrd/telecampro/camera/DualOpenWaitTest.kt:1-72`.
- **Problem:** Tests prove only that the polling helper returns `SIGNALED`, `SUPERSEDED`, or
  `TIMED_OUT`. They do not prove the native-owner postcondition after an asynchronous candidate
  callback. In production, `next`'s refusal callback can null `controller` at 3672-3674; the
  supersession cleanup at 3746-3757 then fails its `controller === next` guard and abandons local
  `old` without restore or close.
- **Concrete failure scenario:** A candidate native refusal races an immediate second route request.
  The UI benefits from the new 20 ms cancellation but the outgoing CameraDevice becomes unreachable,
  making the following open fail until process restart.
- **Suggested fix:** Test and implement the owner terminal, not only wait timing. A deterministic
  interleaving should pause after candidate self-removal, supersede the transaction, resume cleanup,
  and assert exact release/restoration of both native owners.

## Balanced assessment and final sweep

The repository has unusually strong identity/generation discipline, finite provider lanes, durable
media rules, pure policy seams, and extensive regression coverage. Those strengths make the remaining
issues sharper: the dual-open path is an exception to the established terminal-owner pattern, and the
documentation gate's green result is stronger-sounding than its actual coverage. I rechecked all
other recent cycle-34 changes and every major subsystem; no additional current issue cleared the
evidence bar.

## Totals

- New findings: 3
- Severity: 1 High, 2 Medium
