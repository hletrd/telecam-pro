# RPL cycle 4 — independent merge review (`de6afb4d..7ad443b6`, 56 commits)

Reviewer: c4-merge-reviewer (Ralph step 7, THOROUGH tier). Read-only; no Gradle run (tree in use by
another lane). Inputs: `CLAUDE.md`, `docs/plans/2026-10-02-rpl-cycle4.md`, `.context/reviews/_aggregate.md`,
the per-commit diffs, and the lane reports.

**Verdict: APPROVE-WITH-FIXES.** One real regression introduced by C.9 (MRG4-1, made more reachable
by A.2) should be fixed before the cycle closes; everything else is Low and can ride the next cycle.

## Process checks

- All 56 commits are GPG-signed (`%G? == G`), Conventional-Commit + gitmoji headers, no AI
  attribution trailers.
- Merge resolution, `ProControls.kt` `PhotoFormatToggles`: both lanes' intent is preserved. A1
  (AGG4-47) wanted no silent default for the HLG caption input. A2 (AGG4-66/69) replaced the loose
  flags with `sessionOutputs: PhotoSessionOutputs` + `cameraReady`, which have no default, and the
  HLG fact now comes from `sessionOutputs.hlg` through `photoFormatChipModel` → `noStillOutputCaption(outputs.hlg)`.
  No `hlgSessionAccepted` argument is left at any call site (`0479c983` dropped the last test one).
  An omitted argument still fails to compile.
- Merge resolution, `tools/coverage/partition-a-residuals.txt`: I recomputed all 9 rows'
  `region_fingerprint` against the current source, and every one matches. Each re-cited region
  (CameraController 2687→2738, CameraState 916→945, MediaStoreWriter 3224-3225→3417-3418,
  CameraViewModel 151,154→156,159, ZoomMath 121→161) has the same text as at `de6afb4d`, so each
  carried rationale still describes its lines.

## Item-by-item (plan acceptance)

| Item | Fixes the finding? | Complete / invariants | Revert-detecting test |
|---|---|---|---|
| A.1 | yes | VM no longer writes the engine-owned flag | yes (Robolectric) |
| A.2 | yes, see MRG4-1/2/3 | bounded: retry reuses the transaction's generation and `ColdStartRetryGate` counts per generation, so there is no storm, and a newer door supersedes it | **table only** (MRG4-2) |
| A.3 | yes (`chooseVideoSize(selection) != videoSize` → structural) | ok | yes |
| A.4 / A.21 | yes; the VM now owns provenance through `AudioDenialReasonStore` (stronger than planned) | same prefs file in both owners | yes |
| A.5 / A.6 / A.7 / A.8 | yes | A.7 now keys on `activeCameraRoute` on both sides | yes |
| A.9 | yes; the attempt is built at construction, a pre-start cancel is latched, and each cancel is sealed | `StillContinuationHandoff` keeps exactly-once | yes |
| A.10 | yes; one `rearReturnLens` on both sides | standalone route keeps its band | yes |
| A.11 / A.12 / A.13 | yes | A.12 classifies by wire effect and stores controls for the next rebuild/still | yes |
| A.14 | yes | fix-off defaults removed (`chars(shot)`, `chainHead`, plan→outputs) | yes |
| A.15 | yes | — | yes |
| A.16 | yes; processed chips render the effective set, edits fold through `withEdit`, the DNG-only status is edge-only, the OSD shows `--` | MRG3-3 RAW-axis rule kept | yes |
| A.17 | yes (`pickStillSize`) | PMA110: the native-aspect candidate is the array size, so the pick does not change | yes |
| A.18 | yes; route-transition effects only on a real change, on both sides | first publish on a front-only device still resets | yes (both halves) |
| A.19 | yes | PMA110's post-inventory answer is unchanged | yes |
| A.20 | yes; one `effectiveEquivFocalMm` feeds the OSD, the program line and the focus gate | PENDING DEVICE by design; see MRG4-5/6 | yes |
| A.22 | yes; the shared `orphanPoisonedPreviewOutput` covers all three branches | PENDING DEVICE | partial (pure helper) |
| A.23 / A.24 / A.25 | yes | see MRG4-8 for A.24 | yes |
| B.1 | yes. The muxer-stop throw is no longer evidence. The confirming parse uses a fresh descriptor, and a failed reopen is INDETERMINATE. INVALID requires a walk that *proves* the moov absent. Read failures and the box bound give UNKNOWN, and UNKNOWN retains. | no destructive false INVALID found | yes |
| B.2 | mostly; see MRG4-4 | DISCARD rows excluded | yes |
| B.3 / B.7 | yes; the COMPLETE+empty case probes, and a failed probe keeps the row | ok | yes |
| B.4 | yes. Output is SOI + one `Exif\0\0` APP1, followed by every other header segment and then SOS→EOF verbatim. All old Exif APP1s are dropped, so exactly one remains. A segment that would overrun 0xFFFF is refused and the image is saved without EXIF. The HAL EXIF seeds the passthrough composer. | parity: all three lanes share `composeStillExifApp1`; see MRG4-9 | yes |
| B.5 / B.6 / B.8 / B.9 / B.10 / B.11 | yes | B.8 lock order is signal → owner, and owners notify outside their lock (`notifyAvailability`) | yes |
| C.1 / C.2 | yes | glob fallback when git top-level ≠ ROOT | yes |
| C.3 | yes. The floor is enforced in `doFirst` of the gated signing tasks only, so debug and unsigned builds are untouched. The verdicts ride the gate's inputs. The wrapper re-checks the frozen copy after the seal. | see MRG4-7 | yes (Gradle fixture) |
| C.4 | yes | — | yes |
| C.5 | yes; alias + store path in inputs | — | yes |
| C.6 | yes | see MRG4-10 | yes |
| C.7 | yes | — | yes |
| C.8 / C.10 | yes (EN + KO strings) | — | yes |
| C.9 | **regression**, see MRG4-1 | — | the test pins the faulty behaviour |
| C.11 | yes | see MRG4-6 | check_docs |
| C.12 | yes; a cached compile was produced under the flag, so a cached green still attests zero warnings | — | yes |
| C.13 | yes, clean tree only, and the NOTE line is honest | — | yes |

## Issues

### MRG4-1 — A terminal event resurrects an ended PROGRESS condition, and nothing clears it (Medium / High)

- Where: `camera/CameraStatus.kt` `StatusPlate.publish`/`expire` (the `current.lifecycle == PROGRESS` arm defers `current` behind ANY incoming EVENT); `ui/CameraViewModel.kt` `armStatusTimer` expiry runnable.
- Why it is wrong:
  - The camera-condition PROGRESS statuses have no timer. CLAUDE.md says "Ready, rollback/exhaustion, pause, or a newer status ends them".
  - The new reducer treats the terminal event of the same condition as a transient interruption. For example, `CAMERA_UNAVAILABLE_RETRYING` → `scheduleColdStartRetry` Exhausted → `CAMERA_UNAVAILABLE_REOPEN` (ERROR, 6 s). The reducer shows the ERROR and defers RETRYING.
  - When the ERROR expires, `expire()` puts "Camera unavailable, retrying…" back on the plate with no duration. No Ready ever arrives (the budget is exhausted), so it stays until pause or a newer status.
  - The same thing happens for `PREVIEW_UNAVAILABLE_RETRYING` → `PREVIEW_UNAVAILABLE_REOPEN`, and for `CAMERA_RECONFIGURING` → a Not-Ready rollback (`*_UNCHANGED`, `STOP_RECORDING_*_UNCHANGED`, `FRONT_CAMERA_UNAVAILABLE`).
  - A.2 now sends every bare-door preflight failure through the RETRYING → (exhaust) → REOPEN path, so this is reachable from ordinary stab/fps/aspect/video-size doors.
  - The new test `progress waits behind a higher event and returns when it expires` pins exactly this behaviour.
- Fix:
  - Have a condition-ending event end the condition rather than defer it. The cleanest version is to mark the terminal camera-condition messages (every ERROR-severity EVENT in the camera-availability family, i.e. `*_REOPEN`, `*_UNCHANGED`, `FRONT_CAMERA_UNAVAILABLE`, `TELE/LENS_UNAVAILABLE_UNCHANGED`, `STOP_RECORDING_*_UNCHANGED`) and drop `deferredProgress` when one of them replaces or outranks a PROGRESS. A narrower alternative: have the Engine's exhaustion and Not-Ready rollback paths call `clearProgressStatus()` before publishing.
  - Add reducer tests: RETRYING → REOPEN → expire ⇒ plate empty; RECONFIGURING → MR loaded (SUCCESS) → expire ⇒ RECONFIGURING returns (keep that intended case).

### MRG4-2 — The A.2 wiring has no revert-detecting test (Low-Medium / High)

- Where: `RouteInputRollbackTest.kt:92-110` (pure `preflightFailureDisposition` table) and `FacingRollbackPunchInRobolectricTest.kt:173` (still drives the old `rollbackOpticsAfterPreflight` guard directly).
- Why: the plan's acceptance says "a bare door whose caps/selection read fails once ends Ready (or in a scheduled retry), never parked". Neither test drives `handlePreflightFailure` or `reconfigureCamera`. Restoring the pre-fix `rollbackOpticsAfterPreflight` call in the BARE arm (or at either `selectCurrentLens`/`cachedCaps` call site) leaves both tests green.
- Fix: in the existing Robolectric harness, invoke `handlePreflightFailure(currentOpticsReconfiguration().transaction, recoverColdPreflight = false, …)` reflectively. Assert that `coldStartRetryGate` holds a scheduled token (or that a retry runnable is queued on `timelapseScheduler`), and that no Not-Ready rollback bumped `cameraSessionGeneration`.

### MRG4-3 — The bare retry still parks silently when the retry cannot run (Low / Low)

- Where: `CameraEngine.scheduleColdStartRetry` early return (`!canRun || generation mismatch` → revoke and return, with no status).
- Why: `canRun` requires `recorder == null && gl.inputSurface != null && started && !paused`. If a bare door's preflight fails while any of these is false, the door now gets neither the old rollback nor a retry: Not-Ready with no status. A stale generation is correct to ignore. The `recorder != null` case is the open question: it should be unreachable because the bare doors refuse while recording and a camera fault claims the recorder first, but nothing pins that.
- Fix: return a Boolean from `scheduleColdStartRetry`. When BARE_RETRY cannot schedule for a reason other than supersession, publish `CAMERA_UNAVAILABLE_REOPEN` (or fall back to `rollbackOpticsAfterPreflight`), so the outcome is never a silent park.

### MRG4-4 — Bounded re-arm still keeps an unparseable-but-moov-present MP4 forever (Low / Medium)

- Where: `storage/MediaStoreWriter.kt` `recoveryVideoVerdict` (re-throws the parse failure for PRESENT/UNKNOWN) together with `keptRowReassertsPending(… probeOutcome.failed)`.
- Why: a take whose bytes WERE read, whose walk found a complete `moov`, and which `MediaExtractor` deterministically rejects (a corrupt sample table, an unsupported box) is classified as a *transient* probe failure. It is re-armed on every launch, which is exactly the "never-judgeable row becomes immortal hidden storage" class AGG4-4 set out to bound. B.1's live tail can hand recovery such a row (INDETERMINATE after the stop throw).
- Fix:
  - Make the walk distinguish "bytes read, moov PRESENT" (a constant of the bytes) from "could not read" (transient).
  - On PRESENT plus a parse throw, return INDETERMINATE without `failed`, so the row is kept but not re-armed. Keep the re-throw (re-arm) only for walk UNKNOWN caused by a read failure.
  - Add a fixture: moov present, extractor throws ⇒ `keptRowReassertsPending == false`.

### MRG4-5 — The zoom-dependent program target may raise mid-gesture sensor submits (Low / Low-Medium, PENDING DEVICE)

- Where: `ui/CameraViewModel.kt` `programHandheldShutterNs(s)` (now reads `controls.zoomRatio`) feeding `AutoExposure.driveProgram` on every loop tick.
- Why:
  - Before A.20 the handheld target was constant within a lens band. Now every pinch and hardware zoom tick moves the target, and `driveProgram` re-centers the shutter by up to ±0.35 stop per tick (brightness-neutral). Each re-center is an ISO/exposure delta on the paced sensor fast path, and every swap stalls this HAL by about 180 ms (CLAUDE.md).
  - Photo P on PMA110 runs app-side, so a 1×→3× pinch now produces a run of sensor submits that did not exist before. That is exactly the stutter class the zoom fast-path work removed.
- Fix (after a device FrameGap check during a photo-P pinch): compute the handheld target from the last landed (quiet-window) zoom rather than the requested one, or hold the program target constant while `interactionActive`. Add a FIELD_CHECKS entry (MRG4-6).

### MRG4-6 — Cycle-4 PENDING DEVICE items are missing from the device ledger (Low / High)

- Where: `docs/FIELD_CHECKS.md` (the ledger claims to be exhaustive); plan items A.20 and A.22 are marked PENDING DEVICE.
- Why: only D2 and E4 were added. A.20 changes PMA110 exposure behaviour at zoom > 1 by design, and A.22's EGL containment cannot be reproduced on the host. Neither has an entry, so the next field session will not know to check them.
- Fix: add two entries:
  - A.20: photo P at TC local 1× and 4×; the OSD focal versus the EXIF ExposureTime should follow 1/focal, and FrameGap should be measured during the pinch.
  - A.22: an injected acquisition-detach failure or a debug fault hook, if feasible; otherwise state "host-only, no device procedure".

### MRG4-7 — Kotlin and Python secret floors are not value-for-value equal outside ASCII (Low / Low)

- Where: `app/build.gradle.kts` `meetsGeneratedSecretFloor` vs `tools/upload_key_policy.py`.
- Why:
  - Kotlin `String.length` and `toSet()` count UTF-16 units, so a non-BMP character counts as 2 for length and adds 2 "distinct" surrogates. Python counts code points.
  - `isDigit`/`isLetterOrDigit` differ from Python `isdigit`/`isalnum` (for example superscripts and other `No` characters).
  - `lowercase()` vs `casefold()` also differ.
  - The parity fixture uses ASCII vectors only. Plain Gradle can therefore accept a value both wrappers reject. The immutable path still enforces the Python rule.
- Fix: count code points in Kotlin (`codePoints()`), or restrict both implementations' class tests to ASCII. Add one non-BMP and one superscript vector to the parity fixture.

### MRG4-8 — A momentary hold survives a recall or restore and then overwrites it (Low / Medium)

- Where: `ui/MomentaryHold.kt`, `CameraViewModel` hardware AEL/PUNCH_IN arms; `applyLoaded` does not cancel the holds.
- Why: press AEL (snapshot `false`) → MR recall sets `aeLock = true` → key release restores `false` over the recalled bank. PUNCH_IN behaves the same way across a recall or settings restore. The explicit toggles cancel the hold, but the recall/restore door does not.
- Fix: call `momentaryAeLock.cancel()` and `momentaryPunchIn.cancel()` inside `applyLoaded` (and on the mode/lifecycle `onStop` path), and add a Robolectric case.

### MRG4-9 — Hi-res passthrough EXIF can exceed one APP1 and lose orientation (Low / Low; dormant on PMA110)

- Where: `capture/StillCapturePipeline.kt` `writePassthroughJpeg` → `composeStillExifApp1(sourceExifApp1 = HAL APP1)` → `isSpliceableExifPayload` (≤ 0xFFFD).
- Why:
  - A HAL EXIF with an embedded IFD1 thumbnail near the 64 KB cap grows when our tags are added. The splice is then refused and the 200 MP JPEG is saved with no EXIF at all, which on this lane means no `Orientation`, so a rotated capture displays sideways.
  - `decodeByteArray` bounds failure (`outWidth = -1`) has the same effect.
  - Hi-res is dormant on PMA110.
- Smaller note: the HEIF lane now also stamps `Orientation = 1` (it had none before). This is benign because the pixels are upright, but PMA110 HEIF bytes change.
- Fix: when the composed payload exceeds the cap, recompose without the source thumbnail (clear IFD1) before giving up. Add a passthrough test with an oversized-thumbnail seed.

### MRG4-10 — The sealed release refuses common inert user Gradle properties (Low / Low)

- Where: `tools/build_immutable_release.py` `USER_GRADLE_PROPERTY_ALLOWLIST`.
- Why: `org.gradle.caching`, `org.gradle.configuration-cache` (both already overridden by `SEALED_GRADLE_FLAGS`), and `systemProp.http(s).proxyHost/Port` (needed behind a proxy for a dependency refresh) make `verify_host.py --release` refuse. This is not a problem today: this machine has no `~/.gradle/gradle.properties` and no `init.d`, so legitimate use is unaffected.
- Fix: allow the keys the sealed flags already neutralize, and document proxy handling. Alternatively, keep the refusal but name the remedy in the error message.

## Not issues (checked)

- **A.2 storm.** The retry closure re-reads `currentOpticsReconfiguration()`, which does not bump `opticsIntentGeneration`. The gate's attempt counter therefore persists across retries, bounded by `MAX_CAMERA_RECOVERY_ATTEMPTS`. A newer door changes the generation and supersedes the retry.
- **B.1 false INVALID.**
  - A zero-length file is ABSENT, which is correct: there are no bytes.
  - An overrunning box or an impossible size is ABSENT only because nothing complete can follow it.
  - A short or failed read is UNKNOWN, and so is hitting the 4096-box bound.
  - The live tail wraps the walk in `runCatching → UNKNOWN`.
  - Recovery re-throws, so the row is kept.
- **B.4 structure.** Fill bytes, TEM/RST markers and a missing SOI are all handled, as is EOI-before-SOS (null, which means no EXIF rather than a guess). APP0/JFIF follows the APP1, which is the same layout `ExifInterface.saveAttributes` produced.
- **C.3/C.4 debug and unsigned paths.** The floor is computed only inside `if (hasReleaseSigning)` and thrown only from the gated signing tasks' `doFirst`. The fixture task exists only when its property is supplied.
- **C.12.** No per-task escape hatch exists. A cached compile was produced under the flag, so the attestation holds.
