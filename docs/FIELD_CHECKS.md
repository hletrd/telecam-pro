# Field checks

The verifications that need a device in your hands, plus the device checks a host test cannot
close. This ledger is exhaustive: every open device claim in `CLAUDE.md` or `docs/ARCHITECTURE.md`
has an entry here, including the two (D2, E4) that are checkable over ADB but have not been run.
A change whose device effect has NO field procedure is listed too (section F, `⊘ HOST-ONLY`), so
its absence from the open list is a recorded fact rather than an omission.

Grouped so you change the setup as little as possible. Each is: **set up → run → what a pass looks
like.**

**Status (2026-10-02):** A1 ✅ · A2 ✅ · A3 ◐ · A4 ☐ · A5 ☐ · A6 ☐ · A7 ☐ · A8 ☐ · A9 ☐ · B1 ✅ · C1 ✅ · C2 ✅ · C3 ✅ · D1 ☐ · D2 ☐ · D3 ☐ · E1 ☐ · E2 ☐ · E3 ☐ · E4 ☐ · F1 ⊘ · F2 ⊘ · F3 ⊘ · F4 ⊘ · F5 ⊘ · F6 ⊘ · F7 ⊘ · F8 ⊘.
Fourteen remain: **A3** needs the rear camera pointed at a lit room, **A4** needs a rotatable
large-screen front route, **A5** needs a sustained front pseudo-ZSL soak, **A6** needs a photo-P TELE
capture pair, a FrameGap read during a pinch, and an OWNER DECISION on the exposure delta it records,
**A7** needs one JPEG+HEIF still pair, **A8** needs a `dumpsys` YUV list beside a pulled still,
**A9** needs a pause during cold start, **D1** needs an off-axis sound source,
**D2** needs REC takes on the TB336ZU tablet, **D3** needs a short run of ordinary clips through the
live publication tail, and **E1/E2/E3/E4** need real MediaProvider ownership,
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

The ordinary foreground return has an input surface and a presented preview, so it re-binds
nothing and stays byte-identical. Host tests drive both interleavings; whether a real launch can
land in the trigger-1 window is a device question.

- Force-stop the app. Launch it and press Home (or lock the screen) within roughly half a second,
  before the viewfinder appears. Return to the app. Repeat five times, varying the delay.
- Keep the debug logcat for each attempt (`CameraController`, `Session configured`, `StartupTrace`).

**Pass:** every return shows a live, upright preview within a couple of seconds, with no black
viewfinder that needs a second background/foreground to recover, and no crash. Record the delay
and outcome of each attempt.

---

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

### D1. Sound Focus off-axis rejection — ◯ OPEN

Parameter acceptance is verified; the acoustic effect has never been heard.

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

---

## E. MediaProvider provenance — disposable test media

### E1. Owner-null legacy-format restore boundary — ◯ OPEN

Host fakes prove the reducer contract, but cannot prove when a real Android MediaProvider clears
`OWNER_PACKAGE_NAME` after uninstall/reinstall or import. Use disposable media only; do not delete an
operator's existing capture to set this up.

- Save one disposable TeleCam image, record its MediaStore URI and owner column, then use the normal
  uninstall/reinstall path (or import a copied lookalike through a second package) that produces a
  real owner-null row on the test device.
- Confirm the row remains under `DCIM/TeleCamPro` with a valid TeleCam filename and matching MIME.
  Grant contextual visual-media access, then open the in-app review.
- Repeat with an owner-null control whose filename or MIME does not match the save contract.

**Pass:** the valid owner-null candidate is shown with the quiet “origin unverified” descriptor and
file-only delete copy; the mismatched control is not restored. A row still owned by the current
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

---

## Recording results

This committed file is the clean-clone field-results ledger. Record a new result in the matching
check section, update its heading and the dashboard in the same change, and include the date, device/
Android build, setup, immutable APK/source identity, and what was actually observed rather than only
“passed.” The entries above exist because a previous “verified” note described a superseded build.
The optional private `docs/BACKLOG.md`, when present, may mirror maintainer scheduling/history;
its absence never blocks recording evidence here.
