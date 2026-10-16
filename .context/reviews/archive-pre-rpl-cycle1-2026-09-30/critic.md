# Critic review — cycle 50

> Superseded by the cycle 51 report appended below; retained to preserve prior-cycle provenance.

Date: 2026-08-25

Reviewed revision: `2388819d` (`origin/main`)

Workspace: isolated clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`

Role: critic; source review plus focused read-only host verification; no implementation or commit

## Inventory before review

I generated the inventory from all 535 tracked paths before examining behavior. The review did not
sample only the last commit:

| Surface | Paths | Treatment |
|---|---:|---|
| Production Kotlin/Java | 103 | Every module inventoried from the architecture map; Camera2, GL, capture, storage, video, and UI ownership boundaries traced |
| Main resources | 15 | Resource/localization/policy assets inventoried |
| Debug + instrumentation sources | 8 | Export/security and device-probe boundaries inventoried |
| JVM/Robolectric/Compose tests | 238 | Test intent and false-green seams compared with production call sites |
| Host/release tooling | 25 | SDK, immutable-build, artifact, docs, permissions, and coverage gates inventoried |
| Device harness | 14 | Attestation, ADB, media, selector, and case boundaries inventoried |
| Product/architecture/release docs and assets | 64 | Current authorities and field-evidence limits checked; historical plans/assets classified separately |
| Prior review provenance | 44 | Consulted only after the independent inventory to avoid re-reporting fixed findings |
| Build/root/remaining tracked files | 24 | Manifests, Gradle, wrapper, signing example, privacy/publication, license, and repository policy inventoried |

The Cycle 49 executable change surface was then examined completely: `CameraEngine.kt`,
`CameraState.kt`, `CameraScreen.kt`, `MediaReview.kt`, `tools/check_docs.py`, all six changed focused
test files, and `docs/play-console-submit.md`. Cross-file traces additionally covered
`CameraViewModel.kt`, `CameraActions.kt`, `MainActivity.kt`, `EncoderCaps.kt`, `VideoRecorder.kt`,
the optics commit/rollback gates, capture-family producer settlement, modal focus, the committed PNG
manifests, and the current architecture/field-check authorities.

## Finding

### C50-CV-01 — the “fully validate” PNG gate still accepts specification-invalid ancillary ordering

- **Severity / confidence:** Low / High
- **Classification:** Confirmed tooling false-green; current checked-in screenshots themselves pass
  and this is not evidence that a present asset is corrupt.
- **Exact region:** `tools/check_docs.py:111-185`, especially the generic ancillary branch at
  `tools/check_docs.py:180-185`; the mutation suite covers PLTE-specific ordering only at
  `tools/tests/test_tool_contracts.py:1270-1309`.
- **Evidence:** `png_metadata()` describes itself as fully validating the PNG, but after the first
  IDAT every unknown ancillary chunk merely sets `idat_ended = True` and remains accepted as long as
  no later IDAT appears. PNG ancillary chunks have chunk-specific placement rules; for example,
  `tRNS` must precede the first IDAT. I inserted a CRC-correct six-byte truecolor `tRNS` immediately
  before IEND in the current 1440x2880 phone screenshot. The direct production predicate returned
  `(1440, 2880, 8, 2)` instead of `None`. The same class also includes malformed/late `sRGB`, `iCCP`,
  `gAMA`, and related color-space chunks whose structure/order is not validated.
- **Concrete failure scenario:** A screenshot export or metadata-rewrite step emits a late or
  malformed color/transparency ancillary chunk, and the maintainer refreshes the manifest digest as
  required by the normal recapture workflow. Digest, dimensions, CRCs, decompressed raster length,
  and the current gate all pass, while a strict decoder/store ingestion path can reject or
  reinterpret the file. This reopens the release-tool false assurance the Cycle 49 PNG work claimed
  to close.
- **Suggested fix:** Prefer a deterministic production image decoder that performs a complete load,
  then independently enforce the exact dimensions/color contract. If keeping the parser, validate
  the chunk type grammar plus every admitted ancillary chunk's length, value, multiplicity, and
  ordering (or reject all nonessential ancillary chunks). Add digest-refreshed mutations for a
  post-IDAT `tRNS`, malformed/late `iCCP` and `sRGB`, and an invalid chunk-type reserved bit.

## Critic verification and final sweep

- Focused Android tests passed for the changed runtime surfaces:
  `CameraStateTest`, `ModeRollbackOwnershipRobolectricTest`,
  `ViewfinderAccessibilityComposeTest`, `ModalFocusComposeTest`, and
  `MainActivityTouchDispatchTest`.
- `python3 -m unittest tools.tests.test_tool_contracts` passed all 55 configured tests.
- `python3 tools/check_docs.py` passed 152 checks with 24 documented optional-private skips.
- `git diff --check` passed.
- The video-pipeline monitor now covers derivation through publication; the captured rollback packet
  restores codec, ordered candidates, requested transfer, active transfer, UI publication, and REC
  filtering consistently in the examined interleavings.
- Release capture tracing is inert before trace payload construction, and producer-lease/family
  settlement remains outside diagnostics on every terminal path examined.
- Delete-dialog Back/Cancel share one dismissal owner and restore the exact Delete focus requester;
  outside dismissal reaches that same callback. Viewfinder focus-key repeats do not re-run the
  action. No second runtime defect survived the cross-file ownership sweep.
- No device, Camera2 HAL, converter, HDR display, off-axis audio scene, or real MediaProvider consent
  surface was available. Open field checks A3/A4/A5/D1/E1/E2 remain manual and were not promoted.

---

# Critic review — cycle 51 (current)

Date: 2026-08-25

HEAD: `7eb4ee95`
Workspace: isolated clone `/tmp/find-x9-ultra-cycle51.WTu2dW`; shared main untouched. No source implementation, commit, push, deploy, or device action.

## Complete inventory and coverage

The exhaustive tracked inventory was built with `rg --files` and classified without sampling: 634 non-build files total; `app/src/main` 120 (103 Kotlin/Java plus manifest/resources/assets), `app/src/test` 240 (239 Kotlin), `app/src/androidTest` 4, `app/src/debug` 4, `tools` 47, `device-tests` 27, and `docs` 65. Root authorities/configuration reviewed were `CLAUDE.md`, `README.md`, `PRIVACY.md`, `NOTICE`, `LICENSE`, both Gradle scripts, version catalog/wrapper/verification metadata, manifests, backup/data-extraction rules, and both locale resource sets. All production packages (`camera`, `capture`, `focus`, `gl`, `stab`, `storage`, `ui`, `ui/controls`, `ui/overlays`, `ui/review`, `video`) were traced against their same-package tests and tooling contracts. Every committed phone/tablet screenshot and Play bitmap was visually inspected; manifests/digests and all docs were checked by `tools/check_docs.py` (153/153 checks passed, 24 explicitly optional-private skips).

Verification evidence: Python coverage-tool tests passed 9/9 and device-harness self-tests passed 195/195. The tooling suite ran 132 tests with 7 environment-only errors because the selected SDK lacks Emulator `glslangValidator`; `verify_host.py` failed at the same explicit preflight. Direct Gradle reached all 2,112 JVM/Robolectric/Compose tests and exposed the confirmed race below (one failure); the exact failing test then passed 5/5 in isolated reruns, consistent with its ordering flaw. No hardware/manual claim was promoted.

## Findings

### C51-CV-01 — rollback preserves a newer video packet but restores the old GL transfer

- Location: `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt`, `rollbackOptics`, around lines 821–839.
- Severity: Medium. Confidence: High. Classification: **confirmed (source-path)**.
- Evidence: the generation mismatch branch correctly keeps `currentVideoPipelineSelection()`, but the next statement unconditionally calls `gl.setTransfer(before.transfer)` rather than `restoredVideoPipeline.activeTransfer`.
- Failure scenario: accepted Video/HLG begins a Video→Photo transition; while its reopen is pending, the operator selects AVC/SDR in the Video settings; then the Photo transition fails. Rollback restores Video and preserves the newer AVC/SDR Engine/UI packet, but reprograms GL to the old HLG transfer. REC admission and container tagging can therefore say AVC/SDR while the GL encoder draw still applies HLG. The cycle-50 interleave test runs the pipeline command *after* rollback returns and never exercises the generation-mismatch branch before rollback.
- Suggested fix: restore GL from `restoredVideoPipeline.activeTransfer`; add a latch-controlled test where the newer disjoint packet publishes before `rollbackOptics`, then assert Engine fields, GL transfer, UI callback, and REC admission all retain the same packet.

### C51-CV-02 — authoritative JVM gate contains a completion/capacity race

- Location: `app/src/test/kotlin/me/hletrd/telecampro/ui/FamilyDeletionMarkerIntegrationRobolectricTest.kt:167-176`; production ordering in `camera/FamilyDeletionMarkerDispatcher.kt:160-166`.
- Severity: Medium. Confidence: High. Classification: **confirmed**.
- Evidence: the callback counts down `markerFinished` inside `task.run()`, while the semaphore is released only in the executor wrapper's `finally` after `task.run()` returns. The awakened test thread immediately asserts zero capacity, which is not ordered after release. The full 2,112-test run failed `expected 0 but was 1`; five isolated reruns passed.
- Failure scenario: scheduler contention wakes the assertion thread after callback countdown but before the worker's `finally`, making an otherwise correct build fail nondeterministically.
- Suggested fix: wait for the capacity terminal with a bounded eventual assertion, or expose a test-only terminal signal that is emitted after reservation release. Do not weaken production capacity accounting.

### C51-CV-03 — Loupe and pipeline ownership comments/docs contradict executable truth

- Location: `FlipRenderer.kt:294-298`; `GlPipeline.kt:1090-1110` and `1127`; `CLAUDE.md:867-871`; `docs/ARCHITECTURE.md:308-315`.
- Severity: Low. Confidence: High. Classification: **confirmed**.
- Evidence: authoritative docs correctly say the same-stream Loupe Overview is raw/inverted, but source comments repeatedly call it “UPRIGHT” and say it puts the world right-way-up. Separately, the architecture says rollback restores the baseline video packet unconditionally, while cycle-50 code now preserves a newer independently-owned publication. `check_docs.py` still reports the Loupe source/authority contract green, so its invariant misses the contradictory comments.
- Failure scenario: a future maintainer follows the stale rationale and reintroduces the superseded orientation rule, or removes independent pipeline supersession believing rollback always owns the packet.
- Suggested fix: rewrite comments around the actual same-stream raw/inverted exception, document conditional pipeline ownership plus the REC snapshot, and extend the docs mutation check to reject the stale “upright overview/world” claims.

## Final missed-issues sweep

Rechecked every changed cycle-50 file, all lock/executor and native-owner seams, manifests/privacy, locale parity, Compose semantics and interaction sizes, release/tooling scripts, screenshots, and all open field checks. No additional actionable finding survived evidence review. New deduplicated findings: **3** (2 Medium, 1 Low; all High confidence).
