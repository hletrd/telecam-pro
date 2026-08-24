# Critic review — cycle 37

Date: 2026-08-24

Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)

Workspace: isolated detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope and inventory

I read the committed project authorities and inventoried all 489 tracked paths, then examined the
production/debug Android surfaces, UI-facing state/actions/capability reducers, EN/KO resources,
manifests/theme, relevant JVM/Robolectric/Compose tests, privacy/Play documentation, current review
history, and completed plans through cycle 36. I treated cycle 36's dual-open, optimized-harness,
and affordance-contrast findings as resolved history. No device-only behavior is claimed.

## Findings

### CRIT37-01 — Gamma quick controls advertise an impossible choice and silently snap every tap back to SDR

- **Severity / confidence:** Medium / High.
- **Exact regions:** capability truth at `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:1164-1187,1495-1501`; correct menu filtering at `ui/controls/ProControls.kt:919-975`; divergent quick paths at `ui/controls/ControlCycles.kt:230-244`, `ui/controls/FnQuickActions.kt:98-143`, and `ui/controls/ManualDials.kt:621-632`; normalization at `ui/CameraViewModel.kt:2283-2299`; tests preserving the gap at `ui/controls/QuickFnEnabledTest.kt:20-23,80-84` and `ui/controls/PerformQuickFnTest.kt:197-214`.
- **Problem:** `availableTransfers(HEVC, false)` intentionally exposes only SDR on an encoder with no Main10 profile. `TransferSelector` consumes that list, but `quickFnEnabled(TRANSFER)` checks only HEVC/REC state and both quick dispatchers call the global five-value `nextTransfer`. The ViewModel normalizes the unsupported request back to SDR. The default test fixture has `tenBitEncodeAvailable=false` yet asserts Gamma is enabled and dispatches S-Log3.
- **Failure scenario:** On a TB336ZU-class 8-bit HEVC encoder, Fn overlay, My Menu, and expanded DISP show `Gamma / SDR` enabled. Repeated taps provide feedback but never change the value or explain why; the same false affordance exists during conservative pre-inventory state.
- **Suggested fix:** Give every Gamma surface one `availableTransfers(videoCodec, tenBitEncodeAvailable)` projection. Enable cycling only when the projected list has multiple entries and inventory truth is ready; advance with `nextAvailable`. Keep ViewModel normalization as defense in depth and test no-Main10/pre-inventory behavior on all quick surfaces.

### CRIT37-02 — stabilization UI publishes requested labels when the camera applies a different or OFF HAL mode

- **Severity / confidence:** Medium / High.
- **Exact regions:** contract at `camera/CameraState.kt:145-169`; advertised modes/fallback at `camera/CaptureCapabilities.kt:195-201,244-250,551-562`; unfiltered menu at `ui/controls/ProSheet.kt:1246-1269`; global quick cycles at `ui/controls/FnQuickActions.kt:122` and `ui/controls/ManualDials.kt:574-587`; optimistic state at `ui/CameraViewModel.kt:2943-2952`; OSD at `ui/overlays/Overlays.kt:962-970`; fallback-only tests at `camera/CaptureCapabilitiesTest.kt:7-31`.
- **Problem:** `videoStabModes` can map STANDARD/ENHANCED to OFF and ENHANCED to ordinary ON when preview stabilization is absent. Nevertheless the menu/quick controls offer all values, state persists the requested enum, and captions/OSD render it (`STAB STD`, `STEADY`, `OIS+EIS · crop`) rather than the applied mode.
- **Failure scenario:** OFF-only hardware can show Standard/Active while sending OFF. OFF+ON hardware can show Active plus extra crop while sending ordinary ON. At 300 mm the operator may depend on that false state when choosing exposure/framing.
- **Suggested fix:** Derive one exact advertised stabilization list; normalize restored/live state when target caps arrive; make menu, quick cycles, and OSD consume the applied value. Test OFF-only, OFF+ON, and OFF+ON+PREVIEW matrices.

### CRIT37-03 — cycle 36's stronger affordance edge is also painted on disabled focal-rail choices

- **Severity / confidence:** Low / High.
- **Exact regions:** enabled token contract at `ui/theme/Theme.kt:115-127`; disabled rail states at `ui/CameraScreenPolicy.kt:644-680`; unconditional border/text-only dim at `ui/CameraScreen.kt:2827-2905`; FilterChip-only coverage at `ui/controls/AffordanceEdgeComposeTest.kt:33-68`.
- **Problem:** `RailChip` always paints the 36%-white enabled edge even when `presentation.enabled=false`; only its text drops to 38%. Disabled lens/zoom choices therefore retain the same strong structural cue as tappable choices.
- **Failure scenario:** During recording or reconfiguration a sighted user sees fully outlined targets and attempts a control that semantics correctly marks unavailable.
- **Suggested fix:** Resolve a quiet disabled rail edge and coherent selected-disabled fill; retain the enabled 3:1 token. Render the full rail state matrix on bright/dark frames.

### CRIT37-04 — the two same-date privacy policies disclose different facts

- **Severity / confidence:** Low / High.
- **Exact regions:** `PRIVACY.md:1-38`; `privacy-policy/index.html:225-247`; in-app fallback at `app/src/main/res/values/strings.xml:125`; incomplete parity coverage in `tools/check_docs.py`.
- **Problem:** all copies say updated 2026-08-24, but Markdown omits camera make/model, lens, exposure, shot-time metadata, the explicit no-GPS statement, and the broad-library read/no-transmission explanation present in HTML and in-app copy.
- **Failure scenario:** GitHub and browser/in-app readers receive materially different disclosure depth from documents presenting themselves as the current policy.
- **Suggested fix:** Synchronize the key facts across Markdown, HTML, and EN/KO resources and enforce parity, or generate all presentations from one canonical source.

## Final sweep and validation

- `python3 tools/check_docs.py`: 112 checks passed, 24 optional-private skips; it does not detect CRIT37-04.
- Focused `QuickFnEnabledTest` and `PerformQuickFnTest`: passed, confirming current expectations encode CRIT37-01.
- Cycle-36 findings are fixed and not re-reported. No further source-proven critic issue survived the final accessibility/responsive/loading/error/dark/i18n/perceived-performance/privacy sweep.

## Totals

- New findings: 4
- Severity: 2 Medium, 2 Low
- Confidence: 4 High
