# Cycle 41 code-reviewer + architect review

Reviewed revision: `4e4c9dfb` in the isolated clean clone
`/tmp/find-x9-ultra-cycle41.nWoiMj`.

## Coverage

- Read `CLAUDE.md` first, then the complete committed `docs/ARCHITECTURE.md` and
  `docs/FIELD_CHECKS.md` fallback authorities. `docs/BACKLOG.md` is absent, which `CLAUDE.md`
  explicitly permits in a clean clone.
- Inventoried all 499 tracked paths: production Android/Kotlin/Java and resources, debug and
  instrumentation surfaces, host/unit/Compose tests, device harness, build/release tooling,
  Gradle/configuration, privacy/store documentation, plans, and assets.
- Examined every production module through a file/declaration inventory, package dependency and
  ownership-boundary sweep, async/executor/monitor scan, exception/error-path scan, test-to-source
  cross-reference, and the full cycle-40 production/test change surface. Traced the major
  Engine/Controller/GL/recorder/storage/ViewModel ownership interactions against the architecture
  rather than trusting comments or tests.
- Ran `python3 tools/check_docs.py` (125 checks passed; 24 documented private checks skipped) and
  `python3 -m unittest discover -s tools/tests -p 'test_tool_contracts.py'` (33 tests passed).
- Finished with a missed-issues sweep over all production declarations, cross-package imports,
  broad exception handling, non-null assertions, TODO/FIXME markers, concurrency primitives, the
  latest plans/reviews, and the current clean working-tree state.

## Finding

### CA41-01 — MR Save/Update is still a selectable control wearing a Button role

- **Severity:** Medium
- **Confidence:** High
- **Classification:** Confirmed accessibility-semantics defect
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:736-759`
  implements the one-shot `MemoryPresetAction` with `FilterChip(selected = false)` and only adds
  `role = Role.Button` at line 749. Material3 `FilterChip` is a selectable component: its internal
  semantics still publish the `Selected=false` state (and selectable action model); changing only
  `Role` does not clear those other properties. The regression at
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/SelectorRoleSemanticsComposeTest.kt:65-94`
  asserts only `Role.Button` for Save/Update and never asserts that `SemanticsProperties.Selected`
  is absent, so the contradictory semantics pass the new test.
- **Concrete failure:** A TalkBack/Switch Access user focuses MR Save or Update and receives a
  control whose semantic state says “not selected” even though activation is an immediate write,
  not a persistent choice. Accessibility automation likewise sees a Button carrying selection
  state, so the cycle-40 fix changes the noun without fixing the interaction contract it was meant
  to repair.
- **Suggested fix:** Render the action with a genuinely click-only chip/button primitive (for
  example an appropriately styled `AssistChip`/custom clickable surface) so no selectable semantics
  exist. If the `FilterChip` visual must be retained, place it behind an outer semantics owner that
  clears descendant semantics and explicitly restores the localized name, Button role, enabled/
  disabled state, and click action. Extend the Compose regression to assert Button role and OnClick,
  assert that `SemanticsProperties.Selected`/toggle state are absent for both Save and Update, and
  cover the disabled action.

## Final sweep result

No additional current code-quality, logic, state-ownership, threading, error-handling, invariant,
coupling, or layering defect was confirmed. In particular, the cycle-40 durability bridge,
resource-shrinking switch, EXIF bitmap allocation change, shared system-bar helper, exclusive radio
selectors, output checkboxes, and ruler semantics remain consistent with their callers and current
architecture after cross-file review.
