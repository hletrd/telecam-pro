# RPL cycle 5 aggregate — 2026-10-02 (HEAD ea7d4374)

Sources (this cycle, `.context/reviews/`): `code-reviewer.md` (CR5), `perf-reviewer.md` (PR5),
`security-reviewer.md` (SR5), `critic.md` (CT5), `verifier.md` (VF5), `test-engineer.md` (TE5),
`tracer.md` (TR5), `architect.md` (AR5), `debugger.md` (DB5), `document-specialist.md` (DS5),
`designer.md` (UX5), `c4-regression-reviewer.md` (RG5), `fd-code-reviewer.md` (FD5, rerun),
`qa-adversary.md` (QA5, gate run by the orchestrator). Previous cycle archived in
`archive-rpl-cycle4-2026-10-02/`.

Deduplicated: every row keeps the HIGHEST severity/confidence any reviewer gave it. Cross-agent
agreement is noted (`agree: n`) and raises priority. Owner-decided items in CLAUDE.md are not
re-raised. Items already tracked in the cycle-4 plan are listed only where a reviewer added new
evidence.

## Severity summary

0 Critical. 1 High (AGG5-1). Medium: AGG5-2..AGG5-14. Remaining Low/Info.

## Findings

| ID | Sev / Conf | Sources (agree) | Summary | Primary cite |
|---|---|---|---|---|
| AGG5-1 | High / High | TR5-1 | A pause between cold GL start and the preview bind (or before the GL input-ready callback runs) is never replayed by `resume()`: black viewfinder for the process, or input-ready setup (analysis callback → app-side AE, scopes, renderer replay, log transfer, lens inventory) skipped forever | `camera/CameraEngine.kt:1963-2041, 2279-2283, 7671-7707, 4331-4334` |
| AGG5-2 | Medium / High | DB5-3, TR5-4 (2) | One failed family-delete durability latches `deletionJournalUnavailable` for the Engine's life: still capture dead until process restart | `camera/RetainedStillDeletionOwner.kt:56,101-111,244-246` |
| AGG5-3 | Medium / Medium-High | DB5-1 | A MediaCodec in the Error state makes `signalEndOfInputStream`/`stop` throw inside `nativeCleanup`, which is read as "native release unproven" → process quarantine; the AudioRecord is not stopped first | `video/VideoRecorder.kt:190-200, 405-414, 532-541, 308-314` |
| AGG5-4 | Medium / High | TR5-3 | Caps reconciliation on routes that never record (Photo logical, FRONT) narrows and PERSISTS the operator's video stabilization and frame rate request | `ui/CameraViewModel.kt:826-833, 3160-3214` |
| AGG5-5 | Medium / High | PR5-1 (+DB5-13, TE5-12, TR5-15) | `invalidateCameraReady` cancels DNG pre-capture owners before clearing Ready; the cancel's settle continues BURST/AEB onto the dying session and a new DNG allocation escapes the cancel. Related: cancel/start window (DB5-13), admission publish outside lock (TE5-12), RAW-only chain refused by its own still-held admission (TR5-15) | `camera/CameraEngine.kt:550-566, 5088-5099, 5193-5285`; `camera/DngPreCaptureAllocation.kt:50-64,122-186` |
| AGG5-6 | Medium / Medium | PR5-2 | A poisoned orphan EGLSurface whose destroy keeps failing makes every later preview/encoder detach throw; a healthy recording is failed and preview retries are spent on the orphan | `gl/GlPipeline.kt:495-535, 836-865, 2086-2091` |
| AGG5-7 | Medium / Medium | CT5-2, TE5-1, DB5-9, RG5-11, CT5-9 (5) | A durably COMPLETE row with provider SIZE ≤ 0 and an INDETERMINATE probe is kept but never re-armed → expires; CLAUDE.md "adopts durable COMPLETE rows" no longer unconditional | `storage/MediaStoreWriter.kt:1428-1441, 2911-2943` |
| AGG5-8 | Medium / Medium | CT5-1, RG5-16 (2) | Moov-present take whose extractor throws once (documented transient FUSE class) is kept without re-arm and can expire | `storage/MediaStoreWriter.kt` `recoveryVideoVerdict`, `keptRowReassertsPending` |
| AGG5-9 | Medium / High | VF5-1, VF5-6 | ~28 debug producers charge the 180-row recurring log budget twice (pre-gate + aliased `DiagnosticLog`), and the last row is spent with nothing logged; FrameGap/StartupTrace have no reserve despite KDoc | `camera/DiagnosticTelemetry.kt:8-11,58-108` + call sites |
| AGG5-10 | Medium / High | UX5-1 | Every FRONT (or other structurally RAW-less route) shot with a DNG request raises a 6 s ERROR `RAW_UNAVAILABLE` (assertive), which also blocks lower-rank statuses | `camera/CameraEngine.kt:5116-5127` |
| AGG5-11 | Medium / High | UX5-2, RG5-6, CT5-7, RG5-7, TE5-10 (5) | Status plate rank arbitration drops responses to the operator's own action (refusals, `DELETED`, `VIDEO_SAVED`, `MEMORY_SLOT_EMPTY`) and lets a stale event outlive its own resolution; ERROR-severity PROGRESS is deferred behind any event; condition-ending is keyed by message; DNG-only edge latches even when dropped | `camera/CameraStatus.kt` `StatusPlate`; `ui/CameraViewModel.kt:932-959,1811-1907` |
| AGG5-12 | Medium / High | DB5-8, UX5-3 (2) | Shoot-tab Zoom slider still reads lens-local 1.0× on standalone routes while chip/ruler/HUD read 3.0× (AGG4-73 incomplete) | `ui/controls/ProSheet.kt:934-963` |
| AGG5-13 | Medium / High | RG5-1 | OWNER DECISION: A.20 changes PMA110 photo-P handheld shutter (−1..−2.2 EV at TC 2–4.6×; ±0.025 EV at presets) and the focus-detail gate; plan/FIELD_CHECKS A6 understate it | `ui/CameraViewModel.kt:4853-4877`; `camera/CameraState.kt:1846-1852, 2054-2062` |
| AGG5-14 | Medium / Medium | DB5-2 | 10-bit (HLG10-source) video converts BT.2020-primary samples with BT.709-input matrices (HLG desaturated; log gamuts wrong) | `gl/Shaders.kt:133-250` |
| AGG5-15 | Medium (tooling) / High | RG5-12, SR5-1 (2) | Sealed release refuses only `$GRADLE_USER_HOME/init.d`; `init.gradle(.kts)` and the distribution `init.d`/`gradle.properties` still apply | `tools/build_immutable_release.py:201-232` |
| AGG5-16 | Low / High | RG5-13, SR5-2 (2) | jvmargs filter is a denylist (`@argfile`, `-XX:On*`, `-Xbootclasspath`, `-Djava.system.class.loader`) | `tools/build_immutable_release.py:176-221` |
| AGG5-17 | Low / High | SR5-3 | keytool child and Python wrappers inherit full ambient env (`JAVA_TOOL_OPTIONS`, `PYTHONPATH`) | `tools/build_immutable_release.py:781-891`; `tools/run_scoped_signed_release.py:82-98` |
| AGG5-18 | Low / High | RG5-14, SR5-4 (2) | Secret-fact scan still skips `.kt`, `.xml`, `.html`, `.yml`, `.sh`, `.json`, `.properties`, untracked-tree markdown | `tools/check_docs.py:1585-1695` |
| AGG5-19 | Low / High | RG5-15, TE5-20 (2) | Release lint silently skipped on a dirty tree (exit 0) | `tools/verify_host.py:159-167` |
| AGG5-20 | Low-Medium / High | CR5-2, RG5-5, DB5-5, TR5-12 (4) | Focus-ruler punch-in assist and momentary PUNCH_IN hold / explicit toggle / recall snapshot each other; ruler close turns off operator-owned punch-in; loupe latched and persisted | `ui/CameraViewModel.kt:3332-3366, 3440-3447`; `ui/controls/ManualDials.kt:255-264` |
| AGG5-21 | Low / High | RG5-8, CT5-3, DB5-7, TR5-9 (4) | A.4 regression: recall of a denial-silent bank with the mic granted clears the MR slot just lit and forces a save | `AudioDenialReason.kt:61-86`; `MainActivity.kt:1059-1066`; `ui/CameraViewModel.kt:2624-2629` |
| AGG5-22 | Low / High | CT5-4, TE5-4 (2) | Recall-side audio provenance lives only in the Activity wrapper and is untested | `MainActivity.kt:521-537` |
| AGG5-23 | Low-Medium / High | CR5-1, TE5-3, TR5-10 (3) | Async optics rollback of a pre-inventory recall leaves pending codec/transfer/HEIF-JPEG armed → persisted and applied | `ui/CameraViewModel.kt:963-1050, 1516-1521, 1660-1708, 2816-2846` |
| AGG5-24 | Low / Medium-High | TR5-11 | Rollback after a hold-cancelling recall re-latches `aeLock=true` and persists it | `ui/CameraViewModel.kt:1532-1533, 1007, 1757` |
| AGG5-25 | Low / Medium | CR5-3 | `reconfigureCamera` GL-input-missing branch still parks a bare door Not-Ready (AGG4-2 sibling) | `camera/CameraEngine.kt:4331-4346` |
| AGG5-26 | Low-Medium / Medium | TR5-2 | Preview-recovery rebind dropped while paused is never replayed on resume | `camera/CameraEngine.kt:2394-2428, 7671-7707` |
| AGG5-27 | Low / Medium | RG5-10, PR5-3 (2) | `RejectedOutputCleanupCapacityOwner` queue/permit mismatch (AGG4-26 sibling) | `storage/MediaStoreWriter.kt:2133-2201` |
| AGG5-28 | Low-Medium / High | DB5-4 | `onCaptureBufferLost`/`onCaptureSequenceAborted` not observed: lost still buffer wedges shutter for the 8–12 s watchdog | `camera/CameraController.kt:2146-2218` |
| AGG5-29 | Low / High | DB5-6 | Still built from frozen controls takes AF mode/lock override from live controls | `camera/CameraController.kt:2049-2126, 1780-1793` |
| AGG5-30 | Low / High | DB5-10 | One failed `MediaCodecList` scan latches an EMPTY encoder inventory for the process | `video/EncoderCaps.kt:96-116` |
| AGG5-31 | Low / High | DB5-11 | `VideoRecorder.start` refuses empty/mixed candidates with no log line | `video/VideoRecorder.kt:233-237` |
| AGG5-32 | Low / High | DB5-12 | `isoStops`/`shutterStops` loop ~2^31 when an advertised lower bound is 0 (ANR in composition) | `ui/controls/ManualDials.kt:1127-1158` |
| AGG5-33 | Low / Medium | DB5-14, TR5-17, RG5-9 (3) | Route-inventory fold: zoom reset without glide hygiene; stale setup-thread route snapshot; Engine resets FRONT/EXTERNAL zoom while VM keeps it | `ui/CameraViewModel.kt:795-805, 4524-4545`; `camera/CameraEngine.kt:1507-1735` |
| AGG5-34 | Low / High | PR5-4, DB5-15 (2) | JPEG splice encodes into unsized `ByteArrayOutputStream` (memory) | `capture/StillCapturePipeline.kt:313-325` |
| AGG5-35 | Low / High | PR5-5 | HEIF+JPEG shot composes identical EXIF APP1 twice | `capture/StillCapturePipeline.kt:273,323,732-768` |
| AGG5-36 | Low / Medium | DB5-17, TE5-19, CT5-8, RG5-4 (4) | Aspect-first YUV pick uses exact equality: can choose a much smaller still on odd arrays; FRONT size unmeasured | `camera/CaptureCapabilities.kt:347-351, 655-672` |
| AGG5-37 | Low / Medium | DB5-16 | Debug capability dump spends the reserved 120-row warning budget per Engine start | `camera/VendorTagInspector.kt` |
| AGG5-38 | Medium / High | TR5-5 | Multi-lens device with no logical back camera: Photo opens a standalone lens while zoom stays unified ("208 mm" class) | `camera/CameraEngine.kt:4197-4205`; `camera/CameraState.kt:547-556` |
| AGG5-39 | Low-Medium / High | TR5-6 | In-REC snapshot advances the presentation gate so the clip's FAILED/RETAINED/SAVED status and review registration are dropped | `camera/CameraEngine.kt:5652-5656, 7519-7528`; `camera/RecordingStorageDispatcher.kt:187-195` |
| AGG5-40 | Low-Medium / Medium-High | TR5-7 | VM/Engine zoom-interaction state diverge across a remap door → per-tick 180 ms submits or stuck wide-aim/boost | `ui/ZoomGlideState.kt:88-94`; `camera/CameraEngine.kt:2450,4796-4806,3843-3858` |
| AGG5-41 | Low-Medium / High | TR5-8 | Processed-only still discarded when characteristics read fails (only DNG needs it) | `camera/CameraController.kt:2308-2311` |
| AGG5-42 | Low / Medium | TR5-13 | A latched Stop can be absorbed by a racing REC press; published recorder unstoppable | `camera/CameraEngine.kt:6098-6120, 6858-6861`; `camera/RecordingAdmissionLatch.kt` |
| AGG5-43 | Low / Medium-High | TR5-14 | Standby meter stays off after a Stop-latched admission fails | `camera/StandbyAudioController.kt:591-608`; `camera/CameraEngine.kt:6438,6831` |
| AGG5-44 | Low / Medium | TR5-16 | Zoom flush between Engine rollback and VM rollback post writes the failed scale | `camera/CameraEngine.kt:1120,1266,4782-4806` |
| AGG5-45 | Info / Medium | TR5-18 | BURST/AEB `capturePhoto` returns true on a refused head | `camera/CameraEngine.kt:5177-5185` |
| AGG5-46 | Low / Medium | CT5-5 | EXIF ExposureBias read from a result key app-side AE never carries → always 0 | `camera/CameraEngine.kt:~8142` |
| AGG5-47 | Low / High | AR5-1 | Engine lens band never follows seamless zoom; rollbacks publish a stale band; predicate in 3 copies | `camera/CameraEngine.kt:600-614,759-764,4764-4863`; `ui/CameraViewModel.kt:2334-2343,3194-3200` |
| AGG5-48 | Low / High | AR5-2 | Two "unified zoom" formulas (UI vs `pushTeleFinder`) disagree on lens-local routes and RAW law source | `camera/CameraState.kt:590-602`; `camera/CameraEngine.kt:7824-7838` |
| AGG5-49 | Low / High | RG5-2, CT5-6 (2) | Zoom display multiplier divides by nominal 23 mm on logical route (ruler/chip read 3.1× at 3×) and on tablets | `ui/ZoomMath.kt:38-49` |
| AGG5-50 | Medium / High | TE5-2, AR5-4 (2) | Fix-off defaults remain on safety seams (`previewExposureCap=false`, `sessionAttemptPlan` flags, ZoomMath optical defaults, teleFinder zoom default) | `camera/ManualControls.kt:672-682,1024`; `camera/CameraController.kt:2795-2808`; `ui/ZoomMath.kt:387-533` |
| AGG5-51 | Low / Medium | SR5-5 | Passthrough JPEG keeps HAL EXIF (GPS/serial/MakerNote) without a strip (dormant on PMA110) | `capture/StillCapturePipeline.kt:402-424, 732-797` |
| AGG5-52 | Low / Medium | UX5-4 | Ready-state "Switching to a single lens for RAW" caption is a false claim on a RAW-less settled session | `ui/controls/PhotoFormatChips.kt:78-90` |
| AGG5-53 | Low / High | UX5-5 | DNG-only stand-in hard-codes HEIF even without a HEVC encoder | `camera/CameraState.kt:1517-1534` |
| AGG5-54 | Low / High | UX5-6 | Tapping JPEG beside a HEIF stand-in moves the selection | `ui/controls/PhotoFormatChips.kt:43-50`; `camera/CameraState.kt:1314-1330` |
| AGG5-55 | Low / Medium | UX5-7 | Output row says "reconfiguring" for any not-Ready state incl. terminal failure | `ui/controls/PhotoFormatChips.kt:60-79` |
| AGG5-56 | Low / Low | UX5-8 | Exposure-meter stateDescription chatter under TalkBack | `ui/CameraScreen.kt:2758-2787` |
| AGG5-57 | Medium / High | DS5-1 | FIELD_CHECKS claims exhaustive but lacks entries for 8 cycle-4 PENDING DEVICE items | `docs/FIELD_CHECKS.md` |
| AGG5-58 | Low / High | DS5-2, DB5-18 (2) | Stale `FinalizedRecordingValidation` KDoc (pre-AGG4-3 rule) | `video/VideoRecorder.kt:2380-2385` |
| AGG5-59 | Low / High | DS5-3 | Dead KDoc link `StillCapturePipeline.applyExifAttributes` | `capture/StillCapturePipeline.kt:632` |
| AGG5-60 | Low / High | DS5-4 | README "the one exception" vs two sanctioned model-string seams | `README.md:100-103` |
| AGG5-61 | Low / High | DS5-5 | Local (gitignored) Play listing RAW claim + stale v1.0.2 notes | `docs/play-store-listing.md` |
| AGG5-62 | Info | DS5-7, DS5-8, VF5-2, VF5-3, VF5-4, VF5-5 | Doc drift: targetSdk reason; gate comment; FocusConfidence heartbeat 15 s/3 s; FrameGap strict `>200`; "Five rules" lists six; AE deadband comment | CLAUDE.md, `camera/AutoExposure.kt:31` |
| AGG5-63 | Low-Medium / High | TE5-5, TE5-6, TE5-7, TE5-8, TE5-9, TE5-13, TE5-16 | Cycle-4 fixes whose call-site revert stays green (bare-door call sites, settle lambda, JPEG lanes + tautological parity test, recovery VIDEO branch, chain chars, AGG4-8/14/70/73 call sites, second applyLoaded exit) | see `test-engineer.md` |
| AGG5-64 | Low / High | TE5-14 | AGG4-35 interleaving test proves its negative with `Thread.sleep(100)` | `app/src/test/.../ProcessAdmissionSignalTest.kt:51-52,108-111` |
| AGG5-65 | Low / Medium | TE5-11 | Momentary hold leaks if the key is rebound mid-hold; two keys share one hold | `ui/CameraViewModel.kt:228-229` |
| AGG5-66 | Low / Medium | TE5-15 | `MICROPHONE_BUSY` reported for an audio-off take | `camera/CameraEngine.kt:6383-6390` |
| AGG5-67 | Low / Medium | TE5-17 | AGP task-prefix pin skips instead of failing | `tools/tests/test_upload_key_policy.py:166-171` |
| AGG5-68 | Low / Medium | TE5-18 | Residual fingerprint covers only cited lines | `tools/coverage/partition_report.py:93-100` |
| AGG5-69 | Info | TE5-21, TE5-22, TE5-23, TE5-24 | "+0.0" vs "±0.0"; isoStops snaps outside ladder; bitrate KDoc; reflective queue count | see `test-engineer.md` |
| AGG5-71 | Low / Medium | FD5-1 (+FD5-2 = AGG5-34) | Recovery JPEG tail probe (`FF D9` only) can adopt a header-only passthrough file whose APP1 ends in the thumbnail EOI; require an SOS before the tail | `storage/MediaStoreWriter.kt:1832-1847`; `capture/HeifExif.kt` `writeJpegWithExifApp1` |
| AGG5-70 | Info | CR5-4, VF5-7, PR5-6, AR5-3, AR5-5, RG5-3, RG5-17, DB5-18 rest | Chain chars KDoc wording; zero-length audio read spin; GyroEis on main looper; EXIF focal formula duplicated; converter declaration refuses on FRONT; A.19 wording; stacked-KDoc scan misses `//` separators; journal-UNAVAILABLE re-arm per retry; gyro gap interpolation | see sources |

## Already tracked, new evidence only

- AGG-12 (app-side AE lock): CR5 notes the A.24 momentary AEL is a no-op on app-side routes.
- MRG4-9 residual: confirmed `require(width > 0)` path (CR5).
- AGG4-13: TR5-3 adds the cross-route persistence angle (scheduled under AGG5-4).
- AGG4-30: TE5-23/TR5 note `bitrateLevel` still live.
- AGG4-10: TR5 notes trailing setOpenGate/HiRes/Aspect not undone by rollback.
- `MemoryBankAudioProvenance` deslop note: DS5-6 (ARCHITECTURE row).

## AGENT FAILURES

- `fd-code-reviewer` (feature-dev subagent) and `qa-adversary` (first launch) stalled without returning,
  as in cycles 1, 2 and 4. Re-run once: fd as a general-purpose agent writing its own file; QA gate run
  directly by the orchestrator (`qa-adversary.md`). fd rerun returned FD5-1/FD5-2 (folded into AGG5-71 and AGG5-34). QA gate: exit 0, 2507 tests, lint 0 errors / 1 deferred warning, release lint skipped on the dirty tree (QA5-1 = AGG5-19).
