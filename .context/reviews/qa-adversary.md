# QA adversary review — cycle 36

Date: 2026-08-24

Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54` (`origin/main`)

Workspace: isolated worktree `/tmp/find-x9-cycle36.TOpdQ8`

Mode: host-only by directive; no install, launch, ADB, capture, deployment, or device mutation

## Gate-first result

The runbook's Gate 1 was run with JDK 21 and the repository-documented conventional SDK at
`/Users/hletrd/Library/Android/sdk`:

`./gradlew :app:assembleDebug :app:testDebugUnitTest`

Result: **PASS** — `BUILD SUCCESSFUL`, 52 actionable tasks (1 executed, 51 up-to-date). I also
searched production Kotlin for TODO/FIXME/unresolved-reference markers and found none. A first
attempt without SDK environment failed before compilation with “SDK location not found”; it was
not treated as product failure and the corrected isolated-worktree run is the result above.

`python3 tools/check_docs.py` additionally passed 112 checks with 24 optional-private skips.

## Static finding

### QA36-01 — the cycle-35 dual-open test matrix bypasses a production null-alias input

- **Severity / confidence / status:** High / High / Confirmed static correctness defect.
- **Exact regions:** production derivation at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3545,3580-3592,3667-3674,
  3746-3753,7037-7050`; oracle at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/DualOpenWaitTest.kt:101-136`.
- **Problem:** The tests call `dualOpenSupersessionCleanup` with manually exclusive flags. The real
  call computes them from nullable identities. With no outgoing controller and a candidate-cleared
  slot, `controller == null` and `controller === old` are both true because `old` is null, so the
  helper's exclusivity `require` throws. Every current host test remains green because none builds
  the flags from `(old, controller)`.
- **Concrete reproducer model:** `old=null`; install `next`; candidate refusal clears the shared
  slot; advance the optics generation; enter supersession. The production inputs become
  `(candidate=false, vacant=true, outgoing=true)`, which deterministically raises
  `IllegalArgumentException`.
- **Expected:** a total cleanup decision that leaves a clean vacant baseline and lets the newest
  optics intent proceed. **Observed from source:** assertion failure on the setup worker.
- **Suggested fix:** make the test own the same nullable identities as production and enumerate all
  combinations; compute outgoing ownership only for a non-null owner or replace the booleans with a
  typed owner terminal.

## Runbook reliability observation (not counted as a repository finding)

The external runbook command at
`/Users/hletrd/flash-shared/find-x9-ultra-camera/.claude/agents/qa-adversary.md:47-53` pipes Gradle
to `tail` without `pipefail`. The initial SDK failure therefore returned shell status 0 even though
Gradle printed `BUILD FAILED`. This review inspected output and retried correctly, so its verdict is
not affected. The runbook should eventually use `set -o pipefail`, capture `PIPESTATUS`, or avoid the
pipeline; the shared checkout was not modified as directed.

## Feature matrix

| Feature | Result | Evidence |
|---|---|---|
| Gate 1 — debug assembly + JVM/Robolectric/Compose unit tests | PASS | Host command completed `BUILD SUCCESSFUL` after documented SDK environment was supplied. |
| Static dual-open null-owner path | FAIL | Source-derived `(false, true, true)` reaches `require` in `dualOpenSupersessionCleanup`; current test matrix omits it. |
| Gate 2 — install, launch, PID, crash scan | BLOCKED BY DIRECTIVE | Task explicitly forbids deployment/device work; no current `ANDROID_SERIAL` was supplied or reused. |
| Mode-aware route selection / preview / Program exposure | BLOCKED BY DIRECTIVE | Requires current-device observation. |
| PASM, snapping, focus, tap-AF, format gating | BLOCKED BY DIRECTIVE | Requires current-device UI and Camera2 result evidence. |
| Photo files, whole-family delete, video/container/audio | BLOCKED BY DIRECTIVE | Requires disposable device capture and pulled-file inspection. |
| Stabilization, overlays, nine-tab settings, MR restore | BLOCKED BY DIRECTIVE | Requires device interaction and visual/metadata evidence. |
| Rapid route churn, lifecycle/keyguard, format floor, zoom caps, delete-during-save | BLOCKED BY DIRECTIVE | Gate 4 is device-only and was not attempted. |
| Field checks A3, A4, D1, E1, E2 | BLOCKED BY DIRECTIVE | They remain explicitly open/partial in `docs/FIELD_CHECKS.md`; host evidence cannot close them. |

## Final sweep and verdict

I inspected all 486 tracked paths, current plans/reviews, build/manifests/resources, production
subsystems, 326 Kotlin files, 32 Python files, tool/harness tests, and documentation/assets. The
cycle-35 audio overload and EXIF fixes are covered at their production representation boundaries;
the dual-open null-owner path is the one new QA gap. No device claim was recycled.

**GATE NOT PASSED — Gate 1 passes, but QA36-01 is a confirmed static failure and all device gates are BLOCKED BY DIRECTIVE.**

## Totals

- New repository findings: 1
- Severity: 1 High
- Confidence: 1 High
