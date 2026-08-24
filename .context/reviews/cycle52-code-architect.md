# Cycle 52 code-reviewer + architect review

Date: 2026-08-25  
Reviewed revision: `96732cc9` (`origin/main`)  
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle52.868ovy/repo`

## Result

Five findings: **2 Medium, 3 Low**. Four are High-confidence confirmed code/lifecycle defects or
invariant violations; one is a Medium-confidence multi-device risk whose concrete file-size
precondition still needs device/file evidence. No source was edited. This report also includes the
requested critic, verifier, document-specialist, and native Android/Compose designer sweeps.

## Coverage inventory and method

The tracked revision contains **540 paths**. I inventoried and examined the complete review-relevant
surface, not a sample:

- 103 production Kotlin/Java files under `app/src/main/kotlin` and `app/src/main/java`, including all
  camera, GL, capture, storage, video, focus, UI, controls, overlays, review, theme, Activity, policy,
  and input files.
- 241 JVM/Robolectric/Compose test files, 4 instrumented-test files, and 4 debug-only files/configs.
- 25 host/release/coverage tool and tool-test files plus 14 tracked device-harness/self-test files.
- 15 Android resource/asset files, 13 build/configuration files, 72 committed documentation/privacy/
  Play-asset paths, 44 historical review-context paths, and 5 remaining tracked root/support files.

Binary PNG/font/JAR assets were covered through their manifests, parsers, digests, consumers, and
the authoritative gates rather than treated as source text. Historical completed plans/reviews were
read for supersession and recurrence, not mistaken for current implementation truth.

Before reviewing code I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` in full.
The source sweep covered imports/layer direction, public and internal state writers, synchronized and
atomic ownership seams, callbacks under locks, executor/queue bounds, provider/native lifetime,
error/cleanup paths, numeric bounds, file parsers, UI state machines, resource parity, and every test
name/claimed seam. I traced the cycle-51 changes and then performed a final repository-wide missed-
issue scan.

Validation evidence:

- `python3 tools/verify_host.py` passed: debug APK/androidTest assembly, the full JVM/Robolectric/
  Compose suite, debug lint, Partition-A coverage (8427/8442, 99.82%), 133 tool tests, 9 coverage-tool
  tests, 195 device-harness self-tests, 154 documentation checks (24 optional-private skips), Python
  compilation, and `git diff --check`.
- A focused in-memory PNG reproduction confirmed finding C52-CA-04.
- No device, deployment, Camera2 HAL, MediaProvider, physical converter, microphone, HDR-display, or
  browser action was run. Browser automation is inapplicable to this native Compose app, and device
  action was forbidden. A3/A4/A5/D1/E1/E2 therefore remain field obligations, not inferred passes.

## Findings

### C52-CA-01 — camera-policy sequence admission is split from its StateFlow write

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed concurrency defect.
- **Evidence:** `CameraViewModel.kt:1082-1091` calls
  `cameraPolicyPublicationSequence.getAndAccumulate(...)`, decides that a publication is newer, and
  only then enters `_state.update`. The sequence is not rechecked inside the reducer. By contrast,
  `CameraReadyPublicationGate` documents and implements the necessary ownership recheck at
  `CameraState.kt:1278-1326`, and the architecture explicitly requires the recheck inside the
  StateFlow reducer at `docs/ARCHITECTURE.md:316-318`. The only ViewModel test,
  `CameraViewModelRobolectricTest.kt:920-927`, invokes sequence 2 and then sequence 1 serially; it
  never opens the admitted-old-publication window.
- **Failure scenario:** thread T1 admits `CameraPolicyPublication(1, true)` and pauses before
  `_state.update`; T2 admits and writes `(2, false)` after a replacement Ready/resume; T1 resumes and
  writes `true`. The permission gate can therefore reappear over a healthy camera even though the
  atomic sequence remains 2 and future sequence-1 callbacks are rejected.
- **Suggested fix:** use one serialized publication gate whose monitor spans the ownership check and
  state mutation, or recheck `cameraPolicyPublicationSequence == publication.sequence` on every
  `_state.update` retry before copying. Add a deterministic two-thread/latch test for exactly
  `seq1 admitted -> seq2 committed -> seq1 resumes`, including input-block and preserved-review state.

### C52-CA-02 — the review spool's 64 MiB input ceiling can reject a valid saved hi-res still

- **Severity / confidence:** Medium / Medium
- **Classification:** Likely multi-device correctness/UX risk; manual validation required for actual
  encoder output sizes.
- **Evidence:** `LatestHeavyWorkLane.kt:103-117` applies one hard 64 MiB per-source limit, and
  `spoolReviewSource` returns null as soon as the next read crosses it at `:130-154`.
  `MediaReview.kt:453-471` maps that to decode failure; the gallery maps it to `Failed` at `:729-737`,
  and fullscreen review maps it to the generic open-image error at `:649-669`. The save path imposes
  no corresponding compressed-byte ceiling: `StillCapturePipeline.kt:144-164` writes a hi-res HAL
  JPEG verbatim specifically to support approximately 200 MP outputs. The current design authority
  says that in-app review honors that passthrough JPEG's EXIF orientation at
  `docs/ARCHITECTURE.md:95`, without disclosing a file-size exclusion.
- **Failure scenario:** a non-PMA110 device advertises the supported hi-res Camera2 path and returns a
  structurally valid 70 MiB passthrough JPEG. TeleCam publishes it successfully, but both its 240 px
  gallery thumbnail and fullscreen 3000 px review deterministically fail before bounds decode. The
  user sees a failed tile/open-image error for the app's own valid latest capture.
- **Suggested fix:** separate compressed-source/disk admission from decoded-pixel admission. Use a
  ceiling consistent with every supported save lane (preferably provider-reported length plus a
  process/disk budget), or a stable seekable descriptor for package-owned immutable rows while
  retaining bounded spooling for owner-unverified media. At minimum, provide a truthful metadata/
  external-view fallback for over-limit valid app-owned captures. Test just-below/above-limit valid
  JPEGs and a realistic hi-res fixture; measure actual compressed sizes on a capable device before
  choosing the production ceiling.

### C52-CA-03 — review spool files have no crash/restart reclamation owner

- **Severity / confidence:** Low / High
- **Classification:** Confirmed lifecycle/resource leak risk.
- **Evidence:** `spoolReviewSource` creates `cacheDir/review-source-*.bin` at
  `LatestHeavyWorkLane.kt:123-148`. Normal close attempts a one-shot, unchecked `file.delete()` and
  releases the byte lease regardless of the result at `:82-100`; failure cleanup does the same at
  `:149-155`. No production or startup code searches for that prefix or owns stale-spool cleanup
  (the only references are creation/current-owner tests). The tests close every constructed source;
  none models process death or a failed delete.
- **Failure scenario:** the process is killed while a large thumbnail/full-review provider read or
  decode owns a 64 MiB spool. The in-memory lease disappears, but the file survives in cache. Repeated
  kill/relaunch/review cycles accumulate orphan files until cache pressure or low storage makes later
  camera saves/reviews fail. Android may evict cache, but it provides no prompt or deterministic
  reclamation guarantee for this app-owned prefix.
- **Suggested fix:** place spools in a dedicated private directory and perform bounded stale-file
  reclamation once per process before first admission (with exact name/type/no-follow checks), while
  keeping live generation files distinguishable. Treat a failed normal delete as retained cleanup
  work rather than silently releasing all accounting. Add restart-fixture and delete-failure tests.

### C52-CA-04 — screenshot PNG validation accepts out-of-range 8-bit `tRNS` samples

- **Severity / confidence:** Low / High
- **Classification:** Confirmed release-evidence parser false green.
- **Evidence:** for color type 2, `tools/check_docs.py:246-255` checks only placement, uniqueness, and
  six-byte length. PNG stores three 16-bit transparency samples; when IHDR bit depth is 8, the unused
  high bits must be zero, so every sample must be at most 255. The parser never unpacks or bounds the
  values. A CRC-correct 1x1 PNG with IHDR `(bit_depth=8,color_type=2)`, `tRNS=(256,0,0)`, a valid
  four-byte filtered IDAT, and IEND was reproduced locally; `png_metadata` returned
  `(1, 1, 8, 2)` instead of `None`. Existing mutations cover length/order but not sample range.
- **Failure scenario:** after refreshing the screenshot digest, a malformed export containing a
  nonzero high byte in a transparency sample passes the release documentation gate even though a
  strict decoder may reject or interpret it differently. The evidence checker then claims stronger
  PNG validity than it proved.
- **Suggested fix:** unpack the payload as `>HHH` and require every value `< (1 << bit_depth)`; add
  CRC-correct boundary mutations for 255 (pass) and 256/65535 (fail), with no traceback.

### C52-CA-05 — optics rollback performs external publication and component work while holding the Engine monitor

- **Severity / confidence:** Low / High
- **Classification:** Confirmed architecture/invariant violation; runtime contention/deadlock impact
  is a likely risk rather than a reproduced device failure.
- **Evidence:** `rollbackOptics` is `@Synchronized` at `CameraEngine.kt:849-977`, but while holding the
  Engine monitor it posts GL state, calls controller setters, and invokes external callbacks including
  `onOpticsRollback`, `onCapsReady`, `onVideoSizeChosen`, `onPreviewAspect`,
  `onCameraReadyChange`, and `onStatus` (`:885-976`). The same file's Ready path deliberately collects
  publications and calls them after unlocking. The design authority says optics external callbacks
  run after unlocking at `docs/ARCHITECTURE.md:301-307`.
- **Failure scenario:** a failed optics door enters rollback while a callback performs a contended
  StateFlow/status publication or a future callback synchronously queries another owner. Camera
  health, REC admission, controls, and later optics intents that need the Engine monitor all wait
  behind non-transactional UI/component work; a reverse callback lock can turn that into ABBA. The
  8,011-line facade and its direct ViewModel callback surface make such a future change difficult to
  audit locally.
- **Suggested fix:** under the monitor, mutate only the rollback packet and collect immutable
  controller/GL/UI commands plus publications. Release the monitor, then execute them in documented
  order with generation/identity rechecks where necessary. Add a lock-state test for every rollback
  callback (matching `CameraPolicyCallbackOwnershipTest`'s Ready assertion) and a reentrant/blocked
  callback test. Longer term, extract optics transaction publication from the camera/audio/storage
  facade so lock ownership is explicit rather than comment-enforced across one god object.

## Critic sweep

- C52-CA-01 survives challenge: an atomic max protects admission order only; it grants no lease
  through the later StateFlow write. Sequential stale-callback tests are insufficient.
- C52-CA-02 is not claimed as a present PMA110 failure: PMA110's hi-res path is dormant and no >64 MiB
  device output was measured. It remains a real reachable contract mismatch on the intentionally
  supported multi-device hi-res path, hence Medium confidence rather than High.
- C52-CA-03 is not based on assuming cache is permanent. The defect is absence of deterministic app
  ownership after abnormal termination; OS cache eviction is an eventual possibility, not cleanup
  proof.
- C52-CA-04 was reproduced against the production parser, so it is not a speculative reading of the
  PNG grammar.
- C52-CA-05 does not assert a currently reproduced deadlock. It reports the confirmed lock/invariant
  violation and keeps the impact classification appropriately low.

The requested REC-packet hypothesis was also challenged. `videoSize`/`videoFrameRate` writers are
not universally under the Engine monitor, but production ViewModel wiring sets
`isRecording/isRecordingStarting` before dispatching admission and refuses interactive video-setting
writes thereafter; encoder inventory is needed before a successful candidate admission, and route
caps reconciliation is ordered before Ready. I found no concrete successful production interleave,
so it is not reported as a finding.

## Verifier sweep and test gaps

- The full host gate is green but does not force C52-CA-01's check-to-write interleave, abnormal
  process death during C52-CA-03, a valid >64 MiB review source, or `tRNS` sample-range mutations.
- C52-CA-01 can be deterministically verified with a hook/latch immediately after sequence admission
  and before `_state.update`; final truth must remain sequence 2.
- C52-CA-02 needs both host fixtures and a capable-device measurement. Compilation or a small fake
  JPEG cannot establish a safe production compressed-size ceiling.
- C52-CA-03 needs a fresh-process-style directory fixture or explicit startup cleanup seam; ordinary
  `use`/close tests prove only graceful cleanup.
- C52-CA-05 needs lock-state assertions; test callback reentrancy on Java's reentrant monitor alone
  does not prove absence of cross-thread lock inversion.

## Document-specialist sweep

`CLAUDE.md`, architecture, field ledger, resource strings, privacy copy, Play assets, completed plans,
and source comments were checked against executable names and behavior. The material drift is included
in C52-CA-02 (unqualified hi-res in-app review claim versus the hidden 64 MiB refusal) and C52-CA-05
(callbacks-after-unlock authority versus synchronized rollback). EN/KO resource parity and documented
field status passed the committed checks; no additional documentation-only finding survived the final
sweep.

## Native Android / Compose designer sweep

I reviewed information architecture, Sony-style quiet-viewfinder policy, affordances, touch sizes,
focus restoration, keyboard/D-pad actions, semantics/live regions, contrast tokens, responsive phone/
large-screen behavior, rotation, loading/empty/error/restart states, dark system bars, EN/KO, and RTL
anchors. No additional source-provable Compose defect survived the sweep. C52-CA-02 does have a user-
visible state consequence: a valid large app-owned still is represented as failed rather than a
truthful over-limit/metadata/external-view fallback. Device-only visual/acoustic/MediaProvider claims
remain explicitly unverified.

## Final missed-issue sweep

I rechecked every production package and its matching tests after drafting the findings, searched for
additional stale sequence gates, unsynchronized packet writers, unbounded queues/loops, swallowed
cleanup failures, model-string seams, hardcoded user-facing prose, locale asymmetry, and doc/code
authority drift. No further issue met the threshold for a concrete, non-duplicate finding.
