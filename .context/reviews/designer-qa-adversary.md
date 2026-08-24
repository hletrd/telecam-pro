# Cycle 33 native Android designer + QA-adversary review

Date: 2026-08-24

Reviewed revision: `984424cb36ebe409e67bb2e69c8605ea6f41c4bb`

Workspace: isolated clean worktree `/tmp/find-x9-cycle33-latest.Vc7rke`

Mode: static/host-only Jetpack Compose review; no implementation, deployment, emulator, or physical-device claim

## Scope and inventory

I read the complete current `CLAUDE.md` authority and the relevant committed UX/current-state
authorities in `docs/ARCHITECTURE.md` and `docs/FIELD_CHECKS.md`. The optional private
`docs/BACKLOG.md` and `docs/UX_POLICY.md` are absent in this permitted clean worktree. I read the
cycle-32 aggregate, specialist reviews, completed plan, and the cycle-28 through cycle-31 plan
history before filing findings, so the recently fixed level/AF semantics, review bounds,
double-tap provenance, large-font format row, focal-rail fade, ToggleRow, modal focus, and
recording/review gates were rechecked rather than re-filed stale.

The UI inventory covers every production and debug Compose surface under `ui/**`, `MainActivity`
permission/system-dialog ownership, the complete EN/KO resources, manifest/theme/RTL declarations,
UI-facing `CameraUiState`/`CameraActions` seams, all UI/Compose/Robolectric/instrumentation tests,
and the deterministic snapshot host. I traced viewfinder, top/bottom chrome, Fn and manual rulers,
all nine settings tabs, review still/video/RAW paths, delete confirmations and ownerless system
consent, loading/empty/error/retry states, phone/tablet/window rotation, 320 dp/large-font reflow,
keyboard/D-pad/Switch Access, TalkBack semantics, EN/KO and RTL behavior, dark-theme/system bars,
and live-overlay contrast/perceived responsiveness. This is a native Android app, so browser skills
are not applicable. No screenshot was treated as evidence; every finding below is source-backed.

## Findings

### DQA33-01 — the repaired double-tap recognizer uses scroll slop, not double-tap slop

- **Severity / confidence:** Low / High
- **Classification:** Confirmed gesture-recognition regression
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:1009-1018,
  1833-1859`; regression fixture
  `app/src/test/kotlin/me/hletrd/telecampro/ui/review/MediaReviewGestureTest.kt:91-126`.
- **Problem:** Cycle 32 correctly added a spatial condition, but production passes
  `viewConfiguration.touchSlop` as the maximum distance between taps. Touch slop is the much
  smaller threshold for deciding that one contact has become a scroll; Android exposes a separate
  `scaledDoubleTapSlop` specifically as the allowed distance between the first and second contacts.
  The test hides the mismatch by inventing a fixed `18f` threshold rather than exercising the
  device's double-tap configuration. The source comment says the reducer matches platform gesture
  behavior, but it now rejects many gestures that Android's `GestureDetector` accepts.
- **Concrete failure scenario:** A photographer taps a bird's eye twice with normal finger-placement
  variation greater than touch slop but well inside Android's double-tap slop. Review treats the
  second contact as a new first tap and does nothing, making the primary quick focus-check gesture
  feel intermittent even though its timing is valid.
- **Suggested fix:** Feed the real platform double-tap distance from
  `android.view.ViewConfiguration.get(context).scaledDoubleTapSlop` (or restore a platform/Compose
  recognizer while retaining single gesture ownership). Keep touch slop only for classifying motion
  within one contact. Test the production threshold, including a distance between the two values
  that must be accepted.

### DQA33-02 — off-center pinch zoom does not preserve the detail under the fingers

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed review interaction defect
- **Exact region:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:975-990`;
  the point-preserving helper used only by long/double-tap is at `MediaReview.kt:1778-1795`.
- **Problem:** The pinch loop multiplies `scale`, clamps the old `offset`, and then adds only
  `calculatePan()`. It never applies the zoom-centroid correction. Because `graphicsLayer` scales
  around the viewport center, an off-center detail moves away from the fingers on every zoom event.
  The new `centerOn` geometry demonstrates that the code already accounts for this requirement for
  long-press and double-tap, but the continuous pinch path bypasses it. Current tests cover bounds
  and point-centering, not a moving off-center pinch centroid.
- **Concrete failure scenario:** The operator spreads two fingers around a subject near a frame
  corner to judge telephoto focus. Instead of that subject staying under the fingers, it slides
  toward and potentially beyond the viewport edge, forcing a corrective pan during the same focus
  check.
- **Suggested fix:** Apply zoom and pan as one centroid-owned transform: preserve the content point
  under `calculateCentroid()` across `oldScale -> newScale`, then add centroid pan and clamp once.
  Extract the transform into the existing pure geometry seam and test center, corner, simultaneous
  pan+zoom, and clamp-edge cases.

### DQA33-03 — zoomed still review has no non-touch way to pan

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed keyboard/Switch Access accessibility defect
- **Exact regions:** pointer-only pan owner
  `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:914-1044`; still semantics
  `MediaReview.kt:1283-1297`.
- **Problem:** Touch users can pan whenever `scale > 1`, but the still node exports only Zoom 4x,
  Zoom 8x, and Reset Zoom custom actions. It has no D-pad/arrow handling, scroll-axis semantics, or
  directional custom actions. The visible bottom action can change magnification but always resets
  the offset to center. A keyboard, switch scanner, or other action-only accessibility user can
  therefore enter 4x/8x review but cannot reach any content outside the centered viewport.
- **Concrete failure scenario:** A Switch Access user activates Zoom 8x to inspect a subject placed
  by the rule of thirds. Only the image center remains reachable; repeated scanning finds close,
  delete, reset/zoom, and the image node, but no action that moves to the subject.
- **Suggested fix:** Give the focused still node bounded directional pan actions and arrow/D-pad
  handling backed by `ReviewStillGeometry.clampOffset` (or expose truthful horizontal/vertical
  scroll semantics that assistive technology can operate). Announce a coarse position without a
  live region, and test center/edge enablement, RTL-independent image geometry, keyboard, and
  custom-action paths.

### DQA33-04 — the audio meter, histogram, and waveform expose no accessible readings

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed non-text-information accessibility defect
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:570-603,
  1002-1086`; production callers
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:1173-1189`.
- **Problem:** All three live instruments are Canvas-only and add no semantics. Their settings
  toggles are accessible, but once enabled TalkBack/Switch Access cannot inspect channel silence or
  clipping, histogram shadow/highlight clipping, waveform range, data-pending state, or even the
  identity of the visible instruments. This is the same defect class cycle 32 fixed for the horizon
  level, but it remains on the other quantitative finder instruments.
- **Concrete failure scenario:** A screen-reader user arms Video with a stereo external microphone.
  One channel is dead while the other moves. Sighted users see the per-channel bars—the exact reason
  the meter was changed from an average—but accessibility traversal exposes no audio-level node or
  dead-channel state. Enabling Histogram/Waveform likewise creates no inspectable result.
- **Suggested fix:** Add stable localized instrument identities and coarse, change-gated readings
  (for example channel N silent/normal/near clipping; histogram shadows/highlights clipped;
  waveform luma range/data pending). Keep them non-live to avoid telemetry chatter, or offer an
  explicit “read current levels” action that snapshots the value. Add EN/KO Compose semantics tests
  for disabled/empty/live/clipped data and multiple audio channels.

### DQA33-05 — composition guides and the horizon gauge can disappear on bright scene content

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed WCAG 2.2 non-text-contrast and outdoor-legibility defect
- **Exact regions:** shared guide token
  `app/src/main/kotlin/me/hletrd/telecampro/ui/theme/Theme.kt:128-145`; guide draws
  `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:130-170`; horizon draws
  `Overlays.kt:318-350`.
- **Problem:** Frame lines and every grid are a single 40%-white stroke drawn directly over arbitrary
  pixels. The horizon datum is 22%-white and its moving line is 72%-white, or translucent yellow
  when level. None has a dark keyline/halo or adaptive contrast. Over white sky, snow, water glare,
  or a clipped highlight, white-over-white has 1:1 contrast regardless of alpha, and yellow-on-white
  is also below the 3:1 graphical-object floor. The token comment's claim that the 40% line
  “resolves over a bright sky” is therefore not true for the scene class it names.
- **Concrete failure scenario:** With thirds plus Level enabled while framing a bird against an
  overcast white sky, the guide cells and non-level indicator disappear across the sky. When the
  camera becomes level, the yellow result is still low-contrast, so the operator loses both the
  composition reference and the terminal level cue precisely outdoors.
- **Suggested fix:** Preserve the quiet 1 dp/2 dp foreground weights but draw an opaque or
  sufficiently dark wider keyline beneath them, as `FocusReticle` already does for arbitrary
  preview content. Add rendered bright/dark/midtone fixtures for grids, frame lines, and level/non-
  level states, asserting the visible outer edge meets the non-text contrast floor without making
  the foreground heavier.

## Final missed-issue sweep

I re-walked every interactive Compose primitive, custom Canvas, pointer owner, semantics modifier,
modal/Back/focus boundary, horizontal/vertical scroller, locale resource, phone/tablet layout seam,
permission and ownerless-delete state, review state transition, and current UI-focused regression.
I also searched the complete current review/plan history for each issue class above. No finding is
a stale cycle-32 item: the review image is now bounded, AF and horizon share one viewfinder identity,
format chips remain reachable at 320 dp/2x font, modal roots suppress the finder, and current
loading/error/delete/localization paths are present. Hardware pixel appearance, TalkBack speech
timing, and field behavior not provable from source remain unclaimed.

**Finding count: 5 total — 4 Medium, 1 Low.**
