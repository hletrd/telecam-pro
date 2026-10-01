# RPL cycle 3 — independent post-merge review (`c3-laneC` vs `main`)

Reviewer: c3-merge-reviewer. Scope: `git log main..HEAD` on `/Users/hletrd/flash-shared/fx9-c3-laneC`
(30 commits at review time, including the lead's follow-up `6ec34b3d` that regenerated the residual
regions after the merge). Read-only: no Gradle run, no edits. Acceptance: plan
`docs/plans/2026-10-02-rpl-cycle3.md` lanes A.1–A.10, B.1–B.9, C.1–C.7.

## Per-item verdicts

| Item | Commit(s) | Verdict | Note |
|---|---|---|---|
| A.1 AGG3-2 | 58eb4f10 | OK | `!tenBitVideoOnly` on `useRaw` covers every rung, including the TELE regular-full rung that goes back to stream plan 0. The test sweeps tele × hiRes × every attempt with `rawStandaloneOnly=false`, so it would fail on revert. The stacked `maxSessionAttempt` KDoc is unstacked. |
| A.2 AGG3-1 | 6ba15217 | OK | Divisor is `opticalBaseFor(lensPreset)`, the exact inverse of the lens-based entry snapshot (engine `CameraEngine.kt:4006-4028`, VM 2968/2993). `retainedRearWireZoom` delegates, so the live return and the persisted value are one function. The table test and the engine Robolectric test fail on revert (1.2 / 1.33). Side effect: NaN input to `retainedRearWireZoom` now falls back to the preset instead of propagating NaN. Harmless. |
| A.3 AGG3-7 | c5bfd1c8, bf76c0ee | OK (doc nit) | `baselinePrecedesMutation` is true only in `beginOpticsTransaction`. Bare reopen / resume tokens lose the preflight licence. The AGG2-4 pure test still holds for transactional doors. See MRG3-1 (new stacked KDoc). |
| A.4 AGG3-9 | f32a5bbd | OK | `lazyCharacteristicsRead(shot=true)` bypasses the gate and does not consume it. Metering stays gated. A failing-then-succeeding reader test fails on revert. |
| A.5 AGG3-10 | f603a208 | OK | Mirror reads `currentRawWanted()` (`@Volatile`). The test performs the direct write between the rollback commit and the main drain, so it fails on revert. |
| A.6 AGG3-17 | 04fad2e8 | OK | `hlg = useHlg` comes from the accepted plan. The high-speed READY path keeps the default `false` (correct: there is no HLG there). The caption test derives outputs from `sessionAttemptPlan` rungs 0 and 3. |
| A.7 AGG3-13 | 176e2199 | OK | `standaloneRouteFor` reads `engine.rawForcesStandalone` at all 8 VM route sites. No other `ui/` file makes a route decision from the state copy. |
| A.8 AGG3-41 | 9cfd288a | OK | Defaults removed. Every caller passes values explicitly, so dropping an argument no longer compiles. |
| A.9 AGG3-39 | 0b2a6e99 | OK | `dispatchDngPreCaptureAllocation` keeps the order build → register → `start()` → `dispatchResult`, and the test drives the same function. |
| A.10 AGG3-30 | ce60c1a8 | OK | `sleepPreservingInterrupt` keeps the interrupt. An interrupted backoff ends the run as EXHAUSTED, which only logs `MediaRecovery exhausted`, a cosmetic mislabel. |
| B.1 AGG3-3 | 2295ea79 (+6ec34b3d) | OK | idat-relative primary, construction-0 items in mdat, at least one coded item. INVALID outranks INDETERMINATE. Non-primary fields read leniently, so construction-0 primary verdicts are unchanged. Weak spot: an Exif-only mdat item counts as "coded". Low, not filed. |
| B.2 AGG3-6 | 01c42176 | OK per plan; see MRG3-2 | The plan's option (b) is implemented exactly. It partly reverses AGG2-18's protection on the only path where the live probe runs. |
| B.3 AGG3-4 | 5ebe8b07 | OK, PENDING DEVICE | Re-asserts only on KEEP_PENDING non-DISCARD rows. Warnings are change-gated. The `DATE_EXPIRES` effect remains unproven on device, as the plan says. |
| B.4 AGG3-8 | a36c5889 | OK | Provenance is captured from the Activity's preference at save and restored at the applied-load exit. Legacy banks (key absent) keep the AGG2-26 rule. The main blob writes `remove(key)`. Both store/recall entry points (ProSheet → MainActivity actions) are covered. |
| B.5 AGG3-19 | 5a3b89e1 | OK | Clearing all displayed processed outputs clears both processed axes. The test fails on revert. |
| B.6 AGG3-18 | 6cae37ed | OK; see MRG3-3 | Ready no longer writes `photoFormats`. OSD, the storage estimate and the DNG-only Ready status read `effectivePhotoFormats`. CLAUDE.md rule 4 updated. PMA110 standard photo sessions (processed + RAW) render the same as before. |
| B.7 AGG3-20 | cb62f4ea | OK | The gate decrements its counter before `publicationOwner` runs, so a refused bind throws out cleanly. `unboundInput` is released once in `finally` and never started. |
| B.8 AGG3-62 | 6abb57ac | OK | `pixels = null` before compress. Single-use is kept. |
| B.9 AGG3-43 | 9ddf41bf | OK | Dead loop removed; ARCHITECTURE updated. |
| C.1 AGG3-38 | c70af129, 6ec34b3d | OK | Line-identity gate plus a region-holds-count check. At HEAD the regenerated regions (`CameraController.kt:2687`, `CameraState.kt:916`, `MediaStoreWriter.kt:3206-3207`) point at the cited code (verified by reading the lines). The pre-merge regions in c70af129 were stale until 6ec34b3d. |
| C.2 AGG3-31 | 638b666a | OK | `gradlePropertiesPrefixedBy("android.injected.signing.")` covers `-P`, `gradle.properties` and the user-home properties. Refusal is value-free and its input is part of the task inputs. The wrapper validates argv in both `main()` and `build_immutable_release()`. Every documented caller (`verify_host.py --release`, `run_scoped_signed_release.py`) passes only `:app:*` task paths after its own `--root/--output` options, so the grammar does not reject them. |
| C.3 AGG3-32 | b67e9ab5, 1607bfeb | OK; see MRG3-4 | One floor in `upload_key_policy.py`, applied to the effective `storePassword` and `keyPassword ?: storePassword`, mirroring `build.gradle.kts:399-401`. |
| C.4 AGG3-35 | 7b8fbd0a | OK | Alias resolved by `_gradle_signing_value` (file over env). A duplicated `keyAlias` is still refused. |
| C.5 AGG3-36 | 4e603375 | OK | The unsigned refusal also attaches to `packageRelease`, `packageReleaseUniversalApk` and `signReleaseBundle`. The host gate runs no release package task, so it is unaffected. |
| C.6 AGG3-5 | 54abb79b | OK | EN and KO updated for all four strings plus the new `status_video_kept_unverified`. No glyph outside the covered set. `RETAINED_VALIDATION_UNAVAILABLE` → `RETAINED_UNVALIDATED` → "will be checked". Retained-take messages stay 6 s. Consistent with B.2: after B.2 that disposition is reachable only through an open failure, and the "checked" wording is accurate there. |
| C.7 docs | 3d0946ab, 0e9e1e84, 58eb4f10, f32a5bbd, f603a208 | OK with MRG3-1 | DeviceProfile qualifiers match the code. The covered-glyph set gained `’ ↑ ↓`, and all three bundled Inter faces carry U+2019/2191/2193 (verified with fontTools). Several stacked KDocs were unstacked, but A.3 added a new one (MRG3-1). |

Cross-lane semantic checks:
- **Lane B (B.2) vs lane C (C.6):** the status mapping and the finalized-video classification agree. INVALID → FAILED → "Video save failed"; an open-failure INDETERMINATE → "Video kept privately. It is checked …".
- **Lane A vs lane B on VM recall/rollback:** A.5 changes only `dngRaw` in the rollback mirror. B.6 removed only the Ready-time `photoFormats` write. B.4 adds `appliedAudioOffByDenial` at the `applyLoaded` exit. No overlapping field writes, and no ordering dependency.
- **Lane A (A.2 `rearReturnZoom`) vs A.7 (`standaloneRouteFor`):** the VM's front return now uses the engine's RAW law, the same as `CameraEngine.kt:4021-4024`.

## Findings

### MRG3-1 — A.3 introduced a new stacked KDoc (the AGG3-56 pattern this cycle removes elsewhere)
- Severity / Confidence: Low / High
- Location: `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:8556-8574`
- What happens: `preflightRestorableSessionGeneration` and its KDoc were inserted between `rollbackRestorableSessionGeneration`'s KDoc (8556-8562) and that function (8574). The result is two consecutive KDocs. The AGG2-4 rationale now attaches to nothing, and `rollbackRestorableSessionGeneration` has no doc. This is the same defect C.7 / AGG3-56 removed at four other sites in this cycle.
- Fix: move the 8556-8562 block down to just above `internal fun rollbackRestorableSessionGeneration` (8574).

### MRG3-2 — B.2 partly reverses AGG2-18 on the only path where the live probe runs
- Severity / Confidence: Low-Medium / Medium. This is a design risk, not a code defect; the plan asked for it.
- Location: `storage/MediaStoreWriter.kt` `classifyFinalizedVideoTrack` (`muxerStopThrew` branch); `video/VideoRecorder.kt` `RecordingStorageTail` (`muxerStopThrew = true`).
- What happens: the live tail validates only when `muxer.stop()` threw. CLAUDE.md tolerates that throw precisely so that "a mic dropped in the add-track window cannot delete a good take." In AOSP, `MPEG4Writer` still writes moov on that `ERROR_MALFORMED` path, so the usual outcome is a parseable file → VALID. That part is fine. The changed case is an extractor throw on an opened FUSE fd that is transient rather than structural. AGG2-18 (Medium/High, five reviewers) made exactly that case INDETERMINATE. It is now INVALID → delete. AGG3-6 was Medium/Medium from one reviewer, so this decision has flipped between cycles.
- Fix (choose one and record it in the plan, so it does not flip again):
  - (a) Before classifying INVALID, retry the extractor once after a short delay; a second throw is stronger evidence.
  - (b) Use REG3-1's option (a): a per-URI recovery attempt counter that turns a repeated parse throw into INVALID on the Nth launch, and keep the live path INDETERMINATE.
- Either way, a device check of a forced mic-drop take should confirm the common case still publishes.

### MRG3-3 — `effectivePhotoFormats` keeps `dngRaw` from the request, so the readout can claim a DNG no session will write
- Severity / Confidence: Low / High
- Location: `camera/CameraState.kt` `PhotoFormats.effectiveFor` (`normalizedFor(outputs).copy(dngRaw = dngRaw)`); readers are `ui/overlays/Overlays.kt` (OSD format tag) and `ui/CameraScreen.kt` `StatusInfoPill` (shots-remaining estimate).
- What happens: the KDoc says "what an accepted session will actually write". On FRONT, where RAW is structurally excluded and the outputs are `processed=true, raw=false`, a DNG-only request reads "HEIF + DNG" in the OSD and the estimate counts 26 MB per shot. Capture-time normalization writes HEIF only. This is not a regression: the old Ready-time normalization used the same `.copy(dngRaw = …)`. But B.6 made this value the explicitly "effective" readout.
- Fix: for readouts, let `dngRaw` follow the request only while a RAW-carrying route is reachable (for example `dngRaw && (outputs.raw || rawSelectable(...) && !frontFacing)`). Or narrow the KDoc so it does not promise session truth on the RAW axis.

### MRG3-4 — The secret floor now runs before the approval check, and the maintainer's current local keystore fails it
- Severity / Confidence: Low (informational for the release flow) / High
- Location: `tools/build_immutable_release.py:760-765` (`require_approved_upload_key`).
- Evidence: a value-free check of the main checkout's `keystore.properties` printed only booleans; both `storePassword` and `keyPassword` return False from `meets_generated_secret_floor`. That file also has no `uploadKeyRotationApproved` or `uploadKeyCertificateSha256` entry, so `verify_host.py --release` was already refused at the approval step before this change. No legitimate release that worked before is broken. AGG-67 key rotation remains an owner decision.
- Two effects:
  1. The first refusal the operator sees now names the password policy, not the missing approval/fingerprint step.
  2. The rotated key must be generated with floor-compliant store and key passwords. The scoped helper already required this; the plain `keystore.properties` path did not.
- The Gradle-side gate in `app/build.gradle.kts` does not apply the floor. That is acceptable, because direct Gradle release outputs are developer-only per CLAUDE.md.
- Fix: optionally move the floor check after `load_upload_key_prerequisite`, so the approval/fingerprint refusal comes first. Also state the floor in the README rotation/release steps next to the approval properties.

## Device-pending (unchanged by this review)
A.1–A.7, A.10, B.1 (device HEIF re-check claimed in the commit, not re-run here), B.2, B.3
(`DATE_EXPIRES`), B.7 remain host-tested only, per CLAUDE.md "verify on device".

## Release-gate impact summary
The documented flow is `python3 tools/verify_host.py --release` or `build_immutable_release.py :app:lintRelease :app:assembleRelease :app:bundleRelease` with an approved, floor-compliant upload key and no `android.injected.signing.*` property. It is not broken by:
- the argv grammar (only bare task paths are passed),
- the injected-signing refusal (that prefix is never set by the wrappers; ordinary IDE `android.injected.*` properties outside `.signing.` are not matched),
- the `packageRelease` refusal (it applies only when `keystore.properties` is absent),
- the alias precedence (it now matches Gradle).

The only new precondition is MRG3-4's password floor.

VERDICT: APPROVE
