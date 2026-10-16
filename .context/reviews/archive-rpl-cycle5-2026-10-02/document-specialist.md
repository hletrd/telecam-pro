# Document-specialist review: RPL cycle 5 (HEAD ea7d4374)

Angle: doc/code mismatches across CLAUDE.md, docs/ARCHITECTURE.md, docs/FIELD_CHECKS.md, README.md,
docs/play-*.md (tracked and local-private), docs/TESTING.md, KDoc, string resources, the build config,
and version pins. Cycle-4 doc edits (`git diff 887d39fb..HEAD -- CLAUDE.md README.md docs/`) were
re-checked line by line against code at HEAD. Tracked items from
`docs/plans/2026-10-02-rpl-cycle4.md` are cited, not re-reported as new.

## Baseline checks that came back clean

- **Version pins are the latest stable** (registry queries 2026-10-02: Google Maven
  `maven-metadata.xml`, Maven Central, services.gradle.org `current`). AGP 9.4.1, Kotlin / Compose
  compiler 2.4.20, Gradle 9.8.0 (wrapper + sha256), Compose BOM 2026.09.00, core-ktx 1.19.1,
  activity-compose 1.13.0, lifecycle 2.11.0, coroutines 1.11.0, heifwriter 1.1.0, exifinterface
  1.4.2, profileinstaller 1.4.1, androidx.test core/runner 1.7.0, ext-junit 1.3.0, Robolectric 4.17,
  and junit 4.13.2. Nothing is outdated, so there are no Low "bump" findings this cycle.
- **The on-disk CLAUDE.md / README / ARCHITECTURE toolchain tables match** `libs.versions.toml`,
  the wrapper, and `app/build.gradle.kts` (37 / 36 / 33, JDK 21).
- **The new CLAUDE.md compileSdk rationale is verified from AAR metadata.** `core-1.19.1.aar` and
  `lifecycle-runtime-compose-android-2.11.0.aar` both declare `minCompileSdk=37`, while
  `activity-1.13.0` declares 36 and `lifecycle-runtime-android-2.11.0` declares 34. That matches
  `CLAUDE.md:69` exactly.
- **Robolectric 4.17 supports SDK 37.** Its release notes say "supports SDK 37", which matches the
  `libs.versions.toml` comment.
- **Strings:** 505 EN entries, 487 translatable, 487 KO. None are missing or extra, and the
  placeholders match per key. No new hardcoded user-facing Kotlin literal was added in cycle 4.
  `ExposureMeterSpeech` and the reconfiguring caption use resources that have KO entries.
- **`python3 tools/check_docs.py`: 194 checks, 0 failed**, and the stacked-KDoc scan is enforcing.
- **Every cycle-4 doc symbol exists and behaves as written:**
  - `StatusPlate`, `StatusPlateRank`, `deferredProgress`, and `CAMERA_CONDITION_ENDING_MESSAGES`.
    The rank order PROGRESS < SUCCESS < INFO < WARNING < RETAINED_TAKE < ERROR matches
    `CameraStatus.kt:266`, and the reducer at `:296-318` matches CLAUDE.md's description.
  - `composeStillExifApp1` (`StillCapturePipeline.kt:732`) and `keptRowReassertsPending`
    (`MediaStoreWriter.kt:2922`).
  - `CameraViewModel.standaloneRouteFor`, and `gl.setFrontMirrorConvention` (`GlPipeline.kt:560`;
    DOC4-3 is fixed).
  - `MomentaryHold.kt`, `ExposureMeterSpeech.kt`, `PhotoFormatChips.kt` (`withEdit` is imported from
    `camera/`), `RulerAccessibility.kt` (`totalUnits - 1`), and `AudioDenialReason.kt`.
  - FIELD_CHECKS D2, E4, A6, and F1 exist, and the dashboard counts ten open items correctly.
- **README DOC4-1 fix is in place** (`README.md:54-57`, `:100-103`).
- **Play policy.** targetSdk 36 meets the Google Play requirement that new apps and updates target
  API 36 from 2026-08-31 (Play Console Help, "Target API level requirements").
- **Deprecated-API audit at compileSdk 37 is enforced by the build.** `allWarningsAsErrors.set(true)`
  is at `app/build.gradle.kts:765`, so a Kotlin deprecation warning fails compilation.

## Findings

### DS5-1: FIELD_CHECKS claims to be exhaustive, but eight cycle-4 PENDING DEVICE changes have no entry
- Severity: Medium. Confidence: High. Status: Confirmed.
- What the docs say:
  - `docs/FIELD_CHECKS.md:3-7` says the ledger "is exhaustive". It also says a change whose device
    effect has no field procedure is listed in section F, "so its absence from the open list is a
    recorded fact rather than an omission."
  - `docs/ARCHITECTURE.md` calls it the exhaustive committed ledger.
- The cycle-4 plan's progress log (`docs/plans/2026-10-02-rpl-cycle4.md`, merged `verify_host` entry)
  lists these as PENDING DEVICE: A.2, A.3, A.10, A.12, A.17, A.18, A.20, A.22, B.1, B.2/B.3, and B.4.
- Only A.20 (→ A6), A.22 (→ F1), and B.2/B.3 (→ E4) have entries. There is no open entry and no
  section-F row for the rest:
  - A.2: bare-reopen preflight retry.
  - A.3: a recall whose video size differs takes `reconfigureCamera`.
  - A.10: lens re-band on leaving FRONT.
  - A.12: an EV-only delta under app-side AE is a wire NO_OP.
  - A.17: YUV still size is aspect-first (`CaptureCapabilities.kt:341-351`; every FRONT and
    LOGICAL still).
  - A.18: the inventory fold keeps zoom.
  - B.1: live muxer-stop tail with the moov walk.
  - B.4: JPEG EXIF splice.
- B.4 is the sharpest case. `CLAUDE.md:1066-1076` states as fact that ISO / exposure / 35 mm focal /
  make / model "stay in parity across both processed formats", and the plan says the device EXIF
  readback is pending. A6 reads EXIF from one TELE still, but it does not say which format, and it
  does not cover the processed-JPEG or passthrough splice lane.
- Failure scenario:
  - A clean clone (no private BACKLOG) has no committed record that these need closing before the
    v1.0.2 re-cut.
  - A.17 changes the saved frame size on every LOGICAL/FRONT still for any device whose largest YUV
    size is not 4:3. A regression there ships unverified while the ledger says nothing is pending.
- Fix:
  - Add open entries for at least B.4 (pull one JPEG + HEIF pair from the same shot and diff the
    EXIF tags; also check one passthrough JPEG where hi-res exists) and B.1 (a clip with a dropped
    mic in the add-track window plays and is published).
  - For A.17, record that PMA110's chosen YUV size is unchanged (`dumpsys` YUV list against
    `pickStillSize`).
  - Add F-section HOST-ONLY rows, with a reason, for items that have no practical device procedure
    (A.2, A.3, A.10, A.12, A.18).
  - Alternatively, narrow the "exhaustive" wording.
- PMA110 behaviour change: none (docs only).

### DS5-2: `FinalizedRecordingValidation` KDoc still states the pre-AGG4-3 rule
- Severity: Low. Confidence: High. Status: Confirmed.
- What the doc says: `video/VideoRecorder.kt:2381-2385` says "[INDETERMINATE] is the reopen that
  proved nothing (the provider open failed) … An extractor throw on an opened file after the tolerated
  muxer.stop() throw is FAILED (AGG3-6)."
- What the code does:
  - `completeFrozenRecordingStorage` (`:1980-1988`) maps `PendingProbe.INDETERMINATE` to INDETERMINATE.
  - `classifyFinalizedVideoTrack` (`storage/MediaStoreWriter.kt:2736-2790`) returns INDETERMINATE for
    an extractor throw unless a fresh-descriptor re-parse also throws AND the top-level walk proves
    `moov` absent. Its own KDoc says this corrects AGG3-6.
  - So INDETERMINATE now also covers "opened, but the extractor threw without proof", and FAILED
    needs the walk's proof.
- Why it matters: the enum is where a reader learns the stop-tail contract. The stale sentence
  describes the destructive behaviour B.1 removed. Someone "restoring" it would bring back the delete
  of a playable take.
- Fix: reword the KDoc to say INDETERMINATE covers both an open failure and an extractor throw
  without a proven-absent `moov`, and FAILED means the walk proved `moov` absent (AGG4-3, correcting
  AGG3-6).
- PMA110 behaviour change: none.

### DS5-3: Dead KDoc link to `StillCapturePipeline.applyExifAttributes`, which no longer exists
- Severity: Low. Confidence: High. Status: Confirmed.
- What the doc says: `capture/StillCapturePipeline.kt:632` says "[StillCapturePipeline.applyExifAttributes]
  replays it verbatim."
- What the code does: no `applyExifAttributes` exists anywhere. Since B.4 the replay is
  `composeStillExifApp1` (`:752`, `exifAttributeList(shot).forEach { exif.setAttribute(...) }`).
- Why check_docs missed it: its dead-link sweep (M.9) evidently does not resolve qualified
  `Class.member` links. A scripted scan of cycle-4-changed files found this as the only true dead
  qualified link. The rest were platform classes or parameter names.
- Fix:
  - Retarget the link to `[composeStillExifApp1]`.
  - Optionally have check_docs resolve the member part of qualified KDoc links against declared
    names in `app/src/main`.

### DS5-4: README calls the DeviceProfile seam "the one exception" to model-name resolution, but CLAUDE.md sanctions two
- Severity: Low. Confidence: High. Status: Confirmed.
- What the doc says: `README.md:100-103` says "The one exception is deliberate: HAL workarounds …
  are keyed to its model in a single place (`camera/DeviceProfile.kt`)."
- What the code does:
  - `camera/Teleconverter.kt` `detectPhone` also keys off `Build.MODEL`
    (`ui/CameraViewModel.kt:402`), and it preselects the phone dropdown and the converter kit.
  - CLAUDE.md (teleconverter rule 3 and the multi-device bullet) names both seams as sanctioned.
- Why it matters: this is the public front page. The sentence is accurate only for "HAL
  workarounds", but "the one exception" reads as the only model-string use.
- Fix: add a sentence such as "…; separately, the phone model only preselects the Lens tab's phone
  dropdown (a declaration the user can change), and never selects a camera route."

### DS5-5: Local Play listing still carries the universal RAW-routing claim and pre-cycle release notes
- Severity: Low. Confidence: High. Status: Confirmed. The file is gitignored (`.gitignore:66`
  `/docs/*.md`), so this affects only the maintainer's copy.
- What the doc says:
  - `docs/play-store-listing.md:116` (EN) says DNG works "on any rear lens advertising RAW".
  - `:248` (KO) says "RAW는 지원한다고 알리는 후면 렌즈라면 어디서나".
- What the code does: on `DeviceProfile.GENERIC`, `rawRequiresStandalone = false`, so
  `standaloneRouteWanted` (`CameraState.kt:550-556`) keeps DNG on the logical camera. A physical
  lens's RAW is unreachable there. This is the drift DOC4-1 fixed in README but not in the store
  copy.
- Second issue in the same file: the v1.0.2 release notes (`:31-56`) were last edited 2026-08-24
  (file mtime). Since then, user-visible behaviour has changed:
  - Processed JPEGs now carry EXIF written in a single pass.
  - Status messages are priority-arbitrated.
  - The ISO ruler extends to 102400 and down to 25.
  - The P-mode shutter follows zoom (A.20).
  - Momentary AEL/punch-in holds now restore the prior state (A.24).
- Fix:
  - Mirror README's scoped wording in both languages.
  - Re-review the v1.0.2 notes against the commits since 2026-08-24 before the re-cut.

### DS5-6: ARCHITECTURE says MemoryBankAudioProvenance owns bank provenance; production code does not use that path (already tracked)
- Severity: Info. Confidence: High. Status: Confirmed. Already tracked as the cycle-4 "later cycle"
  deslop note.
- What the docs say: the ARCHITECTURE `AudioDenialReason.kt` row lists "bank provenance" under
  `MemoryBankAudioProvenance`.
- What the code does:
  - `bankAudioOffByDenialNow` (`AudioDenialReason.kt:53`) has no production caller.
  - `CameraViewModel.onStoreMemorySlot` (`:3634-3640`) calls `bankAudioOffByDenial(...)` directly
    with its own `AudioDenialReasonStore(app)` instance (`:769`).
  - MainActivity has a second instance (`MainActivity.kt:1051`). Both target the same prefs file, so
    behaviour is correct.
- Why it matters: the doc row and the KDoc ("ONE owner … shared by the Activity … and the
  ViewModel") describe a structure the code does not have.
- Fix: whichever way the tracked deslop item resolves, update the ARCHITECTURE row in the same
  change.

### DS5-7: targetSdk 36 while API 37 is stable has no recorded reason
- Severity: Info. Confidence: High. Status: Confirmed.
- What the docs and build say:
  - `app/build.gradle.kts:609-610` says "Runtime target stays Android 16 (API 36)" but gives no
    reason.
  - CLAUDE.md's toolchain row gives none either, while the global rule is "latest stable
    everything".
- Policy context: Play policy is met (API 36 is required from 2026-08-31). Moving to API 37 is a
  behaviour-change migration and would need device validation, which is the legitimate reason.
- Fix: one clause in the CLAUDE.md row, for example "targetSdk 37 waits for an on-device Android 17
  behaviour-change pass; Play requires 36 since 2026-08-31".

### DS5-8: CLAUDE.md's gate comment omits two new default-gate behaviours
- Severity: Info. Confidence: High. Status: Confirmed.
- What CLAUDE.md says: the build-loop comment (`CLAUDE.md:~96`) lists the host gate as
  "Android + coverage + Python tools/harness/docs".
- What the gate does:
  - Since C.12/C.13, `tools/verify_host.py:100-110, 160-170` also runs `:app:lintRelease` on a clean
    tree and prints a NOTE on a dirty one.
  - Kotlin warnings are fatal.
- ARCHITECTURE `:1349-1352` documents both; CLAUDE.md, the self-contained fallback authority, does
  not.
- Fix: add "(+ `:app:lintRelease` on a clean tree; Kotlin warnings fatal)" to the comment.

## Final sweep (commonly missed)

- **Glyph coverage.** No new symbol was added in cycle-4 literals beyond CLAUDE.md's
  scanned set (`© ° · ± × — … → ∞ ’ ↑ ↓`).
- **Commit/plan IDs cited in CLAUDE.md exist in history:** 4e57fff2, 0ab5c1ba, 2b4bc55, and c27744c.
- **`docs/play-console-submit.md` cycle-4 edits** describe the floor the helper enforces
  (`tools/upload_key_policy.py`). I did not restate its shape here.
- **`device-tests/` is tracked**, so `verify_host.py`'s `unittest discover -s device-tests/tests`
  works in a clean clone.
- **The `.context/reviews/*.md` deletions in the working tree** are the cycle-4 archive move.
  This review recreates only this file.

## Files covered

CLAUDE.md, README.md, docs/ARCHITECTURE.md, docs/FIELD_CHECKS.md, docs/play-console-submit.md,
docs/play-store-listing.md (local), docs/plans/2026-10-02-rpl-cycle4.md,
.context/reviews/archive-rpl-cycle4-2026-10-02/document-specialist.md, gradle/libs.versions.toml,
gradle/wrapper/gradle-wrapper.properties, app/build.gradle.kts (sdk/version/warnings blocks),
app/src/main/res/values{,-ko}/strings.xml, tools/verify_host.py, tools/upload_key_policy.py,
tools/check_docs.py (run), camera/CameraStatus.kt, camera/AutoExposure.kt, camera/CameraState.kt
(effectiveEquivFocalMm, standaloneRouteWanted), camera/CaptureCapabilities.kt (YUV size),
capture/StillCapturePipeline.kt, capture/HeifExif.kt, storage/MediaStoreWriter.kt
(classifyFinalizedVideoTrack, finalizedVideoTrackProbe, reassert KDocs), video/VideoRecorder.kt
(stop tail, validation enum), video/AudioReadPolicy.kt, AudioDenialReason.kt, MainActivity.kt
(provenance wiring), ui/CameraViewModel.kt (status plate, memory-bank store),
ui/ExposureMeterSpeech.kt, ui/controls/PhotoFormatChips.kt, ui/controls/RulerAccessibility.kt, plus
a scripted KDoc-link scan of every Kotlin file changed in 887d39fb..HEAD.
