# Causal-tracing review — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Scope and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely, inventoried all
471 repository paths, and traced the production flows across all relevant files rather than
sampling isolated functions. The causal pass covered optics generations and rollback, camera/device
and session ownership, preview/encoder/analysis generations, ZSL capture correlation, processed/RAW
publication and capture-family deletion, recording admission/native/storage terminals, lifecycle
and topology transitions, standby/recording audio handoff, review media work, and main-thread state
publication. Tests and completed plans were checked as competing-oracle evidence, and resolved
historical findings were not repeated.

## Finding

### TRACE35-01 — RMS quantization shifts the newly added near-clipping peak boundary

- **Severity / confidence:** Low / High.
- **Status:** Confirmed by pure arithmetic and the production call chain; no device-dependent
  premise.
- **Exact evidence:**
  - Standby and recording correctly accumulate post-gain per-channel maxima before emission at
    `app/src/main/kotlin/me/hletrd/telecampro/camera/StandbyAudioController.kt:494-541` and
    `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:771-812`.
  - The shared quantizer rounds values to 1/256 at
    `app/src/main/kotlin/me/hletrd/telecampro/video/AudioLevels.kt:100-110`.
  - `CameraViewModel` sends the peak array through that RMS-oriented quantizer before storing it at
    `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1008-1027`.
  - The accessibility reducer classifies `peak >= 0.95` as `NEAR_CLIPPING` at
    `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:660-679`.
  - The new tests exercise raw `0.95` and producer output directly, but never cross the ViewModel
    quantization boundary: `app/src/test/kotlin/me/hletrd/telecampro/ui/overlays/InstrumentAccessibilityComposeTest.kt:32-75`.
- **Causal trace:** A PCM magnitude of 31,130 is `31130 / 32768 = 0.950012...`, so the producer
  truth satisfies the documented near-clipping threshold. `quantizeLevels` computes
  `round(0.950012 × 256) / 256 = 243 / 256 = 0.94921875`. The reducer then observes a value below
  0.95 and reports `HIGH`, not `NEAR_CLIPPING`. In general the entire interval
  `[0.95, 243.5/256)` (approximately `[0.95, 0.951171875)`) is downgraded. Full clipping survives
  accidentally because `32767/32768` rounds to `1.0`; that does not make the lower boundary sound.
- **Competing hypotheses checked:**
  1. **The producer drops transient peaks:** rejected. Both producers accumulate every admitted
     PCM buffer and hold maxima until the throttled emit; the new recording test proves the
     cross-buffer hold.
  2. **The reducer threshold itself excludes 0.95:** rejected. The predicate is inclusive and its
     direct unit test passes raw `0.95`.
  3. **The UI receives raw peak truth:** rejected. The ViewModel explicitly replaces it with the
     1/256 RMS bucket before `AudioMeter` sees it.
- **Concrete failure scenario:** A sustained input whose post-gain peak sits around 95.0% full
  scale should make TalkBack say “near clipping,” as the new contract and direct reducer test state.
  In production it is announced only as “high” until the signal exceeds about 95.117%, so the
  accessibility warning boundary is not the one the code/tests claim.
- **Suggested fix:** Classify raw peaks into threshold-preserving overload categories before RMS
  quantization, or introduce a peak quantizer whose buckets cannot cross either 0.95 or
  `32767/32768`. Feed those categories to accessibility and keep RMS quantization solely for bar
  geometry. Add an end-to-end reducer/ViewModel test covering values immediately below, exactly at,
  and immediately above both peak thresholds after the actual production transformation.

## Final causal sweep

- The cycle-34 dual-open change was traced through signal, supersession, timeout, candidate close,
  outgoing exact-release proof, deferred-session start, and sequential fallback. Ignoring the enum
  return is not itself a defect because post-wait ownership and `deviceOk` branches own the terminal
  decision and cleanup.
- Camera Ready, tap-AF, custom-WB, capture-id presentation, recorder finalization, ownerless system
  delete, retained still deletion, and launch-recovery callbacks retain exact generation/identity
  rechecks at their final publication threads.
- Family publication/retirement and DISCARD authority retain per-key serialization without holding
  process-wide registry/database locks across provider I/O; no new ABBA or silent late-publication
  path survived inspection.
- Preview, encoder, and analysis failure attribution still follows exact output ownership; a preview
  restore failure after a successful encoder swap is not misclassified as recorder failure.
- No additional confirmed race, stale rollback, wrong-clock correlation, or competing-hypothesis
  defect remained after the final missed-file sweep.

## Totals

- New findings: 1
- Severity: 1 Low
- Confidence: 1 High
