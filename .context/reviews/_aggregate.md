# Aggregated deep review — cycle 56

Date: 2026-08-27
Reviewed revision: `401f2840279c8417dd35303f2799a7414768bf38` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle56.Re05OY`

## Coverage and aggregation

Eleven separate provenance files cover every required perspective: code-reviewer,
performance-reviewer, security-reviewer, critic, verifier, test-engineer, tracer, architect,
debugger, document-specialist, and native Android designer. The global thread ceiling admitted four
parallel review lanes; the cycle owner completed the remaining test, documentation, and designer
perspectives locally in separate provenance files rather than dropping them. Every lane read the
committed authorities, inventoried the repository, examined its full specialist surface and
cross-file interactions, and performed a final missed-file sweep. Browser automation was not
applicable to this native Jetpack Compose application. Every reviewer returned; there were no agent
failures.

The reports produced 20 raw findings. Six reports independently found the missing live pending-
identity retry; code/architecture/critic/verifier agreed on cross-Engine DNG admission publication;
architecture/tracer/critic/verifier agreed on StartupTrace ownership; debugger/critic/verifier found
the same tap-focus quota bypass. Those duplicates are merged below at their highest signal. The
deduplicated result is six findings: one High, four Medium, and one Low, all High confidence in the
documented or defective mechanisms. Provider/native/lifecycle occurrence boundaries and current
live credential state remain explicitly manual/fault-injection limits rather than invented evidence.

## Deduplicated findings

### AGG56-01 — active upload-key procedure preserves weak plaintext-exposed credential practice

- **Severity / confidence:** High / High in the documented procedure; current live key state is
  manual-validation because no credential material was read.
- **Source:** security-reviewer.
- **Evidence:** `docs/play-console-submit.md:781-821` records a six-digit upload-key password that
  was transmitted in plaintext, then decrypts release values into persistent exported
  `TELECAMPRO_*` shell variables without a scoped terminal cleanup.
- **Failure:** theft of the JKS plus offline brute force can permit attacker-signed Play uploads
  until upload-key reset; persistent decrypted variables expand exposure to later child processes
  and shell diagnostics.
- **Fix direction:** make the repository procedure require a strong replacement upload key before
  the next upload and use the supported Play upload-key reset path when applicable; scope decrypt,
  verification, build, and cleanup to a short-lived shell/trap without printing values. Do not
  rotate/revoke credentials or perform an upload in this cycle because those destructive/external
  actions require explicit owner confirmation.

### AGG56-02 — post-launch pending-allocation identity failures have no live retry edge

- **Severity / confidence:** Medium / High.
- **Sources / agreement:** code-reviewer, critic, verifier, debugger, test-engineer,
  document-specialist, and designer.
- **Evidence:** `MediaStoreWriter.kt:432-515` submits one recovery closure after REGISTERED identity
  freeze fails; unresolved work is retained at `:1831-1862`. The only production call to
  `retryPendingAllocationIdentities()` is launch preflight at `:1137-1148`, before ordinary
  post-launch allocations exist. Nullable identity collapse also leaves stable absence
  indistinguishable from temporary unavailability.
- **Failure:** transient provider failures accumulate inert claims until the 32-entry owner closes
  still/video admission for the process even after the provider recovers; the UI then shows generic
  unavailable copy with no progress/recovery guidance.
- **Fix direction:** preserve typed exact/absent/uncertain resolution, add a process-owned bounded
  backed-off retry with one in-flight attempt per claim, retire stable absence safely, publish
  capacity transitions across Engines, and test post-launch failure/recovery/absence/capacity reuse.

### AGG56-03 — process-wide still admission changes are lost across Engine replacement

- **Severity / confidence:** Medium / High.
- **Sources / agreement:** code-reviewer, architect, critic, and verifier.
- **Evidence:** process-global DNG/storage owners expose only pull state
  (`DngPreCaptureAllocation.kt:6-37`, `MediaStoreWriter.kt:907-913`), while each ViewModel polls once
  at `CameraViewModel.kt:1180-1186` and release publishes only through the originating Engine at
  `CameraEngine.kt:4425-4438,5266-5269`. Teardown detaches that callback before old asynchronous
  ownership can finish.
- **Failure:** a replacement UI seeded while old DNG admission is occupied stays disabled after the
  old Engine frees global capacity, because the available edge is delivered only to its detached
  callback graph.
- **Fix direction:** introduce an exact-subscription process admission coordinator (or equivalent
  observable monotonic state) covering DNG and storage capacity; publish after every owner edge and
  test old-Engine ownership, replacement subscription, detach, and terminal release.

### AGG56-04 — mixed-output and sequence DNG publication bypass process-finite tail ownership

- **Severity / confidence:** Medium / High.
- **Source:** performance-reviewer.
- **Evidence:** `StillPublicationDispatcher.kt:17-29` gives process-finite publication only to
  RAW-only SINGLE. Mixed HEIF/JPEG+DNG and BURST/AEB/timelapse publication remains on each Engine's
  single-thread `ioExecutor` (`CameraEngine.kt:5312-5420`), after processed and DNG admission leases
  are released; `release()` only shuts down without interrupting a running provider Binder call.
- **Failure:** a blocked DNG publish survives old-Engine release, while each replacement Engine can
  admit another tail and blocked worker, growing threads/callback graphs/private rows without the
  existing process ceiling.
- **Fix direction:** route every completed DNG publication through the process finite owner,
  preserving mixed/sequence ordering by dispatching after processed completion. On overflow, settle
  exactly once and leave complete bytes private for launch recovery. Test blocked old Engine plus
  repeated replacements.

### AGG56-05 — tap-focus diagnostics bypass the process log budget

- **Severity / confidence:** Medium / High.
- **Sources / agreement:** debugger, critic, and verifier.
- **Evidence:** repeatable `Touch AF: scanning` and `TapFocus: cleared` logs at
  `CameraController.kt:1185-1196` and `CameraEngine.kt:3099-3111` use only `BuildConfig.DEBUG`,
  bypassing `recurringDiagnosticAllowed`; `DiagnosticTelemetryTest.kt:68-123` and the current
  count-based docs contract omit them.
- **Failure:** roughly 150 scan/reset pairs can spend ColorOS's measured 300-row quota outside the
  180-row owner, silently dropping later frame-gap/recovery/fault evidence despite a green reserve
  test.
- **Fix direction:** budget/change-gate both rows and replace occurrence counting with an executable
  inventory that classifies every non-fault DEBUG log site. Mutation-test the tap/reset bypass.

### AGG56-06 — StartupTrace ownership is not scoped to the exact Engine/controller attempt

- **Severity / confidence:** Low / High.
- **Sources / agreement:** architect, tracer, critic, and verifier.
- **Evidence:** `StartupTrace.begin()` reuses a process-global owner while armed
  (`StartupTrace.kt:43-49`); pause/release do not reliably disarm it, and every
  `CameraEngine.wireController` samples mutable global `currentOwner()` at `CameraEngine.kt:2169-2177`.
  Controller finish checks only request generations local to that controller.
- **Failure:** pause/resume or a cold-start controller replacement can give old and new controllers
  the same token; a late old result can emit a mixed/old-origin cold-start line and suppress the real
  replacement measurement.
- **Fix direction:** retain the exact trace owner on the Engine resume/open transaction, pass it
  explicitly into the controller, disarm/revoke on pause/release/replacement, and test two
  controllers plus resume-pause-resume interleavings.

## Verified non-findings and limits

- Focused cycle-55 ownership/telemetry suites and the complete unit suite passed in review lanes;
  documentation contracts passed 155 checks with 24 optional-private skips, and the device harness
  passed its 195 host self-tests. These green tests are consistent with the uncovered composition
  gaps above.
- No tracked private key/token was found. No credential value was read, printed, copied, rotated,
  revoked, or used. No device, deployment, upload, MediaProvider mutation, native fault injection,
  or destructive action ran.
- Open field checks A3/A4/A5/D1/E1/E2/E3 remain evidence obligations, not deferred code findings.

## AGENT FAILURES

None.

## Provenance

- `.context/reviews/cycle56-code-reviewer.md`
- `.context/reviews/cycle56-architect.md`
- `.context/reviews/cycle56-perf-reviewer.md`
- `.context/reviews/cycle56-tracer.md`
- `.context/reviews/cycle56-security-reviewer.md`
- `.context/reviews/cycle56-debugger.md`
- `.context/reviews/cycle56-critic.md`
- `.context/reviews/cycle56-verifier.md`
- `.context/reviews/cycle56-test-engineer.md`
- `.context/reviews/cycle56-document-specialist.md`
- `.context/reviews/cycle56-designer.md`

---

# Aggregated deep review — cycle 55

Date: 2026-08-27
Reviewed revision: `121fcdf09265262ea1c5d2710bddb61b12c3a38f` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle55.32UR9V`

## Coverage and aggregation

Five provenance reports cover every required perspective: code-reviewer, performance-reviewer,
security-reviewer, critic, verifier, test-engineer, tracer, architect, debugger,
document-specialist, and native Android designer. The environment thread ceiling admitted two
parallel full-repository agents; the cycle owner explicitly completed the remaining three role groups
locally in separate provenance files rather than dropping them. All lanes read the committed
authorities, inventoried 562 tracked paths / 459 current review-relevant files, examined their full
specialist surfaces and cross-file interactions, and performed final missed-file sweeps. Browser
automation was not applicable to this native Jetpack Compose app. Every started reviewer returned;
there were no agent failures.

The reports produced 12 raw findings. Four reports independently found the missing DNG allocation
deadline, and verifier/document reviewers independently found the incomplete diagnostic-budget
boundary; those duplicates are merged at their highest signal. The deduplicated result is eight
current findings: seven Medium and one Low, all High confidence in the defective mechanisms. Provider
wedges and the rapid lifecycle startup interleave remain explicit fault-injection/manual occurrence
boundaries rather than invented device evidence.

## Deduplicated findings

### AGG55-01 — rapid second DNG press can leave the shutter permanently false-disabled

- **Severity / confidence:** Medium / High.
- **Source:** code-reviewer/architect/critic.
- **Evidence:** `CameraEngine.kt:4411-4415` publishes unavailable when the DNG singleton rejects a
  second press, but `stillOutputAdmissionAvailable()` at `:4902-4903` omits that singleton. The
  successful and error callback terminals release the lease at `:5391-5411` without republishing
  recomputed availability.
- **Failure:** a rapid second RAW press disables `CameraUiState.stillCaptureAdmissionAvailable`; the
  first shot later completes and frees admission, but the primary shutter stays disabled until an
  unrelated recovery event republishes it.
- **Fix direction:** include DNG ownership in the authoritative still-admission projection and use
  one exactly-once lease terminal that republishes after every release. Test the production
  Engine/ViewModel second-press/success and second-press/error interleaves.

### AGG55-02 — post-allocation Camera2 failures strand empty REGISTERED DNG rows

- **Severity / confidence:** Medium / High.
- **Source:** code-reviewer/architect/critic.
- **Evidence:** DNG preallocation creates an exact durable row at `CameraEngine.kt:4466-4474`, but
  `photoCallback` cancels its reserved rejected-output cleanup when RAW is unexpectedly null at
  `:5383-5386` and on controller error/watchdog at `:5401-5411`.
- **Failure:** session closure, capture refusal/failure, or watchdog after preallocation leaves an
  empty `IS_PENDING=1` row until next launch. Repeated failures grow rows and journal work without
  same-process cleanup/backpressure.
- **Fix direction:** submit the frozen allocation through the already-reserved finite cleanup owner
  on every no-complete-DNG terminal; cancel only when complete-byte ownership transferred. Test
  refusal, watchdog/error, raw-null, saturation, and exactly-once cleanup.

### AGG55-03 — provider-identity freeze failure drops a registered image/video row from live ownership

- **Severity / confidence:** Medium / High.
- **Source:** code-reviewer/architect/critic.
- **Evidence:** `MediaStoreWriter.kt:390-453` inserts and durably REGISTERED-marks a pending image or
  video before `PendingDiscardJournal.captureAllocation`; if the identity read is unavailable,
  ambiguous, or mismatched (`PendingDiscardJournal.kt:32-41`), the factory collapses the result to
  null and forgets the URI until a later process launch.
- **Failure:** every retry during a provider identity outage can add another hidden row and durable
  preference entry. The already-completed launch recovery cannot see these new rows, and the pending
  journal has no same-process capacity bound.
- **Fix direction:** return/retain typed `REGISTERED_WITHOUT_IDENTITY` truth in a finite process
  recovery owner, retry non-destructively until exact identity exists, and fail new output admission
  closed at capacity. Cover both image and video with unavailable/ambiguous/mismatched identities.

### AGG55-04 — DNG provider allocation has no attempt deadline

- **Severity / confidence:** Medium / High.
- **Sources / agreement:** performance/tracer, security/debugger, verifier/test-engineer, and
  document-specialist/designer.
- **Evidence:** `CameraEngine.kt:4411-4522` holds DNG, snapshot, cleanup, and family ownership around
  `DngPreCaptureAllocation`; `DngPreCaptureAllocation.kt:40-97` has explicit cancellation but no
  scheduler/deadline. The finite shared dispatcher cannot interrupt a running provider call
  (`RecordingPreNativeAllocation.kt:27-80`), while the sibling REC path has an eight-second
  first-wins deadline at `CameraEngine.kt:5616-5689`.
- **Failure:** a foreground, same-route provider wedge keeps the shutter/family leases and one shared
  allocator worker forever; cancellation/retry churn can consume the second worker and queue, also
  starving REC allocation.
- **Fix direction:** arm a first-wins DNG deadline before dispatch; timeout immediately settles
  caller ownership and sends any late exact row to recovery cleanup. Test timeout/return/cancel races
  and scheduler rejection through production wiring.

### AGG55-05 — sequence-drive processed snapshots multiply across Engine replacement

- **Severity / confidence:** Medium / High.
- **Source:** performance/tracer.
- **Evidence:** `ProcessedSnapshotBudget.kt:5-13` is process-wide for SINGLE only. BURST/AEB/
  timelapse dispatch without a lease at `CameraEngine.kt:4624-4757`, then the callback retains a
  full snapshot on an Engine-local unbounded `ioExecutor` at `:5238-5270`; `release()` merely calls
  `shutdown()` at `:7391-7399` and cannot reclaim a blocked running task.
- **Failure:** replacing the Activity/Engine while a sequence save is blocked admits another
  full-resolution sequence snapshot and worker. Repetition grows heap/native state per Engine until
  memory pressure or OOM despite the SINGLE process budget.
- **Fix direction:** require every processed snapshot to consume process-wide capacity while keeping
  sequence chaining for order. Test blocked old-Engine BURST/AEB/timelapse against replacement
  admission and exact release.

### AGG55-06 — throwing late-value cleanup skips pre-native retirement

- **Severity / confidence:** Medium / High.
- **Source:** performance/tracer.
- **Evidence:** `RecordingPreNativeAllocationAttempt.retire` at
  `RecordingPreNativeAllocation.kt:239-267` invokes `onLateValue` before `completeRetirement` without
  `try/finally`. DNG retirement releases its admission/snapshot/family owners only in `onRetired`
  (`CameraEngine.kt:4439-4519`); the same generic attempt owns the REC process token at
  `:5630-5641`.
- **Failure:** a cleanup/observer exception after an allocation-return-vs-cancel/timeout race exits
  retirement early, permanently leaking DNG or REC admission even though the row remains durable.
- **Fix direction:** guarantee `completeRetirement` in `finally`, retaining diagnostics without
  suppressing ownership release. Test throwing late cleanup for DNG and video.

### AGG55-07 — repeatable capture logs bypass the process diagnostic budget

- **Severity / confidence:** Medium / High.
- **Sources / agreement:** verifier/test-engineer and document-specialist/designer.
- **Evidence:** `DiagnosticTelemetryTest.kt:68-107` claims every recurring producer but models only
  3A/focus/motion/hardware/ZSL-spike. Unbudgeted Single `CaptureFamily` rows live at
  `CameraEngine.kt:4433-4438,5048-5053,5185-5192`; unbudgeted ZSL refusal/serve and three real-shot
  `ShutterLag` rows live at `CameraController.kt:1558-1624,2106-2127,2228-2232`.
- **Failure:** about 60 ordinary debug shots can spend ColorOS's measured 300-row quota, silently
  dropping later frame-gap/recovery/fault evidence despite the green test claiming a 120-row reserve.
- **Fix direction:** route every action-repeatable capture diagnostic through the shared owner (or a
  bounded capture trace gate) and make the executable call-site inventory complete.

### AGG55-08 — StartupTrace accepts stale controller/request generations

- **Severity / confidence:** Low / High; exact lifecycle occurrence remains fault-injection/manual
  validation.
- **Source:** performance/tracer.
- **Evidence:** `StartupTrace.kt:23-64` owns only a global running bit and accepts no token.
  `CameraEngine.resume()` arms before asynchronous old-controller close completes at
  `CameraEngine.kt:7035-7064`, while every controller request's first diagnostic callback can finish
  that global trace at `CameraController.kt:1029-1034,1111-1119`.
- **Failure:** a queued old first result after rapid background/foreground can finish the new resume
  trace with mixed or one-mark timing, fabricating a fast cold-start measurement and suppressing the
  real replacement result.
- **Fix direction:** make `begin` return an opaque generation and require it on mark/finish; pass it
  only to the controller/open/request created for that resume attempt. Test old-result/new-resume and
  two-request races.

## Verified non-findings and limits

- The complete authoritative host gate passed: Android debug/androidTest assembly, all JVM/
  Robolectric/Compose tests, debug lint, 99.83% Partition A coverage, 136 tool tests, nine coverage
  tool tests, 195 device-harness self-tests, 155 documentation checks with 24 optional-private skips,
  Python compilation, and `git diff --check`.
- Cycle-54 standby native quarantine, review source immutability/spool accounting, and the bounded
  diagnostic producers already behind the process owner were rechecked and not refiled.
- No device, deployment, browser, MediaProvider mutation, native fault injection, destructive
  filesystem action, or physical evidence action ran. Open checks A3/A4/A5/D1/E1/E2/E3 remain
  evidence obligations, not deferred code findings.

## AGENT FAILURES

None.

## Provenance

- `.context/reviews/cycle55-code-architect-critic.md`
- `.context/reviews/cycle55-perf-tracer.md`
- `.context/reviews/cycle55-security-debugger.md`
- `.context/reviews/cycle55-verifier-test.md`
- `.context/reviews/cycle55-document-designer.md`

---

# Aggregated deep review — cycle 54

Date: 2026-08-27
Reviewed revision: `bf40ae2c56c154072691815f83b7090a31f0c424` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle54.7ZqPtj/repo`

## Coverage and aggregation

Five provenance files cover every required perspective: code-reviewer, performance-reviewer,
security-reviewer, critic, verifier, test-engineer, tracer, architect, debugger,
document-specialist, and native Android designer. The environment's thread ceiling admitted two
parallel full-repository agents; the cycle owner completed the three remaining specialist groups
locally rather than dropping them. Every lane read the committed authorities, inventoried the full
repository, reviewed cross-file behavior from its specialist angle, and performed a final missed-file
sweep. Browser automation was not applicable to this native Jetpack Compose application. Every
started reviewer returned, and there were no agent failures.

The reports produced seven raw findings. Security/debugger, verifier/test-engineer, and
document/designer independently found the same mutable review-metadata path, merged below at the
highest signal. The deduplicated result is five findings: one High and four Medium, all High
confidence in the defective mechanisms. Filesystem/native/provider occurrence boundaries remain
explicit device or fault-injection limits rather than inferred facts.

## Deduplicated findings

### AGG54-01 — standby AudioRecord cleanup failures escape native ownership

- **Severity / confidence:** High / High.
- **Source:** code-reviewer/architect/critic.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/StandbyAudioController.kt:411-422`
  constructs an `AudioRecord`, swallows an uninitialized-recorder `release()` failure, then returns
  a resource-free `Failure`; `:618-637` therefore registers no publication owner. The ordinary
  terminal at `:264-277` likewise wraps `release(value)` in `runCatching`, after which the process
  gate publishes success and clears the exact input even if release threw.
- **Failure:** an uncertain native microphone is forgotten while process/standby ownership reopens,
  permitting the next standby or REC input to coexist with a leaked native owner and violating the
  exactly-one-mic contract.
- **Fix direction:** publish every constructed input before validation cleanup, propagate typed
  stop/release failure through the native gate, quarantine and strongly retain the exact input before
  waking waiters, and fault-test both uninitialized and ordinary terminal release exceptions.

### AGG54-02 — DNG allocation identity still blocks the Camera2 callback

- **Severity / confidence:** Medium / High.
- **Source:** performance/concurrency/tracer.
- **Evidence:** `CameraEngine.kt:5113-5118` calls `StillCapturePipeline.saveDng` while the RAW Image
  is live. `StillCapturePipeline.kt:361-384` calls `createPendingImageAllocation`, whose
  `MediaStoreWriter.kt:390-421` path performs provider insert, synchronous REGISTERED persistence,
  and `PendingDiscardJournal.captureAllocation`. `PendingDiscardJournal.kt:32-42,541-584` performs
  volume/version reads and an exact provider query without a deadline. `CameraController.kt:2176-2220`
  cannot close the Image or advance its handler until the callback returns.
- **Failure:** a slow or wedged MediaProvider holds the full-resolution RAW Image and Camera2 handler
  before the cycle-53 rejected-output dispatcher can help, stalling later results/watchdogs/captures.
- **Fix direction:** preallocate/freeze the DNG row on a finite pre-capture provider lane, submit
  Camera2 only after exact allocation is claimed, carry it into the live-Image write, and route
  cancellation/late allocation to bounded cleanup/recovery. Block allocation identity in a test and
  prove no live Image waits on it.

### AGG54-03 — the log-quota test omits recurring producers that exhaust ColorOS's cap

- **Severity / confidence:** Medium / High.
- **Source:** performance/concurrency/tracer.
- **Evidence:** `CameraViewModel.kt:547-590` emits an unchanged FocusConfidence heartbeat every two
  seconds, driven by recurring analysis at `:1026-1039`; that is about 300 rows over the ten-minute
  A5 soak by itself. `MainActivity.kt:743-750,869-872` also logs standard zoom-key repeat edges at
  the measured roughly 20 Hz. `DiagnosticTelemetry.kt:26-51` and its test bound only 3A/ZSL and call
  `rows + 2 <= 210` a process reserve without these producers. `CLAUDE.md:1054-1072` records a
  300-row per-process ceiling.
- **Failure:** ordinary debug photo analysis loses late soak faults/gaps before ten minutes, while a
  held hardware zoom slide can spend the full quota in about 15 seconds and hide its own ZoomTrace
  and frame-gap evidence.
- **Fix direction:** put recurring diagnostics behind a shared quota owner or at minimum use a slow
  stable focus heartbeat and start/terminal/change-gated hardware-key summaries. Extend the budget
  test across 3A, focus, motion, hardware input, ZSL, startup, gaps, recovery, and faults.

### AGG54-04 — review metadata bypasses the immutable source and unverified byte bound

- **Severity / confidence:** Medium / High.
- **Sources / agreement:** security/debugger, verifier/test-engineer, document-specialist/designer.
- **Evidence:** `MediaReview.kt:469-505` freezes bounded still bytes for bitmap bounds/pixels/
  orientation, but `MediaReview.kt:609-631` separately opens the provider and constructs
  `ExifInterface` for the displayed ISO/shutter/focal plate before the later decode at `:699-748`.
  `ReviewDecodeSourceTest.kt:70-118` tests only the latter frozen decoder; no test binds
  `loadMetadata` to that identity or ceiling.
- **Failure:** provider mutation can display one file's pixels beside another's capture metadata;
  owner-unverified input can also reach EXIF parsing without the intended 64 MiB compressed-input
  ceiling, consuming finite descriptor workers on pathological data.
- **Fix direction:** acquire one frozen compressed still source per review request and derive all
  byte-based metadata/orientation/pixels from it, retaining separate identity-checked MediaStore
  columns. Test alternating provider identities, over-ceiling unverified EXIF, one content open, and
  timeout/replacement disposal.

### AGG54-05 — failed review-spool deletion releases accounting while bytes remain

- **Severity / confidence:** Medium / High.
- **Source:** code-reviewer/architect/critic.
- **Evidence:** `LatestHeavyWorkLane.kt:92-116` ignores a false/throwing spool-file delete and always
  releases its `ReviewSourceByteBudget` lease; partial-spool cleanup at `:269-275` has the same
  pattern. Directory cleanup at `:159-200` runs once per process and is bounded. Current tests cover
  successful deletion only.
- **Failure:** repeated filesystem deletion failures leave 64–512 MiB files in cache while the
  advertised 1 GiB process budget sees zero ownership, allowing unbounded same-process cache growth
  until review or capture storage fails.
- **Fix direction:** make file absence a typed terminal, retain byte accounting and exact orphan
  ownership until bounded cleanup proves absence, and fail closed on new spool admission if that
  finite owner is full. Inject false/throwing delete, retry success, partial-file failure, and a
  repeated-close capacity sequence.

## Verified non-findings and limits

- Cycle-53 standby publication, immediate DISCARD identity, rejected-output cleanup dispatcher,
  immutable bitmap decode, and bounded 3A/ZSL changes are present; the findings above are distinct
  cleanup/allocation/process-composition/metadata/delete terminals not covered by those fixes.
- `python3 tools/verify_host.py` passed completely in the code/architecture lane. Focused ownership,
  storage, telemetry, and recording tests passed in the performance lane. The documentation contract
  passed 155 checks with 24 expected optional-private skips.
- No device, deployment, browser, MediaProvider mutation/reset, native fault injection, destructive
  filesystem operation, or physical evidence action ran. Open checks A3/A4/A5/D1/E1/E2/E3 remain
  evidence obligations, not deferred code findings.

---

# Aggregated deep review — cycle 53

Date: 2026-08-25
Reviewed revision: `fcf7ba2ca856fe8885373eb75677c3057173e6d6` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle53.cJwfCJ`

## Coverage and aggregation

Three parallel specialist lanes covered every required perspective: code-reviewer, performance-
reviewer, security-reviewer, critic, verifier, test-engineer, tracer, architect, debugger,
document-specialist, and native Android designer. The team concurrency ceiling prevented separate
workers for every title, so the required roles were explicitly combined rather than dropped. Each
lane read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md`, inventoried all 546 tracked
paths, examined its complete specialist surface and cross-file interactions, and performed a final
missed-file sweep. Browser automation was not applicable to this native Jetpack Compose app, and
device/deployment action was forbidden. Every reviewer returned; there were no agent failures.

The reports produced six raw findings. The mutable app-owned review-source issue was independently
confirmed by two lanes and is merged below at the highest confidence. The deduplicated result is five
findings: one High and four Medium; all five have High confidence in the defective mechanism, while
the destructive provider-reassignment precondition remains a device/manual evidence boundary.

## Deduplicated findings

### AGG53-01 — standby native acquisition can escape quarantine publication ownership

- **Severity / confidence:** High / High.
- **Source:** security-reviewer/debugger.
- **Evidence:** `VideoRecorder.kt:1211-1243` returns only a momentary
  `NativeAcquisitionResult.RETURNED_CURRENT` after leaving the process gate.
  `StandbyAudioController.kt:531-584` binds a new input and publishes start completion only after
  that result escapes the gate, while ordinary cleanup remains at `:642-648`. Quarantine may close
  between the return and bind/`finishStart`. The stop-timeout path at
  `StandbyAudioController.kt:238-251` conversely exposes `abandoned` before its production callback
  closes process admission at `:659-672` / `VideoRecorder.kt:1431-1437`.
- **Failure:** a create/start call returns current, quarantine wins before caller publication, then
  the controller publishes or ordinarily cleans the input after native cleanup has been declared
  unsafe. During a stop timeout, replacement acquisition can enter after abandonment but before
  global quarantine closes, multiplying an uncertain graph.
- **Fix direction:** make concrete create/start publication an exact process-gate transaction, with
  a token that atomically commits the termination owner or classifies/retains it as revoked. Close
  process admission and install strong retention before exposing stop-timeout abandonment or waking
  logical waiters. Deterministically test both return-to-bind gaps and the timeout competition.

### AGG53-02 — immediate DISCARD delete does not consume the identity it just persisted

- **Severity / confidence:** Medium / High for the unsafe mechanism; real URI reuse remains a field
  precondition.
- **Source:** security-reviewer/debugger.
- **Evidence:** `PendingDiscardJournal.kt:33-79` records the identity currently occupying a URI but
  accepts no expected allocation/family identity. `MediaStoreWriter.kt:453-475` then calls ordinary
  `delete(context, uri)`; `:827-849` uses `expectedIdentity = null`, so it is unconditional.
  Identity-conditioned deletion exists only during later replay. The replay predicate omits nullable
  expected owner/date columns instead of requiring `IS NULL`. Callers such as
  `StillCapturePipeline.kt:382-396` possess a family key but pass only the URI.
- **Failure:** a provider/gallery removes or reassigns row A before marker creation, or between marker
  commit and immediate deletion. The caller can bless and/or unconditionally delete row B, then clear
  A's marker. A remap differing only in a stored-null field can also evade the replay predicate.
- **Fix direction:** carry immutable allocation/family identity into marker creation, return the
  committed record, and use its full null-safe predicate for the immediate delete. Fail closed across
  provider-version changes; test reassignment before mark and after mark/before delete.

### AGG53-03 — DNG failure cleanup can block the Camera2 callback while the RAW Image stays live

- **Severity / confidence:** Medium / High.
- **Source:** performance/concurrency/tracer.
- **Evidence:** `CameraEngine.kt:5076-5087` invokes synchronous `saveDng` from the Camera2 callback.
  `StillCapturePipeline.kt:336-367` handles a failed write by calling `discardRejectedOutput(uri)`
  before return. `MediaStoreWriter.kt:453-475,1597-1637` performs marker retries, sleeps, provider
  delete/probe work, and exact-row identity/SQLite work synchronously. `CameraController.kt:2149-2193`
  closes the RAW Image only after the app callback returns.
- **Failure:** a partial DNG write plus slow or wedged MediaProvider holds an ImageReader slot and the
  Camera2 handler while identity queries, retries, sleeps, delete, and probes run; repeating results,
  watchdogs, 3A, and later captures queue behind it.
- **Fix direction:** reserve a process-finite rejected-output cleanup owner before capture and hand
  failed URIs to it after DNG byte work, so the camera callback can close the Image immediately while
  durable fail-closed cleanup continues off-thread. Block the fake identity reader in a test and
  prove callback/Image completion plus eventual exact cleanup ownership.

### AGG53-04 — app-owned review reopens mutable provider bytes for bounds, pixels, and EXIF

- **Severity / confidence:** Medium / High.
- **Sources / agreement:** code-reviewer/architect/critic/verifier/test-engineer and
  security-reviewer/debugger.
- **Evidence:** `MediaReview.kt:463-486` maps `APP_OWNED` provenance to
  `FreshProviderReviewSource`; every `openInputStream()` resolves the provider again. Bounds, pixels,
  and EXIF are opened independently at `:514-547`. `APP_OWNED` proves provider attribution, not
  immutable bytes. `ReviewDecodeSourceTest.kt:28-59` requires three opens but keeps them byte-
  identical; only the unverified spool has a mutation test.
- **Failure:** bounds sees a small JPEG and selects `inSampleSize=1`; a consented gallery edit makes
  the pixel open expose a 100–200 MP JPEG, so BitmapFactory attempts the full allocation before the
  post-decode size check. A third byte identity can independently supply unrelated EXIF orientation.
- **Fix direction:** consume one immutable compressed snapshot for every provenance class. Preserve
  valid >64 MiB app-owned hi-res support with a disk-aware policy or truthful fallback, while bounds,
  pixels, and EXIF share one frozen identity. Test alternating small/large/EXIF sources and a valid
  above-64-MiB trusted fixture.

### AGG53-05 — continuous debug telemetry exhausts the measured ColorOS log quota during A5

- **Severity / confidence:** Medium / High.
- **Source:** performance/concurrency/tracer/document/designer.
- **Evidence:** `CameraController.kt:1060-1123` logs a full `3A:` row every 30 repeating results for
  the lifetime of a debug session. `:1582-1609` can add one sustained-YUV row per second.
  `CLAUDE.md:1054-1065` records a 300-row per-process ColorOS quota, while
  `docs/FIELD_CHECKS.md:106-127` requires a ten-minute debug soak whose later gaps, errors, and
  recovery evidence must survive.
- **Failure:** 30 fps produces roughly 300 3A rows in five minutes before startup/session/fault rows;
  even 15 fps reaches the budget during the ten-minute run. The optional one-Hz probe makes loss
  faster, silently dropping the second half's evidence.
- **Fix direction:** change-gate a bucketed compact 3A tuple with a long heartbeat and accumulate
  sustained-YUV cadence into a bounded summary rather than per-second rows. Add a pure ten-minute
  budget test covering continuous debug producers and reserving space for faults/gaps.

## Verified non-findings and limits

- Cycle-52 fixes for StateFlow policy ordering, rollback effects after monitor release, PNG bounds,
  standby revoked/timeout classification, versioned DISCARD replay, stale spool cleanup, duplicate
  Compose focus owners, and Korean release history are present and are not refiled.
- The isolated baseline debug gate passed `:app:assembleDebug`, `:app:testDebugUnitTest`, and
  `:app:lintDebug`. One reviewer independently passed the authoritative host gate; another passed
  focused UI/review/storage/audio tests and documentation checks.
- No device, deploy, MediaProvider reset, native fault injection, Camera2/GL fault injection,
  physical converter, or production signing action ran. Field checks A3/A4/A5/D1/E1/E2/E3 remain
  manual evidence obligations.

---

# Aggregated deep review — cycle 52

Date: 2026-08-25
Reviewed revision: `96732cc97ce8ff7f333478084eb365333ac505b6` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle52.868ovy/repo`

## Coverage and aggregation

Four parallel specialist lanes covered every required perspective: code-reviewer, performance-
reviewer, security-reviewer, critic, verifier, test-engineer, tracer, architect, debugger,
document-specialist, and native Android designer. Thread capacity initially required combined role
coverage; a dedicated document/designer lane was added as soon as a slot became available. Each
lane read the complete repository authorities, inventoried all 540 tracked paths, examined its full
specialist surface and cross-file interactions, and performed a final missed-file sweep. Browser
automation was not applicable to this native Jetpack Compose app, and device/deployment action was
forbidden. Every reviewer returned; there were no agent failures.

The reports produced eleven raw findings. The MediaStore DISCARD identity-reset risk was reported
independently by security/test and documentation/design reviewers and is merged below at the highest
reported signal. The deduplicated result is ten findings: one High, five Medium, and four Low; seven
are High confidence and three are Medium confidence.

## Deduplicated findings

### AGG52-01 — camera-policy sequence admission is split from its StateFlow write

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer/architect; independently reproduced during aggregation.
- **Evidence:** `CameraViewModel.kt:1082-1091` advances the atomic publication sequence before
  entering `_state.update` and never rechecks ownership inside the reducer. The existing test at
  `CameraViewModelRobolectricTest.kt:920-927` is sequential and cannot force the check-to-write gap.
- **Failure:** seq1 can pass admission and pause, seq2 can commit unblocked truth, then seq1 can
  resume and repaint the camera-policy gate over a healthy replacement session.
- **Fix direction:** recheck exact sequence ownership inside every StateFlow reducer retry (or use
  one serialized gate) and force the old-admitted/new-committed/old-resumed interleave in a test.

### AGG52-02 — the review spool's 64 MiB ceiling rejects a supported valid hi-res still

- **Severity / confidence:** Medium / Medium
- **Source:** code-reviewer/architect.
- **Evidence:** `LatestHeavyWorkLane.kt:103-154` refuses compressed sources above 64 MiB, while
  `StillCapturePipeline.kt:144-164` deliberately publishes Camera2 hi-res JPEG bytes without a
  corresponding encoded-size ceiling. `MediaReview.kt:453-471,649-669,729-737` maps this to failed
  thumbnail/fullscreen review.
- **Failure:** a capable non-PMA110 device can save a valid >64 MiB approximately-200 MP JPEG that
  TeleCam itself cannot review.
- **Fix direction:** use a stable direct source for trusted app-owned published media while keeping
  bounded immutable spooling for owner-unverified rows; add above/below-bound coverage and preserve
  decoded-pixel bounds. Device measurement remains required before claiming concrete output sizes.

### AGG52-03 — review spool files have no crash/restart reclamation owner

- **Severity / confidence:** Low / High
- **Source:** code-reviewer/architect.
- **Evidence:** `LatestHeavyWorkLane.kt:82-100,123-155` creates `review-source-*.bin` directly under
  cache and only attempts deletion from the live in-memory owner. No startup/process owner reclaims
  files left by process death, and normal deletion success is unchecked.
- **Failure:** repeated kills during large review work can accumulate orphan compressed sources
  until cache/storage pressure breaks later review or capture work.
- **Fix direction:** use a dedicated private spool directory with bounded, no-follow stale cleanup
  before first admission; test restart fixtures and failed cleanup without touching unrelated cache.

### AGG52-04 — screenshot PNG validation accepts out-of-range 8-bit `tRNS` samples

- **Severity / confidence:** Low / High
- **Source:** code-reviewer/architect; production parser reproduction confirmed.
- **Evidence:** `tools/check_docs.py:246-255` validates truecolor `tRNS` placement/length but never
  bounds its three 16-bit samples to the IHDR bit depth. A CRC-correct 8-bit PNG with sample 256 was
  accepted.
- **Failure:** malformed Play/release screenshot evidence can pass after digest refresh and render
  differently or fail in stricter PNG consumers.
- **Fix direction:** unpack `>HHH`, require each sample `< (1 << bit_depth)`, and mutation-test 255,
  256, and 65535 with clean diagnostics.

### AGG52-05 — optics rollback executes external publications while holding the Engine monitor

- **Severity / confidence:** Low / High
- **Source:** code-reviewer/architect.
- **Evidence:** synchronized `CameraEngine.rollbackOptics` at `CameraEngine.kt:849-977` performs GL/
  controller work and invokes `onOpticsRollback`, `onCapsReady`, `onVideoSizeChosen`,
  `onPreviewAspect`, `onCameraReadyChange`, and `onStatus` while holding the Engine monitor, contrary
  to the post-monitor publication authority in `docs/ARCHITECTURE.md:301-307`.
- **Failure:** slow or reverse-locking UI/component callbacks can stall camera/REC/control ownership
  or create an ABBA deadlock surface during rollback.
- **Fix direction:** commit an immutable rollback/effects packet under the monitor, then execute
  exact controller/GL/publication commands after unlock with identity checks and lock-state tests.

### AGG52-06 — durable DISCARD replay can delete a replacement row after MediaStore identity reset

- **Severity / confidence:** Medium / Medium
- **Sources / agreement:** security/test and document/designer.
- **Evidence:** `PendingDiscardJournal.kt:9-46,107-149,215-244` persists only a raw URI; recovery at
  `MediaStoreWriter.kt:1200-1224` deletes whatever now occupies that URI without version or row-
  identity validation. Android's current `MediaStore.getVersion` contract requires resynchronization
  after substantial provider changes.
- **Failure:** after a provider reset/reindex reuses `_ID=417`, an old marker for URI 417 can delete
  a different app-owned capture and then clear itself.
- **Fix direction:** persist provider volume/version plus expected row/family identity, validate it
  before destructive replay, migrate URI-only records fail-closed, and test URI reassignment. A
  disposable device check is required before claiming specific OEM reuse behavior.

### AGG52-07 — quarantine revocation can drop or ordinarily clean a standby `AudioRecord`

- **Severity / confidence:** Medium / High
- **Source:** performance/concurrency/tracer/debugger.
- **Evidence:** `StandbyAudioController.kt:454-491` collapses pre-entry refusal and returned-after-
  revocation into one Boolean from `runNativeAcquisition`. A revoked successful create is never
  deliberately retained; a revoked start reaches ordinary stop/release at `:543-548`, violating the
  process native-quarantine contract in `VideoRecorder.kt:1170-1213,1392-1412`.
- **Failure:** Camera/recorder quarantine racing standby mic create/start makes native lifetime depend
  on GC or post-terminal cleanup rather than exact process retention.
- **Fix direction:** return a typed acquisition outcome and quarantine-retain the exact input/owner
  after returned-but-revoked create/start; test both interleavings and forbid publication/cleanup.

### AGG52-08 — blocked standby `AudioRecord.stop()` has no deadline or quarantine terminal

- **Severity / confidence:** High / High
- **Source:** performance/concurrency/tracer/debugger.
- **Evidence:** `StandbyInputTerminationOwner.finishAndRelease` at
  `StandbyAudioController.kt:140-177` waits forever once an accepted stop task blocks; no elapsed
  deadline closes process admission. The stranded terminal blocks input release, the process token,
  ownership completion, and the REC handoff latch behind `:543-566`. The 400 ms REC wait at
  `CameraEngine.kt:5522-5529` only aborts that take.
- **Failure:** a vendor `AudioRecord.stop()` hang permanently locks out REC and replacement standby
  generations without restart-required truth, while the microphone owner may remain live.
- **Fix direction:** add an independent hard deadline that atomically quarantines and strongly
  retains the exact input/termination owner, releases only the logical handoff, makes late return
  inert, and surfaces restart-required status; fault-test stop hang and late return.

### AGG52-09 — six Compose control families install duplicate focus owners

- **Severity / confidence:** Medium / Medium
- **Source:** document-specialist/native designer.
- **Evidence:** explicit `Modifier.focusable()` precedes already-focusable `clickable`/`selectable`
  at `ProSheet.kt:409-425,470-499`, `ManualDials.kt:395-416,442-458`, and
  `CameraScreen.kt:1816-1838,2516-2530`. Existing modal focus tests prove initial focus but never
  activate it or count traversal edges.
- **Failure:** keyboard/D-pad users can land on an invisible duplicate target, need an extra step,
  or fail to activate the visually focused Close/control.
- **Fix direction:** remove redundant explicit focus owners, keep manual semantics, and test Enter/
  DirectionCenter plus one-edge traversal for Close and all settings tabs.

### AGG52-10 — the v1.0.1 localization record reports two incompatible counts

- **Severity / confidence:** Low / High
- **Source:** document-specialist/native designer.
- **Evidence:** `CLAUDE.md:44-46` says v1.0.1 shipped 126 Korean strings, while
  `docs/play-console-submit.md:197-203` says 131. The named versionCode-3 source contains 126 Korean
  `<string>` entries.
- **Failure:** retained release provenance can produce an irreproducible localization claim.
- **Fix direction:** correct the submission record to 126 (or document a reproducible alternative
  metric) and add a historical-source contract assertion.

## Verified non-findings and limits

- The full host gate was green before implementation, but it does not cover policy check-to-write,
  native create/start revocation, blocked mic stop, process-death spool cleanup, valid >64 MiB
  trusted review, URI reassignment, duplicate Compose focus activation, or PNG sample bounds.
- Interactive REC size/FPS writes do not tear the cycle-51 frozen packet under current main-thread
  ViewModel ordering; both code and performance reviewers independently rejected that hypothesis.
- A false `File.delete()` on a gracefully closed spool was not separately counted; the material
  defect is missing abnormal-termination reclamation.
- No device, deploy, Camera2/Audio HAL fault injection, MediaProvider reset, physical converter,
  microphone, or browser action ran. A3/A4/A5/D1/E1/E2 remain manual evidence obligations.

---

# Aggregated deep review — cycle 51

Date: 2026-08-25
Reviewed revision: `7eb4ee951e769afe884f8115ffbde25c828028a3` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle51.WTu2dW`

## Coverage and aggregation

Four parallel specialist groups covered every required role: code-reviewer, performance-reviewer,
security-reviewer, debugger, test-engineer, tracer, critic, verifier, architect,
document-specialist, and native Android designer. Thread capacity prevented a fifth worker, so the
critic/verifier worker completed the architect, documentation, and designer passes in the same
review turn. No repository-local reviewer definition was registered. Every group inventoried the
complete 538-path revision, examined its full specialist surface and cross-file interactions, and
performed a final missed-issue sweep. Browser automation was not applicable to this native Jetpack
Compose app, and deployment/device interaction was forbidden. Every reviewer returned; there were
no agent failures.

The reports produced thirteen raw findings. The rollback-to-GL race was independently confirmed by
test, trace, critic, verifier, architecture, and documentation reviewers; its matching test gap is
part of the same root cause. The Loupe/pipeline documentation drift was independently reported by
critic, verifier, documentation, and design reviewers. The remaining findings have distinct causes.
The deduplicated result is nine findings: six Medium and three Low; seven are High confidence and two
are Medium confidence.

## Deduplicated findings

### AGG51-01 — renderer initialization transfers VBO/texture ownership without checking GLES errors

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/gl/FlipRenderer.kt:142-220` now cleans
  locally owned ids on Kotlin exceptions and zero ids, but `glBufferData` at `:168-173` and external
  texture setup at `:180-184` never inspect `glGetError`. GLES can report `GL_OUT_OF_MEMORY` or an
  invalid operation while retaining a non-zero object name. The ids are then transferred at
  `:186-209`, and `FlipRendererResourceOwnershipTest.kt` exercises only synthetic acquisition
  prefixes rather than non-throwing GLES error state.
- **Failure:** an allocation failure can publish a renderer backed by an unallocated VBO or invalid
  texture state; later draw errors need not fail `eglSwapBuffers`, so preview can be declared Ready
  while blank/corrupt and the bounded initialization recovery path never runs.
- **Fix direction:** clear and inspect operation-scoped GLES error state after every mutating setup
  step before ownership transfer, route failures through exact local cleanup, and fault-inject each
  non-throwing error plus a same-context retry.

### AGG51-02 — immutable review snapshots amplify one admitted source into process-scale heap pressure

- **Severity / confidence:** Medium / High
- **Source:** performance-reviewer.
- **Evidence:** `MediaReview.kt:441-459` grows a `ByteArrayOutputStream` to as much as 64 MiB and
  then copies it again in `toByteArray`; `:476-492` retains that array while allocating a decoded
  bitmap and possibly a second rotation bitmap. The 240 px tile takes the same full-source path.
  `LatestHeavyWorkLane.kt:135-141,236-243,288-300` permits overlapping retired/replacement calls on
  four process workers, so latest-wins publication does not bound simultaneous compressed and pixel
  buffers. Tests use tiny streams and assert neither peak bytes nor overlapping admission.
- **Failure:** concurrent thumbnail/full-review decodes of near-limit owner-null media can occupy
  hundreds of MiB transiently, producing GC stalls or `OutOfMemoryError` for a small visible result.
- **Fix direction:** spool one bounded immutable source to a private seekable file/descriptor, use a
  process-wide byte budget and a smaller thumbnail cap, and test large overlapping retired requests
  plus exact cleanup.

### AGG51-03 — REC discards its immutable admission packet before native setup

- **Severity / confidence:** Medium / High
- **Sources / agreement:** test-engineer and tracer.
- **Evidence:** `CameraEngine.kt:5027-5061` observes accepted session, codec, transfer, FPS, and
  candidates, but `RecordingAdmissionSnapshot` carries only candidates and a session-current
  predicate into `continueRecordingAfterAllocation`. `startRecordingClaimed` then re-reads live
  `videoSize`, `videoFrameRate`, and `transfer` twice at `:5462-5483`; the independently read file
  value configures `VideoRecorder.start` at `:5580-5593`, while the GL value is posted at
  `:5659-5663`. HLG↔S-Log3/LogC3 changes share the HLG10 source-precision class and therefore do
  not invalidate the accepted session. The production snapshot test exits through an injected
  `afterMicrophoneClaim` terminal before this setup branch.
- **Failure:** a same-precision curve change during provider/microphone admission can make GL bake
  HLG while file/container configuration names S-Log3, or vice versa, and the take can publish
  successfully with semantically torn pixels and tags.
- **Fix direction:** carry session, size, frame/capture rate, codec, transfer, and ordered candidates
  as one immutable packet through native setup; derive GL and file transfer from one field and force
  the former interleave through the production recorder/GL seam.

### AGG51-04 — rollback preserves a newer video packet but posts the older transfer to GL

- **Severity / confidence:** Medium / High
- **Sources / agreement:** test-engineer, tracer, critic, verifier, architect, and
  document-specialist.
- **Evidence:** `CameraEngine.rollbackOptics` correctly chooses the newer independently published
  `restoredVideoPipeline` at `CameraEngine.kt:824-834`, then unconditionally calls
  `gl.setTransfer(before.transfer)` at `:835`. The later asynchronous handler command can overwrite
  the winning transfer. Existing rollback tests use Photo/SDR or assert Engine/UI/candidate state
  without observing the live GL sink.
- **Failure:** a Video/HLG optics attempt overlaps a newer S-Log3 or AVC/SDR command, then fails.
  Engine, UI, persistence, and REC preserve the newer packet while the active renderer ends on the
  older curve until a later replay/restart.
- **Fix direction:** post only `restoredVideoPipeline.activeTransfer` (or the selected complete
  packet) and add a newer-before-rollback test spanning Engine, ViewModel, persistence, observable GL,
  and subsequent REC.

### AGG51-05 — review sampling overflows before native decode for extreme positive bounds

- **Severity / confidence:** Low / Medium
- **Source:** security-reviewer.
- **Evidence:** `MediaReview.kt:455-468` evaluates `(longest + sample - 1) / sample` in signed `Int`.
  For a bound near `Int.MAX_VALUE`, the numerator overflows on the second iteration and returns a
  radically undersized sample. The final dimension check occurs only after
  `BitmapFactory.decodeByteArray`; tests stop at 6001 px. Owner-null HEIF/HEIC rows are deliberately
  admitted, though real decoder reachability for such extreme metadata remains manual.
- **Failure:** an adversarial imported image can ask native decode for a raster far beyond the 3000
  px promise and exhaust memory before the defensive post-decode recycle runs.
- **Fix direction:** perform comparison/division in `Long` or an overflow-free quotient/remainder
  form and add `Int.MAX_VALUE` and large-format boundary tests.

### AGG51-06 — PNG validation accepts `tRNS` before a later `PLTE`

- **Severity / confidence:** Low / High
- **Source:** security-reviewer.
- **Evidence:** `tools/check_docs.py:162-174,243-252` validates each truecolor `PLTE` and `tRNS`
  relative to IDAT but never rejects a palette that follows transparency. The production parser
  accepted a CRC-correct `IHDR,tRNS,PLTE,IDAT,IEND` stream even though PNG requires `tRNS` to follow
  `PLTE` when a palette exists. The cycle-50 ancillary-order tests cover post-IDAT cases only.
- **Failure:** malformed store/release screenshot evidence passes after a digest refresh and can
  render differently or fail in stricter consumers.
- **Fix direction:** reject any `PLTE` after `tRNS` and mutation-test the exact legal-relative-order
  violation without traceback.

### AGG51-07 — delayed AppOps classification can latch stale policy truth after replacement Ready

- **Severity / confidence:** Low / Medium
- **Source:** debugger.
- **Evidence:** `CameraEngine.handleActiveCameraFailure` proves failed-controller ownership under the
  monitor at `CameraEngine.kt:3086-3112`, leaves it for an AppOps Binder query, then writes
  `cameraPolicyBlocked=true` at `:3126-3127` without rechecking controller/session/publication
  identity. A replacement Ready clears the latch only at `:704-710`; if it commits before the old
  query returns, the stale write lands afterward and `scheduleCameraRecovery` has no old owner left
  to retire it.
- **Failure:** restored camera access can be followed by stale policy state; a later unrelated HAL
  recovery exhaustion may incorrectly tell the operator camera access is blocked and route them to
  settings.
- **Fix direction:** re-enter the monitor after the Binder query and install the result only while
  the exact failure/controller generation still owns it; identity-gate true/false publications and
  test old-query → replacement-Ready → old-return → unrelated-exhaustion.

### AGG51-08 — the family-deletion integration test races its executor semaphore release

- **Severity / confidence:** Medium / High
- **Sources / agreement:** critic and verifier.
- **Evidence:** `FamilyDeletionMarkerIntegrationRobolectricTest.kt:167-176` counts down its task
  latch before the executor wrapper's `finally` releases capacity, then immediately asserts the
  permit count. The full 2,112-test suite reproduced `expected 0 but was 1`, while the exact test
  passed repeatedly in isolation.
- **Failure:** the authoritative JVM gate is timing-dependent and can fail despite correct product
  behavior, obscuring real regressions and making release evidence non-reproducible.
- **Fix direction:** assert eventual post-release state through a bounded wait or explicit
  post-finally observation seam, never task-body completion alone.

### AGG51-09 — Loupe and video-pipeline ownership prose contradict executable truth

- **Severity / confidence:** Low / High
- **Sources / agreement:** critic, verifier, document-specialist, and designer.
- **Evidence:** comments in `FlipRenderer.kt` and `GlPipeline.kt` describe the current same-stream
  Loupe Overview as upright/right-way-up, while `CLAUDE.md`, `docs/ARCHITECTURE.md`,
  `docs/FIELD_CHECKS.md`, and runtime intentionally show the raw converter-fed inverted field.
  Separately, architecture rollback prose says the baseline packet is restored without documenting
  the independent pipeline-generation rule that preserves a newer publication. The docs gate still
  reports agreement.
- **Failure:** maintainers can reintroduce the superseded orientation rule or overwrite newer
  pipeline intent by following comments presented as rationale.
- **Fix direction:** reserve upright language for a future real wide stream, document conditional
  pipeline ownership and the native REC snapshot contract, and mutation-test stale orientation and
  unconditional-baseline phrases.

## Verified non-findings and limits

- The native Android designer found no additional user-visible regression across accessibility,
  responsive layouts, EN/KO parity, RTL, dark theme, status/error states, and Sony-style policy.
- Focused cycle-50 change tests and all 153 documentation checks passed, but the full JVM run exposed
  AGG51-08. Those greens do not cover the other forced interleavings, GLES faults, large-source heap
  overlap, or extreme decoder metadata described above.
- No device, deploy, Camera2 HAL, GLES fault injection, MediaProvider replacement, HDR display,
  microphone, converter, or physical-input behavior was run or inferred. A3/A4/A5/D1/E1/E2 remain
  manual evidence obligations.

---

# Aggregated deep review — cycle 50

Date: 2026-08-25
Reviewed revision: `2388819d981d32bc3c59b3e81f75fd4f49fab8bd` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`

## Coverage and aggregation

Five parallel specialist groups covered every required role: code-reviewer, performance-reviewer,
security-reviewer, critic, verifier, test-engineer, tracer, architect, debugger,
document-specialist, and native Android designer. No repository-local reviewer definition was
registered. Each group inventoried the complete 535-path revision, examined its full specialist
surface and cross-file interactions, and performed a final missed-issue sweep. Browser automation
was not applicable to this native Jetpack Compose app. Every reviewer returned; there were no agent
failures.

The reports produced 13 raw findings. The video-pipeline supersession race was independently found
by code, trace, and test reviewers; the REC packet-read race and callback-under-lock violation were
each independently found by architecture and documentation reviewers; and the PNG false-green was
independently reproduced by security, critic, and verifier reviewers. Those overlaps are merged at
the highest reported severity/confidence. The deduplicated result is eight findings: one High, two
Medium, and five Low; seven are High confidence and one is Medium confidence.

## Deduplicated findings

### AGG50-01 — an older optics rollback can overwrite a newer Photo-mode video-pipeline command in UI and persistence

- **Severity / confidence:** High / High
- **Sources / agreement:** code-reviewer, tracer, and test-engineer.
- **Evidence:** `CameraEngine.kt:765-843,2511-2556` restores and posts an older complete pipeline
  packet, while a later Photo-mode command takes a no-generation publication branch; the ViewModel
  accepts the queued rollback only by unchanged optics generation at
  `CameraViewModel.kt:911-959`. The existing interleave test at
  `ModeRollbackOwnershipRobolectricTest.kt:52-88` queues the same HEVC/HLG packet that rollback
  restores and never drains/asserts the delayed ViewModel rollback.
- **Failure:** Engine can retain newer AVC/SDR while UI and persisted state are repainted as
  HEVC/HLG; the next Video/REC door can then refuse the visibly selected codec.
- **Fix direction:** add a monotonic pipeline-publication identity independent of Camera2 reopen,
  publish the winning complete packet to the ViewModel, move convenience reads inside the same
  ownership boundary, and add a disjoint-packet interleave through UI persistence and REC admission.

### AGG50-02 — REC admission can observe a hybrid video pipeline because it snapshots the session separately from packet fields

- **Severity / confidence:** Medium / High
- **Sources / agreement:** architect and document-specialist.
- **Evidence:** `CameraEngine.kt:4947-4958` validates the accepted session under the Engine monitor,
  but `:5043-5065` then reads frame rate, caps, size, codec, transfer, and candidates separately
  after unlocking while writers publish the packet under the monitor.
- **Failure:** a concurrent pipeline commit or rollback can pair an old codec with new candidates
  and spuriously reject a valid REC attempt as unavailable.
- **Fix direction:** freeze the accepted session and every REC decision input into one immutable
  snapshot under one monitor section, then test a controlled old/new publication interleave.

### AGG50-03 — production REC wiring is not exercised by rollback tests

- **Severity / confidence:** Medium / High
- **Source:** test-engineer.
- **Evidence:** `ModeRollbackOwnershipRobolectricTest.kt:257-304` reads private fields and directly
  invokes `recordingEncoderAdmission`; `CameraStateTest.kt:215-250` tests the same pure seam. Neither
  executes `CameraEngine.startRecording` / `beginRecordingAllocation` at
  `CameraEngine.kt:5046-5065`, and existing recorder overrides bypass candidate admission.
- **Failure:** future wiring can pass requested rather than accepted transfer, stale candidates, or
  the wrong FPS fact while every pure policy test remains green.
- **Fix direction:** add a narrow allocation injection that preserves production admission and
  exercise public REC after HLG/SDR rollback, including codec/FPS refusals and supersession.

### AGG50-04 — the PNG documentation gate accepts illegal chunk grammar and ancillary ordering

- **Severity / confidence:** Low / High
- **Sources / agreement:** security-reviewer, critic, and verifier.
- **Evidence:** `tools/check_docs.py:111-185` does not require four ASCII-letter chunk bytes or the
  reserved third-byte bit, and accepts chunk-specific illegal placement such as a CRC-correct
  `tRNS` after IDAT. Independent production-predicate mutations returned valid metadata.
- **Failure:** malformed store screenshots can pass the release/docs gate after a digest refresh
  and fail or render differently in stricter consumers.
- **Fix direction:** validate chunk type grammar plus every admitted ancillary chunk's structure,
  multiplicity, and ordering (or reject nonessential ancillary chunks), with focused mutations for
  reserved-bit/non-letter types and late `tRNS`/color-space chunks.

### AGG50-05 — failed shader initialization leaks partially created GL resources across same-context retries

- **Severity / confidence:** Low / High
- **Source:** debugger.
- **Evidence:** `FlipRenderer.kt:114-165,322-344` progressively assigns program/VBO/texture fields;
  compile/link and later setup failures do not delete all local objects, and a retry overwrites the
  only ids that `release()` can reach.
- **Failure:** bounded preview retries can compound leaked shaders, programs, buffers, or textures
  in the still-live EGL context during an already resource-constrained failure.
- **Fix direction:** make initialization transactional with local owners and failure cleanup, publish
  fields only after complete success, and fault-inject every acquisition edge in tests.

### AGG50-06 — owner-null review decoding is not bound to one size-checked file snapshot

- **Severity / confidence:** Low / Medium
- **Source:** security-reviewer; debugger noted the same risk without recounting it.
- **Evidence:** `MediaReview.kt:437-449` opens the URI separately for bounds and pixels, does not
  reject invalid bounds or verify final dimensions, and then opens it again for EXIF orientation.
  Owner-null imported lookalikes are an explicitly supported restoration input.
- **Failure:** a provider returning or accepting changed bytes between opens can bypass the 3000 px
  sampling decision and trigger an unexpectedly large native allocation. Real MediaProvider rewrite
  semantics still require manual validation.
- **Fix direction:** decode from one stable descriptor/private bounded snapshot, reject invalid
  bounds, verify/recycle oversize output, and test a provider that varies content between opens.

### AGG50-07 — Ready invokes `onCameraPolicyBlocked(false)` while the optics monitor is held

- **Severity / confidence:** Low / High
- **Sources / agreement:** architect and document-specialist.
- **Evidence:** `CameraEngine.kt:628-684` states callbacks must run after unlock but calls
  `onCameraPolicyBlocked(false)` inside `OpticsCommitGate.commit`; `:7265-7285` holds the Engine
  monitor through that terminal mutation.
- **Failure:** callback work or re-entry can extend or re-enter a half-published Ready critical
  section, and the live code contradicts the architecture's lock boundary.
- **Fix direction:** capture the policy-unblocked publication inside the mutation and invoke it with
  the other post-commit callbacks; add a re-entrant callback ownership regression.

### AGG50-08 — the completed release-trace plan overclaims a source/variant contract

- **Severity / confidence:** Low / High
- **Source:** test-engineer.
- **Evidence:** `docs/plans/2026-08-25-rpf-cycle49.md:25-30` promises proof that production cannot
  force-unwrap a debug-only payload, but `CameraStateTest.kt:167-213` covers only the pure admission
  matrix; debug unit tests do not execute the release callback caller/consumer at
  `CameraEngine.kt:4170-4183,4723-4751`.
- **Failure:** a caller can re-enable release admission or reintroduce a nullable force-unwrap while
  the claimed regression evidence stays green.
- **Fix direction:** add the promised source/bytecode invariant or executable build-mode callback
  test, and append a dated evidence correction to the completed plan.

## Verified non-findings and limits

- Performance and native Android design reviews found no additional actionable issue.
- The authoritative debug host gate passed in the reviewer that had the complete SDK authority:
  2,103 JVM/Robolectric/Compose tests, lint, 99.82% Partition A, tooling/harness/docs checks, and
  `git diff --check`. Other reviewers' partial environment lacked Emulator `glslangValidator`; this
  is an environment limitation, not a source failure.
- Release tracing, keyboard-repeat suppression, delete-dialog focus restoration, obscured-input
  cancellation, and current AppOps wording otherwise matched their production contracts.
- No device, deploy, MediaProvider replacement, GL fault-injection, camera HAL, converter, HDR,
  microphone, or physical-input behavior was run or inferred. A3/A4/A5/D1/E1/E2 remain manual.

---

# Archived aggregate — cycle 48

Date: 2026-08-25  
Reviewed revision: `ad64188a020000833d653d27e3ae40840868f44a` (`origin/main`)  
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle48.Gvbytf`

## Coverage and aggregation

Five parallel specialist groups covered every required role: code-reviewer, architect,
performance-reviewer, tracer, security-reviewer, debugger, critic, verifier, test-engineer,
document-specialist, and native Android designer. No repository-local `.claude/agents` reviewer was
registered. Each group inventoried all 528 tracked paths, examined its complete specialist surface
and cross-file interactions, and performed a final missed-issue sweep. Browser automation was not
applicable to this native Jetpack Compose app.

The reports produced 12 raw findings. `C48-CA-01` and `CV48-01` are the same transaction-boundary
defect and are merged below at the higher, jointly confirmed High/High signal. The other findings
have distinct causes and fixes. The deduplicated result is therefore 11 findings: one High, five
Medium, and five Low, all High confidence. No reviewer failed, and no device behavior was inferred.

## Findings

### AGG48-01 — failed transfer/codec reconfiguration restores a hybrid video pipeline

- **Severity / confidence:** High / High
- **Sources / agreement:** code-reviewer+architect and critic+verifier.
- **Evidence:** `CameraEngine.kt:476-500,768-818,2473-2504,2862-2873,4996-5006` snapshots and
  restores transfer without the coupled codec/candidate tuple; `OpticsConstraints.kt:22-35` and
  `CameraViewModel.kt:911-958,2351-2367,2869-2888` omit video-pipeline rollback publication.
- **Failure:** a rejected SDR/HLG or HEVC/AVC session transition can leave Engine, GL, recorder
  admission, UI, and persisted settings describing different transfer/codec combinations.
- **Fix direction:** make codec, ordered candidates, requested transfer, and accepted active
  transfer one generation-owned selection with one command, complete rollback publication, and
  ViewModel-level forced-failure/supersession/restore tests.

### AGG48-02 — timelapse settlement tracing remains an unbounded log producer

- **Severity / confidence:** Medium / High
- **Source:** performance-reviewer+tracer.
- **Evidence:** `CameraEngine.kt:4112-4133,4258-4317,4649-4698` gates registration but logs every
  completed callback; `CameraState.kt:957-973` permits unbounded one-second timelapse;
  `CLAUDE.md:1045-1056` records ColorOS's 300-row quota.
- **Failure:** a five-minute timelapse can consume the debug process quota and silently erase later
  startup, frame-gap, focus, error, or teardown evidence.
- **Fix direction:** pass an immutable settlement-trace admission into `photoCallback`, preserve
  ordinary Single and the one-shot in-REC harness path, suppress sequence ticks, and avoid building
  trace-only strings when neither line is admitted.

### AGG48-03 — the shader gate does not verify runtime binding names

- **Severity / confidence:** Medium / High
- **Source:** critic+verifier.
- **Evidence:** `FlipRenderer.kt:67-84` looks up attributes/uniforms without rejecting `-1`, while
  `ShaderProgramCompileTest.kt:13-30,51-57,72-74,104-109` compares a hand-maintained interface set
  only with shader tokens and mutates shader text rather than production lookups.
- **Failure:** a typo/removal in a production binding can leave the compile/link gate green while a
  transform is silently ignored or the preview fails at runtime.
- **Fix direction:** single-source binding literals or mechanically compare the production lookup
  side with the GLSL interface, reject required negative locations, and mutation-test runtime names.

### AGG48-04 — screenshot readiness accepts header-only or truncated PNGs

- **Severity / confidence:** Low / High
- **Source:** critic+verifier.
- **Evidence:** `tools/check_docs.py:110-116,138-162,291-316` validates only the first 33 bytes and a
  manifest digest, not chunk framing/CRC, compressed image data, `IEND`, or successful decode.
- **Failure:** a failed export can pass after the expected digest is refreshed even though Play and
  ordinary image decoders reject the screenshot.
- **Fix direction:** fully decode/verify each PNG with exact geometry and mode, then add truncated
  IDAT, bad CRC, missing IEND, and invalid IHDR-field mutations.

### AGG48-05 — release permission verification rejects AndroidX's private signature guard

- **Severity / confidence:** Medium / High
- **Source:** security-reviewer+debugger.
- **Evidence:** `tools/release_permissions.py:8-25` defines only five runtime/privacy permissions;
  `tools/check_release_artifact.py:721-751` requires exact equality, while the real merged release
  manifest also declares/uses `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` through
  AndroidX Core/Profile Installer (`app/build.gradle.kts:710-719`).
- **Failure:** every real current release bundle fails the mandatory upload permission check despite
  containing only the expected app-private signature permission in addition to the privacy set.
- **Fix direction:** separate user-facing permissions from exact package-internal guards, require
  this guard plus a matching `protectionLevel="signature"` declaration, and test a real-like merged
  manifest/dump without generally ignoring same-package permissions.

### AGG48-06 — full-obscuration flags filter the synthetic gesture cancel

- **Severity / confidence:** Low / High
- **Source:** security-reviewer+debugger.
- **Evidence:** `MainActivity.kt:247-271` copies the obscured event and changes only its action, while
  `MainActivity.kt:351-354` enables DecorView `filterTouchesWhenObscured`; the retained full-
  obscuration bit therefore causes the framework to discard the synthetic cancel.
- **Failure:** a gesture begun cleanly can remain active after a fully obscuring overlay appears;
  the tainted real UP is then also dropped.
- **Fix direction:** reconstruct the cancel with pointer/timing/source identity preserved but both
  obscuration bits cleared, and test partial/full transitions, exactly-one cancel, rejected tail,
  and next-clean-gesture recovery.

### AGG48-07 — closing a full-screen modal does not restore keyboard focus to its opener

- **Severity / confidence:** Medium / High
- **Source:** test-engineer+document-specialist+designer.
- **Evidence:** `CameraScreen.kt:451-458,585-591,1419-1450` disables and later recreates finder focus
  without retaining Menu/Fn/gallery origin; `ModalFocus.kt:8-12` groups/excludes focus only;
  `ModalFocusComposeTest.kt:82-248` tests entry but not close-and-return ownership.
- **Failure:** keyboard, D-pad, or switch users can lose their position after closing Settings, Fn,
  or review.
- **Fix direction:** retain exact origin requesters (or a suitable focus-restorer group), request
  only after modal removal/finder re-enable, and test X, scrim, Back, review/delete-dialog, and My
  Menu transitions.

### AGG48-08 — viewfinder accessibility actions have no hardware-keyboard target

- **Severity / confidence:** Medium / High
- **Source:** test-engineer+document-specialist+designer.
- **Evidence:** `CameraScreen.kt:328-353,724-789` provides TalkBack custom actions and pointer input
  but no focusable/key route; `MediaReview.kt:2035-2051` demonstrates the analogous keyboard seam;
  `ViewfinderAccessibilityComposeTest.kt:97-159` checks semantics only.
- **Failure:** keyboard/D-pad users cannot invoke center focus or reset the held focus point.
- **Fix direction:** add a deliberate focus target and Enter/Space/DPAD-center actions (or equivalent
  named controls), visible focus indication, admission checks, and production Compose tests.

### AGG48-09 — the primary Loupe authority still calls the current inset upright

- **Severity / confidence:** Low / High
- **Source:** test-engineer+document-specialist+designer.
- **Evidence:** `CLAUDE.md:238-251` says “draws UPRIGHT” and “world the right way up,” while
  `CLAUDE.md:21-26`, `docs/ARCHITECTURE.md:32-37`, and `docs/FIELD_CHECKS.md:180-197` correctly state
  that today's same converter-fed stream is raw/inverted relative to the corrected main view.
- **Failure:** a maintainer can treat correct inverted behavior as a regression and re-add the
  afocal term, undoing the tested per-draw contract.
- **Fix direction:** retitle/reword the authority around omission of the afocal term and make the
  docs gate reject unqualified current-upright claims.

### AGG48-10 — gallery thumbnail coverage omits the video-ready branch

- **Severity / confidence:** Low / High
- **Source:** test-engineer+document-specialist+designer.
- **Evidence:** `GalleryThumbComposeTest.kt:51-111` covers video loading/failed and RAW/still ready,
  but not `Ready(VIDEO, bitmap)`; `MediaReview.kt:805-850` has media-specific ready paint/copy; the
  completed cycle-47 plan claims every media-kind branch at `docs/plans/2026-08-25-rpf-cycle47.md:65-70`.
- **Failure:** video-ready copy or paint can regress while the exhaustive-looking surface test and
  completion record stay green.
- **Fix direction:** add video-ready pixels/copy/state-description and disabled EN/KO variants, and
  append a dated evidence correction to the completed plan.

### AGG48-11 — obscured-gesture closeout overstates production Compose evidence

- **Severity / confidence:** Low / High
- **Source:** test-engineer+document-specialist+designer.
- **Evidence:** `MainActivityTouchDispatchTest.kt:67-121` uses a generic `View` and one pointer,
  whereas cycle-47's plan claims production Compose tap/drag/pinch coverage; no test drives the real
  gesture loops in `CameraScreen.kt:742-790` or `ProControls.kt:490-574` through obscuration.
- **Failure:** Activity dispatch can pass while Compose cleanup remains stuck for a ruler drag or
  two-pointer pinch, contradicting the completion record.
- **Fix direction:** exercise real Activity+Compose tap, ruler drag, and pinch under both
  obscuration flags, assert exactly-once cleanup and next clean input, then append a dated plan
  correction.

## AGENT FAILURES

None.

## Provenance

- `.context/reviews/code-reviewer-architect-cycle48.md`
- `.context/reviews/perf-reviewer-tracer-cycle48.md`
- `.context/reviews/security-reviewer-debugger-cycle48.md`
- `.context/reviews/critic-verifier-cycle48.md`
- `.context/reviews/test-engineer-document-specialist-designer-cycle48.md`

---

# Archived prior aggregate — cycle 47 (resolved before cycle 48)

Date: 2026-08-25
Reviewed revision: `5d13d85a305f0acf4bb35d6ca0a01490d6971dd9` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle47.3oSGJA`

## Coverage and aggregation

Five parallel specialist groups covered every required role: code-reviewer, architect,
performance-reviewer, tracer, security-reviewer, debugger, critic, verifier, test-engineer,
document-specialist, and native Android designer. No repository-local reviewer agents were
registered. Each group inventoried all 525 tracked paths, examined its complete specialist surface
and cross-file interactions, and performed a final missed-issue sweep. Browser automation was not
applicable to this native Jetpack Compose app. No device behavior was run or inferred.

The specialists produced ten raw findings. Final source-level comparison found no duplicates: the
transaction, logging, microphone, input-stream, ADB, screenshot, accessibility, GLSL, packaged-
permission, and localization-contract findings have distinct causes and fixes. All ten remain as
deduplicated current findings. No finding was independently reported by more than one group, and no
agent failed.

## Findings

### AGG47-01 — transfer remains outside the complete optics transaction

- **Severity / confidence:** High / High
- **Source:** code-reviewer/architect
- **Evidence:** `CameraViewModel.kt:2281-2336,1381-1420` queues mode/optics before transfer;
  `CameraEngine.kt:2172-2235,2315-2379,2461-2487,2892-2934` mutates transfer before the direct
  reopen baseline is captured and can queue a second reopen.
- **Failure:** Photo→non-SDR Video deterministically tears down/reopens twice, while a failed direct
  SDR↔HLG reopen restores Ready against the post-mutation transfer instead of accepted session
  truth, allowing Camera2 source, GL mapping, and recorder claims to diverge.
- **Plan direction:** include normalized target and accepted-before transfer in one immutable
  generation-owned optics packet, coalesce to one reconfiguration, and add duplicate-reopen and
  forced-failure ownership tests.

### AGG47-02 — capture-registration tracing is an unbounded timelapse log source

- **Severity / confidence:** Medium / High
- **Source:** performance-reviewer/tracer
- **Evidence:** `CameraEngine.kt:4652-4677` emits two DEBUG lines per completed shot;
  `CameraEngine.kt:4241-4308` and `CameraState.kt:957-965` allow unbounded one-second timelapse;
  `CLAUDE.md:1045-1056` records ColorOS's 300-row process quota.
- **Failure:** at most 150 completed ticks consume the quota before other diagnostics, while the
  new registration consumer (`device-tests/cases.py:4751-4778`) accepts Single drive only.
- **Plan direction:** gate registration to the exact Single/one-shot diagnostic owner and cover
  drive-mode admission/consumption with host tests.

### AGG47-03 — standby microphone remains active behind media-permission UI

- **Severity / confidence:** Medium / High
- **Source:** security-reviewer/debugger
- **Evidence:** `MainActivity.kt:497-526,366-377` owns the external permission surface;
  `CameraViewModel.kt:3502-3512,1623-1635,4214-4220` does not include aggregate input obscuration in
  standby-audio admission; `CameraScreen.kt:451-465` sees only Compose modals.
- **Failure:** opening Android's media-permission UI can hide the detailed meter while its
  `AudioRecord` continues, contradicting the visible/unobscured architecture and privacy contract.
- **Plan direction:** include aggregate input ownership in standby admission, refresh atomically on
  every owner edge, and test exact release/reacquisition around the external launcher.

### AGG47-04 — obscuration during a gesture does not cancel the accepted touch stream

- **Severity / confidence:** Low / High
- **Source:** security-reviewer/debugger
- **Evidence:** `MainActivity.kt:237-245` rejects an obscured MOVE/UP without sending
  `ACTION_CANCEL`; gesture loops at `CameraScreen.kt:742-790,915-923` and
  `ProControls.kt:534-574` wait for a terminal pointer edge.
- **Failure:** an overlay appearing after clean DOWN leaves Compose's child gesture stuck, so the
  next clean tap/drag can be suppressed or treated as continuation.
- **Plan direction:** taint the stream, synthesize exactly one cancel to the accepted target, reject
  the remainder until a fresh clean DOWN, and add real-Activity transition tests.

### AGG47-05 — ADB reconnect retries overmatch and report stale errors

- **Severity / confidence:** Low / High
- **Source:** security-reviewer/debugger
- **Evidence:** `device-tests/dtest/adb.py:252-271` treats any stderr containing `not found` as a
  transport failure, reconnects any serial form, and raises with pre-retry stderr.
- **Failure:** a remote missing command triggers a bogus reconnect/retry, while
  offline→unauthorized reports the obsolete offline diagnosis.
- **Plan direction:** match canonical host transport errors only, reconnect endpoint-capable
  serials, refresh failure evidence after each attempt, and add table-driven retry tests.

### AGG47-06 — screenshot readiness trusts declared geometry instead of PNG bytes

- **Severity / confidence:** Low / High
- **Source:** critic/verifier/designer
- **Evidence:** `tools/check_docs.py:113-132,152-215,229-265` checks hashes and declared dimensions
  but never parses PNG IHDR; `tools/tests/test_tool_contracts.py:1014-1124` has no wrong-geometry
  mutation.
- **Failure:** a wrong-sized or wrong-orientation recapture passes after its digest is updated while
  stale declared geometry remains accepted.
- **Plan direction:** validate PNG IHDR and closed orientation/device/locale schema, with valid
  wrong-geometry mutation fixtures.

### AGG47-07 — TalkBack cannot distinguish thumbnail loading, ready, and failed states

- **Severity / confidence:** Medium / High
- **Source:** critic/verifier/designer
- **Evidence:** `MediaReview.kt:654-682,828-843` models and paints distinct states, while
  `MediaReview.kt:805-825` exports only the stable media action name; `GalleryThumbComposeTest.kt:48-89`
  expects identical descriptions.
- **Failure:** assistive users cannot know whether to wait, retry/open, or expect normal review.
- **Plan direction:** add localized EN/KO state descriptions and test action plus state for every
  thumbnail branch.

### AGG47-08 — the host gate never compiles or links the shipping GLSL program

- **Severity / confidence:** Medium / High
- **Source:** test-engineer/document-specialist
- **Evidence:** `FlipRenderer.kt:67-85,271-293` compiles only at runtime; `Shaders.kt:26-39` holds
  the sources; shader unit tests inspect substrings; `tools/verify_host.py:72-87` packages but does
  not execute the instrumentation path.
- **Failure:** syntax, interface, or required-uniform regressions can pass the authoritative host
  gate and fail first launch before preview Ready.
- **Plan direction:** add a pinned GLES-compatible whole-program/interface validator to the host
  gate, or establish a bounded executed GL-init release gate, with syntax/link/interface mutations.

### AGG47-09 — privacy checks are not joined to packaged release permissions

- **Severity / confidence:** Medium / High
- **Source:** test-engineer/document-specialist
- **Evidence:** `tools/check_docs.py:425-451` infers from the source manifest; removal directives are
  at `app/src/main/AndroidManifest.xml:18-22`; `tools/check_release_artifact.py:709-748` dumps the
  packaged manifest but ignores its permission set.
- **Failure:** a variant/dependency/merge regression can add an undisclosed permission, including
  network access, while source docs, signing, and upload validation all remain green.
- **Plan direction:** enforce one closed documented permission set against bundletool's packaged
  manifest dump and add extra/reintroduced-network/expected-set tests.

### AGG47-10 — translation-exception allow-list testing is not closed

- **Severity / confidence:** Low / High
- **Source:** test-engineer/document-specialist
- **Evidence:** `CLAUDE.md:43-55` permits narrow `translatable="false"` exceptions;
  `tools/check_docs.py:134-143` and `KoreanLocalizationRobolectricTest.kt:33-135` never validate the
  complete XML exception-name set.
- **Failure:** ordinary English prose can be marked non-translatable and silently ship unchanged in
  Korean while all current gates pass.
- **Plan direction:** enforce a closed XML resource-name exception set and mutate ordinary prose or
  an unapproved default-only resource in tool tests.

## Agent failures

None.

## Totals

- Raw specialist findings: 10
- Deduplicated new findings: 10
- Severity: 1 High, 5 Medium, 4 Low
- Confidence: 10 High
- Cross-agent duplicates/agreement: none
- Device/manual evidence was not inferred from host behavior.

---

# Aggregate review — cycle 49

Date: 2026-08-25
Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27` (`origin/main`)

## Coverage and aggregation

Five parallel reviewer workers covered every required specialist role: code-reviewer, perf-reviewer,
security-reviewer, critic, verifier, test-engineer, tracer, architect, debugger,
document-specialist, and native Android designer. Each inventoried the complete 534-path repository,
read the committed authorities, examined relevant files and cross-file interactions, and performed a
final missed-issues sweep. The designer reviewed the native Jetpack Compose UI through production
semantics/layout code and tests; browser automation is not applicable to this native app. All workers
returned successfully, so there are no agent failures.

Overlapping reports of the release trace failure were merged into one High/High finding. Keyboard
repeat reports were merged into one Medium/High finding. Delete-dialog focus ownership and its
cycle-48 evidence overclaim were merged into one Medium/High finding. The remaining candidates are
independent. Previously fixed or explicitly deferred historical items were excluded.

## Deduplicated findings

### C49-01 — release Single stills dereference a debug-only trace payload

- **Severity / confidence:** High / High
- **Agreement:** perf-reviewer, tracer, security/debugger, verifier, and architect.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:971-986`
  admits registration/settlement for ordinary Single and settlement for in-recording snapshots in
  every build. `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4713-4725`
  creates `traceText` only when `BuildConfig.DEBUG`, but `:4727-4743` consumes the build-independent
  admission and force-unwraps that nullable payload.
- **Failure scenario:** in release, ordinary Single callback construction throws after registering
  the family but before Camera2 dispatch, producing `PHOTO_CAPTURE_FAILED` and leaking the family
  producer lease. An in-recording snapshot can throw at settlement before terminal cleanup.
- **Fix:** make trace admission build-aware and nullable-safe, keep capture cleanup independent of
  diagnostics, and add a pure debug/release behavior matrix plus release-source contract coverage.

### C49-02 — `setVideoPipeline` can race optics rollback into Photo + active HLG

- **Severity / confidence:** Medium / High
- **Source:** tracer.
- **Evidence:** rollback restores the pipeline packet under the Engine monitor at
  `CameraEngine.kt:764-793`, while `setVideoPipeline` derives `activeTransfer` and
  `tenBitChanged` from volatile state before acquiring that monitor at `:2511-2549`.
- **Failure scenario:** a command derives HLG against desired Video, setup rolls back to Photo/SDR,
  then the stale no-generation branch publishes HLG without reopen, leaving an SDR Photo session
  with HLG Engine/GL truth.
- **Fix:** derive and publish the decision from current state under one monitor, with generation
  ownership when the boundary changes, and add a deterministic rollback/pipeline race regression.

### C49-03 — screenshot PNG validation accepts illegal palettes and can throw on bounded overrun

- **Severity / confidence:** Low / High
- **Source:** security-reviewer; independently noted by debugger.
- **Evidence:** `tools/check_docs.py:111-196` permits `PLTE` after `IDAT`, permits it for color type
  6, and does not validate its cardinality. Exactly one decoded byte beyond the declared raster can
  call `decompressobj.flush(0)`, raising uncaught `ValueError`.
- **Failure scenario:** a malformed checked-in Play screenshot can either pass a claimed structural
  check or abort the docs gate with a traceback instead of a bounded validation failure.
- **Fix:** enforce PNG critical-chunk ordering and PLTE rules, make decompression a total predicate,
  and add malformed-palette and one-byte-overrun fixtures.

### C49-04 — held viewfinder activation keys repeatedly restart autofocus

- **Severity / confidence:** Medium / High
- **Agreement:** code-reviewer, critic, and designer.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:364-385` fires center
  focus for every matching `KeyDown` without filtering `repeatCount`; current tests only send
  discrete press pairs.
- **Failure scenario:** holding Enter, Space, Numpad Enter, or DPAD-center reissues AF at hardware
  repeat cadence instead of behaving like one button activation.
- **Fix:** admit only the initial DOWN or own one activation through UP, and test repeat events plus
  a fresh second press.

### C49-05 — Delete-dialog dismissal has no explicit focus-return owner

- **Severity / confidence:** Medium / High
- **Agreement:** critic, document-specialist, and designer. Runtime landing remains
  platform-dependent; the missing owner and evidence overclaim are confirmed.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:1053-1061,1727-1771`
  has no Delete `FocusRequester`; dismiss only clears `confirmDelete`. The test at
  `ModalFocusComposeTest.kt:218-253` asserts disappearance, not restored focus, despite the completed
  cycle-48 plan claiming cancel coverage.
- **Failure scenario:** a keyboard/D-pad user cancels Delete and can lose the review position or
  land on another control.
- **Fix:** restore focus to the Delete origin after every dismissal path, assert it, and append a
  dated correction to the completed cycle-48 plan.

### C49-06 — Play submission history calls the fixed AppOps disclosure an open gap

- **Severity / confidence:** Low / High
- **Source:** document-specialist.
- **Evidence:** `docs/play-console-submit.md:386-392` says the app says nothing and labels this an
  open UX gap, while `:280-287` and production resources/handling document the shipped blocked-camera
  status plus Settings action.
- **Failure scenario:** maintainers or release reviewers treat fixed behavior as current missing
  work and misreport the artifact.
- **Fix:** label the paragraph as a historical pre-fix observation, point to the current fix, and
  add a docs invariant against the active phrase.

### C49-07 — obscured-gesture cancellation tests can pass with a missing terminal edge

- **Severity / confidence:** Medium / High
- **Source:** test-engineer.
- **Evidence:** `MainActivityTouchDispatchTest.kt:194-249` checks pinch termination only after a
  later clean pinch, so that later gesture can satisfy the count; the slider sibling at `:287-302`
  does not exclude a hostile cancel-coordinate value.
- **Failure scenario:** a regression leaves the canceled pinch owner live or lands an obscured
  slider coordinate while the test suite stays green.
- **Fix:** assert the exact canceled-stream trace before recovery, then independently assert the
  clean gesture.

### C49-08 — rollback REC-admission coverage duplicates production policy

- **Severity / confidence:** Medium / High
- **Source:** test-engineer.
- **Evidence:** `ModeRollbackOwnershipRobolectricTest.kt:202-241` reads private candidates and calls
  `encoderSelectionAdmitsTransfer` itself; it never reaches `beginRecordingAllocation`'s production
  admission branch at `CameraEngine.kt:5037-5057`, despite the cycle-48 plan's completion claim.
- **Failure scenario:** production REC admission can later filter stale/wrong state while the
  duplicated predicate test remains green.
- **Fix:** extract one Android-free production admission decision seam and make both runtime and
  rollback tests call that exact seam, covering HLG and SDR rollback packets.

## AGENT FAILURES

None.

## Final sweep

No further current finding survived source verification or deduplication. Existing manual checks
A3/A4/A5/D1/E1/E2 remain explicit device/scene/provider evidence gaps, not host-proven defects. The
previously recorded broad `CameraEngine` decomposition remains deferred debt and is not duplicated.

---
