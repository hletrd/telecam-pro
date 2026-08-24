# Document specialist review — cycle 35

Date: 2026-08-24

Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518`

Workspace: clean detached cycle worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Coverage

Read the committed clean-clone authorities in full: `CLAUDE.md`, `docs/ARCHITECTURE.md`,
`docs/FIELD_CHECKS.md`, `README.md`, `PRIVACY.md`, the public privacy-policy body,
`docs/play-console-submit.md`, `docs/play-data-safety.md`, and `device-tests/README.md`. Cross-checked
their build, permission, release, field-evidence, camera-route, media-review, accessibility, and
localization claims against the version catalog, Gradle/manifest configuration, production UI and
storage code, English/Korean resources, current tests, and the latest completed plans. The optional
private UX/backlog/testing documents and `.claude/agents/qa-adversary.md` are absent in this clean
worktree, as the committed fallback policy permits. `python3 tools/check_docs.py` reports 107 checks
green with 24 private checks skipped; the two findings below are gaps in that checker rather than
already-reported gate failures.

## Findings

### DOC35-01 — the current architecture quick reference still advertises AGP 9.3.1

- **Severity / confidence / status:** Low / High / Confirmed
- **Evidence:** `docs/ARCHITECTURE.md:1247-1253` calls the section a current quick reference and
  lists AGP 9.3.1. The actual catalog is AGP 9.3.2 at `gradle/libs.versions.toml:1-6`; the primary
  toolchain authorities already agree at `CLAUDE.md:63-70` and `README.md:137-144`. Cycle 34
  explicitly upgraded the patch in `docs/plans/2026-08-24-rpf-cycle34.md:60-64`.
- **Concrete failure:** a clean-clone maintainer following the architecture rather than the catalog
  receives stale toolchain guidance immediately after the repository's latest-stable upgrade. It
  also defeats the purpose of the architecture's “current design authority” heading.
- **Fix:** update the architecture line to 9.3.2 and extend the documentation gate so every active
  toolchain table/quick reference is compared mechanically with `gradle/libs.versions.toml`, not only
  the README and CLAUDE Compose entries.

### DOC35-02 — architecture points to a logical-camera exposure field check that does not exist

- **Severity / confidence / status:** Medium / High / Confirmed
- **Evidence:** `docs/ARCHITECTURE.md:566-587` says a logical-camera 4-second-ceiling bisect
  “remains open in the committed docs/FIELD_CHECKS.md.” The field ledger's exact dashboard at
  `docs/FIELD_CHECKS.md:9-14` names only A3, A4, D1, E1, and E2 as remaining, and the body contains
  no logical-route long-exposure check (`docs/FIELD_CHECKS.md:75-103,198-251`). Its machine check
  correctly proves dashboard/body parity, but does not reconcile external open-work claims.
- **Concrete failure:** a clean-clone operator cannot execute or record the architecture's claimed
  open validation: there is no setup, pass criterion, identifier, or dashboard slot. Conversely,
  treating the field ledger as exhaustive silently drops the architecture's stated device-evidence
  obligation.
- **Fix:** decide the actual status. If the cross-route conservative clamp still needs physical
  validation, add a uniquely identified field check with safe setup/pass/fail criteria and include it
  in the dashboard. If the check was retired or closed, correct the architecture without inventing
  evidence. Add a docs contract that every active “open/remains open in FIELD_CHECKS” reference maps
  to an open/partial field-check identifier.

## Final missed-issue sweep

Rechecked active version claims, min/target/compile SDK values, release-wrapper paths, permissions,
ownerless-media wording, Loupe Overview honesty, EN/KO resource parity, field-check dashboard
membership, optional-private-context wording, and current-plan evidence after the main pass. No
other current documentation contradiction was confirmed; historical artifact and device statements
in `docs/play-console-submit.md` are explicitly scoped as superseded and were not re-filed.
