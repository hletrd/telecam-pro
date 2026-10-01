# Verifier — RPL cycle 2 (2026-10-02)

Lane: evidence-based check of the code against what CLAUDE.md, `docs/ARCHITECTURE.md`,
`docs/FIELD_CHECKS.md`, KDoc and the cycle-1 plan (`docs/plans/2026-10-02-rpl-cycle1.md`) say it does.
Scope: every `[x]` item in the cycle-1 plan (`git log ba5b16e7..HEAD`), then a sample of the CLAUDE.md
"Hard-won device facts" invariants. Read-only; no source edits.

Evidence run: `./gradlew :app:testDebugUnitTest --tests '*ZoomMathTest' --tests '*ExposureModeHandoffTest'
--tests '*RecorderQuarantine*'` exited 0. I did not run the full `tools/verify_host.py`; another lane
runs it.

## Findings

### VER2-1 — An MR recall/restore that changes DNG can land on the wrong lens (race between two transactions)
- **Where:** `ui/CameraViewModel.kt:1429` (`engine.setResolvedOptics`) then `ui/CameraViewModel.kt:1492`
  (`engine.setRawWanted(safeFormats.dngRaw)`), with about 25 synchronous engine setters in between.
  Engine side: `camera/CameraEngine.kt:2792` (`resolveNonTeleId(resolvedLens)` reads `rawWanted` when
  the setupExecutor task runs) and the fast-path terminal at `camera/CameraEngine.kt:2835-2841`
  (`lensBandFollowsZoom(...)` → `lensChoice = LensChoice.forZoom(controls.zoomRatio)`).
- **What the docs say:** the P1.1/AGG-2 fix says a DNG Photo packet restores on its lens-local scale.
  CLAUDE.md says recall is "one complete packet" inside a generation-owned transaction.
- **What the code does:** `restoredOptics(photoStandalone = true)` correctly keeps lens TELE3X at
  local 1.0, but the engine learns the new DNG intent only in a SECOND transaction. If the
  setupExecutor runs T1 before the main thread reaches `setRawWanted`:
  1. `resolveNonTeleId` still sees `rawWanted=false` and resolves the logical id "0", which is the
     same as the current session.
  2. T1 therefore takes the fast path, and its terminal mutation re-derives the band from the
     lens-local 1.0 → `lensChoice = MAIN`.
  3. T2 (`setRawWanted(true)` with `resolvedLens = null`) keeps `MAIN` and reopens the standalone
     MAIN lens at 1.0.
  4. The VM still shows TELE3X, because `onCapsReady` at `ui/CameraViewModel.kt:3062` does not
     re-band while DNG is on.
- **Failure scenario:** Photo, DNG off, ready on the logical camera. The user recalls an MR bank saved
  as "DNG on, 3× lens". The rail and OSD say 3× / ~70 mm, but the session and files come from the
  23 mm main lens at 1×. This is the same symptom AGG-2 fixed, now reachable through recall timing.
- **Fix:** carry `rawWanted` inside `setResolvedOptics`'s own `beginOpticsTransaction`, add a
  `resolvedRawWanted` parameter, and drop the trailing `setRawWanted` from `applyLoaded` (or make it a
  guaranteed no-op). The route answer, band derivation and rollback then share one generation. This is
  the concrete bug behind the "recall split" the plan parked under AGG-49. It is more than a
  structural cleanup.
- **Confidence:** Medium. **Status:** likely; needs manual validation (timing-dependent). A host test
  can make it deterministic: run the setupExecutor synchronously between the two calls.

### VER2-2 — The AGG-4 / 3ec126e1 rollback leaves DNG wanted over a restored logical Photo session after a failed Video recall
- **Where:** `camera/CameraEngine.kt:1011` (`if (rawWantedDirectWrites == before.rawWantedDirectWrites)
  rawWanted = before.rawWanted`) and `camera/CameraEngine.kt:4003-4015` (the direct-write branch
  bumps the counter). The VM mirror is at `ui/CameraViewModel.kt` in the `onOpticsRollback` handler
  (`photoFormats.copy(dngRaw = rollback.rawWanted)`).
- **What the docs say:** AGG-4: "a failed DNG reopen no longer leaves DNG wanted over a logical
  session". CLAUDE.md: "owned async failure restores all of it".
- **What the code does:** in a recall into VIDEO, T1 (`setResolvedOptics`) has already published
  `videoMode=true`. The trailing `setRawWanted(true)` then sees no route flip (Video is standalone
  either way), takes the direct-write branch and bumps `rawWantedDirectWrites`. If T1 fails (no id,
  or reconfigure exhausted), rollback restores Photo/logical but deliberately keeps
  `rawWanted = true`, because the counter moved, and publishes it. The VM then shows the DNG chip on.
- **Failure scenario:** the user is in Photo with DNG off and recalls a Video bank saved with DNG on;
  the recall fails ("camera unavailable, recall unchanged"). The UI is back in Photo with DNG lit,
  but the session is logical with no RAW reader:
  - Shots silently produce no DNG.
  - Both the engine and VM band predicates now treat the unified zoom as lens-local, so the band
    freezes while pinching.
  - The next reopen (resume, aspect, or similar) re-resolves to a standalone lens while still holding
  a unified ratio. That gives the AGG-1 9× crop.
- **Fix:** same root fix as VER2-1: make DNG part of the recall packet so it is snapshotted and rolled
  back with that transaction. If the counter is kept, it should exempt writes issued as part of the
  same logical door (the recall), or restore DNG whenever the restored mode/route makes the kept value
  a route flip.
- **Confidence:** Medium-High (code path is unambiguous; reaching it needs a failed recall).
  **Status:** confirmed by code reading; needs manual validation for user impact.

### VER2-3 — AGG-37 fix (fe7d0578) re-creates the self-locking silent-audio state
- **Where:** `MainActivity.kt:517-528` (`onRecallMemorySlot` clears `AUDIO_OFF_BY_DENIAL_KEY` whenever
  the slot is active after recall). Related code: `CameraPermissionPolicy.kt:95-98`
  (`audioRestoredByMicrophoneGrant`) and `storage/SettingsStore.kt:347,446` (a bank stores only
  `recordAudio`, with no provenance).
- **What the docs say:** CLAUDE.md (2026-07-29): "A denial-disabled audio track is RESTORED by a later
  grant; operator-chosen silence is not." The flag was introduced because conflating the two was
  self-locking.
- **What the code does:** a bank saved while audio was denial-disabled stores `recordAudio=false`,
  which looks the same as deliberate silence. Recalling ANY bank now clears the denial reason. After
  that, granting RECORD_AUDIO in Settings never restores audio, and both symptoms come back: silent
  clips and no level meter.
- **Second, smaller problem:** the `activeMemorySlot == slot` guard is also true when the same slot was
  already active and the re-recall was refused, so a refused recall clears the flag too.
- **Fix:** persist the provenance in the bank (an `audioOffByDenial` field saved alongside
  `recordAudio`) and restore it on recall. Do not clear the flag unconditionally. Alternatively, clear
  it only when the recalled bank's `recordAudio` is true. To detect an applied recall, use the recall's
  own return value, not a post-hoc `activeMemorySlot` comparison.
- **Confidence:** High (logic). **Status:** confirmed by code reading.

### VER2-4 — AGG-34 is only partly fixed: a format edit before the encoder inventory loads still saves the degraded HEIF→JPEG placeholder
- **Where:** `ui/CameraViewModel.kt:2427-2428`:
  ```kotlin
  val formats = formats.normalizedForEncoder(s.heifAvailable)
  if (!s.encoderInventoryLoaded) pendingPhotoFormatsUntilInventory = formats
  ```
  `heifAvailable` defaults to `false` (`camera/CameraState.kt:1649`).
- **What the docs say:** P3.8: "`currentExtras` persists the pending pre-inventory
  codec/transfer/formats", so the operator's request survives the inventory window. `applyLoaded`
  (line 1308) and `onTransfer` (line 2408) both store the RAW request as pending.
- **What the code does:** `onSetPhotoFormats` stores the NORMALIZED value as pending, and the chips
  it starts from already show the placeholder (HEIF off, JPEG on). Any format tap in the window (for
  example adding DNG) therefore turns the persisted HEIF request into JPEG for good:
  `applyEncoderInventory` (line 2695) applies the pending set as-is.
- **Fix:** record the operator's request as pending. Merge the edit into
  `pendingPhotoFormatsUntilInventory ?: s.photoFormats` before normalizing, and normalize only the
  value published to state and the engine.
- **Confidence:** High. **Severity:** Low (short startup window). **Status:** confirmed by code reading.

### VER2-5 — The DNG door decides "route flips" from a different source in the VM than in the Engine, and the VM commits the remap anyway
- **Where:** `ui/CameraViewModel.kt:2440-2443` uses `s.rawForcesStandalone`, which is the
  `CameraUiState` default `true` until the first route-inventory publication (line 774) and is also
  refreshed at line 2725. `camera/CameraEngine.kt:3998-4001` uses
  `activeDeviceProfile().rawRequiresStandalone`, which is route-dependent (EXTERNAL → generic).
- **What the code does:** when the two disagree, the VM remaps lens/zoom and writes the result to
  state (`ui/CameraViewModel.kt:2466-2467`). The engine sees `routeFlips = false` and IGNORES
  `resolvedLens`/`resolvedControls` (direct-write branch, line 4010). From then on the VM shows one
  scale and the wire carries the other. Example: a generic device before the first inventory, where
  unified 3.0 becomes "TELE3X local 1.0" in the UI only.
- **Fix:** have `setRawWanted` return the optics it applied (or whether the route flipped) and publish
  that. Alternatively, read `engine.rawForcesStandalone` directly at the door, as `applyLoaded`
  already does.
- **Confidence:** Medium. **Severity:** Low (narrow window, non-PMA110). **Status:** likely; needs
  manual validation.

### VER2-6 — The "10-bit video · stills off" caption claims 10-bit whenever a Video session has no still readers
- **Where:** `ui/controls/ProControls.kt:1134-1141`. The caption is chosen by `videoMode` alone.
- **What the docs say:** CLAUDE.md: "That is exactly what the UI means by `10-bit video · stills
  off`". The caption is supposed to name the designed trade-off.
- **What the code does:** it is also shown when SDR video fell to the preview-only rung, and when a
  10-bit request fell through the ladder to rung 3. Per the newly pinned `SessionFallbackLadderTest`,
  rung 2 already dropped HLG, so in that case the session is 8-bit. Both captions are false.
- **Fix:** gate the caption on the accepted session's transfer/10-bit truth (and on
  `tenBitSessionWanted`). Otherwise show the existing `status_still_capture_unavailable` wording.
- **Confidence:** Medium. **Severity:** Low (fallback rungs are rare on PMA110). **Status:** needs
  manual validation.

### VER2-7 — CLAUDE.md's ladder bullet still omits the 10-bit rung that P3.15 documented in code
- **Where:** CLAUDE.md line 401 ("non-TELE is full → drop RAW → drop HLG → preview-only"). Compare the
  comment at `camera/CameraController.kt` added in 079b665e and the new `SessionFallbackLadderTest`
  case.
- **What the code does:** in a 10-bit Video request, attempt 0 is HLG10 with NO still readers.
  Attempt 1 is HLG10 + the processed still reader (no RAW). So a session that lands on rung 1 is
  10-bit WITH stills, which contradicts the doc's "10-bit … buys that by dropping the stills". Rung 2
  is 8-bit.
- **Fix:** add one sentence to the ladder bullet and the 10-bit bullet. The code comment already says
  that changing the rung needs a PMA110 measurement.
- **Confidence:** High. **Severity:** Info/doc. **Status:** confirmed.

## Cycle-1 `[x]` items: claim checked against code

| Item | Verdict |
|---|---|
| P1.1 AGG-2 `restoredOptics(photoStandalone)` | Present and correct for the single-transaction path; see VER2-1 for the recall race |
| P1.2 AGG-3 one `lensBandFollowsZoom` predicate | Present at all three engine `forZoom` sites (`CameraEngine.kt:589,2684,2837`); the VM sites use the same predicate |
| P1.3 AGG-1 `remapRouteScaleOptics` door | Present; the helper's math is consistent (`localZoomOf`/`unifiedZoomOf`), and glide `pendingRatio`/`easeTarget` are reset via `invalidateForRemap` |
| P1.4 AGG-4 rawWanted in snapshot + publication | Present; partly defeated by the 3ec126e1 counter on the recall path (VER2-2) |
| P1.5 AGG-5 paused toggle drops `overrideId` | Present (`CameraEngine.kt:4016-4026`) |
| P1.6 AGG-6 non-BACK field-only write | Present; leaving FRONT recomputes the scale via `rearReturnZoom` with the updated intent (engine and VM agree) |
| P1.7 AGG-7 format change stops timelapse | Present |
| P2.1–P2.3 exposure handoffs | Present; `exposureModeHandoff` seeds from live values only for HAL-AE P. The no-overshoot claim of the AE step schedule also holds (`0.6e` and `0.5e` are both < `e`) |
| P3.1 tri-state validation | Present; INDETERMINATE retains the row only under `muxerStarted && wroteVideoSample && failure == null` |
| P3.2 recovery progress | Present (`continueAfterFailureExhaustion = nextCursor != cursor`) |
| P3.3 prior-family tombstone | Present; bounded `deletedPriorOutputs`, and confirmed survivors are forgotten |
| P3.4 characteristics retry | Present; failure logs once via the reserved facade (`DiagnosticLog as Log`) |
| P3.5 token door | Present; the owner-keyed door again refuses everyone while a token is pending (the pre-0ab5c1ba semantics); workers enter by exact token |
| P3.8 / P3.9 | P3.9 present. P3.8 partial (VER2-4) |
| P3.10 HEIF EXIF best-effort | Present |
| P3.12 aspect refuses mid-REC | Present |
| P3.13 Loupe hit-test | Present; hit-test, GL scissor and Compose border all pass `h * finderBottomClearanceFraction` |
| P3.14 nativelog | Present; `tenBitHlg = tenBitSessionWanted(...)` only, at both sites |
| P3.15 ladder pin | Present in code and test; doc gap is VER2-7 |
| AGG-36 requested size | Present, including rollback and recall mirrors |
| AGG-37 recall audio | Present, but see VER2-3 |
| P4.1 Test timeout | `app/build.gradle.kts:679` (30 min) |
| P5.5 toolchain | CLAUDE.md table matches `libs.versions.toml` and the wrapper (AGP 9.4.1, Kotlin 2.4.20, BOM 2026.09.00, Gradle 9.8.0, core-ktx 1.19.1, Robolectric 4.17). SDK levels are 37/36/33 |
| CLAUDE.md token-door wording | Updated by 363c4dbd ("admitted by TOKEN") |

## CLAUDE.md invariants checked and holding (sample)

- Watchdog: `captureWatchdogTimeoutMs` ceils to ms, saturates, applies `max(floor 8 s, exposure + 8 s)`
  and uses the request-clamped exposure only for non-HAL-AE (`CameraController.kt:2022-2030`).
- Still ceiling of 4 s (`HAL_SAFE_MAX_STILL_EXPOSURE_NS`), preview fluidity cap of 1/15 s, preview
  safety cap of 500 ms, digital gain capped at ×16: constants match.
- ZSL: 1/6 stop, 2 % zoom, 400 ms age, ring depth 3: match `ZslAdmission.kt`.
- AE schedule `0.5×|e|` clamped to [0.30, 1.20]: matches `AutoExposure.kt`.
- Throttle of 40 ms, settings debounce of 500 ms, zoom flush of 16 ms, quiet landing at 250 ms,
  interaction end at 700 ms, `SENSOR_SUBMIT_MIN_INTERVAL_MS` of 200: match `CameraViewModel.kt`.
- Log budget: 180 recurring + 120 reserved = 300. FrameGap threshold of 200 ms with 15 s summaries.
- `FINDER_MIN_ZOOM = 3f`, `TELE_MAX_DISPLAY_ZOOM = 60f`, `LENS_MATCH_TOLERANCE = 1.35f` (CLAUDE.md now
  says ×1.35 log-symmetric). The gravity thresholds of 4.9 (½ g) and 2.5 match.
- `TerminalAcquisitionGate.isOpen()` is a lock-free `@Volatile` read; the other members stay
  `@Synchronized`.
- The `external` union volume maps to `external_primary` (`PendingDiscardJournal.kt:746`).
- System bars use `SystemBarStyle.dark`. The portrait lock is keyed on `smallestScreenWidthDp`.
- No `apply()` or async prefs writes in SettingsStore or MainActivity.
- Ready publications go through `CameraReadyPublicationGate` with an ownership recheck inside the
  StateFlow reducer.

## Final sweep

No `[x]` item in the cycle-1 plan was missing from the code. Two items are only partly effective:
P1.4/AGG-4 (VER2-2) and P3.8/AGG-34 (VER2-4). The fe7d0578 AGG-37 fix brings back the self-locking
audio state that CLAUDE.md forbids (VER2-3). Together, the recall path's
`setResolvedOptics` + `setRawWanted` split (VER2-1, VER2-2) is the most important open defect. Under
CLAUDE.md, every camera/zoom item above still needs PMA110 validation before it is called fixed.
