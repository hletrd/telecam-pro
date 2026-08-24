# Debugger review — cycle 36

Date: 2026-08-24
Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle36.TOpdQ8`

## Scope, complete inventory, and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` before the
review, then used the retained reviews and completed cycle-35 plan only to identify fixed regression
hypotheses. Findings were required to survive current-HEAD source and cross-file validation.

All 486 tracked paths were inventoried: 101 production Kotlin files, three debug Kotlin files, four
instrumented-test files, 218 JVM/Robolectric/Compose tests, 32 Python files, 68 Markdown documents,
38 configuration/resource/script files, 16 binary assets, and the remaining repository metadata,
licenses, and wrapper inputs. Every path was included in the type/no-follow and SHA-256 inventories;
all source/configuration/text paths were included in repository-wide risk and contract searches.

The causal debugger pass traced the full executable graph and its test/tool counterparts:

- camera inventory/selection, capability normalization, dual-open and sequential replacement,
  optics generations, callback dispatch, session fallback, request updates, capture correlation,
  pseudo-ZSL, watchdogs, pause/release, and terminal native quarantine;
- GL/EGL input and output generations, frame coalescing, analysis buffers, motion history, preview
  replacement, encoder attachment, and exact resource retirement;
- processed still, HEIF/JPEG/DNG, snapshot budgets, family-producer leases, recording allocation,
  standby microphone handoff, audio read/gain/peak publication, codec/muxer drain/finalization, and
  storage publication/discard terminals;
- capture-family tracking, tombstone reservation, durable deletion, exact-family retirement,
  ownerless system-consent lifecycle, launch recovery, provider timeout/overflow, and late outputs;
- Activity/ViewModel permission and lifecycle edges, settings restoration, optics recall, modal and
  hardware-key ownership, timers/tickers, review decoding/playback, EXIF transforms, and heavy-work
  lane replacement;
- immutable debug/release builders, dependency/source/output evidence, release checker, device-test
  parser/retry/report paths, subprocess and filesystem boundaries, and documentation gates.

The final cycle-35 changes were re-traced under competing interleavings rather than accepted from
their tests: candidate callback-clear versus transaction supersession, vacant/outgoing/newer shared
camera slots, still-capacity refusal, exact PCM threshold boundaries, all eight EXIF orientations,
and completed-plan date/numeric-cycle ordering.

## Verification evidence

- Forty-two focused current-HEAD tests passed with zero failures or errors. The selected suite covers
  `DualOpenWaitTest`, `StillCaptureAdmissionStatusTest`, `AudioGainTest`,
  `ReviewExifOrientationTest`, `DebugCameraControlSecurityTest`, and both ownerless-media delete
  lifecycle/operation suites.
- The complete cycle-35 delta and current production call sites were checked against prior review
  findings. Candidate self-removal no longer loses the outgoing controller; a genuinely newer
  non-null controller prevents stale restoration and causes exact outgoing release.
- Audio display state retains quantized RMS only for geometry while classifying raw held peaks before
  lossy representation; channel-count equality is enforced at `AudioLevelFrame` construction.
- EXIF transformation is performed only after bounded decode, covers all mirrored/rotated standard
  orientations, and has asymmetric-pixel/dimension tests. Failure retains the original usable decode.
- The worktree was clean before report creation. No source, plan, deployment, or device state was
  changed by this review.

## Findings

No actionable latent bug, correctness regression, resource leak, race, or failure-mode defect
survives current-HEAD validation.

The open manual checks in `docs/FIELD_CHECKS.md` remain explicit device-evidence obligations rather
than inferred failures or successes; this review performed no device action.

## Final missed-issue sweep and coverage confirmation

The final sweep revisited every native handle terminal, thread/queue owner, callback-generation
boundary, provider mutation, deletion/recovery state machine, bitmap/media parser bound, settings
corruption seam, Activity/ViewModel lifecycle edge, and build/device evidence boundary. It then
checked all review-relevant files against current tests and the previously fixed issue ledger. No
new reproducible or legally interleavable defect remained, no source/configuration file was sampled
instead of inventoried, and no review-relevant tracked path was skipped.

**New finding count: 0.**
