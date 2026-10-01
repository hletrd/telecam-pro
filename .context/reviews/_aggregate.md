# RPL cycle 2 aggregate review — 2026-10-02 (HEAD e5729ffd)

Sources: `code-reviewer.md` (CR2), `critic.md` (CRIT2), `tracer.md` (TR2), `verifier.md` (VER2),
`debugger.md` (DBG2), `architect.md` (ARCH2), `perf-reviewer.md` (PERF2), `security-reviewer.md`
(SEC2), `test-engineer.md` (TE2), `document-specialist.md` (DOC2), `designer.md` (DES2),
`fd-code-reviewer.md` (FD2). Cycle-1 reviews are archived in `archive-rpl-cycle1-2026-10-02/`.
Items are deduplicated; the highest severity/confidence among agreeing agents is kept, and
cross-agent agreement is listed (more agents → higher signal). Nothing is device-verified; every
camera/GL/audio item is PENDING DEVICE per CLAUDE.md.

## AGENT FAILURES

- **qa-adversary**: the first run (qa-adversary agent type) returned nothing. The single foreground
  re-run (general-purpose, told to write its file) also ended without writing
  `qa-adversary.md`. Its host-gate log (`scratchpad/verify_c2.log`, 833 lines) shows
  `tools/verify_host.py` completing every step through the final `git diff --check` (Gradle BUILD
  SUCCESSFUL, tools/coverage/device-tests unittest suites, `check_docs.py` 188 checks / 0 failed);
  the adversarial QA section was never produced. Gate status is re-established by the orchestrator
  during PROMPT 3.
- **fd-code-reviewer**: the first run returned nothing; the re-run succeeded (no high-confidence
  findings; one below-threshold note FD2-N1, merged into AGG2-2).

## A. Correctness — optics / DNG route door

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG2-1 | 3ec126e1 rollback keeps a "direct" DNG write that flips the RESTORED route (e.g. failed recall of a Video+DNG bank from Photo/DNG-off): DNG lit over a logical session, band predicate reads unified zoom as lens-local (`CameraEngine.kt:1011`, `:4003-4015`) | Medium / High | CR2-5, CRIT2-1, TR2-1, VER2-2, DBG2-1, ARCH2-1, PERF2-2 (7 agents) |
| AGG2-2 | Recall/restore is split into `setResolvedOptics` then `setRawWanted`: the fast-path terminal re-bands lens-local zoom before the DNG transaction (wrong lens), and a T2 failure rolls back to a mixed-scale packet (`CameraViewModel.kt:1429,1492`; `CameraEngine.kt:2792,2835-2841`) | Medium / Medium | CR2-6, VER2-1, ARCH2-3, FD2-N1 (was AGG-49) |
| AGG2-3 | A synchronously rejected DNG pre-allocation runs `onDone` AND returns `false`, so timelapse schedules every tick twice (2^n growth) and AEB resets controls mid-bracket (`CameraEngine.kt:4822, 4967-5059`; `DngPreCaptureAllocation.kt:120-161`) | High / High | CR2-1 |
| AGG2-4 | Post-invalidate preflight failure in `reconfigureCamera` (selection/caps null) rolls back with a moved session generation → old controller keeps streaming but Ready is never restored; shutter/REC dead until another door (`CameraEngine.kt:4108,4136-4160,1035`) | High / High | CR2-2 |
| AGG2-5 | FRONT with retained TELE: snapshot `3·z` converts back with the 10× base once `3z ≥ 10`; saved/stored TELE zoom 4.0 becomes 1.2 (`CameraViewModel.kt:2789-2800`) | Med-High / High | CR2-3 |
| AGG2-6 | AGG-36 fix still persists the delivered video size when the operator never picked one (`CameraViewModel.kt:1623`) | Medium / High | CR2-4, DBG2-3 |
| AGG2-7 | `setVideoResolution` writes `requestedVideoSize` outside any transaction; an older rollback reverts the later pick and b476d1dd now persists the revert (`CameraEngine.kt:974,1008,3692-3706`; `CameraViewModel.kt:951`) | Low-Med / Medium | CRIT2-3, TR2-4, CR2-18 |
| AGG2-8 | Pre-inventory format tap normalizes HEIF→JPEG before storing the pending request; persisted for good (`CameraViewModel.kt:2427-2428`) | Low-Med / High | CR2-9, VER2-4 |
| AGG2-9 | Rollback DNG mirror misses `pendingPhotoFormatsUntilInventory`; inventory replays the rolled-back DNG without remap (`CameraViewModel.kt:973-977,2695`) | Low / Medium | CRIT2-6, TR2-3, DBG2-2 |
| AGG2-10 | DNG toggle with TELE on runs a full same-camera reopen (`CameraEngine.kt:3997-4006`) | Low / High | CR2-11, TR2-2, CRIT2-4 |
| AGG2-11 | VM DNG door decides "route flips" from a state copy of the RAW law (default true) and applies remap side effects (tap-focus clear, invalidations) even when the engine does not reopen (`CameraViewModel.kt:2436-2475`) | Low / Medium | VER2-5, CRIT2-5, CRIT2-4 |
| AGG2-12 | `onCameraOverride` publishes the override even when the engine refuses mid-REC (`CameraViewModel.kt:3420-3429`) | Low / High | CR2-12 |
| AGG2-13 | Recall exposure clamp treats outgoing caps as authoritative across a DNG route flip (`CameraViewModel.kt:1367-1382`) | Low / Medium | CRIT2-8 |
| AGG2-14 | Dual-open candidate install lacks the in-monitor `paused` recheck → camera can open after `onStop` (`CameraEngine.kt:4177-4198`) | Low-Med / Medium | PERF2-3 |
| AGG2-15 | `onCleared` purges main callbacks before detaching engine callbacks; `recordTicker` can repost forever (`CameraViewModel.kt:4155-4160, 232-243`) | Low / Medium | PERF2-4 |

## B. Correctness — exposure

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG2-16 | App-side P seeded from a long M exposure clamps to 1/10 s without ISO compensation (−5 stops for 4 s) (`AutoExposure.kt:125-152`) | Medium / High | CR2-7 |
| AGG2-17 | ISO / shutter / angle dial doors leave HAL-AE P with the stale other axis (`CameraViewModel.kt:2007-2056`) | Medium / Medium | CR2-8 |

## C. Correctness — capture, storage, recording, controller

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG2-18 | Live finalized-video probe maps an extractor throw after open to INVALID → delete; launch recovery keeps the same bytes (`MediaStoreWriter.kt:2579-2601`) | Medium / High | CR2-10, CRIT2-2, PERF2-1, DBG2-4, TE2-12 |
| AGG2-19 | Metering regions silently dropped while `rawChars` is null; lazy retry only in `tryComplete` (and that retry is a Binder call per shot) (`CameraController.kt:1308,2266`) | Low / High | CR2-14, CRIT2-7, PERF2-5 |
| AGG2-20 | ZSL ring not flushed on the `setPinAutoFps` streaming-off edge (`CameraController.kt:1870-1895`) | Low / Medium | CR2-13 |
| AGG2-21 | Launch recovery: a persistently failing collection query is retried on every page (3× each) and starves DISCARD / risks the 120 s deadline (`MediaStoreWriter.kt:1414`) | Low-Med / Medium | CR2-17, PERF2-6 |
| AGG2-22 | Null processed-still decode fails the shot with no log (`StillCapturePipeline.kt:192`) | Low / High | DBG2-5 |
| AGG2-23 | Analysis/AE callback exceptions swallowed per frame with no trace (`GlPipeline.kt:1550-1556`) | Low / Medium | DBG2-6 |
| AGG2-24 | Startup-deadline task `shutdownNow()`s its own thread before `onFailure` (`VideoRecorder.kt:1022-1055`) | Low / High (latent) | PERF2-9 |
| AGG2-25 | Standby meter `check`/`checkNotNull` on a plain thread crash the process (`StandbyAudioController.kt:543,631-655`) | Low / Medium | PERF2-10 |
| AGG2-26 | Recall clears `AUDIO_OFF_BY_DENIAL_KEY` unconditionally (and on a refused re-recall), re-creating the self-locking silent-audio state (`MainActivity.kt:517-528`) | Medium / High | VER2-3, TE2-8 |
| AGG2-27 | `photoExposureTimeNs` (upper) and `wbTint` restored unbounded (`SettingsStore.kt:261,303-304`) | Low-Med / Medium | TE2-7 |
| AGG2-28 | ZSL admission ignores manual focus distance / WB (`ZslAdmission.kt:56-101`) | Medium / Medium | CR2-15 |
| AGG2-29 | Reserved 120-row budget: new per-event warnings (open failures, recovery rows) not change-gated (`MediaStoreWriter.kt:1080,1414`) | Low-Med / Medium | CR2-16, TR2-5, ARCH2-7, SEC2-5 |
| AGG2-30 | YUV still size picked by area without the aspect rule (`CaptureCapabilities.kt:341-345`) | Low / Low | CR2-19 |
| AGG2-31 | Loupe hint clamp ≠ draw clamp (`GlPipeline.kt:1134`) | Low / High (cosmetic) | CR2-20 |
| AGG2-32 | Focus-evidence epoch sampled at callback, not readback; non-volatile (`CameraViewModel.kt:1051-1070`) | Low / Medium | PERF2-7 |
| AGG2-33 | Frame-notification coalescing may keep a standing SurfaceTexture backlog (`FrameNotificationCoalescer.kt:21-40`) | Medium / Low | PERF2-8 |
| AGG2-34 | Presentation reducer invokes listeners under its lock (`RecordingStorageDispatcher.kt:171-177`) | Low / Low | PERF2-11 |
| AGG2-35 | "10-bit video · stills off" caption keyed on `videoMode` only; false on 8-bit fallback rungs (`ProControls.kt:1134-1141`) | Low / Medium | VER2-6 |

Cycle-1 AGG-29/30/31/32 re-validated by DBG2: all still OPEN.

## D. Security / release tooling

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG2-36 | Tracked public doc states the upload-key password's properties (`docs/play-console-submit.md:22,790-792`) | Medium / High | SEC2-1 |
| AGG2-37 | Upload-key gate self-attested, no blocked-cert deny-list, plain Gradle still signs, checker pins the blocked cert (`tools/build_immutable_release.py:524-706`, `tools/check_release_artifact.py:45-47`, `app/build.gradle.kts`) | Medium / High | SEC2-2, CRIT2-10 |
| AGG2-38 | Signing passwords reach the Gradle daemon env; hi-res passthrough EXIF unaudited; wrapper JAR not refreshed | Low / Info | SEC2-3, SEC2-4, SEC2-6 |

## E. Architecture

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG2-39 | Recall = one optics transaction + ~25 trailing setters; async rollback restores only optics → half-applied, persisted hybrid marked active | Medium / High | ARCH2-2 |
| AGG2-40 | VM mirrors of engine route facts start from different defaults; lens-band predicate inlined at four VM sites; persisted zoom has no scale tag | Low-Med / Medium | ARCH2-4, ARCH2-5, ARCH2-6 |

## F. Tests and gate

TE2-1 (DNG door has no engine/VM test), TE2-2 (`StorageFailureDiagnosticsTest` vacuous/racy),
TE2-3 (Partition-A residual line ranges stale and unchecked), TE2-4 (exposure-handoff VM wiring
untested), TE2-5 (aspect mid-REC refusal untested), TE2-6 (requested size/pending inventory paths),
TE2-8 (recall denial flag untested), TE2-9 (lazy characteristics retry untested), TE2-10 (TB336ZU
guard wiring), TE2-11, TE2-13, TE2-14; AGG-85..92 re-validated (AGG-85 partial, rest open).

## G. Docs

DOC2-1 (CLAUDE.md "≤1 stop per tick" vs code ±0.35), DOC2-2 (APV exclusion stated as a platform
rule; official MediaMuxer docs list APV-in-MP4 from SDK 36 — the device-verified failure stands),
DOC2-3 (robolectric.properties comment), DOC2-4 (private TESTING.md), DOC2-5 (verification
metadata keeps superseded pins), DOC2-6 (docs gate pins only AGP/BOM), DOC2-7 (ARCHITECTURE.md lacks
the token door), DOC2-8 ("zero retries" wording), DOC2-9 (release gate presented as routine);
VER2-7 (CLAUDE.md ladder bullet omits the 10-bit rung); CRIT2-9 (device evidence predates the
token-door code; P3.4/P3.5 not marked PENDING DEVICE). Toolchain pins are current per registries.

## H. UI/UX

DES2-1 (KO Delete button TalkBack name is a question), DES2-2 ("Will retry" promises an in-session
retry that only happens at next launch), DES2-3 (statuses drawn under open review), DES2-4..6
(AGG-62..64 still open), DES2-7 (KO picker parts of speech), DES2-8 (read-only rows two TalkBack
stops), DES2-9 (DISP node renames on toggle).
