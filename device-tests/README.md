# device-tests — on-device functional suite (PMA110)

A repeatable, adb-driven functional test suite for TeleCam Pro on the OPPO Find X9 Ultra.
It codifies the manual device-verification protocol developed across the 2026-07 review
cycles: UI-tree-driven interaction, logcat assertions, screencap statistics, and pulled-file
validation. Pure Python 3 stdlib on the host; `ffprobe` is required for a green video result
(the structural MP4 fallback is reported as non-green/incomplete).

This suite exists as an **external harness** (not `androidTest`) deliberately: the
reliability tier kills and backgrounds the app process, which in-process instrumentation
cannot survive. Host-JVM unit tests remain in `app/src/test/` (Gradle).

## Run

```bash
# device over wireless ADB (loopback proxy fine); deploy the debug APK first
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier smoke          # app already foreground
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full           # non-green until required approvals are supplied
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier reliability    # non-green until required approvals are supplied
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier all -k capture # substring filter

# Supply only the effects that the operator explicitly approved:
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier smoke --allow-destructive
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full --allow-settings
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full --allow-settings --allow-media-writes
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier reliability \
  --allow-destructive --allow-settings --allow-media-writes

# Deliberately collect a partial report; skips and the partial flag remain attested.
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full --allow-partial
```

Reports (markdown + JUnit XML + pulled evidence files) land in
`device-tests/reports/<UTC timestamp>-<run token>/` (gitignored). The runner atomically reserves
that directory, writes `run-identity.json`, and records the same run ID in the final attestation.
After a minimal read-only reachability probe, the runner derives a hashed canonical physical-device
key from device-side identity (`ro.serialno`, then `ro.boot.serialno`) and acquires a nonblocking
lock under the user's host-global cache. The runner fails closed if neither physical identity is
available; a per-user Android ID is deliberately not accepted as a physical-device key. The lock is
shared by every checkout and worktree: direct and loopback ADB aliases for one handset contend,
while genuinely distinct devices remain independent. Lock refusal exits non-green with
`run-failure.json`. Both the connection alias and hashed physical key are written to
`run-identity.json` and the final attestation; raw device-side identity is never persisted. Exit
code 0 requires at least one pass and no failures, 1 means fail/error,
and 2 means preflight failure, no matching cases, all-skipped, missing approvals for any selected
case, or a required verification reported as incomplete. `--allow-partial` is the only way to make
an intentionally partial tier green; its report and attestation retain the skips and partial flag.

**CLI report attestation contract:** every completed report directory also contains
`run-attestation.json` and `run-attestation.sha256`. The JSON records the source identity proven from
inside the APK, unique run ID, connection alias + canonical physical-device key, host and installed
APK SHA-256 values, exact approval flags, captured
pre-run and verified post-run state, and a path-sorted list of SHA-256 hashes for report artifacts. A
restoration failure or pre/post state mismatch makes the CLI result non-green rather than producing
only a warning. The SHA-256 sidecar protects the attestation bytes against unnoticed alteration; it
is an integrity check, not a signature or proof of who produced the report.

The runner inspects the exact host APK before device preflight. Android build tools provide its
application ID, launcher activity, and debug-only `UiSnapshotActivity`; those exact components drive
launch/foreground checks and are written to the attestation. The APK is hashed before and after
manifest inspection so an artifact replacement cannot join identity from one file to bytes from
another.

Every debug APK also packages `assets/telecam-debug-provenance/source.manifest`. It contains the Git
commit and a sorted SHA-256/size manifest of the main/debug app source trees and Gradle inputs that
can change APK bytes. The runner recomputes that same identity from the checkout and refuses before
ADB access when the member is missing or malformed, its commit is stale, or any clean or dirty source
content differs. Dirty builds are supported, but their attestation names the exact content-manifest
digest embedded in the exercised APK; a bare `dirty: true` claim is never used as provenance.

The harness reads production `MediaStoreWriter.CAPTURE_SUBDIR` mechanically and queries/pulls only
`DCIM/TeleCamPro/`. A rename cannot silently leave device QA watching an obsolete directory.

`device-tests/` remains ignored because historical evidence is several gigabytes. Before importing
any executable harness module, the CLI walks the source tree through pinned directory descriptors,
opens each input with no-follow semantics, requires the same regular-file inode before/after a
bounded descriptor read, and rejects symlinks, special files, and file/directory replacement races.
It copies every accepted input outside `reports/`, `__pycache__/`, and `.pytest_cache/` into a
private digest-qualified snapshot. A child can enter execution mode only with the parent-created
one-shot pipe proof naming that private root; a preset environment variable cannot skip the copy.
Case registration and execution import only the copied bytes, while repository artifacts (the
default APK and report root) resolve from the separately attested source checkout. The attestation
names and rechecks the snapshot manifest, and the parent removes the private tree when the child
exits. A green attestation therefore names the exact immutable bytes that registered and executed
its cases. Verify the source contracts with:

```bash
python3 -m unittest discover -s device-tests/tests -v
```

Locale is read without changing device state: the app-specific override is queried for the current
Android user and falls back to the system BCP-47 locale. Smoke, full, and reliability actions use
stable harness identities with exact English/Korean accessibility labels for camera chrome,
settings tabs, Fn actions, lens/zoom presets, focus reset, and REC state. The contract tests resolve
every declared identity in both languages and AST-scan state-changing cases so a raw English
`tap_ui`/`find` selector cannot quietly re-enter. Camera-standard abbreviations and numeric/profile
tokens remain identical by design.

The runner refuses any device other than PMA110/API 36 and refuses an installed `base.apk`
whose SHA-256 does not match `app/build/outputs/apk/debug/app-debug.apk` (override the host
path with `--apk`). Cases are independently gated when they may launch/force-stop the app
(`--allow-destructive`), change persisted shooting settings (`--allow-settings`), or create
photos/videos (`--allow-media-writes`). These flags are execution guards, not substitutes for
obtaining operator approval. Launch is destructive-gated because app startup may reclaim proven-
incomplete app-owned pending media. Every force-stop also fails closed at the call site unless the
UI proves the app is idle, so a recording left active by an earlier failed case cannot be killed by
a later case.

Read-only cases require the app to be already foreground. If it is stopped or another surface is
foreground, they deterministically skip instead of conditionally escalating into a launch. Cases
that statically declare the destructive effect can launch only when `--allow-destructive` is also
present; the smoke command with that flag is the intentional cold-start path.

## Tiers and cases

| tier | case | asserts |
|---|---|---|
| smoke | `launch_preview_live` | cold launch → live viewfinder (frame-diff), OSD chrome, clean logcat |
| smoke | `session_configured_3a` | 3A telemetry flows (configured repeating request; ois/vstab visible) |
| full | `camera_chrome_layout` | top/Fn/focal/mode/gallery/shutter bounds ≥48 dp, ordered, non-overlapping, centered |
| full | `mode_switch_roundtrip` | photo↔video sessions plus exactly one checked RadioButton mode, without camera errors |
| full | `lens_presets` | 0.6/1/3/10× cycle; exactly one RadioButton is checked after each tap |
| full | `teleconverter_roundtrip` | TC on→off round-trip; owned session acceptance + 3A `tele=`/`effZoom=` telemetry proves each leg (the compact OSD deliberately hides the focal tag) |
| full | `photo_capture_valid_files` | stable owned MediaStore family; pending=0, size/dims/MIME, pulled file validity |
| full | `tele_dng_capture` | TELE DNG validates as TIFF ≥1 MB; reports non-green incomplete when RAW is not enabled |
| full | `video_record_validate` | 65 s HEVC Main10 HLG 29.97p → full-frame decode, PTS cadence, AAC A/V sync |
| full | `tap_af_hold_visible_and_reset` | tap-AF holds past reticle fade with visible reset, then restores prior AF mode |
| full | `settings_sheet_tabs` | all 9 tabs select matching pages; 48 dp/on-screen; modal isolated; Back restores camera |
| full | `function_menu_roundtrip` | visible Fn entry → enabled tiles → Back restores camera chrome |
| full | `debug_snapshot_ui_contract` | destructive + settings: HAL-free, scenario-ready snapshot activity at 0/90/270° covers maximal OSD, direct Fn/settings and Fn editor, MR/ruler/Loupe, RAW/loading/error/delete review, permission/rationale, RTL and 2× font; temporarily applies an exact 320×300 dp compact-wide display override, asserts semantic bounds/action non-overlap, then restores prior size/density/orientation state in `finally` |
| full | `mode_persists_across_kill` | Remember Settings survives force-stop |
| full | `per_lens_still_geometry` | each rear preset (logical route) + front camera still == that accepted camera's dumpsys-advertised binned array; row↔file parity; 200MP stays dormant |
| full | `tele_dng_parity` | TELE DNG is the advertised RAW16 plane (16-bit, SamplesPerPixel 1, CFA) and DNG+JPEG EXIF ISO/ExposureTime match a UI-set manual request (ISO 800, ~1/100 s) |
| full | `video_container_truth` | SDR + HLG clips (plus the persisted log preset) carry the documented container color policy (never the ST2084/PQ mistag); admitted-spec transfer, decode, row↔file parity |
| reliability | `rec_teardown_soak` | 5×4 s back-to-back REC/finalize/re-arm before any pull; exact five-row delta, full decode/cadence/audio contract, pending=0 |
| reliability | `recording_snapshot_preserves_video` | forces Burst+10 s Photo settings, proves one prompt mid-REC still, restores both, validates exact still+MP4 delta/codec/audio |
| reliability | `capture_then_kill_survives` | kill shortly after shutter (0.6 s aim; the idle-proof dump adds ~2-3 s — measured delta reported) → files survive, valid, no stuck pending |
| reliability | `rec_backgrounded_finalizes` | HOME mid-REC → admitted-spec identity, finalization evidence, playable ≥3.5 s clip, no stuck pending |
| reliability | `rec_stop_then_kill_published` | kill 0.5 s after stop → clip adopted+published by launch recovery |
| reliability | `no_stuck_pending_baseline` | fresh launch sweep leaves zero owned `IS_PENDING=1` rows |

## Device facts the harness encodes

- Wireless-debugging drops recover with a plain `adb connect` (never `adb kill-server` — it
  kills sibling agents' connections). The `Adb` wrapper auto-reconnects once per command.
- Injected taps can silently vanish over the proxy: every interaction re-dumps the UI tree
  or checks logcat afterward instead of assuming delivery.
- The QTI HAL logs a benign framework-level `-38` "Error clearing streaming request" during
  photo↔video teardown; the app contains it, so the fatal-line scanner whitelists exactly
  that signature (`LOGCAT_BENIGN`).
- A flat-lying phone yields a dark, noisy viewfinder: liveness is asserted by frame
  DIFFERENCE (sensor noise), never by brightness.
- `uiautomator dump` is the interaction source of truth — the app's Compose semantics expose
  stable content-descriptions (`Teleconverter`, `Start recording`, `0.6× lens`, …).
- Image/video evidence is keyed by `(collection, _id)`, not directory filename. Queries include
  owned pending rows and wait for a stable family with `IS_PENDING=0` and non-zero `_size`.
- HEIF dimensions are checked twice: MediaStore metadata and the full ImageIO canvas decoded by
  macOS `sips`. This avoids mistaking individual HEIF tiles for the complete image dimensions.
- Still cases require the persisted drive mode to be Single and report non-green incomplete for
  Burst/AEB/Timelapse. The harness never rewrites the photographer's saved drive setting.
- The strict video case requires the visible persisted preset to already be HEVC + HLG + 29.97p;
  it never changes settings. It cross-checks the OSD resolution/bitrate with the admitted encoder
  spec, decodes both video and AAC frames, and compares A/V start/end PTS. Missing `ffprobe`,
  audio-off, or a different preset is non-green.
- REC cleanup retries dropped Stop taps, then requires exact capture-id evidence that codec/audio
  drain, muxer finalization, and MediaStore publish completed. Any unproven or failed terminal state
  aborts every remaining case instead of continuing with live/leaked recorder resources.

## Known non-coverage (deliberate)

- Real two-finger pinch (adb cannot inject multitouch) — zoom is exercised via presets;
  pinch feel needs a human. Instrumented Espresso tests could add this later.
- Hardware camera-control button (`adb input keyevent` does not reach the app — device fact).
- Manual-exposure LONG shots (S/M-mode 4 s ceiling): still a human/host concern — the 4 s
  HAL ceiling clamps are host-tested in `app/src/test/`. `tele_dng_parity` (cycle 7) does
  drive the ISO/shutter rulers with closed-loop tick drags (readout re-read after every
  swipe; probed 42 px/stop, ~28 px slop, LEFT = higher), but only to short, safe stops.
- Front (selfie) camera mirror/rotation SIGNS — `per_lens_still_geometry` now automates the
  front flip, capture, geometry, and accepted-route facts (cycle 7), but the saved-still
  MIRROR truth (unmirrored subject text) remains a human check: it was manually QA-verified
  2026-07-23 (`.context/reviews/qa-adversary.md`) and the signs stay verification-pending in
  `docs/BACKLOG.md`.
- Hi-res (200 MP remosaic) stills — dormant on PMA110: the capability is not exposed to
  third-party Camera2 (probed 2026-07-22, see CLAUDE.md), so there is nothing on this device
  for a case to exercise. The admission seams are host-tested in `app/src/test/`.
- Log-profile PICTURE validation — `video_container_truth` (cycle 7) now device-validates the
  container color policy for SDR, HLG, and whichever log profile is persisted (S-Log3.Cine
  verified 2026-07-24: BT.2020 full-range, CICP-14/bt2020-10 SDR-class transfer, never
  ST2084/PQ). The remaining non-coverage is the log CURVE's visual correctness (a grading
  judgment) and the two log profiles not currently persisted; `RECORDING_SPEC` still matches
  all five transfer names so any persisted profile fails honestly instead of timing out.
- Visual quality judgments (HLG look, OIS effectiveness, uprightness in hand) — human checks,
  tracked in `docs/BACKLOG.md`.
