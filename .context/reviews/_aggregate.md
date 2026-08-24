# Cycle 30 aggregate review

Date: 2026-08-24
Reviewed revision: `3abf6221d42b7275502cfd5872963aa83c18d80f`
Workspace: clean isolated clone `/tmp/find-x9-ultra-cycle30.vpagVN/repo`

## Review coverage

All required perspectives returned: code-reviewer, architect, perf-reviewer, tracer,
security-reviewer, critic, verifier, debugger, test-engineer, document-specialist, and native
Android designer. The reviewers inventoried all 427 tracked files and examined the complete
Camera2/GL, capture/storage/deletion, ViewModel/UI, audio, settings/MR, documentation, build,
release, privacy, and test surfaces. The designer pass used Compose/resource/Robolectric evidence;
browser automation was not applicable to this native non-web UI.

## Deduplicated findings

### AGG30-01 — device transfer can replay destructive MediaStore URIs on another device

- **Severity / confidence:** High / High
- **Agreement:** security-reviewer and critic.
- **Evidence:** `app/src/main/res/xml/data_extraction_rules.xml:2-8` excludes only shared
  preferences, while `PendingDiscardJournal.kt:9-46,215-245` stores exact MediaStore URIs in a
  database and `MediaStoreWriter.kt:1133-1157` replays every restored row through deletion at
  launch. Android 12+ OEM device-to-device transfer may still run when `allowBackup=false`.
- **Failure:** a source URI such as `content://media/.../42` can name a different row after device
  migration; destination launch recovery can attempt to delete media never marked on that device.
- **Fix:** exclude database/private state from both cloud and D2D schemas (and legacy backup rules),
  enforce the policy with a parsed-resource host contract, and retain defense-in-depth provenance.

### AGG30-02 — retirement-lane overflow drops the only in-process retry

- **Severity / confidence:** Medium / High
- **Agreement:** perf-reviewer and tracer; code-reviewer/architect agreed bounded dispatch is safe
  for data integrity but did not classify its in-process liveness consequence as actionable.
- **Evidence:** `CameraEngine.kt:4179-4209` emits the sole terminal retirement continuation;
  `RetainedStillDiscardDispatcher.kt:95-101` drops `OVERFLOW`; `MediaStoreWriter.kt:1062-1128`
  intentionally retains markers owned by the current process, and the journal is capped at 64.
- **Failure:** repeated saturation strands current-process markers until restart and can eventually
  make ordinary family deletion fail closed with `CAPACITY_EXHAUSTED`.
- **Fix:** add a bounded/conflated retry owner re-armed after worker completion and prove eventual
  retirement after saturation without inline provider work or unbounded admission.

### AGG30-03 — ViewModel deletion work still has an unbounded provider queue

- **Severity / confidence:** Medium / High
- **Agreement:** perf-reviewer and tracer.
- **Evidence:** `CameraViewModel.kt:637-643` creates an unbounded single-thread executor;
  whole-family deletes at `:3315-3391` and rejected late-sibling discards at `:3395-3432` enqueue
  provider work without a capacity or conflation owner, and shutdown drains accepted tasks.
- **Failure:** one wedged Binder call permits unbounded closure and obsolete ViewModel retention
  across continued captures/deletes and ViewModel replacement.
- **Fix:** use process-finite provider capacity with durable exact-family continuation and truthful
  overflow status; test the active-plus-backlog ceiling and lack of inline work.

### AGG30-04 — unresolved untracked sibling deletion is falsely reported as complete

- **Severity / confidence:** Medium / High
- **Agreement:** verifier and debugger.
- **Evidence:** `MediaStoreWriter.kt:812-843` collapses query failure, undeletable rows, and an empty
  sweep into an integer; `CameraViewModel.kt:3356-3367` discards the count and builds survivors only
  from tracker-known outputs, then reports `DELETED` at `:3385-3388` when that narrower set is empty.
- **Failure:** an old Engine's newly published but untracked JPEG can survive provider rejection
  while the app says “Deleted.”
- **Fix:** return a typed sweep result, merge unresolved/query-failed state into the terminal
  outcome, keep Gallery retry copy and the durable marker, and cover all provider outcomes.

### AGG30-05 — recalled phone identity and Engine host focal come from different packets

- **Severity / confidence:** Medium / High
- **Agreement:** verifier and debugger.
- **Evidence:** `CameraViewModel.kt:1191-1199` derives the recalled converter from the loaded phone,
  but `:1262-1265` sends the outgoing state's host focal to the Engine before `:1338-1358` publishes
  the recalled phone. `CameraEngine.kt:4325-4329,6614` then uses the mismatched host for shot EXIF.
- **Failure:** recalling a 70 mm OPPO bank while the outgoing declaration is an 85 mm body can show
  300 mm in UI but save approximately 364 mm in metadata.
- **Fix:** make phone/converter/host one atomic recall packet and add a same-route cross-phone
  regression asserting UI, Engine, memory row, and shot metadata agree.

### AGG30-06 — asynchronous optics rollback cannot restore converter and host focal

- **Severity / confidence:** Medium / High
- **Agreement:** verifier and debugger.
- **Evidence:** `CameraViewModel.kt:1262-1279` applies converter state before the generation-owned
  optics transaction, while `CameraEngine.kt:392-413,457-470,657-715` snapshots/rolls back neither
  converter magnification nor host focal and the ViewModel rollback reducer restores no declaration.
- **Failure:** an asynchronously rejected MR recall can restore lens/controls but leave rejected
  converter, zoom/finder geometry, UI declaration, and later EXIF behind.
- **Fix:** include the complete phone/converter/host packet in transaction snapshots and terminal
  rollback publication (or delay commit), with synchronous, async, and supersession regressions.

### AGG30-07 — responsive settings rows can hide their trailing action at 200% font

- **Severity / confidence:** Medium / High
- **Agreement:** test-engineer, document-specialist, and designer.
- **Evidence:** `ProControls.kt:667-700` lays out two unconstrained unweighted Text children in a
  `SpaceBetween` row. At the tested 212 dp content lane, long Korean labels such as the Privacy row
  at `ProSheet.kt:1593-1604` can consume the trailing `보기` action.
- **Failure:** at 320 dp split-screen and 2x font, a tappable navigation row appears inert because
  its value/action affordance is clipped.
- **Fix:** reserve trailing width or stack responsively; add EN/KO compact 2x-font bounds and click
  tests for action, dynamic-value, and disabled rows.

### AGG30-08 — compact permission recovery can clip the only in-app fallback

- **Severity / confidence:** Medium / High
- **Agreement:** test-engineer, document-specialist, and designer.
- **Evidence:** `MainActivity.kt:837-906` vertically centers a non-scrollable permission Column;
  `ExternalNavigationUi.kt:24-53` appends a wrapping error and recovery action below the existing
  message, actions, and spacers. No compact/font-scale test constrains reachability.
- **Failure:** in a 320x340 dp, 2x-font window, browser failure can put `View in app` below the
  viewport precisely when it is the only working privacy route.
- **Fix:** make the bounded permission content vertically scrollable or adapt its arrangement, and
  test EN/KO failure recovery at compact dimensions and 2x font.

### AGG30-09 — the authoritative host gate does not compile androidTest sources

- **Severity / confidence:** Low / High
- **Agreement:** test-engineer and document-specialist.
- **Evidence:** `tools/verify_host.py:58-72` runs debug assembly, JVM tests, lint, and coverage but
  not `:app:assembleDebugAndroidTest`; the four tracked smoke/probe classes compile only when the
  extra task is requested. The explicit task passed on the reviewed revision.
- **Failure:** instrumented smoke call sites can bit-rot while every required host gate stays green.
- **Fix:** add Android-test assembly to the host gate, pin it in the consolidated-gate test, and
  document that this proves source-set compilation rather than device behavior.

## Agent failures

None. Every spawned reviewer returned and wrote its provenance report.

## Final sweep result

No additional actionable security, correctness, performance, architecture, documentation, or
UI/UX finding survived evidence checking. No physical-device behavior was inferred from host tests.
