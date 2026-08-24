# Cycle 31 aggregate review

Date: 2026-08-24
Reviewed revision: `a69c12743383a5f3f98cc73bb6cb4ec5877c1cea`
Workspace: clean isolated clone `/private/tmp/rpf-cycle31.Bwz2Ov/repo`

## Review coverage

All required perspectives returned: code-reviewer, architect, critic, perf-reviewer, tracer,
debugger, security-reviewer, verifier, test-engineer, document-specialist, and native Android
designer/accessibility reviewer. Reviewers inventoried all 435 tracked paths and examined the full
Camera2/GL, capture/storage/deletion, ViewModel/UI, audio, settings/MR, documentation, build,
release, privacy, and test surfaces. Browser automation was not applicable to this native Compose
application. Existing reviews and completed plans through cycle 30 were cross-checked first.

## Deduplicated findings

### AGG31-01 — process-wide retirement rescan can retire a replacement Engine through a stale owner

- **Severity / confidence:** Medium / High
- **Agreement:** code-reviewer, architect, and critic.
- **Evidence:** `RetainedStillDiscardDispatcher.kt:76-78,101-116,140-157` keeps one accepted and
  one pending process-wide rescan, while `CameraEngine.kt:4229-4237` captures one Engine-local
  `RetainedStillDeletionOwner`. `MediaStoreWriter.retireCurrentProcessFamilyDeletions` may remove
  every process-owned marker, but `RetainedStillDeletionOwner.kt:127-149,243-282` can reconcile
  only that captured Engine's local families.
- **Failure:** Engine A's accepted rescan removes Engine B's marker and reports the retirement only
  to A. B retains unresolved output bookkeeping, the queued B rescan sees no marker, and repeated
  misses can fill B's fail-closed ceiling and disable still capture until replacement/restart.
- **Fix:** make rescan completion process-owned and route each retired family to its exact local
  owner, or notify all registered owners, with a deterministic two-Engine overflow regression.

### AGG31-02 — successful provider deletion plus marker-cleanup failure restores a phantom survivor

- **Severity / confidence:** Medium / High
- **Agreement:** code-reviewer, architect, and critic.
- **Evidence:** `MediaStoreWriter.kt:805-817,1231-1235` returns one Boolean for both provider-row
  disposition and exact DISCARD-marker cleanup. `CameraViewModel.kt:3388-3396` treats every false
  result as a surviving URI, and `CaptureOutputTracker.kt:321-352` restores it without rechecking
  provider existence.
- **Failure:** the provider deletes a JPEG, SQLite marker removal transiently fails, and review is
  rebuilt around the now-absent URI even though retaining retry metadata was the only required
  action.
- **Fix:** return typed provider and marker-cleanup dispositions; restore only authoritatively
  present rows while preserving cleanup retry for already-absent rows.

### AGG31-03 — preview surface callbacks can block the main thread behind CameraManager/HAL work

- **Severity / confidence:** Medium / High
- **Agreement:** perf-reviewer, tracer, and debugger.
- **Evidence:** `CameraScreen.kt:748-768` forwards TextureView availability/resize callbacks on the
  UI thread; `CameraEngine.kt:1327-1348,1542-1558,1634-1657` enters the blocking
  `TerminalAcquisitionGate`, whose monitor spans native acquisition at `:7158-7187`, including
  `CameraController.kt:292-304` CameraManager Binder calls.
- **Failure:** a split-screen resize during camera replacement parks main behind an already
  measured ~192 ms gate hold; a wedged vendor call can extend the freeze toward ANR territory.
- **Fix:** synchronously record surface/generation identity, then perform blocking admission on the
  serialized setup lane and recheck surface, generation, terminal state, and GL owner before bind.

### AGG31-04 — current HEAD fails the exact Partition-A coverage gate

- **Severity / confidence:** Medium / High
- **Agreement:** test-engineer and document-specialist.
- **Evidence:** authoritative `verifyPartitionACoverage` reports unexpected uncovered lines at
  `RetainedStillDiscardDispatcher.kt:54,186`, `Teleconverter.kt:149`, and
  `ViewModelMediaDeleteDispatcher.kt:47,49,110`; these are absent from the exact reviewed residual
  manifest despite the aggregate percentage remaining 99.75%.
- **Failure:** `python3 tools/verify_host.py` cannot pass on current HEAD.
- **Fix:** cover the closed-facade/process fallback, invalid OTHER-host fallback, facade counters,
  and production thread factory; record only genuinely unexecutable residuals with rationale.

### AGG31-05 — optics rollback integration test bypasses production rollback

- **Severity / confidence:** Medium / High
- **Agreement:** test-engineer and document-specialist.
- **Evidence:** production rollback is `CameraEngine.kt:658-724`; the test at
  `OpticsRecallTransactionRobolectricTest.kt:176-212` pre-restores the Engine and manually invokes
  the downstream callback. JaCoCo shows the production rollback body uncovered.
- **Failure:** removing the Engine declaration/controller restoration could leave rejected EXIF/OSD
  optics state while the purported asynchronous-failure regression still passes.
- **Fix:** drive a deterministic owned failure through the real rollback boundary and assert
  Engine, controller input, UI packet, and supersession from that one transition.

### AGG31-06 — callback-drain race test can pass before the closer attempts the drain

- **Severity / confidence:** Low / High
- **Agreement:** test-engineer and document-specialist.
- **Evidence:** `EngineCallbackSinkTest.kt:51-77` uses a fixed 25 ms sleep without proving the closer
  reached `closeAndDrain()` in `EngineCallbackSink.kt:35-38,75-80`.
- **Failure:** on a loaded runner, a broken immediately-returning drain can be scheduled only after
  the negative assertion and still let the test pass.
- **Fix:** add a bounded rendezvous/test seam proving the closer is blocked on the admitted lease
  before releasing the callback.

### AGG31-07 — device-harness known-noncoverage documentation contradicts committed evidence

- **Severity / confidence:** Low / High
- **Agreement:** test-engineer and document-specialist.
- **Evidence:** `device-tests/README.md:186-199` calls a multitouch instrumented test future work
  despite `PinchGestureProbeTest.kt:43-136`, and calls front signs pending despite the device-verified
  authorities in `FIELD_CHECKS.md:59-65` and `ARCHITECTURE.md:518-532`.
- **Failure:** contributors can duplicate existing probes or reopen closed device work instead of
  addressing the remaining assertion/automation and subjective-feel gaps.
- **Fix:** distinguish automated harness coverage, diagnostic probes, human/device evidence, and
  genuinely open checks; pin the wording in the docs contract.

### AGG31-08 — review remains actionable while video recording is starting or active

- **Severity / confidence:** Medium / High
- **Agreement:** native Android designer/accessibility reviewer.
- **Evidence:** `CameraScreen.kt:1277-1306,2952-2960` and `MediaReview.kt:669-681` keep review
  actionable; `CameraViewModel.kt:3304-3324` has no defensive refusal; prior video autoplay begins
  at `MediaReview.kt:1114-1122`.
- **Failure:** review hides REC/Stop during a live take and prior-video speaker audio can contaminate
  the still-running AudioRecord capture.
- **Fix:** disable review visually/semantically while starting or recording and refuse it again in
  the ViewModel; preserve timelapse behavior and test idle/starting/active states.

### AGG31-09 — ToggleRow can clip its trailing Switch at compact 2x font

- **Severity / confidence:** Medium / High
- **Agreement:** native Android designer/accessibility reviewer.
- **Evidence:** `ProControls.kt:623-646` measures an unconstrained label before a trailing Switch,
  unlike responsive `LabelValueRow` at `:695-724`; long production labels occur in
  `ProSheet.kt:1498-1503,1664-1669`.
- **Failure:** a 320 dp / 200% font window can leave a clickable row without a visible state/control
  affordance.
- **Fix:** reserve Switch width or adaptively stack, with EN/KO compact checked/unchecked and
  enabled/disabled bounds/activation tests.

### AGG31-10 — AF focused and failed reticles differ only by color

- **Severity / confidence:** Medium / High
- **Agreement:** native Android designer/accessibility reviewer.
- **Evidence:** `Overlays.kt:332-365` changes yellow/green/red but draws identical bracket geometry
  and stroke for scanning, focused, and failed states.
- **Failure:** color-vision-deficient operators cannot reliably distinguish a successful lock from
  failure over arbitrary live imagery.
- **Fix:** retain color but add a non-color terminal cue and contrast outline; add deterministic
  state-presentation coverage.

### AGG31-11 — focal-rail overflow fade uses the wrong modifier order

- **Severity / confidence:** Low / High
- **Agreement:** native Android designer/accessibility reviewer.
- **Evidence:** `ProControls.kt:240-245,298-308` requires the fade before `horizontalScroll`, while
  `CameraScreen.kt:2665-2669` reverses them.
- **Failure:** the fade moves with content instead of staying at the viewport edge, making clipped
  device-derived zoom marks look like a layout bug.
- **Fix:** swap modifiers and add a constrained overflowing-rail assertion for fade presence and
  disappearance at the end.

## Agent failures

None. Every spawned reviewer returned and wrote its provenance report.

## Final sweep result

No additional actionable security, correctness, performance, architecture, documentation, test, or
UI/UX finding survived evidence checking. The security/verifier pass reported zero new findings and
confirmed release manifest, backup, permission, immutable-build, and attestation boundaries. No
physical-device behavior was inferred from host source or tests.
