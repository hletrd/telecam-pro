# FD4 — feature-dev code-reviewer lane (RPL cycle 4 rerun, HEAD 14767b0a, 2026-10-02)

Scope: confidence-filtered (>= 80 %) bug/logic/security review of `app/src/main/kotlin/**`, weighted
toward gl/*, stab/*, ui/review/*, ui/controls/*, standby audio, capture/HeifCapture + DngCapture,
SettingsStore load/migration and MainActivity hardware-key handling. Deduplicated against
`.context/reviews/_aggregate.md` (AGG4-*) and `archive-rpl-cycle3-2026-10-02/_aggregate.md` (AGG3-*).
Read-only; time-boxed (~25 min). Three new findings.

## Findings

### FD4-1 — Quick Zoom ruler reads and drags on the lens-local scale while every other zoom surface shows the main-relative scale (rear standalone routes)

- **Where:** `ui/controls/ManualDials.kt:1241-1278` (`ZoomRuler`), compared with
  `ui/controls/ManualDials.kt:569-578` (the ZOOM Fn chip that opens it),
  `ui/controls/FnQuickActions.kt:71-78`, `ui/CameraScreen.kt:1202-1215` (HUD `ZoomIndicator`), and
  `ui/ZoomMath.kt:36-48` (`zoomDisplayMultiplier`).
- **Why:** `ZoomRuler` uses `base = 1f` whenever the teleconverter is off, and then shows
  `controls.zoomRatio * 1`. The Fn chip, the Fn quick value and the HUD indicator all go through
  `zoomDisplayMultiplier(...)`, which on a BACK route multiplies by
  `caps.equivalentFocalMm / MAIN.targetEquivMm`. On the logical seamless photo route that factor is
  about 1 (the caps belong to camera 0, the main-equivalent), so the two agree. On every rear
  STANDALONE route (all of Video, and Photo with DNG wanted, see CLAUDE.md "The zoom SCALE follows
  the ROUTE"), `controls.zoomRatio` is lens-local and the caps belong to the standalone lens. The
  factor is then about 3.0 on the 70 mm lens, about 10 on the 230 mm lens, and about 0.6 on the
  ultrawide. The ruler ignores it. This is the one zoom surface that skipped the 2026-08-04
  route-scale unification.
- **Failure scenario:** In Video, pick the 3× lens and tap the ZOOM Fn chip. The chip reads
  "3.0×", the HUD pill reads "3.0×", and the ruler that opens directly underneath says "1.0×" with
  a 1.0–10.0× span. On the ultrawide in Video, the chip says "0.6×" and the ruler says "1.0×". The
  same thing happens in Photo with DNG on any non-main lens. Dragging still writes a consistent
  lens-local value, so the wire is correct. The operator, however, reads two contradictory
  magnifications for one framing, and TalkBack announces the wrong one through
  `valueDescription = readout`. This is the same "value right, scale wrong" class the CLAUDE.md
  bullet records as three user-visible symptoms.
- **Fix:** Derive `base` from the same `zoomDisplayMultiplier(teleconverter, magnification,
  caps?.equivalentFocalMm, frontFacing, activeRoute)` the chip uses. Pass `state` (or the
  multiplier) into `ZoomRuler`. Keep `TELE_MAX_DISPLAY_ZOOM` capping only on the teleconverter
  branch, and keep writing `display / base` back as the lens-local ratio. Add a host test that
  checks the ruler readout against `formatDisplayZoom` for a standalone 70 mm caps fixture.
- **Confidence:** 85 %. **Severity:** Low-Medium (display/accessibility mismatch on the primary
  Video path; capture unaffected).

### FD4-2 — Momentary AEL / PUNCH_IN hardware bindings overwrite (and, for punch-in, persist) the operator's latched state on release

- **Where:** `ui/CameraViewModel.kt:3342-3355` (`performHardwareAction`: `AEL -> onToggleAeLock(active)`,
  `PUNCH_IN -> onTogglePunchIn(active)`), `ui/CameraViewModel.kt:3258-3267` (`onTogglePunchIn`
  clears `autoPunchInActive`, `markChanged`, `scheduleSettingsSave`), and
  `MainActivity.kt:752-767, 838-841, 879-882` (the release edges).
- **Why:** The bindings are documented as MOMENTARY (`CameraViewModel.kt:3332-3335`), but they are
  implemented as "set to the key state", not "hold, then restore". The release edge always writes
  `false`, whatever the state was before the press. `onTogglePunchIn` is the operator's persistent
  sheet toggle: it calls `markChanged(FnSlot.PUNCH_IN)` and schedules the 500 ms settings save. A
  hold longer than the debounce therefore persists `true` mid-hold, and the release then persists
  `false`.
- **Failure scenario:**
  - **Punch-in:** Turn on punch-in in the sheet (persisted `punchIn=true`), assign half-press to
    PUNCH_IN, then light-press and release the camera-control button. The loupe turns off, and
    `punchIn=false` is saved, so it stays off after relaunch. Pressing the key during the MF-ruler
    auto-punch-in also clears `autoPunchInActive`, so the assist loses ownership.
  - **AE lock:** Lock AE from Fn/AEL with the full key bound to AEL. One press-and-release of the
    key unlocks the operator's latched AE lock.
- **Fix:** On the press edge, snapshot the prior value and apply the momentary state through a
  non-persisting path (the `onAutoPunchIn`-style seam for punch-in, and an equivalent for AE lock).
  On release, restore the snapshot instead of writing `false`. Do not `markChanged` or schedule a
  save for a momentary hold.
- **Confidence:** 80 %. **Severity:** Low.

### FD4-3 — ISO ruler stops collapse above ISO 25600 (and below 50): intermediate stops become unreachable on wider-range sensors

- **Where:** `ui/controls/ManualDials.kt:1101-1125` (`STANDARD_ISO_LADDER`, `isoStops`).
- **Why:** Each `100·2^(k·step)` candidate is snapped to the nearest entry of a fixed ladder that
  ends at 25600 (and starts at 50). Every candidate above about 25600 therefore maps to 25600 and is
  deduplicated by the sorted set, and every candidate below 50 maps to 50. Only the exact hardware
  bounds survive outside the ladder. The KDoc promises stops "across [lower, upper]" so that "the
  full advertised range stays reachable". That holds only at the endpoints.
- **Failure scenario:** On a multi-device install (minSdk 33) whose route advertises
  `SENSOR_INFO_SENSITIVITY_RANGE` up to 51200 or 102400, the ISO ruler's last two ticks are 25600
  and the raw upper bound. 32000, 40000 and 51200 (1/3 step), or 51200 alone (full step) cannot be
  selected, and one tick jumps one to two stops. This is in the mechanics only; whether PMA110's
  advertised upper bound crosses 25600 was not checked on the device.
- **Fix:** Extend the ladder by the conventional thirds (32000, 40000, 51200, 64000, 80000, 102400,
  … and 25/32/40 at the low end). Alternatively, fall back to `raw.roundToInt()` (or a
  2-significant-figure round) when `raw` is outside the ladder span by more than half a step. Add a
  host test with `isoStops(50, 102400, 1/3f)`.
- **Confidence:** 80 % (mechanics). **Severity:** Low.

## Checked and not reported (no defect at >= 80 %)

- EXIF `TAG_EXPOSURE_TIME` written as a decimal string: exifinterface 1.4.2's
  `Rational.createFromDouble` is a continued-fraction conversion (decompiled), so no 1/10000
  quantization.
- `reviewExifTransform` for all eight orientations, including TRANSPOSE/TRANSVERSE: correct.
- `extractExifApp1` marker walk and `packYuv420ToNv21` bulk paths: correct.
- `SettingsStore` per-field defensive readers, `LOG` → `SLOG3_CINE` migration, and
  `recordAudioOffByDenial` tri-state: correct. `jpegQuality` is restored unbounded but clamped at
  both consumers (`ManualControls.kt:689`, `CameraEngine.kt:5553`).
- GL analysis readback (`GlPipeline.kt:1410-1573`): the owner gate, the generation/epoch publish
  check, and the LUT applied only to the scopes are consistent with the documented contract.
- Hardware key ownership (`HardwareInputPolicy.kt`, `MainActivity.kt:684-905`): DOWN/UP pairing
  and the `onStop` release are sound. Focus-loss cancel-UP is delivered by the input dispatcher.
- `GyroEis` gating, and the motion-inversion arm path (dark behind `MOTION_SIGNS_VERIFIED = false`).
- Standby meter read loop (`StandbyAudioController.kt:720-790`): matches the documented policy.
