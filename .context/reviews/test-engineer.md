# Test-engineer review — cycle 37

Date: 2026-08-24
Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)
Workspace: clean detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope and test inventory

- Inventoried all 220 JVM/Robolectric/Compose test files, four androidTest probes, 32 Python
  tool/harness sources, 96 tooling tests, nine coverage-tool tests, and 184 host-side device-harness
  tests. Reviewed the corresponding production seams rather than treating names or line coverage as
  behavioral proof.
- Ran the complete `python3 tools/verify_host.py` gate successfully. The Android test APK was
  compiled/packaged but not run; Partition A remains 99.81% (8030/8045) with the exact reviewed
  15-line residual manifest. No device-only result is claimed.
- Rechecked the cycle-36 fixes: `CameraControllerRestorabilityTest` plus the identity-derived
  `DualOpenWaitTest` cases cover the absent and terminal outgoing-owner states, and
  `RunAttestationTest.test_optimized_interpreters_refuse_before_snapshot_or_device_preflight`
  covers both `-O` and `PYTHONOPTIMIZE` for device evidence. Those tests match current production
  behavior.

## Findings

### TEST37-01 — no test prevents optimized execution from weakening the consolidated host gate

- **Severity / confidence / status:** Medium / High / Confirmed false-positive test mode
- **Exact evidence:** `tools/verify_host.py:79-82` runs Python suites and `tools/check_docs.py` with
  the same `sys.executable` and inherited environment, but the entry point has no optimized-mode
  refusal. `tools/check_docs.py:360-365` uses a removable plain `assert` for the exact-millisecond ZSL
  source invariant. The only optimized-interpreter regression is device-specific at
  `device-tests/tests/test_attestation.py:173-207`. The closest documentation regression,
  `tools/tests/test_tool_contracts.py:331-367`, mutates only displayed document text and always
  launches the checker normally; it does not mutate the source constant's precision or run the host
  gate/checker under optimization.
- **Concrete reproduction:** with an in-memory source substitution of
  `ZSL_MAX_FRAME_AGE_NS = 400_000_001L`, normal `check_docs.py` compilation raised the intended
  exact-millisecond assertion, while `optimize=2` exited 0 and printed `112 checks, 0 failed, 24
  private checks skipped`. No repository files were changed for this proof.
- **Why existing green coverage is insufficient:** the normal authoritative gate proves only that
  the assertion exists in the normal interpreter. It does not prove the gate refuses an interpreter
  mode that deletes that check. Cycle 36 fixed this class only at `device-tests/run.py`; the broader
  gate remains independently callable under `-O`/`PYTHONOPTIMIZE`.
- **Suggested TDD fix:** add subprocess tests around `tools/verify_host.py` and the committed-export
  documentation fixture for both optimization entry paths, with a non-millisecond source constant
  that must remain non-green. Then add an outer optimization guard and migrate tool invariants from
  `assert` to explicit always-on validation.

### TEST37-02 — the docs test checks the ZSL number but misses its contradictory comparator

- **Severity / confidence / status:** Low / High / Confirmed coverage gap over a current mismatch
- **Exact evidence:** the production boundary test at
  `camera/ZslAdmissionTest.kt:93-98` proves the maximum-age frame is admitted. Production implements
  that inclusive set at `camera/ZslAdmission.kt:87-90`. In contrast, `CLAUDE.md:219-221` and
  `docs/ARCHITECTURE.md:68` specify strict `< 400 ms`. `tools/check_docs.py:366-375` extracts only
  the integer from a hard-coded `age < (\d+) ms` regex, and
  `tools/tests/test_tool_contracts.py:339-367` proves only that changing `400` to `250` fails. There
  is no test comparing the predicate's inclusive/exclusive boundary with the prose operator.
- **Failure scenario:** the test suite is green while code/tests admit exactly 400 ms and both
  authorities exclude it. A future comparator edit can likewise retain the same numeral and evade
  the documentation gate completely.
- **Suggested TDD fix:** make the source/doc contract expose or parse both maximum and inclusivity,
  add a fixture that changes `<=`/`<` without changing `400`, and require the documentation gate to
  fail. Align current prose to the existing inclusive production test unless product intent says to
  tighten the runtime predicate.

## Final coverage and flake sweep

No ignored/disabled tests, vacuous `assertTrue(true)`/`assertFalse(false)` checks, unseeded behavioral
randomness, or new race-dependent failures were found. The suite's UUID use is isolation-only; the
remaining bounded waits are terminal/latch-based, and repeated device-harness self-tests remained
green. The androidTest tier is packaging-only here, exactly as the repository documents.

## Totals

- Current findings: 2
- Severity: 1 Medium, 1 Low
- Confidence: 2 High
- Authoritative host gate: PASS (device execution not run)
