# Test engineer + document specialist + native Android designer review — cycle 48

Date: 2026-08-25  
Reviewed revision: `ad64188a` (`docs(plan): ✅ close cycle 47 fixes`)  
Workspace: isolated clone `/tmp/find-x9-ultra-cycle48.Gvbytf`

## Scope, inventory, and method

I inventoried all 528 tracked paths before review. The current review surface was 477 tracked paths:
all production sources/resources/manifests/build inputs, all 237 JVM/Robolectric/Compose tests, all
four `androidTest` files, all 14 device-harness files, all 25 host-tool files, all 62 committed docs
and assets, the privacy site, and the repository authorities. The remaining tracked paths are
historical review reports and repository metadata; I checked the current aggregate and the prior
reports/plans relevant to each suspected issue so already-fixed findings were not re-filed.

The production UI inventory was complete: `MainActivity.kt`; all 31 files under
`app/src/main/kotlin/me/hletrd/telecampro/ui/` (screen, policy, ViewModel, actions, input/modal,
capture/review ownership, zoom, `controls/`, `overlays/`, `review/`, and theme); all 15 files under
`app/src/main/res/` (EN/KO strings, themes/colors, XML policy, launcher vectors, and Inter fonts);
and the state/capability/rotation/camera/storage/video owners consumed by those surfaces. I also
inspected every committed phone/tablet Play screenshot and its validity manifest. Browser skills
were not applicable because this is a native Jetpack Compose app.

Manual UI review covered information architecture and affordances; touch, stylus, mouse, hardware
keyboard/D-pad and TalkBack semantics/focus; WCAG 2.2 text and non-text contrast; modal focus; touch
targets; loading/empty/error/retry states; deterministic dark appearance under light/dark system
settings; EN/KO localization and forced RTL behavior; font scaling/reflow; reduced-motion behavior;
and perceived-performance feedback. WCAG was used as an applicability lens for the native UI:
[SC 2.1.1 Keyboard](https://www.w3.org/WAI/WCAG22/Understanding/keyboard.html) requires pointer
functionality to have a keyboard equivalent, and
[SC 2.4.3 Focus Order](https://www.w3.org/WAI/WCAG22/Understanding/focus-order.html) requires a
sequence that preserves operability. Android's current Compose inventory provides
`focusRestorer` specifically to save and restore a focus group's focus
([Android modifier reference](https://developer.android.com/develop/ui/compose/modifiers-list)).

No source, test, plan, or shared-main file was modified. This report is the only added file.

## Findings

### C48-TDD-01 — closing a full-screen modal does not restore keyboard focus to its opener

- **Severity / confidence:** Medium / High
- **Status:** **Likely runtime accessibility defect; source-confirmed missing ownership.** A real
  keyboard/D-pad pass is still required to record the OEM/Compose landing position.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:451-458` derives one
  aggregate modal state, and `:585-591` makes the complete finder subtree non-focusable while a modal
  is present. Settings, Fn, and review are then removed with bare state/callback changes at
  `:1419-1450`; none retains the opener's `FocusRequester` or requests it after close.
  `app/src/main/kotlin/me/hletrd/telecampro/ui/ModalFocus.kt:8-12` only disables finder focus and
  groups modal traversal; it does not save/restore focus. Each modal correctly claims initial focus
  (`ui/controls/ProSheet.kt:269-273`, `ui/CameraScreen.kt:2307-2308`, and
  `ui/review/MediaReview.kt:1059-1062`), which makes the missing return edge explicit.
  `app/src/test/kotlin/me/hletrd/telecampro/ui/ModalFocusComposeTest.kt:82-248` proves initial modal
  focus and exclusion of background nodes, but never closes a production modal and asserts focus on
  the originating Menu/Fn/gallery control.
- **Failure scenario:** a tablet/Chromebook or switch-key user focuses Menu, opens Settings, then
  closes it with Back or the X. The trigger has been made ineligible during the modal and there is no
  restoration request when it returns. Focus can therefore be absent or restart at an unrelated
  first/nearest finder control; repeated open-inspect-close operation loses the user's place. The
  same ownership hole exists for Fn and review.
- **Suggested fix:** retain an exact modal-origin focus owner (separate requesters for Menu, Fn, and
  gallery, or a focus group using `focusRestorer`) and request that origin only after its modal leaves
  composition and the finder is re-enabled. Add production `CameraScreen` keyboard tests for X,
  scrim, Back, review close, delete-dialog cancel, and the My Menu WB dismiss/reopen transition.

### C48-TDD-02 — the viewfinder's accessible center-focus/reset actions have no hardware-keyboard focus target

- **Severity / confidence:** Medium / High
- **Status:** **Confirmed code-path gap; manual keyboard validation remains.** TalkBack can reach the
  semantics node and its custom actions; this finding is specifically the keyboard/D-pad path.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:328-353` adds a name,
  state, and `CustomAccessibilityAction`s but neither `focusable()` nor a key handler. Its production
  use at `:724-789` is followed only by the pointer gesture loop; there is no Enter/Space/DPAD-center
  route. The analogous review image explicitly supplies both `onPreviewKeyEvent` and `focusable()` at
  `ui/review/MediaReview.kt:2035-2051`. `ViewfinderAccessibilityComposeTest.kt:97-159` inspects only
  semantics cardinality/state and never requests keyboard focus or fires a key.
- **Failure scenario:** on a keyboard/D-pad device, sequential focus skips the viewfinder. The user
  cannot invoke the app's own "Focus at center" equivalent for tap-to-focus or reset the held focus
  point, even though those exact operations are exposed to TalkBack. This is a keyboard-equivalence
  failure for an endpoint-based pointer action under WCAG 2.1.1, not a request to reproduce arbitrary
  freehand focus placement.
- **Suggested fix:** make the viewfinder a deliberate keyboard focus target when either action is
  available and map Enter/Space/DPAD-center to center focus (with a documented key for reset), or
  expose equivalent named focusable controls in the finder traversal. Extend the production Compose
  test to request focus, activate each branch, verify disabled/loading admission, and assert a visible
  focus indication over both bright and dark preview content.

### C48-TDD-03 — the primary Loupe authority still says the current same-stream inset is upright

- **Severity / confidence:** Low / High
- **Status:** **Confirmed documentation contradiction.** Executable behavior and the rerunnable field
  criterion agree; the bold hard-won-fact heading and its first rationale do not.
- **Exact regions:** `CLAUDE.md:238-251` starts with “The Loupe Overview draws UPRIGHT” and says the
  operator gets “the world the right way up,” but the same paragraph later admits that today's
  converter-fed same-stream box shows the raw inverted field. The top-level authority already says
  “raw, inverted field” at `CLAUDE.md:21-26`; `docs/ARCHITECTURE.md:32-37`; and the executable pass
  criterion at `docs/FIELD_CHECKS.md:180-197` requires the inset to be 180 degrees inverted relative
  to the corrected main view. `tools/check_docs.py:1791-1801,1840-1846` merely searches for the
  correct phrase somewhere in each document, so the contradictory “draws UPRIGHT” claim still
  passes all 151 documentation checks.
- **Failure scenario:** a maintainer scanning the bold “hard-won facts” headings, or an operator using
  its first sentences instead of the later honesty limit, treats an inverted current inset as a
  regression and either adds the afocal term back to the finder draw or records C2 against the wrong
  expected result. That would undo the explicitly tested per-draw contract.
- **Suggested fix:** retitle the bullet around renderer truth, for example “Loupe Overview omits the
  afocal term per draw,” and state immediately that this makes a true second-stream wide view upright
  but leaves today's converter-fed same-stream view inverted. Make the docs gate reject
  `Loupe Overview draws UPRIGHT` (and equivalent unqualified current claims), not only require a
  compensating phrase elsewhere.

### C48-TDD-04 — cycle 47 claims every media-kind thumbnail branch is covered, but video-ready is absent

- **Severity / confidence:** Low / High
- **Status:** **Confirmed test/document-evidence gap; no current product failure found.** The shared
  implementation presently appears correct for video-ready.
- **Exact regions:** the completed plan promises production Compose action/state coverage for every
  still/video/RAW branch at `docs/plans/2026-08-25-rpf-cycle47.md:65-70`. The exhaustive-looking table
  in `app/src/test/kotlin/me/hletrd/telecampro/ui/review/GalleryThumbComposeTest.kt:51-111` covers
  video loading and video failed, then RAW-ready and still-ready, but never constructs
  `GalleryThumbState.Ready(ReviewMediaKind.VIDEO, bitmap)`. A repository-wide test search finds no
  other video-ready construction. Production selects both the media-specific action label and the
  ready paint at `ui/review/MediaReview.kt:805-850`, so that exact cross-product is externally visible.
- **Failure scenario:** a refactor can make a ready video say “Review last photo,” lose its ready
  state description, or render the video bitmap through the wrong placeholder while the test named
  “every thumbnail state truthfully” and the completed plan remain green.
- **Suggested fix:** add video-ready (plus its bitmap-pixel assertion), disabled variants, and EN/KO
  state-description assertions to the production surface matrix. Amend the completed plan with a
  dated correction rather than rewriting historical evidence.

### C48-TDD-05 — the obscured-gesture completion record claims production Compose coverage that is only a generic View test

- **Severity / confidence:** Low / High
- **Status:** **Confirmed test/document-evidence gap; no current cancellation bug reproduced.**
- **Exact regions:** `docs/plans/2026-08-25-rpf-cycle47.md:44-49` marks real-Activity **and production
  Compose** tap and drag/pinch cancellation/recovery sequences complete. The only mid-gesture test is
  `app/src/test/kotlin/me/hletrd/telecampro/MainActivityTouchDispatchTest.kt:67-121`; it replaces the
  Activity content with a plain `android.view.View`, records action integers, and runs a one-pointer
  DOWN → obscured MOVE → clean UP sequence. Repository-wide search finds no obscuration test that
  drives the production viewfinder `awaitEachGesture` loop (`ui/CameraScreen.kt:742-790`), the ruler
  drag loop (`ui/controls/ProControls.kt:490-574`), or a live two-pointer pinch terminal.
- **Failure scenario:** Activity dispatch can correctly emit one `ACTION_CANCEL` to a generic View
  while a Compose pointer coroutine fails to run its pinch-end/drag cleanup or to admit the next
  gesture. The exact stuck-state scenario that motivated the fix would then escape the test suite,
  despite the closeout presenting it as production-sequence evidence.
- **Suggested fix:** install real Compose content in `MainActivity`, begin a production tap, ruler
  drag, and two-pointer viewfinder pinch, introduce each obscuration flag mid-stream, and assert
  exactly-once terminal cleanup plus a successful next clean gesture. Append a dated evidence
  correction to the completed cycle-47 plan.

## Full mobile UI/UX sweep — no additional finding

- **IA and affordances:** the Sony-style hierarchy is coherent: quiet finder, stable top/capture
  anchors, Fn for rapid access, nine-category settings rail, My Menu/MR, and a bounded review overlay.
  Capability-disabled choices generally preserve truthful state and explanatory captions instead of
  accepting no-op taps. All material action targets inspected meet the 48 dp floor; the shutter and
  gallery exceed it.
- **TalkBack and live state:** controls consistently combine localized name, role, selected/checked
  or state description, disabled state, and action. AF terminal results, capture statuses, scopes,
  self-timer, review critical states, external-navigation failures, and the newly added gallery
  loading/ready/failed states have deliberate announcement policy. Decorative canvases are not
  duplicated into traversal. Findings 01-02 are the residual focus/keyboard issues.
- **Focus traps:** modal entry and background exclusion are implemented and host-tested; popup/dialog
  children remain reachable. The residual is return-focus ownership, not escape into the finder.
- **WCAG 2.2 contrast and focus appearance:** shared tokens and render tests cover text on live HUD
  plates, 3:1 enabled affordance edges, guide keylines over bright/dark frames, destructive glyphs,
  disabled rail paint, and the paused-video glyph. No new source-confirmed contrast failure was found.
  Final appearance over real HDR/video content remains correctly documented as device/manual work.
- **Loading, empty, error, and perceived performance:** camera progress is condition-owned rather
  than timer-owned; switching has a bounded black dip; shutter gets immediate flash/press/haptic
  feedback; gallery/review distinguish empty, loading, ready, failed, timeout, retry, RAW-only, and
  restart-required states; background decoding is bounded. Static loading marks avoid unnecessary
  motion. No focus trap or hidden infinite progress state was found.
- **Dark/light and reduced motion:** the app intentionally has one deterministic dark camera theme,
  and `MainActivity` pins light system-bar icons even when Android itself is in light mode. Compose
  animations use framework animation primitives that honor the platform duration scale; essential
  shutter feedback remains brief and non-looping.
- **i18n, scaling, and RTL:** EN/KO XML parity and the closed non-translatable abbreviation list are
  enforced; Korean presentation and 2x-font/high-risk layouts have Compose coverage. Reading surfaces
  remain locale-relative. The viewfinder deliberately holds physical camera geometry LTR while text
  still receives Unicode shaping; horizontal overflow hints and sliders explicitly account for RTL.
  No unlocalized user prose or source-confirmed clipping/overlap survived the sweep.
- **Assets:** all phone/tablet PNGs match their declared IHDR geometry/encoding and hashes. The two
  stale phone screenshots remain fail-closed and explicitly block Play submission; no current-artifact
  claim was inferred from them.

## Verification performed

- `python3 tools/check_docs.py` — **151 passed, 0 failed, 24 explicitly optional-private skips**.
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ANDROID_SDK_ROOT=... python3 -m unittest discover -s tools/tests` — **120 passed**.
- `python3 -m unittest discover -s device-tests/tests` — **195 passed**.
- `python3 -m unittest discover -s tools/coverage/tests` — **9 passed**.
- Focused Gradle/Robolectric/Compose run for `ModalFocusComposeTest`,
  `ViewfinderAccessibilityComposeTest`, `GalleryThumbComposeTest`, and
  `MainActivityTouchDispatchTest` — **BUILD SUCCESSFUL**.
- The first standalone tooling invocation selected the conventional
  `/Users/hletrd/Library/Android/sdk`, which lacks the newly required Emulator validator and failed
  seven SDK-preflight-dependent tests. Re-running against the documented complete SDK at
  `/opt/homebrew/share/android-commandlinetools` passed all 120; this is an environment-selection
  fact, not a repository finding.
- No device, camera, MediaProvider, HDR display, TalkBack service, or external keyboard was run.
  Open manual/device risks remain truthfully tracked by `docs/FIELD_CHECKS.md` (A3/A4/A5/D1/E1/E2).

## Totals

- Findings: **5**
- Severity: **2 Medium, 3 Low**
- Confidence: **5 High**
- Product/UI behavior: **1 likely focus-return defect, 1 confirmed keyboard-path gap**
- Documentation/test evidence: **3 confirmed gaps**

