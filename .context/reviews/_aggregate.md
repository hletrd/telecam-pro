# Aggregated deep review — cycle 44

Date: 2026-08-24
Reviewed revision: `86c2e082987ccfd12c8069e63c38803c57ca1dbb` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle44.GMMtvi`

## Coverage and aggregation

Five parallel specialist groups covered every required role: code-reviewer, critic, architect,
performance-reviewer, tracer, security-reviewer, debugger, verifier, test-engineer,
document-specialist, and native Android designer. No repository-local reviewer agents were
registered. Each group first inventoried the complete relevant production, test, tool, resource,
documentation, asset, and cross-file surface; browser automation was inapplicable to this native
Compose app. No device behavior was run or inferred.

The groups produced 11 raw findings. The microphone recreation-test finding was independently
reported by code/architecture and verifier/test, and the missing front pseudo-ZSL field check was
independently reported by performance/tracing and verifier/test. Those overlaps are deduplicated
below with the highest severity/confidence preserved. The remaining findings affect distinct runtime
owners, resource-admission policy, loading or timelapse UX, contrast, and separate verification
seams. Result: nine deduplicated current findings, all High confidence.

## Findings

### AGG44-01 — restored microphone permission ownership is overwritten by CameraScreen

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer/architect/critic
- **Status:** confirmed lifecycle and state-consistency defect; device-visible hardware behavior
  remains manual validation.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:288-296` restores the
  pending action and publishes an input block. A fresh `CameraScreen` derives `modalVisible` only
  from its own sheet/Fn/review/delete owners and unconditionally publishes the initial false value
  (`ui/CameraScreen.kt:437-477`). `CameraViewModel.onCameraInputBlockedChange` is a last-writer
  Boolean assignment (`ui/CameraViewModel.kt:3414-3419`), so Compose releases the Activity-owned
  microphone block. During the system-dialog phase `showMicrophoneRationale` is false and cannot
  compensate (`MainActivity.kt:606-607,650-651,692-693,735-736`).
- **Failure:** Activity recreation while the system permission result is pending briefly restores
  then clears the shared block; hardware/debug/accessibility paths and countdown/resource gates can
  observe an actionable hidden viewfinder behind an external permission owner.
- **Plan direction:** replace the shared Boolean release seam with identity-owned block tokens (or
  an equivalent owner-aware reducer), migrate Compose and Activity permission/policy owners, and
  test that each owner can release only itself across recreation and result completion.

### AGG44-02 — unavailable YUV timing is treated as proof that deep pseudo-ZSL is fluid

- **Severity / confidence:** Medium / High
- **Source:** performance-reviewer/tracer
- **Status:** confirmed unsafe admission semantics; generic-device runtime impact needs manual
  validation. PMA110's measured positive duration is unaffected by a proof-positive fix.
- **Evidence:** `camera/CaptureCapabilities.kt:438-441` collapses a null map, lookup exception, or
  unavailable duration to `0L`. `camera/CameraController.kt:2644-2656` treats every non-positive
  value as safe even though the platform API defines zero as unavailable. That admits the roughly
  five-buffer full-resolution YUV reader (`CameraController.kt:679-703`) and attaches it to every
  repeating request on logical/front PHOTO SINGLE routes (`:980-988,1430-1434`).
- **Failure:** a generic device with unavailable timing metadata can allocate about 95 MB of deep
  YUV buffers and continuously drive a slow full-resolution stream, reducing finder cadence and
  consuming ISP/DRAM bandwidth without triggering configuration or request failure fallbacks.
- **Plan direction:** require a positive duration at or below the 24 fps bound; fail closed to the
  shallow real-capture path when timing is unavailable; update unit and route-level coverage while
  retaining measured-device behavior.

### AGG44-03 — running timelapse has the idle shutter identity and can lose its stop action

- **Severity / confidence:** Medium / High
- **Source:** document-specialist/designer
- **Status:** confirmed interaction, affordance, and accessibility defect; final TalkBack/pixels
  remain manual validation.
- **Evidence:** `CameraUiState.timelapseRunning` reaches the OSD but is not passed through
  `ShutterRow`/`ShutterButton` (`ui/CameraScreen.kt:1379-1393,3053-3234`), so the active control
  remains a white PHOTO circle labeled `a11y_take_photo`. The Engine nevertheless treats the press
  as stop (`camera/CameraEngine.kt:4032-4044,4107-4115`). `primaryShutterEnabled` requires a ready
  still target (`camera/CameraState.kt:1565-1578`), and capture admission can reject before the
  timelapse-stop branch when Ready drops.
- **Failure:** an active interval run is distinguished persistently only by a red OSD color, speaks
  the wrong action, and can become impossible to stop from the primary control during recovery.
- **Plan direction:** make timelapse-running a first-class shutter state with EN/KO stop semantics,
  non-color paint/state, stop-before-capture admission, always-stoppable enablement, and policy/
  Compose/Engine routing tests.

### AGG44-04 — recording red fails small-text contrast on the live-frame plate

- **Severity / confidence:** Medium / High
- **Source:** document-specialist/designer
- **Status:** confirmed WCAG 2.2 SC 1.4.3 defect from exact source colors.
- **Evidence:** `CameraColors.Record = #FF3B30` (`ui/theme/Theme.kt:67-83`) over the canonical black
  0.82-alpha HUD plate (`ui/overlays/Overlays.kt:77-81,108-120`) composites to a worst-case
  `#2E2E2E` background and 3.83:1 contrast. The token is used as 11 sp low-battery text
  (`ui/CameraScreen.kt:2217-2239`) and 12 sp live-timelapse text
  (`ui/overlays/Overlays.kt:993-1009`), below the 4.5:1 small-text threshold. Current contrast tests
  omit Record small-text consumers.
- **Failure:** over a bright scene, the two critical live power/run warnings are harder to read than
  adjacent compliant HUD text.
- **Plan direction:** retain Record for non-text shapes, use a compliant alarm/live-text token for
  small text, and extend contrast tests across actual small-text roles separately from 3:1 non-text
  shapes.

### AGG44-05 — reconfiguration and recovery conditions are timer-driven events

- **Severity / confidence:** Medium / High
- **Source:** document-specialist/designer
- **Status:** confirmed loading-state lifecycle and authority mismatch.
- **Evidence:** `camera/CameraStatus.kt:175-196` classifies only STARTING_CAMERA as PROGRESS;
  CAMERA_RECONFIGURING, PREVIEW_INTERRUPTED_RECOVERING, CAMERA_ERROR_RECOVERING, and retrying
  statuses expire after 2.5 or 6 seconds. Ready clearing in `ui/CameraViewModel.kt:799-807,1628-1673`
  removes only PROGRESS. `CLAUDE.md:1065-1073` states that a condition must end on an event, not a
  timer, and current tests explicitly pin CAMERA_RECONFIGURING to EVENT/2.5 seconds.
- **Failure:** fast recovery leaves stale “recovering/reconfiguring” copy over a healthy finder,
  while slow recovery removes the only explanation before Ready or a terminal result arrives.
- **Plan direction:** classify owned in-flight camera conditions as untimed progress, retire them on
  exact Ready/rollback/exhaustion/pause or replacement, update authority naming, and test fast/slow/
  replacement lifecycles.

### AGG44-06 — microphone recreation tests cover serializers, not lifecycle continuation

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer/architect/critic + verifier/test-engineer (cross-agent agreement)
- **Status:** confirmed false-assurance gap; this directly permits AGG44-01 to remain green.
- **Evidence:** `MainActivityPendingAudioStateTest.kt:13-53` calls only the Bundle save/restore
  helpers. It never creates/recreates MainActivity, composes CameraScreen, drives a registered
  permission result, observes input ownership, or proves ENABLE_AUDIO/START_RECORDING continuation
  exactly once. The real restore, result, rationale, and save edges at `MainActivity.kt:288-296,
  364-379,536-548,789-791` remain uncovered despite the completed cycle-43 plan claiming
  Activity-recreation coverage.
- **Failure:** removing any lifecycle wire, executing a restored command twice, or clearing the
  wrong owner leaves all current tests green.
- **Plan direction:** add real ActivityScenario/Robolectric recreation coverage, or extract one
  Activity-owned pending-request transition owner plus an instrumented wiring test, covering both
  actions/phases and grant/denial with exactly-once terminal clearing.

### AGG44-07 — repeated-zoom tests do not exercise the production timed submit chain

- **Severity / confidence:** Medium / High
- **Source:** verifier/test-engineer
- **Status:** confirmed wiring/coverage gap; current runtime contains the intended calls.
- **Evidence:** runtime movement and quiet submission live in `camera/CameraEngine.kt:3936-3980`.
  `ZoomSubmitPlanTest.kt:99-160` manually composes pure transitions, while the nearest ViewModel
  timer test advances only the 16 ms coalescer and never the 250 ms landing or 700 ms tail
  (`ui/CameraViewModelTickersRobolectricTest.kt:102-127`). The full coverage report leaves the exact
  landing body unexecuted.
- **Failure:** deleting production movement wiring or the second quiet schedule leaves pure tests
  green and can strand Camera2 at the first ratio while GL/UI display a later one.
- **Plan direction:** expose a small injected submit sink/composite owner and drive the real timed
  start → landing → movement → landing → end sequence, asserting exact targets and no duplicate
  submit.

### AGG44-08 — canonical field ledger omits the explicitly unsoaked front pseudo-ZSL route

- **Severity / confidence:** Medium / High
- **Source:** performance-reviewer/tracer + verifier/test-engineer (cross-agent agreement)
- **Status:** confirmed evidence-ownership contradiction; the actual sustained device behavior
  remains unverified.
- **Evidence:** `CLAUDE.md:210-229` explicitly says FRONT pseudo-ZSL has no sustained idle/memory-
  pressure soak and “remains a field check.” `docs/FIELD_CHECKS.md:1-14` declares exactly five
  remaining checks but contains no front-ZSL soak. Existing front harness coverage is one-shot,
  and `tools/check_docs.py` reports 139 green checks without detecting the omitted obligation.
- **Failure:** release review can close the supposedly exhaustive dashboard while a shipping
  full-resolution repeating route remains without cadence, memory, thermal, or post-soak capture
  evidence.
- **Plan direction:** add a named bounded front PHOTO/SINGLE soak and dashboard count/criteria, then
  teach the docs gate to bind active `CLAUDE.md` field-check claims to ledger identities.

### AGG44-09 — shutter-focus paint coverage can pass with production wiring disconnected

- **Severity / confidence:** Low / High
- **Source:** verifier/test-engineer
- **Status:** confirmed false-assurance gap; current production is wired.
- **Evidence:** `ShutterFocusComposeTest.kt:47-99` checks focus semantics on a real shutter but
  captures pixels from separately forced `ShutterFocusIndicator(false/true)` fixtures. Contrast is
  calculated from color constants, not captured production pixels. Removing the production
  indicator call at `ui/CameraScreen.kt:3236` or rendering a wrong low-contrast edge keeps both
  halves green.
- **Failure:** focus semantics can remain true while the real shutter loses or mispaints its visible
  focus indicator with all current tests passing.
- **Plan direction:** capture the same real ShutterButton before/after focus and compute contrast
  from rendered edge pixels over bright and dark backgrounds; keep standalone coverage only as a
  component diagnostic.

## Agent failures

None.

## Totals

- Raw specialist findings: 11
- Deduplicated new findings: 9
- Severity: 8 Medium, 1 Low
- Confidence: 9 High
- Security/debugger findings: 0
- Device/manual evidence was not inferred from host behavior.
