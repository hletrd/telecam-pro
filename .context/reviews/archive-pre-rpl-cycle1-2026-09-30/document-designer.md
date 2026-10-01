# Cycle 42 document-specialist + native Android designer review

Date: 2026-08-24

Reviewed revision: `70ebb8759b567dcd2ee13bd51b226da2568ff6d7`

Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle42.rPLjyN`

Mode: static/host-only Android Compose, resources, tests, assets, and documentation review. No
device behavior, physical contrast, TalkBack speech, camera output, or deployment is claimed.
Browser automation is inapplicable to this native Android UI.

## Scope and inventory

I read the committed clean-clone authorities first: `CLAUDE.md`, `docs/ARCHITECTURE.md`,
`docs/FIELD_CHECKS.md`, `README.md`, `PRIVACY.md`, `docs/play-console-submit.md`,
`docs/play-data-safety.md`, and `device-tests/README.md`. The requested `docs/BACKLOG.md`,
`docs/UX_POLICY.md`, and `docs/TESTING.md` are absent; `CLAUDE.md:3-7` explicitly permits this and
makes the committed architecture, field ledger, and quiet-viewfinder paragraph the applicable
fallback authority. I also checked every historical plan and retained review before classifying a
finding, including cycle 41's accepted fixes and the standing deferred Engine-decomposition item.

The repository inventory contains 504 tracked paths: 102 production Kotlin/Java modules, all 30
production `ui/**` Kotlin files, all three debug UI/snapshot hosts, both locale trees, manifest/theme,
launcher/font/store assets, 240 host/instrumented/harness/tool tests, and 96 tracked authority,
review, plan, privacy, and documentation/asset paths. I examined every production/debug Compose
surface and its state/action/policy/resource seams, and swept every interaction, semantics, focus,
keyboard/pointer, responsive-layout, modal, loading/empty/error/delete, localization, RTL, and visual
token site. The review covered information architecture, Sony-style quietness, affordance truth,
48 dp targets, TalkBack/Switch Access grouping and state, keyboard/D-pad paths, phone/tablet sizing,
held rotation, light/dark policy, EN/KO parity, perceived responsiveness, and review/viewfinder
design consistency. The screenshot validity manifest truthfully remains `submission_ready: false`,
so stale store screenshots were inventoried but not used as evidence of current pixels.

`python3 tools/check_docs.py` passed all 126 public checks (24 optional-private checks skipped).
EN/KO resources retain placeholder parity and complete translated peers under the repository's
declared exceptions. Current Android guidance was checked against primary Android Developers and
AndroidX sources: radio-style choices require `selectableGroup` on their containing group, while
Material3 `Surface(enabled = false)` disables click handling but does not choose alternate container
or content colors for callers.

## Findings

### DD42-01 — disabled immediate commands keep enabled-strength paint

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed source-level visual-affordance defect; exact device pixels need no
  inference because the supplied colors are identical across enabled states, but final appearance
  should still be snapshot/device-checked.
- **Exact regions:** shared command primitive
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:755-786`; Custom-WB call and
  mutually exclusive active/admission states `ProSheet.kt:1037-1057` plus
  `app/src/main/kotlin/me/hletrd/telecampro/camera/ControlAvailability.kt:144-150`; incomplete
  regression `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/SelectorRoleSemanticsComposeTest.kt:70-113`.
- **Problem:** Cycle 41 correctly replaced one-shot `FilterChip`s with click-only `Surface`s, but
  `ImmediateActionChip` derives its container/content colors only from `active`; `enabled` affects
  only click admission and the inactive border. A clickable Material3 `Surface` does not apply
  component-specific disabled colors. Therefore an inactive disabled MR Update retains full white
  label ink, and an active disabled Custom-WB command is pixel-identical to its active enabled form:
  solid white container, black label, and no border. The Custom-WB case is not hypothetical:
  `active` means `wbMode == CUSTOM`, while measurement admission requires `wbMode == AUTO`, so every
  active instance is disabled. This contradicts the app's established `DISABLED_ROW_ALPHA` /
  `BlockDisabled` visual language and the adjacent caption telling the operator to return to Auto WB.
- **Concrete failure scenario:** After measuring a custom white balance, the WB mode becomes Custom.
  The **Capture Custom WB** command remains the visually strongest filled-white control even though
  it cannot be activated; a sighted or low-vision operator reads it as the primary available action,
  taps it, and gets no response. In a locked MR row, **Update** similarly keeps enabled-strength
  label paint while only its thin edge dims. The current test covers disabled inactive Update and
  enabled active Custom WB only, and asserts semantics rather than rendered enabled/disabled state.
- **Suggested fix:** Resolve container, content, and border from both `active` and `enabled` (or use a
  click-only Material component with explicit disabled colors), preserving active paint as a visual
  value without making disabled active look actionable. Add a composed rendering/token regression
  for all four active/enabled combinations, especially active+disabled Custom WB. Material3's
  [`Surface` contract](https://android.googlesource.com/platform/frameworks/support/+/dcaa116fbfda77e64a319e1668056ce3b032469f/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Surface.kt)
  documents `enabled` as click admission and takes caller-supplied `color`/`contentColor` directly.

### DD42-02 — dropdown radio options are not exposed as one selectable group

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed accessibility-structure defect; exact TalkBack phrasing remains a
  device/manual-validation detail.
- **Exact regions:** dropdown popup and options
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:785-875`; Phone and Converter
  consumers `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:1218-1268`;
  incomplete test
  `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/DropdownSemanticsComposeTest.kt:66-104`.
- **Problem:** Each `DropdownMenuItem` manually exports `Role.RadioButton` and `Selected`, but the
  containing `DropdownMenu` has only `heightIn`; it never applies `selectableGroup`. These are
  mutually exclusive Phone/Converter choices, so marking each leaf as a radio button while omitting
  the group loses the relationship that lets accessibility services understand the set and announce
  position/count. The cycle-40 fix applied the correct group model to segmented and transfer rows,
  but the same radio-role contract in `DropdownRow` was left structurally incomplete. The existing
  test proves one Selected leaf and radio roles, yet never asserts a selectable-group ancestor.
- **Concrete failure scenario:** A TalkBack or Switch Access user opens the Phone catalog and hears a
  series of individually selected/not-selected radio buttons without the semantic radio-group
  context or reliable “N of M” positioning. The Converter menu repeats the ambiguity, making it
  harder to understand that choosing one option replaces the current declaration rather than
  toggling an independent setting.
- **Suggested fix:** Put `selectableGroup()` on the popup's option container (while retaining the
  existing item roles/states and bounded scroll), then extend the composed-tree test to require one
  selectable-group ancestor for every popup radio option before and after selection. Android's
  current [`selectable` API guidance](https://developer.android.com/reference/kotlin/androidx/compose/foundation/selection/selectable.modifier)
  explicitly requires `Modifier.selectableGroup` on a radio group, and the
  [Compose radio-button pattern](https://developer.android.com/develop/ui/compose/components/radio-button)
  uses the same structure.

### DD42-03 — zoom authority still describes the removed periodic-submit throttle

- **Severity / confidence:** Low / High
- **Classification:** Confirmed current-authority/source-comment mismatch; runtime suppression is
  not challenged.
- **Exact regions:** stale Module Map
  `docs/ARCHITECTURE.md:71`; stale still-truth comment
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:530-535`; malformed and
  over-specific swap claim
  `app/src/main/kotlin/me/hletrd/telecampro/camera/ZoomSubmitPlan.kt:15-37`; executable truth
  `ZoomSubmitPlan.kt:39-53` and `docs/ARCHITECTURE.md:702-733`.
- **Problem:** Cycle 41 removed every time/throttle input from `resolveHalZoomSubmit`; a moving tick
  now always records exact still truth and never submits to Camera2. The as-built Module Map still
  says `ZoomSubmitPlan` owns a “throttle window,” and `CameraController.noteRequestZoom` still says a
  shutter operates inside a “~200 ms throttle window” that exists to avoid a request touch. The
  edited `ZoomSubmitPlan` KDoc also contains a duplicated broken sentence (“instead of one per A
  gesture…”) and claims exactly two swaps while the documented quiet landing and later boost-end
  edge are separate owners. These statements conflict with both the five-argument pure function and
  the architecture's later, correct “MOVING gesture submits NOTHING” section.
- **Concrete failure scenario:** A maintainer follows the Module Map or nearest controller KDoc and
  reintroduces elapsed-time fields/tests to tune a nonexistent periodic path, or assumes the 200 ms
  value bounds still-request staleness. The malformed “exactly two” claim also hides the deliberate
  quiet-landing/boost-tail distinction when diagnosing measured zoom stalls.
- **Suggested fix:** Describe `ZoomSubmitPlan` as unconditional moving-tick suppression plus
  wide-aim/exact still-truth projection; rename the controller comment around a suppressed moving
  tick rather than a 200 ms window; rewrite the KDoc to enumerate start, quiet landing, and end
  ownership without asserting a globally fixed swap count. Add a documentation/source contract
  rejecting “throttle window” beside `ZoomSubmitPlan` while its signature has no timing inputs.

## Final missed-issue and file sweep

I re-swept every production/debug UI composable and helper, all click/select/toggle/pointer and
keyboard owners, semantic names/roles/states/live regions/custom actions, modal focus boundaries,
both localization trees and format placeholders, manifest/theme/RTL/locale behavior, all UI-focused
tests, the native device-harness UI contract, every committed public authority, historical-plan
status/deferred records, privacy/Play declarations, and the complete cycle-41 change surface. The
48 dp target policy, quiet dark-only camera theme, modal focus restoration, viewfinder LTR geometry
versus locale-relative reading surfaces, responsive permission/settings/Fn layouts, loading/empty/
error/delete states, and current screenshot-staleness disclosures are otherwise internally
consistent at this revision.

No accepted device conclusion was reopened. `docs/FIELD_CHECKS.md` truthfully keeps A3, A4, D1, E1,
and E2 open; the cycle-35 broad `CameraEngine` decomposition remains explicitly deferred under its
recorded exit criterion. Browser tooling was not used because this is a native Android UI.

**Finding count: 3 total — 2 Medium, 1 Low; all High confidence.**
