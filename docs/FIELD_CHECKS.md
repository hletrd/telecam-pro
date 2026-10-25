# Field checks

The verifications that need a device in your hands, plus the device checks a host test cannot
close. This ledger is exhaustive: every open device claim in `CLAUDE.md` or `docs/ARCHITECTURE.md`
has an entry here, including the two (D2, E4) that are checkable over ADB but have not been run.
A change whose device effect has NO field procedure is listed too (section F, `⊘ HOST-ONLY`), so
its absence from the open list is a recorded fact rather than an omission.

Grouped so you change the setup as little as possible. Each is: **set up → run → what a pass looks
like.**

**Status (2026-10-02):** A1 ✅ · A2 ✅ · A3 ◐ · A4 ☐ · A5 ☐ · A6 ☐ · A7 ☐ · A8 ☐ · A9 ☐ · A10 ☐ · A11 ☐ · A12 ☐ · A13 ☐ · A14 ☐ · A15 ☐ · A16 ☐ · A17 ☐ · A18 ✅ · A19 ◐ · B1 ✅ · C1 ✅ · C2 ✅ · C3 ✅ · D1 ☐ · D2 ☐ · D3 ☐ · D4 ☐ · D5 ☐ · D6 ◐ · D7 ☐ · D8 ☐ · E1 ☐ · E2 ☐ · E3 ☐ · E4 ☐ · F1 ⊘ · F2 ⊘ · F3 ⊘ · F4 ⊘ · F5 ⊘ · F6 ⊘ · F7 ⊘ · F8 ⊘ · F9 ⊘.
Twenty-eight remain: **A3** needs the rear camera pointed at a lit room, **A4** needs a rotatable
large-screen front route, **A5** needs a sustained front pseudo-ZSL soak, **A6** needs a photo-P TELE
capture pair, a FrameGap read during a pinch, and an OWNER DECISION on the exposure delta it records,
**A7** needs one JPEG+HEIF still pair, **A8** needs a `dumpsys` YUV list beside a pulled still,
**A9** needs a pause (and a fast return) during cold start, **A10** needs zoom readouts on PMA110 and
both tablets, **A11** needs a Video→Photo→FRONT→Video trip and a clip's frame rate, **A12** needs a
few FRONT presses with DNG on, **A13** needs the status plate watched through reopens and a mic-less
REC press, **A14** needs one pulled DNG per device, **A15** needs a native-log REC take with a still
inside it, **A16** needs a face in front of the rear, TELE and front routes with Face Priority AF on,
**A17** needs a bright native-log scene recorded at Log EI offset 0 and +2 in P and M,
**A19** needs handheld EIS takes (bare and TELE), the immutable-APK run, a colour chart, a thermal soak and a clipped-highlight scene,
**D1** needs the logcat readback on the current build and then an off-axis sound source,
**D2** needs REC takes on the TB336ZU tablet, **D3** needs a short run of ordinary clips through the
live publication tail, **D4** needs quick REC/Stop pairs with the standby meter showing, **D5** needs a
run of ordinary clips plus one more take, **D6** needs one HE-AAC clap take, **D7** needs the
four soundtrack formats imported into the editors you use, **D8** needs finger snaps left and right
with the phone held in each landscape, and **E1/E2/E3/E4** need real MediaProvider ownership,
system-consent, reset/reindex, and pending-expiry behavior. B1 closed the rotation
work end to end; C1 confirmed the afocal
correction against real converter glass. C3 is closed as an honest no-observable-difference result,
not proof of a distinct teleconverter OIS profile.

Install the exact immutable debug build first (the release strips the diagnostic logs these rely
on). The ordinary Gradle APK is developer-only and cannot support a field-evidence claim:

Complete `README.md` § **Android SDK setup** first; the wrapper runs that same SDK preflight.

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
BUILD_RESULT="$(python3 tools/build_immutable_debug.py)"
printf '%s\n' "$BUILD_RESULT"
EVIDENCE_APK="${BUILD_RESULT##* apk=}"
test -n "$EVIDENCE_APK" && test -f "$EVIDENCE_APK"
adb connect <phone-ip>:<port>
adb install -r -t "$EVIDENCE_APK"
```

---

## A. Indoors, lit room, no converter — 5 min

### A1. Front tap-AF aim — ✅ PASSED 2026-07-28

**Done. Fix `7cda8da` is device-confirmed.** With the room light on (front ISO fell 16000 → ~1300),
the harness ran 3/3 with identical values: bright side `iso=1316`, dim side `iso=1488` — metering
the brighter half pulled exposure down, so the region lands on the tapped half. Re-run below only if
that mapping changes.

The metering mirror was fixed in `7cda8da`. An earlier attempt was inconclusive because AE was
railed at max ISO in a dark room — so **light matters here.**

- Aim the **front** camera at something clearly brighter on one side (a window wall beside a shaded
  one works well). Set **VIDEO** mode, exposure **PROGRAM**.
- **Light matters more than it looks.** Video pins the frame rate, so exposure cannot exceed
  ~1/30 s; in a dim room AE rails against its ISO ceiling and can no longer respond to any
  region. Photo mode lifts that pin but only earns HAL AE with flash AUTO/ON — and the front
  camera advertises no flash — so for the front route, add light rather than changing mode.
- Run: `tools/field/tap_af_aim.py --serial <serial>`

**Pass:** prints `PASS — tapping the bright side lowered ISO`.
**Fail:** prints `FAIL — … landing on the horizontal mirror of the tap` → the sign in
`FrontMirrorConvention.meteringMirrorX` is inverted.
**Exit 2** is not a failure — it means the run couldn't produce a valid answer and says which
precondition missed (railed meter, too-even scene, wrong mode). Fix that and re-run; don't record an
exit-2 as either result.

> Only proves the horizontal half. The tap mapping's **rotation** term is still uncalibrated on the
> front route, so a vertical/axis error would survive a PASS; A4 owns that residual explicitly.

### A2. Front video mirror truth — ✅ PASSED 2026-07-28

**Done.** An 8.1 s front clip was pulled and a frame extracted: "LG" and "WHISEN" on the
air-conditioner read normally, and the frame is horizontally flipped relative to the preview —
preview shows the selfie mirror, the file carries the true scene, as designed.

Front stills were confirmed unreversed on device; the front *clip* had never been checked.

- Front camera, record ~5 s of **legible text** (a book cover, a screen).
- Pull it and play it in an external player.

**Pass:** the text reads normally — not mirror-reversed. (The preview showing it mirrored is correct;
that's the selfie view. The **file** must carry the true scene.)

### A3. P-mode brightness — ◐ HALF DONE 2026-07-28

**Stability half passed:** rear PHOTO/PROGRAM held `iso=9100 expNs=66666667` dead steady across
16 s — zero breathing or hunting at rest, which is the half that catches loop defects.
**Brightness half still open:** the phone was face-up, so the REAR lens faced a dark desk and AE sat
railed at its ISO ceiling; a railed meter cannot demonstrate a sensible brightness target. Re-run
with the rear camera actually pointed at the lit room.

- Rear camera, **PHOTO**, exposure **PROGRAM**, ordinary room light — pointed AT the room.

**Pass:** the image settles at a sensible brightness within ~2 s and then sits still — no slow
breathing or hunting at rest.

### A4. Front tap-AF window-rotation axis — ◯ OPEN 2026-08-24

A1 proves the front route's horizontal mirror term in the portrait-locked PMA110 window. It cannot
prove the window-rotation term because that handset stays at `ROTATION_0`. Use a rotatable sw600dp+
device with an enumerated front camera and a scene whose bright target is unambiguous on both axes.

- Rotate the window to 90° and 270°, enter front VIDEO/PROGRAM, and tap the bright target well away
  from the centre and diagonal symmetry axes.
- Observe the metering region/result with the same fixed-exposure/ISO directionality discipline as
  A1; do not infer a pass from a reticle that merely draws under the finger.

**Pass:** in both window rotations, the applied front metering region lands on the displayed target
and the exposure response follows that target rather than its quarter-turned counterpart. Record
device model, window rotation, displayed target quadrant, applied region, and exposure response.

### A5. Front pseudo-ZSL sustained idle and memory pressure — ◯ OPEN 2026-08-24

The front route has one-shot capture-latency evidence, but not the logical route's sustained
full-resolution repeating-YUV soak. This check owns that evidence gap; do not generalize the rear
S4a result to a different camera/session.

- Record the immutable debug APK path, source commit/tree, device model, Android build, and the
  front camera's advertised largest-YUV minimum frame duration.
- Select front PHOTO, SINGLE drive, processed still output, and leave the viewfinder running for
  10 minutes in an ordinary lit scene. Capture delivered frame cadence and every bounded `FrameGap`
  summary (`count`, `maxMs`, and the 200–399/400–999/≥1000 ms buckets), plus camera errors,
  `dumpsys meminfo`/gralloc observations, and battery temperature before/after. The first summary is
  immediate; continuing gaps summarize at most every 15 s and the GL generation emits any terminal
  remainder, so absence of per-frame rows is the quota-safe evidence format rather than missing data.
- During a second bounded run, create ordinary memory pressure by switching among other apps and
  returning to TeleCam Pro; do not kill the camera process or infer success from session configure.
- Take a front photo after each soak and verify capture completion plus a valid published file.

**Pass:** the front finder sustains its advertised/selected cadence without recurring >200 ms gaps
or camera errors, memory/gralloc use stays bounded without growing across the soak/return cycle,
thermal change is recorded rather than guessed, and the post-soak still completes and publishes.
Record the exact measurements even when they pass. A failure keeps pseudo-ZSL disabled for that
route/device until measured admission evidence exists.

### A6. Photo-P handheld shutter follows the effective focal — ◯ OPEN 2026-10-02 (owner decision)

Cycle 4 (A.20) made photo PROGRAM's handheld target 1/(EFFECTIVE focal): one `effectiveEquivFocalMm`
now feeds the OSD focal, the program line, and the focus-detail exposure gate. Two things changed at
once, and the second was not recorded when this entry was first written:

1. **A zoom term.** Inside a lens band the target used to stay at the band's preset focal; it now
   follows zoom. With TC on it is `teleconverterFocalMm × zoom`.
2. **A measured focal instead of the nominal one.** Without TC the base is the opened camera's
   MEASURED 35 mm equivalent (`caps.equivalentFocalMm`), not the preset's nominal 14/23/70/230 mm. So
   PMA110 is not byte-identical even at the preset points, including the launch-default logical 1×.

**The full PMA110 delta, before → after** (photo, app-side P, flash off; EV < 0 means a faster
shutter, so ISO rises by the same amount while ISO is unrailed; measured logical equivalent 23.4 mm,
with an earlier run at 23.0 mm; standalone 3× lens 69.4 mm; kit converter 300/70):

| PMA110 state | before | after (23.4 mm logical) | after (23.0 mm logical) | Δ |
|---|---|---|---|---|
| logical, 0.6× preset | 71.43 ms (1/14) | 71.23 ms | 72.46 ms | ±0.02 EV |
| logical, 1× (launch default) | 43.48 ms (1/23) | 42.74 ms | 43.48 ms | −0.025 EV / 0 |
| logical, 2× pinch | 43.48 ms | 21.37 ms | 21.74 ms | −1.0 EV |
| logical, 2.9× (top of the main band) | 43.48 ms | 14.74 ms | 14.99 ms | −1.56 EV |
| logical, 3× preset | 14.29 ms (1/70) | 14.25 ms | 14.49 ms | ±0.02 EV |
| logical, 5× | 14.29 ms | 8.55 ms | 8.70 ms | −0.74 EV |
| logical, 9.9× (top of the 3× band) | 14.29 ms | 4.32 ms | 4.39 ms | −1.73 EV |
| logical, 10× preset | 4.348 ms (1/230) | 4.274 ms | 4.348 ms | −0.02 EV / 0 |
| logical, 20× | 4.348 ms | 2.137 ms | 2.174 ms | −1.0 EV |
| TELE + kit TC, local 1× (300 mm) | 3.333 ms | 3.333 ms | same | 0 (byte-identical) |
| TELE + kit TC, local 2× (600 mm) | 3.333 ms | 1.667 ms | same | −1.0 EV |
| TELE + kit TC, local 4× (1200 mm) | 3.333 ms | 0.833 ms | same | −2.0 EV |
| TELE + kit TC, local ≈4.6× (60× cap, 1380 mm) | 3.333 ms | 0.725 ms | same | −2.2 EV |
| DNG on (standalone 3× lens), local 1× | 14.29 ms | 14.41 ms (69.4 mm) | same | +0.012 EV |
| DNG on (standalone 3× lens), local 2× | 14.29 ms | 7.21 ms | same | −0.99 EV |

The same target bounds the focus-detail `SOFT` gate (exposure ≤ 16 × target, in every mode where the
analysis readback runs). At TC local 4× its ceiling drops from 53.3 ms (16 × 1/300) to 13.3 ms
(16 × 1/1200), so `SOFT` is refused across a 2-stop wider band of preview exposures. That is the
"may miss" direction the detector's contract allows; it creates no false fires.

**Owner decision (AGG5-13, open).** The physics backs the new rule — a digital crop is upsampled,
so angular shake blur in the saved frame scales with the zoom-included focal — but its cost lands on
this app's main use: at TC 3–4.6× in mid light, P now runs 1.6–2.2 stops more ISO. That is a
sharpness-against-noise tradeoff, not a defect, so nothing is reverted until the owner chooses:

- **Keep** the effective-focal rule (current behaviour).
- **Keep the zoom term but restore the preset points:** key the non-TC branch on the nominal preset
  focal × (zoom ÷ preset ratio). Preset positions return to the old values exactly; between presets
  the zoom term stays. Host-testable with a table at 23.4/69.4 mm.
- **Revert** to the band step function (preset focal, no zoom term).

The device run below supplies the evidence for that choice. It also checks a second risk: every
zoom step can re-center the app-side program shutter (≤0.35 stop per loop tick,
brightness-neutral), and each re-center is a sensor fast-path submit that this HAL pays for with a
~180 ms repeating-request swap — the stutter class the zoom work removed (MRG4-5). The continuous
zoom dependence makes that reachable on every logical-route pinch, not only under TC. Whether it
shows up during a gesture has not been measured; do not change the program target for it until it
has.

- Rear camera, **PHOTO**, exposure **PROGRAM**, flash OFF, TELE with the teleconverter declared
  (the glass need not be mounted: the rule reads the declared converter). Ordinary room light, so
  ISO is not railed at either end — record the ISO, because a railed ISO lets the program line slide
  the shutter by design.
- At TC local 1× and again at local 4×, note the OSD focal, let AE settle, take one still, and read
  its EXIF `ExposureTime` / `ISOSpeedRatings` / `FocalLengthIn35mmFilm`.
- Repeat at the logical 1× launch default and at a 2× logical pinch, so the preset-point and zoom-term
  rows above are both observed.
- FrameGap during a pinch: with the debug APK, pinch smoothly from TC local 1× to 4× and back three
  times in PROGRAM, then repeat the same pinches in **MANUAL** (fixed exposure, the control). Keep
  the bounded `FrameGap` summaries (`count`, `maxMs`, 200–399/400–999/≥1000 ms buckets) for each run.
  The line counts only producer gaps strictly longer than 200 ms, so a swap stall that lands at or
  under 200 ms (the low part of the measured 170–250 ms band) is not counted by either run.

**Pass:** the OSD focal and EXIF 35 mm focal agree, and EXIF `ExposureTime` is ≈1/(that focal) at
each zoom (≈4× shorter at local 4× than at 1×) while ISO is unrailed. The PROGRAM pinch shows no
more >200 ms gaps than the MANUAL control. Extra gaps in PROGRAM are a fail that reopens MRG4-5
(hold the program target constant while a zoom gesture is active); record both runs' summaries.
Then record the owner's choice above in this entry.

### A7. Processed-still EXIF parity, JPEG beside HEIF — ◯ OPEN 2026-10-02

Cycle 4 (B.4) moved the JPEG lanes to splice the shot's APP1 EXIF into the encoded buffer before the
single write, instead of rewriting the pending row in place; the HEIF lane hands the same attributes
to `HeifWriter.addExifData`. `CLAUDE.md` states that ISO, exposure, 35 mm focal, make and model stay
in parity across both processed formats. The host test parses spliced bytes with ExifInterface; no
device file from the new splice has been read back.

- Rear camera, **PHOTO**, output formats **HEIF + JPEG** together, so one shutter press writes both
  processed formats for the same capture. Take one still on the logical route (YUV → JPEG encode)
  and one on TELE (HAL JPEG).
- Pull both files of each pair and dump their EXIF (`exiftool` or ExifInterface).
- Where a hi-res route exists (not PMA110, whose hi-res is dormant), also pull one passthrough JPEG.

**Pass:** within each pair `ExposureTime`, `ISOSpeedRatings`, `FocalLengthIn35mmFilm`, `Make` and
`Model` are identical, `Orientation` agrees with the pixels, and both files open in a gallery. A
JPEG with no EXIF at all is the logged build-failure path and is a fail here; record which lane.

### A8. YUV still size on the logical and front routes — ◯ OPEN 2026-10-02

Cycle 4 (A.17) made the YUV still size aspect-first through `pickStillSize`. On PMA110 the logical
array is 4080×3064, and the host table assumes the logical YUV list contains that exact size; that
list is not a recorded device measurement. If it is missing, the aspect-first rule can pick a much
smaller in-aspect size with no error (DB5-17), on every LOGICAL and FRONT still.

- Record the advertised YUV_420_888 output sizes for the logical camera and the front camera
  (`adb shell dumpsys media.camera`, the stream configuration list for each id).
- Take one 4:3 still on the logical 1× route and one on the front camera, and read each saved
  file's pixel dimensions.

**Pass:** each saved still has the largest advertised YUV size of its aspect (4080×3064 on the
logical route if listed). Record both YUV lists verbatim; a saved still smaller than the largest
in-aspect size, or a logical list without 4080×3064, is a fail that reopens DB5-17.

### A9. Pause during cold start keeps a live preview — ◯ OPEN 2026-10-02

Cycle 5 (plan A1.1/A1.2) moves the GL re-seed and the lens-inventory enqueue ahead of the `paused`
gate in the cold-start input-ready callback, gates only route resolve and reconfiguration on
`paused`, and re-binds the retained preview surface on resume (`resumePreviewRebindWanted`) on
either of two independent triggers:

1. **No GL input surface yet** (AGG5-1): the pause landed inside the cold-start window. This is the
   half the steps below exercise.
2. **The preview has not presented a real frame** (`previewReady == false`, AGG5-26): a pause
   dropped a preview-recovery rebind that was in flight. This also means a terminally exhausted
   preview, whose `previewReady` stays false, now re-binds on every resume. Neither can be provoked
   on demand on PMA110, because both need a preview EGL failure first. **This half is host-only**
   (as F7/F8 are): host tests drive the interleaving, and no device step is owed for it.
3. **A resume refused while the cold-start task was finishing** (AGG6-20, cycle 6 plan A1.2): the
   task read `paused` and cleared `starting` outside one monitor, so a `resume()` between the two was
   refused by the still-set `starting` and nothing restarted the camera. Cycle 6 decides `paused` and
   retires `starting` under one monitor, so a resume is never refused in the first place; nothing is
   replayed. This needs a background AND a return inside the cold-start window, which the
   fast-return attempts below aim for.
4. **Which path opens the camera after a return inside the GL input window** (cycle 6 plan
   A1.3/A1.4/A1.5, with the MRG6-1 follow-up). The route now resolves before the paused lens
   enumeration, the input-ready open adopts the live trace owner, and resume skips its own reopen
   while that open is still queued behind it; once the queued open dequeues it clears the claim, so
   a pause that made it refuse cannot also turn resume away. A wrong claim shows as a black
   viewfinder; a missing one shows as a second open of the same camera id.

For trigger 4, also keep the system camera-service lines (`logcat -b all | grep -E
"CameraService::connect|StartupTrace|cold start"`).

The ordinary foreground return has an input surface and a presented preview, so it re-binds
nothing and stays byte-identical. Host tests drive both interleavings; whether a real launch can
land in the trigger-1 window is a device question.

- Force-stop the app. Launch it and press Home (or lock the screen) within roughly half a second,
  before the viewfinder appears. Return to the app. Repeat five times, varying the delay.
- Then five fast-return attempts: launch, press Home, and return at once (for example a script that
  runs `adb shell am start -n <component>`, `adb shell input keyevent KEYCODE_HOME` and the same
  `am start` back to back).
- Keep the debug logcat for each attempt (`CameraController`, `Session configured`, `StartupTrace`).

**Pass:** every return shows a live, upright preview within a couple of seconds, with no black
viewfinder that needs a second background/foreground to recover, and no crash. For trigger 4, each
foreground shows exactly one `CameraService::connect` for the opened camera id (no same-id dual
open), and the `cold start (ms since resume)` line appears once per foreground with a single
`onOpened` mark. Record the delay and outcome of each attempt.

### A10. Zoom readout follows the route's own scale — ◯ OPEN 2026-10-02

Cycle 5 (plan A2.4, with the MRG5-4 follow-up) changed every zoom readout — the pill, the Fn ZOOM
value, the zoom ruler and the Shoot-tab slider, all read through `mainRelativeZoomMultiplier`. The
LOGICAL seamless route now displays 1:1, because its wire zoom is already main-relative; it used to
multiply by the measured 23.4 mm ÷ the nominal 23 mm and read "3.1×" at the 3× preset. A standalone
route divides by the nominal 23 mm while the measured main is within the 10 % caption band, and by
the measured main otherwise. This is a PMA110-visible change, and the tablet readings recorded in
`CLAUDE.md` predate it. The host tables cover 23.4 mm and 26 mm mains; no device readout has been
taken.

- PMA110, rear **PHOTO**, DNG off (logical route). Tap the 0.6×, 1×, 3× and 10× presets and note the
  pill, the Fn ZOOM value and the ruler at each.
- Turn DNG on (standalone routes) and repeat at 1×, 3× and 10×.
- On the TB336ZU and the TB331FC, rear PHOTO at the 1× preset (and 3× zoom where the rail offers it).

**Pass:** PMA110 reads 0.6× / 1.0× / 3.0× / 10.0× on the logical route and 1.0× / 3.0× / 10.0× on
the standalone routes, with the pill, Fn value and ruler agreeing at every point. Each tablet's main
lens reads 1.0× (no longer 1.1×). Record each surface's reading; any surface that disagrees with the
pill is a fail.

### A11. Stabilization and frame-rate requests survive non-recording routes — ◯ OPEN 2026-10-02

Cycle 5 (plan A2.1, with the MRG5 follow-ups `dd3edc64` and `3489859e`) separated the operator's
video-stabilization and frame-rate REQUEST from the value a route can display. Photo-logical and
FRONT routes that lack Active stabilization or the higher frame rate narrow only what they show and
send; they no longer write the narrowed value back into the persisted request. The host tests feed
caps without stabilization mode 2 or 60 fps and then the tele Video caps. The device-visible effect —
the tele Video route records with Active stabilization and the higher frame rate again after such a
trip — has not been observed.

- PMA110, rear **VIDEO** on a route that offers Active stabilization and a frame rate above 30 fps
  (TELE, for example). Select Active and the highest frame rate offered, and note both rows.
- Switch to **PHOTO** (logical route), then to the FRONT camera, then back to the rear, then back to
  **VIDEO** on the same route. Repeat once with a background/foreground in the middle and once with
  an app relaunch (Remember Settings on).
- Record a ~5 s clip after each return and read its frame rate (`ffprobe` or `MediaExtractor`); keep
  the debug logcat result line that reports `vstab`.

**Pass:** after every trip the settings rows read Active and the selected frame rate again, the clip
carries that frame rate, and the result metadata reports the matching stabilization mode. A row that
returns as Standard/Off or 30 fps, or a clip at the narrowed rate, is a fail.

### A12. RAW loss is quiet per press and on FRONT — ◯ OPEN 2026-10-02

Cycle 5 (plan A1.11, with the MRG5-1 latch `009bf08f`) stopped raising "RAW unavailable" on every
shutter press of a structurally RAW-less route (one where `rawSelectable` is false, such as FRONT).
On a RAW-capable route whose accepted session lost its RAW reader, the loss is announced once per
session shape at Ready instead. The second half needs the session ladder to drop RAW, which PMA110
cannot be made to do on demand, so this entry checks the visible first half; the once-per-shape
announcement stays covered by the host predicate test.

- PMA110, rear **PHOTO** with DNG on. Take one still and confirm a DNG is saved.
- Switch to the FRONT camera and press the shutter three times. Return to the rear camera and press
  it once more.

**Pass:** no front press shows "RAW unavailable", each saves its processed still, and FRONT's Ready
shows no RAW-loss line. Back on the rear camera the next still writes a DNG again and no notice
appears. Record the status lines seen after each press.

### A13. Reopen caption, refusals and microphone lines on the status plate — ◯ OPEN 2026-10-02

Cycle 6 (plan A2.6 and A2.7) changes three visible status behaviours, all host-tested only:

1. An ordinary reopen (DNG on/off, aspect, frame rate, lens or mode change) shows the reconfiguring
   caption for as long as that reopen holds. The caption follows Not-Ready with a pending optics
   generation, not a published PROGRESS status. Cold start and the terminal reopen-the-app state do
   not show it, and the focal rail's TalkBack state and the Custom WB caption use the same rule.
2. A "Camera reconfiguring…" REFUSAL (a shutter or REC press during a reopen) is a timed response.
   It no longer sticks on the plate over a live, Ready viewfinder.
3. "Microphone busy" and the microphone-outcome lines (for example "Recording without audio") are
   responses to the REC press, so they take the plate even over an unexpired warning or retained-take
   line, which returns afterwards for its remaining time.

- PMA110, rear **PHOTO**. Toggle DNG on and off, then switch the aspect ratio, and watch the Output
  row during each reopen. Force-stop and relaunch, and watch the same row during cold start.
- Toggle DNG and press the shutter at once, inside the reopen. Repeat in **VIDEO** with an fps change
  and an immediate REC press.
- With audio on and the microphone permission revoked in Android Settings, press REC.

**Pass:** (1) the caption appears during each ordinary reopen and clears at Ready, and cold start
shows only "Starting camera…". (2) Each refusal line shows and then clears on its own within a few
seconds, including when Ready arrives first. (3) The microphone-outcome line is shown at the REC
press and the take records video-only. Record the lines seen and any that stuck. A "Microphone busy"
refusal cannot be provoked on demand; if one is seen, record whether it was shown over the line
already on the plate.

### A14. A saved DNG carries no serial or unique-id tag — ◯ OPEN 2026-10-02

`DngCreator` writes its own TIFF/EXIF tags from platform properties, and `CLAUDE.md`'s "captures carry
no GPS tags" proof parsed a HEIF only. No saved DNG has been checked for a camera serial or a unique
image id (AGG6-34). `setLocation` is never called, so no GPS directory is expected.

- PMA110, rear **PHOTO** with DNG on. Save one DNG on TELE and one at the 1× preset. Where available,
  save one DNG on the TB336ZU and on the TB331FC as well.
- Pull each file and list every tag: `exiftool -a -G1 -s <file>` (or the repository's TIFF parser).

**Pass:** no file carries `CameraSerialNumber`, `BodySerialNumber`, `LensSerialNumber`,
`ImageUniqueID` or any GPS tag. Record the full tag list for each device. A present identifying tag is
a fail that needs a strip in the DNG lane plus a matching `CLAUDE.md` correction.

### A15. Native ISP log reaches the file, stills stay normal — ◯ OPEN 2026-10-14

Native log (`camera/NativeLogCurve.kt`) programs an S-Log3 `TONEMAP_CURVE` on the repeating request
of the ordinary 8-bit video session and GL decodes it back before the selected log encode. The HAL
was measured honouring a programmed curve on the PREVIEW (debug experiment, 2026-10-14); the
product path itself — curve in the RECORDED file, stills inside the same session, and the decoded
preview — has host tests only.

- PMA110, **VIDEO**, transfer **S-Log3**, exposure **PROGRAM**, aimed at an evenly lit grey wall (the
  2026-10-14 reference scene). Confirm logcat `Session configured (... hlg=false, nativeLog=true ...)`
  and `sourceLog -> true`.
- Record 5 s. Take one **in-REC snapshot** during it. Stop. Repeat once with **Gamma Display Assist**
  on, and once with zebra + false color on (screenshot each).
- Switch to **LogC3** (no reopen expected) and record 5 s; switch to **HLG** (reopen expected) and
  confirm the session reports `hlg=true, nativeLog=false` with the "10-bit video · stills off" caption.
- Pull the clips and the still. Measure the clip's centre Y10 median (as in the 2026-10-14 baseline:
  GL-log file 0.318).

**Pass:** the native S-Log3 clip's wall reads near the S-Log3 grey anchor without the stock S-curve
(flatter mid-tone contrast than the GL-log baseline clip of the same wall; record the number); the
in-REC still looks like an ordinary photo (not flat), and its EXIF exposure matches the clip; the
assist preview reads as a plausible un-S-curved image; zebra/false-color trigger on the bright areas
rather than going dead; no CAMERA_ERROR and no preview stall beyond the ordinary reopen. Record
whether highlights show local-tone-mapping halos (LTM under a programmed curve is unmeasured).

**Partial result 2026-10-14 (still OPEN — zebra/false-color and the LTM halo look were not checked).**
PMA110, Android 16, immutable debug `a55a4129`, TELE route (no converter), VIDEO S-Log3, M 1/30 s ISO
12800 (wire 12650), lit poster scene. Logcat: `Session configured (fallback=0, hlg=false,
nativeLog=true, jpeg=true, raw=true …)` and `FlipRenderer: sourceLog -> true`. The 4 s clip's Y10
(full range) read p1 108 / p5 167 / p50 439 / p95 609 / max 619 — black near the S-Log3 floor (95)
and every highlight capped at the S-Log3 code of linear 1.0 (0.596 → 610), i.e. the ISP's own
output is S-Log3. An in-REC "Take photo while recording" press wrote DNG+HEIF+JPEG; the JPEG is an
ordinary contrasty photo, not flat. Gamma Display Assist turned the flat preview (mean 123, std 41)
into a display-referred image (mean 190, std 78) — more saturated and harder-clipping than the stock
S-curve, as an un-tone-mapped view should be. No crash, no CAMERA_ERROR. Earlier the same day, on a
grey wall under HAL AE, the curve visibly replaced the stock one (preview mean 64 → 79.5, std 9.5 →
4.4; a linear curve read 18).

**Phone-maker log checks — OPEN, added 2026-10-15 (per flavor; only the PMA110 one is testable in
this lab).** The phone's own log is offered only on its maker's phones (`DeviceProfile.nativeLogFlavor`;
model table in CLAUDE.md); every curve is host-pinned against its white paper, nothing below is
device-measured yet. For each check: VIDEO, native-log session, switch S-Log3 → the phone's log
(confirm NO reopen: no new `Session configured` line), record 5 s of a lit grey card / wall with a
clipped highlight, read Y10 full-range values from the file and its tags with ffprobe, and record
the numbers. **Common pass:** container BT.2020 / full range / an SDR-class transfer; the Gamma
picker on that phone lists S-Log3, S-Log3.Cine, LogC3, its own log and no other maker's; the maker
caption shows above the source caveat in EN and KO. A white well below the expected code or a grey
far from it means the curve or the source decode is wrong for the maker's LUT, not a latitude limit.

- **O-Log2 — PMA110 (testable here; also CPH2841, PMA120).** Clipped highlights near code 0.6946
  (Y10 ≈ 711) and never above; an 18 % card exposed to the meter near 0.488 (≈ 500); deep black
  near the toe's 0.150 (≈ 154), not the old toe-less 0.121; OPPO's official O-Log2 → Rec.709 LUT
  gives a plausible normal-contrast image. At Log EI +3 the clip moves up to ≈ 0.950.
- **O-Log V1 — Find X8 Ultra / Find X9 / Find X9 Pro / OnePlus 15 (no device in the lab).** Grey
  near 0.490 (≈ 501), clip near 0.723 (Y10 ≈ 740); OPPO's official O-Log_to_ACES_AP0_V1 DCTL or
  O-Log → Rec.709 LUT lands grey at normal exposure (this is the check for the 7.37235/16 exposure
  convention). At Log EI +3 the top ≈ 0.12 stop clips flat at ≈ 0.9996 by design.
- **Mi-Log — Xiaomi 15 Ultra / 17 Ultra (no device in the lab).** Grey near 0.4535 (≈ 464), clip
  near 0.6748 (≈ 690), black near 0.147; Xiaomi's Mi-Log LUT gives a plausible image. Also confirm
  the GENERIC quirk profile is adequate there (no PMA110 workaround applies).

### A16. Face Priority AF frames track a face and AF follows it — ◯ OPEN 2026-10-14

Face Priority AF (`camera/FacePriority.kt`) puts `CONTROL_MODE = USE_SCENE_MODE`,
`CONTROL_SCENE_MODE = FACE_PRIORITY` and `STATISTICS_FACE_DETECT_MODE = SIMPLE` on the repeating and
still requests and draws the HAL's `STATISTICS_FACES` as corner frames. Only the capability facts are
device-read (every PMA110 camera advertises `[OFF, SIMPLE]`, 10 faces, FACE_PRIORITY — dumpsys
2026-10-14); the HAL's behaviour under those keys, and the frame geometry, have host tests only. The
geometry is the exact inverse of the tap → metering mapping, which itself carries an uncalibrated
note, and the HAL-EIS crop of a stabilized video stream is not modelled.

- PMA110, Focus tab → **Face Priority AF** on, AF-C. A person (or a printed face) ~1–2 m away.
- **Rear PHOTO, portrait:** frames sit on the face and move with it; the largest face turns green
  once AF settles. Repeat held in **both landscape directions**.
- **TELE** (converter on, or TELE mode without glass at a distant face): the frame stays on the face
  (afocal 180° term). **FRONT:** frame on the face, moving the same way as the face in the mirrored
  preview. **VIDEO 16:9** with stabilization **Active**: note any vertical/scale offset (stream crop
  is modelled, the EIS margin is not). Record one clip and confirm frames stay up during REC.
- **AF:** two faces at different distances — the focus lands on the green one; a tap elsewhere holds
  the tap (frames turn white) and Reset returns AF to faces.
- **Manual exposure unaffected:** M mode, ISO 400 · 1/125 s, a face in frame. Take one still with the
  feature off and one with it on; both EXIF must read ISO 400 / 1/125 s and look equally bright.
  Repeat in photo P (app-side) and compare the settled ISO/shutter with the feature on and off.
- `adb logcat -d | grep -E "FrameGap|CAMERA_ERROR"` across the session.

**Pass:** frames track the face upright in every route and orientation (record any offset per route);
AF settles on a face; M-mode EXIF matches the dial values with the feature on; no `CAMERA_ERROR` and
no `FrameGap` summary beyond the ordinary one-swap toggle edge. A manual-exposure mismatch is a fail
that means this HAL does not keep aeMode active under FACE_PRIORITY: restrict the scene half of
`facePriorityKeysFor` to HAL-AE packets (detection and frames stay) and correct `CLAUDE.md`.

---


**Partial result 2026-10-14 (still OPEN — portrait/landscape/front/16:9-EIS routes, the tap
override, and the M-mode EXIF A/B were not run).** PMA110, immutable debug `4813a245`, TELE (no
converter, so the 180° correction is live), VIDEO S-Log3, AF-C, M 1/30 s ISO 12800, phone held in
landscape, a poster with six faces. Six frames landed on the six faces, upright and on target through
the 180° flip; the largest turned green with `afState=2` (PASSIVE_FOCUSED), `afMode=3`. Manual
exposure held under FACE_PRIORITY: `aeState=0 iso=12650 expNs=33319161` (the nominal 12800 clamped to
the hardware ceiling, as designed). No FrameGap summary, no CAMERA_ERROR.

### A17. Log EI offset buys highlight headroom, grey and stills unchanged — ◯ OPEN 2026-10-15

The Log EI offset (`ManualControls.logEiOffsetStops`, NativeLogCurve.kt) underexposes the native-log
REPEATING request by 2^N (ISO first, then a shorter shutter at the ISO floor; EV comp under HAL AE)
and programs `slog3(k·x)` for the ACHIEVED factor; GL encodes the unclamped scene-linear decode while
the finder stays at normal brightness. Host tests only.

- PMA110, **VIDEO**, transfer **S-Log3**, native-log session confirmed (`nativeLog=true`). A bright
  scene with an 18 % grey card at the meter's exposure AND a highlight that clips (a lamp, a sunlit
  white surface). Video tab → **Log EI Offset**.
- **P (HAL AE):** record 5 s at **0**, then 5 s at **+2 EV** (one rebuild; no `CAMERA_ERROR`). Take
  one **in-REC still** in each. Watch the finder: it must NOT darken by 2 stops at +2.
- **M:** ISO 800 · 1/50 s. Record 5 s at **0** and at **+2 EV**; take an in-REC still in each. Then
  repeat at the sensor's base ISO to exercise the shutter remainder. Note the live ISO/shutter chips.
- Pull the clips and stills; measure centre-of-card and highlight Y10 (full range).

**Pass:** the grey card reads the same S-Log3 grey code (≈ 0.41, Y10 ≈ 420) at 0 and +2 in both P
and M; highlights that pinned at ≈ 0.596 (Y10 ≈ 610) at 0 show gradation above it at +2 (up to
≈ 0.75, Y10 ≈ 766, for 2 stops); shadows are visibly noisier at +2 (expected cost); both in-REC stills look like
ordinary photos and their EXIF exposure matches the operator's values (M: ISO 800 · 1/50 s at both
offsets; P: the same brightness at both offsets — **a 2-stop-dark P still at +2 is the known HAL-AE
risk**, record it); the finder, zebra and false color read the same brightness at both offsets; the
M chips keep showing ISO 800 · 1/50 s; at base ISO the logcat/result shows a shorter wire shutter, not
a longer one. Record the numbers and any partial offset (EV-comp range clamp) observed.


**Result 2026-10-15 — FAILED on PMA110; the offset is now hidden there (owner decision).** PMA110,
immutable debug `17e50cd1`, TELE no converter, VIDEO O-Log2, a sunlit wall. The wire was right
(dumpsys "Last request sent": EI +2 carried the k = 4 curve and `aeExposureCompensation -12` = −2 EV
at the 1/6 step; P exposure 8.30 → 2.08 ms; M at ISO 100 8.30 → 2.07 ms) and GL received
`sourceLogHeadroom -> 4.0`, yet the file and finder stayed dark. Alternating EI quickly (sun drift
excluded) in M: preview p98 decoded to 0.206 / 0.057 / 0.023 / 0.011 for EI 0 / +1 / +2 / +3 —
the ISP output is not exposure-linear under the programmed curve (an adaptive pre-curve gain). +2
also showed banding and desaturation. A GENERIC device would need its own run of this check.

### A18. Ten-bit native log through the P010 bridge, stills and SDR unchanged — ✅ PASSED 2026-10-25 (immutable debug `3c214cfb`)

Native-log VIDEO sessions on a measured route carry the GL stream as YCBCR_P010 (STANDARD profile)
through `P010InputBridge` (ImageReader → ImageWriter → the GL input SurfaceTexture), and every GL
generation on a `tenBitNativeLogBridge` profile runs an RGBA1010102 EGL config so the encoder target
keeps the ten bits. Every stage was measured in a standalone probe on 2026-10-25 (BACKLOG); this
check proves the app wiring.

- PMA110, **VIDEO**, transfer **S-Log3**, main 1× route. Logcat must show `EGL config requested
  tenBit=true, RGBA1010102=true` and `Session configured (... nativeLog=true, p010=true, jpeg=true,
  raw=true ...)`; `dumpsys media.camera` must list the GL stream as `format 0x36, dataspace 0x8c60000`
  (since `f92aa731`, 2026-10-25: `dataspace 0x8c20000` — JFIF, the matrix the HAL actually encodes
  P010 with; the evidence below predates that fix and stands for its own commit).
  The Video tab caption reads "· 10-bit, clips at sensor white".
- Record 5 s with one **in-REC still** (DNG on). Repeat on **TELE** (TC on, `0x80b4`).
- Switch to **SDR** (AVC, then HEVC) and record 5 s each; switch to **HLG** and record 5 s; take a
  Photo-mode still. Flip Photo ↔ Video and S-Log3 ↔ SDR a few times (bridge ↔ direct producer
  hand-off on the GL input Surface).

**Pass:** the S-Log3 clips are HEVC Main 10, `bt2020` full range, black ≈ Y10 95 (O-Log2 ≈ 154),
every code used across smooth patches (the source-precision A/B against an 8-bit stream is the
probe's raw-Y measurement, 94 vs 27 levels — the app has no path that turns the bridge off); the in-REC stills (JPEG
and DNG) save normally; the SDR and HLG clips decode and look like before (no colour shift, no
corruption) — they now pass through a ten-bit EGL target; the Photo still is normal; no
`CAMERA_ERROR`, no `P010 bridge refused` line on the measured routes, and no black or frozen preview
across the hand-offs. A refused bridge must fall back to the same rung at 8 bits (caption "8-bit").

**Evidence 2026-10-25 — PASSED on the immutable debug APK** (`build_immutable_debug.py`,
commit `3c214cfb`, installed as `me.hletrd.telecampro.debug` 1.0.5-debug; the app was left in the
operator's Video · O-Log2 · TELE · M ISO 100 1/125 s state it was found in). dumpsys: TELE
`CUSTOM (32948)` and TELE-off `NORMAL (0)` both carry `3840 x 2160, format 0x36, dataspace
0x8c60000` beside JPEG and RAW 4096×3072; logcat `EGL config requested tenBit=true, RGBA1010102=true`;
caption "· 10-bit". Takes, all with AAC and a clean full decode: TELE O-Log2 (M, near-black room)
Main 10 bt2020 full, 422 frames, black Y10 153–158 against the O-Log2 floor 154, in-REC still saved
`heic,jpg,dng`; TELE-off O-Log2 Main 10, 151 frames; SDR HEVC Main bt709 limited, 154 frames; a lit
TELE O-Log2 take in P: Main 10, 182 frames, black 156, 181 distinct levels, every code used in three
smooth 128×128 patches (20/20, 22/22, 7/7). The exposure mode was put back to M at ISO 100 · 1/125 s.

**Developer-build run (same day, before the commit):** PMA110, an uncommitted `feat/native-log-p010` worktree built with `assembleDebug` and installed
as `me.hletrd.telecampro.p010` (a separate package, so a concurrent session's `.debug` app was not
overwritten) — not the immutable wrapper APK CLAUDE.md requires for evidence, hence ◐. Logcat:
`EGL config requested tenBit=true, RGBA1010102=true`; S-Log3 main route `Session configured
(fallback=0, hlg=false, nativeLog=true, p010=true, jpeg=true, raw=true)` with dumpsys `3840 x 2160,
format 0x36, dataspace 0x8c60000` beside JPEG 4080×3064 and RAW; TELE `operation_mode 0x80b4`, same
P010 stream beside JPEG and RAW 4096×3072. Caption "· 10-bit, clips at sensor white". Clips: main
S-Log3 HEVC Main 10 `yuv420p10le` bt2020 full, 558 frames, Y10 min 103, a 128×128 centre patch used
all 37 codes of its 162–198 range; TELE S-Log3 Main 10, 153 frames, black 98, all 21 codes of 211–231;
the in-REC HEIF saved with the stock tone map. SDR HEVC Main and AVC High: 8-bit bt709 limited, 152
frames each, clean decode, floor G/R 0.80 and B/R 0.885 against the HAL still's 0.78 / 0.89 (no
colour shift through the ten-bit EGL). HLG: Main 10 arib-std-b67 bt2020, 154 frames, clean decode.
Photo HEIF normal. Four Photo ↔ Video flips with S-Log3: no crash, live preview, zero
`P010InputBridge` threads afterwards; the `ImageReader$ListenerHandler … dead thread` warnings also
appear with SDR (no bridge), so they are pre-existing. The same-scene bridge-off A/B was measured in
the standalone probe (raw Y 94 vs 27 levels), not in the app.

### A19. RAW log (experimental): S-Log3 developed from RAW, hand-offs clean — ◐ HALF DONE 2026-10-25

The Video-tab toggle **RAW Log (Experimental)** (S-Log3 / S-Log3.Cine, ≤ 30 fps) replaces the
session's camera outputs with one RAW_SENSOR stream that `RawLogBridge` develops on the GPU into
S-Log3 over S-Gamut3.Cine (ColorMatrix + Bradford path on PMA110, whose ForwardMatrix pair is an
sRGB placeholder) and hands to the GL input SurfaceTexture.

- PMA110, **VIDEO**, transfer **S-Log3.Cine**, 29.97 fps, 3× route; turn the toggle on. `dumpsys
  media.camera` must list ONE stream, `4096 x 3072, format 0x20`; the caption reads "Developed in-app
  from the sensor RAW · 10-bit…"; the OSD tag reads `SG3C RAW`; the viewfinder is upright and framed
  like the native-log 3× view.
- Record 8 s. Turn the toggle off (expect `format 0x36` P010 + JPEG + RAW back), on again, switch to
  LogC3 (expect P010 native log while the toggle stays on), and take a Photo-mode still.
- **Stabilization (gyro EIS, the core):** hand-hold the phone on a detailed subject and record ~10 s
  with Stabilization **Active**, then ~10 s with **Off**; repeat both with the converter mounted and
  **TELE** on. Score each pair with frame-to-frame phase correlation (jitter = motion minus its
  15-frame moving average). The 3A line must read `ois=0 vstab=0` during every RAW log take with EIS.
- Open: a colour-chart comparison against a reference (the RAW path is colorimetric from OPPO's
  ColorMatrix data; the ISP path is OPPO's tuned look — they differ visibly on a warm interior), a
  10-minute thermal/cadence soak, and a lit scene with clipped highlights (neutral clamp, no magenta).

**Pass (stabilization):** the Active takes carry clearly less jitter than the Off takes, bare AND
through TELE — a TELE Active take with MORE jitter than its Off take means the converter's sign (−M)
is wrong for that optic.

**Pass:** HEVC Main 10, `bt2020` full range, ~30 fps with no recurring gaps, AAC present; codes ≥ the
S-Log3 floor; no 16 px chroma checker (the probe's resample bug — fold test < 2 codes at every
period); colours outside BT.709 kept (the native path clips them); every OFF lands on P010, never the
8-bit stream; the Photo still saves JPG + HEIC + DNG.

**Developer-build run 2026-10-25 (`assembleDebug`, not the immutable wrapper — hence ◐):** RAW-only
session `4096 x 3072, format 0x20` on cam 4 at the first rung; clip HEVC Main 10 2160×3840 bt2020
full, 241 frames / 8.03 s = 29.89 fps (median 33.32 ms, one 66 ms gap) and 301 frames / 29.91 fps on
the swap-interval-0 build, AAC 48 kHz stereo; fold test Cb 1.1 / Cr 0.4 at p16 (native clip 1.0 /
1.1); 5.72 % of the frame outside BT.709 kept vs 0.00 % on the native path; OFF → P010 in 21 of 22
transitions — the one 8-bit landing preceded the async-producer fix (`eglSwapInterval 0`, so a
parked developer thread cannot keep the GL input Surface connected), 0 of 6 since; Photo still saved
`jpg, heic, dng`.

**Developer-build run 2026-10-25, resource pass + EIS (still `assembleDebug`):** RAW10 session
(`4096 x 3072, format 0x25`) with the picture intact; per frame on cam 4 upload 2.4 ms CPU (RAW10, only
the demosaic's rows, two PBOs; was 6.6 ms RAW16), develop call 4.2 ms CPU with EIS, pass 1 ~4.1 ms +
pass 2 ~1.7 ms GPU, 301/301 frames; process CPU 56 % in preview (was 83 %; native log 46 %); EIS
session `ois=0 vstab=0` with the explicit OIS OFF (vstab OFF alone read `ois=1`). The phone lay static
on the floor, so stabilization EFFECT is unmeasured — the handheld pairs above are the open half.

**Immutable debug `edb99792`, 10-minute soak 2026-10-25 (1×, 4K 29.97, Stabilization Active, audio
on):** 18 411 frames over 614.3 s, full software decode clean, no repeated, black or spiked frame; the
develop profile read `missing(camera=1 developed=0)` over 64 windows (the one camera skip at start)
at 30.0 fps; the 24 single-frame gaps in the file are the encoder's NTSC cap trimming the 30.01 fps
sensor, one every ~25.7 s (`CLAUDE.md`, drop source 3), not the RAW route. App RSS 388 → 393 MB, thermal
status 0 throughout. Against the stock Pro Video O-Log2 4K30 at 1× over the same 10 minutes from the
same start temperature: system CPU 425 vs 421 %, camera HAL 127 vs 178 %, app 92 vs 46 % (debug,
JIT-only), codec process 33 vs 13 % (our encoder input is RGBA from GL and the codec converts it), GPU
busy 33 vs 23 %; skin +3.7 vs +2.9 °C, GPU sensor +5.8 vs +4.2 °C, battery +2.5 vs +1.8 °C.

## B. Held in hand — 3 min

### B1. Landscape video playback orientation — ✅ PASSED 2026-07-29 (operator)

Held portrait, rotated left 90°, and rotated right 90° clips all play upright in an external player.
This was the last open piece of the rotation work; **rotation is now closed end to end** — preview,
stills, and the video container hint.

> Operator-reported, like C3: a human watched the three clips play. It is not an instrumented
> measurement, and no rotation side-data was re-parsed for this pass. That is the right kind of
> evidence for this check — the thing under test is exactly what a player does with the hint — but
> record it as what it is.

Original procedure, kept for re-runs after any `videoOrientationHint` change:

Saved *stills* were already confirmed upright in every held pose; the video **container orientation
hint** was the piece that had never been played back externally.

> Partial data (2026-07-28, phone lying FLAT): a recorded clip carried a natively portrait
> 2160×3840 buffer and **no rotation side-data or rotate tag at all** (hint = 0), which plays
> upright. That is *consistent* with a device-orientation term of 0 but does NOT prove it —
> flat means in-plane gravity is ~0, so `GyroEis` was holding its last confident value and the
> term was unobservable. The two LANDSCAPE cases are what actually need a held phone.

- Record ~5 s clips held: portrait, rotated left 90°, rotated right 90°.
- Play each in an external player (Google Photos, VLC — **not** the app's own review).

**Pass:** all three play upright, with landscape clips filling the screen in landscape.
**Fail:** a landscape clip plays 180° off or sideways → `RotationMath.videoOrientationHint` sign.

---

## C. Converter mounted — 5 min

### C1. TELE orientation — ✅ PASSED 2026-07-29 (operator)

Upright with the converter mounted. This is the check the whole app exists for: the afocal 180°
correction applied against real converter glass.

Procedure, kept for re-runs after any rotation change:

- Mount the 300 mm converter on the 3× lens, enable **TELE**.

**Pass:** the scene is upright.

> If it's upside down **with the converter mounted**, that's a real defect. If it's upside down with
> the converter **off**, that is expected and not a bug — TELE applies a 180° correction for an
> inversion the optic would be causing.

<a id="loupe-overview-afocal-exception"></a>

### C2. Loupe overview per-draw orientation — ✅ DEVICE-VERIFIED 2026-07-28

The corner overview deliberately does **not** share the main view's afocal 180° correction. Its
one-call `rotationOverrideDeg` carries only the window-rotation term (0 on the portrait-locked
phone), and the framing hint uses the same term. With the converter mounted, the main view must be
upright while today's same-stream overview shows the converter-fed **raw, inverted field**. That is
the current executable contract, not a claim that the inset is already a true upright wide finder.
The optional private `docs/BACKLOG.md`, when present, carries the second-stream design history; this
section is the committed clean-clone authority for today's same-stream limitation.

Procedure, kept for re-runs:

- Mount the converter, enter TELE photo mode, enable **Loupe** and **Loupe Overview** (Menu →
  Assist), and activate the punch-in loupe against a scene with an unmistakable top and bottom.

**Pass:** the main view is converter-corrected upright; the corner overview shows the same delivered
stream without that afocal correction and is therefore 180° inverted relative to the main view. In
a rotated large-screen window, the overview and its framing hint must take the same window term.

Device A/B evidence for the override is preserved in `CLAUDE.md`: the overview's vertical gradient
changed sign while the main view stayed unchanged. **Superseded historical result:** on 2026-07-29
the operator recorded the two draws as “the same way up” under the old criterion. Keep that report as
historical evidence, but do not reuse it as the current pass condition; the contradictory 2026-07-28
backlog conclusion is explicitly marked superseded.

### C3. TC OIS (optional) — ✅ CLOSED 2026-07-28 (operator; no observable difference)

The public Camera2 OIS/stabilization path and the vendor `0x80b4` session acceptance are verified.
The operator's handheld A/B found **no observable difference** attributable to a distinct 300 mm
profile; such a profile was not demonstrated, and this check does not claim one.

- Record two handheld clips of the same distant subject, TELE on, at the same shutter, with the app
  force-stopped between them.

**Future re-run:** only a reproducible visible or measured difference in handheld steadiness may be
recorded as confirmation of a distinct profile. An indistinguishable A/B remains “no observable
difference,” not “confirmed working.”

---

## D. Audio, quiet room — 2 min

### D1. Sound Focus off-axis rejection — ◯ OPEN (unblocked 2026-10-26)

Builds before 2026-10-26 could not write the parameters: AudioFlinger silently drops
`vendor_audiorecord_*` writes from an app without MODIFY_AUDIO_SETTINGS, and the HAL logged
`mEffectType(0)` for every Directionality choice (2026-10-25, `CLAUDE.md`). The app now declares the
permission (owner decision). First confirm the shipping path in logcat (`OplusAudAlgoManager` /
`AudioFlingerExtImpl logSetParametersForDebug`): `effectType 2` for Sound Focus, `5` for Sound Stage,
`0` for Standard, and the effect back at `0` after Stop. Done 2026-10-26 for Sound Stage on the
immutable debug build: session id, orientation 0, focus zoom 1.0 and `effect_type=5` reached the HAL
(`effectType 5`, profile `mEffectType(5)`), and `effect_type=0` followed Stop. What remains is the
listening test below.

- Record the same scene twice, Sound Focus on then off, with a sound source clearly off to one side.

**Pass:** the off-axis source is more suppressed with it on. If indistinguishable, record that — the
feature would then be advertising something it doesn't deliver here.

### D2. TB336ZU REC audio under the token-scoped recorder door — ◯ OPEN 2026-10-02

The silent-clip race on the MediaTek tablet (the audio worker reaching `AudioRecord.startRecording`
before the Engine publishes the pending token) was device-closed for the FIRST fix, which admitted
the pending token's owner: five of five takes carried AAC. The current door (`4e57fff2`) admits the
pending token's own workers by TOKEN instead, and that change is host-tested only.

- On a Lenovo TB336ZU, install the exact immutable debug APK and grant CAMERA and RECORD_AUDIO.
- In VIDEO with audio on, record five takes of about 5 s each, starting each from a cold Video entry
  (the slow first encoder swap is what exposes the race).
- Pull each clip and inspect its tracks (`ffprobe` or `MediaExtractor`), and keep the logcat for each
  take (`AudioRecord` set/openRecord/start rows).

**Pass:** five of five clips carry an AAC track with non-silent audio, and every take's logcat shows
`AudioRecord` reaching `start`. A clip with `set/openRecord` but no `start` is this race, not a mic
fault. Record device build, APK/source identity, and per-take track lists.

### D3. Clip publication through the live muxer-stop tail — ◯ OPEN 2026-10-02

Cycle 4 (B.1) changed what happens when the post-stop parse of a finished clip throws: the clip is
kept (retained, not published) unless a bounded ISO-BMFF top-level walk proves there is no complete
`moov` box, and the confirming parse re-opens a fresh descriptor. A healthy clip must still publish
normally; a false "retained" verdict on good takes would hide every clip from the gallery until the
next launch's recovery.

- PMA110, **VIDEO**, audio on. Record six clips of about 5 s: 4K HEVC SDR, 4K HEVC with HLG, 1080p
  AVC, Open Gate, one with the microphone toggled off, and one where the mic is disabled from the
  quick settings tile in the first second (the add-track window).
- After each stop, note the status line, then pull the clip and confirm it plays.

**Pass:** every clip appears in the gallery immediately after stop (published, not shown as kept or
unverified), plays end to end, and carries the expected tracks: video-only for the audio-off take;
the privacy-toggle take may carry a silent AAC track or degrade to video-only — record which. Any
good clip reported as retained is a fail; keep its logcat and the pulled file.

### D4. Standby meter and REC admission under a quick Stop — ◯ OPEN 2026-10-02

Cycle 6 (plan A1.11, carrying AGG5-42, AGG5-43 and AGG5-66) makes three changes on the REC admission
edge. A Stop latched during admission is no longer absorbed by a racing REC press. The standby level
meter re-arms after a Stop-latched admission fails, where before it stayed off until the next Video
entry. An audio-off take no longer reports "Microphone busy". The interleavings are host-tested; the
meter is a real `AudioRecord` and its re-arm has not been seen on a device.

- PMA110, **VIDEO**, audio on, details visible so the standby meter shows while armed. Speak or tap
  near the phone and confirm the meter moves.
- Press REC and then Stop as fast as possible, five times, so the Stop lands inside the admission
  window. After each pair, wait two seconds and talk again.
- Turn audio off and repeat three REC/Stop pairs.

**Pass:** after every quick pair the standby meter moves again within about a second, no recording is
left running (no tally, no timer), and any clip that was created is published or reported. No
audio-off pair shows "Microphone busy". Record each pair's outcome and keep the debug logcat.

**Partial result 2026-10-04 (still OPEN — the meter half needs a person speaking).** PMA110, Android
16 (API 36), face-down on a desk, TELE, 4K 59.94 HEVC S-Log3, audio on, adb-driven taps. On the
`355f6b67` immutable debug APK, 2 of 8 REC→Stop pairs at a 120–150 ms gap PUBLISHED a clip whose
video track had an empty sample table (`RecordingStored ... saved=true storage=PUBLISHED`; ffprobe
`codec_name=unknown`, 0×0; only two AAC frames in `mdat`): a Stop inside the muxer rendezvous dropped
the IDR and only its dependent frames were muxed. On the `5848b462` immutable debug APK (video track
opens only on a key frame), 24 pairs at 20–150 ms gaps gave 19 published clips that all decode
end to end in ffmpeg (1–6 HEVC frames each) and 5 discarded takes (`saved=false
storage=NOT_APPLICABLE`), no pending rows left behind, no tally or timer left running, no crash.

### D5. Recorder cleanup classification on ordinary stops — ◯ OPEN 2026-10-02

Cycle 6 (plan B.1) extends the cycle-5 cleanup classification (F8). The audio-encoder SETUP failure
path classifies its `stop()` like the video ladder, so an AAC `start()` failure degrades to a
video-only take instead of quarantining the recorder for the process. A codec error is latched where
`signalEndOfInputStream`/`stop` throws, not only on the drain thread. The `*CodecErrorLatched` flags
mean a codec error only, not a microphone read fault or a muxer throw. The error branches cannot be
provoked on demand (F8), but every ordinary stop now runs through the changed classification, and that
path is device-checkable.

- PMA110, **VIDEO**, audio on. Record six clips: three of ~5 s, one stopped within the first second,
  one with the microphone disabled from the quick-settings privacy tile mid-take, and one with audio
  off.
- After the last clip, start a seventh take without restarting the app.

**Pass:** every clip finalizes and appears in the gallery with its expected tracks (video-only for the
audio-off take; the privacy-tile take may carry a silent AAC track or degrade to video-only — record
which). The seventh take starts normally, and no logcat row reports `UNSAFE_RECORDER_RESTART` or a
recorder quarantine. Record each clip's track list.

---

### D6. A/V sync against a clap — ◐ HALF DONE 2026-10-25 (AAC-LC done, HE-AAC open)

The recorder anchors audio to the first video frame's capture time and removes the encoder's priming
(`CLAUDE.md`). Vibration bursts against the gyro sidecar read LC −17 / HE-AAC +10 / HE-AAC v2 +12 /
xHE-AAC −9 ms on immutable debug `c892e1c8` (−60 ms before the fix), but the gyro carries its own
filter delay; a clap is seen and heard at one instant.

- PMA110, **VIDEO**, 29.97 fps, any route, AAC-LC. Clap three times, hands framed and in focus, 1 m
  from the phone; repeat once with HE-AAC.
- Find the first frame showing the hands closed and the audio onset of each clap (any editor's
  waveform at frame zoom, or `ffprobe` packet times plus an envelope).

**Pass:** sound and closed hands within one frame (±33 ms), never sound clearly first.

**Result 2026-10-25 (AAC-LC):** PMA110, VIDEO, TELE with the converter, 29.97 selection (sensor
cadence 33.322 ms), AAC-LC 192 kb/s, debug build with the sample-0 anchor and priming compensation.
In an 8.43 s clip (252 frames) both claps' audio onsets fall inside the first frame that shows the
hands closed: onset 0.9017 s in the frame at 0.900 s (+1.7 ms), onset 1.3691 s in the frame at
1.366 s (+3.1 ms). Sound is never first. The HE-AAC repeat is still open.

### D7. The four soundtrack formats import and play — ◯ OPEN 2026-10-25

Every format decodes cleanly with ffmpeg 8 (`profile=xHE-AAC` read back for xHE-AAC), and
`AacEncoderProbeTest` decodes all four with the platform decoder; the editors are unchecked.

- Record ~10 s with each of AAC-LC 256 (the default), HE-AAC 64, HE-AAC v2 32 and xHE-AAC 128 kb/s.
- Play each in the system Gallery and import each into the editors you deliver with (Resolve,
  Premiere, Final Cut, CapCut, …).

**Pass:** AAC-LC everywhere. Record which editors refuse or mute HE-AAC v2 or xHE-AAC — the format
captions promise "plays in every player and editor" only for AAC-LC and warn for xHE-AAC.

### D8. Stereo left/right in both landscape holds — ◯ OPEN 2026-10-25

The two built-in capsules sit at the phone's bottom and top, so left/right is physical only in
landscape and depends on the orientation the audio HAL is told — and the recorder tells it nothing in
Standard on builds before 2026-10-26, and nothing in any mode before the app declared MODIFY_AUDIO_SETTINGS; current builds send the orientation in every mode.

- Hold the phone in landscape with the camera facing a quiet scene. Record ~8 s: snap once clearly on
  the image's LEFT, then once on its RIGHT. Repeat in the other landscape hold, in Standard and in
  Sound Stage.
- In each clip, compare the two channels' level at each snap.

**Pass:** the left snap is louder in the left channel and the right snap in the right channel, in
BOTH holds. A swap in one hold means the HAL needs the orientation parameter in that mode.

## E. MediaProvider provenance — disposable test media

### E1. Owner-null legacy-format restore boundary — ◯ OPEN

Host fakes prove the reducer contract, but cannot prove when a real Android MediaProvider clears
`OWNER_PACKAGE_NAME` after uninstall/reinstall or import. Use disposable media only; do not delete an
operator's existing capture to set this up.

- Save one disposable TeleCam image, record its MediaStore URI and owner column, then use the normal
  uninstall/reinstall path (or import a copied lookalike through a second package) that produces a
  real owner-null row on the test device.
- Confirm the row remains under `DCIM/TeleCamPro` with a valid TeleCam filename and matching MIME.
  Then open the in-app review. The app holds NO visual-media permission since 2026-10-19 (the READ
  trio was removed under Play's Photo and Video Permissions policy), so a spec provider is expected
  to hide the owner-null row from the app entirely; record whether it is visible at all.
- Repeat with an owner-null control whose filename or MIME does not match the save contract.

**Pass:** the valid owner-null candidate is either hidden by the provider (the expected spec result
without media permission) or, if the provider exposes it, shown with the quiet “origin unverified”
descriptor and file-only delete copy; the mismatched control is never restored. A row still owned by the current
package has no unverified descriptor and retains its normal capture-family deletion scope.

Record the Android build, provider package/version, row owner before/after, import/reinstall path, and
observed UI/delete scope. This check establishes provider semantics only for that measured build.

### E2. Owner-null system delete consent — ◯ OPEN 2026-08-24

Use the disposable valid owner-null row from E1. From its in-app review, choose Delete and approve
the app confirmation. Android must then own a second, system-rendered confirmation for that exact
file; do not substitute an app-owned row, because the current package can delete those directly and
would not exercise the consent route.

- Cancel the system confirmation once. Confirm the same origin-unverified file remains reviewable
  and the app reports cancellation without attempting a direct delete.
- Repeat and approve. Confirm the system removes only that exact URI, the app reports deletion, and
  any sibling formats remain on disk/reviewable.
- Repeat with the row removed by another gallery while the system surface is opening. Confirm the
  app does not restore a phantom review handle and reports that the file was already removed.

**Pass:** owner-unverified deletion always uses `MediaStore.createDeleteRequest`; cancellation and
launch failure preserve the exact file, approval performs no redundant resolver delete, and an
authoritatively absent row is not restored. Record API level, provider version, and the observed
system copy for API 33 and the target API 36 device. Host/Robolectric coverage cannot close this
check because only a real MediaProvider can prove the consent and disappearance semantics.

### E3. DISCARD identity across provider reset/reindex — ◯ OPEN 2026-08-25

Host fakes prove that version changes, row-generation/name reassignment, unavailable volumes,
ambiguous/missing identity, and legacy URI-only records retain their durable marker without issuing a
provider delete. They cannot establish whether a particular OEM reset/reindex reuses row IDs or how
its provider version changes. This check is evidence only; it is not permission to reset a device.

- Obtain explicit operator confirmation before any MediaProvider reset or equivalent destructive
  setup. Use a disposable test device/profile and disposable TeleCam media only.
- Create one pending disposable output, force its immediate delete to fail after the versioned
  DISCARD identity is durable, and record URI, volume, provider version, `_ID`, `GENERATION_ADDED`,
  display name, relative path, MIME, owner, and family identity.
- Perform the approved provider reset/reindex, then create or locate a disposable replacement at
  the same URI if that build actually reuses the ID. Relaunch recovery without weakening the
  identity checks.
- Repeat a control without reset where the exact original row and provider version are stable and
  the first delete fails but the retry succeeds.

**Pass:** a version change or replacement identity causes zero delete calls and retains the marker;
the stable exact-row control retries and clears only after provider absence is authoritative. Record
device/build, provider package/version before and after, whether URI reuse actually occurred, and
the immutable APK/source identity. “No URI reuse observed” is an honest inconclusive result, not a
claim that the guard is unnecessary.

### E4. Pending-row `DATE_EXPIRES` re-arm on a kept row — ◯ OPEN 2026-10-02

Launch recovery re-writes `IS_PENDING = 1` (`reassertPending`) on a row it KEEPS pending only when
a later launch can still adopt it: a kept non-DISCARD row whose probe could not run this launch
(`keptRowReassertsPending`), a kept durable-`COMPLETE` row whose descriptor is not proven empty, an
adoptable row whose publish failed, or a row whose journal state was unreadable. MediaProvider then re-arms the pending row's `DATE_EXPIRES`; otherwise idle
maintenance deletes a retained take about a week after insert. A row whose bytes were read to a
constant verdict (an unknown MIME, an undecidable HEIF layout) is kept but deliberately NOT
re-armed, so MediaProvider's expiry stays its terminal. The host test proves only which kept rows
receive the update; the expiry effect is MediaProvider behaviour.

- Create one disposable retained pending row whose probe cannot run at launch (for example a take
  whose bytes the provider cannot open), and record its URI, `date_expires`, display name,
  `relative_path`, and `_data` with
  `adb shell content query --uri <uri> --projection _id:date_expires:_display_name:relative_path:_data`.
- Relaunch the app so launch recovery keeps the row, then query the same columns again.

**Pass:** `date_expires` moves later after the relaunch and the row is still present and pending.
Record the provider's side effects on that update too: AOSP mainline MediaProvider renames the
backing file to `.pending-<newDateExpires>-<DISPLAY_NAME>`, so `_data` changes while the display
name, `relative_path`, and row id stay put. A changed display name or `relative_path` would break
the frozen discard identity and is a failure. An unchanged `date_expires` means the re-arm does not
hold on that build and the retained-row lifetime is still about one week.

---

## F. Host-only — no device procedure

### F1. EGL texture-acquisition failure with a failed preview detach — ⊘ HOST-ONLY 2026-10-02

Cycle 4 (A.22) gave the texture-acquisition branch the containment the draw/swap branch already
had: when acquisition fails and the preview DETACH also fails, the poisoned preview EGL surface is
orphaned (`orphanPoisonedPreviewOutput`, destroyed later by the checked orphan sweep) and an active
encoder owner is failed, instead of retaining a poisoned owner for same-surface retries. Reaching
that branch needs `updateTexImage` to fail AND an `eglMakeCurrent` detach to fail on the same frame.
No debug fault hook exists to inject either on a device, and an ordinary session cannot provoke
them on demand, so there is **no device procedure** for this change. Its evidence is the host
coverage of the shared helper (all three branches); it is listed here so the open count above is a
measured fact, not a gap.

**Reopen when:** a debug-only fault hook for texture acquisition / EGL detach is added, or a field
log shows a preview loss with an acquisition failure. Then: inject the failure mid-preview and once
mid-REC, and pass when the preview recovers through the bounded same-surface retry (or reaches the
terminal reopen status) and the recording finalizes instead of freezing.

### F2. Bare reopen whose preflight read fails once — ⊘ HOST-ONLY 2026-10-02

Cycle 4 (A.2): a bare `reopenForSession()` whose camera-selection or capability read fails once on a
live controller now schedules the existing bounded retry instead of parking Not-Ready. Reaching it
needs a `CameraManager` characteristics or id-list read to fail transiently at that exact moment;
nothing on the device provokes that on demand. Evidence: the host test that fails the read once and
ends Ready or in a scheduled retry.

**Reopen when:** a field log shows a reopen parked in Not-Ready after a characteristics failure.

### F3. Same-camera recall with a different video size — ⊘ HOST-ONLY 2026-10-02

Cycle 4 (A.3): a memory-slot recall onto the same camera whose Video `chooseVideoSize` differs from
the streaming size takes `reconfigureCamera` instead of the fast commit. The decision is a pure
function of the two sizes, which the host test drives with a packet differing only in video size;
the device-visible result (a recalled bank records at its own resolution) is exercised by any
ordinary recall and adds no measurement.

**Reopen when:** a recalled bank is reported recording at the previous bank's resolution.

### F4. Lens re-band on leaving FRONT — ⊘ HOST-ONLY 2026-10-02

Cycle 4 (A.10): leaving FRONT onto the logical route re-bands the lens chip from the returned zoom in
both the ViewModel and the Engine. The band is a pure function of that zoom and is host-tested; the
visible outcome (the rail lights the chip that matches the returned framing) needs no instrument.

**Reopen when:** the rail shows a lens chip that disagrees with the framing after a FRONT exit.

### F5. EV-only delta under app-side AE is a wire no-op — ⊘ HOST-ONLY 2026-10-02

Cycle 4 (A.12, EV half): under app-side or manual AE an `exposureCompensation`-only change no longer
rebuilds the preview (no `startPreview`), because the app-side loop, not the HAL, consumes EV there.
The plan decision is pure and host-tested. Its device effect is the absence of one ~180 ms swap
stall per EV tick, which A6's FrameGap protocol would show only incidentally; the WB/tint pacing
half that needs FrameGap evidence is scheduled separately.

**Reopen when:** EV ruler drags in S/ISO/M show >200 ms FrameGap summaries that a PROGRAM drag does
not.

### F6. Route-inventory fold keeps zoom — ⊘ HOST-ONLY 2026-10-02

Cycle 4 (A.18): folding a route inventory is pure discovery; zoom and the converter state reset only
when the active route really changes, and the Engine side effect runs only then. The host test folds
the same `(inventory, FRONT)` twice and keeps zoom. A device cannot choose when the inventory is
re-delivered, so there is no procedure that targets the fold.

**Reopen when:** zoom is reported snapping back to 1× without a mode, lens, TC or facing change.

### F7. Lost capture buffer or aborted sequence settles the shot — ⊘ HOST-ONLY 2026-10-02

Cycle 5 (plan A1.6): `onCaptureBufferLost` for this shot's surfaces and `onCaptureSequenceAborted`
take the same terminal as `onCaptureFailed`, so the shutter does not wait for the watchdog. Neither
callback can be provoked on demand on PMA110; the evidence is the pure helper's host test.

**Reopen when:** a field log shows a shot that ended only on the capture watchdog after a lost-buffer
or sequence-aborted callback.

### F8. Recorder cleanup after a codec error — ⊘ HOST-ONLY 2026-10-02

Cycle 5 (plan B.4): cleanup throws are classified — an exception from `signalEndOfInputStream` or
`stop` on a codec that already latched an error skips to `release()`, the AudioRecord is stopped
before later phases are abandoned, and only a hang, a failed `release()` or a live drain thread
quarantines the recorder. A real `MediaCodec` error needs a fault the device cannot inject on
demand; the evidence is the fake-native-graph host test.

**Reopen when:** a field log shows `UNSAFE_RECORDER_RESTART` after a codec error, or a device repro
of an encoder error mid-REC exists. Then pass when the next REC starts without a process restart.

Cycle 6 (plan B.1) extends this classification to the audio-encoder setup path and to a codec error
that surfaces at the `signalEndOfInputStream`/`stop` throw site. The error branches stay host-only
for the reason above; the ordinary-stop path they now share is the open check D5.

### F9. Ready cleared before DNG owners cancel — ⊘ HOST-ONLY 2026-10-02

Cycle 5 (plan A1.4): `invalidateCameraReady` clears Ready and the session generation first and only
then cancels the DNG owners. A dispatched DNG allocation whose attempt is already retired returns
without acting, `DngPreCaptureAdmission` publishes through a locked refresh, and a RAW-only chain
releases its DNG admission before handing the tail to a worker. The visible symptom it closes — a
BURST/AEB step continuing onto a session that is being torn down — needs a reopen to land inside
one step's dispatch, a window that a hand-driven mode switch cannot target. The evidence is the
host ordering tests; an ordinary interrupted burst is already exercised by any mode switch.

**Reopen when:** a field log shows a still dispatched after `invalidateCameraReady`, or a
`CAMERA_ERROR` on a BURST/AEB interrupted by a mode, lens or DNG change.

---

## Recording results

This committed file is the clean-clone field-results ledger. Record a new result in the matching
check section, update its heading and the dashboard in the same change, and include the date, device/
Android build, setup, immutable APK/source identity, and what was actually observed rather than only
“passed.” The entries above exist because a previous “verified” note described a superseded build.
The optional private `docs/BACKLOG.md`, when present, may mirror maintainer scheduling/history;
its absence never blocks recording evidence here.
