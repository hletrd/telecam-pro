# Cycle 52 document-specialist + native designer review

Date: 2026-08-25  
Reviewed revision: `96732cc9` (`cycle52`, `origin/main`)  
Workspace: isolated clone `/tmp/find-x9-ultra-cycle52.868ovy/repo`

## Authority, inventory, and method

I read `CLAUDE.md` completely first, followed by `docs/ARCHITECTURE.md` and
`docs/FIELD_CHECKS.md` completely. No project `AGENTS.md` or optional private `docs/BACKLOG.md`,
`docs/TESTING.md`, `docs/UX_POLICY.md`, store-listing source, or historical specification exists in
this clean clone; the committed fallback authority explicitly permits their absence.

The revision contains 540 tracked paths: 103 production Kotlin/Java modules, 31 production UI
modules, 15 main resources, three debug UI/command modules, four instrumented-test files, 241 JVM/
Robolectric/Compose test paths, 96 Markdown files (including 44 completed cycle plans), and the
remaining build, privacy, release, asset, font, wrapper, Python, shell, and device-harness inputs.
I built the complete inventory before review. I examined every production UI file and its state,
permission, storage, Camera2/GL, and persistence inputs; every EN/KO resource and theme/manifest
resource; all UI-focused tests; current architecture/privacy/release authorities; the aggregate and
current specialist history; every plan/review status header; and every checked-in Play image plus its
fail-closed validity manifest. Historical raw reviews/plans were treated as resolved history, not as
current implementation authority.

The UI pass covered quiet-viewfinder information architecture, modes and affordances, touch floors,
contrast, dark theme, semantic roles and state descriptions, keyboard/D-pad activation, modal focus
and return, loading/empty/error/progress states, destructive confirmation, EN/KO parity, RTL,
large-font and narrow-window reflow, sw600dp+ rotation, review transforms, and perceived-performance
gates. The cross-file pass traced those surfaces through `CameraUiState`, `CameraViewModel`, Engine
callbacks, MediaStore recovery/deletion, settings restore, and current documentation rather than
trusting comments or green tests alone.

## Findings

### C52-DOC-DES-01 — six clickable controls install a second focus owner in front of their real keyboard action

- **Severity / confidence:** Medium / Medium.
- **Classification:** **Likely keyboard/D-pad defect; source-confirmed mechanism, manual-risk for the
  exact traversal/activation symptom.** The bundled Compose Foundation `AbstractClickableNode`
  contains its own `FocusableNode`, so adding `Modifier.focusable()` before `clickable`/`selectable`
  creates two nested focus targets for one visual control.
- **Exact regions:**
  - Settings Close: `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:409-425`.
  - Every settings rail tab: `ProSheet.kt:470-499`.
  - Compact Fn opener and ruler Close: `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ManualDials.kt:395-416` and `:442-458`.
  - Tap-focus reset chip and Fn-modal Close: `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:1816-1838` and `:2516-2530`.
  - The non-clickable adjustable canvases at `ProControls.kt:520`, `ManualDials.kt:1388`, and the
    review image at `MediaReview.kt:2078` are legitimate single focus owners and are not implicated.
  - `ModalFocusComposeTest.kt:150-179,182-213` asserts that the initial Close node is focused and
    that hidden finder nodes stay unfocused, but never presses Enter/D-pad Center on that initial
    owner or proves that one Tab step leaves it. `CameraControlKeyboardComposeTest` exercises the
    controls that rely on clickable/selectable alone, not these redundant-focus variants.
- **Failure scenario:** a keyboard, TV remote, switch device, or camera grip opens Settings/Fn. The
  `FocusRequester` binds to the explicit outer focus target while the actual clickable's focus/key
  owner is nested behind it. Center/Enter can fail to invoke the close action, or the first Tab/D-pad
  move can land invisibly on the second target at identical bounds, requiring an unexplained extra
  step. The nine settings tabs repeat the invisible-stop risk through the primary menu navigation.
- **Concrete fix:** remove the explicit `focusable()` from every node that already ends in enabled
  `clickable`, `combinedClickable`, or `selectable`; keep the manual semantics and let the interaction
  modifier own the sole focus/key target. Add production-composition tests that open each modal,
  activate its initial Close with Enter and DirectionCenter, and count traversal edges across Close
  plus all nine tabs in both directions. Also assert that disabled variants remain skipped.

### C52-DOC-DES-02 — durable DISCARD authority assumes a MediaStore URI remains the same media identity forever

- **Severity / confidence:** Medium / Medium.
- **Classification:** **Likely data-loss risk; destructive replay is confirmed, actual MediaStore
  `_ID` reuse after provider reset is manual-risk/device-dependent.**
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:9-46,107-149,215-244`
    calls the owner an “exact MediaStore URI” and stores only `uri TEXT PRIMARY KEY`; no volume,
    provider version, display name, path, MIME, owner, or capture-family identity is retained.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:823-852,1200-1224`
    reparses the persisted string and directly calls `ContentResolver.delete` before checking only
    whether that same URI is absent. No row identity is validated before deletion.
  - `docs/ARCHITECTURE.md:1135-1173` presents durable DISCARD replay as terminal deletion of the
    exact owned row, but documents no identity-reset boundary or MediaStore-version resynchronization.
- **Failure scenario:** deletion of `content://media/external/images/media/417` fails after its
  DISCARD marker commits. MediaStore is later rebuilt and reuses `_ID=417` for a different app-owned
  capture. Relaunch recovery interprets the old string as exact authority, deletes the replacement,
  observes the URI absent, and clears the marker. The user loses a capture they never selected.
- **Concrete fix:** make the journal record a versioned expected-media identity: volume/collection,
  `MediaStore.getVersion` at marking, canonical capture-family/name, relative path, MIME, and expected
  ownership. Query and require an exact match before destructive replay. On provider-version change,
  resolve conservatively by the stored canonical identity and leave unreadable/ambiguous records
  unresolved. Migrate URI-only v1 markers fail-closed, add a fake-provider URI-remap test, and add a
  disposable-device reset/reindex field check before claiming OEM behavior.

### C52-DOC-DES-03 — the published v1.0.1 localization record gives two different translation counts

- **Severity / confidence:** Low / High.
- **Classification:** **Confirmed documentation mismatch.**
- **Exact regions:** `CLAUDE.md:44-46` records that v1.0.1 shipped **126** Korean strings, while
  `docs/play-console-submit.md:197-203` says **131** strings gained a `ko` translation. The published
  versionCode-3 source identified by the same submission history (`fe6a8a0`) contains 126 `<string>`
  entries in `values-ko/strings.xml`, agreeing with `CLAUDE.md` and not the submission sheet.
- **Failure scenario:** a maintainer reuses the retained v1.0.1 record for Play release notes,
  provenance, or a future localization audit and publishes a count the tagged source cannot
  reproduce. The current docs gate reports green because it validates present-day parity but does
  not cross-check historical release counts.
- **Concrete fix:** change the submission record to 126 translated strings (or explicitly define and
  reproduce a different 131-item counting method). Add a historical-source assertion that derives
  the versionCode-3 Korean entry count from the named commit so the two retained authorities cannot
  drift again.

## Validation and evidence limits

- `python3 tools/check_docs.py` passed all 154 applicable checks with zero failures; 24 explicitly
  optional private-document checks were skipped. This green does not cover the three findings above.
- Present-day resource parsing found 484 English `<string>` entries and 466 Korean entries, with no
  missing Korean peer among translatable English strings. The count difference consists of declared
  non-translatable identities/abbreviations.
- Checked-in phone and tablet screenshots match their digests and declared PNG structure. Their
  manifests correctly remain `submission_ready=false`: phone frames 02/06 have known stale copy and
  all tablet frames lack immutable recapture identity. I did not re-file those explicit blockers as
  new defects.
- No browser automation applies to this native Jetpack Compose UI. No device, emulator, deployment,
  camera/GL operation, destructive MediaProvider reset, external communication, or production release
  action was authorized or performed. A3/A4/A5/D1/E1/E2 remain the six explicit physical evidence
  obligations in `docs/FIELD_CHECKS.md`.

## Final missed-issue and skipped-file sweep

The final sweep rechecked every modal/open/close/Back path, focus requester, focusable/clickable/
selectable chain, semantic role and live region, 48 dp target, slider key policy, viewfinder absolute
geometry, RTL boundary, font-scale breakpoint, rotated large-screen branch, loading/error/empty state,
review gesture and destructive dialog, EN/KO resource mapping, hard-coded UI token, color role,
privacy/permission statement, screenshot readiness record, field-evidence claim, and current
architecture ownership statement. It also reconciled the complete tracked-path inventory against the
files examined and revisited the cycle-51 changes and their focused tests. No review-relevant file was
silently skipped; absent private documents and physical-device checks are explicitly bounded above.

**New document/designer finding count: 3 — two Medium and one Low; one confirmed documentation
mismatch and two likely/manual-risk implementation defects with source-confirmed mechanisms.**
