# Aggregated deep review — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Coverage and aggregation

Five parallel specialist groups covered code-reviewer, architect, critic, performance, tracer,
security, debugger, verifier, test engineer, document specialist, native Android designer, and the
repository-local QA-adversary role. Every group inventoried the complete 471-path clean-clone
surface, read the project authorities, examined all relevant files and cross-file interactions, and
performed a final missed-issue sweep. Native Compose UI was assessed from source, semantics,
resources, and tests; browser tooling is not applicable. No device-only behavior was inferred.

The 21 raw specialist findings deduplicate to eight current root causes. Native dual-open ownership,
audio peak representation, and documentation-gate drift each had broad independent agreement. The
highest severity/confidence reported for each duplicate is preserved.

## Findings

### AGG35-01 — dual-open supersession can orphan the outgoing CameraDevice

- **Severity / confidence:** High / High
- **Sources:** code-reviewer, architect, critic (**broad cross-agent agreement**)
- **Status:** likely race; legal interleaving established, deterministic boundary reproduction needed.
- **Evidence:** `camera/CameraEngine.kt:3574-3624` stores outgoing `old` locally and publishes
  candidate `next`; its native-refusal callback can clear `controller` at lines 3667-3674; the
  supersession branch at lines 3746-3757 restores `old` only if `controller === next`.
- **Failure:** a candidate refusal can null the shared slot before a newer transaction ends the
  polling wait. Cleanup then closes `next` but neither restores nor closes `old`, leaving a live HAL
  owner unreachable and causing later `CAMERA_IN_USE`/black-preview failures until process death.
- **Plan direction:** make dual-open cleanup decide old-owner restoration/release independently of
  whether the candidate callback already vacated the shared slot, and add an exhaustive transition
  seam/test proving no outgoing owner is lost across callback-clear × supersession permutations.

### AGG35-02 — the authoritative host gate is red after cycle-34 plan closeout

- **Severity / confidence:** High / High
- **Sources:** verifier, test-engineer (**cross-agent agreement**)
- **Evidence:** `tools/tests/test_tool_contracts.py:345-360` mutates hardcoded cycle 33, but
  `docs/plans/2026-08-24-rpf-cycle34.md` is now the latest completed plan. The documentation checker
  therefore correctly ignores the older mutation and the negative test fails. `python3
  tools/verify_host.py` exits after one of 92 tool tests fails; preceding Android build/test/lint and
  exact 99.81% Partition-A coverage are green.
- **Failure:** every clean authoritative host verification is red and later coverage-tool, device
  harness, documentation, compile, and clean-diff phases do not run.
- **Plan direction:** share the production plan-selection rule with the fixture, validate the clean
  exported baseline before mutation, and make the negative test target the actual latest completed
  plan dynamically.

### AGG35-03 — audio peak truth is corrupted and over-published at the ViewModel boundary

- **Severity / confidence:** Medium / High
- **Sources:** performance, tracer, designer, QA-adversary (**broad cross-agent agreement**)
- **Evidence:** producers retain exact peaks, but `ui/CameraViewModel.kt:1008-1027` applies the
  RMS-oriented round-to-nearest 1/256 quantizer before `ui/overlays/Overlays.kt:658-679` compares
  exact 0.95 and `32767/32768` thresholds. The quantizer downgrades 31130/32768 below near-clipping,
  rounds 32704..32766/32768 to 1.0 and falsely calls them clipping, and republishes root
  `CameraUiState` for sub-threshold peak changes that affect neither pixels nor semantics.
- **Failure:** TalkBack misses the lower overload boundary, announces saturation for non-clipped
  input, and the 10 Hz held maxima undo meter recomposition dedup.
- **Plan direction:** classify raw producer peaks into coarse threshold-preserving overload states
  before root state, retain 1/256 quantization only for RMS geometry, and test PCM boundary samples
  through producer → ViewModel → semantics plus state-equality behavior.

### AGG35-04 — restored ownerless stills mishandle four standard mirrored EXIF orientations

- **Severity / confidence:** Medium / High
- **Sources:** designer, QA-adversary (**cross-agent agreement**)
- **Evidence:** `ui/review/MediaReview.kt:459-480` handles only EXIF 1/3/6/8 and returns identity for
  mirrored 2/4/5/7, while `storage/LatestCaptureReducer.kt:58-65,319-343` and
  `storage/MediaStoreWriter.kt:305-316` deliberately admit ownerless imported lookalikes for review.
- **Failure:** a reachable imported JPEG/HEIF displays with wrong handedness/axis in both thumbnail
  and full review despite standards-compliant galleries showing it correctly.
- **Plan direction:** implement all eight EXIF transforms on the already bounded bitmap and add
  asymmetric fixtures/pure matrix tests for dimensions and corner mapping.

### AGG35-05 — capture admission refusal reports a deletion failure

- **Severity / confidence:** Medium / High
- **Sources:** code-reviewer, architect, critic (**broad cross-agent agreement**)
- **Evidence:** `camera/CameraEngine.kt:4006-4012` returns false from a photo request when still-output
  capacity is unavailable but emits `COULD_NOT_DELETE_FILE`; the existing truthful identity
  `STILL_CAPTURE_UNAVAILABLE` is used nearby and localized separately.
- **Failure:** a shutter press during output cleanup says “Could not delete file” although no delete
  was requested, sending the operator and diagnostics toward the wrong subsystem.
- **Plan direction:** emit the truthful still-unavailable identity and prove the branch with a pure
  admission/status seam or Engine integration test.

### AGG35-06 — completed-plan ordering fails open at the supported cycle-100 boundary

- **Severity / confidence:** Medium / High
- **Sources:** verifier, test-engineer (**cross-agent agreement**)
- **Evidence:** `tools/check_docs.py:978-988` selects the last lexicographically sorted completed plan.
  Unpadded same-date names sort `cycle100` before `cycle99`, so cycle 99 is treated as newest.
- **Failure:** the documented 100-cycle loop can close cycle 100 without authoritative host evidence
  while the checker validates stale cycle 99 and remains green.
- **Plan direction:** parse `(date, numeric cycle)` and select by that key; cover 9/10, 99/100,
  later-date lower-cycle, incomplete-plan, malformed, and ambiguous cases.

### AGG35-07 — governing facts drift while the documentation gate stays green

- **Severity / confidence:** Medium / High
- **Sources:** code-reviewer, architect, critic, document-specialist, QA-adversary
  (**broad cross-agent agreement**)
- **Evidence:** `docs/ARCHITECTURE.md:1246-1253` says AGP 9.3.1 while the catalog/primary authorities
  say 9.3.2; `CLAUDE.md:210-224`, `docs/ARCHITECTURE.md:68`, and a CameraEngine hot-path comment say
  pseudo-ZSL freshness is 250 ms while `ZslAdmission.kt:25-42` executes/tests 400 ms; Architecture
  claims a logical-camera 4-second field check remains in `docs/FIELD_CHECKS.md`, but no such
  dashboard/body entry exists. `tools/check_docs.py` nevertheless reports all 107 checks green.
- **Failure:** clean-clone maintainers receive wrong current build/behavior guidance and an
  unexecutable field obligation under a passing gate.
- **Plan direction:** correct AGP and ZSL facts; resolve the field-check status from committed
  evidence without inventing device proof; add declarative/negative contracts for each duplicated
  machine fact and every active “open in FIELD_CHECKS” reference.

### AGG35-08 — CameraEngine keeps native transaction invariants non-local

- **Severity / confidence:** Medium / High
- **Source:** architect
- **Status:** confirmed maintainability risk evidenced by AGG35-01 and AGG35-05.
- **Evidence:** `camera/CameraEngine.kt` is 7,647 lines with 233 functions, 82 volatile sites, 72
  synchronized blocks, and 23 callbacks spanning camera, GL, capture/storage, recorder, and recovery
  ownership. Dual-open terminal ownership and capture admission/status are still performed inline.
- **Failure:** local timing or policy changes can violate native exact-release/status invariants far
  from the helper tests, as the current race and cross-subsystem status demonstrate.
- **Plan direction:** address the concrete defects with extracted typed state/policy seams now;
  record the broader facade decomposition as deferred architecture work with exact trigger and
  repository-policy constraints rather than expanding this corrective cycle into a rewrite.

## Agent failures

None. Every available reviewer returned and wrote its provenance report.

## Totals

- Raw specialist findings: 21
- Deduplicated current findings: 8
- Severity: 2 High, 6 Medium
- Confidence: 8 High
- Deferred findings: none at review stage; Prompt 2 must schedule or explicitly defer every item.
