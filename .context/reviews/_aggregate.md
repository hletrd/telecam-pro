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
