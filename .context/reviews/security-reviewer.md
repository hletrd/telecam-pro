# Security Review Report — find-x9-ultra-camera

**Reviewer:** Security Reviewer (OWASP Mobile + Android-specific)
**Date:** 2026-07-02
**Scope:** Full repo. Manifest, gradle/build config, proguard, res/xml backup & data-extraction rules, MediaStoreWriter, DngCapture/HeifCapture, VideoRecorder, MainActivity/CameraViewModel permission flows, VendorTagInspector reflection, CameraController/CameraEngine/CameraSelector2, GyroEis, ColorProfiles, local.properties, libs.versions.toml, .gitignore, git history.
**Package:** com.hletrd.findx9tele — Android 16 (API 36), Kotlin + Compose + Camera2 + OpenGL ES.

## Risk Level: **LOW**

This is a single-device, offline camera app with an unusually small attack surface. No `INTERNET` permission, no exported IPC beyond the launcher activity, no secrets, no native/reflection/dynamic-code loading, no WebView, and scoped-storage-correct MediaStore usage. Every finding below is hardening / low-severity; none are remotely exploitable and none expose user data across a trust boundary on a stock device.

## Summary
- Critical Issues: 0
- High Issues: 0
- Medium Issues: 0
- Low / Hardening Issues: 4
- Positive controls confirmed: 10 (see "Verified Non-Issues")

---

## Low / Hardening Findings

### 1. Vendor-tag / camera-characteristics debug dump always-on in the release build
- **Location:** `app/src/main/kotlin/com/hletrd/findx9tele/camera/CameraEngine.kt:71` (call site) → `app/src/main/kotlin/com/hletrd/findx9tele/camera/VendorTagInspector.kt:18-48`
- **OWASP/CWE:** OWASP Mobile M6 (Inadequate Privacy Controls) / CWE-532 (Insertion of Sensitive Information into Log File)
- **Severity:** LOW | **Confidence:** High | **Classification:** Confirmed
- **Why it's a problem:** `VendorTagInspector.dumpAll(manager)` runs on *every* camera start (inside the GL start callback), unconditionally — it is **not** gated behind `BuildConfig.DEBUG`. It writes each camera's hardware characteristics, OPPO/QTI vendor tags (`com.oplus.*`, `org.quic.*`, `com.qti.*`), and available request/session keys to Logcat at `Log.i`. The release build is also non-minified (see Finding 3), so the diagnostic path ships verbatim in production.
- **Exploit / failure scenario:** On stock Android (>= 4.1) third-party apps cannot read another app's logcat without the signature/privileged `READ_LOGS` permission, so this is not cross-app exploitable. Real exposure requires local access: ADB (`adb logcat -s X9TeleVendor`), a captured bug report, or a device-log-collecting OEM/MDM agent. The leaked data is hardware/firmware fingerprinting metadata (sensor sizes, focal lengths, vendor HAL tags) — useful for device fingerprinting, not user PII. It also increases the reverse-engineering surface for the intended "native teleconverter stabilization" research.
- **Suggested fix:** Gate the dump behind `if (BuildConfig.DEBUG)` (and/or a hidden developer toggle), or remove the call from the production start path. Example:
  ```kotlin
  if (BuildConfig.DEBUG) VendorTagInspector.dumpAll(manager)
  ```
  (Requires `buildConfig = true` in `buildFeatures`.)

### 2. `allowBackup="true"` with empty/permissive backup + data-extraction rules
- **Location:** `app/src/main/AndroidManifest.xml:12-14`; `app/src/main/res/xml/backup_rules.xml` (empty `<full-backup-content>`); `app/src/main/res/xml/data_extraction_rules.xml` (empty `<cloud-backup/>` + `<device-transfer/>`)
- **OWASP/CWE:** OWASP Mobile M9 (Insecure Data Storage) / CWE-530 (Exposure of Backup File to Unauthorized Control Sphere)
- **Severity:** LOW | **Confidence:** High | **Classification:** Risk / hardening
- **Why it's a problem:** `allowBackup=true` combined with rule files that specify **no exclusions** means "back up and transfer everything eligible in app-private storage." Today the app persists **nothing sensitive** to private storage (photos/videos go to shared `DCIM` via MediaStore; no SharedPreferences, DataStore, DB, or tokens exist), so the concrete data-at-risk is currently empty. The risk is latent: if a future change stores settings, camera-override pins, or any credential in app-private storage, it will silently flow into Auto Backup / cloud / device-to-device transfer where it can be extracted by anyone with the linked account or physical device.
- **Exploit / failure scenario:** Attacker with the user's Google account (or a `adb backup`-capable local session on OEM builds that still honor it) restores app-private data that a future version writes. Not exploitable against the current codebase.
- **Suggested fix:** Either set `android:allowBackup="false"` (simplest for a stateless camera app), or add explicit `<exclude>` rules for any future sensitive paths in both `backup_rules.xml` and `data_extraction_rules.xml`.

### 3. Release build is non-minified / non-obfuscated; ProGuard rules effectively unused
- **Location:** `app/build.gradle.kts:20-27` (`isMinifyEnabled = false`); `app/proguard-rules.pro` (empty comments only)
- **OWASP/CWE:** OWASP Mobile M7 (Insufficient Binary Protections) / CWE-656 (Reliance on Security Through Obscurity — informative)
- **Severity:** LOW | **Confidence:** High | **Classification:** Hardening
- **Why it's a problem:** The v1 release ships without R8 shrinking/obfuscation. This is intentional per the comment, but it enlarges the reverse-engineering surface and ships all diagnostic code (e.g., Finding 1) and full symbol names. Minification is not a control by itself, but its absence removes a defense-in-depth layer and leaves dead diagnostic paths in the shipped APK.
- **Exploit / failure scenario:** No direct exploit; eases static analysis / RE of the app. Low impact given there are no secrets to hide.
- **Suggested fix:** Enable `isMinifyEnabled = true` (and `isShrinkResources = true`) for `release`; the existing `proguard-android-optimize.txt` + rules file are already wired. Verify Camera2/EGL reflection-touched classes survive (rules file is prepared for exactly this).

### 4. Alpha-stage dependency shipped in a release build
- **Location:** `gradle/libs.versions.toml:10` — `heifwriter = "1.2.0-alpha01"` (consumed at `app/build.gradle.kts:50`)
- **OWASP/CWE:** OWASP Mobile M2 / OWASP A06:2021 (Vulnerable and Outdated Components)
- **Severity:** LOW | **Confidence:** Medium | **Classification:** Risk
- **Why it's a problem:** `androidx.heifwriter:1.2.0-alpha01` is a pre-release used to encode still images (`HeifCapture`). Alpha artifacts carry API instability and are more likely to contain un-triaged defects; parsing/encoding libraries that touch untrusted-shaped buffers are a historically sensitive class. No known CVE is attributable here.
- **Exploit / failure scenario:** A latent encoder bug could cause crashes/corruption on specific bitmaps; not a demonstrated memory-safety exploit.
- **Suggested fix:** Pin to a stable `heifwriter` release before shipping, or document the alpha risk acceptance. Re-audit on each bump.

---

## Dependency Audit Note (action item, not a finding)
A live dependency audit could not be executed in this environment (offline; no `INTERNET` access, and the declared versions — `agp 9.2.0`, `kotlin 2.3.10`, `composeBom 2026.06.00`, `activityCompose 1.12.4` — are newer than an offline CVE DB can validate). Before release, run online:
- `./gradlew :app:dependencies` (resolve the full graph)
- OWASP `dependency-check` Gradle plugin or `gradle-versions-plugin` for CVE/staleness
- Confirm no CRITICAL/HIGH CVEs and replace the alpha `heifwriter` (Finding 4).

---

## Verified Non-Issues (coverage evidence — controls confirmed correct)
1. **No network surface.** No `android.permission.INTERNET` in the manifest; no `http://`, `WebView`, `TrustManager`, `SSLContext`, `usesCleartextTraffic`, or `networkSecurityConfig`. SSRF (M10/A10), TLS, and cleartext categories are not applicable. The app cannot exfiltrate captured media.
2. **No secrets.** Scanned `*.kt/*.kts/*.xml/*.properties/*.toml/*.pro` and full `git log -p --all` for `api_key|secret|password|token|credential|private key|BEGIN RSA/PRIVATE|aws_|access_key` — zero matches. `local.properties` contains only `sdk.dir` and is untracked.
3. **Secrets hygiene in VCS.** `.gitignore` excludes `local.properties`, `*.jks`, `*.keystore`, `keystore.properties`; git history contains no leaked signing material.
4. **Minimal, justified permissions.** Only `CAMERA` and `RECORD_AUDIO` (`AndroidManifest.xml:4-5`) — both used. No `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `ACCESS_*_LOCATION`, `READ_PHONE_STATE`, or other dangerous/over-broad grants.
5. **Runtime permission gating present.** `MainActivity.kt:70-71` checks `CAMERA` before showing the preview and requests via `RequestMultiplePermissions`; it degrades gracefully if only `CAMERA` is granted (`MainActivity.kt:54`). `VideoRecorder.kt:64,221-223` re-checks `RECORD_AUDIO` at runtime before instantiating `AudioRecord` and drops the audio track if denied — no `MissingPermission` bypass despite the `@SuppressLint`.
6. **Exported-component surface is minimal and inert.** Only `MainActivity` is `exported=true` and solely for the `MAIN`/`LAUNCHER` filter (`AndroidManifest.xml:20-30`). No exported services, receivers, or content providers; no `FileProvider`. `MainActivity` never reads `Intent` extras/data (`getIntent()` unused), so there is no intent-injection / deep-link surface.
7. **Scoped-storage-correct MediaStore usage.** `MediaStoreWriter.kt` writes under `DCIM/<subDir>` via `RELATIVE_PATH` using the `IS_PENDING` publish pattern (`:41-79`); no legacy external-storage paths, no `MODE_WORLD_*`, no `openFileOutput`, no `createTempFile`. File names are app-generated from a fixed prefix + timestamp (`CameraEngine.kt:239-242`), not user input, so no path traversal via `DISPLAY_NAME`; MediaStore additionally sanitizes display names.
8. **No dangerous dynamic execution.** No `Runtime.exec`/`ProcessBuilder`, no `System.loadLibrary`/JNI, no `Class.forName`/`getDeclaredMethod`/`setAccessible`/`invoke` reflection, no dynamic class/dex loading. `VendorTagInspector` uses only public Camera2 characteristic APIs (read-only enumeration), not reflection.
9. **Camera-override input is not an attacker surface.** Although `CameraSelector2.select` parses an `overrideId` string (`CameraSelector2.kt:32-40`) and it reaches `manager.openCamera`, the UI only ever calls `onCameraOverride(null)` to reset (`ProControls.kt:528`) and `CameraScreen` no-ops the action (`CameraScreen.kt:344`). No path sets an arbitrary id, and Camera2 validates ids regardless. Worst case is a self-inflicted crash, not privilege escalation.
10. **No location / geotag leakage.** No location permission is requested, so `DngCreator` (`DngCapture.kt`) and HEIF/EXIF output (`HeifCapture.kt`) carry no GPS metadata; only standard sensor/camera metadata is written. JPEG orientation is explicitly normalized (`CameraController.kt:168`).

---

## OWASP Coverage Matrix
| Category | Status |
|---|---|
| A01 Broken Access Control / M6 | OK — no IPC surface beyond launcher; permissions runtime-gated |
| A02 Cryptographic Failures / M10 | N/A — no crypto, no secrets, no network |
| A03 Injection | N/A — no SQL/command/query building; file names app-generated |
| A04 Insecure Design | OK — minimal, offline, single-purpose design |
| A05 Security Misconfiguration | LOW — Findings 1–3 (debug dump, backup flags, non-minified) |
| A06 Vulnerable Components / M2 | LOW — Finding 4 (alpha heifwriter); full audit deferred to online |
| A07 Auth Failures | N/A — no auth/session/JWT |
| A08 Data/Software Integrity | OK — pinned repos (google/mavenCentral), version-catalog pinned deps, wrapper `validateDistributionUrl=true` |
| A09 Logging Failures / M6 | LOW — Finding 1 (over-logging hardware metadata) |
| A10 SSRF / M10 | N/A — no outbound requests, no INTERNET permission |

## Security Checklist
- [x] No hardcoded secrets (source + git history)
- [x] All external inputs validated (no external input surface reaches sinks)
- [x] Injection prevention verified (no query/command construction)
- [x] Authentication/authorization verified (permission-gated; no auth system)
- [~] Dependencies audited (static review done; live CVE audit deferred — offline; replace alpha heifwriter)
- [x] Exported components reviewed (only inert launcher activity)
- [x] Scoped storage / file handling reviewed (no traversal, no world-readable files)
- [x] Backup/data-extraction rules reviewed (hardening: Finding 2)

## Prioritized Remediation
1. **Finding 1 (LOW, Confirmed):** Gate `VendorTagInspector.dumpAll` behind `BuildConfig.DEBUG` — quick win, removes always-on diagnostic logging from release.
2. **Finding 4 (LOW):** Replace `heifwriter 1.2.0-alpha01` with a stable release before shipping.
3. **Finding 2 (LOW):** Set `allowBackup=false` or add explicit exclude rules (future-proofing).
4. **Finding 3 (LOW):** Enable R8 minify/shrink for `release`.
5. **Action item:** Run an online dependency CVE audit before release.
