# Test-Engineer — Run-2 Cycle 1 (2026-07-06)

Coverage / test-gap analysis.

**Current suites (37 tests, all green):** `RotationMathTest`, `CameraSelector2Test`,
`VideoCapabilitiesTest` (`VideoFrameRate.availableFor` + `videoBitRate`), `FocusMappingTest`. Pure
logic is well-covered; hardware paths stay device-verify per CLAUDE.md.

**Gaps (DEFERRED — pure-extraction candidates, TEST-5..12):**
- `ManualControls.effectiveExposureNs()` (cine angle→ns) — pure, untested.
- `kelvinTintToRggbGains(kelvin, tint)` — pure math, untested (R,Geven,Godd,B order + tint sign are
  correctness-sensitive).
- `GyroEis.currentDeviceOrientation` gravity→quadrant + `FLAT_GRAVITY_THRESHOLD` hold — needs the math
  extracted pure.
- ISO/shutter stop-snap math in `ManualDials` — pure, untested.

**This cycle's scheduled fixes:** R1-1, R1-3, R1-4, R1-6, R1-8 live in Android-typed classes
(`CameraController`/Compose/`CameraViewModel`) → not JVM-unit-testable without Robolectric (not in the
toolchain). Verified by build + lint + reasoning + device (R1-1/R1-3). No new purely-testable logic is
introduced, so the 37-test gate stays green with no new test required. Recommend picking up one
extraction slice (e.g. `effectiveExposureNs`) in a future cycle.
