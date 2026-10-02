# QA adversary — RPL cycle 5 (static gates only, HEAD 72e9c75f tree / ea7d4374 code)

The qa-adversary subagent stalled without returning (same as cycles 1, 2, 4). The orchestrator ran
the gate directly once, as the coordinator asked. No device work was allowed.

## Gate

`python3 tools/verify_host.py` (JAVA_HOME = Homebrew openjdk@21): **exit 0**.

- Unit tests: 2507 run, 0 failures, 0 errors, 0 skipped (`app/build/test-results/testDebugUnitTest`).
- `:app:lintDebug`: 0 errors, 1 warning (`UsableSpace`, `ui/review/MediaReview.kt`; deferred
  AGG4-77/AGG3-61).
- `:app:lintRelease`: NOT run, the tree was dirty (review-file moves). Printed as a NOTE only; this is
  AGG5-19.
- Kotlin warnings: fatal, none.
- Partition A coverage: green, 14 reviewed residual lines across 9 classes, manifest fingerprints exact.
- tools 204 OK, coverage 21 OK, device-tests 195 OK (the fixture FAIL/ERROR lines are self-test output
  of the device harness, already classified as AGG4-83).
- `tools/check_docs.py`: 194 checks, 0 failed.
- `git diff --check`: clean.

## Adversarial sweep

- `TODO`/`FIXME` in `app/src/main`: 0.
- `@Suppress` in `app/src/main`: 11 sites; `tools:ignore`: 1; no lint baseline file. No new
  suppression since cycle 4's A.25/B.11 justifications.
- EN/KO string parity: 487 translatable EN = 487 KO (document-specialist, confirmed).

## Findings

| ID | Sev / Conf | Finding |
|---|---|---|
| QA5-1 | Low / High | Same as AGG5-19: a dirty-tree gate exits 0 without release lint; this cycle's own first gate run is such a run. |
| QA5-2 | Info / High | `UsableSpace` warning unchanged (deferred AGG4-77). |
