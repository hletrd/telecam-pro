# Cycle 14 test-engineer + document-specialist review

Date: 2026-08-24
Reviewed revision: `fbe31d6`
Mode: host-only test/document review; no deployment or physical-device claim

## Inventory and method

I read the current authorities and policies first: `CLAUDE.md`, `.context/README.md`,
`docs/BACKLOG.md`, `docs/ARCHITECTURE.md`, `README.md`, `docs/TESTING.md`,
`docs/FIELD_CHECKS.md`, `docs/UX_POLICY.md`, the Play submission/listing/data-safety documents,
`device-tests/README.md`, and the QA-adversary contract. I inventoried the complete current
review surface outside generated output, historical plan/review archives, `.omc`, and
`.claude/worktrees`: 88 main/debug Kotlin files, 176 host-test Kotlin files, four Android-test
files, eight production device-harness Python files plus five test modules, nine production tool
Python files plus six test modules, Android/Gradle configuration and both locale resource trees,
and the active operator/public documentation and Play assets. I traced all source/test ownership
areas, then re-reviewed every file changed since the cycle-13 baseline and performed a final sweep
for obsolete claims, stale test comments, false-positive green documentation checks, and assets
whose visible text no longer matches production resources.

The executable host evidence was green:

- `./gradlew :app:assembleDebug :app:testDebugUnitTest` — **BUILD SUCCESSFUL**, including the
  current 1,723 JVM/Robolectric/Compose tests.
- `tools/tests` — 36 tests passed; `tools/coverage/tests` — 9 passed; `device-tests/tests` —
  173 passed; `tools/check_docs.py` — 93 checks passed.
- English/Korean resources remain structurally paired: 18 default-only keys are exactly the
  `translatable="false"` product/trademark/camera-abbreviation exceptions described by `CLAUDE.md`.

Those green checks do not cover the four current contract drifts below. No Android/device behavior
was run or inferred.

## Findings

### TESTDOC14-01 — the cycle-13 Loupe contract fix left the obsolete gate in active source/test comments

- **Severity / confidence:** Low / High
- **Classification:** Confirmed active code-documentation mismatch and false-assurance docs-test gap;
  runtime predicate is correct
- **Exact region:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraActions.kt:158-166`;
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1983-1985,2903-2905,
  6097-6103`; `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/LoupeOverviewGateTest.kt:17-28`;
  `tools/check_docs.py:595-605`; `docs/plans/2026-08-23-rpf-cycle13.md:58-66`.
- **Problem:** Cycle 13 correctly changed `CLAUDE.md`, `docs/ARCHITECTURE.md`, and the executable
  `teleFinderResolved` truth table to enabled + active loupe + (TELE or unified zoom >= 3x), with
  Photo requiring 4:3 and Video ignoring still aspect. But `CameraActions` still documents
  `TELE + Photo + 4:3 + loupe`; `CameraEngine` still calls the mode axis “photo-only,” describes
  `toggle && TELE && PHOTO && 4:3`, and says the finder is categorically 4:3-only; and the real
  Compose test KDoc repeats “Photo + 4:3 + Teleconverter.” The completed plan explicitly marks
  “Loupe source/test wording” done and says no active authority can regress. The docs gate passes
  because it checks only that the new phrases exist in selected authority/test files; it never
  rejects the old phrases in the production interface/engine or Compose test.
- **Failure scenario:** A maintainer follows the action contract or the dense Engine workaround
  comment—the repository explicitly says to preserve such comments—and restores the photo-only or
  TELE-only behavior. Pure predicate tests may catch the eventual code edit, but the contradictory
  active guidance continues to generate incorrect patches and makes the cycle-13 completion record
  materially false.
- **Suggested fix:** Rewrite every cited comment to the exact current matrix, including the Video
  aspect exception and converterless 3x path. Extend `check_docs.py` to reject the obsolete
  `TELE + Photo + 4:3` / `toggle && TELE && PHOTO && 4:3` / “overview only ever draws under Photo”
  wording in all current source/test authorities, not merely prove that good wording exists
  somewhere else.

### TESTDOC14-02 — the architecture tells evidence operators to install the mutable APK it just forbids

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed evidence-runbook contradiction and missing documentation contract
- **Exact region:** `docs/ARCHITECTURE.md:1179-1185,1204-1210`;
  `device-tests/README.md:15-24,72-82`; `tools/check_docs.py:497-514`.
- **Problem:** The current architecture correctly says device evidence **must** use the exact APK
  printed by `tools/build_immutable_debug.py` and that ordinary `assembleDebug` output is
  `mutable-development-worktree` and rejected as non-evidence-grade. Twenty lines later its only
  “Device verification” command installs
  `app/build/outputs/apk/debug/app-debug.apk`, the forbidden mutable output, and does not define the
  `$APK` subsequently used to derive components. The corrected device-harness README uses
  `EVIDENCE_APK` consistently. The cycle-13 docs check validates the README and the presence of the
  immutable prose in Architecture, but does not inspect Architecture's executable command block,
  so all 93 checks pass through this direct contradiction.
- **Failure scenario:** An operator follows the as-built authority rather than the subordinate
  harness README, installs the mutable developer build, and either receives a pre-ADB harness
  refusal or performs manual checks against bytes that cannot support the source-identity claim
  immediately above the command. The resulting “verified” note can be attached to the wrong source
  snapshot.
- **Suggested fix:** Make the architecture command capture/use the wrapper's printed immutable path
  (for example `EVIDENCE_APK=...` and `APK="$EVIDENCE_APK"`) and install that exact path. Add a docs
  check that the Device verification block does not contain the mutable Gradle output and that its
  install/component-inspection commands share one defined evidence path.

### TESTDOC14-03 — current Play screenshots visibly assert a superseded SDR-only source contract

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed public-asset/code mismatch and missing visual-regression/content test
- **Exact region:** `docs/assets/play/screenshots/06-video-settings.png` (visible caption
  “Applied to the SDR stream”); `docs/assets/play/screenshots/02-pro-settings.png` (visible
  “Shooting” / “JPEG Quality”); `app/src/main/res/values/strings.xml:16,49,253-261,317`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:736,832,1260,1367-1385`;
  `docs/play-store-listing.md:306-323`; `docs/play-console-submit.md:602-605,613-624`.
- **Problem:** The submission docs call the six phone images “CURRENT” and say the menu frames come
  from—and therefore show—the shipping UI. They do not. Screenshot 06 still tells prospective users
  the selected transfer is applied to an SDR stream. Production changed that caption to “Applied
  to the camera’s already tone-mapped stream” specifically because non-SDR Video now requests an
  accepted HLG10 source. Screenshot 02 also exposes superseded UI labels (“Shooting” and “JPEG
  Quality”) where current resources render “Shoot” and “Still Quality”; screenshot 06 says
  “Transfer” where the current sheet labels the group “Gamma.” The stored image dates to the
  2026-07-27 capture, before later contract/copy changes. The current docs test checks asset
  dimensions/presence and current source strings, but no test ties visible screenshot content or
  provenance to the claimed release UI.
- **Failure scenario:** The v1.0.2 submission reuses the repository's declared-current assets. The
  Play listing then publicly contradicts the app and README on a core honesty claim, telling users
  non-SDR profiles always start from SDR even though the shipped route requests HLG10. It also shows
  menu labels users cannot find in the installed app.
- **Suggested fix:** Recapture at least phone screenshots 02 and 06 from one immutable current
  release/evidence APK, update the Play documents with that exact source/artifact identity, and
  replace the stored PNGs. Add a deterministic asset-content/provenance contract (for example the
  snapshot harness's exact scenario + locale + source-manifest digest and asserted semantic copy)
  so later copy/contract changes invalidate the “current” asset set instead of leaving dimensions
  green.

### TESTDOC14-04 — the active backlog records a DONE GitHub tagline that is neither current nor correct

- **Severity / confidence:** Low / High
- **Classification:** Confirmed current-board/external-state mismatch; historical wording was not
  clearly scoped as superseded
- **Exact region:** `docs/BACKLOG.md:1-3,1011-1020`; current production authority
  `CLAUDE.md:146-151`, `README.md:53-56`, `docs/ARCHITECTURE.md:24-28`; GitHub repository metadata
  `https://api.github.com/repos/hletrd/telecam-pro` checked 2026-08-24.
- **Problem:** The current release board's DONE item says the present GitHub About text is
  “DNG stills in tele mode” and justifies it with “DNG only exists in TELE mode.” Both halves are
  obsolete. Current route truth offers DNG on any rear lens advertising RAW by moving that photo
  route to a standalone camera. The live GitHub API currently says “RAW/DNG on any lens advertising
  it,” not the quoted old tagline. This is not merely an old investigation under a Historical
  heading; it is an active “owner actions outside this repo” completion record.
- **Failure scenario:** Release review treats the DONE record as the external source of truth and
  either “corrects” the already-correct live About text back to TELE-only or cites the obsolete
  restriction in future store/release copy.
- **Suggested fix:** Update the DONE item with the current live tagline and note that the prior
  TELE-only wording was superseded when DNG became a route input. Add a local docs contract that
  rejects the old “DNG only exists in TELE mode” claim in active-board sections; external metadata
  should still be checked manually at release time rather than silently trusted forever.

## Final missed-issue sweep

I re-swept every production/test ownership area, the cycle-13 native/audio/lifecycle/provenance
changes and their new tests, current coverage partition rules, Android and device-harness test
contracts, EN/KO resources, public/operator documentation, and all phone/tablet Play screenshots.
No additional current-HEAD test flakiness, false assertion, or active doc/code mismatch met the
evidence threshold. Historical review/plan claims and owner-recorded backlog deferrals were not
re-filed unless a current authority still presented them as live truth.

**Finding count: 4 total — 2 Medium, 2 Low.**
