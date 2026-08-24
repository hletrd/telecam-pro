# Aggregated deep review — cycle 45

Date: 2026-08-24
Reviewed revision: `ed2a2faf69aa418545dfaa9f083444d9f63d2915` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle45.4l2DdI/repo`

## Coverage and aggregation

Five parallel specialist groups covered all required roles: code-reviewer, architect,
performance-reviewer, tracer, security-reviewer, debugger, critic, verifier, test-engineer,
document-specialist, and native Android designer. No repository-local reviewer agents were
registered. Each group inventoried all 513 tracked files, examined its complete specialist surface
and cross-file interactions, and completed a final missed-issue sweep. Browser automation was not
applicable to this native Jetpack Compose app. No device behavior was run or inferred.

The reviewers produced ten raw findings. Cross-report comparison found no true duplicates: the two
camera-status findings have different causal owners (stale Ready retirement versus hardware-input
admission), and the two microphone/focus items are test-assurance gaps distinct from the underlying
runtime fixes. All ten therefore remain as deduplicated current findings. Security/debugger found
no actionable issue. The authoritative host gate passed after one transient, non-reproducing Gradle
results-file failure was task-forced and rerun successfully.

## Findings

### AGG45-01 — policy-gate replacement can strand an invisible review and input owner

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer/architect
- **Evidence:** `MainActivity.kt:440-447,535-553` removes `CameraScreen` while policy-blocked;
  `CameraScreen.kt:442-459,470-493,1468-1485` owns the exact review URI only in
  `rememberSaveable`; `CameraViewModel.kt:3381-3407,3414-3427` separately retains `reviewOpen`, the
  family pin, and `CameraInputBlockOwner.REVIEW`.
- **Failure:** after policy recovery, the replacement screen has no review URI, so the overlay and
  close control disappear while hardware input remains blocked and the capture family remains
  pinned indefinitely.
- **Plan direction:** give the frozen review identity one lifecycle owner, preferably ViewModel
  state that can reconstruct the overlay, and test policy replacement preserves or atomically
  retires the exact review, pin, and input owner.

### AGG45-02 — stale Ready can clear a newer untimed progress condition

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer/architect
- **Evidence:** `CameraViewModel.kt:782-811,1628-1673` admits a Ready publication once, then clears
  progress synchronously without rechecking ownership; only the later posted camera-state reducer
  rechecks the publication generation. `CameraReadyPublicationGate.owns()` already distinguishes
  admission from current ownership.
- **Failure:** Ready N admits, NotReady N+1 plus a newer recovery status publishes, then Ready N
  resumes and erases the newer untimed condition even though its state publication is rejected.
- **Plan direction:** bind progress retirement to the latest Ready identity/generation and add a
  latch-controlled interleaving test.

### AGG45-03 — empty-gallery taps can enqueue unbounded MediaProvider restores

- **Severity / confidence:** Medium / High
- **Source:** performance-reviewer/tracer
- **Evidence:** `CameraViewModel.kt:686-691,1146-1167,1603-1609` submits every restore request to an
  unbounded single-thread executor; `CameraScreen.kt:1360-1376` and `MediaReview.kt:683-730` leave
  the empty thumbnail repeatedly enabled. Each task can run multiple provider queries and captures
  the ViewModel; `onCleared()` uses `shutdown()`, which drains accepted work.
- **Failure:** one wedged provider query lets repeated taps grow a stale ViewModel-retaining queue,
  starve telemetry/codec work on the same lane, and later replay redundant queries.
- **Plan direction:** add a ViewModel-local one-active plus one-conflated-pending restore owner,
  generation-safe publication, teardown rejection, and deterministic saturation coverage.

### AGG45-04 — hardware shutter can replace an exhausted-camera verdict with permanent progress

- **Severity / confidence:** Medium / High
- **Source:** critic/verifier/test-engineer
- **Evidence:** `HardwareInputPolicy.kt:12-32` and `MainActivity.kt:614-665` admit a camera-key edge
  without camera-health policy; `CameraViewModel.kt:3093-3135` invokes the action despite disabled
  primary-shutter state; `CameraEngine.kt:4046-4060,4929-4937` publishes the now-untimed
  `CAMERA_RECONFIGURING` even when no session/reconfiguration exists.
- **Failure:** after bounded recovery exhausts with actionable terminal guidance, a physical shutter
  key overwrites it with “Camera reconfiguring…” although no operation can emit a terminal event.
- **Plan direction:** apply the same action-admission policy to hardware actions while preserving
  active REC/timelapse stop, or preserve the terminal Engine verdict when no reconfiguration owns
  the request; cover PHOTO and VIDEO exhausted states.

### AGG45-05 — live timelapse interval edits do not affect the active run

- **Severity / confidence:** Medium / High
- **Source:** critic/verifier/test-engineer
- **Evidence:** `ProSheet.kt:941-964` leaves the interval row enabled during a run and
  `CameraViewModel.kt:3084-3089` plus `CameraEngine.kt:2816` publish/persist the edit, but
  `CameraEngine.kt:4223-4267` snapshots one period at start and closes every later schedule over it.
- **Failure:** changing a running 1-second interval to 30 seconds updates UI and persisted state
  while captures continue every second.
- **Plan direction:** define the contract and preferably read the current synchronized interval at
  every next-shot/recovery schedule boundary; test edit, recovery, stop, and restart behavior.

### AGG45-06 — microphone “Activity recreation” tests do not recreate MainActivity

- **Severity / confidence:** Medium / High
- **Source:** critic/verifier/test-engineer
- **Evidence:** production restore/result/save wiring is in
  `MainActivity.kt:303-310,358-394,805-813`, but `MainActivityPendingAudioStateTest.kt:10-70`
  exercises only Bundle serializers and
  pure transitions; `CameraViewModelRobolectricTest.kt:744-755` manually owns the microphone token.
- **Failure:** disconnecting production restore, saved-state, owner acquisition, or exactly-once
  launcher continuation leaves the claimed recreation coverage green.
- **Plan direction:** add real Robolectric/ActivityScenario saved-state recreation coverage for both
  pending actions/phases and grant/denial, including input-owner and exactly-once behavior.

### AGG45-07 — focus-ring contrast passes on one strongest changed pixel

- **Severity / confidence:** Low / High
- **Source:** critic/verifier/test-engineer
- **Evidence:** `ShutterFocusComposeTest.kt:64-94` filters changed edge pixels and asserts only the
  maximum contrast, while `CameraScreen.kt:3248-3265` renders two distributed circular rings.
- **Failure:** a clipped or nearly invisible ring passes if one antialiased pixel remains above 3:1.
- **Plan direction:** require a substantial connected annular coverage/percentile at compliant
  contrast on bright and dark fixtures, with negative one-pixel/short-arc mutations.

### AGG45-08 — enabled recording Stop is dimmed to disabled-strength during Not-Ready

- **Severity / confidence:** Medium / High
- **Source:** document-specialist/designer
- **Evidence:** `CameraState.kt:1565-1581` keeps Stop actionable while recording but does not keep it
  healthy; `CameraScreen.kt:3167-3184,3221-3242` applies 0.35 alpha to the entire control, and
  `CameraAdmissionPresentationComposeTest.kt:48-59,82-108` pins that mismatch.
- **Failure:** during REC recovery the only stop control remains active but its stop mark, ring, and
  focus indicator appear unavailable and fall below the app's non-text contrast convention.
- **Plan direction:** model Stop/Cancel as full-strength owned actions independent of new-capture
  readiness, correct the stale comment/test, and add rendered recovery/terminal/focus coverage.

### AGG45-09 — Korean converter focal OSD bypasses the existing translation

- **Severity / confidence:** Low / High
- **Source:** document-specialist/designer
- **Evidence:** `Overlays.kt:861-883` hardcodes `TELE`, while `CameraScreen.kt:1979-2025` and
  `values/strings.xml:469` / `values-ko/strings.xml:452` use the localized `osd_tele` identity.
- **Failure:** Korean converter mode displays `텔레` in one chip and `300 mm TELE` in the adjacent
  focal readout.
- **Plan direction:** resolve `osd_tele` in the focal presentation or use a localized formatted
  resource, then extend bilingual Compose coverage.

### AGG45-10 — compact/high-font dropdowns can hide the selected optics identity

- **Severity / confidence:** Medium / Medium
- **Source:** document-specialist/designer
- **Evidence:** `ProControls.kt:785-881` forces dropdown triggers/options to single-line ellipsis;
  `ProSheet.kt:1246-1298` supplies long Phone/Converter values inside the roughly 212 dp compact
  lane; unlike the responsive sibling at `ProControls.kt:697-769`, there is no narrow/high-font
  stacked policy or matching test.
- **Failure:** at 2x text, `OPPO Find X9 Ultra` and `OPPO Find X9 Pro` can both present as the same
  `OPPO Find…`, hiding the identity that controls compatible kit and focal arithmetic.
- **Plan direction:** share the sibling row's compact stacking breakpoint, allow bounded popup
  wrapping, and test 212 dp/fontScale 2.0 EN/KO plus RTL without losing radio semantics.

## Agent failures

None.

## Totals

- Raw specialist findings: 10
- Deduplicated new findings: 10
- Severity: 8 Medium, 2 Low
- Confidence: 9 High, 1 Medium
- Security/debugger findings: 0
- Device/manual evidence was not inferred from host behavior.
