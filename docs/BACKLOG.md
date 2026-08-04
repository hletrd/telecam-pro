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
- **RE-CUT DONE AND PINNED.** The live artifact identity — hashes, gate numbers, device matrix —
  lives ONLY in `docs/play-console-submit.md`, which this file's own rule two bullets down already
  says. It was restated here anyway and went a cut stale within a day. Current cut and its evidence:
  see that sheet. Historical note: the 2026-08-02 `66734db` cut was the first verified on FOUR
  targets — PMA110 (A16), Lenovo TB336ZU (A16), Lenovo
  TB331FC (**A15**), and an Android 13 (API 33) emulator — all proven byte-identical first. Matrix
  in `docs/play-console-submit.md`.
  - **Three device-found defects fixed this pass, none of which PMA110 could ever show:**
    1. The encoder buffer is swapped to portrait for the cycle-4 framing contract and nothing asked
       the encoder whether it takes that shape. **Two of four tested encoder families refuse it** —
       the AOSP software one and the TB331FC's Qualcomm one — so recording was impossible there.
       Fixed with a same-aspect fallback ladder (`148e7db`).
    2. Four of five gammas request `Main10`; an 8-bit-only encoder silently returns `Main`. The
       TB336ZU's hardware MediaTek encoder was writing clips tagged `bt2020 / arib-std-b67` over an
       8-bit stream. Capability-gated (`8de0415`), with a same-device before/after recorded.
    3. Still size was "largest advertised JPEG within the array"; the TB331FC advertises a SQUARE
       2448×2448 with more pixels than its native 4:3 2592×1944, so every photo saved square. Shape
       now precedes size (`66734db`).
  - Also landed: the standby audio meter follows the selected input (USB/BT/wired) and meters per
    channel (`a4a7d12`).
  - The signing blocker (a GPG backup holding the RETIRED keystore's password) is resolved and the
    backup re-encrypted; `.gitignore` is now pattern-based (`fc43953`). **Audited: no key,
    keystore, or password has ever entered git history on any ref.**
  - **CLOSED 2026-08-03 by the owner: the upload key will NOT be rotated.** The password is six
    digits and was transmitted in plaintext; rotation was offered before the first upload (one
    `keytool -genkeypair` then, a Play support request afterwards) and **declined**. The residual
    risk is accepted and bounded by Play App Signing: an upload key alone cannot ship to users
    without console access, and the keystore is gitignored and local-only. Do not re-raise this.

### CLOSED 2026-08-04 — the slow camera start was the status timer, not the camera

Owner-reported: "starting the camera takes a long time." It did not. Measured on the reported
device, `am start` returns in 412 ms and the Camera2 session configures at ~950 ms (DNG-independent:
943 ms with RAW wanted, 958 ms without), which matches the documented `resume → first camera frame
≈ 544 ms` budget. What took seconds was the **`"Starting camera…"` pill**.

`statusDisplayDurationMs` classifies by wording, and this message matched nothing, so it landed in
the `else -> 2_500L` neutral bucket. A PROGRESS message reports a condition that is true or false
right now — a fixed timer is wrong in **both** directions: too long and a fast bring-up reads as a
multi-second wait (what the owner saw), too short and the pill vanishes while the camera is still
coming up, which claims ready before it is.

It now has NO display duration and is retired by the owned Ready publication. Retired on the
publication gate's ordering alone, **not** inside the `mainHandler.post` below it: that post
additionally rechecks engine truth to protect the ACCEPTED aux state (formats, pre-TELE baseline)
from a stale cross-thread post, and a progress pill has no such hazard. The clear is guarded on the
message still BEING that status, so anything published during bring-up keeps the pill and its own
timer. Nothing bounds the message otherwise — while the camera genuinely has not come up,
"Starting camera…" is true, and every way that attempt can end (Ready, an error status, the
exhausted-retry terminal status) replaces it.

The literal lives in `camera/CameraState.kt`: the engine emits it, the UI policy must recognise it,
`CameraEngine`'s companion is private, and the camera layer must not import the UI layer to reach a
string. Two literals in two layers would drift apart silently and strand the pill.

**Device-measured A/B, two shapes.** Before: the pill was still on screen **5.2 s** (TB331FC) and
**4.1 s** (Android 13 emulator) after `am start`. After: never sampled across a 20 s window at
~0.5–1 s cadence on either. Host tests pin both halves — the timer-less classification and that
Ready clears it without swallowing a later message.

### CLOSED 2026-08-04 — `zoomRatio` carries two scales, and the route decides which

Owner-reported on the PMA110: tapping `3×` jumped the viewfinder to **9.1×**, and the rail pill then
disagreed with the zoom it had just produced.

`zoomRatio` means **main-relative** on the logical seamless camera and **lens-local** on any
standalone lens. Three call sites — the lens-preset handler, settings restore, and the pre-TELE
capture — read whichever scale they happened to be handed, and the mode→optics remap keyed off
`CaptureMode` when the thing that actually decides the scale is the ROUTE. Photo on a standalone
route (which is what wanting DNG produces) is exactly the case where mode and route disagree, so
3 × 3.03 landed as 9.1.

Fixed as one conversion pair in `camera/CameraState.kt`, host-tested, with every site going through
it: `unifiedZoomOf(lens, ratio, standaloneRoute, optical)` and `localZoomOf(unified, optical)`,
both resolving the base through `opticalBaseFor` — **the optical lens the route actually reaches**,
not the preset the finger touched. `resolveLensOpticsIntent` now takes `standaloneRoute` +
`opticalPresets` instead of the capture mode, and `remapModeOptics` early-returns when photo is
already standalone.

Three commits because the first was **correct on the phone and wrong on both tablets** (it divided
by the tapped preset, which on a crop-only device is not a lens that exists). A second device shape
is what caught it; a single-device check would have shipped the regression.

Device-measured on all three (debug build, code `c66993d`): PMA110 `1×/3×/10×` → 23 / **69** /
**230 mm**; TB336ZU → 26 / **78 mm**; TB331FC → 27 / **81 mm**. Only the phone reads 69 mm at `3×`,
because only it reaches that framing optically — the tablets' exact ×3 is the crop, and that split
is the evidence the conversion is route-based rather than preset-based. The highlighted pill
matched the tapped preset on every device (checked by pixel, since selection is not exposed in the
UI dump). Re-checked 2026-08-04 on the signed release artifact on the two targets still reachable.

### CLOSED 2026-08-03 — camera blocked for ONE app now says so

Found on the TB331FC. The device had `appops CAMERA: ignore` at the UID level with `REVOKED_COMPAT`
on the permission: `checkSelfPermission(CAMERA)` returned **GRANTED** while `openCamera` was
rejected with `Camera "0" disabled by policy` (the stock camera app worked, so the block was
per-app). The app behaved safely — no crash, shutter disabled — but said nothing, leaving normal
viewfinder chrome over a black frame.

Fixed with the operator's approved wording and surface: the EXISTING full-screen permission gate is
reused (no new banner — `docs/UX_POLICY.md` forbids warning chips) with neutral, action-oriented
copy that does not name a cause, because a work profile, kiosk provisioning, and an OEM privacy
manager all produce this shape and naming one would be wrong on the others:

> Camera blocked for this app on this device.  [Settings]

`CameraPolicyBlockedException` + `cameraErrorCodeIsPolicyBlock` / `cameraFailureIsPolicyBlock`
classify it apart from eviction and ordinary HAL faults. The state is LATCHED on the failure but
ANNOUNCED only once the bounded reopen budget is spent, so a transient refusal cannot blank a
working viewfinder.

**The exception code alone is NOT proof, and treating it as proof would have been a false
accusation.** `CAMERA_DISABLED` is the code the platform ALSO raises for the transient
background-proc-state refusal this project already documents (relaunch behind the keyguard, screen
just woken). The latch is therefore confirmed against AppOps — `unsafeCheckOpNoThrow(OPSTR_CAMERA)`,
which answers the actual question: is the op withheld from this package right now. A lifecycle race
leaves it `MODE_ALLOWED` and is never accused. Asking defensively answers "not withheld" on any
failure to ask, so an unanswerable question degrades to the old silence rather than a wrong claim.
Device-verified both ways on the TB331FC. `cameraOpModeWithheld` is pure and host-tested.

Both failure paths were confirmed to arrive on device: `StateCallback.onError code=3` AND a
synchronous `CameraAccessException CAMERA_DISABLED`. The generic "Camera error. Recovering." status
is suppressed for this class — it promises an outcome the retries cannot deliver — while the honest
log line is kept.

**The retract path is load-bearing and was wrong on the first attempt.** The gate REPLACES the
viewfinder, so while it is up there is no preview Surface — and with no Surface the engine cannot
open the camera, so it could never observe the block being lifted. The first implementation
deadlocked the app on the very screen telling the user how to fix it (caught on device, not in
review). `resume()` now clears the latch before anything else, which is exactly the moment the user
returns from Settings; if the block is still in place the bounded reopen re-raises the gate within
a second or two. Device-verified both directions on the TB331FC: blocked → gate; block cleared +
foreground → gate retracts and the preview is live again. Healthy devices are unaffected
(PMA110 and the Android 13 emulator both show `blocked-screen=0`).

Reproduce with `cmd appops set --uid <uid> CAMERA ignore`.

- **Device catalog is now a bigger decision than it was.** It used to lean on `minSdk 36` doing the
  narrowing; at `minSdk 33` an open catalog reaches essentially the whole Android 13+ population
  against two capture-verified devices. Owner call — see the Device Catalog section.

### Landed 2026-08-01 — perf review closure + multi-device + 200MP probe (device-verified)

- **Perf review fully dispositioned (16 items):** 8 low-risk landed earlier (`2c8cccc`); this cycle
  landed #2 single-pass NV21 (+ #3 remainder: merged crop+rotate, single-use snapshot), #6 scope
  publication gate, #8 ZSL never-serve DRIVE gate (repeating-target-only; session shape and ring
  memory kept — the user explicitly accepted the ring's gralloc cost, so #1's session-shape change
  is DROPPED, not deferred), #10 unattended-timelapse dim (10 s grace → 5% brightness,
  `onUserInteraction` restore; verified via `mWindowManagerBrightnessOverride=0.05` engage/restore/
  re-arm/run-stop on device), #11-cheap (remembered review lambda), #15 safe subset (static-quad
  VBO; uniform shadowing deliberately NOT taken — multi-role draws change values per draw).
  Device pass same day: standalone JPEG+DNG pair, logical ZSL serve 0 ms through the NEW NV21 pack,
  5-frame burst all-real-captures under the detached target, timelapse ticks with dim cycling,
  front photo serve + front/rear 4K HEVC clips clean, front→rear return re-resolves the RAW route,
  scopes live in expanded DISP, M-meter live in compact, zoom 1→3× clean on the VBO path.
- **Multi-device:** `minSdk 33` (lint NewApi audit: zero unguarded sub-35 APIs; the 8 API-35
  findings fixed — ByteBuffer bulk get → duplicate() positional reads, VendorTagInspector probes
  SDK-gated). `camera/DeviceProfile.kt` gates the four PMA110-only behaviors
  (front pre-mirror, 0x80b4 TC session type, 4 s still-exposure clamp, oplus request hints);
  GENERIC = spec. PMA110 byte-identical. **Other handsets are installable but UNVALIDATED** —
  per-device passes (front mirror truth, EIS warp, logical-JPEG gralloc, long-exposure ceiling,
  key codes) are open work.
- **Same-day dual-lens review closure (17 confirmed findings, 21 raw, adversarially verified):**
  all code findings fixed same day — front-mirror convention fully parametrized on the profile
  (GENERIC front tap-metering flip + missing selfie mirror, the HIGH pair), timelapse shutter is
  now press-to-start/press-again-to-STOP with the run tinted in the OSD and the run edge
  lock-serialized, the dim skips open modals (mid-run review inspection), ZSL drive-gate resubmits
  only when the target set actually changes (no more ~180 ms no-op stall on TELE/video) with a
  post-swap replay closing the controller-replacement race, the GENERIC ladder bound matches the
  profile-collapsed plan, onCaptureFailed cancels the delivery watchdog, kit focals ride the
  DECLARED phone's host tele (ZEISS 200 on X200U now writes 200 mm, not 165), and capsCache no
  longer memoizes a logical-fallback read. TWO accepted-as-documented residuals:
  1. **Reinstall gallery restore — RESOLVED same day (user decision).** The visual-media READ
     trio (IMAGES/VIDEO/VISUAL_USER_SELECTED) is now requested CONTEXTUALLY at an empty-gallery
     tap; any grant (partial "Select photos" included, `hasVisualMediaAccess`) re-runs the bounded
     capture restore so a previous install's rows seed review. Declining keeps own-rows-only.
     Play follow-through: Data Safety must now declare Photos/Videos access (read-only, no
     collection/sharing) and the privacy policy gains one line.
  2. **`teleDisplayBase`/zoom-pill scale still assumes the 70 mm host** for its display ratio; on
     an 85 mm-host phone the TC zoom pills would read in this phone's scale. Cosmetic-only
     (focals/EXIF are fixed); fold into the per-device validation pass.
- **200MP/50MP probe (stock, device-captured 2026-08-01):** the Hasselblad Hi-Res mode's full-res
  path is a CamX vendor feature pipeline — `RealTimeFeatureNZSLSnapshotRDI0`, sensor mode 0 =
  16320×12256 QUADCFA @ 10 fps on cameraId 2 inside logical 0, operation_mode 0x8001 — negotiated
  by the CHI override. It is NOT a public stream (no >20 MP size in any public map, July probe),
  and the stock app's own first configure attempt gets ILLEGAL_ARGUMENT before falling back to the
  vendor shape, so a sessionType-style replication cannot conjure the stream: unlike 0x80b4 (an
  int + standard streams) this needs stream SIZES the public interface refuses. Additionally the
  mode is LUX-GATED: with the 200 MP badge active in a dark room, stock saved 12.5 MP
  (`IMG20260801062957.jpg` 3064×4080; 15-frame hybridraw merge) — even first-party 200 MP only
  exists in bright light at a 10 fps sensor mode. Conclusion unchanged: third-party full-res needs
  the CameraUnit AUTH_CODE road (Deferred Beyond v1); a raw op-mode/oversized-reader probe against
  this crash-prone HAL was deliberately not attempted on the user's device.

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

**STEP 1 RUN AND MEASURED (2026-07-28): the vendor keys alone do NOT produce log.**

`com.oplus.VideoColorBT709 = 0` was added beside `com.oplus.log.video.mode = 1`, both applied
successfully (`vendor VideoColorBT709=0 applied`), session at `fallback=0, vendorLog=1`, GL curve
suppressed so nothing else could bake one. The recorded 4K clip measures:

```
min 0.0000   p1 0.0000   median 0.0627   p99 0.2353   max 0.6471
```

Blacks are crushed to ZERO, and a log encoding never emits 0 — S-Log3's black sits near 0.09. So the
output is still display-referred. (Scene was dark, which weakens the highlight end of this test, but
not the black end: pure-black pixels are sufficient on their own.)

**The likely reason, from re-reading the stock streams: PIXEL FORMAT, not the dynamic-range profile.**

```
stock Stream[1]: 3840x2160  format 0x36 = HAL_PIXEL_FORMAT_YCBCR_P010   <- 10-BIT
stock Stream[0]: 1920x1080  format 0x7fa30c09 = vendor implementation-defined (preview)
```

Both carry `Dynamic Range Profile 0x1` (STANDARD), which is what misled the earlier reading —
including mine. The stock log path is 10-bit via the **P010 stream format**, not via the
DynamicRangeProfile mechanism this app reasons about. Our record path is SurfaceTexture/PRIVATE
8-bit; the Main10 / `yuv420p10le` / `bt2020-10` in our file is a 10-bit CONTAINER over an 8-bit
SOURCE, which is exactly the mismatch the honesty note in the README describes.

So the remaining step is NOT another vendor key. It is a P010 camera output stream feeding the
encoder, with the BT2020 / SMPTE_170M / FULL dataspace stock uses. That is an architecture change:
the camera→SurfaceTexture→GL→encoder chain is 8-bit end to end today, so GL needs a 10-bit path
(or the record stream must bypass GL). Do not attempt it as a patch — it needs its own slice, and
the afocal 180° rotation means the record path cannot simply skip GL.

**STEP 2–3 RUN (2026-07-28). One solid new capability; the log verdict is still OPEN.**

PROVEN, and worth keeping regardless of log: **a 10-bit HLG10 video session configures on this HAL
at `fallback=0` with no crash**, provided both still readers are dropped in the same configure —
`Session configured (fallback=0, hlg=true, jpeg=false, raw=false, vendorLog=1)`, zero SIGABRT/SIGSEGV.
The long-standing "HLG10 crashes the HAL" note is really "HLG10 **+ full-res JPEG + RAW** crashes
it". `sessionAttemptPlan(tenBitVideoOnly = true)` is that rung; it costs the in-REC snapshot while
active, which is why it is attempt-0-only and falls straight through to the ordinary 8-bit ladder.

NOT established: whether native log ever reaches the file. Both clips measured with blacks at code
0 — but so did the run where the GL S-Log3 curve should have been baking anyway, and a log encoding
of ANY origin cannot emit 0 (S-Log3's floor is ~0.093, code ~95). So the recording path in the
experiment is not in the state the test assumed, and NO conclusion about
`com.oplus.log.video.mode` can be drawn from these clips. Two traps already caught here, both worth
repeating: judging a `bt2020-10`-tagged 10-bit file through an sRGB PNG conversion crushes blacks by
itself (measure the RAW Y plane), and the scene was very dark (whole histogram under code 184),
which is a poor test regardless.

Before re-running, in this order:

1. Record a CONTROL clip with the experiment OFF and S-Log3 selected, measured the same raw-Y way.
   If its blacks also sit at 0, the encoder is not baking the curve and the fault is in the test
   harness, not the HAL — fix that before touching vendor keys again.
2. Shoot a LIT scene with real highlights. A histogram capped at 18% cannot distinguish curves.
3. Only then compare native-log-on vs native-log-off on the same lit scene.

**RESOLVED 2026-07-28 by a CONTROL clip: native log really is inert for third-party Camera2, and
my challenge to the original verdict was wrong.**

| clip | black level (raw Y, 10-bit) |
|---|---|
| CONTROL — GL S-Log3 baked, experiment OFF | `min 58  p1 93  p50 104  p99 153` |
| EXPERIMENT — vendor log keys, GL curve suppressed, HLG10 10-bit session | `min 0  p1 6  p50 43` |

S-Log3's black floor is ~95 of 1023. The control sits **exactly on it**, which proves two things at
once: the measurement method is sound, and the experiment really did suppress the GL curve as
intended. With the GL curve gone the native path supplied nothing in its place — blacks fell to 0.

That holds with BOTH vendor keys applied (`log.video.mode=1`, `VideoColorBT709=0`) on a genuine
10-bit HLG10 session at `fallback=0`. So the original "the HAL accepts the key but third-party
output stays display-referred" conclusion stands, and the three theories raised against it are all
dead: it was not the 8-bit session, not the missing BT709 control, and not the dynamic-range profile.

The remaining untested difference is the stock app's `format 0x36` (P010) CAMERA stream — ours is
SurfaceTexture/PRIVATE feeding GL. Chasing it means a P010 ImageReader path that GL cannot simply
bypass (the afocal 180° lives there), for a feature whose own output the stock app already brands
O-Log2. Not worth it without new evidence; the GL-baked profiles remain the shipping answer.

Keep the GL-baked S-Log3/LogC3 profiles either way: they are the display-referred fallback, and a
native path would be a separate, genuinely scene-referred option.

### 10-bit log — TRANSFORM PROVEN, GATE LANDED, ONE DEVICE CHECK OUTSTANDING (2026-07-29)

**Verified without a device, because no device A/B here was a valid instrument.** A log encoding is
defined by where known anchors land, so `LogFromHlgSourceTest` asserts exactly that:

- 18% grey reaches the S-Log3 anchor (420/1023) from an 8-bit display-referred source **and** from a
  10-bit HLG source; diffuse white agrees between the two routes.
- Both source encodings linearise to the same display light across a full sweep.
- The HLG route yields a genuinely FLAT band (blacks > 0.09, highlights < 0.80, monotonic) — not the
  contrasty deep-black look the broken device clips showed.
- The WRONG decode misses the grey anchor by > 0.05, so the suite can detect its own failure.

`LogProfiles.sourceLinear` is the CPU mirror of the shader branch, so the assertions bind the real
chain rather than a restatement.

**Enablement:** `tenBitSessionWanted(videoMode, transfer)` = VIDEO && transfer != SDR. Photo never
asks, so it never pays the cost — the 10-bit rung drops JPEG/RAW (HLG10 + full-res JPEG + RAW CRASHES
this HAL), which costs the in-REC snapshot while a 10-bit clip records. `setTransfer` now reopens the
session when a change flips that answer, because the transfer became a SESSION input rather than a
GL/encoder-only setting.

**GATE DEVICE-VERIFIED 2026-07-29 — this item is CLOSED.** Every route reports what it should, and
the transfer change reopens the session as intended:

| route | session | GL source decode |
|---|---|---|
| PHOTO (any transfer) | `hlg=false, jpeg=true` | `sourceHlg -> false` |
| VIDEO + SDR | `hlg=false, jpeg=true` | `sourceHlg -> false` |
| VIDEO + log/HLG | `hlg=true, jpeg=false` | `sourceHlg -> true` |

Photo and SDR video keep their stills; only a non-SDR video route pays the JPEG/RAW cost, which is
exactly the trade the gate exists to make. Flipping the transfer between SDR and log inside video
reopens the session both ways, so `setTransfer`'s session-input handling works.

End-to-end file, recorded on the shipping path with no debug override: **HEVC Main 10,
`yuv420p10le`, 2160×3840, `color_primaries=bt2020`, full range** — a genuine 10-bit log clip.

### 10-bit colour pipeline — SEAM LANDED, END RESULT NOT YET CORRECT (2026-07-29)

The source-decode seam exists and is tested, but **the 10-bit path does not yet produce correct log
and must not be enabled**. It stays behind `tenBitExperimentEnabled` (DEBUG + flag file), and with
that flag off the shader takes the byte-identical BT.1886 branch, so shipping behaviour is untouched.

What is proven:
- `Shaders.sourceLinear` replaces the hardcoded BT.1886 decode at all four transfer branches and
  selects on `uSourceHlg`. The HLG branch is the exact inverse of the forward `hlg()` OETF plus the
  inverse of its reference-white scaling, reusing the same constants. `SourceLinearHlgTest` pins the
  round trip both as a pure OETF inverse and end-to-end on the display-light scale (white → 1.0).
- `CameraController.hlgConfigured` reports the session that actually configured, not the intent the
  fallback ladder may have dropped.
- The flag reaches GL through `RendererAssists`/`RendererConfig`, so it is REPLAYED into a new GL
  generation. A first attempt pushed it as a bare `GlPipeline.post` and the value was silently
  dropped — the push logged `true` and the recorded output never changed. That is the documented
  "posted before start() is a no-op" trap, and it cost a full debug cycle.

What is NOT resolved — the remaining unknown, stated precisely so the next attempt starts here:
with `uSourceHlg = 1` the recorded image changed once (mean 100.2 → 75.0, i.e. the seam is live)
and then did NOT move at all when the reference-white normalization was added — a ~3.93x scale that
cannot be a no-op on a signal that is genuinely HLG. The frame still reads as normal-contrast with
deep blacks (p01 = 9) rather than the flat, lifted look the 8-bit reference shows (p01 = 25, p99 =
139). **That guess was then TESTED AND DISPROVED** — record it so nobody repeats it. A probe build forced
`setSourceHlg(false)` on BOTH sessions, making the decode identical so only the camera session's bit
depth varied:

| session | p01 | p50 | p90 | p99 | range |
|---|---|---|---|---|---|
| 8-bit | 24 | 63 | 121 | 135 | 111 |
| 10-bit | 8 | 58 | 154 | **196** | **188** |

Under an identical BT.1886 decode the 10-bit session's source carries a far wider range. S-Log3 vs
S-Log3.Cine differ only by a gamut matrix — a few percent of luma, not 60 points at p99 — so **the
preview buffer really does differ, and is very likely HLG-encoded after all.** The
`setDynamicRangeProfile(HLG10)` on the preview `OutputConfiguration` is doing something real.

**The actual open anomaly is narrower than "is it HLG":** adding the reference-white normalization
(a ~3.93x scale on the linear values) changed the recorded output by NOTHING (mean 75.0 both times,
p99 202 → 201). Neither `gamutFloor` nor `slog3` clamps its input, so that scale cannot be a no-op on
a real signal. Something between `sourceLinear`'s return and the encoder is not carrying the change.

**ROOT CAUSE OF THE NO-OP, FOUND 2026-07-29 — the transform was probably fine; the INSTRUMENT was
not.** `uSourceHlg` was confirmed reaching the draw (`sourceHlg -> true (uniformLoc=2)`), so the HLG
branch really does run. The reference-white normalization is a pure GAIN, and **the app-side AE loop
meters the GL preview luma** — so a brighter preview makes the loop pull exposure down by the same
factor and the RECORDED file lands back at the same luma distribution. A gain change is invisible to
a recorded-luma A/B under active AE, while the earlier BT.1886→HLG swap was a curve-SHAPE change and
therefore partly survived. That single fact explains every "identical output" result above.

So there are now TWO independent reasons a scene A/B cannot measure this pipeline, and both bit:
1. **AE compensation** silently cancels any gain-like change (above).
2. **Transfer persistence** restored a different `ColorTransfer` across app restarts, so two
   comparisons silently pitted SLOG3 against SLOG3_CINE.
A third attempt using MANUAL exposure to defeat (1) failed for a mundane reason worth noting: the
locked ISO/shutter were far under for the scene and both clips came back essentially black.

Next step is a KNOWN-VALUE test, not another scene A/B: render a synthetic ramp through the shader
(or read the analysis FBO with a fixed input) and compare against the CPU reference in
`SourceLinearHlgTest`, so the transform is checked in isolation from camera behaviour, AE drift, and
the transfer-persistence problem that confounded three separate device comparisons here — the app
restored a different `ColorTransfer` across restarts every time, so two of these A/Bs silently
compared SLOG3 against SLOG3_CINE.

A CORRECTION to the 2026-07-29 measurement recorded earlier: the original 8-bit vs 10-bit A/B used
`SLOG3` for one clip and `SLOG3_CINE` for the other — different gamut matrices — so the difference
it showed was confounded and is NOT evidence that the sampler yields HLG. That claim is withdrawn.

### Stock app colour modes side by side — CAPTURED LIVE 2026-07-28

Switched the stock app through its video colour modes with `dumpsys media.camera` captured at each,
and decoded the main 4K stream:

| Stock mode | main 4K format | dynamic-range profile | dataspace |
|---|---|---|---|
| HDR **off** (Rec.709 / SDR) | `YUV_420_888` — **8-bit** | `0x1` STANDARD | `0x10c10000` = BT709 \| SMPTE_170M \| **LIMITED** |
| HDR **on** (Rec.2020) | `YCBCR_P010` — **10-bit** | `0x40` **DOLBY_VISION_10B_HDR_OEM** | `0x12060000` = BT2020 \| **HLG** \| LIMITED |
| **O-Log2** | `YCBCR_P010` — **10-bit** | `0x1` **STANDARD** | `0x8c60000` = BT2020 \| SMPTE_170M \| **FULL** |

**The specific way log works, and why it is closed to us, is now exact.** Log is a **10-bit P010
buffer paired with the STANDARD (SDR) dynamic-range profile**, carrying a full-range BT.2020
dataspace whose transfer is a deliberate SDR placeholder. That pairing is the whole trick: it asks
the HAL for ten bits WITHOUT asking for an HDR transfer, then labels the result itself.

Public Camera2 cannot express it. Bit depth is not an independent axis there — ten bits are obtained
by requesting a 10-bit `DynamicRangeProfile` (HLG10/HDR10/DV), and that profile then DETERMINES the
dataspace. There is no public way to say "10-bit pixels, SDR profile, my own BT.2020 full-range
tag". Combined with the three walls already recorded (P010 absent from
`availableStreamConfigurations`, dataspace not settable outside native, vendor log key inert for
third parties), this closes the question: the stock log path is structurally privileged, not merely
undocumented.

Incidental confirmation: the stock app's HDR-on video really is **Dolby Vision** (profile `0x40`),
matching the 2026-07-26 `DolbyVisionProbeTest` finding that `c2.qti.dv.encoder` is visible and
MediaMuxer accepts a DV track — and its base layer is HLG, exactly as the probe reported.

### How the STOCK app gets its log stream — ANSWERED 2026-07-28 (and why we cannot copy it)

Its live `configure_streams` (captured earlier via `dumpsys media.camera` in `4K·30·O-Log2`):

```
Stream[0]: 1920x1080  format 0x7fa30c09  dataspace 0x8c60000  Dynamic Range Profile: 0x1
Stream[1]: 3840x2160  format 0x36        dataspace 0x8c60000  Dynamic Range Profile: 0x1
```

The mechanism, decoded:
1. **`format 0x36` is `HAL_PIXEL_FORMAT_YCBCR_P010` — a 10-BIT buffer.** So the stock app does get
   ten bits, but NOT the way a third party would: `Dynamic Range Profile: 0x1` is **STANDARD**, not
   HLG10. It takes 10-bit PIXELS while declaring an SDR profile.
2. **`dataspace 0x8c60000` = `STANDARD_BT2020 | TRANSFER_SMPTE_170M | RANGE_FULL`** — BT.2020
   primaries, full range, and a placeholder SDR transfer. That is the container tagging its log
   signal travels under; the transfer is a placeholder precisely because "O-Log2" has no standard
   transfer code.
3. The ISP writes log-encoded data into that buffer, driven by the vendor mode key.

**Why this is not reachable from public Camera2 — three independent walls, any one of which is
fatal:**
- **P010 is not advertised.** `availableStreamConfigurations` on this device contains no `0x36`
  entry at all, so an `ImageReader` at `ImageFormat.YCBCR_P010` is not an offered configuration for
  these cameras. The stock app is configuring a stream the public map does not list.
- **Dataspace is not ours to set.** Publicly it FOLLOWS the DynamicRangeProfile; stamping an
  arbitrary one is `ANativeWindow_setBuffersDataSpace`, a native call, not a Camera2 operation.
- **The vendor log key is inert for us.** Already device-tested (2026-07-09): accepted and
  "applied", changes nothing a third-party session can see. Consistent with the stock package
  holding **`com.oplus.permission.safe.CAMERA`**, a signature-level privileged permission we cannot
  obtain.

**What IS ours:** `availableDynamicRangeProfilesMap` advertises profiles 2/4/8 (HLG10 / HDR10 /
HDR10+), and the HLG10 route is device-proven to configure and to produce real Main 10 output (see
the entry below). That is a genuinely different mechanism from the stock app's — it yields 10-bit
PRECISION on the same display-referred source, not the ISP's scene-referred log. Nothing found here
changes the honesty position: our log profiles remain GL-baked curves on a tone-mapped signal.

### 10-bit + log — MEASURED 2026-07-28, and what it does and does not buy

Arming the debug 10-bit gate (`tenBitExperimentEnabled`, the `nativelog` flag file) configured a
**genuine HLG10 session first try**: `Session configured (fallback=0, hlg=true, jpeg=false,
raw=false)`. A clip recorded on it with a log profile came out **HEVC Main 10, `yuv420p10le`,
`color_primaries=bt2020`, full range** — i.e. the pipeline really is 10-bit end to end, and the log
curve really is applied on top of it.

What it buys: PRECISION. Ten bits of code value across the log curve instead of eight, so the flat
image banding-resists a grade far better.

What it does NOT buy, and must never be claimed: **latitude**. The stream is still the ISP's
DISPLAY-REFERRED output — already tone-mapped, highlights already rolled off or clipped. More bits
subdivide the same range; they do not extend it. And **there is no linear input to be had**: public
Camera2 hands out transfer-encoded video only (SDR, or HLG/PQ under a 10-bit profile). Scene-referred
linear exists for STILLS as RAW/DNG and has no video equivalent here.

One real correctness gap before this could ship: the GL shaders decode the source as **BT.1886 2.4**
(an SDR assumption). Feed them an HLG10 stream and that decode is simply wrong, so the log curve
would sit on a mis-linearised signal. Shipping the 10-bit path therefore needs a source-transfer-aware
decode, not just the session flag. Left DEBUG-gated for that reason — and because the armed flag also
drops JPEG/RAW (the attempt-0 rung is video-only), which is not a shipping trade.

### Front camera — FULL DEVICE PASS 2026-07-28 (except the two AE-headroom tap axes)

A dedicated front-route sweep. Everything that does not depend on AE having headroom passed:

| Behaviour | Result |
|---|---|
| Pseudo-ZSL (`997b5b7`) | **Serving** — `ShutterLag: ZSL served buffered frame, age 167 ms`, then `images+result +0 ms` on the next shot |
| STILL mirror truth | **Correct** — preview and file are horizontal mirrors of each other (foil square left→right, pole right→left, curtain right→left). Preview shows the selfie mirror; the FILE carries the true scene. Covers HEIF and JPEG in one capture (`outputs=heic,jpg`) |
| VIDEO mirror truth | **Correct** — "LG"/"WHISEN" read unreversed in a pulled clip (A2) |
| Still rotation, portrait | **Upright**, `Orientation = 1`, 3072×4096 portrait |
| EXIF identity | `Make=OPPO`, `Model=PMA110`, **`LensModel = "OPPO PMA110 front camera 21mm f/2.4"`** — the front route and its MEASURED 21 mm equivalent, from `DeviceExifLabels`, no literals |
| GPS | **Absent** from IFD0 and ExifIFD, as designed |
| RAW gating | **Correctly unavailable** — DNG greyed with "RAW unavailable" |
| Flash | **Correctly absent** — no flash control in Exposure or Fn (front has no LED), which is also why front HAL AE exists only in VIDEO |
| TELE door | **Correctly not offered** — the TELE control is absent from the top bar on FRONT, not merely refused; `tele=false effZoom=1.0` |
| Lens presets | **Correctly absent** — no zoom preset pills on the front route |
| Multi-format capture | HEIF + JPEG both written from one shutter press |

**Vertical (rotation-term) axis — ATTEMPTED 2026-07-28 with the phone upright and AE off its rails
(`iso=4681`, range 100–16000). INCONCLUSIVE, and the inconclusive result is the important part.**

A two-point vertical pair chosen so that the correct and inverted mappings predict OPPOSITE ISO
orderings (P1 y=1240 luma 108, whose vertical mirror is bright ~167; P2 y=1880 luma 161, whose mirror
is dim ~123) returned, under SPOT metering, 3/3 consistent `P1→~4380, P2→4831` — i.e. tapping the
BRIGHT point RAISED ISO, matching the INVERTED prediction. Two controls then dismantled it:

1. **Order was refuted, correctly** — reversing the pair kept the values attached to the POINTS
   (P2→4831 first or second; P1→~4380 either way), so the effect was position-dependent and real-looking.
2. **A four-corner map killed it.** Corners spanning a 1.6× brightness range (TL 79, TR 127, BL 98,
   BR 115) returned iso 4413 / 4347 / 4347 / 4356 — a **1.5% spread, i.e. essentially no AE response
   to the region at all**, and the earlier 10.8% spread did not reproduce.

So the front route's AE-region response is too weak and too unstable in this scene to determine the
tap mapping. **No defect is claimed and no fix was made**; a 2-point comparison on this route can
manufacture either answer. Any future attempt must include the corner map as a sanity gate — if the
corners do not separate, the run cannot conclude anything. This also means the shipped harness's
2-point design is only trustworthy when its response check passes by a wide margin, which is why its
`MIN_ISO_RESPONSE` gate exists.

**Note on the A1 PASS**: it stands as recorded — 3/3 reproducible at an 11.6% spread under a
different, brighter regime — but the weak-region behaviour observed here is a caveat worth carrying,
not a refutation.

**Not closed: the two tap-axis checks, both needing AE headroom.** The horizontal (mirror) axis PASSED
earlier at 19:29 when the room light was on (3/3, bright `iso=1316` vs dim `iso=1488`). By 19:47 the
light was off again: HAL AE converged but sat at `iso=16000` — its exact ceiling — and mirrored taps
on a 153-vs-111 luma gradient both returned `iso=16000`, i.e. no response in either direction. Note
that being railed at MAX ISO would still leave headroom DOWNWARD, so the attempt was legitimate; the
scene was simply too dim for even its bright region to pull ISO off the ceiling. The VERTICAL
(rotation-term) axis has therefore still never been exercised — it is the one remaining front unknown,
and `viewTapToSensorPoint` documents itself as approximate on that term.

### Loupe Overview "inverted in TELE" — NOT REPRODUCIBLE; inversion is structurally impossible (2026-07-28)

Reported as "Loupe view should not be inverted when TELE mode is on." Investigated to a conclusion:
**the overview cannot be inverted relative to the main view, by construction**, so there is no code
change that would be correct here.

Three independent proofs:

1. **One rotation field, two draws.** `FlipRenderer.rotationDeg` is a private field set only by
   `setRotationDegrees` and consumed inside `draw` (`Matrix.rotateM(rot, 0, rotationDeg + stabRollDeg,
   …)`). Rotation is NOT a `draw` parameter. The main preview draw and the finder PIP draw are two
   calls to the SAME renderer instance in the SAME frame with the SAME `stMatrix`; only the viewport
   and `zoomComp` differ. They therefore carry identical orientation. (`coverScaleInto` does take the
   box size, but that selects cover scaling for the smaller box — not rotation.)
2. **The flip gates on the CONVERTER, not the lens.** `previewRotationDegrees()` is
   `RotationMath.previewRotationDegrees(teleconverterMode)` — the user's TELE declaration that glass
   is mounted, not "the 70 mm lens is selected". So the 180° is applied exactly when the user says
   the afocal optic is in the path.
3. **The framing hint tracks correctly IN TELE (device-measured).** With TELE engaged and the loupe
   active, the untapped hint sits centred in the overview box (measured centre (257, 2020) against a
   box centre of ~(255, 2010)). Tapping the UPPER-LEFT of the main view moved it to (219, 1942):
   Δx −38, Δy −78, i.e. up-and-left — the hint follows the tap's SCREEN direction, which is the
   correct result once both views carry the same 180°. This exercises the `rotationDegrees` term that
   an earlier non-TELE bisection left at 0 and never tested.

**Most likely explanation of the observation: TELE engaged with the converter NOT physically
mounted.** The app then corrects an inversion that is not happening, so the whole frame — main view
AND overview — reads upside down. The overview is where that is obvious, because it shows the entire
scene, while a 13×+ punched-in main view is often ambiguous texture. Un-inverting only the overview
would make it upside down in real, converter-mounted use, so it must not be done.

Remaining (needs the phone aimed at a real scene, cannot be done over ADB): with the 300 mm
converter mounted and TELE on, confirm visually that the corner overview and the main view show the
same scene the same way up. Everything checkable without a scene is checked.

### Permissions + location EXIF — AUDITED on a fresh install (2026-07-28)

Exercised every first-launch permission path on a genuinely clean package (`pm revoke` is refused on
ColorOS exactly like `pm grant`, so the only honest way to reach virgin permission state is
uninstall + reinstall). **Zero crashes and zero errors in every path; `logcat -b crash` stayed empty
throughout.**

| Path | Result |
|---|---|
| Fresh launch, nothing granted | Requests CAMERA immediately; no error |
| CAMERA denied once | `PermissionGate`: "Camera access required." + a working re-request |
| CAMERA denied twice | Flips to "Enable camera access in Settings."; button opens `InstalledAppDetails` |
| CAMERA granted, mic denied | Preview + session `fallback=0`; standby meter correctly never starts |
| REC with mic denied | Rationale sheet → decline → **records video-only** (fixed, see below) |
| Location | Never requested — not a permission the app has |

**One real defect found and fixed** (`06fc6d1`): declining the microphone dropped the REC press
entirely. `VideoRecorder` already records video-only without RECORD_AUDIO, so the refusal withheld a
take the pipeline can deliver, and it stranded anyone who never wants audio — turning `recordAudio`
off is what makes the *next* press skip the prompt, so a decline was a two-press ritual whose first
press vanished behind a transient status line. Now a declined `START_RECORDING` disables audio and
starts the take; both decisions live in the pure `CameraPermissionPolicy` seam
(`microphoneDeclineOutcome`, `microphonePermissionRequired`, 13 unit tests). Device-verified:
`RecordingSpec: admitted ... audio=false`, `MUTE` in the OSD, and a 30.3 s HEVC 2160×3840 clip with
exactly one stream and no audio track.

**Location EXIF: there is none, by construction.** The built APK's merged manifest requests exactly
`CAMERA` and `RECORD_AUDIO` (`INTERNET` is stripped with `tools:node="remove"`), no source file
references any location API, and parsing a saved HEIF's TIFF IFDs found **no GPS IFD pointer in
IFD0 or ExifIFD**. Captures carry `Make`/`Model`/`DateTime`/`Orientation`/`ISO`/`ExposureTime`/
`FocalLengthIn35mmFilm`/`LensModel` and nothing locational. So location cannot be "inaccessible" and
cannot error. Adding geotagging later would be a new permission, a new Data Safety declaration, and
a privacy-policy change — treat it as a feature, not a fix.

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

**Runnable checklist: [`docs/FIELD_CHECKS.md`](FIELD_CHECKS.md)** — the hardware-dependent ones,
grouped by setup with exact commands and pass criteria (~15 min for all).

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
  (one direction; the other follows algebraically). **CLOSED 2026-07-29** — the last residual was
  a held-landscape VIDEO clip in an external gallery; `RotationMath.videoOrientationHint` carries the
  same device-confirmed −dev/+dev term, and the operator ran the container-hint playback check
  (FIELD_CHECKS B1) and reported all three held poses upright. Rotation is closed end to end.
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
    `RotationMath.videoOrientationHint` carries the same term, and its EXTERNAL-PLAYER playback
    check is now CLOSED as well (operator, 2026-07-29 — FIELD_CHECKS B1).
  - **Front VIDEO file mirror truth — RESOLVED 2026-07-28.** Front STILL mirror truth was
    device-verified 2026-07-23; the CLIP had never been checked. Now it has: an 8.1 s front clip
    (HEVC, 2160×3840 portrait, `audio=false` via the declined-mic path) was pulled and a frame
    extracted. **"LG" and "WHISEN" on the air-conditioner read normally — not reversed.** The frame
    is also horizontally flipped relative to the live preview (light source moves left→right, smoke
    detector right, wall pole left), which is exactly the design: the preview shows the selfie
    mirror, the FILE carries the true scene. The encoder un-mirror path is therefore confirmed for
    video, not just stills.
  - **Front tap-AF aim — RESOLVED 2026-07-28. Fix `7cda8da` is DEVICE-CONFIRMED.** Once the room
    light came on (front-camera ISO fell 16000 → ~1300, i.e. AE finally off its ceiling), the harness
    ran clean **3/3 with identical values**: scene left 147–156 luma vs right 123–133, and tapping
    the BRIGHT side settled at `iso=1316` against `iso=1488` for the dim side. Metering the brighter
    half pulled exposure DOWN, so the AE region lands on the tapped half and not its horizontal
    mirror. `FrontMirrorConvention.meteringMirrorX` has the correct sign. The rotation term is still
    uncalibrated on the front route (see below) — that would be a separate finding.
    Historical detail of the fix and the blocked attempts follows.

  - **(superseded) Front tap-AF aim — FIXED IN CODE 2026-07-28 (`7cda8da`); the aim check is what remains.**
    Cycle-6 probe: the front camera (id "1") ADVERTISES `android.control.maxRegions =
    [AE=1, AWB=0, AF=1]`, so tap-AF/AE regions are live on the front route. Debugger F2 was RIGHT:
    `mapTapFocusGeometry` used ONE `mirrorX` for two different questions, and it was pinned false.
    The loupe consumes TEXTURE space, which the pre-mirrored stream matches 1:1 (no flip — that half
    was correct); metering regions are ACTIVE-ARRAY coordinates, and the array holds the TRUE scene
    while the preview shows it mirrored, so the metering half needed the `1−nx` un-flip and never
    got it. `FrontMirrorConvention.meteringMirrorX` now supplies it, equal to `encoderDrawMirrorX`
    (both convert "what is shown" into "what is true"), applied in DISPLAY space before the rotation
    into array coordinates — where the device-verified encoder un-mirror acts. Rear is untouched;
    four unit tests pin the split and its independence.
    STILL NEEDS A HUMAN: tap a subject near the LEFT edge of the selfie preview against a
    depth-separated background and confirm focus/exposure now drives from the tapped subject. Note
    the tap mapping's ROTATION term is still uncalibrated on the front route (`viewTapToSensorPoint`
    documents itself as approximate), so a residual vertical/axis error would be a SEPARATE finding
    from the mirror.
    **A remote attempt was made 2026-07-28 and is INCONCLUSIVE — do not repeat it in these
    conditions.** With the phone face-down the front camera did see a lit ceiling carrying a usable
    left-bright/right-dim gradient (~140 vs ~116 luma), so mirrored tap pairs were tried in VIDEO
    mode (where HAL AE, not the app-side loop, owns exposure and therefore honours AE regions).
    Both taps returned `iso=16000` unchanged, because that sensor's advertised
    `sensitivityRange` is `[100, 16000]` and the video FPS pin holds `expNs` at 1/30 s: **AE was
    railed against both limits and had no freedom to respond to any region.** That is a property of
    the light, not evidence about the mirror. A valid run needs enough light for AE to sit off its
    ceiling, plus real depth separation.
    **The check is now one command: `tools/field/tap_af_aim.py --serial <serial>`.** Aim at a scene
    clearly brighter on one side, put the app in VIDEO + PROGRAM, run it. It taps a mirrored pair and
    prints PASS / FAIL / INCONCLUSIVE, and it REFUSES a verdict unless it first proves the run can
    produce one — photo mode (app-side AE ignores HAL regions), a railed meter, too-even a scene, or
    a meter that barely moved each exit 2 with the reason. Self-verified against the railed state
    above, where it reports exactly that.
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

- **R8/minify — NO LONGER DEFERRED (enabled 2026-08-04).** Kept here as a pointer because this is
  where the blocker lived for the whole of v1. Google Play's "app is not optimized" recommended
  action forced the question and BOTH exit criteria named above were met, so `isMinifyEnabled = true`
  now ships: the SettingsStore enum keep rule in `app/proguard-rules.pro` is LIVE (it was staged and
  commented), and the physical-device release pass ran on the PMA110. Full evidence lives in
  `docs/play-console-submit.md` under the minified-build entry — 316 enum constants across 82 enum
  classes with ZERO renamed while the enum classes themselves were still obfuscated, a `CaptureMode`
  round trip across `force-stop`, HEIF+DNG and a 4K HEVC/AAC clip written by the minified binary, and
  DEX 46.67 MB → 2.48 MB. The "reflection-sensitive OEM SDK paths" half of the original blocker had
  already retired with the `com.oplus.ocs` removal on 2026-07-25. **`isShrinkResources` stays OFF**
  and is a separate follow-up whose audit is already done: there is no `Resources.getIdentifier`
  anywhere in `app/src/main/kotlin`, so it can be flipped whenever the extra size win is wanted.
- **Large-screen orientation/resizability — DONE 2026-08-04, no longer deferred.** Briefly recorded
  here as "declined" earlier the same day; the owner reversed that and the work was carried out, so
  this entry is kept only to correct the record. `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` is
  REMOVED, so a display at sw600dp+ now takes the landscape window Android 16 wants to give it —
  ahead of API 37, which deletes the opt-out outright. `android:screenOrientation="portrait"` STAYS:
  it is still honoured below sw600dp, and PMA110 measures **411 dp** (1440 px ÷ 560 dpi × 160), so
  the phone never rotates and its device-fixed layout is untouched.
  - **What carries the window term:** preview rotation and displayed aspect
    (`RotationMath.windowPreviewRotationDegrees` / `displayedPreviewAspect`, applied through
    `FlipRenderer.draw`'s per-call `rotationOverrideDeg`), tap mapping (`unrotateViewPoint` inside
    `mapTapFocusGeometry`), and glyph counter-rotation (`glyphRotationDegrees`). Each is proven inert
    at `ROTATION_0` by a unit test — that degeneracy IS the phone's regression fence.
  - **What deliberately does NOT:** capture masks and encoder framing stay GRAVITY-derived, so a
    still or clip records the same field however the window is turned. Do not "fix" them to follow
    window shape; that re-opens the cycle-4 overscan bug.
  - **Layout:** a wide window gets the Sony-style operator rail (`landscapeOperator`, 208 dp), whose
    width is SUBTRACTED from the preview box rather than drawn over it, so no control covers the
    frame. Keyed on window SHAPE, not rotation — split-screen can be wide at `ROTATION_0`.
  - **Device-verified.** TB336ZU (Android 16, 1600×2560), the same tablet that reproduced the
    original problem on 2026-08-02: the rotation sign was BISECTED rather than assumed — the
    preview's brightness asymmetry moved top (dy −2.46) to left (dx −3.64), i.e. 90° CCW, matching
    `windowPreviewRotationDegrees(90) = 270° CW`; preview width in the landscape window went
    1216 → 2560 px, and 2144×1206 with the rail; the portrait window was unchanged at x=64..1536.
    PMA110 regression: window stayed 1440×3168, portrait layout and all four lens presets intact,
    HEIF 1.5 MB + DNG 25 MB with the still UPRIGHT, and a 10.4 s clip at HEVC 2160×3840 / 29.92 fps
    + AAC 48 kHz stereo. Zero `FATAL EXCEPTION` on either device.
  - **Known-good caveat:** after an accidental ~2-minute 4K recording the PMA110 session came back
    with every control dimmed and refused a REC press. A force-stop/relaunch cleared it and the
    clip above recorded normally. Not attributable to this change (which is inert at `ROTATION_0`),
    but worth knowing before chasing it as a new defect.
  - **Play Console will still flag this.** Its check reads the `screenOrientation` attribute, which
    is deliberately retained; the recommendation is advisory and does not block publishing.
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
