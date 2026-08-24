# Aggregated deep review — cycle 43

Date: 2026-08-24
Reviewed revision: `b3f82463d4d116b4ee7af3ac7c8925a4ab21357e` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle43.q4Rs33`

## Coverage and aggregation

Five parallel specialist groups covered every required role: code-reviewer, critic, architect,
performance-reviewer, tracer, security-reviewer, debugger, verifier, test-engineer,
document-specialist, and native Android designer. No repository-local reviewer agents were
registered. Each group inventoried all 506 tracked paths and examined relevant production, test,
tooling, resource, documentation, asset, and cross-file interactions. Browser automation was inapplicable
to this native Compose app. No device behavior was run or inferred.

Six raw specialist findings remain six distinct current root causes. The microphone-permission
continuation, zoom lifecycle authority, Korean privacy route, shutter focus visibility, command-chip
rendered coverage, and brand-asset consistency findings affect separate owners and failure modes.
Highest severity and confidence are preserved. Code/architecture and performance/tracing found no
additional current defect.

## Findings

### AGG43-01 — Activity recreation loses the pending microphone-permission command

- **Severity / confidence:** Medium / High
- **Source:** security-reviewer/debugger
- **Status:** confirmed lifecycle correctness defect; no privilege bypass or data loss.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:148-149,325-341,756-772`
  stores `pendingAudioAction` only in ordinary Activity memory and uses its current value as the
  sole owner when the registered permission result arrives. There is no Saved State Registry,
  `onSaveInstanceState`, or `SavedStateHandle` owner. `CameraPermissionPolicy.kt:50-60` promises
  that a denied `START_RECORDING` continues as video-only, while existing tests keep the action
  supplied and never recreate the Activity.
- **Failure:** if Android recreates the Activity or process while the microphone permission UI is
  open, a grant starts neither the requested recording nor audio enablement; denial can disable
  audio without starting the promised video-only take.
- **Plan direction:** persist both the pending action and rationale ownership before launching UI,
  restore them exactly once, clear only after a terminal continuation, and add recreation tests for
  enable-audio and start-recording grant/denial paths.

### AGG43-02 — zoom re-pinch tests model a different lifecycle than production

- **Severity / confidence:** Medium / High
- **Source:** verifier/test-engineer
- **Status:** confirmed test/authority mismatch; current runtime still reaches exact framing.
- **Evidence:** `camera/ZoomSubmitPlan.kt:59-73` says quiet landing is once-only and models re-pinch
  through `startZoomInteraction()`, as does `ZoomSubmitPlanTest.kt:132-139`. Production
  `ui/CameraViewModel.kt:2085-2109` starts a new Engine interaction inside the 700 ms tail only for
  a fresh outward edge. An inward/same-direction re-pinch retains `exactLanded=true`, suppresses
  moving Camera2 ticks, and relies on a second quiet callback, which happens to submit because
  `landQuietZoom` ignores `exactLanded`.
- **Failure:** a maintainer making the implementation match its "once only" KDoc would suppress the
  second quiet landing and the end submit, leaving the HAL at the first landed ratio while the
  finder shows the second.
- **Plan direction:** record movement after a landing in the interaction state, make duplicate quiet
  calls truly idempotent only when no movement intervened, test inward re-pinch, duplicate quiet,
  and outward re-pinch sequences, and align KDoc/architecture.

### AGG43-03 — the normal Korean Privacy action opens an English-only policy

- **Severity / confidence:** Medium / High
- **Source:** document-specialist/designer
- **Status:** confirmed localization and user-facing documentation defect.
- **Evidence:** the bilingual rule is `CLAUDE.md:44-51`; `ui/ExternalNavigation.kt:50-65,95`
  exposes one external privacy URL; `privacy-policy/index.html:2,161-278` declares English and has
  English-only prose. Korean policy copy exists only in the exceptional in-app fallback at
  `ui/ExternalNavigationUi.kt:56-78` and `res/values-ko/strings.xml:105-112`.
- **Failure:** on a Korean device with a browser, tapping `개인정보처리방침` succeeds externally and
  displays only English, while the Korean copy is paradoxically available only when launch fails.
- **Plan direction:** publish and locale-route a Korean policy or make the public document properly
  bilingual, keep external and bundled claims under one checked inventory, and test locale routing.

### AGG43-04 — the primary shutter has no visible keyboard-focus treatment

- **Severity / confidence:** Medium / High
- **Source:** document-specialist/designer
- **Status:** confirmed source-level focus-visible defect; final pixels need snapshot/device validation.
- **Evidence:** `ui/CameraScreen.kt:3154-3208` makes the custom Canvas shutter clickable/focusable,
  disables indication, and observes press but not focus. `CameraControlKeyboardComposeTest.kt:114-129,163-192`
  proves keyboard activation only. Existing controls show the established contrast-aware focus
  pattern at `ui/controls/ProControls.kt:495-506,614-624` and
  `ui/controls/ManualDials.kt:1343-1360`.
- **Failure:** a tablet, ChromeOS, D-pad, or keyboard user can focus and fire the shutter without any
  visible indication that it owns Enter/Space.
- **Plan direction:** observe focus and draw a persistent high-contrast halo/keyline without adding
  a touch ripple; test focused/unfocused pixels over bright and dark finder backgrounds while
  preserving disabled non-focusability and activation behavior.

### AGG43-05 — disabled-command paint coverage does not render the command chip

- **Severity / confidence:** Low / High
- **Source:** verifier/test-engineer
- **Status:** confirmed false-assurance gap.
- **Evidence:** `ui/controls/SelectorRoleSemanticsComposeTest.kt:121-147` tests only the
  `immediateActionChipColors` result. Its rendered `ImmediateActionChip` checks at lines 83-119
  assert semantics and click admission, never the `Surface` paint wired in
  `ui/controls/ProSheet.kt:792-817`.
- **Failure:** disconnecting container/content/border mapping from `Surface` would restore the
  visible disabled-state defect while all current tests remained green.
- **Plan direction:** add stable rendered coverage for all active/enabled combinations, retaining
  the pure mapping test as mapping-only evidence; use the existing snapshot harness if host
  rasterization cannot provide a stable assertion.

### AGG43-06 — launcher and public-brand assets use unrelated icon systems

- **Severity / confidence:** Low / High
- **Source:** document-specialist/designer
- **Status:** confirmed asset/metadata consistency defect; OEM masks need manual validation.
- **Evidence:** installed assets `res/drawable/ic_launcher_foreground.xml:2-44` and
  `ic_launcher_background.xml:2-20` use a white lens/chevron on an azure-cyan gradient, while
  `docs/assets/play/icon-512.svg:1-16`, `docs/assets/play/feature-graphic.svg:13-43`, and
  `docs/assets/logo.svg:1-13` use a black telescope/barrel mark with blue aperture/base. The README
  SVG also retains the obsolete `Find X9 Ultra Tele Camera logo` identity and `300mm tick marks`
  comment.
- **Failure:** the Play listing mark does not visually identify the installed launcher entry, and
  future inline reuse of the README SVG restores stale single-device accessible branding.
- **Plan direction:** choose one core mark across launcher/monochrome/store/feature/README variants,
  update stale SVG metadata, regenerate raster assets, and validate adaptive/themed masks.

## Agent failures

None.

## Totals

- Raw specialist findings: 6
- Deduplicated new findings: 6
- Severity: 4 Medium, 2 Low
- Confidence: 6 High
- Device/manual-only residuals: A3, A4, D1, E1, and E2 remain correctly open in
  `docs/FIELD_CHECKS.md`; none was reclassified as a code defect.
