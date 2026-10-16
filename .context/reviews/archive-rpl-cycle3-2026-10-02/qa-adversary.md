# qa-adversary (QA3) — RPL cycle 3, HEAD e3a2bdd4, static gates only

(Saved by the orchestrator from the agent's message; the agent type cannot write files.)

Verdict: `python3 tools/verify_host.py` exit 1.

- QA3-1 (High for the gate / High): `python3 -m unittest discover -s tools/tests` — 168 tests, 1 error:
  `test_tool_contracts.ConsolidatedHostGateTest.test_documentation_gate_keeps_exact_millisecond_verdict_under_all_modes`
  raises `OSError: [Errno 66] Directory not empty: '.../staging/.git'` from `shutil.rmtree` in
  `TemporaryDirectory.__exit__` (second, `-O` call of `run_documentation_gate_from_committed_export`).
  Reproduced 3/3 via unittest, 0/2 as a bare script. verify_host.py aborts there (check=True) and
  never reaches coverage tests, device-tests, check_docs, compileall, diff --check.
- QA3-2 (Medium / High): `check_docs.py` on the live tree fails the password-property rule on two
  UNTRACKED `.context/reviews/**/security-reviewer.md` files; a clean `git archive HEAD` export passes
  160/160. (Same as DOC3-1.)
- Gradle leg: assembleDebug + androidTest assemble OK; 2365/2365 unit tests pass; lint 0 errors /
  1 warning `UsableSpace` at `ui/review/MediaReview.kt:456` (already deferred in cycle 2);
  Partition A 99.86% PASS; compileDebugKotlin FROM-CACHE so compiler warnings not re-printed.
- tools/coverage/tests 9/9, device-tests/tests 195/195, compileall and `git diff --check` clean.
- Static audit: 4 `@Suppress` sites all justified; no TODO/FIXME; no lint baseline; two documented
  lint `disable +=` entries (`OldTargetApi`, `ObsoleteSdkInt`).
