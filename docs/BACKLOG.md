# Release Status & Backlog — TeleCam Pro

Current release board. Read after `CLAUDE.md`; use `ARCHITECTURE.md` for implementation details.
Historical investigation notes are snapshots under `docs/reviews/` and `.context/reviews/`, not
active TODO lists. Last synced 2026-07-25 (cycle 8, plus the namespace/SDK-removal and rotation doc passes);
per-file history via `git log -- docs/BACKLOG.md`.

## Release State

Version 1.0 (`versionCode=1`). **RESOLVED 2026-07-27 — the owner chose to re-cut from `main` with
cycle 9 included, and that candidate is built, verified, and screenshotted.** The earlier "ship the
frozen artifact vs re-cut" decision is closed; both frozen candidates (`9541697` 2026-07-25 and
`a0d4dbc` 2026-07-27) are SUPERSEDED and must not be uploaded.

- **The artifact:** built from current `main`, signed with the unchanged upload certificate, and
  validated the same way as its predecessors (`bundletool validate`, AAB `jarsigner -verify`, APK v2
  signing, 16 KiB alignment). Exact hashes and the certificate fingerprint live in
  `docs/play-console-submit.md` — that sheet remains the single home for artifact identity; do not
  copy hashes here.
- **What it adds over the frozen candidates:** the Kotlin namespace move, cycle-8 responsiveness, the
  pseudo-ZSL ring and focus-confidence detector — all previously excluded — plus cycle 9 (selectable
  teleconverter, device-derived TELE rail, AE lens-switch seed, camera-switch dip, loupe framing
  hint, gallery-restore fix, zoom-gesture submit policy).
- **The screenshot blocker is CLEARED.** All six were recaptured 2026-07-27 at the required
  1440×2880; provenance and the measured crop box are recorded in `docs/play-console-submit.md`.
  One slot (`05-lens-and-tele`) is a black-scene frame — correct and current, but a handheld TELE
  capture would make the three viewfinder frames consistent.
- **What is NOT re-run:** the full PMA110 release matrix against this exact artifact. Individual
  features were device-verified from this build (see cycle 9 below), but the formal matrix sweep
  recorded for `9541697` has not been repeated end to end.

### Verified 2026-07-10

- Release build, bundle, lint, and the then-current JVM suite passed. The suite has since grown;
  re-run the release gate before upload and treat `app/src/test/` as the source of truth rather than
  copying a test/task count here.
- `bundletool 1.18.3 validate`, APK v2 signing, and 16 KiB zip alignment passed.
- Manifest has target/min SDK 36, no `INTERNET`, and no `DEBUGGABLE` flag.
- The installed PMA110 release APK matched the local verified APK byte-for-byte.
- Fresh state starts on 1x / 23 mm with TELE off.
- Remember Settings, Preserve Lens, and Preserve TELE default on; lens and TELE restore independently.
- Rapid double-shutter produces one serialized DNG+HEIF pair; both files are valid.
- 4K HLG records HEVC Main10 at 30000/1001 with AAC 48 kHz stereo.
- Open Gate records 2560x1920 4:3 HEVC Main10 at 30000/1001 with AAC.
- Core photo/video UI, menu hierarchy, settings persistence, and Play screenshots were reviewed on
  the physical PMA110. No crash or ANR was observed.

### Verified 2026-07-14 — seamless zoom + capture-pipeline session
1. **iPhone-style seamless zoom (photo)** — logical camera 0 (zoomRatio 0.6–20, physIds 3/2/4/5),
   pinch never reopens; lens picks = zoom presets; OSD shows the live effective focal.
   Device-verified: zero reopens across a 0.6→10 sweep.
2. **Video re-homed to standalone lenses** — the logical camera's EIS (Standard AND Active) leaks
   its uncorrected warp margin (~6% of width) into the preview AND the recorded file (verified by
   frame extraction; shows as a rainbow band at the bottom in portrait playback).
   `resolveNonTeleId` splits camera homes by mode; `setVideoMode` remaps unified↔lens-local zoom.
3. **Shutter could permanently wedge on the logical camera** — gralloc rejects its HAL-JPEG blob
   (the image never arrives) and a RAW target errors the camera device ~5 s post-shot. Fixed:
   YUV stills via `StillSnapshot`, RAW gated standalone-only (DNG = TELE mode), an 8 s capture
   watchdog, and instant shutter-blink feedback (the physical lag is pipeline-depth ×
   frame-duration: ~0.85 s at a 1/10 s dark-room preview, ~0.3–0.5 s in normal light).
4. **Zoom lag/jank, three layers** — full-request rebuild per tick (→ controller fast path on a
   cached repeating builder), per-input-event state updates at ~120 Hz (→ 16 ms/~60 Hz coalescing;
   compounding inputs MUST base on the coalesced pending value, not the stale UI state), and a
   ~33 MB full-res analysis glReadPixels every 5th frame (→ aspect-matched FBO, ≤256 px long edge).
5. **REC tally border vanished at the panel's rounded corners** — now follows the physical corner
   radius (WindowInsets RoundedCorner API, ×1.2 squircle compensation, user-tuned on device).
6. **UI pills** — Fn-row edge-fade scroll hint; one shared 12 dp left inset (OSD / meter / Fn row).

### Verified 2026-07-14 (late) — 0x80b4 TC session experiment (user-landed, agent-verified)
- The stock app's TC operation_mode **0x80b4 passes through to the HAL** as a plain
  SessionConfiguration sessionType on the standalone 3× camera: CamX logs
  `configure_streams() operation_mode: 0x80b4`, the session configures at fallback=0 WITH RAW,
  stills (HEIC+DNG) and 4K HEVC recording work, and TELE off returns cleanly to 0x0. No
  CameraUnit AUTH_CODE involved — this may be the 300 mm TC OIS profile unlock (stock pairs the
  mode with sensor mode 48). **Residual field check:** mount the converter and A/B the shake
  damping at 300 mm vs the previous SESSION_REGULAR build to confirm the OIS profile actually
  differs (result metadata shows ois=1/vstab=2 either way).

### Verified 2026-07-18 — review-plan-fix cycles 3-4 (device-verified)

- Long-exposure stills: 2/3.2/4 s captured with correct EXIF on the standalone TELE; 5/6.3 s
  reproducibly error the device (`CAMERA_ERROR(3)`) — the 4 s caps-seam ceiling
  (`HAL_SAFE_MAX_STILL_EXPOSURE_NS`) plus the 500 ms repeating clamp ship as the fix. NOTE: the
  bisect covers ONLY the standalone TELE; the ceiling is applied to every route as a conservative
  assumption (see Residual Field Checks for the logical-camera bisect).
- Cycle-3 P9.2 session: zoom fast path + slider both directions, TC on/off, tap-AF + ISO-drag
  focus hold (bit-exact lens distance), photo↔video flips clean.
- Cycle 4: the recorded-video FRAMING defect was device-confirmed and fixed — the encoder buffer
  was stream-shaped landscape while GL content is portrait, so every clip carried a ~3.16× center
  band of the viewfinder field (luminance-gradient A/B; cell-map corr 0.29 pre-fix). Post-fix the
  encoder takes the displayed-aspect swapped buffer (2160×3840 for 4K UHD): preview↔file cell-map
  correlation 0.992, span ratio ~0.87. Older "Verified" video entries in this file predate this
  finding — their container/codec facts stand, their implied framing does not.

### Landed 2026-07-25 — cycle 8 responsiveness (device-verified except the focus tag)

Plan: [`plans/2026-07-25-rpf-cycle8.md`](plans/2026-07-25-rpf-cycle8.md); live status in
`.context/cycle8/status.md`. All slices gate-green (incl. coverage A ≥ 99.5%). Device evidence was
received 2026-07-25 for slices 1, 2 and 4; slice 3's own check ran and is recorded in place below:

1. **Preview brightness simulation** — AE-OFF previews ride a 1/15 s fluidity cap
   (`PREVIEW_FLUIDITY_MAX_EXPOSURE_NS`); the residual shortfall past ISO headroom renders as
   bounded (≤×16) linear GL gain (`uDigitalGain`); zebra/false-color/scopes/AE meter the
   simulated still exposure; files and stills untouched; the 500 ms HAL-safety clamp stays outer.
2. **AE step schedule** — `maxStepStops` = 0.5×|err| in [0.30, 1.20]; big scene changes converge
   ~2× faster; steady-state smoothness unchanged.
3. **Focus-confidence tag** — one compact amber OSD tag (700 ms hold), two proofs. `AF_LIMIT`
   (AF failed/hunting near `LENS_INFO_MINIMUM_FOCUS_DISTANCE`) shows `TOO CLOSE` with an optional
   closer-lens suffix. `FRAME_DETAIL` (`gl/FocusDetail.kt`, a pure curvature-ratio metric riding
   the existing analysis readback) shows `SOFT` and no suffix — it proves the frame resolves no
   fine detail, not that the subject is too close. Added because the TELE HAL false-locks
   `FOCUSED` at infinity on a ~9 cm subject, which makes `AF_LIMIT` unreachable on that route
   (see CLAUDE.md). **DEVICE-CHECKED 2026-07-25 — the detector MISSES the case it was built for.**
   Measured from its own tile votes (a `FocusConfidence` DEBUG trace added for this check):
   - sharp reference (front camera, lit room at normal distance): **5-6 soft of 75-76 judgeable**
     (7-8% soft), `bestRatio` 0.89-1.05 → RESOLVED, correct.
   - defocused, subject ~3 cm on TELE (min focus 120 cm): **53 soft of 72** (74%), `bestRatio` 0.30
     → **RESOLVED, a MISS.** Same subject at 1x (min focus 15 cm): 45 of 73 (62%), `bestRatio` 0.19,
     also a MISS.
   The binding constraint is the FRAME rule, not the metric: `FOCUS_SHARP_TOLERANCE = 0.02` requires
   ≈1 sharp tile out of ~74, and genuinely defocused frames still show 19-28 (sensor noise raises
   the fine-lag term — by design, see `FOCUS_SOFT_RATIO`; noiseless synthetic frames cannot expose
   this, so no host test could have caught it). The metric ITSELF separates cleanly, and `bestRatio`
   separates best (0.19-0.30 defocused vs 0.89-1.05 sharp).
   **Left unchanged deliberately.** The contract is "may miss, must never false-fire", and a miss
   does not violate it. Relaxing the tolerance to 0.50 was tried and REVERTED: it broke the existing
   "a small sharp subject inside a defocused field keeps the frame resolved" test — i.e. it would
   false-fire on shallow-depth-of-field shots, which for a 300 mm telephoto app is the *normal*
   photograph. Three scenes are not enough evidence to move a never-false-fire threshold.
   **To close this properly** (deferred): drive the frame verdict off `bestRatio` (nothing anywhere
   in the frame resolves) instead of a sharp-tile count, and calibrate it on a device set that
   MUST include bokeh — a sharp subject on a blurred background — plus flat, dark, grainy, and
   motion-blurred scenes. Until then the FRAME_DETAIL path is effectively dormant on this device
   and `AF_LIMIT` remains the only live proof (itself unreachable on TELE).
4. **Pseudo-ZSL — S4a measured, S4b LANDED.** S4a soak and S4b serve numbers are recorded once, in
   CLAUDE.md's pseudo-ZSL bullet; the ring shipped as `4ec42c0` (pure admission seam) + `7ffb406`
   (3-deep ring, inline serve, exact-timestamp adoption), and the debug toggle is now fps-logging
   only. **The dark-shot refusal is the accepted design, not an open defect.** A session-ladder rung
   later made the deep reader degradable (`SessionAttemptPlan.useDeepZslReader`) so a gralloc
   rejection of the ~5×19 MB allocation costs zero-lag serving instead of all stills. The ZSL probe
   also found YUV/PRIVATE reprocessing ADVERTISED on cams 0–5 (evidence:
   `.context/cycle8/zsl-probe-2026-07-25.md`) — see the true-ZSL deferral below.

### Landed 2026-07-27 — cycle 9 transitions (DEVICE-VERIFIED except the two-finger items)

Eight commits, gate-green at 1175 host tests. The device came online late in the cycle
(`127.0.0.1:37605` via a loopback proxy; mac0 reaches the phone at LAN speed through a local TCP
relay, which is how the 34 MB debug APK installs in 0.9 s instead of stalling for 9 minutes on the
tunnel). Device evidence is recorded per item below. **What could NOT be checked: anything needing
a two-finger pinch** — `adb input` has no multitouch, so the pinch-feel items are still open.

1. `daa7639` **TELE rail → device-derived zoom marks.** Floor and ceiling come from the lens's own
   advertised bounds × the converter magnification; unreachable snaps are ABSENT rather than
   clamped (a 1.5× converter drops 60× because the lens ceiling is 45.7×).
   **DEVICE-VERIFIED 2026-07-27:** TELE on turns the rail from `0.6× 1× 3× 10×` into
   `13× 30× 60×` with 13× selected (3× lens × 4.286 ≈ 12.86 → 13×). The same toggle reconfigured
   with `raw=true` where non-TELE reported `raw=false`, which independently re-confirms the
   standalone-only RAW gating.
2. `2122175` **boost-flip rebuild removed** where it provably cannot change a request key
   (`boostFlipChangesFpsDecision`). Saves the documented ~180 ms stall at BOTH pinch edges on the
   app-side exposure route — which is this device's default stills route.
3. `a057b8a` **`leadingEdgeArmed`** re-arms the zoom-out wide-aim margin on the quiet-window
   landing rather than on tail expiry, so a re-pinch starting inside the previous gesture's tail
   still gets its margin.
4. `1aefb39` **AE carried across a lens switch at constant EV** (`seedForApertureChange`,
   `t_in = t_out · (N_in/N_out)²`, mode-aware carrier). 1×→10× is 2.295 stops on this device.
   **DEVICE-VERIFIED 2026-07-27** across a real camera switch (TELE toggle, f/1.58 ↔ f/2.26 =
   1.03 stops), dark room: 1× sat at ISO 9052 / 66.7 ms; the tele's FIRST frame came up at ISO
   12700 (the seed wants 9052 × 2.046 = 18520 and clamps at the sensor ceiling — right direction,
   right magnitude up to the clamp); returning to 1× landed on ISO 9052 EXACTLY on its first
   frame. No convergence excursion in either direction, which is the reported defect gone.
   Note the scene was dark enough that exposure was pinned at the fluidity cap throughout, so
   this exercised the ISO carrier only — the time carrier still wants a lit-scene check.
5. `f612054` **camera-switch dip.** Replaces the FROZEN old-lens frame (magnified on TELE-off)
   that today fills the gap between `controller.close()` and the new stream's first frame.
   Discriminator is a session-generation CHANGE, never `cameraReady` — every optics door clears
   that bit including the same-route fast path behind every photo lens preset, so a ready-keyed
   cover would flash black on the most-used control in the app. Cold start and resume also raise
   a cover.
   **THE CONSTANTS ARE NOW MEASURED (2026-07-27), and both hold.** `GlPipeline`'s FrameGap is the
   right instrument — it IS the frozen-frame window the cover exists to hide. Measured on device:
   a TELE-**off** reopen (→ logical, `raw=false`) gaps **288 ms**; a TELE-**on** reopen (→
   standalone 4, `raw=true`) gaps **658 ms**. So the 120 ms grace is shorter than the SHORTEST
   real gap and therefore covers both, and the 1500 ms deadline has 2.3× headroom over the
   LONGEST, i.e. it never fires on a normal reopen — exactly the safety-net role it was given.
6. `f0f4d69` **Loupe Overview gated on the loupe**, so it can no longer report On while drawing
   nothing. The engine/GL path itself was traced and is correct (GL combines the pushed resolved
   flag with its own punch-in state inside `drawFrame`).
   **DEVICE-VERIFIED 2026-07-27, both directions and end to end:** with Loupe off, the Loupe
   Overview label renders grey and its switch dim while the parent Loupe row stays bright (the
   gate is targeted, not blanket); turning Loupe on brings the label to full white and the switch
   to normal. With all five conditions met the corner viewport DOES draw and the OSD carries both
   `LOUPE` and `OVERVIEW`. So the user's "the PIP loupe still doesn't work" was the missing parent
   toggle, and the UI now says so instead of silently reporting On.

**OPEN — the sustained mid-gesture frame-rate drop is NOT fixed.** Item 2 above removed only the
two gesture-EDGE rebuilds. The user's actual report ("it drops further while zooming") has a
different cause, now confirmed by code trace rather than hypothesis:

- `previewExposureTrade` takes **no gesture parameter** and `AutoExposure.kt` contains no gesture
  references at all. The cycle-8 fluidity cap is unconditional, so the previously documented
  "gesture fps-boost trades exposure→ISO so the base frame rate rises" no longer exists as a
  gesture-specific mechanism — it was subsumed by that always-on cap.
- `ManualControls.kt` states the boost's ENTIRE wire effect is
  `pinAutoFps = pinAutoFps || smoothPreviewBoost`. On the app-side exposure route `manualAeAdmitted`
  is already true, so **the smooth-preview boost has zero wire effect on the route the user shoots
  on.**

**MEASURED ON DEVICE 2026-07-27 — the mechanism is confirmed and it is WORSE than documented.**
Driving 12 zoom-preset submits (1×↔3×, the same controller fast path a pinch drives) over ~4.2 s
produced six FrameGaps ≥200 ms: **400, 282, 213, 413, 276, 210 ms — 1794 ms, i.e. ≥43% of the
window with the preview stalled**, and that is a FLOOR because gaps under 200 ms are not logged at
all (see the ColorOS log-quota bullet in CLAUDE.md). Individual stalls reach **413 ms**, above the
170–250 ms the rest of the docs quote. The GL self-redraw keeps the ZOOM motion smooth by redrawing
the last frame at the new `zoomComp`, but the SCENE is frozen — which is exactly what "it drops
further while zooming" looks like when panning.

**The obvious remedy — raise `SENSOR_SUBMIT_MIN_INTERVAL_MS` — is REFUTED by this same run, and the
earlier entry proposing it was wrong.** The taps above were ~350 ms apart and the resulting submits
landed ~400 ms apart, i.e. already double the 200 ms floor — and the stalls were undiminished
(413 ms and 276 ms still appear at that spacing). The stall is a property of the repeating-request
swap itself, not of how closely two swaps are packed, so spacing them further apart buys back
proportionally little and just makes `zoomComp` diverge more. Do not tune this constant expecting a
fix.

**RESOLVED by user decision (2026-07-27): a MOVING gesture now submits NOTHING.** Presented with the
measurement and the tradeoff, the user chose "제스처 중 전부 생략" — one submit at each gesture edge
instead of one per ~200 ms. `resolveHalZoomSubmit`'s `submitNow` is simply `!interactionActive`.

Two consequences that had to move with it:

- **The wide aim relocated to the gesture-START edge.** It used to ride the mid-gesture submits that
  no longer exist, and it is the only thing pre-buying the field the GL crop needs to zoom OUT, so
  `setZoomInteraction(true)` now passes a wide-aimed `halZoom` while `finalZoom` keeps the exact
  ratio — the still-request truth must never inherit the aim (a still would frame ~17% wide).
- **The quiet-window landing is kept and becomes load-bearing.** `landExactZoom` already fires only
  while an interaction is active, so a PAUSED finger still lands the exact ratio and sharpens the
  preview, while a MOVING one stays silent. Pause-to-sharpen came for free.

Accepted cost, stated to the user before the choice: with no mid-gesture submit the HAL field is
frozen at the edge's wide-aimed target, so the preview softens progressively while zooming IN (GL
upscales) and runs out of field if zooming OUT past the 1.2× margin. Video-P and flash-metered P
start a gesture without the margin at all, because those two routes take the rebuild path whose wire
zoom comes from `controls` — accepted rather than pay a second stall to widen.

**DEVICE-VERIFIED 2026-07-27 with a REAL injected pinch.** `adb shell input` is single-pointer and
`sendevent` on the touchpanel is refused by SELinux (shell is in the `input` group, so this is a
policy denial, not a DAC one) — but INSTRUMENTATION can do it: `UiAutomation` holds INJECT_EVENTS,
so `PinchGestureProbeTest` (androidTest, probe-only, never fails the build) posts multi-pointer
`MotionEvent`s into our own activity. Measured over two full pinches:

| | zoom-in 1.0→4.6 | zoom-out 4.6→1.0 |
|---|---|---|
| submits during finger MOTION | 0 (start edge only) | 0 (start edge only) |
| start-edge HAL target | 1.0 (wide aim clamped at the range floor) | **3.756** = 4.6 ÷ 1.2 |
| FrameGaps DURING the gesture | **0** | **0** |
| FrameGap after fingers lift | 370 ms | 396 ms |

Both conclusions are load-bearing. Mid-gesture stalls are GONE — the only gaps fall after the
fingers lift, during the settle, where the old policy produced one roughly every 200 ms and each
demonstrably cost 210–413 ms. And the accepted zoom-OUT risk did NOT materialise: zoom ran the whole
way back to 1.0 instead of starving at the margin, because the relocated wide aim (visible on the
wire as `submit=3.756`) pre-bought the field. Note the comparison is structural, not a same-input
A/B — the earlier 43%-stalled figure came from rail taps, which are NOT gestures and still submit by
design (re-confirmed at 251/275/291/354 ms). The absolute result is the claim: zero submits and zero
gaps while the fingers move.

What a human still owns is the SUBJECTIVE half — whether the progressive softening while zooming in
reads as acceptable. The stall behaviour itself is now measured, not assumed.

### Landed 2026-07-27 — startup/UI profiling pass

Profiled the RELEASE build on PMA110 rather than guessing at what felt slow, and the guess would
have been wrong: the camera path was already clean.

`dumpsys gfxinfo` over a mixed interaction showed 3.01% janky frames with **GPU p95 at 4 ms and
every janky frame flagged `Slow UI thread`** — CPU-bound, not render-bound. The Compose compiler
report (`-PcomposeReports=true`, now an opt-in flag in `app/build.gradle.kts`) then ruled out the
usual suspect: **94 of 94 restartable composables are skippable, none unskippable**, so the cycle-4
stability work (`app/compose_stability.conf`) is doing its job and recomposition breadth was not the
problem.

Isolating each interaction with its own `gfxinfo reset` located the cost precisely — first
composition of the menu, not the viewfinder:

| action | p99 before | p99 after |
|---|---|---|
| open settings | **61 ms** | **22 ms** |
| tab switches | 36 ms | 30 ms |
| close settings | 16 ms | 13 ms |
| idle viewfinder | 10 ms | 7 ms |
| photo↔video | 11 ms | 12 ms |

Root cause: `dumpsys package dexopt` reported the shipped APK at **`status=verify`** — no profile
had ever been supplied, so it ran interpreted until JIT warmed. Fixed by adding
`androidx.profileinstaller` 1.4.1 plus `app/src/main/baseline-prof.txt`, which AGP compiles into
`assets/dexopt/baseline.prof` (verified present in both the APK and the AAB).

Two notes for whoever revisits this:

- The profile is CLASS-level and hand-scoped, not generated. A macrobenchmark module
  (`androidx.baselineprofile`) would produce method-level rules from a measured run and should
  replace this file if that module is ever added. Coarse as it is, it already beat a forced
  blanket `compile -m speed` on the worst frame (22 ms vs 26 ms).
- `profman` is not reachable from the adb shell on this device, which is why the device's own JIT
  profile could not be converted into generated rules.

### Front shutter lag — MEASURED 2026-07-28, root cause is the missing ZSL route

Device numbers, same app / same session / same room, 3 of 3 each:

| route | shutter lag |
|---|---|
| REAR (pseudo-ZSL) | `images+result +0 ms` — serves a buffered frame aged 104–119 ms |
| FRONT (no ZSL) | **+554 / +561 ms** |

Brightness was NOT the dominant term. In the dark the front sat at ISO 16000 / 66.7 ms; in a lit
room it runs ISO 375 / 33.3 ms, and the lag only fell to ~555 ms. The `ShutterLag` breakdown puts
~455 ms of that BEFORE the capture starts (`started +455 ms`), i.e. roughly 14 frame intervals of
queueing — not exposure, and not something more light can fix.

**The fix is extending pseudo-ZSL to the front route, and it is a real session-shape change, not a
flag flip.** `zslStreamingActive()` requires `caps.isLogicalMultiCamera` because the ring is fed by
the full-res YUV still reader that only the LOGICAL photo route configures (that route uses YUV
stills because gralloc rejects its HAL-JPEG blob — see the CLAUDE.md bullet). Standalone routes,
front included, keep the proven HAL-JPEG path and have no YUV stream to buffer.

Order of work when picked up:

1. Relax the `isLogicalMultiCamera` condition in `zslStreamingActive()`.
2. Add the full-res YUV reader to the front session's ladder, degrading to today's HAL-JPEG path if
   the HAL rejects the combination — this HAL has form here (gralloc blob rejection, the RAW-on-
   logical device error), so the rung must fail soft.
3. `ZslAdmission` needs NO change: it is already a pure predicate with no route assumptions.

Risk to respect: the front route is documented as "untouched … byte-for-byte", and every stream-combo
change on this device has needed a real capture pass to trust.

### Native log — the stock app's recipe, CAPTURED (2026-07-28)

Traced the stock camera live in `4K·30·O-Log2` via `dumpsys media.camera`, which prints the active
client's configured streams. The result overturns BOTH prior positions — mine and the older doc's.

```
Stream[0]: 1920x1080  format 0x7fa30c09  dataspace 0x8c60000  Dynamic Range Profile: 0x1
Stream[1]: 3840x2160  format 0x36        dataspace 0x8c60000  Dynamic Range Profile: 0x1
```

**Native log runs on an 8-BIT session.** `Dynamic Range Profile 0x1` is STANDARD, not HLG10 (0x2).
So the "log needs 10-bit, which is why our 8-bit test showed nothing" theory is WRONG — being
8-bit was never the obstacle, and switching to a 10-bit session is not the path.

`dataspace 0x8c60000` decodes to **STANDARD_BT2020 | TRANSFER_SMPTE_170M | RANGE_FULL**. That is
the tagging the stock log stream carries: BT.2020 primaries, full range, and a placeholder SDR
transfer — not an HDR transfer.

Vendor tags found in the same dump that this app does NOT currently touch:

| tag | id | why it matters |
|---|---|---|
| `com.oplus.VideoColorBT709` | `811900e4` int32 | plausibly the switch that forces the 709 output we kept observing |
| `com.oplus.video.dataspace` | `8119007e` int32 | sets the stream dataspace the table above shows |
| `com.oplus.log.extension.iso.range` | `8119002e` int32[2] | a LOG-SPECIFIC ISO range — the HAL genuinely has a log path, not just an accepted key |
| `com.oplus.log.video.mode` | `811901e5` int32 | already known and already set by this app |

That last row is the point: we set the log MODE key alone and concluded it was inert. The dump shows
the stock path also carries a dataspace and a BT709 control, so the honest reading is that the key
was necessary but not sufficient — never that the HAL ignores it.

Replication order (no guessing left):

1. Set `com.oplus.VideoColorBT709 = 0` alongside the existing `com.oplus.log.video.mode`, on both
   the session parameters and every repeating request.
2. Tag the encoder stream `STANDARD_BT2020 | TRANSFER_SMPTE_170M | RANGE_FULL`, matching stock,
   instead of the transfer our container policy picks today.
3. If output is still 709, set `com.oplus.video.dataspace` explicitly to the same value.
4. Judge a RECORDED FILE, never the preview and never the container tag — the earlier false
   "it recorded as log" was exactly a misread container tag.

Keep the GL-baked S-Log3/LogC3 profiles either way: they are the display-referred fallback, and a
native path would be a separate, genuinely scene-referred option.

## Before Production

These are manual Play Console operations, not repository implementation work:

1. Create the app and upload the signed AAB to Internal testing.
2. Enter the listing, privacy policy, App content, and Data Safety answers from `docs/play-*.md`.
3. Upload the icon, feature graphic, and the six 1440x2880 phone screenshots.
4. Set the device catalog (see play-console-submit.md § Device Catalog — the app is no longer single-device).
5. Install from Internal testing and review Play's automated checks and pre-launch report.
6. Promote the same tested artifact to production.

Use `docs/play-console-submit.md` as the operator checklist. The account was created in 2015, so the
new-personal-account closed-test rule does not apply; an internal test remains the release gate.

**Owner actions outside this repo (recorded by cycle 6, 2026-07-23):**

- **DONE 2026-07-27 — the GitHub About tagline.** The cycle-6 finding (F-2) described it as claiming
  "Raw / Log video"; by the time it was actioned it already read "DNG stills, log-profile video", so
  the RAW-video misreading was gone and that half of the note was stale. What was still wrong was
  different: it pinned the optic to "Hasselblad 300 mm teleconverter" after the converter became
  selectable, and "DNG stills" read as unconditional when DNG only exists in TELE mode on the
  standalone camera. Now: *"Manual camera for the OPPO Find X9 Ultra and clip-on afocal
  teleconverters — selectable converter magnification, DNG stills in tele mode, HLG / S-Log3 /
  LogC3 video profiles, full manual Camera2 control"*.
- **STILL OPEN (D-2):** confirm the Play/privacy contact mailbox is real and monitored, and that the
  GitHub Pages privacy-policy URL is live, before submission. The repo `homepageUrl` is also empty —
  set it if the listing should point at the Pages site.

## Residual Field Checks

These do not require a code or metadata change unless the result exposes a defect:

- **RESOLVED 2026-07-24 (disposition kept for the durable record): the ColorOS installer gate on
  the instrumented test APK is bypassed by a plain streamed `adb install -r -t` of
  `app-debug-androidTest.apk`** (the install-session path UTP uses gets `-99` from the OPlus
  gate; the streamed path goes through). The test package is deliberately LEFT INSTALLED on the
  PMA110 — uninstalling re-arms the gate. Full workaround + the HOME-key lifecycle caveat live in
  docs/TESTING.md ("PMA110 device caveat"); merged coverage is reproducible via
  `tools/coverage/union_report.py`. Connected leg verified: 4/4 instrumented tests pass, merged
  overall 48.64% (exact union, basis drift 0).

- Record the same real scene with Sound Focus/Stage on and off, then compare off-axis rejection.
  Camera/HAL parameter acceptance is verified; the acoustic effect needs ears and a suitable scene.
- **RESOLVED 2026-07-25 — the held-orientation STILL matrix is device-verified (and it caught the
  cycle-6 MAJOR for real):** the pre-fix `+dev` matrix saved a landscape-held rear still 180°
  rotated (laptop shot, keyboard-up); after a209830 (BACK −dev / FRONT +dev, dev being GyroEis
  CCW-positive) the full matrix passed on device: rear portrait ✓, rear landscape LEFT-90 ✓ and
  RIGHT-90 ✓ (room shots, both upright with landscape dims), front portrait ✓, front landscape ✓
  (one direction; the other follows algebraically). REMAINING residual: a held-landscape VIDEO
  clip in an external gallery — `RotationMath.videoOrientationHint` now carries the same
  device-confirmed −dev/+dev term, but the container-hint playback check itself has not been run.
- Added by review-plan-fix cycles 2-4 (2026-07-17/18 runs):
  P-mode brightness-target judgment in a lit room (QA-3); EIS warp-band re-confirmation per the
  established lit-scene frame-extraction method (QA-4); a long-exposure bisect of the LOGICAL
  camera's still ceiling (the shipped 4 s clamp is tele-verified-only and conservatively global);
  level-overlay responsiveness feel after the roll-alpha retune (0.93); the mid-REC dead-mic
  meter zeroing (hard to trigger on demand — code-inspected); held portrait/landscape playback of
  the new PORTRAIT-buffer video files in external galleries (orientation-hint sign, U1).
- Added by review-plan-fix cycle 6 (2026-07-23; the phone was desk-bound via a remote proxy this
  cycle, so every held-device item below is NEEDS-HUMAN):
  - **RESOLVED 2026-07-25 — capture-rotation device-orientation term sign, BOTH facings.** Cycle-6
    debugger F1 was CORRECT: the signs were swapped. `a209830` landed BACK = sensor − dev and
    FRONT = sensor + dev (dev being the GyroEis CCW-positive gravity read), and the held matrix
    passed on device — see the RESOLVED entry above for the evidence.
    `RotationMath.videoOrientationHint` carries the same term; only its EXTERNAL-PLAYER playback
    check remains open (below).
  - **Front VIDEO file mirror truth.** Front STILL mirror truth WAS device-verified 2026-07-23
    (cycle-6 QA: a pulled front JPEG showed legible, unreversed "LG/WHISEN" text after a viewing
    180° — the saved file keeps the true scene). The encoder un-mirror path is shared with video,
    but no front CLIP has been pulled and checked: record a front video of legible text and
    confirm it reads unreversed in an external player.
  - **Front tap-AF aim — NOT moot.** Cycle-6 probe: the front camera (id "1") ADVERTISES
    `android.control.maxRegions = [AE=1, AWB=0, AF=1]`, so tap-AF/AE regions are live on the front
    route. Debugger F2's suspected sensor-half mirror error stands: the display half of the tap
    mapping is correct, but `viewTapToSensorPoint` undoes only rotation, never the mirror, so the
    metering region may land at the horizontally OPPOSITE active-array point. Check: tap a subject
    near the LEFT edge of the selfie preview against a depth-separated background and confirm
    focus/exposure drives from the tapped subject, not its horizontal mirror. If wrong, the sensor
    half needs the `1−nx` un-flip (seam: `gl/FrontMirrorConvention` + `mapTapFocusGeometry`).
  - **Log-profile on-device check PARTIALLY CLOSED.** An S-Log3.Cine 4K clip was ffprobe-verified
    2026-07-23 (HEVC Main10, `color_transfer=bt2020-10` — confirmed NOT PQ/ST2084, the exact
    mistag the explicit-transfer container policy exists to prevent). Still open: playback
    appearance of the HLG/log output on a real HDR/reference display.
  - **Hi-res dormancy device checks (dormant on PMA110; live code).** Before releasing on any
    device that actually advertises a hi-res size: (1) audit the passthrough-JPEG EXIF lane — HAL
    bytes go to disk verbatim, so verify no unexpected maker-note/GPS payload survives that the
    processed lane's re-stamp would have dropped (cycle-6 security F-5); (2) time the still
    watchdog against a real full-sensor capture — remosaic delivery may exceed the current
    exposure-derived margins (cycle-6 tracer T5).
- Added by the 2026-07-10 review cycle (code changed since the recorded release AAB — re-run the
  full release gate and refresh `docs/play-console-submit.md`'s hash before upload):
  one HEIF capture on heifwriter 1.1.0 (stable, was 1.2.0-alpha01); waveform overlay visual parity
  after the drawPoints batching; the new OSD AE/AWB/AF lock tags + TeleChip hit-area layout on the
  3168 px screen; a TalkBack pass over the new dial/slider semantics.

## Deferred Beyond v1

- **R8/minify:** keep disabled until enum persistence has explicit keep rules and another
  physical-device release pass. (The "reflection-sensitive OEM SDK paths" half of this blocker
  retired with the `com.oplus.ocs` removal on 2026-07-25 — no build variant links an OEM SDK now,
  and `app/proguard-rules.pro` carries no vendor keep rules, only the staged, still-commented
  SettingsStore enum rule.)
- **Dolby Vision:** device-probed end to end on 2026-07-26 and the technical path WORKS — the
  blockers are legal and honesty, not engineering. `DolbyVisionProbeTest` (androidTest; logs under
  `DVProbe`, never fails the build) showed `c2.qti.dv.encoder` is HW and visible to our own
  third-party package, configured it, encoded 12 P010 frames, and MediaMuxer accepted the track and
  closed a file `ffprobe` reads as a genuine DV stream: `dvvC` box, `dby1` brand, `dv_profile=8`,
  `dv_level=11`, `rpu_present_flag=1`, `bl_present_flag=1`. Do NOT reason from the APV exclusion —
  AOSP's `MPEG4Writer` special-cases `video/dolby-vision` rather than rejecting it. The emitted
  transfer is `arib-std-b67` (HLG) even when PQ is requested, i.e. **Profile 8.4**, whose base layer
  is the same HLG signal our GL pipeline already builds, with the encoder generating the RPU. So the
  remaining engineering is small (a 10-bit encoder EGL config plus the DV MIME/profile). What must
  be resolved first, in order:
  1. **Source honesty.** The stream is still the ISP's display-referred 8-bit SDR output (see the
     entry below). A DV-branded file would assert more than the pipeline can back — the same rule
     that already forbids marketing our HLG as end-to-end 10-bit.
  2. **Trademark.** Using the device's licensed encoder through public Camera2/MediaCodec is one
     question; putting "Dolby Vision" in the UI or the Play listing is a separate permission
     question that public Dolby documentation does not answer (their Implementation Handbook covers
     chip and device makers only). Requires contacting Dolby before any user-visible naming.
- **End-to-end 10-bit Camera2 input:** the stable shipping Camera2/EGL source is SDR/8-bit; the prior
  HLG10 + JPEG + RAW combination crashed the HAL.
- **Authenticated CameraUnit path:** still a legitimate deferred option, but the groundwork was
  REMOVED 2026-07-25 (commits `c27744c` probe + `2b4bc55` build graph) — restore from those rather
  than re-deriving coordinates or credentials. Gone: the `com.oplus.ocs` dependency (camera 1.1.0 +
  base 1.0.16 and its base-auth/base-internal transitives), the OPPO OpenCapability maven repo and
  its credentials, the four `gradle/verification-metadata.xml` components, and the debug-only
  `OcsProbe` availability check. **Why:** without an AUTH_CODE the probe could only ever return
  `errorCode=1004` (AUTHCODE_EXPECTED) — a constant of the missing registration, not of the device,
  so it could never be what tells us the answer changed — while costing ~32 ms of debug cold start
  (isolated A/B, proc-start → `configure_streams` END, median of 4 runs each: 527 ms with the SDK,
  495 ms without; the release-vs-debug 354/535 ms gap is debug-BUILD overhead and must not be
  quoted as the SDK's cost) and, decisively, 200+ log rows that blew ColorOS's 300-row per-process
  quota and ate our own `StartupTrace` instrumentation. Re-enable order, which
  is the ONLY sanctioned path:
  1. an AUTH_CODE actually ISSUED (not merely applied for) for applicationId `me.hletrd.telecampro`
     plus the signing cert, via OPPO developer registration;
  2. that code as a `com.coloros.ocs.camera.AUTH_CODE` manifest meta-data (the placeholder comment
     in `app/src/main/AndroidManifest.xml` marks the spot);
  3. restore the maven repo block, the version-catalog entries, the `debugImplementation` lines and
     the four `com.oplus.ocs` verification-metadata components from the commits above;
  4. re-add behind a build flag so it is NOT on by default even in debug, and re-measure cold start
     and startup log volume before merging;
  5. a product decision on a separate CameraUnit video session that lacks the current RAW/manual
     Camera2 surface.
  Orthogonal to the shipped TC path: vendor session type 0x80b4 and the public `com.oplus.*` Camera2
  request keys are device-verified, need no AUTH_CODE and no SDK, and were untouched by the removal
  (see "Verified 2026-07-14 (late)" above).
- **Optional product work:** configurable keep-screen-on, geotagging, custom save locations, slow-
  motion playback metadata, and advanced focus/bracketing workflows.
- **TELE pseudo-ZSL (cycle-8 deferral):** the ring design targets the LOGICAL photo route only;
  the standalone TELE keeps its proven HAL-JPEG capture. Extending ZSL there needs a full-res YUV
  repeating target INSIDE the vendor 0x80b4 session — a session-shape change on the proven TELE
  path. The S4a streaming evidence now exists (cycle 8 above) and the LOGICAL ring has shipped; what still
  gates TELE is the session-shape change alone — a full-res YUV repeating target INSIDE the vendor
  0x80b4 session. Reassess only with an explicit device session plan.
- **True ZSL via reprocessable session (cycle-8 deferral):** YUV_REPROCESSING/PRIVATE_REPROCESSING
  are ADVERTISED on cameras 0–5 (`maxNumInputStreams=1`, YUV/PRIVATE 4096×3072 INPUT → JPEG/YUV;
  probe: `.context/cycle8/zsl-probe-2026-07-25.md`), but an input stream is a new session shape on
  a HAL with a record of fatally over-advertising, and the reprocess JPEG output on camera 0 needs
  the exact blob reader gralloc already rejects there. Debug-gated device spike only, behind an
  explicit device window.
- **True wide-field TELE finder (design item, owner decision needed):** the shipped `Loupe Overview`
  assist (default OFF) honestly re-draws the FULL current camera frame — the single
  Camera2 stream already carries the HAL's `CONTROL_ZOOM_RATIO` crop, so no GL work can show a
  genuinely wider field. A real iPhone-style wide finder needs either (a) a second concurrent
  wide stream (high risk on this HAL — physical-sub-camera routing and several multi-stream
  combos crash it; must be device-verified per session plan), or (b) capping the HAL zoom and
  rendering the remaining main-view magnification in GL (touches AE metering, tap-AF region
  mapping, video encoder framing, and preview sharpness — a zoom-architecture change, not a
  finder change). 2026-07-21 device probes narrowed the options: public concurrent sets contain only
  `[0,1]`, so rear pairs `2+0`, `2+4`, and `2+5` cannot use separate CameraDevice sessions. A safe
  API-35 `CameraDeviceSetup` query returned `true` for logical-0 physical PRIVATE pairs `2+4` and
  `2+5` at both 640x480+640x480 and 640x480+1920x1440, but that metadata-only answer does not erase
  the existing QTI `configureStreams` SIGSEGV/Broken-pipe evidence for physical routing. The next
  step is an explicitly approved, isolated preview-only HAL experiment on `127.0.0.1:5602`; do not
  enable or present the same-stream overview as the requested 1x finder.

## Dispositions from review-plan-fix cycle 1 (2026-07-17 run; durable record)

Full records in `docs/plans/2026-07-17-rpf-cycle1.md` § Deferrals:

- **AGG-20 (Low/Medium)** — RETIRED BY IMPLEMENTATION (cycle 2, 2026-07-17): the sensor-keys-only
  fast path on the cached builder landed (`sensorOnlyControlsDelta`/`applySensorValueControls`,
  ≥200 ms pacing with trailing exact landing), covering the app-side AE pair and manual
  focus/ISO/shutter drags. Residual: on-device confirmation that dial-drag preview cadence
  improved rides the normal per-cycle device verification.

## Deferrals from the 2026-07-26 functional + UI review triage (durable record)

Everything else from that review was fixed in the same run; these are the items that need device
evidence or a design decision, with enough detail to act on without re-deriving them.

- **P3 — the always-on full-res YUV ZSL target's memory/thermal cost (needs a device soak).**
  `CameraController` raises the LOGICAL route's processed reader from `maxImages = 2` to
  `ZSL_RING_DEPTH + 2` at `caps.largestYuvSize` (4080×3064 ≈ 18.7 MB/buffer): ~94 MB of graphics
  memory instead of ~37 MB, held for the whole photo session, and the reader is on the REPEATING
  request, so the HAL writes ~18.7 MB × ~30 fps ≈ 560 MB/s whenever the photo viewfinder is up —
  whether or not the user ever shoots. These are the app's largest allocations. The 10-min soak
  already run measured +0.3 °C, but covered NEITHER a low-memory device state NOR a long idle
  viewfinder. Check: (1) a 30-min idle photo viewfinder with `dumpsys meminfo` + thermal sampling;
  (2) the same under memory pressure (several heavy apps resident) watching for a gralloc failure or
  a session reconfigure. Mitigations if it bites, in order: `ZSL_RING_DEPTH = 2` (still ~66 ms of
  history at 30 fps, far above the measured 0 ms serve latency), and/or detach the ZSL target while
  ProSheet or review is open.
- **P4 — a tap-to-focus fires two extra full-res YUV frames into the ring (needs measurement).**
  The AF CANCEL and START one-shots are submitted from the SAME cached builder that carries the ZSL
  target, so every tap costs two extra ~18.7 MB frames through the reader plus two extra
  `zslRingAdd`/close cycles on the camera thread. Correctness is unaffected (legitimate frames,
  legitimate results). Deferred rather than fixed because building the two triggers from a
  ZSL-less copy touches the device-verified tap-AF submit path for an unmeasured win. Check: trace
  camera-thread time and `Image` churn across a burst of taps; fix only if it shows.
- **UI13 — the dormant `landscapeOperator` two-pane layout.** ~15 lines of unreachable Compose
  emission plus two dead spacing ternaries in the app's hottest file, gated by a hardcoded `false`.
  KEPT deliberately: the comment records that it is dormant pending the orientation pipeline (GL
  sampling, capture masks, tap mapping and encoder framing all share a portrait-window contract),
  and deleting it would discard that design. Revisit together with that pipeline, not before.
- **UI16 — CLOSED 2026-07-26 (typography pass). `FontWeight.Bold` is gone; no fourth face bundled.**
  `app/src/main/res/font/` has Regular/Medium/SemiBold only, and the ~22 call sites that asked for
  Bold (`CameraScreen` ×11, `MediaReview` ×5, `ProSheet` ×3, `ManualDials` ×2, `ProControls` ×1)
  were all being resolved 700 → 600 by font matching, so collapsing them to `SemiBold` was
  pixel-identical everywhere except FocalRail. **The stated blocker was false**: FocalRail did not
  need a new face — `FontWeight.Medium` (500) is already bundled, so its selected/unselected step is
  now SemiBold(600) vs Medium(500) and RENDERS with zero new assets. Six of the 22 disappeared into
  the theme entirely when `titleSmall`/`titleMedium`/`titleLarge`/`displaySmall` were raised to 600.
  **The "+~110 KB" estimate was also wrong by ~4×**: the three bundled faces measure 412/417/420 KB,
  so a fourth static face is ~420 KB — and eleven of the sites sit at or below 13 sp, where Inter's
  700 thickens stems into the counters (worst on the inverted `TELE` pill, black on white at 11 sp).
  Do not bundle `inter_bold.ttf`, and do not reintroduce `FontWeight.Bold` at a call site.
  *Device check attached:* FocalRail selected vs unselected at a glance (`0.6×/1×/3×/10×`).
- **The green AF reticle can claim focus on a visibly defocused frame (correctness, not cosmetic).**
  `FocusReticle` draws the green bracket and announces "Focus locked" straight off
  `AfIndication.FOCUSED`. On the TELE route this HAL reports `afState = FOCUSED_LOCKED` with the
  lens racked to infinity while a 9 cm subject is completely defocused (device-measured
  2026-07-25), so the reticle is currently the only UI element that can make a FALSE POSITIVE claim
  about the capture. No UI-layer change fixes it — it needs a different truth source. The obvious
  candidate now exists: `gl/FocusDetail.kt` already produces a per-frame judgement, and
  `state.focusConfidence == FRAME_DETAIL` is exactly "the frame resolved no fine detail". Proposed
  shape (needs device work, and must not regress the honest routes): suppress the GREEN state — not
  the reticle — while the frame-detail verdict is SOFT, leaving the neutral bracket. Blocked on the
  frame-detail detector's own first device verification.

## Verification Quick Reference

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew testDebugUnitTest lintRelease assembleRelease bundleRelease
adb connect <device-ip>:<port>
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n me.hletrd.telecampro/me.hletrd.telecampro.MainActivity
```

The component above is correct for `main`. The pinned v1 release artifact PREDATES the namespace
move, so an APK built from `.claude/worktrees/release-v1 @ 9541697` launches
`me.hletrd.telecampro/com.hletrd.findx9tele.MainActivity` instead. The applicationId is identical in
both, so the mismatch surfaces only as a silent "activity not found".

The wireless-debugging port changes by session. On this multi-homed Mac, direct ADB can report
`No route to host`; proxy the current phone port to a temporary loopback port, connect to
`127.0.0.1:<proxy-port>`, and stop the proxy when verification ends. Do not reuse a recorded old port.

Runtime CAMERA and RECORD_AUDIO permissions are granted through the app UI on ColorOS. The `camtest`
AVD is suitable only for UI/crash checks; telephoto routing, RAW, color, audio, stabilization, and
saved-file behavior require PMA110.

## Deferral dispositions from the 2026-07-17 cycle-2 run (updated 2026-07-18)

- **AGG2-36 / AGG3-17** CameraEngine god-object extraction: cycle 4 landed step 1
  (`StillCapturePipeline`); the current cycle landed steps 2-3 (`RendererAssists` and
  `StandbyAudioController`). The remaining ordered slices stay recorded in
  `docs/plans/2026-07-18-rpf-cycle4.md` §Deferrals.
- **AGG2-38** start↔stop same-tick REC race test: CLOSED by cycle 4 (`RecStartStopRaceTest` over
  the real `RecordingAdmissionLatch` + the extracted `shouldPublishRecording` save gate).

## Deferred from review-plan-fix cycle 2 (2026-07-10 run; durable record)

That 2026-07-10 loop ended with its cycle 2 (later runs — 2026-07-16 cycles 1-9 and the 2026-07-17
loop — have their own dated plans under `docs/plans/`), so the 2026-07-10 deferrals live HERE, not
"in the next cycle." Full citations, severities, and exit criteria:
`docs/plans/2026-07-10-rpf-cycle2.md` § Deferred (D-1..D-18). Summary of what remains open:

- **Device re-verification of the cycle-2 changes** — the PMA110 re-locked behind a secure keyguard
  mid-cycle. VERIFIED on device post-unlock by the orchestrator (2026-07-10): letterboxed preview
  geometry photo 4:3 / video 9:16, photo↔video mode-switch reopens, tap-AF reticle (plus the
  3ba28c8 AspectMask axis follow-up). STILL QUEUED: review-overlay hardware-key gating, resume/
  lens-tap serialization under load, record start/stop under the ordered encoder teardown,
  MediaReview tap-to-pause, flat-resume capture orientation.
- **D-1** muxer orientation-hint SIGN (already in Residual Field Checks above).
- **D-2** confirm the Play/privacy contact mailbox is real and monitored (owner decision;
  `play-store-listing.md` / `play-console-submit.md` / `PRIVACY.md` / `privacy-policy/index.html`).
- **D-3** (restated by cycle 6, 2026-07-23) O-Log2 is DORMANT-DE-LOG-ONLY: the user-facing option
  was removed 2026-07-22 and its forward OETF left `Shaders.kt` with it — only the dormant Gamma
  Display Assist inverse (uTransfer=3) remains, reserved for a future CameraUnit-authenticated
  scene-referred stream. The inverse-threshold discrepancy therefore concerns dormant code only,
  and its numeric test coverage is now string-only (the numeric pin left with the forward curve —
  cycle-6 test-review F-A2). Decide with OPPO's white paper in hand before reviving it; nothing
  shipping depends on it.
- **D-4** structural refactors (state triplication/applyExtras, CameraEngine split, AE-loop move,
  CameraSession seam, caps projection out of CameraUiState, telemetry-flow split, typed engine
  events) — MUST land before the CameraUnit / "Authenticated CameraUnit path" v2 work. Cycle-6
  sizes at review baseline: CameraEngine.kt 4889 lines (+221 that cycle; ~5000+ after the cycle-6
  fixes), CameraScreen.kt 2526 (+126), CameraViewModel.kt 2318 (+98), CameraState.kt 931 (+80).
  FACING is now the FOURTH hand-copied optics axis (mode/lens/TC/facing): every door hand-repeats
  the same invariant checklist and the ViewModel hand-mirrors every engine transaction (cycle-6
  architect F1/F2) — the gap between this trend and the "must land before v2" order is widening.
- **D-5..D-9** implementations are landed: downsampled analysis readback, REC admission off main,
  single-decode HEIF+JPEG, GL frame coalescing / cached request builder / waveform draw reuse, and
  DNG publication on `ioExecutor` after the required synchronous live-RAW write. Their remaining
  performance/feel evidence is part of the pending current PMA110 matrix.
- **D-10** MR-bank management placement — CLOSED 2026-07-21: save/recall lives in Shooting/My Menu,
  the viewfinder strip is removed, and only an active slot appears in the compact OSD. **D-11** AE metering under LOG preview,
  **D-12** dead backup_rules removal (needs explicit owner sign-off — file deletion),
  **D-13** pure-env release signing gate (when CI exists), **D-14** keep-screen-on toggle (already
  above), **D-15** FnSlot table test on next enum growth, **D-16** OPPO Maven cred relocation — CLOSED
  BY REMOVAL 2026-07-25 (`2b4bc55`): the credentials were deleted from `gradle.properties` with the
  maven repo block that was their only reader, so the deferral's exit criterion ("CameraUnit
  registration lands") no longer gates anything. They were OPPO's documented public read-only
  credentials, so no rotation was required. **D-17** GyroEis roll wrap transient — CLOSED by cycle 3 (aa6cab9, wrap-aware smoothedRoll + GyroEisMathTest), **D-18** the
  lit-scene/human-ears residual field checks (above).

## Historical References

- `docs/reviews/README.md` — tracked review-archive index and usage rules.
- `docs/reviews/2026-07-09-backlog-handoff.md` — pre-release implementation handoff and device history.
- `docs/reviews/2026-07-03-comprehensive-review.md` — comprehensive review snapshot.
- `docs/plans/` — completed review/fix plans.
- `.context/reviews/` — optional local raw specialist review snapshots (gitignored).
- `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` — original design intent.
