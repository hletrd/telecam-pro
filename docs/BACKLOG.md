# Release Status & Backlog — TeleCam Pro

Current release board. Read after `CLAUDE.md`; use `ARCHITECTURE.md` for implementation details.
Historical investigation notes are snapshots under `docs/reviews/` and `.context/reviews/`, not
active TODO lists. Updated 2026-07-10.

## Release State

Version 1.0 (`versionCode=1`) is ready for Play Console upload at the code, packaging, and physical-
device verification levels. The upload artifact is:

`app/build/outputs/bundle/release/app-release.aab`

Its SHA-256 and signing certificate are recorded in `docs/play-console-submit.md`. Rebuilds produce a
new hash and must update that sheet after the full release gate is repeated.

### Verified 2026-07-10

- Release build, bundle, lint, and 63 unit tests passed (87 Gradle tasks; suite has since grown to
  216 tests — re-run the release gate before upload, as this doc already mandates).
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

## Before Production

These are manual Play Console operations, not repository implementation work:

1. Create the app and upload the signed AAB to Internal testing.
2. Enter the listing, privacy policy, App content, and Data Safety answers from `docs/play-*.md`.
3. Upload the icon, feature graphic, and six 1440x2560 phone screenshots.
4. Restrict the device catalog to OPPO Find X9 Ultra codes CPH2841 and PMA110.
5. Install from Internal testing and review Play's automated checks and pre-launch report.
6. Promote the same tested artifact to production.

Use `docs/play-console-submit.md` as the operator checklist. The account was created in 2015, so the
new-personal-account closed-test rule does not apply; an internal test remains the release gate.

## Residual Field Checks

These do not require a code or metadata change unless the result exposes a defect:

- Record the same real scene with Sound Focus/Stage on and off, then compare off-axis rejection.
  Camera/HAL parameter acceptance is verified; the acoustic effect needs ears and a suitable scene.
- Capture a clearly upright subject while deliberately holding the phone in portrait and both
  landscape directions. Confirm HEIF display orientation and DNG EXIF orientation in saved files.
  Rotation math and flat-phone orientation retention are unit-tested; this closes the final visual
  output check. Also confirm a held-landscape VIDEO clip in an external gallery (the muxer
  orientation-hint SIGN is unverified — `RotationMath.videoOrientationHint` pins the current
  mapping and is the one place to flip if wrong).
- Added by the 2026-07-10 review cycle (code changed since the recorded release AAB — re-run the
  full release gate and refresh `docs/play-console-submit.md`'s hash before upload):
  one HEIF capture on heifwriter 1.1.0 (stable, was 1.2.0-alpha01); waveform overlay visual parity
  after the drawPoints batching; the new OSD AE/AWB/AF lock tags + TeleChip hit-area layout on the
  3168 px screen; a TalkBack pass over the new dial/slider semantics.

## Deferred Beyond v1

- **R8/minify:** keep disabled until enum persistence and reflection-sensitive OEM SDK paths have
  explicit keep rules and another physical-device release pass.
- **Dolby Vision:** hardware support exists, but a correct Dolby Vision MP4 path is not implemented.
- **End-to-end 10-bit Camera2 input:** the stable shipping Camera2/EGL source is SDR/8-bit; the prior
  HLG10 + JPEG + RAW combination crashed the HAL.
- **Authenticated CameraUnit path:** requires OPPO developer registration, an issued `AUTH_CODE`, and
  a product decision on a separate video session that lacks the current RAW/manual Camera2 surface.
- **Optional product work:** configurable keep-screen-on, geotagging, custom save locations, slow-
  motion playback metadata, and advanced focus/bracketing workflows.

## Verification Quick Reference

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew testDebugUnitTest lintRelease assembleRelease bundleRelease
adb connect <device-ip>:<port>
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n me.hletrd.telecampro/com.hletrd.findx9tele.MainActivity
```

The wireless-debugging port changes by session. On this multi-homed Mac, direct ADB can report
`No route to host`; proxy the current phone port to a temporary loopback port, connect to
`127.0.0.1:<proxy-port>`, and stop the proxy when verification ends. Do not reuse a recorded old port.

Runtime CAMERA and RECORD_AUDIO permissions are granted through the app UI on ColorOS. The `camtest`
AVD is suitable only for UI/crash checks; telephoto routing, RAW, color, audio, stabilization, and
saved-file behavior require PMA110.

## Deferred from review-plan-fix cycle 2 (2026-07-10 — final cycle; durable record)

The review-plan-fix loop ended with cycle 2, so its deferrals live HERE, not "in the next cycle."
Full citations, severities, and exit criteria: `docs/plans/2026-07-10-rpf-cycle2.md` § Deferred
(D-1..D-18). Summary of what remains open:

- **Device re-verification of the cycle-2 changes** — the PMA110 re-locked behind a secure keyguard
  mid-cycle. VERIFIED on device post-unlock by the orchestrator (2026-07-10): letterboxed preview
  geometry photo 4:3 / video 9:16, photo↔video mode-switch reopens, tap-AF reticle (plus the
  3ba28c8 AspectMask axis follow-up). STILL QUEUED: review-overlay hardware-key gating, resume/
  lens-tap serialization under load, record start/stop under the ordered encoder teardown,
  MediaReview tap-to-pause, flat-resume capture orientation.
- **D-1** muxer orientation-hint SIGN (already in Residual Field Checks above).
- **D-2** confirm the Play/privacy contact mailbox is real and monitored (owner decision;
  `play-store-listing.md` / `play-console-submit.md` / `PRIVACY.md` / `privacy-policy/index.html`).
- **D-3** Shaders O-Log2 inverse-threshold discrepancy — decide with OPPO's white paper in hand;
  do not hot-patch the shipping OETF blind.
- **D-4** structural refactors (state triplication/applyExtras, CameraEngine split, AE-loop move,
  CameraSession seam, caps projection out of CameraUiState, telemetry-flow split, typed engine
  events) — MUST land before the CameraUnit / item-#4 v2 work.
- **D-5..D-9** perf items needing device measurement (downsampled analysis readback, startRecording
  off main, async DNG write, single-decode HEIF+JPEG, GL frame coalescing / cached request builder /
  waveform draw reuse).
- **D-10** MR-bank management placement (IA decision), **D-11** AE metering under LOG preview,
  **D-12** dead backup_rules removal (needs explicit owner sign-off — file deletion),
  **D-13** pure-env release signing gate (when CI exists), **D-14** keep-screen-on toggle (already
  above), **D-15** FnSlot table test on next enum growth, **D-16** OPPO Maven cred relocation (with
  the CameraUnit AUTH_CODE work), **D-17** GyroEis roll wrap transient (cosmetic), **D-18** the
  lit-scene/human-ears residual field checks (above).

## Historical References

- `docs/reviews/README.md` — tracked review-archive index and usage rules.
- `docs/reviews/2026-07-09-backlog-handoff.md` — pre-release implementation handoff and device history.
- `docs/reviews/2026-07-03-comprehensive-review.md` — comprehensive review snapshot.
- `docs/plans/` — completed review/fix plans.
- `.context/reviews/` — optional local raw specialist review snapshots (gitignored).
- `docs/superpowers/specs/2026-07-01-find-x9-ultra-camera-design.md` — original design intent.
