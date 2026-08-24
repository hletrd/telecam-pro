# Document-specialist review — cycle 50

Date: 2026-08-25

Reviewed revision: `2388819d` (`origin/main`)

## Inventory and validation

I inventoried all 92 Markdown files plus the published HTML privacy policy, EN/KO resources,
manifests, version catalog/build scripts, release tools, device harness, and source/tests named by
current documentation. I read the complete committed authorities (`CLAUDE.md`,
`docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`), README, privacy authorities, device-harness guide,
Play Data Safety authority, and the complete Play submission sheet. Historical plans/reviews were
searched for unqualified current-state claims and checked through the repository's plan/doc gates.

`tools/check_docs.py` passed all 152 applicable checks (24 optional-private checks skipped). EN/KO
resource parity, manifest permissions, versionCode/versionName, Android floor/target, release
not-ready state, screenshot blockers, field-check membership, privacy statements, and current
Loupe/DNG/HLG wording agree. Current-version claims were also checked against official metadata:
AGP 9.3.2 and Compose BOM 2026.08.00 are the newest stable Google Maven entries, Kotlin 2.4.10 is
JetBrains' current stable release, and Gradle's official current endpoint reports 9.7.1. Sources:
Google Maven metadata for
[`com.android.tools.build:gradle`](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)
and
[`androidx.compose:compose-bom`](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml),
JetBrains' [Kotlin releases](https://kotlinlang.org/docs/releases.html), and Gradle's
[`versions/current`](https://services.gradle.org/versions/current).

## Findings

### D50-01 — the architecture promises an atomic REC video packet that production does not snapshot

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed authoritative-doc/code mismatch; same root cause as A50-01.
- **Exact regions:** `docs/ARCHITECTURE.md:308-314` says codec, ordered candidates, requested
  transfer, and active transfer form one immutable packet and that Ready/REC can never observe a
  hybrid. `CLAUDE.md:867-871` repeats that rollback restores the packet before the next REC
  admission. Writers do serialize it (`CameraEngine.kt:764-793,2511-2556`), but production REC
  obtains the accepted session under a lock at `:4947-4958` and reads each packet/capability field
  after unlocking at `:5043-5065`.

**Failure scenario:** a pipeline commit or rollback between those volatile reads can make admission
pair HEVC with AVC candidates or a non-SDR transfer with an SDR-only component. The app can refuse a
valid start as “Selected codec unavailable,” despite the authority's absolute “never” claim. The
exact filter fails closed, so this review does not claim that an incompatible encoder is started.
The new tests cover writer synchronization and the pure helper, not the production snapshot
interleave.

**Suggested fix:** fix the production snapshot as described in A50-01, then retain the current
authority wording and add a docs/test invariant naming the one locked snapshot owner. If the code is
not fixed, weaken both authorities to describe the actual per-field behavior; that would document a
race rather than make it safe and is not the preferred resolution.

### D50-02 — “external callbacks run after unlocking” has one live counterexample

- **Severity / confidence:** Low / High
- **Classification:** Confirmed authoritative-doc/code mismatch; same root cause as A50-02.
- **Exact regions:** `docs/ARCHITECTURE.md:301-307` states that external callbacks run after the
  optics commit unlocks. `CameraEngine.kt:628-630` repeats the same rule in source. Nevertheless,
  `commitOpticsReady` invokes `onCameraPolicyBlocked(false)` inside the
  `OpticsCommitGate.commit` mutation at `CameraEngine.kt:633-677`; the gate holds the Engine monitor
  for that mutation at `:7265-7285`.

**Failure scenario:** maintainers rely on the authority when adding work to that callback and
unknowingly place Engine re-entry, callback-sink waiting, or UI work inside the Ready critical
section. The mismatch also prevents the docs gate from protecting the real rule because it checks
the prose but not this callback site.

**Suggested fix:** move the policy-unblocked callback beside the other post-commit publications at
`CameraEngine.kt:678-684`, then add a source-contract check (or a behavior test) that terminal
mutation bodies contain no callback invocation. The present architecture wording can remain.

## Final sweep and evidence limits

No other current documentation defect survived the full inventory sweep. The six open field checks
remain A3, A4, A5, D1, E1, and E2; no host result was promoted to device evidence. The complete debug
test task passed. The consolidated host gate was not runnable in this clone because the local SDK is
missing the stable Emulator `glslangValidator`, so this review does not repeat the historical
cycle-49 host-gate pass as evidence for current execution.

---

## Archived prior review

# Document-specialist review — cycle 49

Date: 2026-08-25

Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27`

## Coverage

I read the three committed operating authorities completely, then the README, privacy policy,
device-harness guide, Play Data Safety authority, and complete Play submission sheet. I inventoried
all 93 tracked Markdown files and checked historical plans/reviews for current-tense claims that
escaped their supersession labels. I also ran the full documentation checker (151 pass, 24 declared
private-file skips) and cross-checked user-facing EN/KO resources, manifest permissions, release
state, field-check membership, and current source behavior.

## Findings

### C49-DOC-01 — a historical matrix still calls the already-fixed AppOps disclosure an open UX gap

- **Severity / confidence:** Low / High
- **Classification:** Confirmed documentation contradiction.
- **Evidence:** `docs/play-console-submit.md:386-392` says the app “said NOTHING” and declares
  “This is an open UX gap,” directing readers to BACKLOG. The same document's current release delta
  at `:280-287` says the AppOps-policy path now surfaces “Camera blocked for this app on this
  device.” plus Settings and is confirmed. Production contains that localized status
  (`app/src/main/res/values/strings.xml:271`, Korean peer present) and handles the policy failure in
  `CameraEngine.kt:3000-3080`.
- **Failure scenario:** a release reviewer or maintainer treats a closed behavior as current work,
  duplicates it, or reports the present artifact as silently black despite the implemented status.
  The surrounding matrix is labeled historical, but the paragraph uses unqualified present tense
  and the emphatic “open” status, so it conflicts with the current authority within the same file.
- **Concrete fix:** explicitly mark the paragraph as the historical pre-fix observation and point
  to the current fixed release item/commit. Extend `tools/check_docs.py` to reject the exact active
  “open UX gap” phrase outside an explicitly superseded quotation.

### C49-DOC-02 — cycle-48 claims delete-dialog focus coverage without asserting focus return

- **Severity / confidence:** Low / High
- **Classification:** Confirmed evidence-record gap; product behavior is separately `C49-CT-02`.
- **Evidence:** `docs/plans/2026-08-25-rpf-cycle48.md:77-82` marks delete-dialog cancel covered, but
  `ModalFocusComposeTest.kt:218-253` checks disappearance only and never asserts the Delete opener or
  any review node regains focus.
- **Concrete fix:** after adding the missing product assertion/ownership, append a dated correction
  to cycle 48 describing the evidence actually added.

## Final documentation sweep

No further current-source contradiction survived. EN/KO parity, privacy permissions, minSdk,
current no-artifact release state, screenshot blockers, Loupe orientation, device-evidence limits,
and the six open field checks all agree across the committed authorities.

---

## Archived prior review

# Document-specialist review — cycle 39

Date: 2026-08-24

Reviewed revision: `5ee6b21` (`origin/main`)

Workspace: isolated worktree `/private/tmp/find-x9-cycle39.feeBBZ`

## Scope and evidence

Inventoried all 493 tracked paths and examined the complete committed instruction/architecture/field
authority, README, privacy and Play material, release/build configuration, device-harness guidance,
resources/manifests, production implementation, tests, and current/historical review plans. Optional
private maintainer documents are absent, as the committed clean-clone policy permits.
`tools/check_docs.py` passed 120 checks with 24 optional-private skips. Current stable dependency
claims were cross-checked against Google Maven, JetBrains plugin metadata, Maven Central, and
Gradle's official current-release service; no toolchain-version drift was found.

## Findings

### DOC39-01 — current authorities and the renderer comment put Loupe Overview on the wrong side

- **Severity / confidence:** Low / High.
- **Exact regions:** `CLAUDE.md:251-253` and `docs/ARCHITECTURE.md:745-749` both say the viewport is
  in the bottom-left corner; the renderer repeats that stale claim at
  `app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt:1067-1073` even as it consumes
  `rect.x`. The executable geometry explicitly insets from the **right** at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:665-691`, because the left column
  owns the exposure/zoom ruler. Compose consumes that absolute x-coordinate from a BottomLeft
  origin at `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:896-917`, so the positive
  `boxWidth - width - inset` offset lands the overview at bottom-right. The position-sensitive test
  pins that right-side x value at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/FinderGeometryTest.kt:18-33`.
- **Mismatch / scenario:** a maintainer following either authority or the renderer comment can move or test the overlay as a
  bottom-left element, exactly where the implementation comments record that it overlapped the
  persistent ruler. The two authoritative prose copies therefore describe the superseded placement,
  while code and tests enforce the user-requested right side.
- **Suggested fix:** change both current-authority occurrences to “bottom-right” (or “right-inset
  bottom corner”) and extend `tools/check_docs.py` to bind the authority wording to `finderRect`'s
  right-edge law so the two copies cannot drift again.

### DOC39-02 — cycle 38 records pure predicate tests as Engine-facing stabilization proof

- **Severity / confidence:** Low / High.
- **Exact regions:** `docs/plans/2026-08-24-rpf-cycle38.md:27-28` marks focused pure **and
  Engine-facing** regression coverage complete, and `:75-80` reports that stabilization
  normalization avoids rebuild/reopen. The only new stabilization tests are the direct pure-helper
  assertions at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/CaptureCapabilitiesTest.kt:65-100`. Repository-wide
  search finds no test that invokes `CameraEngine.setVideoStabMode`, whose state assignment,
  `applyStabilization()`, and `reopenForSession()` control flow lives at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1587-1600`.
- **Mismatch / scenario:** the durable completion record promises integration evidence that does not
  exist. A future reviewer can accept a green pure predicate as proof that no request or session side
  effect occurred, even though a call-order regression in the Engine would be invisible to it.
- **Suggested fix:** add the Engine-side-effect regression described by TEST39-01, then append a
  dated correction to the completed cycle-38 plan identifying the new test. Do not rewrite away the
  historical overclaim.

## Final sweep

Android floor, current stable toolchain versions, privacy disclosures, Play release/artifact state,
field-check membership, afocal-orientation exception, DNG routing, ZSL boundary, finder geometry,
and current source/module ownership otherwise agree with committed truth. No other current-authority
drift survived the final sweep.

## Totals

- New findings: 2
- Severity: 2 Low
- Confidence: 2 High

---

# Document-specialist review — cycle 51 (current)

Date/HEAD: 2026-08-25, `7eb4ee95`. Isolated clone only; no implementation/deploy/device work.

## Complete inventory and checks

Read `CLAUDE.md` (1,153 lines), `docs/ARCHITECTURE.md` (1,372), `docs/FIELD_CHECKS.md` (284), README/privacy/legal/notices, all committed plans/submission/data-safety/device-catalog docs, every source KDoc/comment touching stated behavior, both locale resource sets, manifest/build authorities, all tooling/device-harness READMEs and contract tests, and all 15 committed visual/vector Play assets plus validity manifests. `tools/check_docs.py` passed 153/153 checks with 24 explicitly optional private-file skips; locale pairing and screenshot hashes/geometry passed.

## Finding

### C51-CV-03 — orientation and rollback prose is stale despite a green docs gate

- Locations: `FlipRenderer.kt:294-298`, `GlPipeline.kt:1090-1110` and `1127`; `CLAUDE.md:867-871`; `docs/ARCHITECTURE.md:308-315`.
- Severity: Low. Confidence: High. Classification: **confirmed**.
- Mismatch: current authorities say the same converter-fed overview deliberately omits afocal correction and is raw/inverted; source comments still call it “UPRIGHT,” “world the right way up,” and a pre-converter-world stand-in. The same architecture section says rollback restores the baseline packet without noting the new independent publication generation that preserves a newer codec/candidate/transfer packet. The docs check's reported Loupe agreement therefore does not cover these contradictory comments.
- Failure scenario: maintainers reintroduce a superseded rotation or baseline-overwrites-newer-intent behavior using comments presented as design authority.
- Suggested fix: consistently call the current inset raw/inverted same-stream truth; reserve upright language for a future real wide stream. Document conditional packet restoration, publication-generation recheck, and atomic REC inputs; mutation-test the stale phrases.

## Coverage conclusion

Open A3/A4/A5/D1/E1/E2 remain correctly manual, screenshots explicitly marked stale/non-submission-ready remain so, and no other current-source/doc/resource/i18n mismatch survived. Findings: **1 Low/High**.
