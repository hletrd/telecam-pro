# Architect report — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Scope and architecture inventory

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely, then inventoried
all 471 tracked paths and examined all review-relevant production, test, tool, configuration, and
authority files. The implementation surface contains 101 production Kotlin files. I traced the
module graph from Activity/Compose through ViewModel and CameraEngine into Camera2, GL, capture,
storage, recorder/audio, review, and process-owned recovery/dispatcher components, including every
cross-thread ownership seam and the current cycle-34 delta.

## Findings

### ARCH35-01 — dual-open ownership is split between a local `old`, a shared nullable field, and asynchronous callbacks

- **Severity:** High
- **Confidence:** High
- **Status:** Likely correctness defect with a fully specified legal interleaving.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3574-3814`,
  specifically candidate callback mutation at 3667-3684 and conditional old-owner restoration at
  3746-3757.
- **Architectural problem:** The repository generally models replaceable native resources with
  explicit identity terminals (`AtomicOwnerSlot`, `CameraTeardownTerminal`, recorder teardown
  coordinators). Dual-open does not: the outgoing controller lives only in a local variable, the
  candidate lives both locally and in `controller`, and camera callbacks can independently null the
  shared field. Consequently, shared-field equality is being used as proof of who must release a
  local native owner, but a candidate callback can erase that proof without assuming responsibility
  for `old`.
- **Concrete failure scenario:** Candidate native refusal clears `controller`; a concurrent optics
  supersession causes the polling loop to return; the supersession branch cannot pass
  `controller === next`, so neither restores nor closes `old`. A later generation opens over an
  unreachable still-live CameraDevice and may remain black/`CAMERA_IN_USE` until restart.
- **Suggested fix:** Extract a `DualOpenTransition` owner with an atomic state machine that contains
  outgoing, candidate, transaction, and terminal release/restoration actions. All callbacks and
  setup-lane branches should deliver facts to it; only its exactly-once terminal should mutate the
  Engine controller slot. Test candidate-error × supersession × pause × signal permutations, not
  only the timing helper.

### ARCH35-02 — current contracts are duplicated across code and prose without a general drift boundary

- **Severity:** Medium
- **Confidence:** High
- **Status:** Confirmed systemic maintainability defect with two current instances.
- **Exact regions:**
  - Build truth: `gradle/libs.versions.toml:1-8` says AGP 9.3.2; `CLAUDE.md:61-68` agrees;
    `docs/ARCHITECTURE.md:1246-1253` still says AGP 9.3.1.
  - ZSL truth: `camera/ZslAdmission.kt:25-42` and its tests use 400 ms;
    `CLAUDE.md:210-224`, `docs/ARCHITECTURE.md:68`, and `CameraEngine.kt:4066-4074` say 250 ms.
  - Gate gap: `tools/check_docs.py:310-339` single-sources only the Compose BOM; the complete
    checker reports 107/107 green despite both contradictions.
- **Architectural problem:** `CLAUDE.md` and `docs/ARCHITECTURE.md` are designated governing
  authorities, but mechanically verifiable constants are copied into them ad hoc. The documentation
  gate has one-off checks created after individual drifts rather than a declarative map of code/build
  facts to every authority consumer.
- **Concrete failure scenario:** A toolchain upgrade changes the catalog and one authority while a
  release operator reads the stale architecture quick reference. Separately, a maintainer follows
  the mandatory 250 ms ZSL claim and reverts a deliberate 400 ms device fix. Both survive the
  advertised repository-wide documentation gate today.
- **Suggested fix:** Add a small declarative contract table in `tools/check_docs.py` for AGP, Kotlin,
  Gradle wrapper, SDK levels, Compose BOM, and behavior constants such as ZSL age. Prefer eliminating
  redundant exact versions from secondary prose where a link to the canonical table suffices. Add
  negative fixtures for each mapped authority.

### ARCH35-03 — CameraEngine remains an oversized transaction boundary that makes native-owner invariants non-local

- **Severity:** Medium
- **Confidence:** High
- **Status:** Confirmed maintainability/architecture risk; the current dual-open defect is concrete
  evidence of the risk rather than size alone.
- **Exact region:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt` (7,647 lines,
  233 function declarations, 82 `@Volatile` sites, 72 synchronized blocks, and 23 callback properties).
  Representative mixed responsibilities are route/capability caching at 846-1050, GL lifecycle at
  1400-1900, optics transactions at 2180-3815, capture/storage deletion at 3992-4780, recorder/native
  finalization at 4820-6305, and launch recovery/release at 6350-6700.
- **Architectural problem:** Extracted pure helpers and finite process owners are good, but the
  facade still performs their multi-owner state transitions directly through dozens of independent
  volatile fields and local variables. Invariants such as “every outgoing CameraDevice is either
  restored or strictly released” and “capture denial reports its own subsystem” cannot be inspected
  or tested at one boundary.
- **Concrete failure scenario:** The cycle-34 polling improvement changed wait timing without a
  corresponding transition owner; the now-faster supersession path exposes the callback/cleanup gap
  in ARCH35-01. The capture-admission branch at `CameraEngine.kt:4006-4012` also emits a deletion
  status, a smaller example of responsibility leakage inside the same facade.
- **Suggested fix:** Continue the repository's successful extraction pattern at state-machine, not
  helper-function, granularity. First extract dual-open replacement ownership; then extract a capture
  admission/result policy that returns typed decisions and status identities. Keep CameraEngine as
  wiring/orchestration, but require each native lifecycle to have one identity-owned terminal and one
  exhaustive transition test matrix.

## Final architecture sweep

I rechecked module direction, process-lifetime singleton ownership, executor capacity, lock ordering,
route/profile isolation, callback leases, Ready/session publication, recorder and EGL terminals,
MediaStore family serialization, and documentation/build authority flow. No additional current
cross-layer issue met the evidence threshold after excluding completed historical plans and explicit
device-only field checks.

## Totals

- New findings: 3
- Severity: 1 High, 2 Medium
