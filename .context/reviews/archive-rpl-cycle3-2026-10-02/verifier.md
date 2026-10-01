# Verifier — RPL cycle 3 (2026-10-02, HEAD e3a2bdd4)

Lane: evidence-based check that the code implements the invariants stated in CLAUDE.md "Hard-won
device facts" and `docs/ARCHITECTURE.md`. Read-only; no Gradle run (another lane owns the gate).
Cycle-2 aggregate and plan were read first; items fixed there (AGG2-1..AGG2-27 etc.) are not
re-reported. AGG-11 (HLG10 preview/meter reads HLG code values as SDR) was re-confirmed and is
still open, but it is not re-reported here because I have no new evidence.

## Findings

### VER3-1: The TELE 10-bit Video ladder reaches HLG10 + full-res JPEG + RAW, the documented HAL-crash combination
- **Severity:** Medium. **Confidence:** High (code path). **Status:** Confirmed by code reading.
  Whether the HAL crashes needs a device run, but CLAUDE.md records it as a crash.
- **Where:**
  - `camera/CameraController.kt:2749-2796` (`sessionAttemptPlan`).
  - The caller is at `camera/CameraController.kt:656-677`. There, `wantHlg` and `tenBitVideoOnly`
    are both `tenBitHlg && caps.supportsHlg10()`, and `teleconverterMode` is
    `teleconverterMode && deviceProfile.vendorTcSessionType`, which is true on PMA110.
- **Stated invariant:**
  - CLAUDE.md: "HLG10 preview + full-res JPEG + RAW together crash it", and the crash is "a crash,
    not a rejection, so the fallback ladder cannot rescue it".
  - The plan's own comment (lines 2740-2748) says the same.
  - `SessionFallbackLadderTest.kt:409-411` asserts "RAW never rides any rung of a 10-bit request".
- **What the code does:** the 10-bit rung replaces attempt 0 only. Later attempts go through the
  TELE table without a shift:
  - attempt 1 → `(1, vendor)`
  - attempt 2 → `(2, vendor)`
  - attempt 3 → `(0, non-vendor)`
  - On attempt 3, `streamAttempt = 0` gives:
    - `useHlg = wantHlg && 0 < 2` = true
    - `useJpeg` = true
    - `useRaw = 0 < 1 && supportsRaw && standalone && !logicalMultiCamera` = true, because TELE is
      standalone camera 4, which advertises RAW16 (TELE DNG is device-measured).
  - The reader plan never consults `rawWanted` on TELE.
  - The ladder test that pins "RAW never rides" uses `teleconverterMode = false` only, so the TELE
    table is untested for `tenBitVideoOnly`.
- **Failure scenario:** the user records 10-bit HLG or log video with TELE on. A firmware or
  ColorOS change makes the vendor 0x80b4 session type fail to configure; the TELE ladder exists
  for exactly this case (see the `createCaptureSession` comment at `CameraController.kt:833-837`).
  1. Attempts 0-2 are all vendor-mode and fail.
  2. Attempt 3 configures HLG10 + JPEG + RAW on the standalone tele.
  3. Per the device fact, that crashes the HAL instead of being rejected, so the camera dies
     rather than degrading to an 8-bit or preview-only session.
  - A lighter version of the same exposure: one transient refusal of each vendor rung lands on
    the crash rung.
- **Fix (host-testable):**
  - Add `!tenBitVideoOnly` to the RAW term, i.e. `useRaw = ... && !tenBitVideoOnly`. Ten-bit
    Video never needs a RAW reader, and the non-TELE ladder already gets the same result through
    `streamAttempt >= 1`.
  - Extend `SessionFallbackLadderTest` to iterate attempts 1..`maxSessionAttempt(true, false)`
    with `teleconverterMode = true, tenBitVideoOnly = true`. Assert `!useRaw` and
    `!(useHlg && useJpeg && useRaw)` on every rung.

### VER3-2: An accepted session rewrites the operator's processed-format request; a DNG-only selection becomes HEIF+DNG after a FRONT trip or a drop-RAW rung
- **Severity:** Low. **Confidence:** High. **Status:** Confirmed by code reading.
- **Where:**
  - `camera/OpticsConstraints.kt:77-81`:
    `photoFormats.normalizedFor(photoOutputs).copy(dngRaw = photoFormats.dngRaw)`.
  - `camera/CameraState.kt:1445-1462` (`normalizedFor`: no supported output → `PhotoFormats(heif = true)`).
  - It is published into state at `ui/CameraViewModel.kt:880-910` (Ready handler) and persisted by
    the next save or by background.
- **Stated invariant:** CLAUDE.md DNG rule 4: "A still-less session must not edit the request …
  Capture-time normalization still guarantees no shot is attempted against a missing output."
  The KDoc on `acceptedOpticsAuxState` keeps the RAW axis because "RAW's presence is a CONSEQUENCE
  of this very request". The same reasoning applies to the processed axis whenever the session
  lacks RAW for route reasons.
- **What the code does:** take an operator with DNG only selected (HEIF off, JPEG off, DNG on).
  - Their session ends up with a processed reader but no RAW reader. Two ways:
    - FRONT: `useRaw` excludes `frontRoute`.
    - A rear standalone session that fell to the "drop RAW" rung.
  - `normalizedFor` finds no supported output, falls back to `heif = true`, and `.copy(dngRaw =
    true)` puts DNG back. The REQUEST becomes HEIF+DNG and is written to state and settings.
  - After returning to the rear route, every shot writes a HEIF the operator never selected, and
    the DNG-only choice is gone for good.
  - `CameraEngine.capturePhoto` (`CameraEngine.kt:4963`) already normalizes per shot against
    `accepted.outputs`. A front DNG-only shot would therefore save a HEIF either way, without
    editing the request.
  - The hi-res branch has the same shape: HEIF+DNG becomes JPEG+DNG and stays that way after
    hi-res is turned off. That path is dormant on PMA110.
- **Fix:** do not write the normalized processed axis back into the request. Keep
  `photoFormats` as requested, and derive the "effective" set for captions and Fn from
  `normalizedFor(photoSessionOutputs)` at render time, the same way capture already does.
  Alternatively, normalize only when the session's outputs are a superset-capable answer for the
  request (processed reader present AND requested processed set non-empty). Host test: run
  `acceptedOpticsAuxState(DNG-only, outputs(processed = true, raw = false))` and expect the
  request to come back unchanged.

### VER3-3: CLAUDE.md's front-camera bullet still says tap-AF needs no un-flip and that the mirror inversion "becomes" a DeviceProfile flag; the code does both differently
- **Severity:** Low (doc). **Confidence:** High. **Status:** Confirmed.
- **Where:** CLAUDE.md line ~701 says "tap-AF needs NO un-flip (displayed x == texture x;
  `mapTapFocusGeometry(mirrorX=false)`)" and "On a multi-device build this inversion becomes a
  DeviceProfile quirk flag".
- **What the code does:**
  - `CameraEngine.kt:3179-3190` passes two separate flags:
    - `mirrorX = FrontMirrorConvention.tapDisplayMirrorX(...)`, which is false on PMA110.
    - `meteringMirrorX = FrontMirrorConvention.meteringMirrorX(...)`, which is
      `frontRoute` = TRUE on every front route.
  - `mapTapFocusGeometry` (`CameraEngine.kt:8858-8860`) DOES un-flip the metering point. This is
    commit 7cda8dab (2026-07-28), "stop front tap-AF metering the mirrored point".
  - The inversion already IS a profile flag: `DeviceProfile.frontStreamPreMirrored`
    (`DeviceProfile.kt:65,74`), consumed by `FrontMirrorConvention`.
  - `docs/FIELD_CHECKS.md:56` already describes the metering flip correctly. Only CLAUDE.md is
    stale.
- **Why it matters:** CLAUDE.md is the stated authority ("this file overrides"). A future agent
  "restoring" `mirrorX=false` semantics for both halves would re-introduce the cycle-6 F2 bug,
  where front tap-AF metered the horizontally opposite point.
- **Fix:** reword it to say that the loupe/texture mapping needs no un-flip on PMA110, that the
  AE/AF metering point IS un-flipped on every front route (array space holds the true scene), and
  that both derive from `FrontMirrorConvention` + `DeviceProfile.frontStreamPreMirrored`.

## Invariants verified as implemented (no divergence found)

| Invariant (CLAUDE.md) | Evidence |
|---|---|
| Capture rotation: rear `sensor + afocal180 − dev`, front `sensor + dev`, EXTERNAL sensor only; muxer hint −dev / +dev | `RotationMath.kt:149-195`; both engine call sites (`CameraEngine.kt:5499-5507, 7835-7844`) pass route + frontFacing |
| Preview rotation = afocal 180 only in TELE, 0 otherwise; window term only via per-draw override | `RotationMath.kt:35`, `GlPipeline.kt:1063-1068` |
| Still ceiling 4 s clamped at the caps seam; per-profile null = trust advertised | `CaptureCapabilities.kt:20-33, 404-419`; `DeviceProfile.kt:67,76`; `CameraEngine.kt:1260` |
| Repeating request: fluidity cap 1/15 s inside the 500 ms safety cap, unconditional clamp, residual → GL gain ≤ ×16 | `ManualControls.kt:325-445`; both repeating builders pass `previewExposureCap = true` (`CameraController.kt:1030, 1735`); still request does not |
| Still watchdog: HAL-auto 8 s; app-owned = ceil(clamped exposure ms) + 8 s, saturating | `ManualControls.kt:580-607`, `CameraController.kt:2034-2042` |
| ZSL: 1/6 stop, zoom 2 %, age ≤ 400 ms, AE-OFF, processed-only, no AE-flash, no gesture; SINGLE only; logical/front only; not while pinAutoFps | `ZslAdmission.kt`, `CameraController.kt:1511-1515`, `CameraEngine.kt:5008` |
| Shader transfer codes 0/1/2/4/5, code 3 vacant; S-Log3/LogC3 constants and piecewise cuts match the reference | `FlipRenderer.kt:633-643`, `Shaders.kt:227-251`, `LogProfiles.kt` |
| Analysis readback always display-referred (transfer null); FocusDetail/motion get no gain LUT | `GlPipeline.kt:1317, 1522-1530, 1826` |
| Front mirror roles: preview mirrors only when the stream is not pre-mirrored; encoder/analysis un-mirror when it is | `FrontMirrorConvention.kt`, `GlPipeline.kt:1056, 1250, 1483` |
| SettingsStore and permission prefs commit synchronously; no `apply()` anywhere | grep: only `commit()` / `edit(commit = true)` in main source |
| Zoom scale follows the ROUTE: one `unifiedZoomOf`/`localZoomOf` pair keyed on `standaloneRouteWanted`, divisor = optical base | `CameraState.kt:446-475, 536-543`; engine band predicate `CameraEngine.kt:610-612` |
| DNG rule 2: `setRawWanted` re-resolves with `overrideId = userCameraPin` inside its own transaction; rule 3 `rawSelectable`; rule 4 still-less sessions keep the request | `CameraEngine.kt:4141-4152`, `OpticsConstraints.kt:103-109`, `ProSheet.kt:885-891`, `OpticsConstraints.kt:77-81` (see VER3-2 for the processed axis) |
| Ready publication: monotonic sequence, observe/owns rechecked inside the StateFlow reducer | `CameraState.kt:1310-1365`, `CameraViewModel.kt:843-930` |
| GL re-seed in the start callback (transfer, digital gain, all renderer assists incl. sourceHlg, gamma assist, AE metering) | `CameraEngine.kt:1872-1897`, `RendererAssists.kt:231-257` |
| Controls throttle 40 ms trailing; pinch coalesce 16 ms; quiet landing 250 ms; end 700 ms; settings debounce 500 ms; moving gesture submits nothing | `CameraViewModel.kt:4103-4106, 2216, 2256-2258, 4275`; `ZoomSubmitPlan.kt` |
| AE schedule: 0.5×|error| in [0.30, 1.20]; deadband 0.05; program slow cap 1/10 s | `AutoExposure.kt:44-60, 125-130` |
| Video AUTO pins the selected fps; photo AUTO uses the lowest-floor range | `CameraEngine.kt:2491, 4367`; `ManualControls.kt:1048-1053` |
| HEVC/AVC offered only; APV inventoried but never offered and never admits a transfer; AVC forces SDR GL + tags | `EncoderCaps.kt:28-29`, `VideoRecorder.kt:1856-1863`, `CameraEngine.kt:6329-6338`, `CameraViewModel.kt:3022-3030` |
| Container tags: log = BT.2020 full + explicit SDR transfer; HLG = BT.2020 limited + HLG; SDR = BT.709 limited | `ColorProfiles.kt` `hevcColorTagsFor` |
| Edge-to-edge `SystemBarStyle.dark` for both bars; portrait lock only below sw600 | `MainActivity.kt:210-212, 354-356`; `CameraScreenPolicy.kt:66` |
| REC tally uses the reported RoundedCorner radius unscaled | `CameraScreen.kt:1073-1094` |
| Hardware keys: 767 → half-press family, 781 → quick button, 168/169 zoom | `MainActivity.kt:783, 876, 1039-1048` |
| TerminalAcquisitionGate.isOpen lock-free; runIfOpen/close synchronized | `CameraEngine.kt:8657-8674` |
| UI glyph coverage: no string-literal or `strings.xml` glyph outside the bundled Inter cmap (fontTools scan; U+2019 is covered, U+25B8 absent and unused) | scan of `res/values*/strings.xml` and `ui/**`, `CameraStatus.kt` literals |
| Facing never persisted; Remember Settings defaults ON; preserve-lens/TELE default ON | `SettingsStore.kt:108-109, 162-166` |
| EXIF Make/Model from the build; lens model from measured focal; passthrough lane overrides orientation | `StillCapturePipeline.kt:474-481, 704, 719-724` |

## Files examined

- camera: `RotationMath.kt`, `CaptureCapabilities.kt`, `ManualControls.kt`, `ZslAdmission.kt`,
  `CameraController.kt` (session plan, request builders, capture/watchdog, ZSL), `CameraEngine.kt`
  (rotation helpers, setRawWanted, setFrontCamera, resolveNonTeleId, pushTeleFinder, GL start,
  recording setup transfer, capturePhoto, TerminalAcquisitionGate, mapTapFocusGeometry),
  `CameraState.kt` (zoom scale pair, rearReturnZoom, PhotoFormats normalization, Ready gate,
  LensChoice), `OpticsConstraints.kt`, `AutoExposure.kt`, `DeviceProfile.kt`, `Teleconverter.kt`,
  `CameraSelector2.kt`, `ZoomSubmitPlan.kt`, `RendererAssists.kt`, `DiagnosticTelemetry.kt`
  (constants)
- gl: `Shaders.kt`, `LogProfiles.kt`, `FlipRenderer.kt`, `GlPipeline.kt`, `FrontMirrorConvention.kt`
- video: `EncoderCaps.kt`, `ColorProfiles.kt`, `VideoRecorder.kt` (transfer admission)
- ui: `CameraViewModel.kt` (Ready handler, applyLoaded, DNG door, TC/converter doors, codec door,
  throttles), `ZoomMath.kt`, `CameraScreen.kt` (tally), `CameraScreenPolicy.kt`,
  `controls/ProSheet.kt` (format chips)
- app: `MainActivity.kt` (system bars, orientation lock, permission prefs, hardware keys)
- storage: `SettingsStore.kt`
- tests: `SessionFallbackLadderTest.kt`
- res: `values*/strings.xml`, `font/inter_*.ttf` (cmap)
- docs: CLAUDE.md, `docs/ARCHITECTURE.md` (color pipeline), `docs/FIELD_CHECKS.md` (front mirror),
  cycle-2 aggregate/plan, cycle-1 verifier V4
