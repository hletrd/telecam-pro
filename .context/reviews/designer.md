# Designer / UI-UX — Run-2 Cycle 1 (2026-07-06)

Jetpack Compose UI review (synchronous `code-reviewer` helper over the ~2900 LOC UI layer). Full IDs
in `_aggregate.md`; UI-N ↔ R1-N mapping noted.

## Scheduled this cycle
- **UI-1 → R1-3 [High/Med] crash** `ProControls.kt:177-184` / `ProSheet.kt:391-399` — degenerate ISO
  `Slider` range (`400..400`) before caps → NaN thumb. Guard degenerate range in `LabeledSlider`.
- **UI-3 → R1-7 [Med/High] touch** `CameraScreen.kt:576-600,517-531`, `ManualDials.kt:195-225` —
  `ModeLabel`/`TeleChip`/`DialChip` < 48 dp. Add `sizeIn(minWidth/minHeight=48.dp)`.
- **UI-4 → R1-8 [Med/High] a11y** `ManualDials.kt:195-225` — greyed EV chip is a clickable no-op.
  Make it non-clickable in manual + `semantics { disabled() }`.
- **UI-5 → R1-4 [Med/Med] state** `CameraViewModel.kt:242-268` — AV1/Open-Gate leaves resolution out
  of range + bogus encoder summary. Reconcile `videoResolution`.
- **UI-6 → R1-5 [Low-Med] i18n** locale-sensitive `String.format` on machine readouts. Use `Locale.US`.
- **UI-7 → R1-6 [Low/Med] crash** `Overlays.kt:349-371` — `drawWaveform` unchecked `bins` size.
- **UI-10 → R1-10 [Low] doc** `Overlays.kt:110-118` — stale `AspectRatio.FULL` KDoc.

## Deferred (recorded, exit criteria in `_aggregate.md`)
- **UI-2 → R1-11 [Med-High] perf** — whole `CameraUiState` recomposes dials/top-bar/sheet per
  telemetry tick.
- **UI-8 → R1-13 / UI-9 → R1-14 [Low] maint** — `RulerSlider` unkeyed remember + compose-time write;
  `onFractionChange` needs `rememberUpdatedState` (device-tuned dial feel).
- **UI-11 → R1-12 [Low] perf** — `VideoTab` list re-allocation (fold into R1-11).
- **R1-15 [Low] UX** — top-bar grid toggle discards sheet-chosen grid.
- Standing UX deferrals (unchanged): full a11y semantics + `strings.xml` (UX-8), pinch-to-zoom (M6),
  gallery/last-shot review (UX-5/M7), metered-manual EV needle (UX-1), reachable bottom settings entry
  (UX-4), loading/error state (UX-7), overlay color tokens (UX-16), `LensFlipButton` repurpose (UX-10),
  shutter re-center on snapshot (UX-21).

## Verdict
Overlay math (grids, aspect mask, histogram, focus/shutter/EV/WB dials, stop-snap) is sound; ship the
scheduled fixes above. UI-1 is the one plausible launch-window crash.
