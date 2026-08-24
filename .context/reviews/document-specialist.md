# Document specialist review — cycle 36

Date: 2026-08-24

Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54` (`origin/main`)

Workspace: isolated worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Coverage

I read the complete committed authorities (`CLAUDE.md`, `docs/ARCHITECTURE.md`,
`docs/FIELD_CHECKS.md`), plus `README.md`, `PRIVACY.md`, the bundled privacy policy, Play
submission/data-safety material, device-harness documentation, every completed plan, current review
provenance, manifests/build configuration, version catalog, EN/KO resources, and the documentation
checker and fixtures. `python3 tools/check_docs.py` passed 112 public checks with 24 optional-private
checks skipped. All 486 tracked paths were inventoried; current claims were checked against code and
tests rather than accepted from comments. No device evidence was generated or inferred.

## Finding

### DOC36-01 — cycle 35's completion evidence overclaims an exhaustive dual-open matrix

- **Severity / confidence / status:** Medium / High / Confirmed current evidence mismatch, sharing
  the code root cause reported as CRIT36-01.
- **Exact regions:** plan promise and completion claim at
  `docs/plans/2026-08-24-rpf-cycle35.md:19-27,101-108`; production nullable identities at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3545,3580-3592,3746-3753,
  7037-7050`; test inputs at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/DualOpenWaitTest.kt:101-136`.
- **Problem:** The latest completed plan says the transition matrix covers candidate-current,
  candidate-cleared, and newer-controller states and proves no outgoing owner is lost. The tests
  instead pass three already-separated booleans. Production derives those booleans from nullable
  object identities. For the expressly admitted `old == null` path after candidate self-clear,
  `slotVacant` and `outgoingOwnsSlot` are both true because `null === null`; the helper throws on the
  exact state the completion note claims the matrix covers.
- **Concrete failure scenario:** A maintainer or later review trusts the newest completed plan as
  proof that candidate-cleared supersession is closed and therefore skips the missing cold/no-old
  permutation. The authoritative host gate remains green because the fixture never derives the
  booleans from nullable identities, while production can crash in that permutation.
- **Suggested fix:** Correct the code and add the production-shaped identity matrix, then append a
  truthful cycle-36 superseding completion note. Do not rewrite cycle-35 history; explicitly state
  that its Boolean-only matrix missed the null-alias case. Extend the completion-evidence review
  discipline so an “exhaustive transition matrix” claim cites a test that constructs the same typed
  state production consumes.

## No additional documentation drift

Active AGP/Kotlin/Gradle/Compose/SDK values agree with the catalog and wrapper; pseudo-ZSL truth is
400 ms everywhere active; every open FIELD_CHECKS reference resolves to A3/A4/D1/E1/E2; privacy
and Data Safety match the manifest and ownerless-media behavior; release docs consistently reject
mutable or stale artifacts; the two stale phone screenshots remain explicitly blocking and
hash-pinned. Architecture names every production Kotlin module and correctly scopes PMA110-specific
facts, DNG routing, Loupe Overview, large-screen rotation, and host/device evidence.

## Totals

- New documentation findings: 1
- Severity: 1 Medium
- Confidence: 1 High
