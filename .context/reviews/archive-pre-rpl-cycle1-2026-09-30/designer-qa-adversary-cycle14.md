# Designer + QA-adversary review — cycle 14

Date: 2026-08-24
Reviewed revision: `fbe31d6`
Mode: Android Compose UI, static/host-only; deployment and device work forbidden by directive

## Scope and inventory

I read `CLAUDE.md`, `docs/UX_POLICY.md`, `docs/BACKLOG.md`, `docs/ARCHITECTURE.md`,
`docs/TESTING.md`, the Play asset/submission documents, and `.claude/agents/qa-adversary.md` before
reviewing the complete UI surface. The static pass covered all production/debug UI Kotlin and
resources, UI-facing policy/state/actions, all Compose/Robolectric UI tests, instrumented/device
UI contracts, and every phone/tablet Play screenshot. I checked Sony Alpha/Xperia information
hierarchy, preview-first/quiet-viewfinder rules, 48 dp targets, focus/keyboard/semantics, modal and
Back ownership, loading/empty/error/delete states, fixed-dark contrast, window rotation and
large-screen behavior, EN/KO parity, RTL, 2x-font/compact-wide coverage, and high-frequency preview
interaction. The current source/resource tests are broad and no new runtime accessibility, layout,
or interaction defect survived the static evidence threshold.

The English/Korean resources remain paired except for the 18 expressly permitted
`translatable="false"` abbreviations and product/trademark identities. The required static gate
`./gradlew :app:assembleDebug :app:testDebugUnitTest` passed in 3m26s with all 1,723 current
JVM/Robolectric/Compose tests. The manifest merger emitted the repository's established intentional
`tools:node="remove"` no-network warnings; no test/build error occurred.

## Findings

### DQA14-01 — declared-current Play menu screenshots advertise a superseded UI and source pipeline

- **Severity:** Medium
- **Confidence:** High
- **Classification:** Confirmed public visual/UX contract drift; current runtime source is internally
  consistent
- **Exact regions:** `docs/assets/play/screenshots/02-pro-settings.png` and
  `docs/assets/play/screenshots/06-video-settings.png`; `docs/play-store-listing.md:306-323`;
  `docs/play-console-submit.md:602-605,613-624`;
  `app/src/main/res/values/strings.xml:16,49,253-261,317`;
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProSheet.kt:736,832,1260,1367-1385`.
- **Problem:** The Play documents label the six 2026-07-27 phone frames “CURRENT” and say the menu
  frames show the shipping UI, but the visible images predate later source/copy changes. Screenshot
  02 says “Shooting” and “JPEG Quality” while current UI says “Shoot” and “Still Quality.” Screenshot
  06 says “Transfer” instead of current “Gamma” and, more importantly, displays “Applied to the SDR
  stream.” Production deliberately replaced that text with “Applied to the camera’s already
  tone-mapped stream” once non-SDR Video began requesting an HLG10 Camera2 source. The old image now
  makes the app's most sensitive honesty claim wrong in the public storefront.
- **Failure scenario:** A v1.0.2 Play submission follows the checked-in operator sheet and uploads
  these images unchanged. Prospective users see controls/copy that do not exist and are told that
  HLG/log always map an SDR source, contradicting the current app's HLG10-source behavior and public
  README. This is a discoverability problem (“Gamma” cannot be matched to the pictured “Transfer”)
  as well as a technical-trust problem.
- **Suggested fix:** Recapture phone frames 02 and 06 from the same immutable current release/evidence
  APK, with the final English resources and accepted non-SDR state visible; update the Play sheet
  with exact artifact/source identity. Bind “current screenshot” status to a snapshot scenario,
  locale, expected semantic strings, and source-manifest digest so future wording changes invalidate
  the assets rather than leaving only dimension checks green.

### DQA14-02 — Loupe's active UI guidance still describes the old Photo/TELE-only design

- **Severity:** Low
- **Confidence:** High
- **Classification:** Confirmed designer-facing source/test contract drift; runtime and pure truth
  table are correct
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraActions.kt:158-166`;
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1983-1985,2903-2905,
  6097-6103`; `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/LoupeOverviewGateTest.kt:17-28`;
  versus `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:390-400,555-584` and
  `docs/ARCHITECTURE.md:693-725`.
- **Problem:** The cycle-13 authority fix landed the intended Sony-style rule—enabled + active loupe
  + (TELE or unified zoom >=3x), Photo 4:3 only, Video independent of still aspect—but the public
  action KDoc, several dense Engine comments, and the Compose gate test still teach
  TELE + Photo + 4:3. The Compose test verifies only the parent-Loupe row enablement and its KDoc
  states a route rule that the actual shared predicate rejects.
- **Failure scenario:** A UI maintainer follows the nearest action/test documentation and removes
  Loupe Overview from Video or from converterless 3x operation. That silently reduces the long-lens
  assist exactly where the current design intentionally made it available, while the stale test
  continues to appear as UI-level assurance.
- **Suggested fix:** Align all cited source/test comments to the exact matrix and add a real composed
  visibility/OSD assertion for Video and non-TELE 3x, or explicitly name the existing pure predicate
  test as the owner and keep the Compose KDoc scoped only to its parent-row enablement assertion.

## Static UX sweep result

Current implementation evidence supports the fixed-dark Sony-style hierarchy, quiet viewfinder,
modal isolation, 48 dp geometry, keyboard/semantics, EN/KO and RTL handling, and compact/large-screen
policies. Host composition cannot prove GPU pixels, physical-display contrast, TalkBack speech,
touch feel, camera output, OIS, focus, or capture orientation; none of those were promoted to PASS.

## QA gate report

| Feature | Result | Evidence |
|---|---|---|
| Gate 1 — static build + unit | PASS | Required `./gradlew :app:assembleDebug :app:testDebugUnitTest` completed `BUILD SUCCESSFUL` in 3m26s; 52 tasks, no failing test. |
| Gate 2 — install, launch, crash scan | BLOCKED BY DIRECTIVE | No install, launch, ADB, device serial, or historical run evidence was used. |
| 1. Mode-aware camera selection | BLOCKED BY DIRECTIVE | Requires current Camera2/device evidence. |
| 2. Preview renders and is upright | BLOCKED BY DIRECTIVE | Requires a current lit, oriented scene. |
| 3. Program exposure default | BLOCKED BY DIRECTIVE | Requires current exposure/result evidence. |
| 4. PASM behavior | BLOCKED BY DIRECTIVE | Requires current request/result evidence. |
| 5. ISO/shutter snapping | BLOCKED BY DIRECTIVE | Requires current ruler/request evidence. |
| 6. Continuous/manual focus | BLOCKED BY DIRECTIVE | Requires a current optical scene. |
| 7. Tap-to-focus hold/reset | BLOCKED BY DIRECTIVE | Requires current AF/AE and accessibility interaction evidence. |
| 8. Format gating | BLOCKED BY DIRECTIVE | Requires accepted-session truth on a current device. |
| 9. Photo capture/files | BLOCKED BY DIRECTIVE | Requires current MediaStore rows and pulled files. |
| 10. Whole-shot delete | BLOCKED BY DIRECTIVE | Requires a current multi-output save/delete run. |
| 11. Video | BLOCKED BY DIRECTIVE | Requires a current pulled MP4. |
| 12. OIS/EIS | BLOCKED BY DIRECTIVE | Requires a current handheld long-lens comparison. |
| 13. Monitoring overlays | BLOCKED BY DIRECTIVE | Requires live preview interaction. |
| 14. Nine-tab settings sheet | BLOCKED BY DIRECTIVE | Static source/tests cover the structure; QA PASS requires current-device application checks. |
| 15. MR/settings restore | BLOCKED BY DIRECTIVE | Requires current force-stop/relaunch and framing evidence. |
| Gate 4 — lifecycle/toggle/cap/delete adversary | BLOCKED BY DIRECTIVE | Device execution was expressly forbidden. |

**GATE NOT PASSED — Gate 1 passed; every device gate is BLOCKED BY DIRECTIVE because deployment and
device work were forbidden.**

**Finding count: 2 total — 1 Medium, 1 Low.**
