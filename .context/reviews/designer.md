# Native Android designer review — cycle 37

Date: 2026-08-24

Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)

Workspace: isolated detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope and method

This is native Compose, so browser automation is not applicable. I inventoried all 489 tracked paths
and reviewed production/debug Compose, semantics, resources, manifests/theme, UI state/actions,
relevant tests, and historical assets (history only; their manifest marks blockers). Coverage included
IA, affordances, non-touch/TalkBack/Switch Access, target size/contrast, phone/large-screen/freeform,
font scale/insets, EN/KO/RTL, dark-only UI, loading/error/delete states, and perceived performance.

## Findings

### DES37-01 — Gamma is a hot no-op on no-Main10 hardware and while encoder truth is loading

- **Severity / confidence:** Medium / High.
- **Exact regions:** `camera/CameraState.kt:1164-1187,1495-1501`; `ui/controls/ProControls.kt:919-975`; `ui/controls/ControlCycles.kt:230-244`; `ui/controls/FnQuickActions.kt:98-143`; `ui/controls/ManualDials.kt:621-632`; `ui/CameraViewModel.kt:2283-2299`.
- **Problem:** the menu withholds unsupported non-SDR options, but Fn overlay, My Menu/Recent, and expanded DISP keep Gamma enabled. A tap requests an unsupported curve and normalizes back to SDR, with press feedback but no change, unavailable/loading state, or explanation.
- **Failure scenario:** a TB336ZU-class user repeatedly taps `Gamma / SDR`, reads the lack of response as broken touch, and cannot reconcile it with the singleton settings row.
- **Suggested fix:** derive value, enablement, and next action from one projected list; dim singleton/pre-inventory state with truthful semantics. Test all quick surfaces in EN/KO and non-touch activation.

### DES37-02 — stabilization can claim OIS+EIS/extra crop while applied mode is OFF or Standard

- **Severity / confidence:** Medium / High.
- **Exact regions:** `camera/CaptureCapabilities.kt:195-201,244-250,551-562`; `camera/CameraState.kt:145-169`; `ui/controls/ProSheet.kt:1246-1269`; `ui/controls/FnQuickActions.kt:122`; `ui/controls/ManualDials.kt:574-587`; `ui/CameraViewModel.kt:2943-2952`; `ui/overlays/Overlays.kt:962-970`.
- **Problem:** every device sees Off/Standard/Active and global quick cycling regardless of advertised HAL modes. Controller fallback changes the wire value, but chip, Fn value, caption, and OSD retain the request. This is false live instrument feedback, not merely an unavailable option.
- **Failure scenario:** OFF-only hardware displays `STAB STD`/`STEADY`; OFF+ON hardware displays Active plus `OIS+EIS · crop`, though no distinct active-crop profile is applied.
- **Suggested fix:** show exact profiles, normalize unsupported restored state at caps acceptance, and make menu/Fn/OSD consume applied truth. Cover route changes and capability matrices visually and semantically.

### DES37-03 — disabled focal-rail controls keep the full enabled-strength outline

- **Severity / confidence:** Low / High.
- **Exact regions:** `ui/theme/Theme.kt:115-127`; `ui/CameraScreenPolicy.kt:644-680`; `ui/CameraScreen.kt:2827-2905`; missing rail coverage at `ui/controls/AffordanceEdgeComposeTest.kt:33-68`.
- **Problem:** cycle 36's 36%-white enabled edge is unconditional in `RailChip`; only disabled text dims. Recording/reconfiguring choices retain the same boundary cue as tappable choices.
- **Failure scenario:** visual presentation invites a lens change while TalkBack correctly calls it unavailable.
- **Suggested fix:** use a quiet disabled rail edge and coherent selected-disabled treatment without weakening the enabled edge; render all states over bright/dark frames.

## Confirmed strengths and final sweep

The nine-tab IA, preview-first OSD, Fn/My Menu/MR model, critical/error/delete surfaces, modal focus,
review non-touch controls, primary 48 dp targets, responsive physical geometry, EN/KO pairing,
dark-system-bar contract, consumer-gated telemetry, and bounded review work remain coherent. Apart from
the three findings, no additional source-proven accessibility, responsive, localization,
loading/error, or perceived-performance issue survived.

## Totals

- New findings: 3
- Severity: 2 Medium, 1 Low
- Confidence: 3 High
