# RPL cycle 5 — verifier review (VF5)

Role: evidence-based check of stated behaviour (CLAUDE.md "Hard-won device facts", docs/ARCHITECTURE.md)
against code at HEAD `ea7d4374`, plus a spot-check of every `[x]` item in
`docs/plans/2026-10-02-rpl-cycle4.md`. Read-only; no Gradle run.

Note: the CLAUDE.md copy injected into agent context is OLDER than the file on disk (e.g. it still
says "≤1 stop/tick", "±35%", "Normalization now runs only when the session has a still target").
Every claim below was checked against the ON-DISK CLAUDE.md, which already corrects those three.

## Summary

| Severity | Count |
|---|---|
| Critical | 0 |
| High | 0 |
| Medium | 1 |
| Low | 3 |
| Info | 4 |

Cycle-4 plan: 58 `[x]` items checked against production code — 55 Implemented, 3 Partial-by-wording
(A.21, B.11, C.13; all within the plan's own "or record why not" / "move/justify" latitude, A.21's
dead helper already tracked as a later-cycle deslop note). None Missing, none Contradicting.

## Findings

### VF5-1 — Recurring diagnostic rows are charged TWICE; the "one 180-row door" is effectively ~90 and silently drops FrameGap/startup evidence (CODE bug vs doc)
- Severity: Medium (evidence integrity: can turn a field check into a false pass) · Confidence: High · Status: Confirmed (code), DEBUG-only
- Citations:
  - `camera/DiagnosticTelemetry.kt:58-68` — `DiagnosticLogDoors.d/i` call `recurringDiagnosticAllowed(..., recurring)` → `budget.tryAcquire()`.
  - `camera/DiagnosticTelemetry.kt:94-108` — `DiagnosticLog` binds that door; files import it as `Log` (`CameraController.kt:23`, `CameraEngine.kt:12`, `ui/CameraViewModel.kt:12`, `video/VideoRecorder.kt:15`, `camera/StandbyAudioController.kt:12`, `camera/StartupTrace.kt:4`).
  - Callers that ALREADY acquired a row and then call the aliased `Log.i` (second acquire): `CameraController.kt:576-578` (ZoomTrace), `:1207-1209` (3A), `:1219-1220` (Touch AF), `:758,765,895,1087,1548,1611,1632,1652,1671,1693,1698,2174,2191,2296`; `CameraEngine.kt:885-886,1456,1919,2187,2292,3447`; `ui/CameraViewModel.kt:597-599` (FocusConfidence), `:700-704` (MotionInversion), `:727-729`; `VideoRecorder.kt:776-777`; `StandbyAudioController.kt:464-467`. (Sites that use raw `android.util.Log.i` after the gate — `CameraEngine.kt:4967,5631,5768,6788,7441,7499`, `GlPipeline.kt:567,909`, `FlipRenderer.kt:342`, `CameraViewModel.kt:1161`, `MainActivity.kt:781` — are charged once, correctly.)
- Why: CLAUDE.md (log-quota bullet) states Capture-family, ZSL, standby, hardware, zoom, motion, focus-confidence, Touch-AF, 3A, session/recording rows "and those [FrameGap] summaries share one 180-row process admission door". In code ~28 producers spend 2 rows per emitted line. Additionally, when exactly one row remains, the outer `tryAcquire()` takes it and the inner door refuses, so the row is spent and nothing is logged. `FrameGap` (`GlPipeline.kt:902-908`) and `StartupTrace.finish` (`StartupTrace.kt:37,86`, via `DiagnosticLog.i`) draw from the same recurring pool, contradicting the KDoc at `DiagnosticTelemetry.kt:8-11` ("startup, frame-gap, recovery, and fault rows always retain an explicit reserve").
- Failure scenario: the A5 ten-minute soak alone emits 41 3A rows (CLAUDE.md) = 82 budget units; add ZoomTrace/Motion/FocusConfidence during the FIELD_CHECKS F1/A6 pinch protocol (`docs/FIELD_CHECKS.md:150-160`) and the 180 pool is exhausted early. The terminal `FrameGap` summary is then silently not printed, and "no FrameGap rows" reads as "no ≥200 ms gaps" — a false PASS for MRG4-5. A later resume's cold-start line is likewise lost.
- Fix (host-testable): pick ONE charging point. Either (a) callers that pre-gate call `android.util.Log.i` directly (as the CameraEngine/GlPipeline sites already do), or (b) remove the explicit `processDiagnosticLogBudget.tryAcquire()` / `recurringDiagnosticAllowed` from those call sites and keep the debug check only. Add a test that drives one gated emission through `DiagnosticLogDoors` with a fresh budget and asserts `usedRows() == 1`. Optionally give FrameGap/StartupTrace their own small reserved slice (or the reserved 120 owner) so the KDoc's "explicit reserve" becomes true. PMA110 behaviour: none (DEBUG logging only).

### VF5-2 — FocusConfidence trace heartbeat is 15 s / 3 s change floor, not "2 s heartbeat" (DOC drift)
- Severity: Low · Confidence: High · Status: Confirmed
- Citations: `CLAUDE.md:1168` ("`FocusConfidence` trace (change-gated + 2 s heartbeat)"); code `ui/CameraViewModel.kt:557` `DiagnosticChangeLogGate<Any?>()` with defaults `DIAGNOSTIC_CHANGE_MIN_INTERVAL_MS = 3_000L`, `DIAGNOSTIC_HEARTBEAT_MS = 15_000L` (`camera/DiagnosticTelemetry.kt:368-369`, gate logic `:216-225`).
- Why/scenario: an on-device checker waiting for a 2 s heartbeat to confirm the detector is alive will see nothing for up to 15 s, and changes faster than 3 s are coalesced — misread as a silent/refused detector.
- Fix: update the CLAUDE.md sentence to "change-gated (≥3 s between changes) + 15 s heartbeat". No code change.

### VF5-3 — FrameGap threshold is strict `> 200 ms`; docs say it "still catches the ~180 ms setRepeatingRequest stalls" and FIELD_CHECKS pass criteria count "≥200 ms" gaps (DOC drift / instrumentation gap)
- Severity: Low · Confidence: Medium · Status: Needs device validation
- Citations: `camera/DiagnosticTelemetry.kt:140` (`gap.takeIf { it > frameGapThresholdMs }`), `:173`, `:371` (`PREVIEW_FRAME_GAP_THRESHOLD_MS = 200L`); `CLAUDE.md:1161-1165`; `CLAUDE.md:310-313` (stall measured "170–250 ms"); `docs/FIELD_CHECKS.md:141-143,157-158`; comment `gl/GlPipeline.kt:958-964`.
- Why: a stall of 170–200 ms (the low half of the measured swap-stall band, which the F1/MRG4-5 check exists to detect — "each re-center is a sensor fast-path submit that this HAL pays for with a ~180 ms repeating-request swap") produces an inter-frame gap that may land at or under 200 ms and is never counted. "≥200 ms" in FIELD_CHECKS also disagrees with the strict `>` in code at exactly 200.
- Fix: either reword CLAUDE.md/FIELD_CHECKS to "catches stalls whose producer gap exceeds 200 ms (the upper part of the 170–250 ms swap band)" and use "> 200 ms" consistently, or (if the MRG4-5 check needs it) lower the threshold for a dedicated debug-only check window. Do not change the steady-state threshold without re-checking the quota math in VF5-1.

### VF5-4 — CLAUDE.md teleconverter section announces "Five rules" and lists six (DOC drift)
- Severity: Info · Confidence: High · Status: Confirmed
- Citation: `CLAUDE.md:640` vs items 1–6 at `:641-683`.
- Fix: "Six rules hold this together".

### VF5-5 — AutoExposure comment says the deadband is "~1/12 stop"; the constant is 0.05 stop (~1/20) (comment drift)
- Severity: Info · Confidence: High · Status: Confirmed
- Citations: `camera/AutoExposure.kt:31-32` vs `:47` `DEADBAND_STOPS = 0.05f`.
- Fix: correct the comment (do not retune the constant — steady-state smoothness is user-tuned).

### VF5-6 — `DiagnosticTelemetry.kt` KDoc promises a reserve for startup/frame-gap rows that the code does not provide (comment drift; companion to VF5-1)
- Severity: Info · Confidence: High · Status: Confirmed
- Citations: `camera/DiagnosticTelemetry.kt:8-11`; StartupTrace and FrameGap both spend from `processDiagnosticLogBudget` (180), not `processReservedDiagnosticLogBudget` (120, warnings/errors only).
- Fix: fold into VF5-1 (either give them a reserve or say they share the recurring pool, as CLAUDE.md does).

### VF5-7 — Standby meter: a zero-length `AudioRecord.read` is an unbounded `continue` (no backoff) (Info, not a doc mismatch)
- Severity: Info · Confidence: Low · Status: Needs device validation
- Citations: `video/AudioReadPolicy.kt:15-20` (`byteCount == 0 -> Retry`); `camera/StandbyAudioController.kt:753-758` (`AudioReadOutcome.Retry -> continue`).
- Why: CLAUDE.md's "zero retries" rule is about NEGATIVE reads and is honoured. A blocking `read` returning 0 repeatedly (legal per the API, rare in practice) would spin the meter thread while ownership is held. No observed instance.
- Fix (optional): count consecutive zero reads and treat N in a row as TERMINAL_READ, or sleep briefly on Retry. PMA110 unchanged in practice.

### VF5-8 — Lens-match tolerance wording now correct on disk; injected copy stale (Info)
- On-disk `CLAUDE.md:613-614` correctly says "×1.35 ratio … +35 % / −25.9 %" matching `camera/CameraState.kt:926,951`. Only the stale agent-context copy says "±35%". No action beyond keeping the disk file authoritative.

## Claims verified as MATCHING code (no finding)

- Zoom scale follows route: `unifiedZoomOf`/`localZoomOf` (`camera/CameraState.kt:448-461`) keyed on `standaloneRouteWanted(video, raw, rawForcesStandalone) = video || (raw && law)` (`:547-556`), no default on the law arg (AGG4-47).
- Preview exposure cap: `previewExposureTrade` (`camera/ManualControls.kt:400-439`) caps at `min(PREVIEW_FLUIDITY_MAX_EXPOSURE_NS = 66_666_667, PREVIEW_SAFE_MAX_EXPOSURE_NS = 500 ms)` unconditionally, residual gain ≤ `PREVIEW_MAX_DIGITAL_GAIN = 16f` (`:325,340,341`).
- Still ceiling: `HAL_SAFE_MAX_STILL_EXPOSURE_NS = 4 s` (`CaptureCapabilities.kt:20`) applied at the caps seam from `activeDeviceProfile().stillExposureCeilingNs` (`CameraEngine.kt:1344-1348`); PMA110 profile = 4 s, GENERIC = null (`DeviceProfile.kt:64-80`); external routes take GENERIC (`:88-89`).
- Rotation: `previewRotationDegrees` = 180 in TELE else 0; `captureRotationDegrees` BACK = sensor + afocal − dev, FRONT = sensor + dev, EXTERNAL = sensor (`camera/RotationMath.kt:35,149-162`); `encoderSurfaceSize` swaps on 90/270 (`:213-219`).
- ZSL admission: 1/6 stop on exposure AND ISO, zoom 2 %, age 0..400 ms, manual AE, processed-only, no RAW, flash OFF/TORCH, no gesture (`camera/ZslAdmission.kt:36-42,77-101`); SINGLE drive only and never in-REC (`CameraEngine.kt:5152-5161`).
- Still watchdog: floor 8 s for HAL-AE; exposure ceil-to-ms + 8 s margin, saturating (`ManualControls.kt:583-607`); controller feeds the clamped exposure (`CameraController.kt:2058-2066`).
- DNG route-input rules 1–4: restore/encoder paths push `setRawWanted` (`CameraViewModel.kt:2587,2841`); reopen only when `dngIntentChangesRearRoute`, transaction with `overrideId = userCameraPin`, paused path drops cached override (`CameraEngine.kt:4213-4292,8639-8647`); `rawSelectable` (`OpticsConstraints.kt:97-103`); `acceptedOpticsAuxState` never edits the request (`OpticsConstraints.kt:53-76`); RAW reader is route-carried, not intent-carried (`CameraController.kt:2864-2865`).
- `tenBitSessionWanted` and the 10-bit attempt-0 rung with no still readers, then ordinary ladder from rung 1 (`CameraState.kt:573-574`, `CameraController.kt:2822-2832`).
- Mic decline: START_RECORDING → audio off + record; denial reason written before `onToggleRecordAudio(false)`; operator silence clears the reason (`CameraPermissionPolicy.kt:38-99`, `MainActivity.kt:540-550,943-963`, `AudioDenialReason.kt`).
- Settings: `SettingsStore.commitEdit` uses `.commit()` (`storage/SettingsStore.kt:150-156`); all other prefs writers use `edit(commit = true)`; save debounce 500 ms (`CameraViewModel.kt:4401`).
- Controls throttle 40 ms with `applyScheduled` (`CameraViewModel.kt:319-329,4223-4225`); zoom flush 16 ms, quiet landing 250 ms, interaction end 700 ms (`:2281,2321,2323`); ease ticker 33 ms (`:469`).
- Log quota constants 180/120/300 (`DiagnosticTelemetry.kt:31-33`); FrameGap 200 ms threshold, 15 s summary, 400/1000 ms buckets (`:159-200,371-372`) — see VF5-1/3 for the accounting defects.
- Standby recreation ≤3 failed generations, reset on first PCM, 300 ms backoff (`StandbyAudioController.kt:752-756,857-872,912-913`; `AudioReadPolicy.kt:27-28`).
- AE schedule 0.5×|e| in [0.30, 1.20], program shutter ±0.35/tick, 1/10 s ceiling (`AutoExposure.kt:43-51,124,137`).
- Front pick: non-logical first, then largest array, then id (`CameraSelector2.kt:424-427`); tele pick closest-to-70 with standalone tie-break (`:344-364`).
- Model-string seams: only `DeviceProfile.resolve` and `detectPhone`/`PhoneModel` (`DeviceProfile.kt:84`, `Teleconverter.kt:41`); `Build.MODEL` elsewhere only feeds those or EXIF labels.
- Hardware keys 767 half-press, 781 quick, 769/782 (`MainActivity.kt:1025-1034`); quick button default SHUTTER (`CameraState.kt:1720`).
- Portrait lock below sw600 (`ui/CameraScreenPolicy.kt:66`); preview recovery ≤3 (`CameraEngine.kt:8217`); DNG allocation 8 s (`DngPreCaptureAllocation.kt:260`); pre-native 2+4, still publication 2+2, rejected-output 2+8 (constants listed in their files).
- `TerminalAcquisitionGate.isOpen` is a `@Volatile` read (`CameraEngine.kt:8903,8923`).
- ZoomGlide invalidation on every remap door incl. converter declaration (`CameraViewModel.kt:2429-2436,2770-2775`; `ui/ZoomGlideState.kt:88-94`).

## Cycle-4 plan spot-check (`docs/plans/2026-10-02-rpl-cycle4.md`)

All `[x]` items located in production code at HEAD. Representative evidence (full table available on
request): A.1 `CameraViewModel.kt:4164-4192` (no admission write); A.2 `CameraEngine.kt:1026-1070`
BARE_RETRY → `scheduleColdStartRetry`, M.2 BLOCKED → `CAMERA_UNAVAILABLE_REOPEN`; A.3 `:3038-3042`;
A.8 `CameraViewModel.kt:2369-2370`; A.12 `ManualControls.kt:717-745` + `CameraController.kt:1444-1448`
(EV-only under admitted manual AE is NO_OP, and `applyExposure` writes no AE_EXPOSURE_COMPENSATION in
that branch, `ManualControls.kt:1075-1085`); A.17 `CaptureCapabilities.kt:347`; A.18
`CameraEngine.kt:1512-1515`, `CameraViewModel.kt:4526-4545`; A.19 `CameraEngine.kt:1414`; A.22
`GlPipeline.kt:519,2073`; A.25 `CameraController.kt:866-877`; B.1/B.2/M.3 `MediaStoreWriter.kt:1462-1467,
2749-2925`; B.4 `StillCapturePipeline.kt:313-392`; B.6 `:703` (`xxx`); B.8 `ProcessAdmissionSignal.kt:58-72`;
B.9 `FamilyDeletionMarkerDispatcher.kt:163`; C.8 `RulerAccessibility.kt:13`; C.9 `CameraStatus.kt:266,295`;
C.12 `app/build.gradle.kts:765`; C.13 `tools/verify_host.py:109-167`.

Partial-by-wording (no defect):
- A.21: the Activity no longer supplies provenance; the ViewModel reads `AudioDenialReasonStore` itself
  (`CameraViewModel.kt:769,3640`). `MemoryBankAudioProvenance.bankAudioOffByDenialNow` (`AudioDenialReason.kt:53`)
  is production-dead — already tracked (plan "later cycle: deslop note").
- B.11: suppression justified at the declaration (`VideoRecorder.kt:677-680`) pointing at the guard (`:365`), not moved.
- C.13: `:app:lintRelease` runs only on a clean tree (`verify_host.py:109,126-127,159-167`); dirty-tree runs print a NOTE. Recorded rationale exists, as the plan allowed.

## Final sweep / coverage

Commonly-missed checks done: double-gated logging (found VF5-1), stale injected-vs-disk docs, strict vs
inclusive thresholds (VF5-3), default arguments that silently select PMA110 law (`standaloneRouteWanted`
has none; `CameraCaps.read`'s 4 s default is overridden by its only caller), model-string leaks, zero-length
audio reads (VF5-7).

Files read/grepped: CLAUDE.md (on disk, §Hard-won device facts), docs/ARCHITECTURE.md (numeric claims),
docs/FIELD_CHECKS.md (F1/A6), docs/plans/2026-10-02-rpl-cycle4.md; camera/{CameraEngine, CameraController,
CameraState, CaptureCapabilities, DeviceProfile, ManualControls, AutoExposure, ZslAdmission, RotationMath,
OpticsConstraints, DiagnosticTelemetry, StandbyAudioController, StartupTrace, CameraSelector2, Teleconverter,
DngPreCaptureAllocation, StillPublicationDispatcher, FamilyDeletionMarkerDispatcher, VendorTagInspector}.kt;
video/{AudioReadPolicy, VideoRecorder}.kt; gl/{GlPipeline, FlipRenderer, FocusDetail}.kt;
focus/MacroProximity.kt; storage/{SettingsStore, MediaStoreWriter}.kt; ui/{CameraViewModel, ZoomGlideState,
CameraScreenPolicy}.kt; MainActivity.kt; AudioDenialReason.kt; CameraPermissionPolicy.kt; stab/GyroEis.kt;
plus cycle-4 implementing files via the plan spot-check.
