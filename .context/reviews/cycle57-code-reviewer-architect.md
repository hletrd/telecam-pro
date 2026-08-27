# Cycle 57 code-reviewer and architect review

Reviewed revision: `b44d5fce43b9a4910143133b6e6e280559704763`

## Provenance and method

This lane worked only in the cycle-57 isolated clone. It did not edit production source, use a
device, deploy, read credentials, or touch the shared main worktree. `CLAUDE.md` was read completely
before the complete `docs/ARCHITECTURE.md` and `docs/FIELD_CHECKS.md` authorities.

The tracked inventory contains 575 files. The code/architecture surface covered all 123 production
members under `app/src/main` (105 Kotlin, one Java, manifests/resources/assets), all four debug
members, all four instrumented tests, all 249 JVM-test resources, 27 tool files, 14 device-harness
files, root Gradle/build/privacy/release configuration, and the current documentation/review/plan
authorities. Every production package (`camera`, `capture`, `focus`, `gl`, `stab`, `storage`, `ui`,
`ui/controls`, `ui/overlays`, `ui/review`, and `video`) was included in the inventory and cross-file
searches. The deeper control-flow pass followed Camera2/GL lifecycle ownership, optics and Ready
transactions, still-family creation/publication/deletion/recovery, recording/native teardown,
process-finite owners and Engine replacement, callback-to-StateFlow publication, release signing,
and the tests/tooling that claim those contracts. Historical review text was used only to identify
areas requiring current-HEAD verification; its findings were not recycled.

## Code Reviewer

### CR57-01 — the “strong-key” gate accepts trivially guessable replacement passwords

- **Severity / confidence:** Medium / High; confirmed with the current parser, not dependent on a
  live credential.
- **Region:** `tools/run_scoped_signed_release.py:49-72`, especially the predicate at `:66-67`;
  `tools/tests/test_scoped_signed_release.py:24-45`.
- **Evidence:** the policy rejects values shorter than 16 characters and the one exact shape
  `\d{6}`. It accepts both `0000000000000000` and `aaaaaaaaaaaaaaaa` as satisfying the
  “strong-key policy”; a direct invocation of `parse_scoped_credentials` confirmed both return
  successfully. The test matrix rejects only the historical six-digit form and does not exercise
  low-entropy values at the accepted length.
- **Concrete failure:** after the explicitly required upload-key rotation/reset, an operator can
  choose a repeated-character or all-numeric 16-character password, see the helper accept it as
  strong, and produce a release. Theft of the JKS then again permits cheap offline guessing, so the
  new fail-closed workflow does not actually enforce the security property introduced to replace
  the exposed six-digit password.
- **Suggested fix:** define an enforceable generated-secret contract rather than special-casing one
  known password shape—for example require a sufficiently long randomly generated value from an
  approved generator and reject all-one-class/repeated/common-pattern values—or stop claiming that
  the helper validates strength and make the owner-approved fingerprint the only machine-verifiable
  prerequisite. Add mutation cases for all-numeric, repeated-character, sequential, and whitespace-
  altered inputs. Rotation/reset remains an external destructive action and was not attempted.

No other correctness finding survived validation in this role. In particular, the cycle-56 DNG
publication transfer retains mixed-output ordering, every accepted process tail settles its family
continuation, pending-identity retry remains non-destructive until exact identity or authoritative
absence, and StartupTrace now carries an Engine-owned token rather than sampling global ownership at
controller wiring.

## Architect

### AR57-01 — the combined still-admission cache is bypassed by local-owner publications

- **Severity / confidence:** Medium / High; confirmed cross-file state-machine defect. Occurrence
  needs an Engine-overlap/provider-failure interleaving, but the stale-state path is deterministic
  once those documented conditions occur.
- **Regions:** `CameraEngine.kt:205-245` (`lastProcessStillAdmission` and
  `publishProcessStillAdmission`), local retained-family publications at `:302-305`, `:5100-5103`,
  and `:5138-5153`, the combined projection at `:5118-5123`, the capture-time defensive refusal at
  `:4749-4754`, and the UI consumer at `CameraViewModel.kt:1180-1184`.
- **Evidence:** `lastProcessStillAdmission` change-gates the value computed from all three owners:
  process DNG, Engine-local retained-family capacity, and process storage. Only callbacks arriving
  through the two process subscriptions update that cache. Local retained-family transitions call
  `onStillCaptureAdmissionChanged` directly, so they can change the UI without changing the cached
  “last” value. The existing `ProcessStillAdmissionEngineTest` covers a process DNG edge across
  Engine replacement, but never interleaves a local false/true transition with a foreign process
  edge.
- **Concrete failure:** let a replacement Engine's retained-family owner close admission, then let
  a still-live old Engine/process tail move a process owner so the replacement caches combined
  `false`. If retained-family retirement next reopens the replacement locally, the direct path
  publishes `true` to its ViewModel but leaves the cache `false`. A later old-Engine rejected-output
  reservation or DNG acquisition makes the real combined value `false`; the subscription recomputes
  `false`, equals the stale cache, and suppresses the only callback. The replacement UI therefore
  leaves the shutter enabled. `capturePhoto` still refuses at execution time, so this presents as an
  enabled shutter that answers with “finishing previous photo” rather than admitting the shot.
- **Suggested fix:** make one Engine-local admission publisher own both change-gating and callback
  delivery, and route every local and process edge through it; no direct callback site may bypass
  its delivered-state record. Prefer a small projection owner that records the individual DNG,
  retained-family, and storage states plus a monotonic publication sequence, rather than caching the
  result of unsynchronized pull reads. Add a deterministic test with local close/reopen around a
  foreign process false edge, plus callback replacement/detach coverage.

This finding is also the architectural consequence of splitting one logical projection across a
process signal and numerous direct Engine callback sites. The process owners themselves are finite,
and their listener close/drain ordering is sound; the fault is the second, bypassable cache of their
combined result.

## Verification and final missed-issue sweep

- `python3 tools/check_docs.py`: 158 checks passed, zero failed, 24 declared optional-private skips.
- `python3 -m unittest tools.tests.test_scoped_signed_release -v`: 4/4 passed. The accepted weak
  examples above demonstrate a missing policy assertion, not a currently failing test.
- Focused Gradle tests for `ProcessAdmissionSignalTest`, `ProcessStillAdmissionEngineTest`,
  `StillPublicationDispatcherTest`, and `StartupTraceTest` passed with the documented JDK and
  explicit conventional Android SDK path.
- Final sweeps covered TODO/disabled-test markers, uncaught/ignored failure paths, executor and
  callback ownership, direct `Log` inventory, hardcoded user-facing literals and locale parity,
  exported components/backup policy, model-string seams, production-to-test mapping, and current
  cycle-56 diffs. No additional finding survived source-level validation.

Open field checks A3/A4/A5/D1/E1/E2/E3 remain correctly labeled manual evidence obligations. No
host result here is represented as camera, optical, acoustic, HDR-display, or real-MediaProvider
evidence.
