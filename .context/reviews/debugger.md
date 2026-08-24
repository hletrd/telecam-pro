# Debugger review — cycle 37

Date: 2026-08-24
Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope, inventory, and method

I reviewed only the detached `origin/main` worktree. `CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md` were read as current authority. Retained reviews and the completed cycle-36
plan were used as regression hypotheses, not current findings.

All 489 tracked paths were inventoried: 101 production Kotlin files, three debug Kotlin files, four
instrumented-test files, 220 JVM/Robolectric/Compose tests, 32 Python files, 69 Markdown documents,
16 binary assets, and 44 other tracked inputs. The whole tracked tree was included in file-type,
Git-mode/no-symlink, digest, suspicious-token, concurrency, exception, and subprocess searches. The
causal pass then traced the executable cross-file graphs most likely to conceal latent failures:

- Camera2 inventory/selection, optics transactions, dual-open/sequential replacement, callback and
  teardown terminals, session fallback, request fast paths, capture correlation/ZSL, watchdogs,
  recovery, pause, and release;
- GL/EGL generations, frame coalescing, analysis/motion buffers, preview replacement, encoder
  attachment, and exact resource retirement;
- still snapshot budgets, HEIF/JPEG/DNG publication, capture-family leases, recording pre-native
  allocation, standby microphone handoff, codec/muxer finalization, and post-native storage;
- MediaStore restoration, tombstones, pending/discard journals, process retries, ownerless delete
  ActivityResult ownership, review decoding/playback, and provider timeout behavior;
- Activity/ViewModel permissions and lifecycle, settings restore, modal/hardware input ownership,
  timers/tickers, and immutable build/device-evidence tooling.

The cycle-36 dual-open fix received a separate interleaving review. I checked candidate self-clear,
vacant/current/outgoing/newer shared slots, null outgoing, terminal outgoing, callback-before-close,
and pause/newer-intent ownership. Terminality is published before the Engine callback, close also
revokes restoration, newer non-null owners are not overwritten, and an absent/terminal outgoing
controller converges to vacancy while its native owner is released. The new state remains coherent
under the setup executor and callback-lane ordering; no current-HEAD race survived.

## Verification evidence

- Six focused current-HEAD suites passed with zero failures: `DualOpenWaitTest`,
  `CameraControllerRestorabilityTest`, `DebugCameraControlSecurityTest`, both ownerless-media delete
  suites, and `ReviewExifOrientationTest`.
- Current source revalidation confirms the cycle-36 device harness rejects optimized Python at both
  its outer and fork/runpy child boundaries. The separate documentation-checker weakness is recorded
  once as SEC37-01 in `security-reviewer.md`; it is not duplicated here as a second debugger count.
- No tracked symlink or abnormal Git mode exists. No device or deployment action was taken. The
  first attempted focused Gradle invocation failed cleanly at SDK preflight because the isolated
  worktree has no `local.properties`; rerunning with the existing SDK path in `ANDROID_HOME` passed.
  This changed no tracked source or configuration.

## Findings

No additional actionable production bug, correctness regression, deadlock, race, resource leak, or
failure-mode defect survives current-HEAD validation in the debugger scope.

The five open items in `docs/FIELD_CHECKS.md` remain explicitly classified physical/device evidence
obligations. This host-only review did not infer either passes or failures for them.

## Final missed-issue sweep and count

The final sweep revisited every native terminal, queue/admission owner, callback-generation edge,
provider mutation and recovery state, deletion family, bitmap/media parser bound, settings-corruption
seam, Activity/ViewModel lifecycle transition, and build/device evidence boundary. It replayed the
complete cycle-36 implementation delta against its current callers and tests and rechecked older
resolved findings for regression. No review-relevant current path was skipped or sampled as a
substitute for the inventory.

**New debugger finding count: 0.** SEC37-01 is the one cross-scope tooling finding and is counted only
in the security report.
