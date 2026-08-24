# Test-engineer review — cycle 36

Date: 2026-08-24
Reviewed revision: `1f45887` (`origin/main`)
Workspace: isolated worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Scope and test inventory

- Read the committed project authorities and all prior cycle-35 review/plan evidence before
  reviewing current tests. Inventoried all 218 JVM/Robolectric/Compose tests, four androidTest
  probes, 32 Python tool/harness sources, 96 tool tests, nine coverage-tool tests, and 183 host-side
  device-harness tests; inspected their corresponding production seams rather than trusting test
  names or comments.
- Reviewed every tracked path through the all-file inventory and systematic static sweeps, with
  focused tracing of concurrency gates, media ownership, Camera2 route/session fallbacks, GL/review
  transforms, settings/localization, release provenance, and device-harness attestation. Checked for
  ignored/disabled tests, vacuous assertions, random/unseeded inputs, wall-clock sleeps, false-green
  fallback paths, and missing boundary cases.
- Ran the complete authoritative host gate successfully. Partition A remains 99.81% with the exact
  reviewed 15-line residual manifest; all configured host test/tool/doc gates are green. The four
  androidTest files are compilation/package probes only, as the repository correctly documents.

## Finding

### TEST36-01 — the harness has no optimized-interpreter regression despite assert-owned verdicts

- **Severity / confidence / status:** High / High / Confirmed false-positive test mode
- **Exact evidence:** `device-tests/cases.py` contains 315 production `assert` statements, including
  essential device outcomes at `:2005-2011` (process, live preview, OSD, fatal logs) and
  `:2322-2343` (recording admission, codec, source/encoder raster, bitrate, transfer, exact NTSC FPS).
  `device-tests/dtest/framework.py:133-168` translates `AssertionError` into FAIL but otherwise
  reports PASS. `device-tests/run.py:547-566` forks and `runpy`-executes the immutable snapshot in
  the same interpreter without checking `sys.flags.optimize`. The 183 self-tests contain no
  optimized-mode test or guard contract.
- **Concrete failure scenario:** `PYTHONOPTIMIZE=1 python3 device-tests/run.py ...` strips the checks
  while leaving case setup, taps, captures, notes, report generation, and attestation active. A case
  can therefore exercise the device, observe an invalid result, return normally, and be counted as
  PASS. A focused framework reproduction with a body containing only `assert False` produced one
  pass and exit code 0 under `PYTHONOPTIMIZE=1`.
- **Why existing coverage is false confidence:** `python3 tools/verify_host.py` runs all 183 harness
  self-tests in the normal interpreter, so the suite proves behavior only while assertions exist.
  It does not prove the evidence runner refuses a mode that removes the very checks being tested.
- **Suggested TDD fix:** first add subprocess tests in `device-tests/tests/test_attestation.py` (or a
  focused runner-contract test) that launch both `python -O` and an environment-only
  `PYTHONOPTIMIZE=1` path, inject sentinels proving APK/ADB preflight was not reached, and require a
  non-green diagnostic. Then add an always-on optimize guard at both outer and inherited child
  boundaries. Add a small explicit `require(condition, detail)` evidence helper and migrate the 315
  case verdicts incrementally so correctness does not rely solely on interpreter mode.

## Final coverage sweep

No ignored/disabled tests, vacuous `assertTrue(true)`/`assertFalse(false)` checks, or unseeded random
tests were found. The one real `Thread.sleep(1)` in `StandbyAudioControllerTest` is inside a bounded
deadline loop and the surrounding ownership assertions are latch/terminal based, so it is not a
standalone current flake finding. Apart from TEST36-01, current cycle-35 behavior has direct focused
coverage and the full host gate is green; device-only claims remain correctly open in
`docs/FIELD_CHECKS.md` rather than being promoted by host tests.
