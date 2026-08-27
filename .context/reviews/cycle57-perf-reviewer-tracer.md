# Cycle 57 performance-reviewer and tracer review

Date: 2026-08-27  
Reviewed revision: `b44d5fce43b9a4910143133b6e6e280559704763` (`origin/main`)  
Workspace: isolated clean clone
`/var/folders/kz/t1c9x6qj5zgb2sg_4lv0nh900000gn/T/find-x9-ultra-cycle57.XXXXXX.yRT92pSLwp/repo`

## Authority, inventory, and method

I read `CLAUDE.md` completely first, then the complete current authorities in
`docs/ARCHITECTURE.md` and `docs/FIELD_CHECKS.md`. I checked the current and recent review/plan
history before filing anything so the closed cycle-53 through cycle-56 DNG/provider, process-budget,
and startup-owner defects were not recycled.

The repository inventory at the reviewed HEAD contains 575 tracked paths: all 106 production
Kotlin/Java modules (37 camera, 11 GL, seven video, five capture, five storage, 31 UI, and the ten
remaining app-root/focus/stabilization modules), 248 JVM/Robolectric/Compose tests, four instrumented
tests, 37 Python/shell tool and device-harness sources, 118 Markdown files, and all build, manifest,
resource, font, image, provenance, license, and privacy inputs. I reconciled that inventory against
the architecture module map and ran repository-wide searches over every production source for
thread/executor creation, handlers and delayed work, monitors/atomics/semaphores/latches, blocking
waits and sleeps, native/provider calls, frame/tick loops, logging, image/bitmap/buffer ownership,
queue saturation, retry/deadline terminals, and lifecycle release. Comments and passing tests were
treated as hypotheses, not execution truth.

## Performance-reviewer provenance

The performance pass covered every producer/consumer lane: Camera2 callbacks and ImageReaders;
setup, capture, REC, teardown, retry, and recovery executors; GL frame coalescing, cached redraws,
EGL outputs, readback and analysis; processed/RAW/video byte and publication ownership; standby and
recording audio; MediaStore identity/deletion/recovery work; review decoding/player setup; ViewModel
tickers, zoom throttles, StateFlow publications, and teardown; and the host/device evidence tools.
It re-traced all cycle-56 source changes, including process admission subscriptions, pending-identity
backoff, completed-DNG process dispatch, diagnostic budgeting, and Engine-owned StartupTrace.

### C57-PT-01 — repeated real frame gaps can still exhaust the supposedly reserved ColorOS log capacity

- **Severity / confidence / classification:** Medium / High / confirmed unbounded diagnostic
  producer; the exact device drop point under a failing route is manual/fault-injection validation.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt:919-936` emits one bare `Log.i`
    for every draw interval over 200 ms. It has neither a change gate, accumulator, cadence gate,
    nor `recurringDiagnosticAllowed` admission.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/DiagnosticTelemetry.kt:7-39` reserves only 180
    rows for repeatable diagnostics specifically so frame-gap/recovery/fault evidence can survive
    under the measured 300-row process ceiling; the FrameGap producer does not consume or obey that
    bound.
  - `tools/check_docs.py:75-79,104-140` explicitly exempts the `FrameGap:` anchor as
    `reserved_fault`, so the executable classification gate passes without proving the producer is
    finite. `python3 tools/check_docs.py` passed all 158 checks at this HEAD, demonstrating that the
    current green contract accepts the bypass.
  - `docs/FIELD_CHECKS.md:106-125` requires a ten-minute A5 soak and preservation of every
    `FrameGap >= 200 ms` plus later camera errors and recovery evidence. `CLAUDE.md:1076-1090`
    records that ColorOS silently drops process logs after 300 rows and that losing later evidence
    is precisely the failure the diagnostic policy exists to prevent.
- **Concrete failure scenario:** a broken front pseudo-ZSL route degrades to four real frames per
  second, producing intervals around 250 ms. The current line emits roughly 2,400 FrameGap rows over
  the required ten-minute A5 soak and reaches the complete ColorOS quota in about 75 seconds even
  before startup/session/3A rows. Later camera errors, recovery transitions, terminal cadence
  summary, and the post-soak capture trace are then silently dropped. Even one qualifying gap per
  second exceeds the entire process quota within the soak.
- **Competing hypotheses checked:** raising the old threshold from 50 to 200 ms correctly avoids
  logging the designed 15 fps dark cadence, but it does not bound a genuinely defective cadence.
  Calling the row a reserved fault class partitions intent only; there is no separate OS quota or
  runtime reserve behind that name. Error rarity is not a valid assumption for a frame-by-frame
  health predicate whose field check explicitly investigates recurring stalls.
- **Suggested fix:** give FrameGap a bounded producer of its own: log a small first-occurrence and
  change/bucket sample through the shared process budget, accumulate count/max/distribution in
  constant memory, and emit one terminal/periodic summary at a cadence that provably preserves the
  120-row fault reserve. Extend the executable ten-minute composition test and documentation
  classifier so reintroducing a bare per-gap `Log.i` fails. Adjust A5 to consume the bounded summary
  rather than promising an impossible unlimited list under the known platform quota.

## Tracer provenance

The causal pass followed requested zoom from Compose input through
`CameraViewModel.applyZoomRatio`/`flushZoom`, its 16 ms newest-value coalescing, Engine zoom truth,
`GlPipeline.setZoomTarget`, cached-frame redraw, the Camera2 frame-notification coalescer, and the
next producer-fed draw. It separately followed the same timestamp through preview-loss and GL
generation cleanup. Competing hypotheses included a hidden producer timestamp, an updateTex guard,
a test-only instrumentation clock, and the possibility that cached redraws stop during the measured
stall; none exists in the current path.

### C57-TR-01 — cached zoom redraws overwrite the camera-frame clock and invalidate FrameGap evidence

- **Severity / confidence / classification:** Medium / High / confirmed causal tracing defect; the
  precise frequency on PMA110 is manual validation, but both false-negative and false-positive
  mechanisms execute directly from current source.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:2104-2125,2129-2172` coalesces
    live zoom to about 60 Hz and sends every flushed moving value to the Engine/GL path.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4441-4487` applies the target
    to GL immediately while moving Camera2 submits are suppressed; the start/quiet/end edges can
    still pay the documented repeating-request stall.
  - `app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt:879-900` uses `lastDrawMs` to decide
    that the camera is quiet, then calls `drawFrame(updateTex = false)` to repaint the last texture.
  - `GlPipeline.kt:919-941` computes `FrameGap` from that same `lastDrawMs` and unconditionally
    assigns `lastDrawMs = now` before it branches on `updateTex`. Thus a cached preview repaint is
    indistinguishable from a newly delivered camera frame to the diagnostic clock.
  - `app/src/androidTest/kotlin/me/hletrd/telecampro/PinchGestureProbeTest.kt:25-32,66-79` explicitly
    tells the operator to correlate FrameGap with ZoomTrace inside the injected pinch spans, the
    exact interval in which cached redraws are frequent. No unit/instrumented test mentions
    `lastDrawMs` or proves producer-only gap measurement.
- **Concrete failure scenario:** the zoom-start Camera2 request stalls real delivery for 400 ms while
  moving target updates trigger cached preview redraws about every 16 ms. Each redraw resets
  `lastDrawMs`; when the next actual SurfaceTexture frame arrives, its apparent gap is only the few
  milliseconds since the last cached repaint, so no FrameGap row is emitted. The probe can therefore
  report a visually smooth cached preview and zero gaps while the encoder/analysis received no real
  frame for 400 ms. In the opposite direction, after a quiet producer interval a sparse
  `updateTex=false` zoom repaint can itself emit `FrameGap`, attributing UI redraw spacing to
  Camera2.
- **Competing hypotheses checked:** `FrameNotificationCoalescer` correctly identifies real producer
  notifications, and `previewSignal.readyAfterSwap(realCameraFrame = updateTex)` already preserves
  that distinction later in `drawFrame`; the timestamp simply ignores it. `lastSelfRedrawMs` limits
  repaint frequency but does not preserve a producer-frame timestamp. Encoder and analysis correctly
  skip cached redraws, which makes the diagnostic mismatch more consequential rather than harmless.
- **Suggested fix:** split the state into a render timestamp used only by the self-redraw throttle
  and a producer-frame timestamp updated and checked only when `updateTex == true`. Cached redraws
  must neither satisfy nor emit producer health. Reset both at GL-generation release. Extract a pure
  timestamp policy or inject a clock, then test real-frame → many cached redraws → late real-frame,
  cached-only false-positive refusal, first frame, and generation reset; keep the pinch probe as
  device validation rather than the sole regression guard.

## Verification, limits, and final missed-issue sweep

- `python3 tools/check_docs.py`: **158 checks, 0 failed, 24 optional-private checks skipped**. Its
  success is consistent with C57-PT-01 because the inventory deliberately classifies FrameGap as an
  unbounded reserved fault.
- No source, plan, build input, production output, device, emulator, deployment, provider state, or
  external service was modified. No Camera2/GL/audio/provider fault injection ran. Open physical
  obligations A3/A4/A5/D1/E1/E2/E3 remain evidence boundaries rather than inferred pass/fail claims.
- The final sweep rechecked every production thread/executor owner, blocking wait, recurring task and
  log, bounded collection/queue, frame/readback hot path, full-resolution snapshot, lifecycle edge,
  and cycle-56 terminal. The new pending-identity scheduler remains one scheduled/in-flight retry per
  claim; process admission callbacks run outside owner monitors and detach with an exact drain; all
  completed DNG tails now cross the finite process owner; StartupTrace tokens are Engine/controller/
  request-owned. Those closed findings were not refiled.
- GL analysis remains one-in-flight over a <=256-pixel-long-edge FBO; frame notifications are
  coalesced; processed snapshots, provider lanes, review work, recording storage, native teardown,
  and standby AudioRecord ownership remain finite under the examined failure paths. No additional
  current performance, liveness, race, leak, or causal-trace finding survived the final competing-
  hypothesis pass.

**Finding count: 2 — both Medium severity and High confidence.**
