# Verifier review — cycle 36

Date: 2026-08-24
Reviewed revision: `1f45887` (`origin/main`)
Workspace: isolated worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Scope and evidence

- Read the complete committed authorities `CLAUDE.md`, `docs/ARCHITECTURE.md`, and
  `docs/FIELD_CHECKS.md`; compared current behavior with the cycle-35 aggregate, specialist reports,
  implementation plan, and implementation commits so fixed findings were not repeated.
- Inventoried all 486 tracked paths: 101 production Kotlin files, 218 JVM/Robolectric/Compose test
  files, four instrumented-test files, three debug Kotlin files, 32 Python files, 15 Android resource
  files, 68 Markdown files, and the remaining build metadata/assets. Examined every text source and
  cross-file contract; binary Play assets and packaged outputs were independently covered by the
  repository's validity/provenance gates.
- Traced the newest dual-open ownership, capture-refusal status, audio-overload, EXIF-transform, and
  completed-plan-evidence fixes through their production callers and focused tests. Swept the whole
  tree for ignored/disabled tests, vacuous assertions, stale TODOs, timing dependencies, unchecked
  failure paths, and documentation/code drift. No ignored/disabled tests or unresolved TODO/FIXME
  markers were found.
- Ran `python3 tools/verify_host.py`. It passed Android debug assembly, androidTest packaging, all
  JVM/Robolectric/Compose tests, lint, exact Partition-A coverage (8015/8030, 99.81%), 96 tooling
  tests, nine coverage-tool tests, 183 device-harness self-tests, 112 documentation checks, Python
  compilation, and the final diff check. No device-only behavior is inferred from that host result.

## Finding

### VER36-01 — optimized Python can attest device failures as passes

- **Severity / confidence / status:** High / High / Confirmed
- **Exact evidence:** `device-tests/run.py:15-30,547-566` imports `sys` but never rejects
  `sys.flags.optimize != 0`; the immutable child is forked and executed with `runpy` in the same
  interpreter, so it inherits the outer interpreter's optimization level. The real device verdicts
  use 315 plain `assert` statements in `device-tests/cases.py` (representative launch contract at
  `device-tests/cases.py:1997-2011` and recording/container contract at `:2305-2343`).
  `device-tests/dtest/framework.py:132-168` marks a case PASS when its function returns and only
  recognizes a failed check by catching `AssertionError`; under `python -O` or
  `PYTHONOPTIMIZE=1`, those assertions are removed before execution.
- **Concrete reproduction:** with `PYTHONOPTIMIZE=1`, a registered smoke case whose body is only
  `assert False, "device evidence failed"` returned `PASS` and framework exit code 0. The production
  outer-child path cannot repair this because `fork` + `runpy` preserve the already-optimized code
  semantics. No test under `device-tests/tests/` mentions `PYTHONOPTIMIZE`, `sys.flags.optimize`, or
  `__debug__`.
- **Failure scenario:** an operator or CI environment sets `PYTHONOPTIMIZE=1` (or invokes
  `python3 -O device-tests/run.py`). A frozen/black preview, missing OSD, fatal camera log, wrong
  recording codec/raster/transfer/FPS, missing MediaStore output, or failed EXIF/container parity can
  pass through the stripped checks. The runner then writes a green report and attestation for device
  behavior that actually failed.
- **Suggested fix:** fail closed at the outermost device-runner entry before snapshot/device work
  whenever `sys.flags.optimize != 0`, and repeat the guard in the snapshotted child or make it part of
  the inherited proof validation. Add a subprocess regression for both `python -O` and
  `PYTHONOPTIMIZE=1` proving exit 2 before APK/ADB interaction. Longer term, replace evidence verdict
  `assert` statements with an explicit always-on contract exception/check helper; retain the runtime
  guard even then to prevent a future plain assertion from silently weakening evidence.

## Final missed-issue sweep

The cycle-35 fixes agree with their stated behavior and focused tests, and the authoritative host
gate is green. I found no additional current verifier defect after excluding previously fixed review
items and the explicitly open physical checks A3/A4/D1/E1/E2 in `docs/FIELD_CHECKS.md`. Every tracked
path was included in the inventory/static sweep; every production Kotlin module remains represented
in the Architecture module-map gate.
