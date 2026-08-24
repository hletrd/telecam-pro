# Verifier review — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4d` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Scope and evidence

- Read `CLAUDE.md` completely, plus the committed current authorities
  `docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, and `README.md`.
- Inventoried the complete clean-clone implementation/test surface: 101 production Kotlin files,
  217 JVM/Robolectric test files, four instrumented-test files, three debug Kotlin files, 34 Python
  or shell tool/harness files, 15 resource files, and 55 committed Markdown files. Reviewed the
  cycle-34 implementation delta and its cross-file contracts, then swept the whole tree for stale
  assertions, ignored tests, blocking waits, and unowned test/tool behavior.
- Ran the authoritative non-device gate `python3 tools/verify_host.py`. Android debug assembly,
  androidTest packaging, all JVM/Robolectric/Compose tests, lint, and exact Partition-A coverage
  passed (`7993/8008`, 99.81%, with the exact 15-line residual manifest). The gate then failed in
  the Python tool-contract suite; evidence is detailed in the test-engineer report.
- Re-read the documentation checker, its committed-export harness, cycle-33/cycle-34 completed
  plans, and the exact failing regression. No device-only camera/GL behavior is claimed from host
  evidence.

## Findings

### VER35-01 — “latest completed plan” is chosen by filename lexicography, not chronology

- **Severity / confidence / status:** Medium / High / Confirmed latent correctness defect
- **Evidence:** `tools/check_docs.py:978-988` sorts `docs/plans/*.md` as `Path` values and treats the
  last completed path as the newest. The cycle suffix is not zero-padded. A direct reproduction
  sorts same-date names as `cycle100`, `cycle34`, `cycle99`, so cycle 99 wins over cycle 100.
  `docs/plans/2026-08-24-rpf-cycle34.md:77-84,120-125` describes this check as enforcing the newest
  repository-wide green claim, which the implementation cannot guarantee across the numeric-width
  boundary.
- **Failure scenario:** this review loop explicitly supports up to 100 cycles. If cycles 99 and 100
  complete on the same date, a cycle-100 plan can omit or falsify the authoritative host evidence
  while the checker validates cycle 99 and prints `ok latest completed implementation plan...`.
  The same class of error appears for any non-zero-padded numeric suffix with different widths.
- **Why it matters:** this is a fail-open provenance check on release/readiness evidence. It can
  bless a stale plan precisely at an allowed loop boundary rather than validating the newest claim.
- **Suggested fix:** parse the filename into `(date, numeric cycle)` (or carry an explicit sortable
  plan identity), reject ambiguous/malformed completed-plan names, and select `max` on the parsed
  key. Add fixtures for cycles 9/10 and 99/100 on the same date plus a later-date lower cycle.

## Final missed-issue sweep

The cycle-34 audio peak propagation, dual-open cancellation, settings migration, exported-debug
component policy, exact coverage additions, and AGP/R8 documentation changes agree across their
current production and focused test seams. I found no additional current verifier defect after
excluding already-fixed historical review items and device-only checks that remain explicitly open
in `docs/FIELD_CHECKS.md`.
