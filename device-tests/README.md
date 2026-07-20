# device-tests — on-device functional suite (PMA110)

A repeatable, adb-driven functional test suite for TeleCam Pro on the OPPO Find X9 Ultra.
It codifies the manual device-verification protocol developed across the 2026-07 review
cycles: UI-tree-driven interaction, logcat assertions, screencap statistics, and pulled-file
validation. Pure Python 3 stdlib on the host; `ffprobe` is optional (video checks degrade to
structural MP4 validation without it).

This suite exists as an **external harness** (not `androidTest`) deliberately: the
reliability tier kills and backgrounds the app process, which in-process instrumentation
cannot survive. Host-JVM unit tests remain in `app/src/test/` (Gradle).

## Run

```bash
# device over wireless ADB (loopback proxy fine); deploy the debug APK first
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier smoke          # ~30 s, every deploy
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier full           # ~4 min feature sweep
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier reliability    # safe cases; kill cases skip
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier all -k capture # substring filter

# Only after explicit approval to force-stop the app:
python3 device-tests/run.py --serial 127.0.0.1:5599 --tier reliability --allow-destructive
```

Reports (markdown + JUnit XML + pulled evidence files) land in `device-tests/reports/<ts>/`
(gitignored). Exit code 0 requires at least one pass and no failures, 1 means fail/error,
and 2 means preflight failure, no matching cases, or an all-skipped selection.

The runner refuses any device other than PMA110/API 36 and refuses an installed `base.apk`
whose SHA-256 does not match `app/build/outputs/apk/debug/app-debug.apk` (override the host
path with `--apk`). Force-stop cases are skipped unless `--allow-destructive` is supplied;
the flag is an execution guard, not a substitute for obtaining operator approval. Every
force-stop also fails closed at the call site unless the UI proves the app is idle, so a
recording left active by an earlier failed case cannot be killed by a later case.

## Tiers and cases

| tier | case | asserts |
|---|---|---|
| smoke | `launch_preview_live` | cold launch → live viewfinder (frame-diff), OSD chrome, clean logcat |
| smoke | `session_configured_3a` | 3A telemetry flows (configured repeating request; ois/vstab visible) |
| full | `mode_switch_roundtrip` | photo↔video flips without camera errors (benign −38 teardown excluded) |
| full | `lens_presets` | 0.6/1/3/10× focal-rail presets cycle without errors |
| full | `teleconverter_roundtrip` | TC on→off round-trip; OSD `mm TELE` state tracks the toggle |
| full | `photo_capture_valid_files` | still → HEIF/JPEG pulled + parsed (dims, JPEG EXIF APP1 present) |
| full | `tele_dng_capture` | TELE-mode capture; any DNG sibling validates as TIFF ≥1 MB |
| full | `video_record_validate` | ~5 s clip → ffprobe: HEVC, sane duration, dimensions, audio |
| full | `tap_af_lock_persists` | tap-AF engages `afMode=1` and HOLDS past the 2 s reticle timeout |
| full | `settings_sheet_tabs` | settings sheet opens with the full tab rail |
| full | `mode_persists_across_kill` | Remember Settings survives force-stop |
| reliability | `capture_then_kill_survives` | kill 0.6 s after shutter → files survive, valid, no stuck pending |
| reliability | `rec_backgrounded_finalizes` | HOME mid-REC → playable clip finalizes |
| reliability | `rec_stop_then_kill_published` | kill 0.5 s after stop → clip adopted+published by launch recovery |
| reliability | `no_stuck_pending_baseline` | fresh launch sweep leaves zero `.pending-*` files |

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

## Known non-coverage (deliberate)

- Real two-finger pinch (adb cannot inject multitouch) — zoom is exercised via presets;
  pinch feel needs a human. Instrumented Espresso tests could add this later.
- Hardware camera-control button (`adb input keyevent` does not reach the app — device fact).
- Manual-exposure ruler drags (S/M-mode 4 s ceiling shots) and O-Log2 transfer selection:
  deep settings-drag flows, deliberately left out of v1 to keep the suite non-flaky; the
  underlying clamps are host-tested in `app/src/test/`.
- Visual quality judgments (HLG look, OIS effectiveness, uprightness in hand) — human checks,
  tracked in `docs/BACKLOG.md`.
