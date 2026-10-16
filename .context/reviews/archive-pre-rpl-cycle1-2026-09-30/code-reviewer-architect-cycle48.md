# Code reviewer + architect review — cycle 48

Date: 2026-08-25  
Reviewed revision: `ad64188a` (`origin/main`)  
Workspace: `/tmp/find-x9-ultra-cycle48.Gvbytf`

## Result

One new actionable defect survived source-level verification. It is a confirmed generation/rollback
bug at the boundary between Camera2 source precision, encoder selection, GL transfer, ViewModel
presentation, and settings persistence. No source was modified as part of this review.

## Authority, inventory, and review method

I read `CLAUDE.md` first and then the complete current `docs/ARCHITECTURE.md`. I also read the
committed `docs/FIELD_CHECKS.md` evidence ledger so unresolved device/provider risks would not be
mistaken for host-confirmed defects. Tests, comments, historical reviews, and completed plans were
treated as leads and claims, not as proof.

The tracked inventory at this revision contains 528 paths:

- 103 production source modules (102 Kotlin and one Java): the Activity/application/policy roots;
  all 35 `camera/` modules; all `capture/`, `focus/`, `gl/`, `stab/`, `storage/`, `video/`, and UI
  modules, including the top-level UI, controls, overlays, review, and theme packages. The
  architecture module-map check independently confirms that every production Kotlin/Java module is
  named by the current design authority.
- The main/debug manifests, Gradle and version/signing configuration, ProGuard/stability/baseline
  inputs, both locale trees, XML resources, fonts, launcher assets, privacy page, and store assets.
- 244 Android tests (237 JVM/Robolectric/Compose tests, four instrumented tests, and three debug
  hosts), plus 33 Python files across immutable build/release tooling, coverage tooling, and the
  device harness, their tests, and both shell utilities.
- The README, privacy/Play/field/architecture authorities, all historical implementation plans and
  retained review reports, and the checked-in asset-validity metadata.

The source review used a complete file/declaration/import inventory plus cross-file traces of the
major runtime paths: Activity permission/input/lifecycle ownership; ViewModel state, restore, and
rollback; Engine optics/session generations; Controller capability/session fallback; GL generation
and transfer replay; still/RAW/video capture; recorder admission/finalization; exact-family
MediaStore durability/delete/recovery; and review publication. The final sweep covered mutable
cross-thread fields, monitor/atomic ownership, executor admission and shutdown, ignored failures,
assertions, route/model branching, capability normalization, requested-versus-accepted session
truth, persistence, localization, manifests, release provenance, and the complete cycle-47 change
surface.

## Finding

### C48-CA-01 — failed transfer/codec reconfiguration restores a hybrid video pipeline

- **Severity:** High
- **Confidence:** High
- **Classification:** Confirmed code/architecture defect. The inconsistent state follows directly
  from the production branches; a real configure failure is device-dependent, while the repository
  already has a forced rollback seam suitable for deterministic host reproduction.

**Evidence**

1. `CameraEngine.kt:476-500` calls `OpticsSnapshot` the accepted transaction baseline and now
   includes `transfer`, but it does not include `videoCodec` or `videoEncoderCandidates` even though
   those three values jointly define the video pipeline.
2. `CameraEngine.kt:2862-2873` updates `videoEncoderCandidates` and `videoCodec` first, then calls
   `setTransfer(SDR)` when the new codec is not HEVC. `CameraEngine.kt:2473-2504` starts a generation-
   owned reconfiguration containing only the transfer.
3. On failure, `CameraEngine.kt:768-818` restores `before.transfer` and republishes Ready, but it
   neither restores codec/candidates nor publishes any of those values to the UI. Despite its
   “Complete Engine -> UI rollback publication” contract, `OpticsRollbackPublication` at
   `camera/OpticsConstraints.kt:22-35` contains no transfer, codec, or encoder selection.
4. The ViewModel live codec path at `CameraViewModel.kt:2869-2888` calls the split Engine setters and
   then publishes the new `videoCodec`/`transfer`. Its rollback reducer at
   `CameraViewModel.kt:911-958` restores mode/lens/facing/controls/declaration but not the video
   pipeline fields. It then schedules persistence of that incomplete rollback.
5. Recording admission consumes the Engine tuple, not the UI tuple:
   `CameraEngine.kt:4996-5006` filters the current candidates by both `videoCodec` and
   `encoderSelectionAdmitsTransfer(..., transfer)`. Thus the hybrid state is operationally visible;
   it is not merely stale presentation.
6. The new regression at
   `ModeRollbackOwnershipRobolectricTest.kt:149-167` invokes `engine.setTransfer()` directly and
   asserts only Engine transfer and Ready. It does not enter through `CameraViewModel.onTransfer` or
   `onVideoCodec`, and it never asserts ViewModel state, codec/candidate rollback, persistence, or
   subsequent recording admission. The green test therefore does not cover the cross-file contract
   that is broken.

**Concrete failure scenarios**

- In Video/HEVC/SDR, the operator selects HLG. The ViewModel installs HLG-capable encoder candidates,
  Engine begins an SDR-to-HLG Camera2 reconfiguration, and the HAL rejects the new session. Engine
  correctly restores SDR and republishes the outgoing Ready session, but the UI and persisted
  settings still say HLG. A later recording is configured from Engine SDR truth while the shooting
  surface promises HLG, so the take is not the selected profile.
- In Video/HEVC/HLG, the operator selects AVC. `setVideoEncoders` immediately installs AVC candidates
  and `videoCodec=AVC`; the nested transfer transaction requests SDR. If that reconfiguration fails,
  rollback restores only HLG. The resulting Engine tuple is AVC candidates + AVC codec + HLG
  transfer, while UI/prefs say AVC + SDR. The exact admission filter at lines 5002-5006 rejects all
  AVC candidates for HLG and reports `SELECTED_CODEC_UNAVAILABLE` even though the viewfinder has just
  returned to Ready and the UI shows a valid AVC/SDR choice. Re-selecting the same option happens to
  retry convergence, but pressing REC directly fails.
- Settings/MR restore has the same split boundary: `CameraViewModel.kt:1381-1420` begins the optics
  transaction with the target transfer and only afterward installs the recalled codec/candidates.
  A failed recall can therefore restore the prior transfer under the recalled codec tuple.

**Architectural cause**

The recent change made `transfer` an optics-transaction member but left the other fields that
normalize and consume it behind independent imperative setters. The transaction is described as a
complete desired packet, yet the packet boundary cuts through one invariant. The result is a
distributed rollback protocol across `CameraViewModel`, `CameraEngine`, Camera2 session truth, GL,
and recorder admission. This concrete defect spans more than two `CameraEngine` responsibility
regions, so it satisfies the focused-extraction exit criterion recorded for deferred AGG35-08; it
does not justify a wholesale facade rewrite, but it does justify extracting this bounded packet.

**Suggested fix**

- Introduce one immutable video-pipeline selection (normalized codec, exact ordered encoder
  candidates, requested video transfer, and active Camera2/GL transfer) and make a single Engine
  command own its generation, reconfiguration decision, commit, and rollback. Do not let
  `setVideoEncoders` trigger a nested `setTransfer` transaction.
- Snapshot and restore the complete Engine tuple. Publish the rollback result required to reconcile
  `CameraUiState` before settings are saved. Because Photo intentionally retains the next-Video
  profile while Engine actively uses SDR, model “requested next-video transfer” separately from the
  accepted active-session transfer instead of conditionally overloading one field.
- Bind `AcceptedCameraSession` or an adjacent accepted-video record to the source-HLG truth and the
  normalized encoder tuple used for REC admission, so Ready cannot be published for a tuple that no
  longer matches the accepted session.
- Add forced-failure tests through the real ViewModel entry points for SDR->HLG, HLG->SDR, and
  HEVC/HLG->AVC/SDR; assert Engine, GL replay snapshot, ViewModel state, candidates, persistence, and
  subsequent REC admission. Add settings/MR recall and rapid supersession cases. Each terminal state
  must be either the complete old tuple or the complete new tuple—never a mix.

## Verification and evidence limits

- `./gradlew :app:testDebugUnitTest` passed completely after supplying the existing Android SDK path.
- `python3 tools/check_docs.py` passed all 151 applicable checks; 24 documented optional private-file
  checks were skipped. `git diff --check` passed before this report was created.
- `python3 tools/verify_host.py` could not start because the host Android SDK lacks the newly required
  Android Emulator `glslangValidator`. The direct Python tooling suite ran 120 tests: 113 passed and
  seven ended in the same SDK-preflight error. These are environment-blocked validations, not source
  failures; the preflight reported the missing component precisely. The chained coverage/device-
  harness self-tests did not run after that suite exited nonzero.
- No device, camera, microphone, MediaProvider consent flow, immutable APK build, or deployment was
  run. The finding above is source-confirmed, but the exact HAL error frequency and visible recovery
  timing remain device observations.
- The open field-ledger items A3, A4, A5, D1, E1, and E2 remain manual-validation risks exactly as
  documented in `docs/FIELD_CHECKS.md`; this review did not relabel them as defects or infer passes.

## Final missed-issue sweep

No second independent code-quality, SOLID/layering, lifecycle, concurrency, media-durability, or
maintainability finding survived cross-file checking. The broad 7,000-plus-line `CameraEngine`
decomposition remains an already-recorded deferred debt and is not duplicated here; only the focused
video-pipeline packet boundary above now meets its recorded extraction criterion. Current manual
field checks and the missing local shader validator are validation limits, not additional product
findings.
