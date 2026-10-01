# Document-Specialist Review — doc/code/authoritative-source mismatches

Date: 2026-09-30 · HEAD `ba5b16e7` · Read-only review (no edits, no commits).

## Scope and method

- Docs inventoried: `CLAUDE.md`, `README.md`, `docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`,
  `docs/TESTING.md`, `docs/UX_POLICY.md`, `PRIVACY.md`, `privacy-policy/index.html`,
  `docs/play-*.md`, `device-tests/README.md`, `gradle/libs.versions.toml`, `app/build.gradle.kts`,
  `gradle/wrapper/gradle-wrapper.properties`.
- Scripted an identifier sweep: every backticked camelCase / CONSTANT_CASE token in `CLAUDE.md`,
  `ARCHITECTURE.md`, `FIELD_CHECKS.md`, and `README.md` was grepped against the tracked source.
  A second pass grepped against `app/src/main` only, which catches names that survive only in tests.
  About 90 CLAUDE.md-cited symbols were also checked one by one against main sources.
- Every `*.kt` file in `app/src/main` (105 files) was checked for a mention in `ARCHITECTURE.md`.
  Every `.kt` file named in the docs was checked for existence.
- Numeric constants were checked against their definitions.
- The declared manifest was compared with the merged release manifest and the privacy/Data-safety documents.
- Compared `values/` against `values-ko/` string resources (names, `translatable`, format placeholders).
  Swept Compose/Kotlin code for hardcoded English. Checked bundled Inter faces with fontTools
  for glyph coverage of every non-ASCII UI character.
- Latest versions were queried live from Google Maven / Maven Central `maven-metadata.xml`,
  `services.gradle.org/versions/current`, and the AGP release-notes page on developer.android.com.
- Ran `python3 tools/check_docs.py`: **188 checks, 0 failed**.

## Findings

### DS-1 (MEDIUM, confidence HIGH): stale GL API name for the front-mirror push, plus a future-tense sentence about work already done
- **Where:** `CLAUDE.md:688` ("Pushed as route state by `applyStabilization` (`gl.setFrontStreamPreMirrored`)");
  `docs/ARCHITECTURE.md:551` ("pushed as route state by `GlPipeline.setFrontStreamPreMirrored`").
- **Code truth:** no `setFrontStreamPreMirrored` exists anywhere in the repo. The push is
  `gl.setFrontMirrorConvention(front, streamPreMirrored)`. It is defined at `gl/GlPipeline.kt:544`
  and called from `CameraEngine.applyStabilization` at `camera/CameraEngine.kt:1918-1921`.
  The roles are derived in `FrontMirrorConvention.kt`.
- **Also stale:** `CLAUDE.md:688-689` says "On a multi-device build this inversion becomes a
  DeviceProfile quirk flag." That already happened. `DeviceProfile.frontStreamPreMirrored` is at
  `camera/DeviceProfile.kt:22`, with PMA110 `true` at :65 and GENERIC `false` at :74. ARCHITECTURE.md:89 already describes this correctly.
- **Impact:** an agent following the authority doc will grep for a function that does not exist. It may
  also "implement" a quirk flag that already exists and fork the mirror authority.
- **Fix:** replace both citations with `GlPipeline.setFrontMirrorConvention(front, streamPreMirrored)`.
  Rewrite the CLAUDE.md sentence in past tense: "Since 2026-08-01 this inversion is the
  `DeviceProfile.frontStreamPreMirrored` quirk; GENERIC takes the naive roles." Also note that
  `mapTapFocusGeometry(mirrorX=false)` is the PMA110 value, not a universal constant.
- **Status:** open.

### DS-2 (LOW, confidence HIGH): `statusDisplayDurationMs` no longer exists
- **Where:** `CLAUDE.md:1141` ("because `statusDisplayDurationMs` classifies by wording").
  `docs/BACKLOG.md:78` (private) says the same.
- **Code truth:** the symbol is gone from main sources. Duration is now a typed property:
  `CameraStatus.durationMs` (`camera/CameraStatus.kt:96`), resolved per `CameraStatusMessage` with the
  2.5 s default at `camera/CameraStatus.kt:190` and `PROGRESS` giving null. The only remaining
  mention is a test comment at `app/src/test/.../ui/CameraViewModelRobolectricTest.kt:762`.
- **Impact:** the sentence reads as present-tense mechanism, but the wording-based classifier was replaced by the typed enum. Anyone
  hunting that function to adjust a timeout will find nothing.
- **Fix:** rephrase as history ("the former wording-classified `statusDisplayDurationMs`…") and point to
  `CameraStatus.durationMs` / `CameraStatusLifecycle`. Update the test comment too.
- **Status:** open.

### DS-3 (MEDIUM, confidence HIGH): the glyph-coverage rule contradicts the EN+KO constraint
- **Where:** `CLAUDE.md:562-563` says "Every user-facing literal must stay inside those faces". It refers to the three bundled Inter
  faces in `app/src/main/res/font/`.
- **Truth (verified with fontTools):** none of `inter_regular.ttf`, `inter_medium.ttf`, or `inter_semibold.ttf`
  carries Hangul (U+AC00 absent, 2852 cmap entries each). All 478 `values-ko` strings therefore render in a
  system fallback face by construction. The rule as written is violated by every Korean string, and
  the same file mandates those strings (CLAUDE.md:45-52).
  Positive result: every **non-Hangul** non-ASCII character in `values*/strings.xml` and in Kotlin
  string literals is covered by Inter. U+2192 is present and U+25B8 is absent, so the actual intent of the rule currently holds.
- **Impact:** a literal reading of the rule could push someone to strip Korean or bundle a large CJK font.
  It also hides the real question: whether mixed Inter plus system-Hangul metrics in one OSD tag are acceptable
  (for example `너무 가까움 → 1×`).
- **Fix:** scope the rule: "Every non-Hangul symbol in a user-facing literal must be in the Inter faces;
  Hangul intentionally uses the system face." Optionally add a host test that asserts non-Hangul glyph coverage.
  The check above is a 20-line fontTools script and can be added to `tools/check_docs.py`.
- **Status:** open.

### DS-4 (MEDIUM, confidence HIGH): toolchain pins are behind the latest stable (policy: "bump when newer stable ships")
The CLAUDE.md toolchain table matches the build exactly. AGP 9.3.2, Kotlin 2.4.10, Gradle wrapper 9.7.1,
BOM 2026.08.00, compileSdk/targetSdk/minSdk 37/36/33 (`app/build.gradle.kts:493,504,505`),
`jvmToolchain(21)` (`:640`), and heifwriter 1.1.0 all agree. The table is simply out of date against the registries
(queried 2026-09-30):

| Component | Pinned (`gradle/libs.versions.toml` / wrapper) | Latest stable | Note |
|---|---|---|---|
| AGP | 9.3.2 (`libs.versions.toml:5`) | **9.4.1** (9.3.3 patch also exists) | AGP 9.4 needs Gradle ≥ 9.6 (met), default Build Tools 36.0.0 (matches `tools/android_sdk.py:12`), max API 37 |
| Kotlin / Compose compiler plugin | 2.4.10 (`:7`) | **2.4.20** | |
| Compose BOM | 2026.08.00 (`:11`) | **2026.09.00** | |
| Gradle | 9.7.1 (`gradle-wrapper.properties:4`) | **9.8.0** | update `distributionSha256Sum` too |
| core-ktx | 1.19.0 (`:8`) | **1.19.1** | patch |
| Robolectric | 4.16.1 (`:18-20`) | **4.17** | the toml comment says "4.17 is still beta"; that is now stale. `robolectricAndroidAll` must move in lockstep (`:26`) |
| lifecycle 2.11.0, activity 1.13.0, coroutines 1.11.0, heifwriter 1.1.0, exifinterface 1.4.2, profileinstaller 1.4.1, androidx.test core/runner 1.7.0, ext-junit 1.3.0 | — | current | no action |

- **Impact:** a policy violation (CLAUDE.md "Latest toolchain"; global "latest versions"). The doc table and the toml comment
  would both need updating with the bump.
- **Fix:** bump each item in its own commit (AGP / Kotlin / BOM / Gradle / Robolectric+android-all). Run
  `python3 tools/verify_host.py` after each and refresh `gradle/verification-metadata.xml`. Then update the CLAUDE.md
  table row by row. The Robolectric bump may also allow simulated SDK 37.
- **Status:** open, not verified by build here (read-only lane).

### DS-5 (LOW, confidence HIGH): a dead English wording function is the one the tests pin
- **Where:** `focus/MacroProximity.kt:193-200` `focusConfidenceLabel()` returns hardcoded `"SOFT"` /
  `"TOO CLOSE → $it"`. Nothing in `app/src/main` calls it. Only `FocusConfidenceTest.kt:232-242` and
  `CameraUiPolicyTest.kt:317` do.
- **Code truth:** the OSD actually renders `R.string.focus_confidence_soft/_too_close/_too_close_lens`
  (`ui/overlays/Overlays.kt:1070-1071`), with Korean at `values-ko/strings.xml:485-487`.
  `ARCHITECTURE.md:119` and `CLAUDE.md:556-563` describe `MacroProximity` as owning the OSD wording.
- **Impact:** a wording-policy regression (for example, adding a `→ <lens>` suffix to the SOFT string resource, which
  CLAUDE.md forbids) would pass every test, because the tests guard a copy the UI never shows.
- **Fix:** either delete `focusConfidenceLabel` and move the invariant tests onto the resource-selection
  seam in `Overlays.kt`, or make the function return a typed key (`SOFT` / `TOO_CLOSE(lens?)`) that the UI
  maps to resources, and test that. Update ARCHITECTURE.md:119 accordingly.
- **Status:** open.

### DS-6 (LOW, confidence MEDIUM): latent English fallbacks on localized UI paths
- **Where:** `ui/controls/FnQuickActions.kt:49` has `fnSlotValue(..., context: Context? = null)`, where each slot falls back to
  English `*Label()` when `context` is null. `ui/CameraScreenPolicy.kt:504-506` has `fnOverlayVisualLabel` defaults
  `fullLabel = fnSlotLabel(slot)`, `"Stab"`, and `"Gate"`. `ui/controls/ControlLabels.kt:171` (`"Custom"`) and `:346` (`"Phone"`) are English-only
  label tables.
- **Current reachability:** none in production. Every production caller passes a localized context or
  string (`CameraScreen.kt:2561`, `:2601-2606`; `ProSheet.kt:682`).
- **Impact:** if a new caller omits the argument, it silently ships English to Korean users. Lint will not catch it, and
  the EN/KO parity check in resources cannot see it.
- **Fix:** make `context` non-null (or pass a resolver) and remove the English defaults from
  `fnOverlayVisualLabel`. Keep English `*Label()` functions test/diagnostic-only (for example `@VisibleForTesting`).
- **Status:** open (defensive).

### DS-7 (INFO, confidence LOW): the privacy contact address should be confirmed as a monitored mailbox
- `01@0101010101.com` is used consistently in `PRIVACY.md:62`, `privacy-policy/index.html:296,412`,
  `docs/play-store-listing.md:15`, and `docs/play-console-submit.md:560`. It is consistent but looks like a placeholder.
  Google Play requires a working contact. If it is intentional and real, no action is needed.

### DS-8 (INFO): several documented timings are unnamed literals
- `ui/CameraViewModel.kt:2126` (16 ms zoom flush), `:2166` (700 ms interaction end), `:2168` (250 ms quiet
  landing), and `:3930` (40 ms controls throttle) all match CLAUDE.md. However, they are bare literals, while most other
  documented numbers are named constants. Naming them (as `SETTINGS_SAVE_DEBOUNCE_MS` already is) would let the docs and
  `check_docs.py` anchor to them. Optional.

## Verified consistent (no action)

- **Constants:** these all match their docs.
  - `PREVIEW_FRAME_GAP_THRESHOLD_MS = 200L` and 15 s summary (`DiagnosticTelemetry.kt:340-341`).
  - `RECURRING_DIAGNOSTIC_ROW_BUDGET = 180`, `RESERVED_… = 120`, `COLOR_OS_PROCESS_LOG_ROW_LIMIT = 300` (`:31-33`).
  - `HAL_SAFE_MAX_STILL_EXPOSURE_NS = 4 s` (`CaptureCapabilities.kt:20`).
  - `PREVIEW_FLUIDITY_MAX_EXPOSURE_NS = 1/15 s`, `PREVIEW_SAFE_MAX_EXPOSURE_NS = 500 ms`, `PREVIEW_MAX_DIGITAL_GAIN = 16`, `CAPTURE_WATCHDOG_FLOOR_MS = 8 s` (`ManualControls.kt:325,340,341,484`).
  - `TELE_MAX_DISPLAY_ZOOM = 60f`, `FINDER_MIN_ZOOM = 3f` (`CameraState.kt:353,397`).
  - `SENSOR_SUBMIT_MIN_INTERVAL_MS = 200L` (`CameraController.kt:2409`).
  - `SETTINGS_SAVE_DEBOUNCE_MS = 500L` (`CameraViewModel.kt:4094`).
  - ZSL: 400 ms / 1/6 stop / 2 % / depth 3 (`ZslAdmission.kt:36-45`).
  - `MAX_STEP_STOPS 0.30` / `MAX_FAR_STEP_STOPS 1.20` (`AutoExposure.kt:44-45`).
  - `MACRO_HOLD_MS = 700`, `FOCUS_DETAIL_MAX_AGE_MS = 1 s`, ×16 shutter gate (`MacroProximity.kt:40,63,77`).
  - `ZOOM_GESTURE_MARGIN = 1.2f` (`CameraEngine.kt:7864`).
  - `FLAT_GRAVITY_THRESHOLD = 4.9` (≈½ g) and `LEVEL_GRAVITY_THRESHOLD = 2.5` (`GyroEis.kt:328,332`).
  - `TELECONVERTER_MAGNIFICATION = 300/70`.
  - Pre-native allocator backlog 4 and rejected-output 2 workers + 8 backlog.
  - 3840-px video width cap (`CameraEngine.kt:7703,7714`).
- **Identifiers:** apart from DS-1 and DS-2, every CLAUDE.md / ARCHITECTURE.md cited symbol and every `.kt` path exists.
  All 105 `app/src/main` Kotlin files appear in ARCHITECTURE.md.
  - The names that CLAUDE.md lists as deliberately removed are absent, as claimed: `VendorLogMode`, `vendorLogMode`, `setNativeLog`,
    `delogAssist`, `com.oplus.VideoColorBT709`, and `landscapeOperator`.
  - `tenBitExperimentEnabled` survives as documented.
- **Permissions:** the manifest declares CAMERA, RECORD_AUDIO, READ_MEDIA_IMAGES/VIDEO/VISUAL_USER_SELECTED, and removes
  INTERNET and ACCESS_NETWORK_STATE.
  - The merged release manifest adds only the signature-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
  - This matches `PRIVACY.md`, `privacy-policy/index.html` (EN and KO sections), and `docs/play-data-safety.md:8-9`.
  - There is no location permission, as the docs claim.
  - `localeConfig` is generated through `androidResources.generateLocaleConfig = true` (`app/build.gradle.kts:593-600`).
- **Strings:** 496 EN entries and 478 KO entries. The 18 non-KO entries are exactly the `translatable="false"` set, and **0 translatable strings lack Korean**.
  No KO-only keys. Format placeholders match in every pair. The only KO value identical to EN is `fn_short_focal_mm` (`%1$dmm`,
  a unit). There are no hardcoded English `Text("…")` / `contentDescription` literals in Compose code. The remaining literals are `"×"`, `"4:3"`, and
  `"T${s}s"`. Statuses flow through `CameraStatusMessage`, which is localized.
- **SDK claims:** Platform 37 / Build Tools 36.0.0 (README.md:114, CLAUDE.md:81, `tools/android_sdk.py:12`)
  agree with AGP 9.4's default Build Tools.
- **Secrets:** `keystore.properties`, `*.jks`, `telecampro-upload-passwords.txt.gpg`, and `local.properties` sit in the
  working tree but are **untracked**. Only `keystore.properties.example` is tracked.

## Counts
8 findings: 0 HIGH, 3 MEDIUM (DS-1, DS-3, DS-4), 3 LOW (DS-2, DS-5, DS-6), 2 INFO (DS-7, DS-8).
