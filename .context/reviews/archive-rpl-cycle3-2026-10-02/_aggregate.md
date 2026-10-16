# RPL cycle 3 — aggregate review (HEAD e3a2bdd4, 2026-10-02)

Sources (one file per agent, kept for provenance): `code-reviewer.md` (CR3), `perf-reviewer.md`
(PERF3), `security-reviewer.md` (SEC3), `critic.md` (CRIT3), `verifier.md` (VER3),
`test-engineer.md` (TE3), `tracer.md` (TR3), `architect.md` (ARCH3), `debugger.md` (DBG3),
`document-specialist.md` (DOC3), `designer.md` (DES3), `c2-regression-reviewer.md` (REG3, the
re-run of cycle 2's missing post-merge review), `fd-code-reviewer.md` (FD3), `qa-adversary.md` (QA3).

Dedup rule: one AGG3 entry per root cause; highest severity/confidence among duplicates is kept;
"Agents" lists every agent that flagged it (more agents = higher signal). Orchestrator
verification notes are marked **[orch]**.

## A. Correctness / data-loss (highest priority)

| ID | Title | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG3-1 | Leaving FRONT re-zooms every standalone rear route through the ratio-picked divisor (`rearReturnZoom` is not the inverse of the lens-based entry snapshot): Video MAIN 4.0 → 1.33, UW 2.0 → 0.72×, TELE3X 4.0 → 3.6×; live return disagrees with the save path (`retainedRearWireZoom`) | Medium / High | CR3-1, TR3-1, CRIT3-1, TE3-3 (VM call untested) | `camera/CameraState.kt:606-614`; `CameraEngine.kt:3985-3998`; `CameraViewModel.kt:2993-3007` |
| AGG3-2 | TELE 10-bit Video ladder rung 3 (`(0, non-vendor)`) configures HLG10 + full-res JPEG + RAW, the documented HAL-crash combination; the "RAW never rides a 10-bit rung" test covers only the non-TELE table **[orch: confirmed — `useRaw` has no `!tenBitVideoOnly` term]** | Medium / High | VER3-1 | `camera/CameraController.kt:2749-2796`; `SessionFallbackLadderTest.kt:409-411` |
| AGG3-3 | Launch recovery can never adopt a real app HEIF: `HeifWriter`'s gridded primary item uses `construction_method = 1` (`idat`), which the probe maps to INDETERMINATE, so every REGISTERED HEIF stays pending forever (proved on checked-in device HEIFs) | Medium / High | DBG3-1 | `storage/MediaStoreWriter.kt:2989-2991`; `MediaDurabilityPolicyTest.kt:60,115` |
| AGG3-4 | Retained IS_PENDING rows expire (MediaProvider default ~7 days, deleted in idle maintenance); nothing re-arms `DATE_EXPIRES`, so every "fail closed, keep for recovery" path can silently lose the take | Medium / Medium (Needs-device for the exact expiry) | CRIT3-2, DES3-2, DBG3-1 | `storage/MediaStoreWriter.kt` (no `DATE_EXPIRES` anywhere) |
| AGG3-5 | Retained-take copy over-promises: "will be saved the next time the app starts" is true only for a NEW PROCESS (orphan sweep selects `DATE_ADDED < processStartSecs`), and is false for RETAINED_VALIDATION_UNAVAILABLE video; still vs video marker-unavailable wording diverge and still copy says "Recovery marker" | Medium / High | DES3-1, DES3-4, REG3-1 (copy half), CRIT3-2 (copy half) | `res/values*/strings.xml` `status_*_save_delayed`, `status_output_saved_pending*`; `RecordingStorageDispatcher.kt:122-125` |
| AGG3-6 | A finalized video whose `muxer.stop()` threw and whose extractor throws is INDETERMINATE live AND at every launch recovery, so a corrupt take is retained forever and never judged | Medium / Medium | REG3-1 | `MediaStoreWriter.kt` `classifyFinalizedVideoTrack` ~2675-2692, `pendingProbeOutcome` ~2312-2318; `VideoRecorder.kt:462-472,1983-1993` |
| AGG3-7 | b5c57e8a restores Ready on the OLD session after a preflight failure of a mutate-then-reopen door (`applyVideoSize`, stab, aspect/hi-res, high-speed fps): `currentOpticsReconfiguration()` snapshots `before` AFTER the mutation, so Ready publishes a `videoSize`/`previewStreamSize` the stream was never configured for **[orch: confirmed in `applyVideoSize`/`reopenForSession()`]** | Medium / Medium | CR3-2, ARCH3-3 (secondary) | `CameraEngine.kt:3792-3805, 3395-3397, 950-956, 1038-1049` |
| AGG3-8 | Recall audio provenance: f64417fa (keep denial key on a silent-bank recall) re-opens AGG-37 — a deliberately silent bank is forced to audio-on by a later grant; banks store bare `recordAudio` | Medium / High | CRIT3-3, REG3-4 | `MainActivity.kt:515-530`; `CameraPermissionPolicy.kt` `audioDenialReasonClearedByRecall` |
| AGG3-9 | Shared `rawChars` retry gate: the still's own `applyMetering` spends the 1 s token, so `tryComplete` for a delivered image fails "Missing camera characteristics" where the old per-shot retry would have saved it | Low-Medium / Medium | TE3-6, DBG3-3, REG3-6 | `CameraController.kt:161, 1306-1312, 2269-2277, 2420-2436` |
| AGG3-10 | Rollback publication mirrors `rawWanted` from the frozen packet while video size is read live; a direct DNG write between setup-thread commit and main post is reverted in the VM only | Low / Medium | ARCH3-2, REG3-3 | `CameraViewModel.kt:960 vs 985-990`; `CameraEngine.kt:1085` |
| AGG3-11 | A stale rollback publication is dropped whole when a newer door begins before the main post; VM keeps the failed door's optimistic packet for fields the newer door does not republish | Medium / Medium (Likely) | ARCH3-1 | `CameraViewModel.kt:933-1011` |
| AGG3-12 | Direct-write counters cannot tell who wrote last under a stacked baseline; a failed transaction's own write survives rollback | Low-Medium / High | ARCH3-3 | `CameraEngine.kt:778, 1043-1047, 1055-1063, 2834-2836` |
| AGG3-13 | VM still answers "standalone photo route?" from the `CameraUiState.rawForcesStandalone` copy at 8 sites while the DNG door uses the engine law (generic device before inventory / EXTERNAL↔BACK) | Low-Medium / Medium | ARCH3-5, REG3 O-1 | `CameraViewModel.kt:2273, 2402, 2604, 2854, 2890, 2972, 3001, 3126` |
| AGG3-14 | Recall evaluates the restored zoom scale with the CURRENT route's RAW law (EXTERNAL→BACK recall) | Low / Medium | CRIT3-4 | `CameraViewModel.kt:1341-1345`; `CameraEngine.kt:1740` |
| AGG3-15 | Route-aware rollback silently drops the operator's in-flight DNG pick with a generic status | Low / High | CRIT3-5 | `CameraEngine.kt` `rollbackRawWanted`; `CameraViewModel.kt:984-1000` |
| AGG3-16 | MR recall's trailing session setters issue a second same-generation reopen behind the recall's own reconfigure (double black dip) | Low / Medium | CR3-4 (AGG2-39 runtime effect) | `CameraViewModel.kt:1500-1522` |
| AGG3-17 | "10-bit video · stills off" caption on the 8-bit preview-only rung; request predicate ignores `supportsHlg10()` (carried AGG2-35 residual) | Low / High | TR3-2 | `ProSheet.kt:898`; `ProControls.kt:1037-1048`; `CameraController.kt:672,764` |
| AGG3-18 | An accepted session rewrites the processed-format request (DNG-only → HEIF+DNG after FRONT or drop-RAW rung) and persists it | Low / High | VER3-2 | `camera/OpticsConstraints.kt:77-81`; `CameraState.kt:1445-1462` |
| AGG3-19 | `PhotoFormats.withEdit`: pre-inventory "JPEG off" on a HEIF request is a silent no-op | Low / High | TE3-7 | `CameraState.kt` `withEdit` ~1256 |
| AGG3-20 | Standby meter catch path (5a49bbfd) can leak a created-but-unbound AudioRecord and finish a stale publication token | Low / Low | REG3-7 | `StandbyAudioController.kt:~676-696, 783-797` |
| AGG3-21 | Drop-frame rates (29.97 etc.) drive `KEY_MAX_FPS_TO_ENCODER`; the surface frame dropper drops a real camera frame every ~1001 frames | Low / Medium (Needs-device) | DBG3-2 | `video/ColorProfiles.kt:44-49`; `CameraState.kt:1060-1068` |
| AGG3-22 | Restorable-preflight rollback cancels in-flight DNG pre-allocations for a camera that "remained unchanged" **[orch: the tap-AF half of CR3-3/ARCH3-4 is REFUTED — the restore branch calls `commitRetainedOpticsControls`, whose `retainedOpticsApplyPlan(tapResetPending=true)` is FULL_REBUILD (DBG3 agrees); only the DNG-cancel half stands]** | Low / Medium | CR3-3, ARCH3-4 | `CameraEngine.kt:548-565, 1188-1189`; `ManualControls.kt:730-736` |

## B. Performance / concurrency

| ID | Title | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG3-23 | While recording, the encoder draw waits behind a blocking preview `eglSwapBuffers` (TextureView, swap interval 1); a main-thread stall drops frames in the FILE | Medium / Medium (Needs-device) | PERF3-1 | `gl/GlPipeline.kt:1044-1259`; `gl/EglCore.kt:92-94` |
| AGG3-24 | JPEG lanes re-stamp EXIF via `ExifInterface.saveAttributes()` (full temp-file copy + rewrite of every JPEG through FUSE); partial-rewrite failure inside `runCatching` still publishes | Medium / High | PERF3-2 | `capture/StillCapturePipeline.kt:322-368, 470-486` |
| AGG3-25 | DNG camera callback also performs Binder `openOutputStream` + fsync'd COMPLETE marker with sleep backoff (≤75 ms) on the camera HandlerThread | Low-Medium / High | PERF3-3 | `StillCapturePipeline.kt:388-407`; `MediaStoreWriter.kt:636-647, 1056-1064` |
| AGG3-26 | Gallery thumbnail spools the whole still into cache and decodes three times for 240 px | Low-Medium / High | PERF3-4 | `ui/review/MediaReview.kt:472-500, 830-841` |
| AGG3-27 | `PendingDiscardJournal` opens/closes a fresh SQLite connection per op under a process-wide lock | Low / High | PERF3-5 | `storage/PendingDiscardJournal.kt:97-389` |
| AGG3-28 | Logical-route stills round-trip through an intermediate q97 JPEG encode+decode | Low / Medium | PERF3-6 | `capture/StillSnapshot.kt:44-55`; `StillCapturePipeline.kt:200` |
| AGG3-29 | GL-thread input-ready continuation holds `TerminalAcquisitionGate` across Binder route enumeration, racing a second enumeration on setupExecutor (carried AGG-50, new evidence) | Low-Medium / Medium | PERF3-7 | `CameraEngine.kt:1880-1914, 1556-1582` |
| AGG3-30 | Launch-recovery backoff `runCatching { Thread.sleep }` swallows the interrupt flag (AGG-30 tail) | Low / High | CRIT3 note, PERF3 inventory | `camera/CameraEngine.kt:7573` |

## C. Security / release tooling

| ID | Title | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG3-31 | Gates verify the `keystore.properties` key but AGP signs with `android.injected.signing.*` when present; wrapper forwards arbitrary argv (`-P`, `-I`, `-x`) as "tasks" | Medium / Medium | SEC3-1 | `app/build.gradle.kts:775-867`; `tools/build_immutable_release.py:871-918` |
| AGG3-32 | Generated-secret strength floor enforced only by the scoped helper, not by the documented wrapper or Gradle | Medium / High | SEC3-2 | `tools/run_scoped_signed_release.py:35-90`; `build_immutable_release.py:707-735` |
| AGG3-33 | Checker's expected signer is self-attested from untracked `keystore.properties` | Low / High | SEC3-3 | `tools/check_release_artifact.py:701-711` |
| AGG3-34 | Store password captured in a doFirst lambda (configuration-cache state) | Low / Medium | SEC3-4 | `app/build.gradle.kts:812, 847` |
| AGG3-35 | Alias precedence: `load_upload_key_prerequisite` env-first vs Gradle properties-first | Low / High | SEC3-5 | `tools/build_immutable_release.py:623-625` |
| AGG3-36 | No-signing refusal not attached to `packageRelease` (APK written before `assembleRelease` doFirst) | Info / High | SEC3-6 | `app/build.gradle.kts:775-786` |

## D. Tests / gates

| ID | Title | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG3-37 | **Host gate is RED in this worktree**: `check_docs.py` password-property rule scans untracked `.context/**/*.md` review notes under a "tracked docs" label **[orch: reproduced — 189 checks, 1 failed]** | Medium / High | DOC3-1, QA3 | `tools/check_docs.py:1542-1558` |
| AGG3-38 | Partition-A residual manifest cites wrong lines for 6/9 entries (b953b741 wrote two of them); gate checks class+count only | Medium / High | TE3-1, REG3-2 | `tools/coverage/partition-a-residuals.txt`; `tools/coverage/partition_report.py:131-149` |
| AGG3-39 | AGG2-3 regression test rebuilds the engine wiring itself; reverting the engine fix stays green | Medium / High | TE3-2 | `CameraEngine.kt:4844-4938`; `DngPreCaptureAllocationTest.kt` |
| AGG3-40 | ~10 cycle-2 fixes guarded only by a pure helper; call sites revertable silently | Medium / High | TE3-3 | see test-engineer.md TE3-3 list |
| AGG3-41 | New owner parameters default to the "fix off" value (`currentStandalone=false`, `liveIso=null`), so dropping an argument is silent | Low-Medium / High | TE3-4 | `ui/ZoomMath.kt:252-253`; `camera/ManualControls.kt` ownership helpers |
| AGG3-42 | VM DNG door PMA110 remap path and AGG2-8 VM merge never executed by any test | Medium / High | TE3-5 | `CameraViewModel.kt` ~2463-2500 |
| AGG3-43 | `MediaStoreWriter.cleanupOrphanedPending` is dead code yet was edited by f4ecda3d | Low / High | TE3-8 | `MediaStoreWriter.kt:1246-1280` |
| AGG3-44 | AGG2-15 test covers 3 of 6 ticker guards and not detach-before-purge order; tearDown double-clears | Low / High | TE3-9 | `CameraViewModelTickersRobolectricTest` |
| AGG3-45 | Refusal doors hand-written per door; aspect refusal untested (table test wanted) | Low-Medium / High | TE3-10 | `CameraViewModel.kt` 17 `rejectIfRecording` sites |
| AGG3-46 | `DiagnosticLogTest` production-binding check is vacuous (passes with zero rows) | Low / Medium | TE3-11 | `camera/DiagnosticLogTest.kt:79-96` |
| AGG3-60 | **Host gate is RED**: `tools/tests` `test_documentation_gate_keeps_exact_millisecond_verdict_under_all_modes` fails `ENOTEMPTY` removing `staging/.git` because git 2.54 runs detached auto-maintenance (repack/multi-pack-index/bitmap) after the fixture commit, racing `TemporaryDirectory` cleanup **[orch: reproduced; `.git/objects/pack/*`, `multi-pack-index`, `bitmap-ref-tips_*` appear after `git commit` returns]**; verify_host aborts before coverage/device-tests/check_docs | High (gate) / High | QA3-1 | `tools/tests/test_tool_contracts.py:105-117` and the other fixture-repo `git commit` sites in `tools/tests/` |
| AGG3-61 | Lint warning `UsableSpace` (`ui/review/MediaReview.kt:456`) — already deferred in cycle 2; re-recorded | Low / High | QA3 | `ui/review/MediaReview.kt:456` |
| AGG3-62 | `StillSnapshot.Nv21.jpegBytes()` keeps the ~19 MB NV21 buffer after a FAILED `compressToJpeg` (nulls only after `check(ok)`) | Low / High | FD3-3 | `capture/StillSnapshot.kt:~46-52` |
| AGG3-63 | Sub-confidence: ladder-exhaustion reader close depends on Engine; `applyMetering` region sizing keyed `physicalId ?: logicalId` | Low / Low | FD3-1, FD3-2 | `camera/CameraController.kt` |

## E. Documentation

| ID | Title | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG3-47 | Authority docs state PMA110 DeviceProfile quirks (4 s ceiling, YUV logical still, standalone-only RAW, DNG moves off seamless) as universal; 5 of 6 profile fields undocumented | Medium / High | DOC3-2 | `docs/ARCHITECTURE.md:610-611, 630-631, 702`; `CLAUDE.md` DNG bullet |
| AGG3-48 | CLAUDE.md front-camera bullet: says tap-AF needs no un-flip and that the inversion "becomes" a DeviceProfile flag; code un-flips metering and already has the flag | Low / High | VER3-3 | `CLAUDE.md` front bullet; `CameraEngine.kt:3179-3190, 8858-8860` |
| AGG3-49 | CLAUDE.md TC OIS "UNVERIFIED" vs FIELD_CHECKS C3 closed | Low / High | DOC3-3 | `CLAUDE.md` 0x80b4 bullet; `docs/FIELD_CHECKS.md:208-219` |
| AGG3-50 | Stale "debug-gated 10-bit EXPERIMENT" comment on the shipping non-SDR rung | Low / High | DOC3-4 | `CameraController.kt:2740` |
| AGG3-51 | DNG bullet item 2 omits the TELE/direct-write no-reopen path and the rollback keep rule | Low / High | DOC3-5 | `CLAUDE.md` DNG item 2 |
| AGG3-52 | Covered-glyph list in CLAUDE.md incomplete (’ ↑ ↓) | Low / High | DOC3-6 | `CLAUDE.md` FocusDetail bullet |
| AGG3-53 | 8K frame-rate KDoc/rule unreachable under the 3840-wide selector cap | Low / Medium | DOC3-7 | `CameraState.kt:1083-1106` |
| AGG3-54 | Private TESTING.md pins Robolectric 4.16.1 (now 4.17) | Low / High | DOC3-8 | `docs/TESTING.md:119,138` |
| AGG3-55 | ColorProfiles KDoc claims the engine forces SDR for AVC | Low / High | DOC3-9 | `video/ColorProfiles.kt:24-25` |
| AGG3-56 | KDocs stacked by cycle-2 helper insertions (`setVideoResolution`, `readRawCharacteristics`, `shouldPublishRecording` lost their docs) | Low / High | REG3-5 | `CameraEngine.kt:~3757-3772`; `CameraController.kt:2420-2431`; `VideoRecorder.kt:~2340-2364` |

## F. UI / accessibility

| ID | Title | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG3-57 | OSD status row has no spoken form; TalkBack reads raw finder codes as separate stops | Low-Medium / High | DES3-3 | `ui/overlays/Overlays.kt:882-1095` |
| AGG3-58 | Dropdown selected option marked by colour alone (WCAG 1.4.1) | Low / High | DES3-5 | `ui/controls/ProControls.kt:870-880` |
| AGG3-59 | Carried DES2-3..9 (statuses under review overlay, a11y timeout incl. new evidence that the longer retained copy sits at 2.5 s, tab-rail word breaks, plate margin, KO adverbs, LabelValueRow stops, DISP node rename) — unchanged | Low–Low-Medium / High | DES3 carried | see designer.md |

## Cross-agent agreement (highest signal)

- AGG3-1 (4 agents), AGG3-4 (3), AGG3-9 (3), AGG3-5 (4 incl. halves), AGG3-8 (2), AGG3-10 (2),
  AGG3-13 (2), AGG3-38 (2), AGG3-22 (2, partly refuted).

## Cycle-2 regression verdict (REG3 + DBG3 + CRIT3 + CR3)

40 commits checked (REG3 per-commit table). No regression that changes PMA110 capture behaviour in
the common path. Issues attributable to cycle 2: AGG3-5/AGG3-6 (49f435e1 + c4cc92e7), AGG3-7
(b5c57e8a), AGG3-8 (f64417fa), AGG3-9 (03203d17), AGG3-20 (5a49bbfd), AGG3-38 (b953b741),
AGG3-56 (4d0c5e3c, 03203d17, 56803b42), and AGG3-1's live half left unfixed by ae359201.

## AGENT FAILURES

None. All 14 lanes returned. fd-code-reviewer and qa-adversary returned by message (they cannot
write files) after one orchestrator status nudge each; their reports are saved verbatim-in-substance
to `fd-code-reviewer.md` and `qa-adversary.md`.

## Totals

63 deduplicated AGG3 entries (from ~95 raw findings across 14 lanes). Refuted on verification:
the tap-AF half of CR3-3/ARCH3-4 (see AGG3-22).
