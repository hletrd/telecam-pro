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
