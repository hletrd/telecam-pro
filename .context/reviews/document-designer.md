# Cycle 41 document-specialist + native Android designer review

Date: 2026-08-24

Reviewed revision: `4e4c9dfbce294fb2965a56ea63d74d6096744836`

Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle41.nWoiMj`

Mode: static/host-only Android Compose, resources, tests, assets, and documentation review; no
device behavior or deployment is claimed. Browser automation is not applicable to this native
Android UI.

## Scope and inventory

I read the committed clean-clone authorities first: `CLAUDE.md`, `docs/ARCHITECTURE.md`,
`docs/FIELD_CHECKS.md`, `README.md`, `PRIVACY.md`, the Play submission/data-safety material, and
`device-tests/README.md`. The requested `docs/BACKLOG.md` and `docs/UX_POLICY.md` are absent, which
`CLAUDE.md:3-7` explicitly permits; the committed architecture, field ledger, and the quiet-viewfinder
paragraph at `CLAUDE.md:54-59` are therefore the applicable fallback authority. I inventoried all 58
tracked documentation/resource-asset paths, including every historical plan, the Play screenshot
validity manifest, six phone and four tablet screenshots, SVG sources, launcher resources, fonts,
privacy copy, and EN/KO strings.

For the designer pass I examined all production/debug Compose surfaces and their UI-facing state,
policy, action, resource, manifest, and theme seams: the 102 production Kotlin/Java modules, all
`ui/**` and debug snapshot code, both string trees, all 227 host/instrumented tests (with focused
inspection of every UI/Compose/accessibility/responsive/review test), and the device-harness UI
contract. The pass covered information architecture, Sony-style visual quietness, affordances and
48 dp targets, TalkBack semantics/focus/modal ownership, guide and HUD contrast, phone/tablet and
held-orientation behavior, loading/empty/error/delete states, EN/KO localization, RTL ownership, and
review/viewfinder perceived-performance paths.

`python3 tools/check_docs.py` passed all 125 public checks (24 optional-private checks skipped). EN
and KO resources have exact placeholder parity and every translatable default string has a Korean
peer. Focused Compose tests for selector/manual-ruler semantics, bilingual presentation, viewfinder
accessibility, permission responsiveness, non-touch review, and instrument accessibility passed.
The screenshot manifest truthfully remains `submission_ready: false` and names the two stale phone
captures, so no checked-in historical screenshot was treated as current UI evidence.

Current Android guidance was checked against the primary Android Developers Compose semantics,
radio-button, and API-default documentation. It confirms that semantic role/state/action are
separate properties, exclusive choices should use `selectableGroup` plus radio-button selection
semantics, and interactive targets should be at least 48 dp. The bundled Material3 1.4.0 source is
also relevant below: `FilterChip` delegates to `SelectableChip`, which passes `selected` into
`Surface` and separately applies `Role.Checkbox`; replacing only the role does not remove the
selection property.

## Findings

### DD41-01 — one-shot action chips still expose persistent selection state

- **Severity / confidence:** Medium / High
- **Status:** Confirmed accessibility interaction-model defect; the cycle-40 role-only fix is
  incomplete.
- **Exact regions:** MR write action
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:734-759`; custom-WB measurement
  action `ProSheet.kt:1019-1034`; incomplete regression
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/SelectorRoleSemanticsComposeTest.kt:65-94`.
- **Problem:** `MemoryPresetAction` is a `FilterChip(selected = false)` with only
  `role = Role.Button` overlaid. The role changes, but the Material selectable surface still exports
  `SemanticsProperties.Selected=false`. The test asserts `Role.Button` but never asserts that
  `Selected` is absent, so it passes while the command retains persistent-selection state. The
  adjacent **Capture Custom WB** command is an even clearer instance: it remains an unmodified
  FilterChip whose checkbox/selected state is tied to whether WB mode is CUSTOM, although tapping it
  performs a fresh camera measurement rather than toggling that mode. This conflicts with the
  current Compose guidance that role, state, and action are distinct semantics, and with the code's
  own stated goal that these controls be immediate commands rather than persistent choices.
- **Concrete failure scenario:** A TalkBack or Switch Access user reaches **Save**/**Update** and the
  accessibility node still carries “not selected” even though activating it writes an MR bank. They
  then reach **Custom WB measurement**, which is presented as a checked/unchecked checkbox depending
  on the already-selected WB mode; double-tapping unexpectedly starts a measurement. Role-only tests
  remain green because neither retained selection property is checked.
- **Suggested fix:** Render immediate commands with a button/action primitive (`AssistChip`, an
  on-click `Surface`, or an equivalent visual wrapper) rather than `FilterChip`. Keep any desired
  active visual tint independent of accessibility selection. Extend composed-tree tests for both MR
  Save/Update and Custom WB to require `Role.Button`, an OnClick action, correct disabled state, and
  the **absence** of both `Selected` and toggleable state. Primary references:
  [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics),
  [radio-button pattern](https://developer.android.com/develop/ui/compose/components/radio-button),
  and [Material3 `SelectableChip` source](https://android.googlesource.com/platform/frameworks/support/+/828c1e3849b371f3317649df0655543620c915a2/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Chip.kt#1991).

### DD41-02 — the as-built Module Map omits the new production Java durability owner

- **Severity / confidence:** Low / High
- **Status:** Confirmed current-authority/code mismatch introduced by the cycle-40 lint fix.
- **Exact regions:** new owner
  `app/src/main/java/me/hletrd/telecampro/storage/SharedPreferencesDurableEdit.java:5-21`;
  production consumers
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:429-438,1228-1233,1407-1415`;
  storage Module Map `docs/ARCHITECTURE.md:107-112`; checker blind spot
  `tools/check_docs.py:862-875`.
- **Problem:** The architecture's as-built Module Map names every production Kotlin file and the
  checker explicitly treats that table as the production-module inventory, but cycle 40 added a
  production Java class that now owns fail-closed synchronous SharedPreferences writes. The class is
  absent from the storage section, and the checker enumerates only `app/src/main/kotlin/**/*.kt`, so
  the public design authority passes while omitting a real durability boundary used at multiple
  MediaStore state transitions.
- **Concrete failure scenario:** A maintainer auditing why these writes deliberately use synchronous
  `commit()` follows the architecture to `MediaStoreWriter`/`PendingDiscardJournal` and never sees the
  Java bridge. They remove it as an unexplained lint workaround or replace it with Kotlin KTX
  `edit(commit = true)`, losing the Boolean failure result that makes REGISTERED/COMPLETE/DISCARD
  writes fail closed, while the documentation gate still reports complete module coverage.
- **Suggested fix:** Add `SharedPreferencesDurableEdit.java` to the storage map with its Boolean
  durability responsibility, and make the module-inventory check extension/language-neutral across
  production source roots (at least `app/src/main/{kotlin,java}`). Add a negative fixture proving a
  newly added Java production owner cannot be omitted.

### DD41-03 — live UI authority still documents the retired 0.55 guide weight

- **Severity / confidence:** Low / High
- **Status:** Confirmed source-documentation drift; runtime tokens/tests are correct.
- **Exact regions:** actual token and current rationale
  `app/src/main/kotlin/me/hletrd/telecampro/ui/theme/Theme.kt:128-147`; stale scope comparison and
  arithmetic `Theme.kt:148-162`; stale Loupe comparison
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:919-924`; stale OSD note
  `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:943-954`; executable regression
  `app/src/test/kotlin/me/hletrd/telecampro/ui/overlays/HudContrastTest.kt:258-271`.
- **Problem:** `CameraColors.GuideLine` and its test deliberately pin the current frame-line/grid
  alpha to **0.40**, after the 0.55 treatment was rejected as too heavy. Three nearby live comments
  still call the current guide weight 0.55: `ScopeFrame` says it differs from “GuideLine (0.55),” the
  Loupe border says 0.85 is compared with “the 0.55 GuideLine,” and the OSD note says the file's
  “other 0.55s” are now GuideLine. The theme comment also calls 0.30 versus 0.35 “one hundredth
  apart,” although the difference is five hundredths. These are not harmless historical labels:
  each sentence presents the retired number as the current visual-role contract.
- **Concrete failure scenario:** A designer tuning the Loupe border or scope hierarchy uses the
  nearest live rationale and preserves a 0.30/0.55 contrast gap, or restores grid/frame lines to
  0.55 to match it. That undoes the measured quiet-viewfinder restyle while `HudContrastTest` catches
  only the final token value, not the misleading comparative authority that prompted the change.
- **Suggested fix:** Change every current comparison to 0.40, correct “one hundredth” to “five
  hundredths,” and add a narrow documentation/source contract rejecting an active
  `GuideLine (0.55)` or `0.55 GuideLine` claim while the token remains 0.40.

## Final missed-issue and file sweep

I re-swept every production/debug UI composable and helper, every click/select/toggle/pointer owner,
all semantic names/roles/states/custom actions, both localization trees and format placeholders,
manifest/theme/RTL/locale generation, all UI-focused tests, the native device-harness UI contract,
every committed current/public document, historical-plan status/deferred records, privacy/Play
declarations, and the cycle-40 changed surface. The 48 dp target policy, modal focus restoration,
single durable viewfinder identity, coarsened level semantics, bounded review pan/double-tap
recognition, responsive permission/settings/Fn layouts, loading/empty/error/delete states, and
current screenshot staleness disclosures are otherwise internally consistent at this revision.

No pixel, TalkBack speech, camera output, physical contrast, rotation, or gesture-feel claim was
inferred from host code. The committed field ledger truthfully keeps A3, A4, D1, E1, and E2 open;
the cycle-35 broad `CameraEngine` decomposition remains explicitly deferred under its recorded exit
criterion and is not re-filed here.

**Finding count: 3 total — 1 Medium, 2 Low; all High confidence.**
