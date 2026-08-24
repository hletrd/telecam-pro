# Document-specialist review — cycle 38

Date: 2026-08-24

Reviewed revision: `fa95299` (`origin/main`)

Workspace: isolated worktree `/private/tmp/find-x9-cycle38.FKvYBP`

## Scope

Inventoried all 490 tracked paths and read the committed authorities, field ledger, README, privacy
and Play material, current/historical plans, resources/manifests, relevant implementation/tests, and
prior review records. Optional private `docs/BACKLOG.md` and `docs/UX_POLICY.md` are absent, so no
private state is inferred. `tools/check_docs.py` passes 120 checks with 24 optional-private skips.

## Findings

### DOC38-01 — the completed Cycle 37 plan claims bright-frame selected-disabled coverage that does not exist

- **Severity / confidence:** Low / High.
- **Exact regions:** `docs/plans/2026-08-24-rpf-cycle37.md:47-52,88-100` marks a coherent
  selected-disabled fill plus native Compose coverage over bright and dark frames complete. The
  implementation at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:2835-2855,2912-2924` replaces the
  dark `HudPlate` with a 12%-white translucent fill for selected-disabled. The test at
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/AffordanceEdgeComposeTest.kt:65-107` checks
  only that state's token alpha; it renders no selected-disabled chip and uses only the dark
  `CameraColors.Pill` background.
- **Mismatch:** the durable completion record says the exact missing bright/dark state matrix was
  exercised, while the checked-in evidence neither renders nor protects it. On a bright live frame,
  the 12%-white container/border and 38%-white label all blend into the white scene because this
  branch has no dark contrast plate.
- **Suggested fix:** correct the selected-disabled composition and add the promised bright/dark
  render matrix. Keep the historical plan complete only once its concrete evidence is true.

### DOC38-02 — `finderRect` documentation advertises a bottom-inset control the implementation suppresses

- **Severity / confidence:** Low / High.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:671-691` says
  `bottomMargin` insets the PIP from the bottom, then marks that parameter
  `@Suppress("UNUSED_PARAMETER")`; `:693-719` derives `y` from `topAnchor` and
  `bottomClearance` instead. `FinderGeometryTest.kt:8-13,18-35,70-88` repeats the old independent
  bottom-clearance story while never asserting that changing `bottomMargin` changes position.
- **Mismatch:** maintainers are told they can tune a bottom margin, but it is an inert argument. The
  comments later in the function correctly explain that the top anchor/current clearance replaced
  the old approximation, making the opening KDoc and test header stale.
- **Suggested fix:** remove the dead parameter and rewrite the KDoc/test vocabulary around
  `topAnchor` plus measured `bottomClearance`, or restore an explicit and tested semantic for it.

## Final sweep

Toolchain versions, Android floor, ZSL inclusivity, stabilization/Gamma capability wording, privacy
facts, release status, Loupe exception, DNG routing, and open field checks agree with current source.
No additional current-authority drift survived.

## Totals

- New findings: 2
- Severity: 2 Low
- Confidence: 2 High
