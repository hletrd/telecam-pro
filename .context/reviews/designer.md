# UI/UX Design Review — Find X9 Ultra Teleconverter Camera

**Reviewer role:** UI/UX Designer-Developer, judging the Compose viewfinder as a *professional* camera app
for photographers migrating from **Sony** (BIONZ XR2: dedicated dials, Fn menu, always-on metered HUD)
and **Google Pixel Camera** (near-empty chrome, discoverable, fast, forgiving).
**Date:** 2026-07-03 · **Scope:** `ui/CameraScreen.kt`, `ui/CameraViewModel.kt`, `ui/CameraActions.kt`,
`ui/controls/{ManualDials,ProSheet,ProControls}.kt`, `ui/overlays/Overlays.kt`, `ui/theme/Theme.kt`,
`camera/CameraState.kt` + `camera/ManualControls.kt`, `MainActivity.kt`.
**Method:** read the Compose source (layout, hierarchy, affordances, state) — no fabricated device
screenshots; the emulator has synthetic camera HW so a live render is uninformative for tele/RAW/HLG.

The bones are genuinely good: a clean Pixel-style resting viewfinder, a Sony-style tabbed pro menu,
an inventive scrolling tick-ruler for the Fn dials, a deterministic dark theme, and an honest
"∞ + N" relative focus scale that respects the afocal optics. What follows is where it falls short of
a demanding pro's expectations. **This is a tele-first, manual-focus-critical, RAW/HLG-capable
instrument** — and the review is weighted accordingly: exposure verification, focus confirmation, and
capture honesty matter more here than on a phone point-and-shoot.

---

## Summary by severity

| Sev | Count | IDs |
|---|---|---|
| **High** | 8 | UX-1 · UX-2 · UX-3 · UX-4 · UX-5 · UX-6 · UX-7 · UX-8 |
| **Medium** | 16 | UX-9 … UX-24 |
| **Low** | 6 | UX-25 … UX-30 |
| **Total** | **30** | |

### Top 5 gaps vs Sony / Pixel (the ones that will actually lose a migrating pro)
1. **No metered-manual exposure indicator (UX-1).** In manual, nothing tells you if ISO+shutter is
   over/under. Sony's `M.M.` scale and Pixel's EV readout are both absent; the histogram is off by default.
2. **No capture confirmation and a dead gallery thumbnail (UX-2, UX-5).** No screen blink, no haptic,
   no thumbnail update, no tap-to-review. At 300 mm you *must* chimp focus; there is no way to.
3. **The HUD lies about what you're capturing (UX-3).** The status bar shows *requested* HEIF+DNG/HLG,
   but the HAL fallback ladder can silently drop RAW/HLG. A pro thinks they got a RAW they never got.
4. **Everything lives at the top of a 3168 px screen (UX-4).** The only entrance to 90% of controls is
   a 36 dp gear in the top-right corner — physically unreachable one-handed on this phone.
5. **Below-spec touch targets everywhere (UX-9)** — 36 dp chrome buttons, ~36 dp Fn chips, ~30 dp mode
   labels. A pro shooting fast, gloved, or one-handed will mis-tap constantly.

---

# High severity

## UX-1 — No metered-manual exposure indicator (the single biggest pro gap)
**Where:** `ui/controls/ManualDials.kt` `DialChipRow` (L120-188) + `ProSheet.kt` `ExposureColorTab`
(L351-427); nothing in `Overlays.kt` renders a meter.
**Problem:** In manual exposure the UI shows the *chosen* ISO and shutter, but never the *metered
result*. There is no exposure-scale needle, no "+1.3 EV over" readout, no metered-manual (`M.M.`)
indicator. The histogram (`state.histogram`) is `false` by default (`CameraState.kt` L92) and is buried
in the Assists tab.
**Who it hurts:** Sony migrants first (they live by the `-3…+3` bottom scale with the amber needle and
`M.M.` badge), Pixel migrants second (Pixel always simulates final exposure and shows the EV chip).
**Scenario:** Photographer sets ISO 400 + 1/125 s through the tele at dusk. The preview is dim, but is
it -2 EV under or is that just the dark subject? They have no instrument to answer this, so they bracket
blindly and burn the moment.
**Fix:** Add an always-visible exposure meter when `!controls.autoExposure`. Concretely, a horizontal
scale (−3…0…+3 EV, tick every 1 EV) with a needle driven by a metered-vs-set delta from the engine
(you already read `TotalCaptureResult`; expose a metered-EV estimate on `CameraUiState`). Place it just
above the dial cluster in the bottom scrim (reuses the existing gradient for legibility). Minimum spec:
280 dp wide × 24 dp tall, needle in `ManualActive` (#FFD60A), 0-mark and clip zones flagged. Cheaper
interim: force a compact histogram visible by default in manual mode. **Confidence: High.**

## UX-2 — No capture confirmation feedback (shutter fires into the void)
**Where:** `ui/CameraScreen.kt` `ShutterButton` (L662-689) — a bare `Canvas` with `.clickable`, no
ripple/scale/haptic; `onCapturePhoto` wiring (L262-270); `CameraViewModel.onCapturePhoto` (L238-242)
sets no "capturing" state.
**Problem:** Tapping the shutter produces zero visual/haptic acknowledgement — no screen blink, no
shutter-ring animation, no haptic, and the gallery thumbnail never changes (see UX-5). The only feedback
channel is `statusMessage`, which may not even fire on success.
**Who it hurts:** Everyone, but especially Pixel migrants (Pixel does a crisp white blink + thumbnail
fly-in) and anyone shooting fast — they'll double-fire thinking the first tap missed.
**Scenario:** Burst of hand-held 300 mm frames; the user can't tell which taps registered, re-taps, gets
duplicate/blurred frames, and doesn't trust the button.
**Fix:** (a) Animate the shutter on press — scale 1.0→0.88→1.0 over ~120 ms via `animateFloatAsState`,
and add `Modifier.clickable` ripple or an explicit press-state ring. (b) A brief full-screen white flash
overlay (`Color.White` alpha 0→0.85→0, ~90 ms) on `onCapturePhoto`, gated to PHOTO. (c)
`view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)` on fire. (d) Update the thumbnail (UX-5).
Add a `capturing: Boolean`/`lastCaptureUri` to `CameraUiState` so the stateless UI can react.
**Confidence: High.**

## UX-3 — The HUD reports *requested*, not *actual*, capture configuration
**Where:** `ui/overlays/Overlays.kt` `StatusBar` (L252-290) reads `state.photoFormats` and
`state.transfer`; `CameraScreen.kt` L198-210 passes them straight through. Per `ARCHITECTURE.md` the
`CameraController.configureSession` fallback ladder can drop RAW then HLG silently.
**Problem:** The status strip shows "HEIF+DNG" and "HLG" because that's what the user *asked for* — but
if the session fell back (attempt ≥1 drops RAW, attempt ≥2 drops HLG), the UI still claims them. The HUD
asserts a capability the pipeline isn't delivering.
**Who it hurts:** The RAW-shooting Sony pro who deliberately enabled DNG and later discovers, at the
computer, that half the shoot has no RAW. This is a trust-destroying, silent data loss.
**Scenario:** On this HAL, full RAW+HLG+JPEG crashes and the ladder drops to JPEG-only; the user keeps
seeing "DNG · HLG" all day.
**Fix:** Surface the *actually configured* streams from the engine (you already log
`Session configured (fallback=…, hlg=…, raw=…)`) onto `CameraUiState` (e.g. `activeFormats`,
`activeTransfer`, `fallbackLevel`). Render the StatusBar from those, and when the achieved config is
below the request, show a persistent amber warning chip ("RAW unavailable — JPEG only") rather than a
transient toast. Colors: warning in `ManualActive` (#FFD60A) on the existing 0.45 black scrim.
**Confidence: High.**

## UX-4 — Primary settings entrance is unreachable one-handed on a 3168 px screen
**Where:** `ui/CameraScreen.kt` `GearButton` at `.align(TopEnd)`-side of `TopBar` (L328-354, gear at
L352/531), plus the whole quick-toggle row (flash/timer/aspect/grid/TELE) pinned top-left (L342-351).
**Problem:** The device is 1440 × 3168 (portrait-locked). The gear — the *only* door to 8 tabs of pro
controls — sits in the top-right corner, ~15 cm from the thumb of a hand holding the phone to shoot. The
top quick-toggles are equally far. Only the bottom cluster (dials/mode/shutter) is reachable.
**Who it hurts:** Both migrants, but it's the opposite of Pixel's reachability ethos and worse than
Sony's physical Fn button that falls under the thumb.
**Scenario:** One hand braces a heavy tele rig against a railing; the other hand is on the phone. Opening
settings or toggling grid requires a two-handed juggle or a shift of grip that risks the shot.
**Fix:** Add a reachable path: a swipe-up from the bottom scrim to open `ProSheet`, and/or a small gear
mirrored into the bottom cluster near the dials. Consider a "reachability" one-handed mode that drops the
top toggles to a thumb-zone drawer. At minimum, make the gear reachable — duplicate it at the
bottom-right of the dial cluster (48 dp). **Confidence: High** (ergonomics are measurable from screen
size).

## UX-5 — Gallery thumbnail is a dead placeholder; no shot review
**Where:** `ui/CameraScreen.kt` `GalleryThumbPlaceholder` (L628-659) — explicitly "not wired to any
CameraActions … purely decorative"; `ShutterRow` L611.
**Problem:** A 52 dp control that looks tappable does nothing and never reflects the last shot. There is
no in-app review at all — `CameraActions` (L30-109) has no "open last capture" method.
**Who it hurts:** Everyone. On a 300 mm manual-focus tele, *chimping the last frame at 100 %* is the
core focus-confirmation loop. Both Sony and Pixel make the last shot one tap away.
**Scenario:** Manual-focus a distant subject, shoot, need to verify critical focus — impossible without
leaving the app for the system gallery, losing the camera session and the moment.
**Fix:** Wire the thumbnail to the last `MediaStore` URI (add `lastCaptureThumb`/`onOpenReview` to state
+ actions). Tap → a lightweight full-screen review with pinch-zoom (focus check) and a swipe-away. Even a
minimal "load last image bitmap into the thumb + tap opens system viewer via `ACTION_VIEW`" closes 80 %
of the gap. If review is truly out of scope, *remove* the fake affordance — a dead 52 dp target is worse
than none. **Confidence: High.**

## UX-6 — Permission flow is a dead-end on permanent denial, generic, and over-asks
**Where:** `MainActivity.kt` `PermissionGate` (L88-101), launcher (L51-59), `REQUIRED_PERMISSIONS`
includes `RECORD_AUDIO` up front (L84), `hasCamera` checks only CAMERA (L54).
**Problem:** Three issues. (a) If the user picks "Don't allow" twice (permanent denial), the "Grant
Permission" button just re-launches a system dialog that no longer appears → **hard dead-end**, and on
ColorOS `pm grant` doesn't work either. (b) The gate is default-Material generic (`Surface` + plain
`Text`/`Button`) with no explanation of *why* camera+mic are needed for a tele. (c) `RECORD_AUDIO` is
requested at first launch before the user ever enters Video — a Play/Pixel anti-pattern (request
in-context).
**Who it hurts:** Any user who reflexively denies once; they can be permanently locked out with no
recovery path — a likely 1-star review and a Play data-safety concern.
**Scenario:** User denies to "look first", relaunches, taps Grant, nothing happens, uninstalls.
**Fix:** (a) Detect permanent denial (`shouldShowRequestPermissionRationale == false` after a denial) and
switch the button to "Open App Settings" → `ACTION_APPLICATION_DETAILS_SETTINGS`. (b) Style the gate on
brand: dark `CameraColors.Background`, a lens/tele glyph, a one-line rationale ("Camera to see through
the 300 mm converter; microphone for video sound"). (c) Drop `RECORD_AUDIO` from the launch request;
request it the first time recording starts with audio on. **Confidence: High.**

## UX-7 — No persistent loading or hard-error state; camera failure is a transient toast
**Where:** `ui/CameraScreen.kt` renders full chrome regardless of `state.caps == null`; only feedback for
failure is `statusMessage` (L238-250), an ephemeral top-center text. `StatusBar` shows "-" / "--" while
caps load (L190-197, `Overlays.kt` L270-274).
**Problem:** (a) **Loading:** on launch the preview is black and the HUD shows "-"/"--" with fully live
controls — it reads as *broken*, not *starting*. (b) **Error:** if the fallback ladder is exhausted and
the session never configures, the app shows a black viewfinder with working buttons and a toast that
scrolls away — no retry, no explanation, no recovery.
**Who it hurts:** Everyone at cold start; and anyone who hits the HAL crash path gets a mute black
screen.
**Scenario:** Cold launch in a hurry → 1–2 s of "broken-looking" black chrome; or a HAL config failure →
silent dead viewfinder.
**Fix:** Add explicit `CameraStatus { Initializing, Ready, Error(reason) }` to state. While Initializing,
show a subtle centered spinner/"Starting camera…" and dim controls. On Error, show a persistent card with
the reason and a "Retry" button (re-run `configureSession`). Never leave the user with live controls over
a dead preview. **Confidence: High.**

## UX-8 — Accessibility: zero semantics on every control; TalkBack gets nothing
**Where:** Grep across `ui/` returns **no** `contentDescription`, `semantics`, `role`, `toggleable`, or
`selectable`. Every affordance is a `Canvas`/`Box` with `.clickable` — `ChromeIconButton` (L378-392),
all top-bar glyphs, `ShutterButton` (L662), `LensFlipButton` (L717), `DialChip` (L191), tab-rail items
(`ProSheet.kt` L188).
**Problem:** A blind or low-vision user (and Play's pre-launch accessibility scan) sees unlabeled tap
targets. The shutter is announced as "button", the flash toggle as nothing. No state is exposed (a
`Switch` gets some default semantics, but the custom canvas toggles do not).
**Who it hurts:** Accessibility users entirely; also costs you Play pre-launch-report warnings and the
"tested for accessibility" credibility a pro tool should have.
**Scenario:** TalkBack user swipes the top bar and hears "button, button, button, button".
**Fix:** Add `Modifier.semantics { contentDescription = …; role = Role.Button/Switch;
stateDescription = … }` to every custom control. Shutter → "Shutter, take photo" / "Stop recording";
flash → "Flash: Auto" with state; dial chips → "ISO 400, adjust"; use `Modifier.toggleable`/`selectable`
for the FilterChip-equivalents and mode labels. Wrap decorative canvases (grid, glyphs) with
`Modifier.clearAndSetSemantics {}`. Route labels through `strings.xml` (see UX-30). **Confidence: High.**

---

# Medium severity

## UX-9 — Touch targets below the 48 dp minimum across primary chrome
**Where:** `ChromeIconButton` `.size(36.dp)` (`CameraScreen.kt` L385) → flash/timer/aspect/grid/gear all
36 dp; `TeleChip` `.height(36.dp)` (L519); `DialChip` `padding(h=14, v=9)` around ~14 sp text ≈ 36 dp
tall (`ManualDials.kt` L213); `ModeLabel` ≈ 30 dp tall (`CameraScreen.kt` L573-597, `vertical = 2.dp`);
`CloseButton` `.size(32.dp)` (`ProSheet.kt` L151); `SnapshotButton` `.size(36.dp)` (L697).
**Problem:** Material and WCAG target ≥ 48 dp; these frequent controls are 30–36 dp. On this ultra-dense
panel 36 dp is physically tiny.
**Who it hurts:** Fast/one-handed/gloved shooters mis-tap flash when reaching for grid, or miss the mode
switch entirely.
**Scenario:** Reaching a thumb to flip TELE mid-action, the user hits the adjacent grid button.
**Fix:** Bump `ChromeIconButton` to 44–48 dp (keep the 16–18 dp glyph, grow the hit area/scrim), the mode
labels to a ≥ 48 dp tall row, dial chips to ≥ 44 dp, close button to 44 dp. Where visual size must stay
small, expand only the touch area via `Modifier.sizeIn(minWidth/minHeight = 48.dp)` or
`minimumInteractiveComponentSize()`. **Confidence: High.**

## UX-10 — `LensFlipButton` masquerades as a camera-flip; it's a third duplicate of TELE
**Where:** `ui/CameraScreen.kt` `LensFlipButton` (L716-735), placed in the shutter row where every phone
camera puts front/back flip; its own doc-comment concedes "there is nothing to flip to".
**Problem:** It draws a lens glyph in the canonical "switch camera" slot but actually toggles the
teleconverter — which is *also* the top-bar TELE chip (L350) *and* a Stabilization-tab switch
(`ProSheet.kt` L462). One boolean, three controls, one of them impersonating a different universal
function.
**Who it hurts:** Pixel migrants tap it expecting the selfie cam and instead flip their whole optical
mode 180°.
**Scenario:** User wants a selfie-style framing check, taps the "flip" glyph, and the image inverts +
EIS re-scales to 300 mm — baffling.
**Fix:** Repurpose that reachable slot for something the app actually needs and lacks reachably — the
**gear/settings** (UX-4), a **histogram quick-toggle** (UX-23), or **review** (UX-5). If it must stay a
tele toggle, redraw it unambiguously (e.g., a "300 mm" text chip) and drop one of the two other
duplicates. **Confidence: High.**

## UX-11 — Manual focus isn't linked to a magnifier, and hyperfocal/DoF data goes unused
**Where:** `ManualDials.kt` `FocusRuler` (L249-266) + `ManualDialCluster` focus branch (L108-111);
`punchIn` is an unrelated manual toggle in the Assists tab (`ProSheet.kt` L557); `CameraCaps` exposes
`hyperfocalDiopters` (`CaptureCapabilities.kt` L22) — **never surfaced in UI**.
**Problem:** This app's raison d'être is critical manual focus near infinity through the afocal
converter, yet grabbing the Focus dial does **not** auto-engage the punch-in magnifier (Sony's Focus
Magnifier auto-triggers on MF ring touch), and the known hyperfocal point / DoF is never shown even
though it's computed.
**Who it hurts:** Every serious tele shooter — the hardest, most important task in the app is the least
supported.
**Scenario:** User nudges focus by one ruler tick to nail a distant ridge line; without a magnified view
or peaking auto-on, they can't see the micro-change on a full-frame preview.
**Fix:** When the Focus dial opens (or `focusMode == MANUAL`), auto-enable punch-in (call
`engine.setPunchIn(true)`) and focus peaking, restoring prior state on close. Add an "∞ / hyperfocal"
marker on the focus ruler using `caps.hyperfocalDiopters` (a labeled major tick). Consider a peaking
color choice (yellow default clashes with `ManualActive`; offer red/cyan). **Confidence: Medium.**

## UX-12 — Rotation handling is inconsistent; corner readouts spin in place and can clip
**Where:** `CameraScreen.kt` `overlayRotation` (L117-120) is applied to `StatusBar` (L209) and the
histogram/waveform boxes (L227, L230) — but **not** to `RecordingIndicator`/`AudioMeter` (L220-224), and
**not** to the TopBar toggles, mode carousel, dial cluster, or shutter. The rotated `StatusBar` is a wide
`Row` pinned to `TopStart` and rotated about its own center.
**Problem:** Two defects. (a) **Partial coverage:** turn the phone 90° and the scopes/status rotate while
the record timer, the dial labels ("ISO", "Focus"), and the whole toolbar stay portrait — a mix of
upright and sideways text. (b) **Spin-in-corner:** rotating a wide horizontal strip 90° in a corner
swings its long axis vertical; it can extend past the status-bar inset and overlap the TopBar or clip off
the top edge, rather than re-anchoring to the new top edge the way Sony/Pixel translate their readouts.
**Who it hurts:** Anyone shooting landscape (most tele wildlife/sports) — the HUD becomes a patchwork of
orientations.
**Scenario:** Turn to landscape for a horizon subject; the exposure/dial text is now sideways while the
histogram is upright.
**Fix:** Decide one policy and apply it uniformly. Either (a) rotate *all* readouts/labels consistently
(add `overlayRotation` to the record indicator + dial value text), or better (b) re-*position* corner
HUD elements to the orientation-correct edge instead of spinning them in a fixed corner. For the dial
value text specifically, counter-rotate just the glyph/number so digits stay upright. **Confidence:
Medium.**

## UX-13 — No pinch-to-zoom and no viewfinder swipe gestures
**Where:** `CameraScreen.kt` the preview `AndroidView` handles only `detectTapGestures` for focus
(L130-136). Zoom lives as a slider in `ShootingTab` (`ProSheet.kt` L309-317); `caps.zoomRatioRange`
exists.
**Problem:** Both Sony (touch models) and Pixel support pinch-to-zoom on the viewfinder; here the only
way to change `zoomRatio` is to open a full-screen modal and drag a slider you can't preview against
live. There's also no swipe-to-change-mode. The brief flags tap/pinch/swipe conflict — currently there's
no conflict only because two of the three gestures don't exist.
**Who it hurts:** Everyone; it's a baseline expectation that its absence reads as unfinished.
**Scenario:** User instinctively pinches to fine-tune digital zoom on a distant subject; nothing happens.
**Fix:** Add `detectTransformGestures` for pinch → `onZoomRatio`, composed with the existing tap. Add a
horizontal swipe on the bottom cluster (or viewfinder) to switch Photo/Video. Guard conflicts: treat a
two-finger gesture as zoom, single-tap as focus, and confine mode-swipe to the mode carousel band so it
never eats a focus tap. **Confidence: Medium.**

## UX-14 — Tapping the WB dial detonates a full-screen modal on the wrong-looking tab
**Where:** `ManualDials.kt` `ManualDialCluster` onSelect WB branch (L96-100) →
`onRequestWhiteBalanceSheet()` → `CameraScreen.kt` opens `ProSheet` at `ProSheetTab.EXPOSURE` (L289).
**Problem:** The WB Fn chip behaves unlike every other dial: instead of an inline ruler it throws up the
full settings sheet, on a tab labelled "Exposure/Color" where WB is *below the fold* (after mode, AE
lock, anti-flicker, shutter mode, exposure step, ISO, metering — `ProSheet.kt` L352-409). The user taps
"WB" and lands on a wall of exposure controls, having lost the live preview.
**Who it hurts:** Both migrants — Sony's WB is a quick dedicated pick; Pixel's is an inline slider. This
is a jarring, mode-switching detour.
**Scenario:** User taps WB to warm the image slightly and is dumped into a modal, must scroll, adjusts
Kelvin without seeing the preview change.
**Fix:** Make WB behave like the other dials: tapping it opens an inline preset picker over the live
preview (Auto / Daylight / Cloudy / Shade / Incandescent / Fluorescent / Manual), and if Manual, the
existing Kelvin ruler (`WbRuler` already exists, L383-397). Only route to the sheet for the rarely-touched
tint. If a sheet must open, deep-link to a dedicated WB section, not the top of Exposure. **Confidence:
Medium.**

## UX-15 — No AE/AF/AWB lock indicator or long-press-to-lock; tap reticle gives no result
**Where:** Lock toggles exist only in `ProSheet` (`aeLock` L361, `awbLock` L426, `afLock` L441); no
viewfinder indicator. `FocusReticle` (`Overlays.kt` L172-195) is a static yellow bracket that auto-hides
after 2 s (`CameraViewModel` L105-115) with no success/fail state and no exposure control.
**Problem:** (a) When AE/AWB/AF lock is engaged there's no on-screen badge — the user can't tell locking
is active. (b) There's no gesture to lock (Pixel/Sony long-press-to-lock AE/AF); it's buried in a modal.
(c) The tap-to-focus reticle never turns green on lock/fail and offers no exposure slider (Pixel shows a
sun slider beside the point).
**Who it hurts:** Sony migrants expect an AEL badge; Pixel migrants expect long-press lock + tap-exposure.
**Scenario:** User taps a subject to meter+focus, sees a bracket flash and vanish, with no confirmation
it locked or where exposure was set.
**Fix:** (a) Show persistent `AEL`/`AWBL`/`AF-L` chips in the HUD when locked. (b) Long-press on the
viewfinder → lock AE/AF at that point, indicated by the reticle latching (solid) until tapped away. (c)
Reticle: animate to green on `AF locked`, red on fail (needs an AF-state field on state); add a vertical
EV slider beside the reticle in auto-exposure. **Confidence: Medium.**

## UX-16 — Palette drift: overlays hardcode off-token colors; grid vanishes on bright scenes
**Where:** `Overlays.kt` uses `Color(0xFF4C9AFF)` for transfer (L285), `Color(0xFF4CD964)` for EIS/level/
waveform (L287, L146, L356), `Color(0xFFFF5252)` for the red histogram channel (L308) and
`Color(0xFFFF3B30)` for the record dot (L212) — while `Theme.kt` defines `Accent #8AB4F8`, `Record
#FF3B30`, `ManualActive #FFD60A` as the tokens (L23-27) and explicitly asks callers to reference them.
Grid lines are `Color.White.copy(alpha = 0.55f)` single-stroke (`Overlays.kt` L45).
**Problem:** (a) There are ~4 blues, 2 greens, 2 reds scattered as literals that don't match the token
set — the exact "designer smell" the theme file warns against, and they'll drift further over time. (b)
A single 55 %-white grid line **disappears over a bright/white scene** (snow, sky) — the classic HUD
legibility failure the brief calls out (WCAG-ish over arbitrary scenes).
**Who it hurts:** Visual coherence for everyone; grid legibility for landscape/sky shooters.
**Scenario:** Rule-of-thirds composition against an overcast sky — the grid is invisible.
**Fix:** (a) Route every overlay color through `CameraColors` (add `Info`, `Good`, `Warn` tokens; unify
the blues to `Accent`, greens to one `Good`, reds to `Record`). (b) Draw grid/level lines as a
**dual-tone** stroke — a 2 dp black line at ~30 % under a 1 dp white line — so they read on any
luminance. Same treatment for the focus reticle. **Confidence: High** (colors are literal in-code).

## UX-17 — Instrument readouts aren't tabular; digits jitter as values change
**Where:** `Theme.kt` `FindX9TeleTheme` passes only `colorScheme`, no `typography` (L54-59) → default
Roboto proportional figures. Numeric readouts: `RulerReadout` (`ManualDials.kt` L237-247), `DialChip`
values (L218), `StatusBar` focal/format (`Overlays.kt` L282-284), `RecordingIndicator` time (L214).
**Problem:** With proportional figures, a readout changing "1/125" → "1/1000" or "00:09" → "00:10"
shifts width and reflows/recenters — a fidgety, non-instrument feel. Sony/pro HUDs use fixed-width
numerals so the needle-side numbers stay rock-steady.
**Who it hurts:** Anyone watching a value while dragging a dial — the number dances under the fixed
center indicator, undermining the precision the ruler metaphor promises.
**Scenario:** Dragging the shutter ruler, the readout keeps micro-shifting horizontally as digit widths
change.
**Fix:** This is an *instrument*, not editorial — so the domain-correct distinctive choice is a
**technical monospaced numeric treatment**, not a display serif. Define a `Typography` for the app with
tabular figures: either a bundled monospace (e.g. JetBrains Mono / IBM Plex Mono) for readouts, or apply
`fontFeatureSettings = "tnum"` on Roboto's numeric styles. Reserve it for numbers (ISO/shutter/K/EV/time)
so labels stay in the clean sans. **Confidence: Medium.**

## UX-18 — No haptic detents on dials, sliders, or mode changes
**Where:** `RulerSlider` snaps to stops (`ManualDials.kt` L471-483) with no haptic; ISO/shutter stop
snapping (L324-350); `ModeLabel` mode change (L573); no `performHapticFeedback` anywhere in `ui/`.
**Problem:** The whole Fn interaction is sold as *dials* with detented stops, but there's no tactile
tick as the value crosses each stop. Sony's dials click; Pixel gives slider/mode haptics. Silent
snapping feels mushy and makes eyes-off adjustment impossible.
**Who it hurts:** Fast shooters adjusting by feel; the dial metaphor loses its payoff.
**Scenario:** User drags ISO expecting to feel each 1/3-stop; instead the number just slides.
**Fix:** Fire `HapticFeedbackConstants.CLOCK_TICK` (or `SEGMENT_TICK` on API 34+) each time the snapped
stop index changes inside `RulerSlider`, and `CONFIRM`/`SEGMENT_FREQUENT_TICK` on mode switch and on
open/close of a dial. Respect the system haptic setting. **Confidence: Medium.**

## UX-19 — `ProSheet` fully occludes the live preview while adjusting visual settings
**Where:** `ProSheet.kt` `ModalBottomSheet` with `skipPartiallyExpanded = true` (L94) and content at
`fillMaxHeight(0.82f)` (L122).
**Problem:** WB, transfer function (HLG/LOG), color effect, processing, EIS strength, aspect, grid — all
*visual* settings whose whole point is to be judged against the image — are edited on a modal that covers
82 % of the screen. You change LOG→HLG or Kelvin and can't see the result until you dismiss.
**Who it hurts:** Colorists and anyone grading exposure/WB — the Sony pro who tweaks Creative Look while
watching the frame, the Pixel user used to inline sliders over live preview.
**Scenario:** Adjusting Kelvin to neutralize a grey card — impossible to judge with the card hidden.
**Fix:** Make visual settings adjustable over a visible preview. Two good options: (a) a
partially-expanded sheet (`skipPartiallyExpanded = false`) that leaves the top ~40 % of the viewfinder
visible, or (b) promote the visual controls (WB, transfer, color effect, EIS strength) into the inline
dial/quick-strip layer so the modal is reserved for non-visual config (formats, codec, drive, camera
override). **Confidence: Medium.**

## UX-20 — Tab-rail glyphs are cryptic abstractions with 10 sp labels
**Where:** `ProSheet.kt` `drawTabIcon` (L220-286) — hand-rolled abstract Canvas primitives (concentric
circles for Stabilization, a crosshair for Focus, three sliders for Processing) with a 10 sp label under
each (L207-215) in a 76 dp rail (L177).
**Problem:** The glyphs aren't recognizable camera iconography — three concentric circles reads as
"target/aperture/wifi?" not "stabilization"; the exposure sun and the advanced gear look alike. At 10 sp
the labels are the real wayfinding, but they're tiny. Sony's menu uses conventional, legible category
icons.
**Who it hurts:** Discoverability for everyone during the (frequent) hunt through 8 tabs.
**Scenario:** User looking for EIS strength scans eight ambiguous glyphs and has to squint at 10 sp text.
**Fix:** Either adopt conventional Material camera icons (a proper hand/wave for stabilization, aperture
for exposure, etc.) or keep custom glyphs but bump labels to ≥ 12 sp and lead with the label. Consider a
scrollable *top* tab bar (Sony-like) or larger rail items (≥ 56 dp) with clearer selected-state (the
current 20×2 dp accent tick + 10 % wash is subtle). **Confidence: Medium.**

## UX-21 — The shutter jumps off-center when the snapshot button appears mid-recording
**Where:** `CameraScreen.kt` `ShutterRow` (L601-622): center group is `Row { if(recording) SnapshotButton;
ShutterButton }` flanked by equal `weight(1f)` spacers.
**Problem:** Because the snapshot dot is *inside* the centered group (to the left of the shutter, L613-
618), starting a recording widens the group and shoves the main shutter left of true center. The most
important control moves the instant you hit record.
**Who it hurts:** Everyone shooting video — muscle memory for the shutter position breaks exactly when
you need to stop recording or grab a snapshot.
**Scenario:** User starts recording, reaches for the (now shifted) big button to grab a still and taps
empty space or the snapshot dot instead.
**Fix:** Keep the shutter globally centered and place the snapshot button in a reserved side slot that's
always allocated (e.g., mirror it opposite the gallery thumb, or give it a fixed-width placeholder so the
shutter never translates). Anchor the shutter with `Modifier.align(Alignment.Center)` in a `Box` and lay
the snapshot in a fixed offset lane. **Confidence: High.**

## UX-22 — "Disabled" dial chips are still clickable and open inert rulers
**Where:** `ManualDials.kt` `DialChip` always attaches `.clickable(onClick)` regardless of `enabled`
(L205-213) — `enabled` only recolors text (L200-204). EV chip: `enabled = controls.autoExposure`
(L184) but `onSelect(EV)` has no special-case (L112 falls through to toggle open), and `EvRuler`'s
slider is `enabled = controls.autoExposure` (L418). Focus chip similarly gated visually but clickable.
**Problem:** In manual exposure, tapping the greyed EV chip opens a ruler whose slider is disabled and
whose indicator is drawn in `TextSecondary` — a dead control with no explanation. Focus/ISO/Shutter are
saved by special-case handlers that flip to the right mode, but EV isn't, so it's the odd one out.
**Who it hurts:** Anyone probing the greyed chips to learn the model — they get an inert panel, not a
hint.
**Scenario:** Manual mode; user taps EV to bias exposure, gets a frozen ruler and no message that EV only
applies in Auto.
**Fix:** Make disabled chips genuinely non-interactive (`Modifier.clickable(enabled = enabled, …)`) *and*
show a one-line hint on tap of a gated chip ("EV compensation applies in Auto exposure"), mirroring the
helpful auto→manual flips already done for ISO/Shutter/Focus (L103-111). **Confidence: High.**

## UX-23 — Exposure scopes are off by default and buried; no quick access for manual work
**Where:** `CameraState.kt` defaults `histogram=false`, `waveform=false`, `zebra=false`,
`falseColor=false` (L89-93); all toggled only inside `AssistsTab` (`ProSheet.kt` L543-558). No top-bar or
quick access.
**Problem:** For a manual, RAW/HLG, exposure-critical tele app, the exposure-verification tools require
opening a modal and digging into the last-but-one tab, every session (settings don't persist —
BACKLOG.md #5). Combined with UX-1 (no meter), the day-one exposure story is: *nothing on screen tells
you about exposure* unless you go spelunking.
**Who it hurts:** Every serious shooter; the tools exist but are effectively hidden.
**Scenario:** Chasing highlights on a bright subject through the tele — the zebra/histogram that would
save the shot are three taps and a scroll away.
**Fix:** Add a one-tap scopes control in the reachable zone (repurpose the LensFlip slot from UX-10, or a
long-press on the exposure dial) that cycles Off → Histogram → Waveform, and/or a persistent compact
histogram when in manual exposure. Persist assist toggles across launches. **Confidence: Medium.**

## UX-24 — Horizontally-scrolling selectors have no overflow affordance and nest inside a vertical scroll
**Where:** `ProControls.kt` `SegmentedSelector` uses `.horizontalScroll(rememberScrollState())` (L129-
134) for option rows (WB has 7 entries L307-315; video sizes; fps), sitting inside the sheet's
`verticalScroll` (`ProSheet.kt` L129).
**Problem:** (a) When chips overflow the width there's **no fade/chevron** cue that more options exist to
the right — users think the visible set is all there is (they may never find "Shade"/"Manual" WB). (b) A
horizontal scroller nested in a vertical scroller creates directional-ambiguity drag conflicts near the
row edges. Also FilterChips are ~32 dp tall (below UX-9's 48 dp).
**Who it hurts:** Anyone selecting from a long option set — silently truncated choices.
**Scenario:** User scans the WB row, sees Auto…Cloudy, doesn't realize Shade/Manual are off-screen right.
**Fix:** Add an edge fade gradient (or a subtle chevron) when the row overflows; consider wrapping to two
lines (`FlowRow`) for long sets so nothing is hidden; raise chip height/hit area to 44–48 dp. Ensure the
horizontal scroller consumes only horizontal drags so the parent vertical scroll stays smooth.
**Confidence: Medium.**

---

# Low severity

## UX-25 — Overlay rotation animates the long way around 270°→0°
**Where:** `CameraScreen.kt` `overlayRotation = animateFloatAsState(-state.deviceOrientation.toFloat())`
(L117-120).
**Problem:** Rotating from 270° to 0° animates backward through 180° (a 270° sweep) instead of the
shortest +90°. Cosmetic but visibly wrong when turning the phone.
**Fix:** Normalize to the shortest angular path (accumulate a continuous target, or animate the delta
mod 360 choosing the ≤180° direction). **Confidence: Medium.**

## UX-26 — Reduced-motion / animation-scale not honored
**Where:** `animateFloatAsState` (L117), `AnimatedVisibility` for dials (`ManualDials.kt` L76-89),
`ModalBottomSheet` transitions.
**Problem:** Users who disable animations (accessibility / motion sensitivity) still get the rotations
and expand/collapse. Minor, but part of a Play-ready accessibility pass.
**Fix:** Check `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (or a Compose reduced-motion signal) and
switch to snap transitions. **Confidence: Low.**

## UX-27 — No battery / storage / frames-remaining status
**Where:** `StatusBar` (`Overlays.kt` L252-290) shows camera id, focal, formats, transfer, EIS — but no
capacity indicators.
**Problem:** Sony always shows battery % and shots-remaining; a pro on a long tele shoot wants to know
they won't run out of card or power mid-session, especially with RAW+HLG file sizes.
**Fix:** Add a compact battery and free-space (or estimated shots) readout to the status strip, or the
bottom scrim. **Confidence: Low.**

## UX-28 — Recording state is weakly signaled and rotates inconsistently
**Where:** `RecordingIndicator` (`Overlays.kt` L197-221) is a small top-right chip, not counter-rotated
(`CameraScreen.kt` L220-224), while nearby scopes are.
**Problem:** A small dot+timer is easy to miss on a large screen; pro apps flash a red border to make
"you are recording" unmissable. And it's the one HUD element left un-rotated while its neighbors rotate.
**Fix:** Add a subtle 2–3 dp `Record`-red frame inset while recording; include the indicator in the
rotation policy chosen for UX-12. **Confidence: Low.**

## UX-29 — Ruler readout has no scrim; yellow can wash out on bright scenes
**Where:** `ManualDials.kt` `RulerReadout` (L237-247) — plain `Text` in `ManualActive` (#FFD60A), no
background (the ruler strip below it has a 0.35 black bg, the readout does not).
**Problem:** Over a bright/yellow scene the readout value (the number you're actively setting) can lose
contrast. HUD text over arbitrary scenes needs its own scrim.
**Fix:** Give the readout the same rounded 0.35–0.5 black pill background used elsewhere, or a thin text
shadow/outline. **Confidence: Medium.**

## UX-30 — All user-facing strings hardcoded in Kotlin (no `strings.xml`)
**Where:** Labels throughout — "Photo"/"Video" (`CameraScreen.kt` L566-567), "TELE" (L526), "Settings"
(`ProSheet.kt` L118), tab labels (`ProSheet.kt` L74-81), enum labels (`ProControls.kt` L246-355),
"Camera permission is required" (`MainActivity.kt` L96). `res/values/strings.xml` exists but is unused by
the UI.
**Problem:** Even under the English-only mandate, centralizing strings is the conventional home for
TalkBack content descriptions and state phrases (ties to UX-8), keeps copy consistent, and avoids
literals drifting. It's also a Play-hygiene expectation.
**Fix:** Move user-facing copy to `strings.xml` and reference via `stringResource(...)`; use the same
resources for the `contentDescription`s added in UX-8. **Confidence: Low.**

---

## What's already right (keep it)
- **Resting viewfinder is genuinely Pixel-clean** — chrome collapses to a thin top row + bottom cluster
  (`CameraScreen.kt`), which is the correct discoverability baseline.
- **The scrolling tick-ruler dial** (`RulerSlider`, `ManualDials.kt`) is a strong, original Fn metaphor —
  fixed center indicator, content-follows-finger, stop snapping in real EV increments. Just needs haptics
  (UX-18) and a readout scrim (UX-29).
- **Relative "∞ + N" focus scale** (`formatFocusRelative`) is the honest, correct call for afocal optics —
  far better than a fake metric distance.
- **Auto→Manual affordance flips** for ISO/Shutter/Focus (`ManualDialCluster` L103-111) are thoughtful;
  just extend the same courtesy to EV (UX-22).
- **Deterministic dark theme** (`Theme.kt`, no dynamic color) is exactly right for a viewfinder — a
  camera HUD must not change with wallpaper. The token set is good; the problem is overlays *not using*
  it (UX-16).
- **Bottom gradient scrim** (`CameraScreen.kt` L276-281) correctly guarantees control legibility over any
  scene — the top HUD elements need the same discipline.

## Suggested fix order (impact × reach)
1. UX-1 (meter) + UX-23 (scopes access) — the exposure-verification story, together.
2. UX-2 + UX-5 — capture confirmation and shot review (the focus-confirmation loop).
3. UX-3 — stop the HUD from lying about RAW/HLG.
4. UX-4 + UX-9 + UX-10 — reachability and touch targets (repurpose LensFlip as the reachable settings/
   scopes entry, kill two birds).
5. UX-6 + UX-7 + UX-8 — Play-readiness: permission recovery, loading/error states, accessibility.
6. Everything else as polish.
