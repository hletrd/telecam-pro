# Critic review — recent change surface (2026-09-30)

Scope: `git log -60` with a close read of the four most recent functional fixes, which are the
riskiest. They are 6b2f07dd (union-volume identity), 2eb57e4e (silent insert exits),
0ab5c1ba (pending-token audio admission) and f67023d5 (shutter unit switch). Also read: their
tests, the sibling code paths, and the cycle-57 fixes (1ed6d1b6, b73dc0d0, 4979a09a, ed7a6ad3)
where they overlap. Settled owner decisions in CLAUDE.md were not relitigated.

Verification run (read-only, no source edits):
`./gradlew :app:testDebugUnitTest --tests CameraEngineRecordingPreNativeTest --tests PendingTokenNativeStartTest --tests ShutterModeConversionTest --tests MediaStorePendingDiscardIdentityReaderTest`
exited 0.

Findings: **8** (0 High severity, 4 Medium, 4 Low).

---

## CR-1 — Pending-token owner widening admits the WHOLE Engine, not just the recorder's audio worker (Medium, confidence Medium, status: likely / needs-manual-validation)

- `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:1252` (`runNativeWithResult`), `:1279-1281` (`foreignRecorderHoldsAdmissionLocked`), `:1294` (`runNativeWithPublication`)
- Owner identity: `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:245` (`processNativeOwner = Any()`), which is passed to:
  - the REC token: `CameraEngine.kt:5822` (`snapshotAdmission(processNativeOwner)`)
  - the recorder workers: `VideoRecorder.kt:753` and `:919` (`processAdmissionToken?.owner`)
  - **every GL generation and preview-output EGL acquisition**: `CameraEngine.kt:248/254` → `GlPipeline.kt:321, 369, 747`
  - **every Camera2 open and session acquisition**: `CameraEngine.kt:2417, 4187` → `CameraController.kt:330, 350, 438, 836, 901`

**Why.** The fix admits "a pending token's own owner". The audio worker, the GL pipeline and the
Camera2 controller all share one owner object, so while REC setup is pending the atomic gate now
also admits that Engine's own GL/EGL and Camera2 native acquisitions. The removed comment said
explicitly that "General GL/Camera2 acquisition must wait for that setup to publish or retire".
Only the advisory, non-atomic `nativeAcquisitionMayProceed()` (`CameraEngine.kt:6899-6907`) still
refuses on `setupPending`. That leaves two inconsistencies:
- The advisory check reports "blocked" while the atomic gate reports "admitted".
- The check→native-entry race that the atomic gate existed to close (`convergeRetainedSurfaceAfterNativeRefusal` → `awaitRecorderSetupReplay`, `CameraEngine.kt:7005-7020`) can no longer fire for the same Engine during pending.

**Failure scenario.** The advisory check passes. The same Engine then snapshots a REC token
(shutter pressed), and a preview-surface bind or GL restart enters `runNativeAcquisition`. Before
this change it was refused and replayed after publication. Now it runs concurrently with MediaCodec
setup and the encoder's first swap. The frozen REC packet is rechecked at publication, so the likely
result is a spurious REC failure or a re-bind, not corruption. It still runs a path the design says
must not run. `CameraEngineRecordingPreNativeTest.kt:798` ("same Engine preview bind pending race
rebinds active graph after publication") still passes. However, its injected same-owner token no
longer produces the atomic refusal it was written for, so any second bind now has to come from
elsewhere, and the test may pass vacuously. Needs a check that the refusal is actually observed.

**Fix.** Scope the exception to the token, not the owner. Route the audio worker's
`startRecording` through the token-scoped door that already exists (`admissionGate.runPendingNative(token, …)`,
`VideoRecorder.kt:1619`), or give recorder workers a distinct owner derived from the token. Then
restore `pendingToken != null` refusal for every other caller. Add a gate test in which a GL-style
caller with the same Engine owner is still REJECTED while pending. Tighten the CLAUDE.md wording
("admitted … by OWNER") to say whose workers are admitted.

## CR-2 — New identity-read warnings are per-retry and drain the 120-row reserved log budget (Medium, confidence High, status: confirmed by code reading)

- `app/src/main/kotlin/me/hletrd/telecampro/storage/PendingDiscardJournal.kt:636-644` (`unavailable()`/`ambiguous()`), `:627-629`, `:685-688` (`missing()`)
- Retry driver: `MediaStoreWriter.kt:236-249` (owner of up to 32 claims), `:66-67` (250 ms initial delay, 30 s maximum, retried with no end)
- Budget: `camera/DiagnosticTelemetry.kt:32-36` (`RESERVED_DIAGNOSTIC_ROW_BUDGET = 120` for the whole process lifetime; `DiagnosticLog.w/e`)

**Why.** Each call to `MediaStorePendingDiscardIdentityReader.read()` that ends as
Unavailable or Ambiguous now spends one reserved row. That reader runs:
- on every retry of every parked identity-recovery claim, with exponential backoff capped at 30 s
- on launch recovery of each journaled row
- inside `mark()`

The rows are neither change-gated nor summarized. CLAUDE.md requires "Any new per-frame or
per-tick log must be change-gated or thresholded". A retry loop is a tick.

**Failure scenario.** A row stays Unavailable. Causes include an unmounted SD volume, a provider
timeout, or a future OEM provider quirk of the same class as the one just fixed. One stuck claim
spends about 8 rows in the first minute, then 2 per minute, and uses up the budget in about an hour.
With the 32-claim owner full (the tablet failure mode before the fix), it takes about a minute. After
that, every `DiagnosticLog.w/e` in the process is silently dropped for the rest of its life. That
includes camera-fault, recorder-failure and still-save errors (`StillCapturePipeline.kt:182-235`
goes through the same facade). The diagnostic that "finally located" this defect would hide the
next one.

**Fix.** Log the first read failure per URI/claim and then only when the reason changes (or emit a
constant-memory summary like `FrameGapAccumulator`). Also log once when a claim terminates. Add a
test that N retries of one Unavailable URI spend at most 1–2 reserved rows.

## CR-3 — Fn "Shutter" toggle can enter ANGLE in ISO-priority/PROGRAM and freeze the AE loop's shutter axis (Medium, confidence Medium, status: likely)

- `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1997` (`onShutterMode` → `withShutterMode`) compared with `:1998-2003` (`onShutterAngle`, which escalates PROGRAM/`autoShutterDriven` to MANUAL)
- `ui/controls/FnQuickActions.kt:112-114` (toggle is gated only by `shutterDialEnabled = manualAeAvailable`, `ControlAvailability.kt:135`)
- `camera/ManualControls.kt:441-446` (`effectiveExposureNs` ignores `exposureTimeNs` in ANGLE); `AutoExposure.kt:178-190` (ISO + angle has no carrier)

**Why.** f67023d5 fixed the value that gets carried across the unit switch, but not the mode
invariant. `onShutterMode(ANGLE)` does not escalate the exposure mode the way `onShutterAngle`
does. `onExposureMode` also forces SPEED on entering ISO (`:1981`) because "an ANGLE derivation
would override the value the AE loop writes into exposureTimeNs". The Fn toggle reaches exactly
that state.

**Failure scenario.** In ISO priority, the user taps the Fn Shutter tile. Controls become ISO +
ANGLE. The loop keeps writing `exposureTimeNs`, which is ignored on the wire, so exposure stops
tracking the scene while the OSD still says ISO priority. In photo app-side PROGRAM, the program
line's time axis freezes at the carried angle in the same way.

**Fix.** In `onShutterMode`, apply the same escalation as `onShutterAngle` (PROGRAM or
`autoShutterDriven` → MANUAL), or refuse ANGLE while `autoShutterDriven`. Add a ViewModel-level
test. The current tests exercise only the pure `withShutterMode`, not the handler wiring.

## CR-4 — Sibling of f67023d5: entering ISO from a MANUAL+ANGLE state revives a stale `exposureTimeNs` (Low, confidence High, status: confirmed by code reading)

- `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1981-1987`

**Why.** `shutterMode` is forced to SPEED, but `exp` is taken from `it.exposureTimeNs` unless the
previous mode was PROGRAM. This is the same bug class the commit fixed: the mode flag flips and a
stale value from the other unit comes back into use.

**Failure scenario.** The user is in M with a 180° angle at 30p (1/60 s), and an old speed of
1/16000 s is still stored. Switching to ISO priority seeds the loop at 1/16000 s. The preview goes
about 8 stops dark, and the loop needs several seconds to converge. A still taken in that window is
badly underexposed.

**Fix.** Seed with `it.effectiveExposureNs()`, for example by applying
`withShutterMode(ShutterMode.SPEED)` before the copy.

## CR-5 — Silent-exit logging (2eb57e4e) is incomplete on the same save path (Low, confidence High, status: confirmed)

CLAUDE.md now states that "every silent exit on the insert / registration / identity path logs a
reserved diagnostic row" and that a save failing with no app log "is the signature of THIS class".
Remaining silent exits on the same path:
- `storage/PendingDiscardJournal.kt:44-49`: a family-identity mismatch on a Present read becomes `Uncertain` with no reason. Only the generic "creation-time identity uncertain" line appears.
- `storage/MediaStoreWriter.kt:1882-1890` (`pendingRegistrationDisposition`): the exceptions from `register`, `delete` and `rowExists` are swallowed. Only the disposition is logged, not why registration failed.
- `storage/MediaStoreWriter.kt:1052-1068` (publish `IS_PENDING=0`): update exceptions are swallowed on every attempt. A publish failure shows the "retained for recovery" copy with no cause in logcat.
- `storage/PendingDiscardJournal.kt:69-78` (`mark`): returns null with no reason on a mismatch.

**Fix.** Log once per URI with the throwable at each of these exits, keeping the CR-2 budget
discipline. Or reword the CLAUDE.md claim to match what is actually covered.

## CR-6 — A full identity-recovery owner closes all capture with only a generic failure message (Low, confidence Medium, status: confirmed by code reading; UX)

- `camera/CameraEngine.kt:5739-5743` (REC refused with generic `RECORDING_FAILED`); `:5128-5133` (still admission false)
- `storage/MediaStoreWriter.kt:1011-1012`

**Why.** The root cause on the tablets is fixed. The design still turns any future
identity-Unavailable class into "every photo and video fails for this process", and the user sees
only a generic "Recording failed" or save-failed message. That is how the tablet defect was first
misfiled as a camera bug.

**Fix.** Add a distinct status for storage-recovery backlog closure. Keep it quiet and OSD-grade,
in line with UX policy (for example "Storage busy — saves paused"). Consider a bounded
recover-on-resume attempt.

## CR-7 — Angle clamp silently changes exposure by large amounts in Photo mode (Low, confidence High, status: confirmed; UX)

- `camera/ManualControls.kt:456-471`

**Why.** The Speed/Angle switch is offered in Photo mode (`shutterDialEnabled` is not gated to
Video). Photo shutters run up to 4 s, and `fps` is the video rate. Toggling SPEED 2 s → ANGLE clamps
to 360° = 1/30 s: a silent change of about 6 stops. The commit message claims "a round trip returns
the same exposure", but that holds only inside the 1°..360° band. The test (`ShutterModeConversionTest.kt:60`)
uses an in-band value.

**Fix.** Either offer ANGLE only in Video mode (the cine convention), or refuse or flag the switch
when the carried exposure is out of band. Correct the round-trip claim.

## CR-8 — Test for the pending-owner fix does not pin the non-audio boundary (Low, confidence High, status: confirmed)

- `app/src/test/kotlin/me/hletrd/telecampro/video/PendingTokenNativeStartTest.kt`

The test covers three cases: the same owner is admitted, and foreign or anonymous owners are
refused. It does not model the production fact that the "same owner" is also the Engine's GL and
Camera2 owner (see CR-1). As written, it cannot catch the widening. Add a case in which a
second same-owner caller representing GL/Camera2 must be refused while pending, or restructure per
the CR-1 fix.

---

## Checked and judged correct

- **6b2f07dd union → primary resolution.** This is the only `getVolumeName` /
  `getExternalVolumeNames` site in `app/src/main` (grep), so no sibling missed it. The PMA110 path is
  byte-identical, because concrete names pass through `resolveVolumeName` unchanged. The row
  `VOLUME_NAME` confirmation fails closed as Ambiguous. The Robolectric test drives the real reader
  against a fake provider, including absence keyed to the resolved volume.
- **2eb57e4e insert logging.** It correctly separates an insert that threw from one that returned
  null, and reservations are still cancelled on both image and video paths.
- **ba5b16e7.** A genuine flake fix: it uses a thread-safe list and awaits the third event.
- **Log facade.** Remaining raw `Log.*` call sites are either `DiagnosticLog as Log` aliases
  (`VideoRecorder`, `StillCapturePipeline`, `VendorTagInspector`) or explicitly budgeted
  (`MainActivity.kt:753-762` BtnDbg).

## Other perspectives (brief)

- **Play release.** The latest plan (`docs/plans/2026-08-27-rpf-cycle57.md`) is still marked
  "blocked — release bundle requires authorized local signing credentials". It is not an app defect,
  but it is the gating item for shipping.
- **Maintainer.** Admission ownership is now expressed in three places that must agree:
  advisory `generalNativeAcquisitionState`, the atomic gate, and token-scoped `runPendingNative`.
  0ab5c1ba changed one of them. A single predicate shared by all three would have made CR-1 a compile
  or test failure instead of a review finding.
