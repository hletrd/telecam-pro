# Code-Reviewer — Run-2 Cycle 1 (2026-07-06)

Logic / correctness / maintainability. Full IDs + fixes in `_aggregate.md`.

- **R1-1 (=C2-3) [Med/High]** `camera/CameraController.kt:413-457` — `capturePhoto` overwrites the
  single `pending` slot with no in-flight guard; a rapid double-tap drops/misattributes the first
  shot and leaks its `Image`. BURST/AEB unaffected (next shot posts after `pending` clears). Fix:
  reject when `pending != null` via `cb.onError`. **SCHEDULED.**
- **R1-4 (=UI-5) [Med/Med]** `ui/CameraViewModel.kt:242-268` — `onVideoCodec`/`onToggleOpenGate`
  reconcile fps but not `videoResolution`; AV1/Open-Gate leaves an out-of-range resolution + bogus
  encoder summary. Fix: clamp resolution to the newly-valid option set. **SCHEDULED.**
- **R1-8 (=UI-4) [Med/High]** `ui/controls/ManualDials.kt:195-225` — `DialChip.enabled` is cosmetic;
  the greyed EV chip in manual is a clickable no-op. Fix: honor `enabled` on the EV chip's
  `clickable` + `semantics { disabled() }`. **SCHEDULED (EV slice).**
- **R1-9 (=DOC-1) [Low/High]** `video/VideoRecorder.kt:28-30` — class KDoc "NO MediaMuxer orientation
  hint is set" contradicts `start()` (`:88-90`). **SCHEDULED.**
- **R1-10 (=UI-10) [Low/High]** `ui/overlays/Overlays.kt:110-118` — KDoc references removed
  `AspectRatio.FULL`; code early-returns on `W4_3`. **SCHEDULED.**
- **R1-15 [Low/Med]** `ui/CameraScreen.kt:359-362` — top-bar `GridButton` toggles only `NONE⇄THIRDS`,
  discarding a sheet-chosen grid. **DEFERRED.**
- Nit `ManualDials.kt:134-137,409-416` — `evStepValue`/`majorEvery` guard `denominator==0` but not
  `numerator==0`; a `Rational(0,d)` would zero the readout. DEFERRED (hardware won't report it).

Verified sound: `RotationMath`, `CameraSelector2.pickBest`, `FocusMapping`, `kelvinTintToRggbGains`,
session fallback ladder, `Pending` completion/close paths, lifecycle guards.
