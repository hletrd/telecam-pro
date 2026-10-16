# Aggregated deep review — cycle 34

Date: 2026-08-24
Reviewed revision: `56602a2dc38a17712bbc10760b74f31262ca87cb` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-cycle34.EZe8ao`

## Coverage and aggregation

Five parallel specialist groups plus the cycle agent covered all required roles: code-reviewer,
architect, performance, tracer, security, critic, verifier, debugger, test engineer, document
specialist, and native Android designer. No repository-local reviewer definitions were present.
Every group inventoried the complete 469–470-path clean-clone surface and read the committed project
authorities. UI review used Compose/resource/semantics evidence because this is a native app, not a
web app. No device-only behavior was inferred.

The 19 raw specialist findings deduplicate to 11 current findings. The clean-host coverage failure
had the strongest cross-agent agreement (seven specialist roles); the stale dual-open wait and audio
meter mismatch were each independently confirmed twice. Highest reported severity is preserved.
All findings have High confidence.

## Findings

### AGG34-01 — authoritative clean-host coverage gate is red

- **Severity / confidence:** High / High
- **Sources:** code-reviewer, architect, tracer, verifier, debugger, test-engineer,
  document-specialist (**broad cross-agent agreement**)
- **Evidence:** `tools/verify_host.py:68-83`, `app/build.gradle.kts:643-669`, and
  `tools/coverage/partition-a-residuals.txt:1-12`. Fresh regenerated reports pass tests and measure
  Partition A at 99.69%, then fail exact residual ownership for
  `FamilyDeletionMarkerCapacityOwner` (1 line), `CameraScreenPolicyKt` (2),
  `OwnerlessMediaDeleteOverrides` (2), and `ReviewStillGeometry` (7), in
  `camera/FamilyDeletionMarkerDispatcher.kt:178-182`, `ui/CameraScreenPolicy.kt:73-77`,
  `ui/CameraViewModel.kt:132-143`, and `ui/review/MediaReview.kt:1853-1958`.
- **Failure:** the documented authoritative command exits 1 and never reaches later Python,
  harness, and documentation phases. Ordinary assemble/test/lint can misleadingly remain green.
- **Plan direction:** exercise every reachable branch, classify only genuinely framework-bound or
  proven-unreachable residuals with exact rationales, retain the threshold/fail-closed checker, and
  prove the full host gate from regenerated outputs.

### AGG34-02 — lens-preservation migration default contradicts live/default authority

- **Severity / confidence:** Medium / High
- **Sources:** code-reviewer, architect (**cross-agent agreement**)
- **Evidence:** `storage/SettingsStore.kt:40-44,103-107,215-354` defaults
  `ExtraSettings.preserveLensSelection` false, while `camera/CameraState.kt:1460-1465`,
  `ui/CameraViewModel.kt:1185-1192,1456-1462`, `CLAUDE.md:717-721`, and
  `docs/ARCHITECTURE.md:112,1168-1174` make the policy default-on.
- **Failure:** an older saved blob lacking the key resets its remembered lens to MAIN, publishes the
  toggle off, and persists that unintended migration policy, unlike a clean install.
- **Plan direction:** make one shared default-on policy authority and test no-blob, legacy-blob, and
  current-blob parity with a non-main saved lens.

### AGG34-03 — superseded camera switch blocks on the stale dual-open wait

- **Severity / confidence:** Medium / High
- **Sources:** performance, tracer (**cross-agent agreement**)
- **Evidence:** the sole setup lane begins dual-open and invalidates accepted readiness at
  `camera/CameraEngine.kt:3529-3688`, then unconditionally blocks on a private two-second latch at
  `CameraEngine.kt:3703-3705`; generation/lifecycle rejection occurs only afterward at lines
  3706-3744. A newer optics transaction can enqueue but cannot wake that latch.
- **Failure:** a slow/silent stale open holds the only setup worker and Not-Ready UI for the full
  timeout before the latest user-selected lens/mode can begin.
- **Plan direction:** add a transaction-owned supersession/pause/release terminal to the wait while
  preserving the absolute HAL deadline and exact late-callback cleanup; deterministically test a
  silent candidate A superseded by B.

### AGG34-04 — accessible audio meter claims clipping from RMS-only input

- **Severity / confidence:** Medium / High
- **Sources:** verifier, debugger (**cross-agent agreement**)
- **Evidence:** standby and recording producers publish RMS only at `video/AudioLevels.kt:9-43` and
  `video/VideoRecorder.kt:2038-2065`; `ui/overlays/Overlays.kt:605-669` calls RMS `>=0.999`
  “clipping.” Scalar-only tests do not cover the producer/consumer contract.
- **Failure:** ordinary clipped speech or a full-scale sine contains saturated samples but RMS far
  below 0.999, so TalkBack reports only high/near-clipping and misses the advertised terminal.
- **Plan direction:** carry per-channel peak/overload evidence alongside RMS, hold it at the coarse
  accessibility cadence, and test real interleaved PCM fixtures across standby and recording.

### AGG34-05 — seven accessible still-review positions lack an oracle

- **Severity / confidence:** Medium / High
- **Source:** test-engineer
- **Evidence:** the nine-way classifier at `ui/review/MediaReview.kt:1915-1938` is tested only for
  CENTER and TOP_LEFT by `MediaReviewGestureTest.kt:121-152` and
  `MediaReviewNonTouchComposeTest.kt:47-91`; five classifier lines remain uncovered.
- **Failure:** a sign or label-wiring regression can announce the wrong right/down/corner position
  to TalkBack or Switch Access while existing pointer and two-position tests pass.
- **Plan direction:** table-test all nine states and transition boundaries, then assert Compose
  state descriptions after navigation in all four directions, including EN/KO and RTL-independent
  image geometry.

### AGG34-06 — transitive debug activities are exported without protection

- **Severity / confidence:** Low / High
- **Source:** security-reviewer
- **Evidence:** debug dependencies at `app/build.gradle.kts:726-743` merge exported, unprotected
  `androidx.compose.ui.tooling.PreviewActivity` and `androidx.activity.ComponentActivity`; the
  repository-owned debug activities are DUMP-protected in `app/src/debug/AndroidManifest.xml:5-26`.
- **Failure:** another app can launch a reflective preview or blank test host under the debug app's
  identity and interrupt/confuse device evidence sessions. Release is unaffected.
- **Plan direction:** DUMP-protect PreviewActivity, make the test ComponentActivity non-exported,
  and assert every merged-debug exported component is protected.

### AGG34-07 — AGP 9.3.1 is behind stable 9.3.2

- **Severity / confidence:** Low / High
- **Source:** critic
- **Evidence:** `gradle/libs.versions.toml:2-8` and `CLAUDE.md` pin 9.3.1; Google Maven metadata on
  2026-08-24 lists 9.3.2 as the newest stable patch. Dependency verification is fail-closed.
- **Failure:** the repository violates its latest-stable toolchain policy and misses patch fixes;
  a catalog-only bump would fail verified dependency resolution.
- **Plan direction:** bump AGP and its verified artifacts to 9.3.2, update version authorities, and
  run complete debug/release gates.

### AGG34-08 — unused experimental aggregation flag warns on every Gradle run

- **Severity / confidence:** Low / High
- **Source:** critic
- **Evidence:** `gradle.properties:25-29` enables
  `android.experimental.reportAggregationSupport=true`, but no unified aggregation task is consumed;
  current coverage uses the per-debug-unit path. AGP warns on every invocation.
- **Failure:** permanent noise normalizes the warning channel and hides future actionable diagnostics.
- **Plan direction:** remove the unused opt-in and obsolete comment; do not suppress the warning.

### AGG34-09 — ownerless-delete queue fixture emits a compiler warning

- **Severity / confidence:** Low / High
- **Sources:** verifier, debugger, test-engineer
- **Evidence:** `ui/OwnerlessMediaDeleteLifecycleTest.kt:121-124` uses
  `dispatcher.dispatch(Runnable { Unit })`; forced test compilation reports “Expression is unused.”
- **Failure:** a known warning dilutes new compiler diagnostics and violates the warning-free gate
  contract.
- **Plan direction:** use an empty runnable (or a meaningful synchronization effect) and force
  recompilation to prove warning-free output.

### AGG34-10 — debug preview comment states release minification is off

- **Severity / confidence:** Low / High
- **Source:** critic
- **Evidence:** `app/src/debug/kotlin/me/hletrd/findx9tele/ui/CameraScreenPreview.kt:27-30` says release
  has `isMinifyEnabled = false`; `app/build.gradle.kts:557-569` enables full-mode R8 and all current
  authorities agree.
- **Failure:** maintainers receive contradictory guidance when deciding whether helpers survive or
  belong in production source.
- **Plan direction:** correct the comment without changing runtime behavior and extend the source/doc
  check to reject affirmative stale release-minification claims.

### AGG34-11 — latest completion evidence claims a green authoritative gate that is red

- **Severity / confidence:** Medium / High
- **Source:** document-specialist
- **Evidence:** `docs/plans/2026-08-24-rpf-cycle33.md:114-123,142-150` claims configured gates and
  warnings green, while the current authoritative command named by `CLAUDE.md:83-94` and
  `docs/ARCHITECTURE.md:1259-1270` fails as AGG34-01 and compilation warns as AGG34-09.
- **Failure:** a maintainer can trust a narrower recorded Gradle trio and advance toward release
  without running the repository-wide verifier.
- **Plan direction:** after fixing the gate, append a truthful superseding cycle-34 evidence note
  without erasing historical provenance, and enforce that repository-wide green claims name
  `tools/verify_host.py`.

## Agent failures

None. Every available reviewer returned and wrote its provenance report.

## Totals

- Raw specialist findings: 19
- Deduplicated current findings: 11
- Severity: 1 High, 5 Medium, 5 Low
- Confidence: 11 High
- Deferred findings: none at review stage; Prompt 2 must schedule every item.
