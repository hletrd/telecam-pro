# Verifier review — cycle 37

Date: 2026-08-24
Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)
Workspace: clean detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope and evidence

- Read the committed authorities `CLAUDE.md`, `docs/ARCHITECTURE.md`, and
  `docs/FIELD_CHECKS.md`; inventoried all 489 tracked paths: 101 production Kotlin files, 220
  JVM/Robolectric/Compose test files, four instrumented-test files, 32 Python files, 69 Markdown
  files, and the remaining Android resources/build metadata/assets. Historical review and plan
  records were used only to distinguish resolved findings from current behavior.
- Traced the cycle-36 dual-open, optimized device-runner, and active-affordance fixes through their
  production callers and focused tests. The nullable/terminal dual-open matrix is now total, and
  `device-tests/run.py` rejects optimized execution before both outer snapshot work and child
  imports; those cycle-36 findings are resolved and are not repeated below.
- Ran `python3 tools/verify_host.py`: Android debug assembly, androidTest packaging, all
  JVM/Robolectric/Compose tests, lint, exact Partition-A coverage, 96 tooling tests, nine
  coverage-tool tests, 184 device-harness self-tests, 112 documentation checks, Python compilation,
  and the final diff check all passed. Partition A is 8030/8045 lines (99.81%); all 15 misses match
  the reviewed residual manifest. No device behavior is inferred from this host result.
- Reproduced VER37-01 without changing the tree by executing `tools/check_docs.py` from its exact
  source while substituting `ZSL_MAX_FRAME_AGE_NS = 400_000_001L` in the read stream. Normal
  compilation failed with `AssertionError: ZSL frame age must be an exact millisecond fact`;
  optimized compilation (`optimize=2`, the semantics of `-O`) exited 0 with `112 checks, 0 failed`.

## Findings

### VER37-01 — optimized Python can false-green the authoritative host documentation gate

- **Severity / confidence / status:** Medium / High / Confirmed
- **Exact evidence:** `tools/verify_host.py:50-98` has no `sys.flags.optimize` guard and forwards the
  current environment while invoking every Python suite and `tools/check_docs.py` through
  `sys.executable` (`:79-82`). `tools/check_docs.py:360-365` enforces the source freshness constant's
  exact-millisecond representation with a plain `assert`, then integer-divides it before comparing
  the resulting `400` token with the authorities. Python removes that assertion under `-O` or
  `PYTHONOPTIMIZE`. The repository has an optimized-mode guard/regression for the *device runner*,
  but no corresponding host-gate/tool guard.
- **Failure scenario:** CI or an operator runs `python -O tools/verify_host.py` or inherits
  `PYTHONOPTIMIZE=1`. A source edit from `400_000_000L` to `400_000_001L` (or any non-millisecond
  value that still floors to 400) violates the explicit documentation contract, but the checker
  reports all 112 checks green and the authoritative host gate can complete successfully. This is
  distinct from resolved cycle-36 finding VER36-01: device evidence is now protected, while the
  consolidated host authority is not.
- **Suggested fix:** reject `sys.flags.optimize != 0` at the outer `verify_host.py` entry and replace
  correctness-bearing `assert` statements in `check_docs.py` with always-on checks/exceptions. Add
  subprocess regressions for both `python -O` and environment-only `PYTHONOPTIMIZE=1` using a
  committed-export fixture whose ZSL constant is deliberately non-millisecond; require refusal
  before a green documentation summary.

### VER37-02 — the committed ZSL freshness contract excludes a boundary the implementation admits

- **Severity / confidence / status:** Low / High / Confirmed documentation/behavior mismatch
- **Exact evidence:** `camera/ZslAdmission.kt:87-90` rejects only `ageNs >
  ZSL_MAX_FRAME_AGE_NS`, so the 400,000,000 ns boundary is admitted. The focused production test
  explicitly pins that behavior at `camera/ZslAdmissionTest.kt:93-98`: exactly the maximum is true,
  maximum plus one nanosecond is false. `CLAUDE.md:219-221` and
  `docs/ARCHITECTURE.md:68` instead promise `age < 400 ms`. The implementation's own comment at
  `ZslAdmission.kt:28-34` says `<=0.4 s-old`, and `CameraEngine.kt:4088` says “up to 400 ms,” so the
  inclusive code/test contract has stronger internal evidence.
- **Failure scenario:** a frame exactly 400 ms old is served although both top-level authorities say
  it is outside the admissible set. The one-nanosecond boundary has negligible photographic impact,
  but it is a real current contract error and makes exact verification claims internally
  contradictory.
- **Suggested fix:** align the authorities to `age <= 400 ms` (the measured/code/test intent), then
  make `check_docs.py` validate the comparator as well as the number. If strict exclusion is instead
  intended, change the predicate to reject `>=` and reverse the existing boundary assertion; do not
  leave code and authority with different sets.

## Final missed-issue sweep

No ignored/disabled tests, vacuous constant assertions, unresolved TODO/FIXME markers, unexpected
Partition-A misses, or additional current dual-open/device-runner regressions were found. The five
physical checks A3/A4/D1/E1/E2 remain explicitly open in `docs/FIELD_CHECKS.md`; they are not host
failures and were not promoted to findings. No source, plan, Git, or device state was changed.

## Totals

- Current findings: 2
- Severity: 1 Medium, 1 Low
- Confidence: 2 High
- Resolved historical findings rechecked and not repeated: 3 cycle-36 root causes
