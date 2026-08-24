# Aggregated deep review — cycle 47

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
