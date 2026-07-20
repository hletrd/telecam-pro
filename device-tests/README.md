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
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier smoke          # ~30 s, every deploy
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full           # read-only cases; stateful cases skip
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier reliability    # approval-gated cases skip
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier all -k capture # substring filter

# Supply only the effects that the operator explicitly approved:
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full --allow-settings
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full --allow-settings --allow-media-writes
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier reliability \
  --allow-destructive --allow-settings --allow-media-writes
```

Reports (markdown + JUnit XML + pulled evidence files) land in `device-tests/reports/<ts>/`
(gitignored). Exit code 0 requires at least one pass and no failures, 1 means fail/error,
and 2 means preflight failure, no matching cases, all-skipped, or a required verification
reported as incomplete.

The runner refuses any device other than PMA110/API 36 and refuses an installed `base.apk`
whose SHA-256 does not match `app/build/outputs/apk/debug/app-debug.apk` (override the host
path with `--apk`). Cases are independently gated when they may launch/force-stop the app
(`--allow-destructive`), change persisted shooting settings (`--allow-settings`), or create
photos/videos (`--allow-media-writes`). These flags are execution guards, not substitutes for
obtaining operator approval. Launch is destructive-gated because app startup may reclaim proven-
incomplete app-owned pending media. Every force-stop also fails closed at the call site unless the
UI proves the app is idle, so a recording left active by an earlier failed case cannot be killed by
a later case.

## Tiers and cases

| tier | case | asserts |
|---|---|---|
| smoke | `launch_preview_live` | cold launch → live viewfinder (frame-diff), OSD chrome, clean logcat |
| smoke | `session_configured_3a` | 3A telemetry flows (configured repeating request; ois/vstab visible) |
| full | `camera_chrome_layout` | top/Fn/focal/mode/gallery/shutter bounds ≥48 dp, ordered, non-overlapping, centered |
| full | `mode_switch_roundtrip` | photo↔video sessions plus exactly one checked RadioButton mode, without camera errors |
| full | `lens_presets` | 0.6/1/3/10× cycle; exactly one RadioButton is checked after each tap |
| full | `teleconverter_roundtrip` | TC on→off round-trip; OSD `mm TELE` state tracks the toggle |
| full | `photo_capture_valid_files` | stable owned MediaStore family; pending=0, size/dims/MIME, pulled file validity |
| full | `tele_dng_capture` | TELE DNG validates as TIFF ≥1 MB; reports non-green incomplete when RAW is not enabled |
| full | `video_record_validate` | 65 s HEVC Main10 HLG 29.97p → full-frame decode, PTS cadence, AAC A/V sync |
| full | `tap_af_hold_visible_and_reset` | tap-AF holds past reticle fade with visible reset, then restores prior AF mode |
| full | `settings_sheet_tabs` | all 9 tabs select matching pages; 48 dp/on-screen; modal isolated; Back restores camera |
| full | `function_menu_roundtrip` | visible Fn entry → enabled tiles → Back restores camera chrome |
| full | `mode_persists_across_kill` | Remember Settings survives force-stop |
| reliability | `rec_teardown_soak` | 5×4 s back-to-back REC/finalize/re-arm before any pull; exact five-row delta, full decode/cadence/audio contract, pending=0 |
| reliability | `recording_snapshot_preserves_video` | forces Burst+10 s Photo settings, proves one prompt mid-REC still, restores both, validates exact still+MP4 delta/codec/audio |
| reliability | `capture_then_kill_survives` | kill 0.6 s after shutter → files survive, valid, no stuck pending |
| reliability | `rec_backgrounded_finalizes` | HOME mid-REC → playable clip finalizes |
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
- Manual-exposure ruler drags (S/M-mode 4 s ceiling shots) and O-Log2 transfer selection:
  deep settings-drag flows, deliberately left out of v1 to keep the suite non-flaky; the
  underlying clamps are host-tested in `app/src/test/`.
- Visual quality judgments (HLG look, OIS effectiveness, uprightness in hand) — human checks,
  tracked in `docs/BACKLOG.md`.
