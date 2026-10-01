# Document-specialist review: RPL cycle 4 (HEAD 14767b0a)

Angle: doc/code mismatches across CLAUDE.md, docs/ARCHITECTURE.md, docs/FIELD_CHECKS.md, README.md,
docs/play-*.md, KDoc, string resources, the build config, and the version pins. Cycle-3 doc edits
(`git diff e3a2bdd4..HEAD -- CLAUDE.md docs README.md`) were re-checked line by line against code.
Every finding below was checked against the on-disk files at HEAD.

## Baseline checks that came back clean

- **Version pins are all at the latest stable release** (registry queries made 2026-10-02):
  AGP 9.4.1, Kotlin / Compose compiler 2.4.20 (2.5.0-Beta1 is the only newer one, and it is not
  stable), Gradle 9.8.0 (services.gradle.org `current`), Compose BOM 2026.09.00, core-ktx 1.19.1,
  activity-compose 1.13.0, lifecycle 2.11.0, coroutines 1.11.0, heifwriter 1.1.0, exifinterface
  1.4.2, profileinstaller 1.4.1, androidx.test core/runner 1.7.0, ext-junit 1.3.0, Robolectric 4.17,
  junit 4.13.2. Build Tools 37.0.0 is now stable, but 36.0.0 is the default in AGP 9.4.1
  (`ToolsRevisionUtils` in builder-9.4.1.jar), so README/ARCHITECTURE "36.0.0 (the AGP 9.4 default)"
  is correct. CLAUDE.md, README, and ARCHITECTURE toolchain tables all match
  `gradle/libs.versions.toml`, the wrapper (9.8.0), and `app/build.gradle.kts` (37/36/33, JDK 21).
- **Strings:** 483 translatable EN keys, 483 KO, none missing or extra. Placeholder differences only
  come from EN-only plural quantities. No literal in `values*/strings.xml` or in any Kotlin string
  literal uses a glyph outside the shared cmap of the three bundled Inter faces (checked with
  fontTools).
- **Manifest vs Data Safety/PRIVACY:** the declared permissions match (CAMERA, RECORD_AUDIO, the
  READ_MEDIA trio). INTERNET and ACCESS_NETWORK_STATE are `tools:node="remove"`. The only other
  merged entry is androidx.core's signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which
  is not a runtime/data permission.
- **Platform claims checked against primary sources:** `SystemBarStyle.dark` → light icons (matches
  `MainActivity.kt:209-213`). For MediaStore `DATE_EXPIRES` on pending rows, AOSP mainline
  `FileUtils.computeDateExpires` sets now + `DEFAULT_DURATION_PENDING` (7 days) whenever
  `IS_PENDING=1` appears in insert or update values (`MediaProvider` insert path and update path
  both call it). See DOC4-5 for a side effect the docs omit.
- **Constants quoted in CLAUDE.md** match the code: 1/15 s fluidity cap, 500 ms safe cap, 4 s still
  ceiling, ×16 gain, FINDER_MIN_ZOOM 3, 60× cap, 200 ms submit interval, 8 s watchdog floor and DNG
  allocation deadline, ZSL 1/6 stop, 2 %, 400 ms, depth 3, AE 0.30/1.20 stops, log budgets 180/120/300,
  FrameGap 200 ms / 15 s, worker/backlog counts 2+2, 2+8, 2+4, settings debounce 500 ms, and gravity
  thresholds 4.9/2.5.
- `python3 tools/check_docs.py`: 190 checks, 0 failed. The cycle-3 AGG3-37 fix holds.
- Every cycle-3 CLAUDE.md symbol exists and behaves as written: `dngIntentChangesRearRoute`,
  `rollbackRawWanted`, `CameraUiState.effectivePhotoFormats`, `meteringMirrorX`,
  `FrontMirrorConvention`, `cleanupOrphanedPendingBatch`, `deviceProfileForRoute`,
  `resolveMustUseYuvStill`, `rawStandaloneOnly`, `runRecorderWorkerNative(token)`, and the
  DeviceProfile field readers. The ARCHITECTURE identifier in DOC4-3 is the one that does not exist.

## Findings

### DOC4-1: README still states the PMA110 RAW-routing law, and "not by model name", as universal
- Severity: Medium. Confidence: High. Status: Confirmed.
- Doc:
  - `README.md:54-55` says "Wanting RAW is what routes photo onto a standalone camera, so DNG is
    offered on any rear lens that advertises it".
  - `README.md:100-101` says "Hardware is resolved by enumerating Camera2 capabilities rather than by
    model name".
- Code:
  - `camera/DeviceProfile.kt:83-84` resolves `PMA110` vs `GENERIC` from `Build.MODEL`.
  - `GENERIC.rawRequiresStandalone = false` feeds `standaloneRouteWanted(..., rawForcesStandalone)`
    (`CameraState.kt:537-543`; engine `CameraEngine.kt:612, 4085, 4111`), so on every non-PMA110
    device DNG stays on the seamless logical camera.
- Why: cycle 3 (AGG3-47, commit 3d0946ab) scoped this law to the PMA110 profile in CLAUDE.md and
  ARCHITECTURE, but did not touch README, the public, Play-linked front page. ARCHITECTURE's blanket
  qualifier ("statements elsewhere in this document and in CLAUDE.md … describe the PMA110 profile",
  `docs/ARCHITECTURE.md:703-704`) does not cover README. ARCHITECTURE's own overview `:27`, journey
  step `:247`, and Stills paragraph `:847` are covered by that qualifier, but they still read as
  universal when quoted alone.
- Consequence: the public description of route behaviour is wrong on every device the app was
  opened up to on 2026-08-01. The "not by model name" sentence denies the sanctioned model-string
  seam that CLAUDE.md documents.
- Fix: README:54 should say "On the Find X9 Ultra, wanting RAW moves photo onto a standalone camera
  (that HAL cannot carry RAW on its logical camera); elsewhere DNG stays on the seamless camera when
  advertised". README:100 should add "…; measured HAL workarounds for the Find X9 Ultra are keyed to
  its model in one place (`DeviceProfile`)". Optionally add "(PMA110)" at ARCHITECTURE `:27/:247/:847`.

### DOC4-2: `standaloneRouteWanted`'s KDoc is detached 120 lines away and still claims the law is universal
- Severity: Low-Medium. Confidence: High. Status: Confirmed.
- Doc: `camera/CameraState.kt:416-429` ("Whether the rear PHOTO route must pin a STANDALONE lens … RAW
  cannot come off it … Every physical camera on this device…") sits directly above ANOTHER KDoc
  (`:430`, the optical-base helper). The function it describes is at `:537`.
- Code: `CameraState.kt:537-543`. Its parameter comment says the opposite of the detached KDoc ("A
  spec device carries RAW on the logical route").
- Why / consequence: Dokka and IDE hover show nothing (or the wrong text) for the central
  route-question function. A reader who finds the prose believes the PMA110 law is universal. This
  is the same drift class as DOC4-1, in the code authority itself.
- Fix: move the block onto `standaloneRouteWanted` and qualify it ("On the PMA110 profile
  (`rawRequiresStandalone`)…; GENERIC keeps RAW on the logical camera; VIDEO's pin is the universal
  EIS decision").

### DOC4-3: ARCHITECTURE's new DeviceProfile table names a method that does not exist
- Severity: Low. Confidence: High. Status: Confirmed.
- Doc: `docs/ARCHITECTURE.md:710` ("pushed by `CameraEngine` as `gl.setFrontStreamPreMirrored`").
  This was introduced by cycle-3 commit 3d0946ab.
- Code: no `setFrontStreamPreMirrored` exists anywhere. The push is
  `gl.setFrontMirrorConvention(front, streamPreMirrored)` in `CameraEngine.applyStabilization`
  (`CameraEngine.kt:2019-2022` → `GlPipeline.kt:546`). CLAUDE.md's Front bullet already names it
  correctly.
- Consequence: a grep-driven reader finds nothing. The table row is the cycle-3 "authority" for this
  flag.
- Fix: replace it with `gl.setFrontMirrorConvention(front, streamPreMirrored)` from
  `applyStabilization`.

### DOC4-4: Two "PENDING DEVICE" claims are missing from the FIELD_CHECKS ledger that says it is complete
- Severity: Medium. Confidence: High. Status: Confirmed.
- Doc:
  - `docs/FIELD_CHECKS.md:3` says "Everything checkable over ADB is already done", and its status
    line counts seven open items.
  - `docs/ARCHITECTURE.md:617` calls FIELD_CHECKS "the exhaustive committed … ledger".
  - Two commits in this review window leave device verification open:
    - `CLAUDE.md:974-976`: the token-scoped recorder door (4e57fff2) "is host-tested only and
      PENDING DEVICE on TB336ZU". Only the earlier owner-admitting fix had the 5/5 AAC device
      evidence.
    - `docs/ARCHITECTURE.md:1192` and the `MediaStoreWriter.kt:1135-1137` KDoc: `reassertPending`
      re-arming the pending expiry is "PENDING DEVICE".
- Why: neither item has an entry in FIELD_CHECKS (sections A–E), and both can be checked over ADB:
  a recorded clip's AAC track on TB336ZU, and `content query` of `date_expires` before and after a
  relaunch. That contradicts the ledger's own "everything checkable over ADB is done" claim.
- Consequence: a clean clone (where the private BACKLOG is absent) has no committed record that
  these need closing before the v1.0.2 re-cut. The audio item is a user-visible regression risk
  (a silent clip on the MediaTek tablet).
- Fix: add FIELD_CHECKS entries (for example A6 "TB336ZU REC audio under the token door" and E4
  "pending-row DATE_EXPIRES re-arm") with pass criteria, update the status line count, or soften
  `:3`.

### DOC4-5: The `reassertPending` contract omits that MediaProvider renames the file on that update
- Severity: Low. Confidence: Medium-High (AOSP mainline source; OEM forks unverified).
  Status: Needs-manual-validation.
- Doc: `MediaStoreWriter.kt:1129-1138` KDoc and `docs/ARCHITECTURE.md:1190-1192` describe the
  update only as re-arming `DATE_EXPIRES`.
- Platform source:
  - `IS_PENDING` and `DATE_EXPIRES` are both in `MediaProvider.sPlacementColumns`, and update
    movement defaults to allowed for non-self callers (`allowMovement = !isCallingPackageSelf()`).
  - `FileUtils.computeDataFromValues` rebuilds the on-disk name as
    `.pending-<newDateExpires>-<displayName>`.
  - So each relaunch that keeps a row also RENAMES the backing file and changes `_data`.
    `DISPLAY_NAME` and `RELATIVE_PATH`, which `PendingDiscardIdentity` freezes, are preserved, so the
    identity check should survive.
- Consequence: the "PENDING DEVICE" caveat is stronger than needed for the expiry itself (AOSP
  confirms it), while the rename side effect is undocumented. Anyone later adding `_data` or
  generation-modified to the identity will silently break discard authority.
- Fix: cite `FileUtils.computeDateExpires` / `computeDataFromValues` in the KDoc, note the rename and
  that identity must not include `_data`/`GENERATION_MODIFIED`, and keep the device check for OEM
  provider forks (see DOC4-4).

### DOC4-6: Stacked KDoc pairs: 21 mid-file declarations still have their docs detached
- Severity: Low. Confidence: High. Status: Confirmed (scripted scan: one `/** */` block immediately
  followed by another, with no declaration between).
- AGG3-56 fixed three sites, but the same defect class persists. Each first block below documents a
  symbol that sits elsewhere, and Dokka attaches only the second block:
  - `CameraController.kt:528` (zoom fast path), `:1253` (`applyMeteringRegions`), `:2650`
    (`sessionAttemptPlan`'s core), `:2692` (`CameraPolicyBlockedException` prose).
  - `CameraEngine.kt:1340`, `:3463` (session invalidate/claim), `:8672`, `:8785`.
  - `CaptureCapabilities.kt:630`, `ManualControls.kt:1126` (flash AE modes),
    `MediaStoreWriter.kt:2400` (`sleepPreservingInterrupt`, separated by an unrelated const KDoc).
  - `CameraState.kt:387` (finder gate), `:416` (DOC4-2), `:836` (`LensChoice`), `:1226` (HEIF
    availability).
  - `FlipRenderer.kt:561` (cover scale), `GlPipeline.kt:608` (Loupe Overview setter),
    `FocusDetail.kt:153`.
  - `CameraScreenPolicy.kt:79` (no declaration at all; see DOC4-7), `Overlays.kt:685` (TopStatus
    OSD), `ControlCycles.kt:164`.
- Consequence: the HAL-quirk rationale CLAUDE.md says to keep ("don't delete a comment that explains
  a workaround") is attached to the wrong symbols, or to none. Several of these are the hot-path files.
- Fix: re-home each block onto its declaration. Add the same stacked-KDoc scan to `check_docs.py`
  so the class cannot recur.

### DOC4-7: KDoc links point at symbols that were renamed or removed
- Severity: Low. Confidence: High. Status: Confirmed.
- `CameraScreenPolicy.kt:81` `[CAMERA_STARTING_STATUS]`: no such symbol (the status is now
  `CameraStatusLifecycle.PROGRESS`, `CameraStatus.kt:76-197`). The whole 79-90 block documents no
  declaration.
- `CameraScreenPolicy.kt:686` `[railChipStateDescription]`: renamed to `railChipState` (`:703`).
- `CameraController.kt:2722` `[me.hletrd.telecampro.camera.cameraPolicyBlockConfirmed]`: no such
  function. The confirmation is `CameraEngine.cameraOpWithheld()` (`:3478`) plus the AppOps
  predicate below it.
- `CameraState.kt:1564` `[macroCloserLensLabel]`: the field is `macroCloserLens` (`:1567`).
- Consequence: dead links in the ownership and accessibility docs. The policy-block one is the
  safety argument for not accusing the user's device.
- Fix: retarget the four links, and delete or attach the orphan PROGRESS block.

### DOC4-8: The CLAUDE.md toolchain row credits lifecycle alone with forcing compileSdk 37
- Severity: Low. Confidence: High. Status: Confirmed (AAR `aar-metadata.properties` from the Gradle
  cache).
- Doc: `CLAUDE.md:69` says "compileSdk 37 required by lifecycle 2.11.0".
- Evidence:
  - The lifecycle 2.11.0 runtime/runtime-ktx/viewmodel AARs declare `minCompileSdk=34`. Only
    `lifecycle-viewmodel-compose` and `lifecycle-runtime-compose` declare 37.
  - `androidx.core:core` / `core-ktx` 1.19.1 also declare `minCompileSdk=37` and
    `minAndroidGradlePluginVersion=9.1.0`.
- Consequence: someone downgrading lifecycle to escape compileSdk 37 would still be blocked by core.
  The row explains the constraint incompletely.
- Fix: "compileSdk 37 required by core/core-ktx 1.19.x and lifecycle-viewmodel-compose 2.11.0".

### DOC4-9: CLAUDE.md's "covered set in use" lists glyphs no user-facing literal uses
- Severity: Low. Confidence: High. Status: Confirmed.
- Doc: `CLAUDE.md:586` lists `§ © ° · ± × γ — … → ∞ ≈ ’ ↑ ↓`.
- Code: a scan of every `values*/strings.xml` and every Kotlin string literal finds `© ° · ± × — ’ …
  ↑ → ↓ ∞`. `§`, `γ`, and `≈` occur only in comments and docs now. All glyphs are inside the shared
  Inter cmap, so there is no rendering defect.
- Fix: drop "in use", or trim the list to the scanned set. Better, let `check_docs.py` derive it.

### DOC4-10: ARCHITECTURE's Lens-tab description omits the phone/converter declaration
- Severity: Low. Confidence: High. Status: Confirmed.
- Doc: `docs/ARCHITECTURE.md:1251-1252` lists the Lens tab as presets, TELE, stabilization, and OIS.
- Code: `ProSheet.kt` `LensTab` (`:1217+`) also renders the phone dropdown
  (`phone_detected_summary`), the converter dropdown (`label_teleconverter`), the magnification
  field, and the converter-host captions. That matches `CLAUDE.md:621` ("asked as two dropdowns in
  the Lens tab").
- Fix: add "phone + teleconverter declaration (and custom magnification)" to item 5.

## Cycle-3 doc-edit verification summary

- Accurate: the CLAUDE.md DNG items 2 and 4 (route-flip gating, direct writes, rollback keep rule,
  and render-time `effectivePhotoFormats`), the TC OIS closure text, the front metering un-flip, the
  4 s ceiling scoping, the YUV qualifier, and the glyph-list addition. Also accurate: ARCHITECTURE's
  DeviceProfile table values and readers (apart from DOC4-3), the `cleanupOrphanedPendingBatch` API
  line, and the HEIF gridded-layout proof text.
- Inaccurate or incomplete: DOC4-3 (nonexistent method in the new table) and DOC4-1/DOC4-2 (the
  scoping did not reach README or the function's own KDoc).
