# RPL cycle 6 — document-specialist review

- Agent: document-specialist (c6), finding prefix `DS6-`
- HEAD: `30970c9e` (cycle-5 range `ea7d4374..30970c9e`)
- Mode: read-only. No Gradle, no `verify_host.py`, no state-changing git.

## Scope inventory

| Area | Files / method |
|---|---|
| Authority docs | `CLAUDE.md` (on-disk copy, 1310 lines), `docs/ARCHITECTURE.md`, `docs/FIELD_CHECKS.md`, `README.md`, `docs/play-data-safety.md`, `docs/play-console-submit.md` (header) |
| Toolchain | `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts` SDK block vs the CLAUDE.md table, README table and ARCHITECTURE quick reference |
| Strings | `res/values/strings.xml` (504 entries) vs `res/values-ko/strings.xml` (486). Scripted check of missing KO, extra KO, KO for `translatable="false"`, format-argument parity (strings and plurals), and identical EN==KO text |
| Glyphs | Bundled Inter faces (fontTools cmap intersection) vs every non-ASCII char in EN/KO strings and in Kotlin string/char literals |
| KDoc | Scripted scan of every `[Symbol]` link in `/** */` blocks under `app/src`, with each unresolved hit checked by hand |
| Doc → code | Every backticked camelCase/UPPER_SNAKE identifier and every backticked file path in the four authority docs checked against the source tree. Every documented numeric constant (zoom/ZSL/log budgets/heartbeats/worker+backlog sizes/exposure caps/gravity thresholds/watchdog) checked against its `const val` |
| Module map | Every `app/src/main/kotlin/**/*.kt` basename checked for an ARCHITECTURE mention, and every `.kt` named in ARCHITECTURE checked for existence |
| Cycle-5 deltas | Every non-doc cycle-5 commit checked against the docs that describe its area (EXIF compose/splice, passthrough privacy strip, zoom display divisor, capability dump, status plate, RAW-loss notice, recovery) |
| Platform claims | AGP 9.4 compatibility (Gradle 9.6 minimum, Build Tools 36.0.0 default, max API 37) and Play target-API (API 36 from 2026-08-31) checked against developer.android.com. `ExifInterface.setAttribute(tag, null)` removes the tag, as documented |

**Clean results** (no finding): the toolchain is consistent across all four places (AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, BOM 2026.09.00, 37/36/33), and the Android docs back the AGP and Play claims. EN/KO parity is complete: 0 missing, 0 extra, 0 format-argument mismatches, and the KO plurals using `other` only is correct for Korean. The 13 identical EN==KO strings are all units, codecs or format joins. `translatable="false"` is used only for the sanctioned abbreviations, the app name, phone names and trademarked converter names. Every non-ASCII glyph in strings and literals is covered by all three Inter faces. The module map is complete in both directions. Every documented constant matches its source value.

## Findings

| ID | Severity | Confidence | Status | Summary | Cite |
|---|---|---|---|---|---|
| DS6-1 | Medium | High | confirmed | FIELD_CHECKS claims to be exhaustive but is missing 4 of the cycle-5 PENDING DEVICE items (AGG5-57 recurrence) | `docs/FIELD_CHECKS.md:3-6,12`; `docs/plans/2026-10-02-rpl-cycle5.md:175-176` |
| DS6-2 | Low | High | confirmed | Hi-res passthrough lane described as "bytes verbatim, EXIF orientation TAG only" and "HAL tags survive exactly as before", but it now carries full shot EXIF and strips identifying HAL tags (AGG5-51) | `docs/ARCHITECTURE.md:99,683`; `CLAUDE.md:844`; `capture/StillCapturePipeline.kt:391-397` |
| DS6-3 | Low | High | confirmed | Status mismatch: the plan says PENDING DEVICE for A1.6/B.4, while FIELD_CHECKS files them as `⊘ HOST-ONLY` (F7/F8) | `docs/plans/2026-10-02-rpl-cycle5.md:175`; `docs/FIELD_CHECKS.md:577-597` |
| DS6-4 | Low | High | confirmed | Dead KDoc links: `ExposureMode` points to `[letter]`/`[label]`, which no longer exist | `camera/ManualControls.kt:300-304` |
| DS6-5 | Info | High | confirmed | The display-zoom law (logical 1:1; standalone divisor follows the caption band) is undocumented in CLAUDE.md/ARCHITECTURE, and the device-verified readouts in CLAUDE.md predate it | `CLAUDE.md:297-316`; `ui/ZoomMath.kt` `zoomDisplayMultiplier` / `standaloneMainDivisorMm` |
| DS6-6 | Info | High | confirmed | Smaller drift: the ARCHITECTURE `HeifExif.kt` row omits the JPEG splice helpers it now owns; the ARCHITECTURE JPEG paragraph says "re-stamped after compress"; CLAUDE.md's covered-glyph list omits U+2019 | `docs/ARCHITECTURE.md:100,1077`; `CLAUDE.md` FocusDetail bullet |

---

### DS6-1 — FIELD_CHECKS exhaustiveness broken again by cycle-5 device items (Medium / High / confirmed)

**Where:** `docs/FIELD_CHECKS.md:3-6` says: "This ledger is exhaustive: every open device claim in `CLAUDE.md` or `docs/ARCHITECTURE.md` has an entry here … A change whose device effect has NO field procedure is listed too (section F)". The cycle-5 plan's final gate line (`docs/plans/2026-10-02-rpl-cycle5.md:175-176`) lists as PENDING DEVICE: "A1.1, A1.2, A1.4 (visible half), A1.6, A1.11 (visible half), A2.1, A2.4, B.4, B.1/B.2 (FIELD_CHECKS E4), plus FIELD_CHECKS A6–A9, D3."

**Mapping against the ledger:**

| Plan item | Covered? |
|---|---|
| A1.1/A1.2 | A9 |
| A1.6 | F7 |
| B.4 | F8 |
| B.1/B.2 | E4 |
| **A1.4 AGG5-5** (Ready cleared before DNG owners cancel; visible half) | **none** |
| **A1.11 AGG5-10** (RAW-loss notice once per session instead of per press, plus the MRG5-1 latch `009bf08f`; visible half) | **none** |
| **A2.1 AGG5-4** (stabilization/frame-rate request kept across non-recording routes, plus the MRG5 follow-ups `dd3edc64` and `3489859e`) | **none** |
| **A2.4 AGG5-49 + MRG5-4** (logical route reads 1:1; standalone divisor in the caption band, `14504891` / `665ab849`) | **none** |

The plan text itself names the A2.4 change as user-visible: "the preset '3.0×' instead of '3.1×' (AGG5-49)". A tablet's main lens should now read 1.0× instead of 1.1×.

**Why it matters:** this is exactly the defect class AGG5-57 (DS5-1) fixed one cycle ago, and it has come back on the next batch. A clean-clone maintainer uses FIELD_CHECKS as the authority (CLAUDE.md § Pointers). Four device-visible changes have no procedure, no pass criterion and no dashboard entry. The "Fourteen remain" count is therefore understated.

**Failure scenario:** someone runs the FIELD_CHECKS dashboard to clear PENDING DEVICE before cutting v1.0.2. The zoom-readout change and the RAW-notice cadence ship without ever being looked at on PMA110 or the tablets, even though both are regressions to the "PMA110 byte-identical" rule if they are wrong.

**Fix:** add entries for each missing item. A2.4: PMA110 logical 3× preset reads 3.0×; standalone 10× reads 10.0×; TB336ZU/TB331FC main reads 1.0×. A2.1: switch Photo↔Video on a route that lacks the stab/fps value, return, and check that the persisted request survives. A1.11: one RAW-loss notice per session shape, not per press. A1.4: list it under F if it has no visible procedure. Update the dashboard and the count. Consider having `tools/check_docs.py` cross-check the plan's "PENDING DEVICE:" list against the FIELD_CHECKS headings so this cannot recur silently.

### DS6-2 — Passthrough-JPEG lane docs predate the AGG4-6 splice and the AGG5-51 privacy strip (Low / High / confirmed)

**Where:**
- `docs/ARCHITECTURE.md:99`: "HAL bytes go to disk verbatim with the capture rotation as an EXIF orientation TAG only".
- `docs/ARCHITECTURE.md:683`: "(bytes verbatim, EXIF orientation TAG only — no 200MP decode)".
- `CLAUDE.md:844`: "the still saves via the EXIF-orientation-only passthrough-JPEG lane".
- KDoc `capture/StillCapturePipeline.kt:391-397`: "The HAL's own EXIF is the composer's seed, so its tags survive under ours exactly as the old in-place `saveAttributes()` merge kept them".

**Code truth:**
- `composePassthroughStillExif` (`StillCapturePipeline.kt:840-858`) runs the full `composeStillExifApp1`. That applies `exifAttributeList(shot)`: ISO, exposure, aperture, focal, 35 mm focal, make, model, lens model, datetime and more, plus orientation and dimensions. So the lane does not carry "orientation TAG only".
- Since `35ccc225`, the HAL seed is no longer preserved "exactly". `PASSTHROUGH_PRIVACY_STRIPPED_TAGS` (`StillCapturePipeline.kt:866ff`) removes the GPS directory, the body/lens serials, the owner name, the unique id and the MakerNote.
- The encoded bytes are not written verbatim: an APP1 is spliced in (`writeSingleJpeg` with `exifPayload`). Only the scan data is verbatim.

**Why it matters:** this lane is dormant on PMA110 but live on any hi-res-capable device (multi-device since 2026-08-01). Two concrete risks:
- The KDoc's "exactly as the old merge" sentence now contradicts the privacy fix right below it. A future editor who "restores" exact preservation would reintroduce the GPS/serial leak that AGG5-51 closed.
- The privacy strip is a user-data property that neither CLAUDE.md's location/GPS bullet nor ARCHITECTURE records.

**Fix:**
- Reword ARCHITECTURE:99/683 and CLAUDE.md:844 to: "pixels verbatim (no decode/crop/pixel-rotate); the shot's composed EXIF APP1, with orientation, is spliced in before the single write; identifying HAL tags are stripped".
- Change the KDoc to "survive under ours EXCEPT `PASSTHROUGH_PRIVACY_STRIPPED_TAGS`".
- Add one sentence to the CLAUDE.md "no GPS tags" bullet saying that HAL-seeded EXIF is GPS/serial-stripped.

### DS6-3 — Plan vs ledger status disagree for A1.6 and B.4 (Low / High / confirmed)

The plan's final line (`docs/plans/2026-10-02-rpl-cycle5.md:175`) lists A1.6 and B.4 as PENDING DEVICE. FIELD_CHECKS F7/F8 (`docs/FIELD_CHECKS.md:577-597`) record the same changes as `⊘ HOST-ONLY … no device procedure`. Only one of these can be true, and a reader of the plan will look for a device procedure that does not exist. **Fix:** in the plan, say "host-only (FIELD_CHECKS F7/F8)" for A1.6 and B.4.

### DS6-4 — Dead KDoc links on `ExposureMode` (Low / High / confirmed)

`camera/ManualControls.kt:300-304` reads: `[letter] is the compact dial badge; [label] the settings-row name.` The enum is `enum class ExposureMode { PROGRAM, SHUTTER, ISO, MANUAL }` and has no members. The labels moved to `ui/controls/ControlLabels.kt:190` (`exposureModeLetter`) and the localized label helpers during the bilingual refactor (`f284dcff`). Dokka/IDE resolution fails, and the comment points readers to properties that do not exist. **Fix:** link `[me.hletrd.telecampro.ui.controls.exposureModeLetter]` and the localized label function, or drop the sentence. (The scripted scan found no other unresolved KDoc links in `app/src`.)

### DS6-5 — Display-zoom law undocumented in the authority docs (Info / High / confirmed)

Cycle 5 changed `zoomDisplayMultiplier`:
- 1:1 on the logical route (AGG5-49).
- On standalone routes, divide by the nominal 23 mm while the measured main is within `LENS_CAPTION_ROUNDING_BAND`, and by the measured main otherwise (MRG5-4).
- One `CameraUiState.mainRelativeZoomMultiplier` feeds the pill, Fn chip/value, ruler and Shoot-tab slider.

CLAUDE.md's zoom-scale bullet (`CLAUDE.md:297-316`) documents only the wire conversion (`unifiedZoomOf`/`localZoomOf`). Its device-verified tablet readings were measured before the display divisor changed. ARCHITECTURE has no mention of `zoomDisplayMultiplier`. This rule cost two cycles (AGG4-73, AGG5-49, MRG5-4), which is the kind of hard-won fact CLAUDE.md exists to hold. **Fix:** add a short paragraph that names the display law and its single owner. Mark the tablet readings as pre-AGG5-49 until DS6-1's field entry closes.

### DS6-6 — Minor drift (Info / High / confirmed)

- `docs/ARCHITECTURE.md:100`: the `HeifExif.kt` row says "APP1 payload extraction … for `HeifWriter.addExifData`". The file also owns the JPEG splice lane (`exifSplicePlan`, `spliceExifApp1`, `writeJpegWithExifApp1`, `exifApp1WithoutThumbnailIfd`), which the JPEG lanes depend on.
- `docs/ARCHITECTURE.md:1077`: "exposure EXIF is re-stamped after `Bitmap.compress`". The design is a once-composed APP1 spliced into the encoded buffer before the single write (CLAUDE.md:1072-1079 is correct). "Re-stamped" reads like the retired in-place `saveAttributes()` rewrite.
- CLAUDE.md's FocusDetail bullet lists the covered non-ASCII set "in use" as `§ © ° · ± × γ — … → ∞ ≈`. The EN strings also use U+2019 (’), which Inter covers, so nothing breaks, but the list is not the set in use.
