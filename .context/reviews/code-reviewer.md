# Code review — cycle 50

Date: 2026-08-25

Reviewed revision: `2388819d981d32bc3c59b3e81f75fd4f49fab8bd` (`origin/main`)

Workspace: isolated clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`

## Inventory first

The closed tracked inventory is 535 paths. I inventoried and examined all 103 production Kotlin/Java
modules (55,212 lines): the five app-root modules; all 35 `camera/` modules; all five `capture/`, two
`focus/`, eleven `gl/`, one `stab/`, six `storage/`, seven `video/`, seventeen root `ui/`, nine
`ui/controls/`, one `ui/overlays/`, three `ui/review/`, and one `ui/theme/` modules. The exact source
inventory is the complete set under `app/src/main/{kotlin,java}/me/hletrd/telecampro/**`; the
architecture module-map gate independently confirms that none is omitted.

The remaining review inventory was also closed rather than sampled: all 237 JVM/Robolectric/Compose
tests, four instrumented tests, three debug hosts, both manifests, all 15 main resource paths,
baseline profile, Compose stability and R8 rules, Gradle/version/dependency-verification/wrapper
inputs, all 25 `tools/**` and 14 `device-tests/**` paths, their tests, privacy/Play/store assets and
manifests, the complete current authority (`CLAUDE.md`, `docs/ARCHITECTURE.md`, and
`docs/FIELD_CHECKS.md`), 42 historical plans, and 44 prior review-provenance paths. Binary assets and
historical reports were checked for their executable/provenance contracts, not treated as source
code.

I traced every cross-file runtime path: Activity permission/input/lifecycle ownership; ViewModel
state, settings/MR restore, optimistic publication, and rollback; Engine optics/session/pipeline
generations; CameraController capability/session/capture truth; GL generations and analysis;
processed/RAW capture; recorder admission/native finalization; exact-family MediaStore durability,
delete, and recovery; review loading/focus; and Compose action admission. Comments, tests, completed
plans, and previous reviews were treated as claims and rechecked against production callers.

## Finding

### C50-CR-01 — a Photo-mode pipeline command can be overwritten in UI by the older rollback it superseded

- **Severity / confidence:** High / High.
- **Classification:** Confirmed concurrency/state-ownership defect. The interleaving is deterministic
  from the production monitors and main-queue hop; an actual failed Camera2 reconfiguration remains
  device-dependent.
- **Exact evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:765-843` restores the complete
    codec/candidate/requested-transfer tuple under the Engine monitor, then publishes an
    `OpticsRollbackPublication` tagged only with the unchanged optics generation.
  - `CameraEngine.kt:2511-2556` now serializes `setVideoPipeline` on that monitor, but when the
    restored mode is Photo, `activeTransfer` remains SDR and `tenBitChanged` is false. The command
    takes the direct `publish()` branch and advances no optics or pipeline publication generation.
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:911-959` posts rollback to main,
    accepts it solely through `engine.isOpticsGenerationCurrent(rollback.generation)`, then rewrites
    `transfer` and `videoCodec` and schedules persistence. Because the newer Photo-mode pipeline
    command did not advance that generation, the older rollback is still accepted.
  - The new race test at
    `app/src/test/kotlin/me/hletrd/telecampro/ui/ModeRollbackOwnershipRobolectricTest.kt:52-88`
    deliberately queues a pipeline command behind Photo rollback, but uses the same HEVC/HLG tuple,
    asserts only Engine fields, and never drains/asserts the already-posted ViewModel rollback after
    the pipeline thread completes. It therefore cannot expose the Engine/UI split.
  - The convenience reads at `CameraEngine.kt:2503-2505,2909-2920` additionally snapshot current
    codec/candidates/requested transfer before entering the synchronized method, so callers using
    those entry points can carry a rejected tuple across the same boundary.
- **Concrete failure scenario:** Photo is accepted while its retained next-Video selection is
  HEVC/HLG. The operator enters Video and Camera2 rejects that mode/session reconfiguration. The
  setup thread restores Photo + HEVC/HLG and posts its rollback to main. While the main thread is
  already processing a codec tap ahead of that post, `setVideoPipeline(AVC, SDR)` blocks on the
  Engine monitor, then resumes after rollback in Photo mode and publishes AVC/SDR without a
  generation change. The input handler updates the UI, returns, and the queued older rollback still
  passes the optics-generation check; it rewrites UI and persisted settings to HEVC/HLG while Engine
  retains AVC/SDR. The next Video entry then supplies the visible HLG request to an Engine still
  holding AVC candidates, so production REC admission can report the codec unavailable (or later
  code can consume a codec/profile tuple different from the visible choice).
- **Suggested fix:** give video-pipeline intent/publication its own monotonic ownership token (or an
  equivalent complete Engine-to-UI packet) even when Photo's active SDR Camera2 boundary does not
  require a reopen. Include that token in rollback and reject a rollback reducer superseded by a
  later pipeline command. Move convenience-wrapper reads inside the same monitored decision.
  Extend the deterministic test to change codec/candidates, let the ViewModel rollback queue drain,
  and assert Engine tuple, UI tuple, persistence input, and production `recordingEncoderAdmission`
  all remain either wholly old or wholly new.

## Verification, limits, and final missed-issue sweep

- `:app:testDebugUnitTest` resolved the current cached task outputs: 2,103 tests, zero
  failures/errors/skips. The green suite validates the current assertions but not the missing
  post-command rollback-queue drain described above.
- `python3 tools/check_docs.py` passed 152 checks with 24 declared clean-clone private-file skips;
  `git diff --check` passed before other parallel review reports began writing.
- The Python tool suite ran 130 tests: 123 passed and seven were environment-blocked because the
  installed Android Emulator lacks `glslangValidator`. All nine coverage-tool tests and all 195
  device-harness self-tests passed. This environment limit is not a source failure.
- I re-swept every mutable cross-thread field, monitor/atomic owner, executor and shutdown edge,
  ignored failure, non-null assertion, capability/model seam, requested-vs-accepted state,
  persistence/localization/build boundary, and every cycle-49 production/test change. No second
  correctness or maintainability finding survived competing-hypothesis checking.
- Existing broad `CameraEngine` decomposition debt remains the explicitly deferred AGG35-08 record;
  it is not duplicated here. Manual field checks A3/A4/A5/D1/E1/E2 remain manual validation, not
  host-confirmed defects.

---

## Archived prior review

# Code review — cycle 49

Date: 2026-08-25

Reviewed revision: `69c9c64ac778341189be9dbee5621601b1353a27` (`origin/main`)

Workspace: isolated clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`

## Scope and coverage

I read `CLAUDE.md` first, followed by the complete current `docs/ARCHITECTURE.md` and
`docs/FIELD_CHECKS.md`. I inventoried all 534 tracked paths: all 103 production Kotlin/Java modules,
237 JVM/Robolectric/Compose tests, four instrumented tests, manifests/resources/build inputs, host
tools, device harness, current documentation, historical plans, and prior review provenance. The
source pass covered every production declaration/import and its test references, then traced the
Activity input/lifecycle boundary, ViewModel reducers, CameraEngine optics/session generations,
CameraController request/session truth, GL generation/bindings, still/video/storage ownership, and
Compose focus/input paths. Tests and completion notes were treated as claims, not proof.

## Finding

### C49-CR-01 — held activation keys retrigger viewfinder autofocus on every repeat DOWN

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed input-policy defect. The production handler executes every
  `KeyDown`; physical repeat timing remains device-dependent.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraScreen.kt:364-385` maps Enter,
  Numpad Enter, Space, and DPAD-center directly to `onFocusAtCenter()` whenever the event type is
  `KeyDown`, with no repeat-count gate and no pressed-key owner. Android hardware long-press emits
  repeated DOWN events. `app/src/test/kotlin/me/hletrd/telecampro/ui/ViewfinderAccessibilityComposeTest.kt:171-215`
  uses `pressKey`, which supplies one down/up pair per activation and never exercises repeated DOWNs.
- **Failure scenario:** a keyboard or remote user rests on Enter/Space/DPAD-center. The viewfinder
  reissues identical tap-focus commands at the platform repeat cadence, repeatedly restarting or
  superseding Camera2 AF work instead of performing the single button-like activation the user
  expects. The reset branch is mostly idempotent, but it is governed by the same unbounded edge.
- **Concrete fix:** accept only the initial DOWN (`nativeKeyEvent.repeatCount == 0`) or own the key
  from first DOWN through UP and fire once. Add production modifier tests for initial DOWN,
  repeated DOWNs, matching UP, cancellation/focus loss, and a fresh second press for every mapped
  key family.

## Verification and limits

- Focused `ViewfinderAccessibilityComposeTest` and `ModalFocusComposeTest` passed; their green result
  confirms the missing repeat case is outside current coverage.
- `python3 tools/check_docs.py` passed 151 checks with 24 declared private-file skips.
- No device, camera, keyboard/remote, TalkBack service, or MediaProvider flow was run. Field checks
  A3/A4/A5/D1/E1/E2 remain manual risks, not findings from this review.

## Final missed-issue sweep

No second code-quality or invariant failure survived cross-file checking. In particular, the new
video codec/candidate/requested-transfer/active-transfer packet restores all four Engine fields on
optics rollback, and REC admission filters candidates against the current transfer. The broad
CameraEngine size remains previously recorded debt rather than a new concrete defect.

---

## Archived prior review

# Code review — cycle 39

Date: 2026-08-24
Reviewed revision: `5ee6b21` (`origin/main`)
Workspace: `/private/tmp/find-x9-cycle39.feeBBZ`

## Coverage

Inventoried all 493 tracked paths: 101 production Kotlin files, 224 JVM/Robolectric/Compose and
instrumented Kotlin tests, 32 Python files across build/release tooling and the device harness,
Android manifests/resources, Gradle/version/signing configuration, shell utilities, and the
committed documentation/review/plan corpus. I read the complete `CLAUDE.md`, current
`docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md`; the optional private `docs/BACKLOG.md`,
`docs/TESTING.md`, and `docs/UX_POLICY.md` are absent as permitted by the clean-clone policy.

The review followed the complete request path from `MainActivity` and `CameraViewModel` through
`CameraEngine`, route/capability normalization, `CameraController`, GL generation ownership,
processed/RAW capture, video admission/finalization, MediaStore durability/recovery/deletion, and
review publication. It also checked settings/MR restore, front/rear/external route behavior,
PMA110-only `DeviceProfile` gates, renderer-state replay, lifecycle teardown, process-finite worker
owners, permission fallbacks, localization/resource usage, and immutable debug/release evidence.
Tests and documentation were treated as claims to verify against source rather than as proof by
themselves. Historical reviews were used only as leads; previously fixed or explicitly deferred
items were not refiled.

## Findings

No new actionable code-quality, correctness, or maintainability finding survived evidence checking
at the reviewed revision.

The cycle-38 changes were examined specifically. The stabilization-label fast path compares the
resolved Camera2 value before suppressing reconfiguration and still stores the normalized intent;
all real HAL-mode transitions retain request/session reconfiguration. The selected-disabled focal
chip retains the shared live-frame contrast foundation, the latest-work capacity tests now establish
the required start ordering, and the removed finder-margin seam has no remaining production caller.

## Verification and final missed-issue sweep

- `python3 tools/check_docs.py` completed 120 checks with zero failures (24 clean-clone private-file
  checks skipped), including production-module inventory and the review-critical ownership map.
- `git diff --check` passed and the worktree was clean before these two review reports were written.
- I swept TODO/FIXME/suppression sites, ignored `runCatching` results, blocking and executor creation,
  callback publication, compare-and-set/monitor ownership, route/model branching, persistence
  normalization, accepted-session output truth, late media callbacks, native teardown/quarantine,
  manifest permissions/features, and build provenance boundaries.
- The known broad `CameraEngine` decomposition debt remains explicitly deferred in
  `docs/plans/2026-08-24-rpf-cycle35.md` with its original Medium/High classification and exit
  criterion. No new defect crossed enough responsibility regions to trigger that criterion.
- The remaining field checks in `docs/FIELD_CHECKS.md` require real scene/device/provider evidence;
  no host-only result was promoted to device proof.
