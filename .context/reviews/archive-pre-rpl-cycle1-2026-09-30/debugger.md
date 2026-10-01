# Cycle 51 debugger review

Date: 2026-08-25
Reviewed revision: `7eb4ee95` (`origin/main` at review start)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle51.WTu2dW`

## Authority, complete inventory, and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely, then inventoried all
538 tracked regular files: 103 production Kotlin/Java files, three debug Kotlin files, four
instrumented-test files, 239 JVM/Robolectric/Compose tests, 22 host-tool files, 13 device-harness
Python files, 95 Markdown files, and 59 build/resource/asset/license/configuration inputs. There are
531 mode-100644 and seven mode-100755 entries, with no symlink, submodule, FIFO, socket, or device.

The direct debugger pass traced route inventory and Camera2 open/session generations; fallback
configuration, pseudo-ZSL correlation and capture watchdogs; still snapshots, family leases,
MediaStore publication/recovery/deletion; GL/EGL outputs and shader initialization; MediaCodec,
MediaMuxer, AudioRecord, recording allocation/storage, and quarantine; Activity/ViewModel/Compose
lifecycle; settings/MR rollback; obscured/hardware input; bounded workers/timers; immutable build and
release tools; and the complete cycle-50 delta. Every tracked file participated in final exception,
suppression, stale-callback, partial-construction, timeout, queue-rejection, dangerous-API, parser,
and resource-ownership searches. Tests and comments were compared to production control flow rather
than accepted as proof.

## Finding

### DBG51-01 — delayed AppOps classification can latch policy truth onto a replacement session

- **Severity / confidence:** Low / Medium.
- **Classification:** Confirmed ownership gap with a likely concurrency/status regression; a
  deterministic interleave test and device timing remain to validate the user-visible outcome.
- **Evidence:** `CameraEngine.handleActiveCameraFailure` proves the failed controller owns the Engine
  only inside the synchronized section at `CameraEngine.kt:3086-3112`. It then leaves that ownership
  boundary, performs the AppOps Binder query, and writes `cameraPolicyBlocked = true` at lines
  3126-3127 without rechecking controller identity, session generation, or accepted-session state.
  A successful replacement session clears the same latch only during `commitOpticsReady` at lines
  704-710. Therefore a delayed classification can land *after* that newer Ready commit and recreate
  stale policy state. `scheduleCameraRecovery` immediately returns when the failed controller has
  been replaced (`CameraEngine.kt:3163-3166`), so this stale write has no owner that will retire it;
  a later unrelated recovery exhaustion consumes the latch at lines 3170-3174 and can publish the
  policy-block UI. Existing tests cover the exception/AppOps value tables and the cycle-50
  post-monitor unblock callback, but none interleaves a delayed policy query with replacement Ready.
- **Concrete failure scenario:** A policy-blocked failure begins classification, the operator restores
  camera access, and recovery installs a new Ready session before the old AppOps call returns. The old
  result then writes `cameraPolicyBlocked=true` after Ready. If a later ordinary HAL failure exhausts
  recovery, the app can surface “camera blocked for this app” and route the operator to permissions
  even though the current failure was not a policy block and access had already been restored.
- **Suggested fix:** Capture the failed controller plus failure/session generation, run the AppOps
  query outside the monitor, then re-enter the Engine monitor and install the latch only if that
  failure owner is still current and no replacement accepted session has committed. Give policy
  publications a monotonic identity (or fold them into camera-ready publication identity) so delayed
  true/false callbacks are rejected after a newer terminal. Add a latch-controlled test for old
  failure query -> replacement Ready -> old query return -> unrelated exhaustion.

## Cross-role risks

The security report records two separate findings not counted again here: the new review sample-size
ceiling division overflows for extreme positive dimensions before native decode, and the PNG gate
accepts `tRNS` before a later `PLTE` despite its ancillary-order test name.

## Validation evidence and limitations

- `python3 tools/check_docs.py` passed 153 checks with 24 optional-private skips;
  `python3 -m compileall -q tools device-tests`, `git diff --check`, and repository integrity/mode
  sweeps passed.
- Cycle 50's exact debug, release, and authoritative host gates were green at this reviewed revision.
  I did not run Camera2, EGL/codec fault injection, a delayed AppOps Binder seam, a real
  MediaProvider, device deployment, or production signing in this review.
- The six open physical checks A3, A4, A5, D1, E1, and E2 remain evidence obligations, not inferred
  failures.

## Final missed-issue and file-coverage sweep

The final sweep rechecked every cycle-50 change (pipeline publication identity, atomic REC snapshot,
transactional renderer initialization, bounded review snapshot, PNG parser, policy callback move,
and release trace), then revisited every Camera2/GL/codec/audio terminal, provider and family owner,
stale generation, partial native construction, queue/scheduler rejection, timeout, lifecycle edge,
release/debug branch, and false-green test boundary. Prior resolved findings and field-only evidence
were not re-filed. No additional debugger defect survived source validation.

**New debugger finding count: 1 — Low severity, Medium confidence; the ownership gap is confirmed and
the user-visible interleave requires deterministic/device validation.**
