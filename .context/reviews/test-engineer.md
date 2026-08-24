# Test-engineer review — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4d` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Scope and test inventory

- Read `CLAUDE.md` completely and the required architecture/field-check authorities. Inventoried
  all 217 JVM/Robolectric tests, four androidTest probes, tool/coverage tests, and the 183-test
  device harness represented by the authoritative host gate.
- Compared every cycle-34 production change with its focused tests: dual-open wait ownership,
  standby/recording PCM peak truth, settings migration, debug manifest protection, review-position
  accessibility, exact residual ownership, R8 documentation, and AGP verification metadata.
- Ran `python3 tools/verify_host.py` from the clean detached revision. Gradle build, lint, all Android
  host tests, and exact coverage passed. The Python tool suite ran 92 tests and failed one; because
  the gate stops on first failing suite, coverage-tool tests, device-harness self-tests,
  documentation checks, Python compilation, and the final diff check were not reached by that run.

## Findings

### TEST35-01 — the new negative documentation fixture is stale and makes the authoritative gate red

- **Severity / confidence / status:** High / High / Confirmed current failure
- **Evidence:** `tools/tests/test_tool_contracts.py:345-360` hardcodes
  `docs/plans/2026-08-24-rpf-cycle33.md`, removes `python3 tools/verify_host.py`, and expects the
  checker to fail. But cycle 34 is now the latest completed plan
  (`docs/plans/2026-08-24-rpf-cycle34.md:5,77-84,120-125`) and still contains that command.
  `tools/check_docs.py:978-988` correctly ignores the mutated older plan under its current ordering,
  returns success, and makes the test fail at line 357. The observed gate ends with
  `FAILED (failures=1)` and `CalledProcessError`; all preceding Gradle tasks and Partition-A
  coverage were green.
- **Failure scenario:** every clean invocation of the repository's authoritative
  `python3 tools/verify_host.py` fails after roughly 39 seconds even though the checker behaves
  consistently with the current fixture. A developer cannot produce the required green host gate,
  and later non-device suites are skipped. The cycle-34 completion note therefore became stale as
  soon as the new completed plan made cycle 33 cease to be the checker target.
- **Suggested fix:** make the fixture discover and mutate the same latest completed plan selected by
  production instead of naming cycle 33. Prefer extracting one parsed plan-order helper shared by
  checker and tests; at minimum determine the target dynamically inside the exported tree. Assert
  the unmodified exported baseline succeeds before mutation, then assert removing the command from
  the selected newest plan fails. Re-run the full authoritative gate after committing/updating a
  completed plan, not only before its closeout document exists.

### TEST35-02 — no test covers numeric cycle ordering at the supported 100-cycle boundary

- **Severity / confidence / status:** Medium / High / Confirmed coverage gap with reachable failure
- **Evidence:** `tools/check_docs.py:978-983` relies on lexical path order, while the plan filenames
  use unpadded suffixes. `tools/tests/test_tool_contracts.py:345-360` tests only one hardcoded
  historical path and has no multi-plan ordering fixture. The concrete ordering
  `cycle100 < cycle34 < cycle99` demonstrates that the checker selects cycle 99 after cycle 100 on
  the same date.
- **Failure scenario:** the supported `/review-plan-fix` maximum reaches cycle 100; a bad cycle-100
  completion claim is not tested because the gate continues checking cycle 99. Existing tests pass
  once TEST35-01's stale path is updated unless this boundary is added explicitly.
- **Suggested fix:** table-test 9/10, 34/35, and 99/100 same-date pairs plus a later-date lower cycle;
  verify that only the parsed newest completed plan is mutated/checked and that incomplete newer
  plans are intentionally excluded.

## Final missed-issue sweep

The cycle-34 focused Android tests cover the intended success, boundary, and rollback branches for
the changed runtime code, and the exact residual manifest remains synchronized. I found no ignored
tests or stale already-fixed runtime findings worth re-reporting. Device-only behavior remains
truthfully separated in `docs/FIELD_CHECKS.md`; host tests cannot close those open measurements.
