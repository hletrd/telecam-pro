# Cycle 53 performance, concurrency, tracer, document, and native-UI review

Date: 2026-08-25  
Reviewed revision: `fcf7ba2c` (`cycle53`, `origin/main`)  
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle53.cJwfCJ`

## Authority, complete inventory, and method

I read `CLAUDE.md` first and then the committed current authorities in
`docs/ARCHITECTURE.md` and `docs/FIELD_CHECKS.md`. The optional private maintainer documents named by
those files are absent, as the clean-clone fallback policy permits. I inventoried all 546 tracked
paths before tracing behavior: all 103 production Kotlin/Java modules (including all 31 production UI
modules), 241 JVM/Robolectric/Compose tests, four instrumented tests, 15 main resource paths, all 39
`tools/**` and `device-tests/**` paths, 101 Markdown paths, and the remaining manifests, Gradle/
provenance inputs, fonts, images, licenses, and privacy assets.

The runtime pass followed every Camera2 callback/session/ImageReader/watchdog edge; Engine optics,
preview, rollback, capture, REC, lifecycle, and native-quarantine generation; GL/EGL frame,
readback, analysis, and output owner; processed/RAW/video save and MediaStore publication/recovery
lane; standby/recording AudioRecord handoff; ViewModel ticker/throttle/debounce and StateFlow write;
review decoder/player/bitmap/spool owner; and every executor, handler, scheduler, queue, monitor,
atomic, latch, retry, deadline, and bounded collection. I separately traced every cycle-52 source
change through its callers and terminals. The document/UI pass examined every production Compose
file, EN/KO resource pair, theme/manifest resource, UI-focused test, current public/architecture/
field/submission authority, and checked-in screenshot validity record. It covered accessibility
semantics, keyboard/D-pad focus and restoration, contrast, touch floors, RTL, narrow/large-font/
large-screen layout, loading/error/progress states, destructive confirmation, localization, and
interaction affordances. Comments and green tests were treated as hypotheses, not execution truth.

## Findings

### C53-PT-01 — DNG write failure performs unbounded provider and SQLite cleanup while the RAW Image and camera callback remain live

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed unbounded camera-thread failure path; the duration of an actual
  MediaProvider outage remains device-dependent.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:5076-5087` calls
    `StillCapturePipeline.saveDng` synchronously from `CameraController.PhotoCallback.onPhoto` because
    DngCreator needs the live RAW Image.
  - `app/src/main/kotlin/me/hletrd/telecampro/capture/StillCapturePipeline.kt:336-367` catches an
    incomplete DNG write by calling `discardRejectedOutput(uri)` before it returns. That call is not
    limited to DngCreator or the live write.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:196-202,453-475,1597-1637`
    shows that `BoundedRejectedOutputOwner` bounds retained identities but executes
    `discardEffect` synchronously before taking its short lock. The effect retries durable DISCARD up
    to three times, sleeps between failures, then performs provider delete/probe work inline.
  - Since the cycle-52 identity fix, every DISCARD attempt first calls
    `PendingDiscardJournal.mark`; `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:32-79,498-541`
    makes that call synchronously obtain mounted-volume/version truth, query the exact MediaStore
    row, open/transactionally write SQLite, and clean legacy preferences. None of those Binder/
    filesystem calls has a deadline.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:2149-2193` invokes the app
    callback on the Camera2 HandlerThread and closes/clears the RAW Image only in the surrounding
    `finally`, after the entire failure cleanup above returns.
- **Concrete failure scenario:** MediaProvider or its database becomes slow/unresponsive while a DNG
  output stream fails partway through a shot. The catch path immediately queries that same provider,
  potentially three times, writes SQLite/preferences, sleeps, deletes, and probes—all on the Camera2
  handler while the acquired RAW Image still occupies an ImageReader slot. Repeating results, later
  image callbacks, watchdog completion, 3A/focus publication, and the next shot queue behind that
  provider outage; an indefinitely blocked identity query makes this an unbounded camera stall rather
  than a bounded save failure.
- **Competing hypotheses checked:** The rejected-output owner is finite in *cardinality* only; it is
  not a dispatcher. The ordinary processed lanes run on `ioExecutor`, but the failing DNG branch does
  not. RAW-only publication is process-finite and asynchronous only after a successful `saveDng`
  return, so it cannot protect this catch. The outer controller `finally` guarantees eventual Image
  close only if provider cleanup eventually returns.
- **Concrete fix:** Reserve a slot in a process-wide finite rejected-output dispatcher before firing
  the RAW request, and hand the failed URI to that dispatcher after DngCreator returns so the camera
  callback can close the Image immediately. Preserve fail-closed crash recovery with a cheap
  camera-thread-safe rejection intent (or an already-reserved task) and perform identity capture,
  SQLite upgrade, delete, and retry entirely on the bounded worker. Add a deterministic test whose
  identity reader blocks: `onPhoto`/Image close must finish promptly, exactly one URI must remain
  owned, and late worker completion must still produce durable DISCARD or the existing unresolved
  recovery state.

### C53-PT-02 — always-on debug 3A telemetry exhausts ColorOS's log quota before the committed ten-minute soak completes

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed diagnostic/tracing failure; debug-only, with no release-runtime cost.
- **Exact regions:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:1060-1123` increments
    `threeAFrame` for every repeating result and writes one full `3A:` log row on the first result and
    every 30 frames thereafter. It is time-throttled but not change-gated or run-bounded.
  - The optional sustained-YUV instrument at `CameraController.kt:1582-1609` adds a second row every
    second for as long as `zslSpikeLogging` is armed.
  - `CLAUDE.md:1054-1065` records the measured ColorOS ceiling of 300 rows per process and explains
    that quota exhaustion silently drops `StartupTrace`, focus, gap, and fault evidence.
  - `docs/FIELD_CHECKS.md:106-127` requires a ten-minute front pseudo-ZSL soak and asks for every
    `FrameGap >= 200 ms`, camera error, cadence, memory, and thermal observation through the end.
- **Concrete failure scenario:** At 30 fps the always-on `3A:` line alone emits about 300 rows in five
  minutes (and startup/session/error diagnostics consume part of the same quota, so loss starts
  earlier). Even at the documented dark 15 fps cadence it reaches about 300 rows over the ten-minute
  A5 run before other logs are counted. If the cadence probe is armed, its additional one row/second
  makes loss certain much sooner. The second half of the required soak can then experience a real
  frame gap, camera error, focus transition, or recovery without any surviving app log, corrupting
  precisely the evidence this debug build exists to collect.
- **Competing hypotheses checked:** `BuildConfig.DEBUG` protects release users, but A5 explicitly
  requires the immutable debug build, so that guard does not protect the evidence workflow.
  `threeAFrame % 30` reduces CPU/logging rate but does not bound total rows. `StartupTrace`'s one-line
  buffer and the change-gated focus/motion traces cannot reclaim quota already spent by 3A. The ZSL
  spike logger is optional, but the 3A producer is always active.
- **Concrete fix:** Emit 3A only when a compact diagnostic tuple changes, plus a long heartbeat no
  shorter than the existing 15-second motion/focus precedent; bucket noisy ISO/exposure fields so
  steady AE does not defeat the gate. Accumulate the spike cadence in memory and emit one summary on
  disable/end (or a small fixed number of interval summaries), not one row per second. Add a pure
  cadence-budget test simulating the full ten-minute A5 run and asserting all continuous debug
  producers remain safely below a shared quota reserve for gaps and faults.

## Document and native Compose UI result

No additional document/UI finding survived the full static pass. The cycle-52 duplicate-focus fix is
present at the production sites: Settings Close and tabs, compact Fn/ruler Close, tap-focus reset,
and Fn-modal Close now rely on their clickable/selectable owner, while the only remaining explicit
`focusable()` uses are genuinely non-clickable adjustable/viewfinder/review surfaces. The new
production-composition tests exercise Enter/DirectionCenter activation and one-edge tab traversal.
Modal focus boundaries and return owners, gallery/review loading-ready-failed semantics, assertive/
polite error and progress regions, 48 dp interaction floors, stacked/scrolling responsive branches,
RTL physical-control exceptions, dark-theme contrast tokens, destructive confirmation, and EN/KO
resource parity all agree with current code and authority by static evidence. This is not a device,
TalkBack-service, display-contrast, or physical-layout claim.

The cycle-52 DISCARD identity prose now matches the versioned SQLite/provider implementation and its
open E3 field boundary; the v1.0.1 Korean count is reproducibly corrected; the app-owned-vs-unverified
review source split, stale-spool reclamation, rollback-after-unlock packet, standby AudioRecord typed
revocation/stop-deadline terminal, and current field dashboard are reflected consistently in the
current authorities. The two findings above do not depend on stale documentation.

## Verification and evidence limits

- `python3 tools/check_docs.py`: **155 checks, 0 failed, 24 optional-private checks skipped**.
- `:app:lintDebug`: **BUILD SUCCESSFUL**.
- Focused JVM/Robolectric/Compose suite covering modal/keyboard controls, all review tests, standby
  audio, and pending-DISCARD journal: **BUILD SUCCESSFUL**.
- No device, emulator, deployment, Camera2/GL/audio fault injection, MediaProvider mutation, browser
  automation, or destructive operation was performed. Open physical obligations A3/A4/A5/D1/E1/E2/
  E3 remain open exactly as `docs/FIELD_CHECKS.md` records them.

## Final missed-file and competing-hypothesis sweep

I reconciled the tracked inventory against the subsystem/UI/document map and rechecked every source
of recurring work, unbounded wait, provider/native call, per-frame allocation/log, main-thread I/O,
late publication, lifecycle restart, queue rejection, capacity overflow, and partial cleanup. I also
rechecked every modal/action/Back path, semantic role/state, focus requester, touch floor, fixed
geometry, scroll/reflow branch, resource literal, locale peer, screenshot manifest, privacy/
permission statement, and current evidence claim. Binary fonts/images/wrapper artifacts were checked
at their glyph, manifest, packaging, and provenance boundaries rather than treated as executable
concurrency surfaces.

The cycle-52 standby stop timeout now wakes an existing waiter and strongly quarantines its exact
input; revoked create/start returns retain typed ownership; DISCARD replay is identity-conditioned;
review source work is process-finite; rollback effects leave the Engine monitor before external
publication; and the six duplicate focus owners are gone. No second queue leak, lock-order cycle,
native-release race, steady-state bitmap/analysis amplification, UI focus/layout defect, localization
gap, or current-authority drift survived the final causal pass.

**Finding count: 2 — both Medium severity and High confidence.**
