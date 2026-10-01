# RPL cycle 4 — aggregate review (HEAD 14767b0a, 2026-10-02)

Sources (one file per agent, kept for provenance): `code-reviewer.md` (CR4), `perf-reviewer.md`
(PERF4), `security-reviewer.md` (SEC4), `critic.md` (CRIT4), `verifier.md` (VER4),
`test-engineer.md` (TE4), `tracer.md` (TR4), `architect.md` (ARCH4), `debugger.md` (DBG4),
`document-specialist.md` (DOC4), `designer.md` (DES4), `c3-regression-reviewer.md` (REG4, the
explicit re-check of cycle-3 commits `e3a2bdd4..887d39fb`), `fd-code-reviewer.md` (FD4),
`qa-adversary.md` (QA4, static gates only).

Dedupe rule: overlapping findings are merged under one AGG4 id with the HIGHEST severity /
confidence any duplicate carried. "Agents" counts independent reporters (cross-agent agreement =
higher signal). Orchestrator spot-checks are marked **[orch]**.

## A. Correctness / data-loss (highest priority)

| ID | Finding | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG4-1 | VM writes the engine-owned `stillCaptureAdmissionAvailable = false` on an UNRESOLVED late-output discard; the engine's change-gated publication never re-sends `true`, so the photo shutter and hardware shutter stay dead for the life of the ViewModel **[orch: confirmed at `CameraViewModel.kt:4058-4078`]** | Medium / High | CR4-1, PERF4-2, DES4-3 (3) | `ui/CameraViewModel.kt:4058-4078`; `camera/DngPreCaptureAllocation.kt:26-44` |
| AGG4-2 | Bare `reopenForSession()` doors (stab, fps/high-speed, hi-res/aspect, video size, camera-error recovery) end in a parked Not-Ready over a still-streaming controller after a transient preflight failure: no retry, "camera unchanged" copy, preview sized for a never-configured stream (cycle-3 c5bfd1c8 reopened AGG2-4) | Medium / High | CR4-3, CRIT4-2, REG4-1, VER4-2 (4) | `camera/CameraEngine.kt:963-1005, 1142-1153, 4255-4295, 8562-8565` |
| AGG4-3 | Live muxer-stop tail deletes a take on two extractor throws; the premise "stop throw ⇒ moov never written" is false for the only (tolerated, empty-audio-track) path that reaches it, and the retry reuses the same fd | Medium / Medium | TR4-3, DBG4-1, TE4-4, CRIT4-5, CR4-7 (5) | `storage/MediaStoreWriter.kt:1747-1772, 2685-2727`; `video/VideoRecorder.kt:451-475, 2050-2052` |
| AGG4-4 | `reassertPending` re-arms pending expiry for rows that can never be judged (crash-truncated MP4 without moov, unknown MIME, undecidable HEIF), making them permanent hidden storage leaks | Medium / Medium | CRIT4-1, REG4-2, DBG4-2 (3) | `storage/MediaStoreWriter.kt:1128-1157, 1436-1443, 1713-1735, 2732-2747` |
| AGG4-5 | `reassertPending` is skipped on the `PUBLISH_FAILED` and journal-`UNAVAILABLE` keep paths, so a valid take whose publish keeps failing still expires | Low / High | VER4-5 | `storage/MediaStoreWriter.kt:1388-1396, 1422-1427` |
| AGG4-6 | A kill during the in-place JPEG EXIF rewrite leaves a misaligned file still ending `FF D9`; launch recovery's EOI-only probe adopts it as VALID (data-integrity extension of carried AGG3-24) | Medium / Medium | CR4-4, VER4-4 (2) | `capture/StillCapturePipeline.kt:320-375, 470-486`; `storage/MediaStoreWriter.kt:1791-1807` |
| AGG4-7 | A complete DNG of a mixed HEIF/JPEG+DNG shot is withheld (retained-take path) when its processed sibling was never queued (snapshot failure / dispatch rejection) | Medium / High | VER4-1 | `camera/CameraEngine.kt:5705-5790`; `camera/StillPublicationDispatcher.kt:28-76` |
| AGG4-8 | Same-camera Video MR recall publishes the recalled resolution but keeps streaming and recording the old `videoSize` (fast commit never re-picks) | Medium / High | CR4-2 | `camera/CameraEngine.kt:2860, 2899-2945, 899-944, 7887-7894`; `ui/CameraViewModel.kt:1484-1485, 1579` |
| AGG4-9 | Recalling a denial-silent MR bank while the mic is ALREADY granted records silent clips until a later `onResume`, then flips audio on with an unprompted status | Low-Medium / High | CR4-8, CRIT4-3, TR4-2, DBG4-3 (4) | `MainActivity.kt:530-548, 985-1003`; `CameraPermissionPolicy.kt:95-129` |
| AGG4-10 | Failed MR recall rolls back optics but leaves `activeMemorySlot` lit, the bank's non-optics half applied and persisted, and the denial reason rewritten | Low-Medium / High | TR4-1 | `ui/CameraViewModel.kt:935-1015, 1291-1596`; `MainActivity.kt:538-546` |
| AGG4-11 | `applyLoaded` arms `pending*UntilInventory` before its refusal exits; a refused recall's codec/transfer/formats (incl. DNG) apply later when the inventory lands | Low / Medium | CR4-10 | `ui/CameraViewModel.kt:1332-1337, 1389, 1480-1483` |
| AGG4-12 | Pseudo-ZSL admission ignores focus distance / lens MOVING / AF state, so a pre-MF-change or mid-AF-scan frame can be served (adds a dimension; does NOT widen the dark tolerance) | Medium / Medium | CR4-5 | `camera/ZslAdmission.kt:46-100`; `camera/CameraController.kt` ~1600-1645 |
| AGG4-13 | Video frame rates are offered and pinned with no per-size `getOutputMinFrameDuration` / encoder check; "gated against the selected size" exists only in docs | Medium / Medium | ARCH4-2 | `camera/CameraState.kt:1099-1124`; `camera/CaptureCapabilities.kt:238-239, 351-358` |
| AGG4-14 | App-side PROGRAM handheld rule and focus-detail exposure gate use preset nominal focal × converter, ignoring zoom and the measured focal the OSD/EXIF show | Medium / High | ARCH4-1 | `ui/CameraViewModel.kt:1872-1887, 4722-4729, 578, 4167` |
| AGG4-15 | Non-forced route-inventory republish (every resume / GL generation) resets VM FRONT/EXTERNAL zoom to 1× without touching the Engine; forced path writes Engine optics outside a transaction | Low-Medium / Medium | ARCH4-3 | `camera/CameraEngine.kt:1437-1479, 1547-1553`; `ui/CameraViewModel.kt:776-784, 4398-4414` |
| AGG4-16 | Rear-only optics refusal: VM and Engine feed the shared predicate different inputs (EXTERNAL with a back camera) → VM publishes an optimistic lens/TC change the Engine refuses | Low / High | ARCH4-4 | `ui/CameraViewModel.kt:1824-1828`; `camera/CameraEngine.kt:3858-3866` |
| AGG4-17 | DeviceProfile resolved independently by Engine and each controller; route-dependent still ceiling memoized in an id-keyed process caps cache | Low / Medium | ARCH4-5 | `camera/CameraEngine.kt:1272-1293, 2373-2375`; `camera/CameraController.kt:67-73` |
| AGG4-18 | Texture-acquisition failure branch does not orphan a preview EGL surface whose detach failed; retries re-bind the poisoned surface | Low-Medium / High | VER4-3 | `gl/GlPipeline.kt:419-427, 500-510, 980-993, 1209-1234` |
| AGG4-19 | 10-bit-boundary transfer change during REC is published but its session reopen is never owed | Low / Medium | CR4-9 | `camera/CameraEngine.kt:2999-3009, 5915` |
| AGG4-20 | Hardware-zoom ease ticker can be double-posted (two 30 Hz chains) | Low / Medium | CR4-11 | `ui/CameraViewModel.kt:439-463, 2172, 2302-2304, 2945` |
| AGG4-21 | `DngPreCaptureAllocation.cancel()` may read the unassigned `lateinit attempt`; the throw escapes `invalidateCameraReady()` before Ready is cleared | Low / Low-Medium | CR4-12 | `camera/DngPreCaptureAllocation.kt:98-104, 167`; `camera/CameraEngine.kt:549-551, 4978-4983` |
| AGG4-22 | EXIF `OffsetTime*` written as `"Z"` in UTC+0 (pattern `XXX`) | Low / High | CR4-13 | `capture/StillCapturePipeline.kt:709-715` |
| AGG4-23 | Leaving FRONT onto the logical photo route keeps a stale lens band | Low / Medium | CR4-14 | `ui/CameraViewModel.kt:2988-3010`; `camera/CameraEngine.kt:4013-4030` |
| AGG4-24 | YUV still size ignores the aspect-first rule JPEG uses | Low-Medium / Medium | CR4-6 | `camera/CaptureCapabilities.kt:341-345, 653-670` |
| AGG4-25 | Before the lens inventory lands, VM (`LensInventory.ALL`) and Engine (empty set) use different optical sets for every scale conversion | Low / High | TR4-6 | `camera/CameraState.kt:885, 1714`; `camera/CameraEngine.kt:1346, 1376` |
| AGG4-26 | Family-deletion dispatcher releases its permit before the worker dequeues → reserved submit rejected as FAILED | Low / Low-Medium | CR4-16 | `camera/FamilyDeletionMarkerDispatcher.kt`; `camera/CameraEngine.kt:5343-5350` |
| AGG4-27 | Dual-open supersession restore leaves controls/photoExposure/lensChoice normalized against abandoned caps | Low / Low | CR4-17 | `camera/CameraEngine.kt:4371-4378, 4515-4535` |
| AGG4-28 | Recovery treats provider `SIZE <= 0` as INVALID before probe/journal (and ADOPTs a zero-size COMPLETE) | Low / Low | VER4-6 | `storage/MediaStoreWriter.kt:1401-1403` |
| AGG4-29 | Refused mic claim retires the REC attempt with no status | Low / Medium | VER4-10 | `camera/CameraEngine.kt:6256-6259` |
| AGG4-30 | Frozen REC packet's diagnostic bitrate reads the live codec | Low / High | VER4-11 | `camera/CameraEngine.kt:6464, 7944-7945` |
| AGG4-31 | Failed JPEG EXIF re-stamp is silent (HEIF lane logs) | Low / High | VER4-8 | `capture/StillCapturePipeline.kt` ~323, ~372 |
| AGG4-32 | Audio/video PTS origins differ and overruns during muxer-start wait shift audio | Medium (if audible) / Low-Medium | CR4-15 | `video/VideoRecorder.kt:364-368, 830-901`; `gl/GlPipeline.kt:1253-1254` |
| AGG4-33 | Cold launch in Video with a remembered non-SDR curve configures SDR first, then reopens for 10-bit | Low / Medium | TR4-7 | `ui/CameraViewModel.kt:1330-1333, 2734-2774`; `camera/CameraEngine.kt:3002` |

## B. Performance / concurrency

| ID | Finding | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG4-34 | WB Kelvin/tint and EV drags take an unpaced full request rebuild per 40 ms tick; EV under app-side AE rebuilds for a wire no-op | Medium / High | PERF4-1 | `camera/CameraController.kt:1358-1434, 1704-1722`; `camera/ManualControls.kt:709-714, 998-1055` |
| AGG4-35 | `ProcessAdmissionSignal` publishes a value computed outside its lock → stale change-gated signal can swallow the final notification | Low / Medium | PERF4-3 | `ProcessAdmissionSignal.kt:44-56`; `storage/MediaStoreWriter.kt:213-217` |
| AGG4-36 | Warm relaunch in the same process re-runs whole launch recovery and re-writes every kept row | Low / Medium | PERF4-4 | `camera/LaunchMediaRecoveryCoordinator.kt:93-117` |
| AGG4-37 | Per-shot characteristics re-read bypasses the gate on every BURST/AEB completion (PERF2-5 cost back); gate KDoc stale | Low / High | REG4-6 | `camera/CameraController.kt:2277-2282, 2422-2432, 2490-2527` |

## C. Security / release tooling

| ID | Finding | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG4-38 | Generated-secret floor not enforced by plain Gradle (`./gradlew bundleRelease`); wrapper floor check is TOCTOU vs the sealed copy | Medium / High | SEC4-2 | `app/build.gradle.kts:860-899`; `tools/build_immutable_release.py:744-798` |
| AGG4-39 | Gradle signing gates are a task-name allowlist; `makeApkFromBundleForRelease` signs and is not gated; gate inputs omit the alias | Low-Medium / Medium | SEC4-1, REG4-9 (2) | `app/build.gradle.kts:772-859` |
| AGG4-40 | Tracked test fixture restates the redacted blocked-key password property next to the real anchor; docs gate scans only `.md` | Low / High | SEC4-3 | `tools/tests/test_tool_contracts.py:1694-1714`; `tools/check_docs.py:1559-1574` |
| AGG4-41 | "Sealed" release build inherits ambient Gradle channels (env, init.d, user gradle.properties, build cache) | Low / High | SEC4-4, CRIT4-6 (2) | `tools/build_immutable_release.py:119-125, 690-708, 868, 953` |
| AGG4-42 | Secret floor accepts trivially low-entropy values | Low / High | SEC4-5 | `tools/upload_key_policy.py:89-108` |
| AGG4-43 | Password-property doc scan misses new not-yet-staged docs; a wrong-repo empty listing passes vacuously | Low-Medium / High | REG4-3, DBG4-4 (2) | `tools/check_docs.py:1546-1575` |
| AGG4-44 | Release wrapper's new floor on the CURRENT approved key was never exercised with real credentials | Low / Low | REG4-10 | `tools/build_immutable_release.py:748-764` |

## D. Tests / gates

| ID | Finding | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG4-45 | `partition_report --write-regions` carries rationale by class name → can launder a same-class substitution | Medium / High | TE4-5, REG4-8 (2) | `tools/coverage/partition_report.py:330-360` |
| AGG4-46 | AGG3-39 shared wiring still leaves the AGG2-3 bug line (`onDone = settle`) engine-only | Medium / High | TE4-1 | `camera/CameraEngine.kt:4876-4891`; `DngPreCaptureAllocationTest.kt:266-277` |
| AGG4-47 | New fix-off defaults: `chars(shot=false)`, `acceptedPhotoSessionOutputs(hlgSessionAccepted=false)`, `PhotoFormatToggles(hlgSessionAccepted=false)`, `standaloneRouteWanted(rawForcesStandalone=true)`; caption test rebuilds controller wiring | Low-Medium / High | TE4-2, CRIT4-7 (2) | `camera/CameraController.kt:2430, 2572`; `ui/controls/ProControls.kt:1070`; `camera/CameraState.kt:539-543` |
| AGG4-48 | FRONT round-trip test has a tautological assertion; VM half of AGG3-1 unexercised | Low / High | TE4-3 | `PunchInResolvedTest.kt:93-99`; `ui/CameraViewModel.kt:2956-3011` |
| AGG4-49 | AGG3-8 provenance wiring lives only in MainActivity (untested); VM `onStoreMemorySlot` silently records null | Low / High | TE4-6 | `MainActivity.kt:517-545`; `ui/CameraViewModel.kt:3532` |
| AGG4-50 | Only 1 of 8 engine-law route conversions pinned by a test | Low / Medium | TE4-7 | `ui/CameraViewModel.kt` `standaloneRouteFor` callers |
| AGG4-51 | idat-HEIF VALID verdict pinned only by a synthetic fixture with non-measured widths | Low / Medium | TE4-8 | `MediaDurabilityPolicyTest.kt` `gridHeif()` |
| AGG4-52 | Injected-signing / no-signing refusals tested only as source text | Low / Medium | TE4-9 | `tools/tests/test_upload_key_policy.py` |

## E. Documentation

| ID | Finding | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG4-53 | README states the PMA110 RAW-routing law and "not by model name" as universal | Medium / High | DOC4-1 | `README.md:54-55, 100-101` |
| AGG4-54 | FIELD_CHECKS ledger missing two PENDING DEVICE items (TB336ZU token-door AAC; DATE_EXPIRES re-arm) | Medium / High | DOC4-4 | `docs/FIELD_CHECKS.md:3`; `CLAUDE.md:974-976`; `docs/ARCHITECTURE.md:1192` |
| AGG4-55 | Stacked/detached KDocs: `sleepPreservingInterrupt` (new, dd91413c) plus 20 more mid-file pairs; `standaloneRouteWanted` KDoc detached and universal | Low-Medium / High | REG4-4, PERF4-5, VER4-9, DOC4-2, DOC4-6 (5) | `storage/MediaStoreWriter.kt:2398-2416`; `camera/CameraState.kt:416-429, 537`; list in DOC4-6 |
| AGG4-56 | ARCHITECTURE cites nonexistent symbols: `gl.setFrontStreamPreMirrored`, `CameraCaps.rawForcesStandalone` | Low / High | DOC4-3, REG4-7 (2) | `docs/ARCHITECTURE.md:710, 715` |
| AGG4-57 | AGG3-30 interrupt-preserving backoff comments claim retired owners stop recovery; nothing interrupts that thread | Low / High | REG4-5 | `camera/CameraEngine.kt:7609-7614`; `LaunchMediaRecoveryCoordinator.kt:215-301` |
| AGG4-58 | `reassertPending` KDoc omits the MediaProvider `.pending-<expiry>` rename and the identity constraint | Low / Medium-High | DOC4-5 | `storage/MediaStoreWriter.kt:1129-1138`; `docs/ARCHITECTURE.md:1190-1192` |
| AGG4-59 | Dead KDoc links (`CAMERA_STARTING_STATUS`, `railChipStateDescription`, `cameraPolicyBlockConfirmed`, `macroCloserLensLabel`) | Low / High | DOC4-7 | `CameraScreenPolicy.kt:79-90, 686`; `CameraController.kt:2722`; `CameraState.kt:1564` |
| AGG4-60 | CLAUDE.md "exactly one caller" for `rotationOverrideDeg` — there are two | Low / High | VER4-7 | `CLAUDE.md` Loupe bullet; `gl/GlPipeline.kt:1065-1069, 1123` |
| AGG4-61 | CLAUDE.md compileSdk-37 attribution incomplete (core 1.19.x also) | Low / High | DOC4-8 | `CLAUDE.md` toolchain row |
| AGG4-62 | CLAUDE.md glyph "covered set in use" lists glyphs no literal uses | Low / High | DOC4-9 | `CLAUDE.md` FocusDetail bullet |
| AGG4-63 | ARCHITECTURE Lens-tab description omits the phone/converter declaration | Low / High | DOC4-10 | `docs/ARCHITECTURE.md:1251-1252` |

## F. UI / accessibility

| ID | Finding | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG4-64 | TalkBack cannot step snapped viewfinder rulers (ISO FULL-stop does not move at all): `progressSemantics` has no `steps` | Medium / High | DES4-1 | `ui/controls/ManualDials.kt:1389-1398, 999-1234` |
| AGG4-65 | Status plate is last-writer-wins; a 6 s retained-take instruction or an error is replaced by a lower-priority line | Medium / High | DES4-2 | `ui/CameraViewModel.kt:1755-1786`; `camera/CameraStatus.kt:111-205` |
| AGG4-66 | Format chips show the REQUEST while the session writes something else (FRONT DNG-only shows nothing selected; hi-res HEIF→JPEG) | Low / High | CRIT4-4, TR4-4 (2) | `ui/controls/ProSheet.kt:878`; `ui/controls/ProControls.kt` ~1125-1150 |
| AGG4-67 | DNG-only Ready status repeats on every Ready (no longer edge-triggered) | Low / High | TR4-5 | `ui/CameraViewModel.kt:899-910` |
| AGG4-68 | Every DNG shot greys the shutter as "unavailable"; a tap is silently swallowed (hardware key gets "Finishing previous photo") | Low-Medium / High | DES4-4 | `camera/CameraEngine.kt:4834-4848, 5354-5359`; `ui/CameraScreen.kt:3239-3307` |
| AGG4-69 | Output row says "DNG only"/"Still capture unavailable" and disables chips during every reconfigure | Low-Medium / High | DES4-5 | `ui/controls/ProControls.kt:1141-1183`; `ui/controls/ProSheet.kt:877-899` |
| AGG4-70 | Preview-only photo session OSD still lists requested formats; the promised "--" is unreachable | Low / High | DES4-6 | `ui/overlays/Overlays.kt:753-756, 988-999` |
| AGG4-71 | Exposure meter has no spoken form | Low / High | DES4-7 | `ui/CameraScreen.kt:2738-2813` |
| AGG4-72 | KO Fn tile values likely ellipsize in portrait | Low / Medium | DES4-8 | `ui/CameraScreen.kt:2667-2731` |

## Refuted / not counted

- SEC4 "docs drift: CLAUDE.md toolchain says AGP 9.3.2 / Gradle 9.7.1" — **refuted [orch]**: the
  on-disk CLAUDE.md table reads AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, BOM 2026.09.00, matching
  `gradle/libs.versions.toml` and the wrapper (DOC4 agrees).
- REG4 residual "`effectiveFor` erases normalizedFor's RAW-only fallback" — unreachable today
  (reviewer did not count it); folded into AGG4-66's chip/readout work as a note.
- VER4 "39 mm vs 40 mm" wording — not a defect (reviewer's own verdict).

## Cross-agent agreement (highest signal)

AGG4-3 (5 agents), AGG4-55 (5), AGG4-2 (4), AGG4-9 (4), AGG4-1 (3), AGG4-4 (3), and 2 each for
AGG4-6, AGG4-39, AGG4-41, AGG4-43, AGG4-45, AGG4-47, AGG4-56, AGG4-66.

## Cycle-3 regression verdict (REG4 + DBG4 + CRIT4 + CR4 + TE4)

All 42 cycle-3 commits are GPG-signed with no attribution trailer. Regressions/incomplete fixes:
c5bfd1c8 (AGG4-2), 01c42176/dd91413c (AGG4-3, AGG4-55 stacked KDoc), 5ebe8b07 (AGG4-4/AGG4-5),
6cae37ed (AGG4-66/AGG4-67), a36c5889 (AGG4-9), f32a5bbd (AGG4-37), 5398acd8 (AGG4-43), c70af129
(AGG4-45), 3d0946ab (AGG4-56), ce60c1a8 (AGG4-57 rationale only). All other commits judged clean.

## G. FD4 (feature-dev reviewer rerun) and QA4 (static gate)

| ID | Finding | Sev / Conf | Agents | Cite |
|---|---|---|---|---|
| AGG4-73 | Quick Zoom ruler reads/drags on the lens-local scale while chip/HUD show main-relative on every rear standalone route | Low-Medium / High | FD4-1 | `ui/controls/ManualDials.kt:1241-1278` vs `ui/ZoomMath.kt:36-48` |
| AGG4-74 | Momentary AEL / PUNCH_IN hardware bindings write `false` on release and persist, clobbering the operator's latched state | Low / High | FD4-2 | `ui/CameraViewModel.kt:3258-3267, 3342-3355`; `MainActivity.kt:752-882` |
| AGG4-75 | ISO ruler stops collapse above 25600 / below 50 (fixed ladder) | Low / High | FD4-3 | `ui/controls/ManualDials.kt:1101-1125` |
| AGG4-76 | Gate cannot attest zero compiler warnings (compile tasks UP-TO-DATE) and nothing makes warnings fatal | Medium / High | QA4-1 | `tools/verify_host.py:123-133`; `app/build.gradle.kts` |
| AGG4-77 | Debug lint `UsableSpace` warning (carried AGG3-61) | Low / High | QA4-2 | `ui/review/MediaReview.kt:456` |
| AGG4-78 | Release lint not in the default gate; last release report stale (2026-08-24) | Medium / High | QA4-3 | `tools/verify_host.py:141-151` |
| AGG4-79 | `CameraCaps.read` (incl. the 4 s still-ceiling clamp wiring) has 0 % host coverage | Medium / High | QA4-4 | `camera/CaptureCapabilities.kt:267-467`; `tools/coverage/partition-b.txt:24-25` |
| AGG4-80 | `Class$*` globs silently surrender future nested classes to Partition B (no B floor) | Medium / High | QA4-5 | `tools/coverage/partition-b.txt` |
| AGG4-81 | Function-wide `WrongConstant` suppression on `configureSession` | Low / Medium | QA4-6 | `camera/CameraController.kt:628` |
| AGG4-82 | Misplaced, uncommented `@Suppress("MissingPermission")` in VideoRecorder | Low / Medium | QA4-7 | `video/VideoRecorder.kt:679` |
| AGG4-83 | Info: androidTest compile ≠ evidence; fixture FAIL/ERROR lines in a green log; debug-only MissingPermission caller contract | Info / High | QA4-8, QA4-9, QA4-10 | `tools/verify_host.py:126`; device-tests self-tests; `camera/VendorTagInspector.kt:58,128` |

Baseline gate (orchestrator run at `14767b0a`): `python3 tools/verify_host.py` exit 0 — Gradle
green (configuration-cache reuse, compile tasks UP-TO-DATE), Partition A 99.86%, tools 181 /
coverage 15 / device-tests 195 OK, check_docs 190/0, lint 0 errors / 1 warning (`UsableSpace`).

## AGENT FAILURES

- `feature-dev:code-reviewer` (c4-fd-code-reviewer): its result never reached the orchestrator
  after two status nudges (the agent type has no Write tool, so nothing was saved). Retried once as
  a general-purpose rerun (`c4-fd-rerun`), which delivered `fd-code-reviewer.md` (FD4-1..3).
- `qa-adversary` (c4-qa-adversary): no report delivered after two nudges and no gate process was
  observed. The orchestrator ran the gate itself (baseline above) and retried the static lane once
  as `c4-qa-rerun`, which delivered `qa-adversary.md` (QA4-1..10).

## Totals

83 merged ids (AGG4-1..AGG4-83) from ~110 raw agent findings (CR4 17, PERF4 5, SEC4 5, CRIT4 7,
VER4 11, TE4 9, TR4 7, ARCH4 5, DBG4 4, DOC4 10, DES4 8, REG4 10, FD4 3, QA4 10). Refuted: 1.
