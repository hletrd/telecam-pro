# Security-Reviewer — Run-2 Cycle 1 (2026-07-06)

OWASP-style sweep. No High/Med security findings this cycle.

- **Permissions:** `AndroidManifest.xml` declares only CAMERA + RECORD_AUDIO; both requested at
  runtime with a permanent-denial Settings deep-link (`MainActivity.kt:62-111`). No network
  permission → no network exfiltration surface.
- **Storage:** scoped MediaStore, `IS_PENDING` write-then-publish, delete-on-failure across
  HEIF/JPEG/DNG/MP4. Fixed `RELATIVE_PATH` (`DCIM/X9Tele`), app-generated display names → no path
  traversal, no world-readable temp files.
- **Backup:** `allowBackup=false` + custom extraction/backup rules — captures/settings don't leak via
  backup.
- **Reflection / restricted APIs:** only the documented heifwriter-alpha `@SuppressLint("RestrictedApi")`
  (`HeifCapture.kt:21`) and `VendorTagInspector`, `BuildConfig.DEBUG`-gated (`CameraEngine.kt:131-134`).
- **Secrets:** none in source. **`exported="true"`** on `MainActivity` is required for LAUNCHER and
  exposes no extra intent-filters.

No findings scheduled or deferred from this angle. (R1-1 capture race is correctness/data-loss, owned
by code-reviewer, SCHEDULED there.)
