# RPL cycle 5: cycle-4 regression review (`887d39fb..ea7d4374`, 86 commits)

Role: cycle-4 regression reviewer (RG5-). The review was read-only, with no Gradle run. Every
commit in the range was listed, and every commit that touches `app/src/main` or `tools/` was read
with `git show` against current code. The review commits, plan commits and pure-doc commits were
skimmed for code impact only. Findings already in MRG4-1..10 or in the cycle-4 "later cycle"
lists are not reported again unless there is new evidence.

## Summary

- No Critical or High regression found.
- 1 Medium **owner-decision** item (RG5-1: A.20 changes PMA110 exposure; numbers below).
- 1 Medium tooling gap (RG5-12).
- 10 Low and 4 Info findings.
- Refuted while reviewing: an apparent toolchain-row drift. `CLAUDE.md` on disk says AGP 9.4.1,
  which matches `gradle/libs.versions.toml`, so only the session-injected copy was stale. Not
  reported.

## Mandatory checks

### RG5-1: A.20 / AGG4-14 changes PMA110 photo-P exposure and the focus-detail gate, including at the default 1× (Medium, owner decision / High / Confirmed)

**Cite:**
- `ui/CameraViewModel.kt:4853-4877` (`programHandheldShutterNs`, `handheldShutterNs`).
- `camera/CameraState.kt:1846-1852` (`CameraUiState.effectiveEquivFocalMm`).
- `camera/CameraState.kt:2054-2062` (`effectiveEquivFocalMm`).
- Callers: `ui/CameraViewModel.kt:587` (focus gate) and `:4277` (`AutoExposure.driveProgram`).
- Commit `2e10888c`.

**What changed:**
- **Before:** on the rear route the target was `1e9 / (s.lens.targetEquivMm × (TC ? mag : 1))`. That is a step function of the lens band (14/23/70/230 mm), with no zoom term. FRONT/EXTERNAL used the measured equivalent with no zoom.
- **After:** the target is `1e9 / effectiveEquivFocalMm`.
  - With TC on: `teleconverterFocalMm × zoom`.
  - Otherwise: `caps.equivalentFocalMm × zoom`, using the opened camera's measured equivalent.

The target feeds two consumers:
- **Photo PROGRAM program line**, app-side only (flash off). The shutter sits at this target whenever ISO is not clamped.
- **Focus-detail SOFT gate** (`MacroProximity.kt:123`, exposure ≤ 16 × target). It applies wherever the analysis readback runs, so in S/ISO/M as well.

**PMA110 measured inputs:**
- Logical route equivalent: 23.4 mm (`docs/play-console-submit.md:358-361`; an earlier run read 23.0 mm).
- Standalone 3× lens: 69.4 mm (CLAUDE.md lens-rail bullet).
- Kit converter: 300/70.

**Handheld target, before → after** (EV < 0 means a faster shutter, so ISO rises by the same amount while ISO is unrailed):

| PMA110 state (photo, app-side P) | before | after (23.4 mm logical) | after (23.0 mm logical) | Δ |
|---|---|---|---|---|
| logical, 0.6× preset | 1/14 = 71.43 ms | 71.23 ms | 72.46 ms | ±0.02 EV |
| logical, **1× (launch default)** | 1/23 = 43.48 ms | **42.74 ms** | 43.48 ms | **−0.025 EV** / 0 |
| logical, 2× pinch | 43.48 ms | 21.37 ms | 21.74 ms | −1.0 EV |
| logical, 2.9× (top of MAIN band) | 43.48 ms | 14.74 ms | 14.99 ms | −1.56 EV |
| logical, 3× preset | 1/70 = 14.29 ms | 14.25 ms | 14.49 ms | ±0.02 EV |
| logical, 5× | 14.29 ms | 8.55 ms | 8.70 ms | −0.74 EV |
| logical, 9.9× (top of 3× band) | 14.29 ms | 4.32 ms | 4.39 ms | −1.73 EV |
| logical, 10× preset | 1/230 = 4.348 ms | 4.274 ms | 4.348 ms | −0.02 / 0 EV |
| logical, 20× | 4.348 ms | 2.137 ms | 2.174 ms | −1.0 EV |
| **TELE + kit TC, local 1× (300 mm)** | 3.333 ms | **3.333 ms** (same float, `70f × mag × 1f`) | same | **0 (byte-identical)** |
| TELE + kit TC, local 2× (600 mm) | 3.333 ms | 1.667 ms | same | −1.0 EV |
| TELE + kit TC, local 4× (1200 mm) | 3.333 ms | 0.833 ms | same | **−2.0 EV** |
| TELE + kit TC, local max ≈4.6× (60× cap, 1380 mm) | 3.333 ms | 0.725 ms | same | −2.2 EV |
| DNG on (standalone 3× lens), local 1× | 14.29 ms | 14.41 ms (69.4 mm) | same | +0.012 EV |
| DNG on (standalone 3× lens), local 2× | 14.29 ms | 7.21 ms | same | −0.99 EV |

**Focus-detail gate:** at TC local 4× the gate's exposure ceiling drops from 53.3 ms (16 × 1/300) to 13.3 ms (16 × 1/1200), so the SOFT tag is refused across a 2-stop wider band of preview exposures. That moves in the "may miss" direction the contract allows. It changes when SOFT appears; it does not create false fires.

**Byte-identity assessment:**
- PMA110 behaviour is NOT byte-identical except at TELE + TC local 1×.
- The plan (A.20, "changes by design at zoom > 1") and `docs/FIELD_CHECKS.md` A6 ("at any zoom above 1×") **understate** the change. Because the source switched from the nominal preset focal to the measured equivalent, every non-TC preset position also moves by up to ±0.025 EV. That includes the launch-default logical 1× whenever the route measures 23.4 mm.
- Photographically this is negligible, but it is a PMA110 delta that nothing records.

**Is current behaviour "proven wrong"?**
- AGG4-14 is an internal-consistency argument: the OSD and EXIF say 1200 mm while P holds 1/300 s. It is not a device measurement.
- The physics backs the new rule. A digital crop is upsampled, so angular shake blur relative to the saved frame scales with the effective (zoom-included) focal. 1/(effective focal) is the correct form of the rule.
- The cost is real and lands on the app's main use case: at TC 3–4.6× in mid light, P now runs 1.6–2.2 stops more ISO.
- So whether to keep it is an owner tradeoff (sharpness against noise), not a defect fix the rule mandates.

**Not decided here (per instructions):**
- Do not revert on this review's say-so.
- MRG4-5 (mid-gesture program re-centres) is already tracked. The continuous zoom-dependence makes it reachable on every logical-route pinch, not only under TC.

**Suggested follow-up:**
1. Owner confirms or rejects the TC 2–4.6× delta through FIELD_CHECKS A6.
2. Correct the plan, A6 and the commit-ledger wording to say "every non-TC route moves to the measured focal (±0.025 EV at presets; 0 only at TELE+TC local 1×)".
3. If the preset-point identity matters, key the non-TC branch on the nominal preset focal × (zoom ÷ preset ratio). That restores the old values exactly at preset positions and keeps the zoom term between them. It is host-testable with a table at 23.4/69.4.

### RG5-2: A.23's "logical stays 1:1" holds only if the logical route measures exactly 23.0 mm, so the ruler readout drifts +1.7 % on PMA110 (Low / Medium / Likely; device run to confirm the equivalent)

**Cite:** `ui/ZoomMath.kt:38-49` (`zoomDisplayMultiplier`, the `else` branch), `ui/ZoomMath.kt:57-89` (`zoomRulerScale`), `ui/controls/ManualDials.kt:295-306`, and `app/src/test/.../ui/ZoomRulerScaleTest.kt:45-52` (`logical route and FRONT stay 1 to 1` uses `multiplier(23f)`).

- On the LOGICAL photo route `zoomRatio` is already main-relative. Even so, `zoomDisplayMultiplier` multiplies it by `caps.equivalentFocalMm / 23`, which is ≈1.017 at the measured 23.4 mm.
- `6e75d245` now feeds that multiplier to the Quick Zoom ruler.
- **Drag mapping is identical.** `localFor(f) = lower + f·(upper − lower)` and `fraction()` both cancel the base, so the wire value for a given ruler position is byte-identical.
- **Readout changes on PMA110:**
  - 3× preset: "3.0×" → "3.1×" (3.05).
  - 10×: "10.0×" → "10.2×".
  - 20×: "20.0×" → "20.3×".
  - The low end reads 0.61.
- This puts the ruler in agreement with the ZOOM chip and HUD pill, which already showed these values. The inflation itself is **pre-existing** in the chip and pill, and A.23 extended it to the ruler.
- The test fixture hard-codes 23 mm, so it cannot see this.

**Fix:**
- On a BACK route that is the logical seamless camera (`!standaloneRouteWanted(...)`), the display multiplier should be 1.
- `zoomDisplayMultiplier` needs the route-type input that `unifiedZoomOf`/`localZoomOf` already use.
- Add a 23.4 mm row to `ZoomRulerScaleTest` and `ZoomMathTest`.
- PMA110 impact: on the logical route the chip, pill and ruler would read the round preset again ("3.0×"), which is what the rail says.

### RG5-3: A.19 is PMA110-identical only after the inventory publishes (Info / High / Confirmed)

**Cite:** `camera/CameraEngine.kt:1406` (`acceptedOpticalPresets = LensInventory.ALL.optical`), commit `85e1bef8`.

- After `onLensInventory`, PMA110 enumerates all four presets as optical (pinned by test), so its answer is unchanged.
- Before the inventory publishes (a settings restore, or a TC/FRONT door ahead of the first `setupExecutor` enumeration), the Engine used to divide by 1×. It now divides by the preset's own base, the same as the VM.
- So the pre-inventory window changes from a VM/Engine disagreement to agreement. This is a correction, and the window is effectively unreachable once the camera opens. The "PMA110-identical" wording should say "post-inventory".

### RG5-4: A.17 YUV still size on PMA110 is unchanged (Info / High for logical; FRONT unmeasured)

**Cite:** commit `e9f39680`, `camera/CaptureCapabilities.kt` `pickStillSize`.

- The PMA110 logical active array and its largest YUV size are both 4080×3064. `pickStillSize`'s exact-ratio filter keeps 4080×3064 before and after. 4096×3072 was never eligible on logical.
- The FRONT size changes only if its largest YUV size is off the array aspect. One FRONT still-dimension readback closes this.
- Residual risk is non-PMA110: the exact cross-multiply could drop to a half-size exact match if the full-size entry were missing.

## Regressions and side effects found in other commits

### RG5-5: MomentaryHold (A.24) and the focus-ruler auto punch-in snapshot each other's transient values (Low / Medium / Likely)

**Cite:** `ui/CameraViewModel.kt:3354-3365` (`onAutoPunchIn`, which never consults `momentaryPunchIn`), `ui/CameraViewModel.kt:3440-3447` (PUNCH_IN hold), `ui/MomentaryHold.kt`, `currentExtras()` punch-in branch (`autoPunchInActive` wins over `momentaryPunchIn.persistedValue`), and commits `992ec720`/`7a2d6cb5`.

Failure scenarios:
1. **Hold, then ruler.** The operator's loupe is off.
   - Press-hold the PUNCH_IN key, so the loupe turns on.
   - Open the MF ruler. `punchInBeforeAuto` snapshots `true`, which is the hold's value.
   - Background now: `currentExtras` takes the `autoPunchInActive` branch, so `punchIn = true` is persisted.
   - Alternatively, release the key, then close the ruler. The assist restores `true`, the loupe stays on, and the next save persists it, because the hold is gone and the live value is `true`.
2. **Ruler, then hold.** With the ruler open (assist `true`), press-hold, close the ruler (the assist restores `false`), then release. The hold restores its snapshot `true` and the loupe is stuck on. Nothing owns that value now, so the next save persists it.

**Fix:**
- `onAutoPunchIn(true)` should snapshot `momentaryPunchIn.persistedValue(_state.value.punchIn)`.
- Make the assist and the hold mutually exclusive: `onAutoPunchIn` cancels the hold, or a release while `autoPunchInActive` writes `punchInBeforeAuto = prior` instead of the live loupe.
- Pure VM test over the four orderings.
- PMA110 impact: only with a PUNCH_IN hardware binding. Behaviour without one is unchanged.

### RG5-6: The ranked status plate lets a stale higher-rank event outlive its own resolution (Low / Medium / Confirmed by code)

**Cite:** `camera/CameraStatus.kt` `StatusPlateRank` and `StatusPlate.publish`, `ui/CameraViewModel.kt` `publishStatus`/`arbitrateStatus`, and commits `eba84af6`/`dfb122f8`.

- A lower-rank incoming event is **dropped**, never queued. The ordering puts SUCCESS below INFO and everything below ERROR.
- Concrete sequences that now show the wrong thing:
  - `COULD_NOT_DELETE_FILE` (ERROR, 6 s), then the operator retries and gets `DELETED` (SUCCESS). The success is dropped and the plate keeps saying the delete failed after it succeeded.
  - `FINISHING_PREVIOUS_CLIP` or `RECORDING_WITHOUT_AUDIO` (INFO), then a short take's `VIDEO_SAVED` (SUCCESS) within its display time. The save confirmation never appears.
  - `DELETE_CANCELED` (INFO), then an immediate re-delete gives `DELETED`, which is dropped.
- The plan's intent (AGG4-65) was that a retained-take instruction or an error must not be wiped by unrelated chatter. It did not mean the event's own resolution should be suppressed.

**Fix:**
- Let an incoming event that RESOLVES the shown one replace it regardless of rank. The same-family pairs are delete-fail→deleted, finishing→saved and without-audio→saved, held as a small explicit map beside `CAMERA_CONDITION_ENDING_MESSAGES`.
- Alternatively, queue a dropped SUCCESS to show when the higher event expires.
- Pure reducer tests.
- PMA110 impact: status copy only.

### RG5-7: Condition-ending messages are keyed by message, not by the condition they end (Low / Low / Likely)

**Cite:** `camera/CameraStatus.kt` `CAMERA_CONDITION_ENDING_MESSAGES` and the `endsCondition` branches (`dfb122f8`). Emitters: `camera/CameraEngine.kt:2785-2796, 2963-2973, 4020-4061, 4168-4180, 4343`.

- The `*_UNCHANGED` and `STOP_RECORDING_*_UNCHANGED` rollbacks end the operator's OWN optics door.
- The plate drops whatever PROGRESS condition is shown or deferred, including an unrelated one: `PREVIEW_UNAVAILABLE_RETRYING` or `CAMERA_ERROR_RECOVERING` from a camera-health or preview-EGL recovery still in flight.
- Scenario:
  - A preview-EGL retry is running ("Preview unavailable, retrying…").
  - The operator taps a lens that is unavailable, and `rollbackOptics` publishes `LENS_UNAVAILABLE_UNCHANGED`.
  - The retry condition is erased.
  - When the error expires, the plate is blank while recovery is still in progress.
- `rollbackOptics` is ownership-gated (a superseded rollback publishes nothing), so this needs a live, current-owner rollback during a separate recovery. That is rare.

**Fix:** end only the optics-family conditions (`CAMERA_RECONFIGURING`, the cold/bare `*_RETRYING` the same transaction scheduled). Alternatively, carry the ending transaction's generation on the condition. Reducer test.

### RG5-8: A recall that immediately reconciles the microphone grant un-lights the slot it just loaded (Low / High / Confirmed)

**Cite:** `AudioDenialReason.kt` `MemoryBankAudioProvenance.afterRecall` → `restoreIfGranted(announce = false)` → `MainActivity` `restoreAudio` → `vm.onToggleRecordAudio(true)`, and `ui/CameraViewModel.kt:2624-2629` (`activeMemorySlot = null` plus an immediate `saveSettingsIfEnabled()`). Commit `1218a635`.

- Recalling a denial-silent bank while the mic is granted lights MRn, and then clears it in the same main-thread turn. It also triggers a synchronous full-prefs save.
- The AGG4-9 restore is correct. The side effect is that the "MRn loaded" status shows with no lit slot.

**Fix:** a non-indicator-clearing VM entry for the provenance restore, for example `restoreRecordAudioFromGrant()` that keeps `activeMemorySlot`. Robolectric test.

**PMA110:** only reachable with a denial-silent bank, so it is unlikely in practice.

### RG5-9: A.18 VM/Engine zoom divergence after a same-route topology change on FRONT/EXTERNAL (Low / Medium / Likely)

**Cite:** commit `54c90d3a`. VM `cameraRoutePublishedState` now keeps zoom when the route is unchanged. The Engine's `convergeAfterRouteTopologyChange` (`camera/CameraEngine.kt` ~1718-1735) still writes `zoomRatio = 1f` for any non-BACK route on the forced-inventory path (~1620-1635).

- Scenario: FRONT/EXTERNAL at 2×, then a camera is added or removed, or a same-id identity epoch changes.
  - The OSD, rail and pinch base read 2×.
  - The wire is at 1×.
  - The next controls push silently re-zooms.
- Before A.18 both sides always reset, so they stayed consistent.

**Fix:** preserve zoom in the Engine converge when the route is unchanged, or publish the converge's zoom in the same generation. Host test.

**PMA110:** effectively unreachable (no EXTERNAL; front identity is stable).

### RG5-10: The AGG4-26 queue/permit race still exists in `RejectedOutputCleanupCapacityOwner` (Low / Medium / Confirmed in code)

**Cite:** `storage/MediaStoreWriter.kt:2138` (`ArrayBlockingQueue(backlogCapacity)`) against the semaphore at `:2142` (`workerCount + backlogCapacity`), with the permit released in `runAttempt`'s `finally` (`:2196-2201`) before the worker dequeues.

- `25abb2f2` fixed exactly this pattern in `FamilyDeletionMarkerCapacityOwner`, but missed this sibling.
- With the backlog full, a `reserve()` can win the released permit while the queue is still full. `submitReserved` is then rejected (`:2178-2184`), which gives a spurious `UNRESOLVED`.
- That counts against `admissionLimit` and can close the shutter until a retry.
- No data loss: the row stays pending.

**Fix:** size the queue `workerCount + backlogCapacity`, the same as 25abb2f2, and add the same `afterTaskReleased` seam test.

**PMA110:** saturation only.

### RG5-11: A COMPLETE row whose provider SIZE reads 0 can now expire instead of being adopted (Low / Low / Needs device validation)

**Cite:** commit `9344c2fa`, `storage/MediaStoreWriter.kt:1418-1423` together with `orphanDisposition` (~`:2918`) and `keptRowReassertsPending` (`:2865+`).

- A durable-COMPLETE row with `SIZE <= 0` is now probed.
- A non-failed INDETERMINATE probe leads to KEEP_PENDING without a re-arm. Probes that can return that are an undecidable HEIF layout, a keep-pending MIME, or a moov-present MP4 the extractor rejects.
- Before this change the row was adopted on its marker. CLAUDE.md says "Recovery also adopts durable COMPLETE rows".
- Reachable only if MediaProvider leaves SIZE at 0 after a close. AOSP updates SIZE on FUSE close, hence Low confidence.

**Fix:** `keptRowReassertsPending` always re-arms `journalState == COMPLETE`.

### RG5-12: The sealed release still applies `$GRADLE_USER_HOME/init.gradle(.kts)` (Medium / High / Confirmed)

**Cite:** `tools/build_immutable_release.py:201-213` (`require_sealed_gradle_user_home` refuses only a non-empty `init.d`), and the seal rationale comment at ~`:122-128`. Commit `6d93afa6` (C.6 / AGG4-41).

- Gradle auto-applies `GRADLE_USER_HOME/init.gradle` and `init.gradle.kts` exactly like `init.d/*`, and the distribution's own `init.d` as well.
- A maintainer's `~/.gradle/init.gradle.kts` (a repo mirror, an `allprojects` hook) runs inside the "sealed" release, and `release-evidence.json` still claims a sealed run.

**Fix:** refuse when `home/"init.gradle"` or `home/"init.gradle.kts"` exists, and optionally the wrapper distribution's `init.d`. Fixture test beside the `init.d` one.

**PMA110:** none.

### RG5-13: The sealed jvmargs screen is a denylist (Low / Medium / Confirmed)

**Cite:** `tools/build_immutable_release.py:176` (`_JVM_ARGUMENT_INJECTION`: `-javaagent`, `-agentpath`, `-agentlib`, `-Dorg.gradle.project.`, `-Dandroid.`).

- An admitted `org.gradle.jvmargs` or `kotlin.daemon.jvmargs` can still carry:
  - `@argfile` (the launcher expands it and can smuggle `-javaagent`);
  - `-XX:OnOutOfMemoryError=` / `-XX:OnError=`;
  - `-Xbootclasspath/a:`;
  - `-Djava.system.class.loader=`.
- `--no-daemon` still forks a single-use daemon JVM with these args.

**Fix:** an allowlist (`-Xmx`, `-Xms`, `-XX:MaxMetaspaceSize`, `-XX:+HeapDumpOnOutOfMemoryError`, `-Dfile.encoding`, `-Duser.*`), or explicit rejection of `@`, `-XX:On*`, `-Xbootclasspath` and `-Djava.system.class.loader`. Test.

### RG5-14: The docs secret-fact scan misses markdown outside the listed trees and all `.kt` and `.xml` (Low / High / Confirmed)

**Cite:** `tools/check_docs.py` `password_property_scan_paths` (the `c6598aae` region).

- Markdown is scanned only under `docs/`, `.context/` and the fixed authority list.
- The non-markdown pass excludes `.md` and does not cover `.kt` or `res/values*/strings.xml`. Those strings are published text.
- A new `tools/*.md`, `app/README.md`, or a Kotlin fixture comment passes unscanned. That is the same "file type this rule never read" premise SEC4-3 closed for other types.

**Fix:** scan every tracked `.md`, plus `.kt`/`.xml` under `app/src`, with the existing synthetic-marker allowlist.

### RG5-15: Release lint is silently skipped on a dirty tree (Low / High / Confirmed)

**Cite:** `tools/verify_host.py:159-167` (`0a22ffb2`).

- On a dirty tree `:app:lintRelease` is skipped with a NOTE and the gate still exits 0.
- This cycle's pre-commit gates ran dirty (review-file moves). A green dirty-tree run can be read as "lintRelease green".

**Fix:** emit a distinct `release_lint: skipped (dirty tree)` line that the plan ledger must cite, or lint a clean `git worktree`/export of HEAD.

### RG5-16: A moov-present take the extractor rejects is no longer re-armed, even though the throw may be transient (Low / Low / Likely; new angle on MRG4-4)

**Cite:** commit `93c43931` (`storage/MediaStoreWriter.kt:2881-2898`), simplified by `321a6f34`.

- The AGG2-18 note says `MediaExtractor` throws identically for a transient FUSE read and for a corrupt container. A successful 8-byte box-header walk does not prove the extractor's throw was permanent.
- A fail-closed good take that hits a one-off throw at launch is no longer re-armed. If the next launch falls past the expiry window, it is collected.

**Fix:** stop re-arming only after N consecutive moov-present-but-throw launches, recorded in the discard journal.

MRG4-4's own residual (the 4096-box bound) is already tracked. This is the opposite direction.

### RG5-17: The stacked-KDoc scan misses KDocs separated by `//` or `/* */` lines (Info / Medium / Confirmed)

**Cite:** `tools/check_docs.py:~2608` (`STACKED_KDOC` matches `*/`, then blank lines, then `/**` only).

- A detached KDoc followed by a `//` line, a block comment or an annotation-only line, and then another `/**`, is equally detached but passes the now-enforcing scan.

**Fix:** widen the pattern and add fixtures.

## Commit-by-commit verdicts (no regression unless noted)

**Storage / capture**
- `5cf9c5e3` B.1: the walk is correct. Muxer-killed files → ABSENT. A sample-less stop → PRESENT (retained). Reparse and open failure → INDETERMINATE.
- `ea1ff6ed` B.2 and `16e85a64` B.3: correct. See RG5-11 and RG5-16.
- `9344c2fa` B.7: see RG5-11. A benign side effect: zero-byte REGISTERED rows with an unknown MIME are now kept until expiry instead of being deleted.
- `257d5b7a` B.4: splice order matches `ExifInterface.saveAttributes`.
  - `compress` failure now happens before row allocation, so no orphan row.
  - Perf note, not a bug: about two encoded copies are held in memory, ~10–20 MB at 12 MP.
- `9408fb35` B.5: `processedQueued` is synchronous in the same `onPhoto`. `finishDng` stays exactly-once.
- `ac51a897` B.6: only UTC output changes.
- `56bb2269` B.8: listeners fire outside the signal lock, so there is no inversion.
- `25abb2f2` B.9: correct, but its sibling was missed (RG5-10).
- `1ab30dda`, `7aa7a14f`: comments only.
- `53790691`: the suppression sits under a real `doAudio = recordAudio && hasRecordPermission()` guard.
- `93c43931` MRG4-4: see RG5-16.
- `a41edd49` MRG4-9: the tiering is correct and the IFD1 unlink is bounds-checked.
- `321a6f34`, `5528a958` (deslop): behaviour-preserving.

**Camera / engine / GL / video**
- `b1f7869e` A.2: the retry reuses the transaction generation, so it stays bounded. The old bare rollback was a no-op restore, so nothing is lost.
- `7f137b9b` MRG4-3: correct BLOCKED classification.
- `691943df` A.3: `chooseVideoSize` reads the same request/caps the reconfigure uses. Photo returns false.
- `a36aa43b` A.9: a pre-start cancel retires exactly once, and the per-owner cancel cannot abort the invalidation.
- `69d58550` A.10: VM and Engine share `rearReturnLens`. The PMA110 change is only the band after a front trip, which is the fix.
- `385093ae`, `d1c06597`: status only, and a frozen-codec read with the same formula.
- `cc23de76` A.12: under admitted app-side AE no wire key derives from EV, and the NO_OP case requires an EV-only delta.
- `36e93446` A.13: `chars(shot=false)` equals the old default. BURST/AEB continuations now share the metering gate, which leaves PMA110 unaffected.
- `c7953e52`, `49fc1307`: same order and `plan.useHlg` equals the removed local.
- `e9f39680` A.17: RG5-4.
- `54c90d3a` A.18: RG5-9.
- `85e1bef8` A.19: RG5-3.
- `7797d0b3` A.22: the helper is byte-equivalent to both inline copies. The acquisition branch now also orphans. One pre-existing note: a detach failure in the orphan sweep can still fail an active encoder.
- `c64b53b2`: same value.
- `47d5442b` (deslop): exact negation.
- `8ae920d5`, `44dca067`: a token-level comparison with comments stripped is identical across all 21 touched main files.

**UI / VM**
- `c1cd9bef` A.1: correct. The admission flag is Engine-owned.
- `1218a635` A.4/A.21: same prefs file (`permission_state`), with a process-singleton SharedPreferences, so it is coherent. RG5-8.
- `79e1e2ea` (deslop): behaviour-preserving, with an identical synchronous commit.
- `6f29df98` A.5: also clears a still-true slot on an unrelated door's rollback, which the commit acknowledges. Acceptable.
- `53c0ac65` A.6: no reader of `pending*UntilInventory` sits between the old and new arming points. Saves are debounced, so nothing can persist the pre-arm values inside `setResolvedOptics`.
- `41ff4a53` A.7: dropping `!cameraRoutes.back` matches the Engine gate exactly. One residual I did not trace: before the first route publication, the default `activeCameraRoute = BACK` on a device with no back camera now admits the door in the VM. The Engine refuses it on the same input, so the two cannot disagree.
- `a2665544` A.8: correct.
- `9097cb95` A.16: identical on the PMA110 logical/standalone Ready states (the displayed set equals the request when the session carries it). The deliberate change is that chips stay enabled during a reopen.
- `2e10888c` A.20: RG5-1. The OSD label is byte-identical (same rounding, same branches).
- `6e75d245` A.23: RG5-2. The ISO ladder change leaves 50–25600 unchanged (PMA110's advertised range lies inside it). It adds 25/32/40 and 32000–102400 only where a sensor advertises them.
- `992ec720`, `7a2d6cb5`, `597c40df` A.24/MRG4-8: RG5-5. The 767 half-press DOWN/UP pair, ~3 ms apart, still collapses inside the 40 ms controls throttle exactly as before.
- `8887ae66` C.8, `ec5de200` C.10: correct. `formatEvComp` carries no unit, so "%1$s EV" does not double it.
- `eba84af6` C.9 and `dfb122f8` MRG4-1: RG5-6 and RG5-7.

**Tools / release / build**
- `42f450d2`, `d0303120`, `248801fe`, `9596c16a`, `3521e6fc`: correct. Kotlin and Python decode the properties file identically and code-point parity holds.
- `6d93afa6`: regression-free, but the seal is incomplete (RG5-12, RG5-13).
- `c6598aae`: RG5-14.
- `37504df5`: fingerprints are bound to the cited lines.
- `b1c7bf0e`: RG5-17.
- `c09cd6c2`: `allWarningsAsErrors` applies to every compilation.
- `0a22ffb2`: RG5-15.
- `d673580c`, `13f3e070`, `a2208e0d`, `7ad443b6`, `4dbbfcac`, `8f8ec5c3`, `b952effc`: correct. These are pure re-cites (every manifest fingerprint re-hashes) and doc checks.

Read-only checks run by the tools lane: `python3 tools/check_docs.py` 194/0, `test_upload_key_policy` plus `test_immutable_release` 49 OK, coverage tests 21 OK. The manifest loads all 9 rows.

## Final sweep (commonly missed)

- **Merge conflicts** (`PhotoFormatToggles` and the residual manifest): resolved correctly. A2's `sessionOutputs`/`cameraReady` signature is kept with no default on the route facts. Manifest fingerprints are unchanged.
- **Threading:** the new VM state (`deferredProgressStatus`, `readyDngOnlyAnnounced`, `MomentaryHold`) is written only under the status gate or on the main thread. `acceptedDngOnly` is reset per CAS attempt like its neighbours.
- **Persistence:** momentary holds and the auto punch-in assist are the only new transient-vs-persisted split (RG5-5). Every save path funnels through `currentExtras` and `operatorOwnedControls`. `storeMemorySlot` was checked: it uses `operatorOwnedControls`.
- **EN+KO:** the new strings (`a11y_exposure_meter*`) have KO entries. `a11y_ev_value` is `translatable="false"`, which is consistent with the EV exemption.

## Files covered

`ui/CameraViewModel.kt`, `ui/ZoomMath.kt`, `ui/MomentaryHold.kt`, `ui/ExposureMeterSpeech.kt`, `ui/CameraScreen.kt`, `ui/controls/{ManualDials,PhotoFormatChips,ProControls,ProSheet,FnQuickActions,RulerAccessibility}.kt`, `ui/overlays/Overlays.kt`, `camera/{CameraState,CameraStatus,CameraEngine,CameraController,CaptureCapabilities,AutoExposure,Teleconverter}.kt`, `focus/MacroProximity.kt`, `gl/GlPipeline.kt`, `storage/MediaStoreWriter.kt`, `capture/*` (EXIF splice/compose), `video/VideoRecorder.kt`, `AudioDenialReason.kt`, `MainActivity.kt`, `tools/{build_immutable_release,check_docs,verify_host,upload_key_policy}.py`, `app/build.gradle.kts`, `docs/FIELD_CHECKS.md`, `docs/plans/2026-10-02-rpl-cycle4.md`, `docs/play-console-submit.md` (measured equivalents), tests `EffectiveFocalUnityTest`, `ZoomRulerScaleTest`, `ZoomMathTest`.
