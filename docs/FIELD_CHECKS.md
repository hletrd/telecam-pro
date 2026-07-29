# Field checks

The verifications that need the phone in your hands. Everything checkable over ADB is already done —
these are the ones that need a real scene, real light, the physical converter, or your eyes.

Grouped so you change the setup as little as possible. Each is: **set up → run → what a pass looks
like.**

**Status (2026-07-29):** A1 ✅ · A2 ✅ · A3 ◐ (brightness half) · B1 ✅ · C1 ☐ · C2 ☐ · C3 ✅ · D1 ☐.
Four remain, ~8 minutes: **A3** needs the rear camera pointed at a lit room, **C1/C2** need the
converter physically mounted, **D1** needs an off-axis sound source. B1 closed the rotation work end
to end.

Install the debug build first (the release strips the diagnostic logs these rely on):

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
./gradlew :app:assembleDebug
adb connect <phone-ip>:<port>
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
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
> front route, so a vertical/axis error would survive a PASS and is a separate finding.

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

### C1. TELE orientation

- Mount the 300 mm converter on the 3× lens, enable **TELE**.

**Pass:** the scene is upright.

> If it's upside down **with the converter mounted**, that's a real defect. If it's upside down with
> the converter **off**, that is expected and not a bug — TELE applies a 180° correction for an
> inversion the optic would be causing.

### C2. Loupe overview agreement

- Still in TELE, enable **Loupe** and **Loupe Overview** (Menu → Assist), activate the punch-in loupe.

**Pass:** the corner overview and the main view show the same scene **the same way up**.

The code makes disagreement structurally impossible — both are one call each to the same renderer,
same frame, same rotation field — so this is a confirmation, not a suspicion. Reported once; see the
2026-07-28 backlog entry.

### C3. TC OIS (optional) — ✅ CONFIRMED WORKING 2026-07-28 (operator)

Never verified whether the vendor `0x80b4` TC session actually engages a different OIS profile at
300 mm — result metadata reads identically either way.

- Record two handheld clips of the same distant subject, TELE on, at the same shutter, with the app
  force-stopped between them.

**Pass/fail:** honestly, only a visible difference in handheld steadiness would tell you anything. If
you can't see one, record "no observable difference" — that is a legitimate result and closes the
item.

---

## D. Audio, quiet room — 2 min

### D1. Sound Focus off-axis rejection

Parameter acceptance is verified; the acoustic effect has never been heard.

- Record the same scene twice, Sound Focus on then off, with a sound source clearly off to one side.

**Pass:** the off-axis source is more suppressed with it on. If indistinguishable, record that — the
feature would then be advertising something it doesn't deliver here.

---

## Recording results

Put outcomes in `docs/BACKLOG.md` under the matching residual-check entry. Please write what was
actually observed rather than "passed" — the entries above exist because a previous "verified" note
turned out to describe a superseded build.
