# Aggregated deep review — cycle 33

Date: 2026-08-24
Reviewed revision: `984424cb36ebe409e67bb2e69c8605ea6f41c4bb`
Workspace: clean detached worktree `/tmp/find-x9-cycle33-latest.Vc7rke`

## Coverage and aggregation

Five parallel specialist groups inventoried and reviewed all 447 tracked paths: 98 production
Kotlin files, 205 JVM/Robolectric/Compose tests, four instrumented tests, the complete Android
resource/manifest/build surface, 30 Python/tool and device-harness files, and all committed current
documentation. The groups covered code quality, architecture, critique, performance, tracing,
debugging, security, verification, testing, documentation, native Android design, accessibility,
and QA-adversary roles. No repository-local reviewer agents were present.

Every reviewer read `CLAUDE.md` and the committed fallback authorities. UI review was source- and
semantics-backed because this is a native Compose app, not a web app; no screenshot-only claim was
used. The public documentation checker passed 96 checks, focused security/UI tests and release
manifest processing passed where reviewers ran them, and device-only behavior was not claimed.

The 16 raw findings deduplicated to 15 current findings: `SECVER33-03` and `TD33-02` are one
field-check dashboard finding, with the broader test/document evidence retained. All findings have
High confidence. Cross-agent agreement is noted below.

## Findings

### AGG33-01 — delete-request creation performs synchronous MediaProvider IPC on main

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer / architect / critic
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:352-373` calls
  `MediaStore.createDeleteRequest` inline from the Compose delete callback. Android implements that
  request creation with a synchronous `ContentResolver.call` Binder transaction.
- **Failure:** a slow or wedged provider blocks the main looper before consent UI appears, freezing
  the viewfinder/input/lifecycle and risking an ANR.
- **Plan direction:** move request creation to finite background ownership with a first-wins
  deadline, identity-check the still-current URI before launching on main, and test a blocked
  provider without blocking the caller.

### AGG33-02 — consent cancellation can retain the full camera-input block forever

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer / architect / critic
- **Evidence:** `CameraViewModel.kt:3402-3449` clears `ownerlessDeleteConsentPending` and
  `cameraInputBlocked` only after an accepted `knownOutputPresence` task returns;
  `MediaStoreWriter.kt:838-860` performs the provider query, and
  `ViewModelMediaDeleteDispatcher.kt:52-79` has finite admission but no terminal deadline.
- **Failure:** a canceled consent queued behind a wedged worker, or a query that itself wedges,
  leaves all shutter/zoom/focus/camera input inert until process death.
- **Plan direction:** add exact pending-consent first-wins timeout ownership that restores the
  frozen handle with truthful `UNKNOWN`, clears modal state, and ignores late results; test both
  queued-behind-blocker and active-query blocker cases.

### AGG33-03 — delete-intent durability queues without bound behind still encoding

- **Severity / confidence:** Medium / High
- **Source:** performance / tracer / debugger
- **Evidence:** `CameraEngine.kt:130-137,4238-4289` submits every family marker/callback to an
  unbounded single-thread `ioExecutor`; processed encoding/provider work can wedge ahead of it while
  RAW-only DNG capture/publication remains independently admissible at `CameraEngine.kt:3995-4023,
  4627-4661`.
- **Failure:** repeated DNG capture/delete appends unbounded retained family/output/ViewModel
  callback graphs before any marker is durable; shutdown preserves the accepted queue.
- **Plan direction:** give pre-marker delete work a process-wide finite family admission owner (or
  separate bounded lane). Refusal must promptly restore review because launch recovery cannot own a
  marker that was never committed. Prove a fixed active+queued ceiling without inline provider work.

### AGG33-04 — converted `SendIntentException` is mislabeled as user cancellation

- **Severity / confidence:** Low / High
- **Source:** security / verifier
- **Evidence:** `MainActivity.kt:302-311` maps every non-OK result to `CANCELED`, while AndroidX
  converts `SendIntentException` into `RESULT_CANCELED` with its documented action/exception extra;
  `CameraViewModel.kt:3930-3950` intentionally presents launch failure differently.
- **Failure:** a framework/policy launch failure tells the operator they canceled the operation.
- **Plan direction:** extract a pure activity-result classifier for OK, genuine cancel, and the
  AndroidX converted-exception marker; route the last to `LAUNCH_FAILED` and test all three.

### AGG33-05 — exported debug launcher accepts camera-control extras from other apps

- **Severity / confidence:** Low / High
- **Source:** security / verifier
- **Evidence:** exported launcher `app/src/main/AndroidManifest.xml:69-105` consumes `zsl_spike` and
  `debug_zoom` extras in debug builds at `MainActivity.kt:163-181`, which flow to live state through
  `CameraViewModel.kt:1994-2006` without sender protection.
- **Failure:** another installed app can alter a running debug measurement/camera session and
  invalidate evidence. Release is inert, so this is limited to debug builds.
- **Plan direction:** move shell-only hooks behind a debug-only `android.permission.DUMP`-protected
  component/alias, keep launcher extras inert, and assert merged-debug-manifest protection.

### AGG33-06 — committed clean-clone authorities still require absent private backlog context

- **Severity / confidence:** Medium / High
- **Source:** test-engineer / document-specialist
- **Evidence:** the clone-safe fallback contract in `CLAUDE.md:3-9` conflicts with unqualified
  `docs/BACKLOG.md` dependencies in `docs/FIELD_CHECKS.md:141-146,234-238`,
  `docs/ARCHITECTURE.md:1337-1342`, and `device-tests/README.md:206-213`; `tools/check_docs.py:593-606`
  skips them without enforcing a qualifier and committed fallback.
- **Failure:** a clean-clone operator has nowhere authoritative to record new field evidence while
  the docs gate falsely reports a self-contained clone.
- **Plan direction:** qualify every private reference as optional and name a tracked fallback/results
  ledger; extend the checker across committed authority sections with exported-tree negative tests.

### AGG33-07 — the field dashboard omits open E2 and an A1 rotation obligation

- **Severity / confidence:** Medium / High
- **Sources:** security / verifier; test-engineer / document-specialist (**cross-agent agreement**)
- **Evidence:** `docs/FIELD_CHECKS.md:9-12` says only A3/D1/E1 remain, while E2 is explicitly open at
  lines 212-230 and A1 lines 49-57 retain an uncalibrated rotation term outside the dashboard.
- **Failure:** release review can declare all physical validation closed without ownerless delete
  consent or the remaining front tap-rotation evidence.
- **Plan direction:** make every residual an explicit ID/status, reconcile the dashboard/count, and
  machine-check exact membership/order against open/partial body headings.

### AGG33-08 — TC OIS is labeled confirmed although the distinct profile was never verified

- **Severity / confidence:** Medium / High
- **Source:** test-engineer / document-specialist
- **Evidence:** `docs/FIELD_CHECKS.md:163-173` says `CONFIRMED WORKING` while its body states the
  300 mm profile difference was never verified; `CLAUDE.md:766-774` retains the narrower truth.
- **Failure:** later release/support claims can cite a green heading as proof of a distinct TC OIS
  effect that the evidence never demonstrated.
- **Plan direction:** record only the actual observation and label an indistinguishable result as
  closed/no observable difference; add a checker against confirmed/pass headings with unresolved
  body qualifiers.

### AGG33-09 — clean-clone commands omit required Android SDK authority

- **Severity / confidence:** Low / High
- **Source:** test-engineer / document-specialist
- **Evidence:** `local.properties` is ignored, but `README.md:107-118`, `docs/FIELD_CHECKS.md:14-24`,
  and `device-tests/README.md:13-26` omit SDK environment/setup; `tools/verify_host.py:18-30,52-68`
  configures JDK only. The reviewer reproduced `SDK location not found` with an installed SDK.
- **Failure:** documented clean-clone build/evidence commands fail before producing artifacts.
- **Plan direction:** add canonical SDK discovery/setup and a precise host preflight, with tests for
  environment, local authority, conventional path, and missing-SDK remediation.

### AGG33-10 — device-harness README disagrees with Git and the executable registry

- **Severity / confidence:** Low / High
- **Source:** test-engineer / document-specialist
- **Evidence:** `device-tests/README.md:86-101` says the directory is ignored although only generated
  evidence/caches are ignored by `.gitignore:50-73`; the case table at README lines 131-157 omits
  registered `localized_camera_semantics` from `device-tests/cases.py:2016-2035`.
- **Failure:** contributors may omit harness source changes or audit an incomplete smoke matrix.
- **Plan direction:** correct both claims and add a registry-to-table exact membership/order test.

### AGG33-11 — review double-tap uses touch slop instead of double-tap slop

- **Severity / confidence:** Low / High
- **Source:** designer / QA-adversary
- **Evidence:** `MediaReview.kt:1009-1018,1833-1859` passes `touchSlop` as inter-tap distance, while
  Android exposes `scaledDoubleTapSlop`; `MediaReviewGestureTest.kt:91-126` hides the mismatch with
  an invented fixed threshold.
- **Failure:** normal second-tap placement accepted by platform gesture behavior is intermittently
  rejected during the primary focus-check gesture.
- **Plan direction:** use the platform double-tap threshold while retaining touch slop for one-contact
  motion, and test a distance between the two values.

### AGG33-12 — off-center pinch zoom loses the detail under the fingers

- **Severity / confidence:** Medium / High
- **Source:** designer / QA-adversary
- **Evidence:** `MediaReview.kt:975-990` scales around viewport center and adds pan but never applies
  centroid correction; point-preserving geometry exists only for long/double tap at lines 1778-1795.
- **Failure:** a corner subject slides away during pinch and may leave the viewport.
- **Plan direction:** preserve the content point under the centroid across old/new scale, combine
  simultaneous centroid pan, clamp once, and pure-test center/corner/edge cases.

### AGG33-13 — zoomed still review has no non-touch pan path

- **Severity / confidence:** Medium / High
- **Source:** designer / QA-adversary
- **Evidence:** `MediaReview.kt:914-1044` owns pointer-only panning, while still semantics at lines
  1283-1297 expose zoom/reset actions but no arrows, scroll axes, or directional actions.
- **Failure:** keyboard or Switch Access users can zoom but cannot reach off-center content.
- **Plan direction:** add bounded directional custom actions and key handling backed by
  `ReviewStillGeometry.clampOffset`, with coarse non-live position semantics and RTL-independent tests.

### AGG33-14 — audio meter, histogram, and waveform expose no accessible readings

- **Severity / confidence:** Medium / High
- **Source:** designer / QA-adversary
- **Evidence:** Canvas-only instruments at `Overlays.kt:570-603,1002-1086`, called from
  `CameraScreen.kt:1173-1189`, add no semantic identity or reading.
- **Failure:** TalkBack/Switch Access users cannot discover a silent mic channel, clipping, histogram
  clipping, waveform range, or pending state.
- **Plan direction:** add localized stable identities and coarse change-gated/non-live readings (or
  an explicit snapshot action), plus EN/KO Compose semantics tests.

### AGG33-15 — composition guides and horizon gauge disappear over bright content

- **Severity / confidence:** Medium / High
- **Source:** designer / QA-adversary
- **Evidence:** `Theme.kt:128-145` supplies only translucent light strokes; guides at
  `Overlays.kt:130-170` and horizon lines at `Overlays.kt:318-350` have no dark keyline/halo.
- **Failure:** white sky/snow/glare yields effectively 1:1 contrast and hides composition/level cues.
- **Plan direction:** draw a wider dark keyline beneath the quiet foreground, following the existing
  focus-reticle treatment, and add rendered bright/dark/midtone contrast fixtures.

## Agent failures

None. Every available reviewer returned and wrote its provenance artifact.

## Totals

- Raw specialist findings: 16
- Deduplicated current findings: 15
- Severity: 10 Medium, 5 Low
- Confidence: 15 High
- Deferred findings: none at review stage; Prompt 2 must schedule every item.
