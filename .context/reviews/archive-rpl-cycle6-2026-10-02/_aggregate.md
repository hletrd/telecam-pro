# RPL cycle 6 aggregate — 2026-10-02 (HEAD 30970c9e)

Sources (this cycle, `.context/reviews/`): `code-reviewer.md` (CR6), `perf-reviewer.md` (PR6),
`security-reviewer.md` (SR6), `critic.md` (CT6), `verifier.md` (VF6), `test-engineer.md` (TE6),
`tracer.md` (TR6), `architect.md` (AR6), `debugger.md` (DB6), `document-specialist.md` (DS6),
`designer.md` (UX6), `fd-code-reviewer.md` (FD6), `c5-regression-reviewer.md` (RG6, cycle-5 commits
ea7d4374..30970c9e), `qa-adversary.md` (QA6, host gate run). Previous cycle archived in
`archive-rpl-cycle5-2026-10-02/`.

Deduplicated: each row keeps the HIGHEST severity/confidence any reviewer gave it; `agree: n` marks
cross-agent agreement. Owner-decided items in CLAUDE.md (and AGG5-13, awaiting the owner via
FIELD_CHECKS A6) are not re-raised.

AGG5-1 special-attention verdict (RG6): the cold-start pause / GL replay fix is sound on every traced
interleaving; residuals RG6-2/3/4 below. TR6-3 found one separate cold-start window (AGG6-20).

## Severity summary

0 Critical, 0 High. Medium: AGG6-1..AGG6-9. Remaining Low/Info.

## Findings

| ID | Sev / Conf | Sources (agree) | Summary | Primary cite |
|---|---|---|---|---|
| AGG6-1 | Medium / High | CR6-1, RG6-1, CT6-1, DB6-1, FD6-1, PR6-2 (6) | AGG5-30 incomplete: an exhausted codec walk returns `CodecInventory.EMPTY`, the VM applies it as device truth (clears pending intent, `encoderInventoryLoaded = true`), narrows and PERSISTS HEIF→JPEG and HLG/log→SDR, REC has no candidates for the VM's life | `video/EncoderCaps.kt:123-150`; `ui/CameraViewModel.kt:3043-3093` |
| AGG6-2 | Medium / High | AR6-1, CR6-2, CT6-4, TR6-1 (4) | MRG5-8 half-closed: recall rollback restores only the VM stab/fps REQUEST; Engine `videoStabMode`/`videoFrameRate` and the displayed values keep the bank's — wire/UI/persisted split three ways; re-picking the shown mode is a no-op | `ui/CameraViewModel.kt:1165-1177, 1728, 1748, 3431-3471` |
| AGG6-3 | Medium / High | CR6-6, RG6-5, CT6-6, DB6-2, FD6-2, PR6-3, TE6-3, TR6-2, PR6-1 (9) | AGG5-2/MRG5-5 residual: `producerTerminalIds` is an oldest-first 32-entry set used as proof of terminality; a failed delete marker for an evicted (or never-registered) id is read as "live" and closes still admission until Engine release. PR6-1: republish decision read before the mark (publication stays false while admission is true) | `camera/RetainedStillDeletionOwner.kt:67, 112-135`; `camera/CameraEngine.kt:8392` |
| AGG6-4 | Medium / High | VF6-2, CT6-5, RG6-7 (3) | FrameGap evidence: `FrameGapAccumulator` zeroes counts before the log gate, so a refused periodic summary loses stalls and the terminal row covers only the last window (or is absent at count 0); the 12-row evidence reserve is shared with StartupTrace so repeated cold starts starve the terminal FrameGap row | `camera/DiagnosticTelemetry.kt:213-241`; `gl/GlPipeline.kt:902-920` |
| AGG6-5 | Medium / High | DS6-1, VF6-3, DS6-3 (3) | FIELD_CHECKS claims to be exhaustive but lacks cycle-5 PENDING DEVICE items A1.4, A1.11, A2.1, A2.4; A1.6/B.4 filed HOST-ONLY vs plan PENDING DEVICE | `docs/FIELD_CHECKS.md:3-12, 577-597` |
| AGG6-6 | Medium / Medium | VF6-1, DB6-3, TR6-8, PR6-6, TR6-7, RG6-10, TE6-1 (7) | AGG5-3 residuals in `VideoRecorder`: audio-encoder setup failure stops a possibly errored AAC codec through unclassified `nativeCleanup` (degrade → quarantine); a Stop racing an async codec error throws before the drain thread latches; `*CodecErrorLatched` set by ANY thread exit (mic read fault, muxer throw) so a real native `stop()` failure is skipped; tests re-implement the wiring (revert stays green) | `video/VideoRecorder.kt:218-232, 466-478, 606-607, 668-673, 810-818` |
| AGG6-7 | Medium / High | UX6-1 | REC refusal `MICROPHONE_BUSY` and mic-outcome lines are not classed as RESPONSES and are dropped under the failure/retained-take line that triggers them; the REC press looks inert | `camera/CameraStatus.kt:314-333, 459-462`; `camera/CameraEngine.kt:6547, 6619` |
| AGG6-8 | Medium / Medium | CR6-3 | AGG5-49's `!standaloneRoute -> 1f` keys on the mode/RAW law, not the actual route: on a rear setup with no logical multicamera Photo presets land on standalone lenses and the readout says 3.0× over a 9× crop (AGG5-38 sibling) | `ui/ZoomMath.kt:65`; `camera/CameraEngine.kt:4272-4280` |
| AGG6-9 | Medium / High | CT6-2, UX6-5 (2) | AGG5-55 regression/incomplete: ordinary reopens (DNG, aspect, fps, lens, mode) publish no PROGRESS so the Output row says nothing during exactly the reopen it was written for; the focal rail TalkBack state and Custom WB caption still say "reconfiguring" for cold start and the terminal reopen-the-app state | `ui/controls/PhotoFormatChips.kt`; `ui/CameraScreenPolicy.kt:695`; `ui/CameraScreen.kt:2986`; `ui/controls/ProSheet.kt:1116-1119` |
| AGG6-10 | Low-Medium / Medium-High | CT6-3, UX6-2 (2) | `CAMERA_RECONFIGURING` used as a one-shot refusal but has timer-less PROGRESS lifecycle; after a terminal `*_REOPEN` or a late recorder refusal it sticks on the plate indefinitely | `camera/CameraEngine.kt:6308, 6535, 6636`; `camera/CameraStatus.kt:203-210` |
| AGG6-11 | Low-Medium / High | AR6-2 | Recall rollback keyed on the recall's begin generation: a newer door that supersedes the recall and then fails restores the pre-recall Engine baseline but drops the VM restore record (AGG5-23/24, MRG5-8 return via supersession) | `ui/CameraViewModel.kt` recall rollback |
| AGG6-12 | Low / Medium | AR6-3, CR6-5, VF6-4 (3) | Route-inventory fold (A2.9) resets zoom on the setup thread but POSTS glide invalidation; never cancels the throttled `pendingControls`; a queued pinch compounds from the old scale; post cancels `zoomInteractionEnd`/quiet landing without telling the Engine (interaction stuck active) | `ui/CameraViewModel.kt:876-892, 2650-2657` |
| AGG6-13 | Low / High | CR6-4 | RAW-loss facts ride only `commitOpticsReady`; Ready from `handlePreviewReady` (cold start, resume, preview recovery) has `rawLoss = null` so the drop-RAW notice is never announced there | `camera/CameraEngine.kt:870-896, 2395-2418`; `ui/CameraViewModel.kt:1009` |
| AGG6-14 | Low / High | CR6-7, RG6-9, SR6-3, TE6-2 (4) | Passthrough privacy strip (AGG5-51) fails open: composition failure / refused splice writes HAL bytes verbatim; non-Exif APP1 (XMP) and `TAG_XMP`/free-text tags kept. Dormant on PMA110 | `capture/StillCapturePipeline.kt:391-424, 866-904` |
| AGG6-15 | Low / High | RG6-11, TR6-4 (2) | `restoreRecordAudioFromGrant` lacks the `rejectIfRecording` guard: a grant seen on resume mid-take flips `recordAudio = true` over a silent take; meter shows audio on | `ui/CameraViewModel.kt:2860-2871`; `MainActivity.kt:961` |
| AGG6-16 | Low / Medium | RG6-6, TR6-5 (2) | AGG5-23 incomplete when the encoder inventory lands between a pre-inventory recall and its rollback: `applyEncoderInventory` consumes the armed bank codec/transfer/DNG; rollback restores neither | `ui/CameraViewModel.kt:1083-1085, 1156-1166, 3058-3095` |
| AGG6-17 | Low / High | RG6-2 | Paused input-ready branch enumerates the lens inventory before route resolution; an external-only device pins `LensInventory.ALL` for the process | `camera/CameraEngine.kt` input-ready callback (2d0855c6) |
| AGG6-18 | Low / Medium | RG6-3 | Replayed cold start loses its StartupTrace: resume's armed owner leaks at the `glInputPending` return; the GL continuation carries the owner pause revoked | `camera/CameraEngine.kt` resume (2d0855c6) |
| AGG6-19 | Low / Low | RG6-4 | Resume rebind (missing input) plus resume's own reopen can both converge one generation (possible double open) | `camera/CameraEngine.kt` resume |
| AGG6-20 | Low / Medium | TR6-3 | Cold-start task reads `paused` outside the monitor then clears `starting`; a `resume()` between the two is refused by the still-true `starting` and nothing restarts → black viewfinder until the next surface event | `camera/CameraEngine.kt:1974-1998, 7838-7843` |
| AGG6-21 | Low / High | RG6-8, PR6-5 (2) | Once-per-process debug capability dump (~25–40 rows) spends the shared 168-row budget and latches before admission | `camera/VendorTagInspector.kt` (adda4175) |
| AGG6-22 | Low / Medium | DB6-4 | A throw in Engine `onPhoto` after the processed save is queued runs `onError` → `finishProcessed()` while the save still runs (family settles early) | `camera/CameraEngine.kt` onPhoto/onError |
| AGG6-23 | Low / Medium | DB6-5 | AGG5-45 partial: BURST/AEB head refused synchronously by the DNG allocator (OVERFLOW/SHUTDOWN) still returns true via `StillContinuationHandoff.dispatchResult`; chain walks the remaining shots | `camera/CameraEngine.kt` BURST/AEB dispatch |
| AGG6-24 | Low / Medium | TR6-6 | DB5-13 worker recheck `isRetired()` is check-then-act; a cancel after the check lets an insert run without a rejected-cleanup reservation (fails closed into launch recovery) | `camera/DngPreCaptureAllocation.kt:148-160` |
| AGG6-25 | Low / High | UX6-3 | Zoom readouts show "1.0×" during the reopen after Photo→Video or DNG-on: multiplier mixes the NEW route intent with the OUTGOING camera's caps | `ui/ZoomMath.kt:91-104`; `ui/CameraViewModel.kt:2816, 3467` |
| AGG6-26 | Low / High | UX6-4 | Drop-RAW rung with DNG-only request: HEIF stand-in chip enabled+checked but tap is a no-op | `ui/controls/PhotoFormatChips.kt:96-97`; `camera/CameraState.kt:1317-1333` |
| AGG6-27 | Low / Medium | UX6-6 | Per-press `PROCESSED_STILL_UNAVAILABLE_DNG_ONLY` still fires every shot though the Ready edge announces it | `camera/CameraEngine.kt:5210-5213` |
| AGG6-28 | Low / Medium | PR6-4 | AGG5-4 request/wire split: `reconcileFrameRate` pushes narrowed/restored fps via `setControls` on every route change (FULL_REBUILD ~180 ms after Ready) | `ui/CameraViewModel.kt` reconcileFrameRate |
| AGG6-29 | Info / High | CR6-8 | Rebinding one key ends the momentary hold of another key bound to the same action that is still held | `ui/CameraViewModel.kt:4055-4062` |
| AGG6-30 | Low / High | TE6-4, TE6-5, TE6-6, TE6-7, TE6-8, QA6-2, RG6-12 (7) | Cycle-5 fixes whose call-site revert stays green: AGG5-29 (+ `touchAfActive`/`lastFocusDistance` still live), AGG5-28 overrides, AGG5-41 call site, AGG5-39 Engine publish, MRG5-6 race, AGG5-9 budget 180; reflection-driven DNG invalidation test | see `test-engineer.md` |
| AGG6-31 | Info / Medium | TE6-9, TE6-10 | Sleep-window negatives (50–100 ms) and a 250 ms positive deadline can flake | see `test-engineer.md` |
| AGG6-32 | Low / High | SR6-1, SR6-2, SR6-4, SR6-5 (release tooling) | Release export trusts ambient git config/env (filters, hooks, attributes, `GIT_*`); Kotlin compile daemon not covered by `--no-daemon`; `org.gradle.logging.level` any value allowed; distribution `lib/` jars not re-verified | `tools/build_immutable_release.py` |
| AGG6-33 | Info / High | SR6-7, RG6-13 (2) | Secret-fact scan skips non-UTF-8 files; release seal hard-fails on common `JAVA_TOOL_OPTIONS=-Dfile.encoding` with no remedy | `tools/check_docs.py`; `tools/build_immutable_release.py` |
| AGG6-34 | Info / High | SR6-6 | No output-file check proves a saved DNG carries no serial/unique-id tag | device |
| AGG6-35 | Info / High | DB6-6 | YUV still path grows an unsized `ByteArrayOutputStream` + `toByteArray()` (~2× encoded size live) | `capture/StillSnapshot.kt:55-63` |
| AGG6-36 | Low / High | DS6-2, DS6-4, DS6-5, DS6-6, VF6-5, VF6-6, VF6-8 (7) | Doc drift: passthrough lane "bytes verbatim" (now composed + stripped); dead `[letter]`/`[label]` KDoc links; display-zoom law undocumented; HeifExif row / "re-stamped after compress"; U+2019 glyph list; VendorTagInspector description; GlPipeline FrameGap comment; 3A "201 rows" > 168 pool | `docs/ARCHITECTURE.md:99-100, 683, 1077`; `CLAUDE.md`; `camera/ManualControls.kt:300-304`; `gl/GlPipeline.kt` |
| AGG6-37 | Info / High | AR6-4, AR6-5, AR6-6, VF6-7 (4) | Lexical gate anchor in check_docs quota scan; tested-but-unused seams (`StatusPlate.clearProgress`, `recallMemorySlot`, `lastAppliedRecall`); god-object growth; remaining fix-off defaults (`mapTapFocusGeometry`, `CameraController.open`) | `tools/check_docs.py`; `camera/CameraStatus.kt:486-493`; `ui/CameraViewModel.kt:113-169` |
| AGG6-38 | Medium (process) / High | QA6-1 | Release lint not exercised at HEAD (dirty tree from the archive move); must run on the clean committed tip | gate log |
| AGG6-39 | Info / High | QA6-3, QA6-4, QA6-5 | UsableSpace lint warning (deferred AGG4-77); Gradle-10 deprecation from AGP internals only; cached compiles hide warnings (allWarningsAsErrors already set by AGG4-76) | gate log |

## AGENT FAILURES

None. All 14 reviewer lanes (general-purpose agents) returned and wrote their files; fd and qa ran as
general-purpose agents per the run's process-hygiene note. QA baseline gate: exit 0, 2595 tests /
0 failures, lint 0 errors / 1 deferred warning, Partition A 99.87%, release lint SKIPPED (dirty tree).
