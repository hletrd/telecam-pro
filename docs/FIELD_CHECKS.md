# Field checks

The verifications that need the phone in your hands. Everything checkable over ADB is already done —
these are the ones that need a real scene, real light, the physical converter, or your eyes.

Grouped so you change the setup as little as possible. Each is: **set up → run → what a pass looks
like.**

**Status (2026-08-24):** A1 ✅ · A2 ✅ · A3 ◐ · A4 ☐ · B1 ✅ · C1 ✅ · C2 ✅ · C3 ✅ · D1 ☐ · E1 ☐ · E2 ☐.
Five remain: **A3** needs the rear camera pointed at a lit room, **A4** needs a rotatable large-screen
front route, **D1** needs an off-axis sound source, and **E1/E2** need real MediaProvider ownership
and system-consent behavior. B1 closed the rotation work end to end; C1 confirmed the afocal
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

---

## Recording results

This committed file is the clean-clone field-results ledger. Record a new result in the matching
check section, update its heading and the dashboard in the same change, and include the date, device/
Android build, setup, immutable APK/source identity, and what was actually observed rather than only
“passed.” The entries above exist because a previous “verified” note described a superseded build.
The optional private `docs/BACKLOG.md`, when present, may mirror maintainer scheduling/history;
its absence never blocks recording evidence here.
