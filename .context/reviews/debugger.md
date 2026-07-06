# Debugger — Run-2 Cycle 1 (2026-07-06)

Latent-bug / crash / failure-mode surface. Full detail in `_aggregate.md`.

- **R1-3 (=UI-1) [High/Med] crash** `ui/controls/ProControls.kt:177-184` ← `ProSheet.kt:391-399` —
  ISO slider gets `valueRange = 400f..400f` before caps → Material3 fraction `0f/0f = NaN` →
  `Float.roundToInt()` throws `"Cannot round NaN value."`. Repro: cold launch → open gear before caps
  → Exposure tab. Fix: guard degenerate range in `LabeledSlider`. **SCHEDULED.**
- **R1-6 (=UI-7) [Low/Med] crash** `ui/overlays/Overlays.kt:349-371` — `drawWaveform` indexes
  `bins[col*rows+row]` with no `bins.size` check → AIOOBE on a resize race. Fix: size guard.
  **SCHEDULED.**
- **R1-1 (=C2-3) [Med/High]** `camera/CameraController.kt:426` — unguarded `pending` reassignment;
  rapid double-tap → dropped shot + leaked `Image` (reader starves at `maxImages=2`). **SCHEDULED.**

Defensive posture confirmed (no bug): `drainVideo` loops past `!running` to EOS; `close()` posts
teardown before `quitSafely()`; late open/config callbacks short-circuit on `closed`; `GyroEis` holds
last confident orientation when flat. Device-only failure modes (H2 hint sign, M3/M4 EIS axis/sign,
tap-focus crop inverse) remain **DEFERRED** — unreproducible on the synthetic-camera AVD.
