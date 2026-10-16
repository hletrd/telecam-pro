# Review-plan-fix cycle 14 — aggregate review

Date: 2026-08-24
Reviewed HEAD: `fbe31d6`
Mode: host-only; deployment and device interaction disabled

## Review provenance

- `code-reviewer-architect-cycle14.md`
- `perf-reviewer-tracer-cycle14.md`
- `security-reviewer-debugger-cycle14.md`
- `critic-verifier-cycle14.md`
- `test-engineer-document-specialist-cycle14.md`
- `designer-qa-adversary-cycle14.md`

The six specialist-role reports produced 16 raw findings. Adjacent specialties were consolidated
across five concurrent workers because the environment exposed five child slots; every required
role and the repository's registered `qa-adversary` were covered. This aggregate deduplicates the
raw results into 14 current-HEAD findings and preserves the highest reported severity/confidence.
The static debug/unit gate, focused provenance suites, device-harness self-tests, and documentation
checks were green during review. Device gates were blocked by the explicit no-deploy directive and
are not treated as failures.

## Merged findings

### AGG14-01 — immutable release compilation accepts transient unproven source bytes

- **Severity / confidence:** High / High.
- **Evidence:** `tools/build_immutable_release.py:176-251`;
  `tools/tests/test_immutable_release.py:35-105`.
- **Failure:** the writable release export can change A -> B while Gradle reads it and return to A
  before the final digest, exporting B-derived APK/AAB bytes under A provenance.
- **Required fix:** seal the release compiler's complete input owner, verify replacement-relevant
  identity metadata, and add a deterministic release A -> B -> A barrier test.

### AGG14-02 — private APK/AAB inspection copies remain writable across independent readers

- **Severity / confidence:** High / High.
- **Evidence:** `device-tests/run.py:128-169,1473-1487`;
  `tools/check_release_artifact.py:94-131,375-466`;
  `device-tests/tests/test_attestation.py:763-821`.
- **Failure:** one inspector can see transient B while the final digest or original-path check sees
  A, so signer, manifest, ZIP, provenance, and reported hash need not describe one artifact.
- **Required fix:** seal and identity-check the private artifact owner through every inspector and
  test permanent plus A -> B -> A mutation of the private copy on both APK and AAB paths.

### AGG14-03 — report-root relocation can preserve a valid green attestation after failure

- **Severity / confidence:** High / High.
- **Evidence:** `device-tests/run.py:1044-1262`;
  `device-tests/tests/test_attestation.py:1079-1111`.
- **Failure:** publication, final verification, and rollback reopen the report root separately. A
  root rename/replacement makes finalization fail while rollback visits an empty replacement and a
  checksum-valid JSON/sidecar pair survives in the moved original.
- **Required fix:** retain one no-follow report-root/inode owner through publication, finalization,
  and exact-inode rollback; make the sidecar the final commit marker and test root/leaf relocation.

### AGG14-04 — checkout matching does not freeze Git authority with scoped source bytes

- **Severity / confidence:** Medium / High.
- **Evidence:** `device-tests/dtest/contracts.py:452-525`;
  `device-tests/tests/test_contracts.py:242-340`.
- **Failure:** scoped files are frozen twice but HEAD, index, status, and ignored-query truth are
  sampled only once. A ref/index transition with unchanged scoped bytes can admit an APK against
  stale commit or dirty-state authority.
- **Required fix:** derive Git and file truth from one immutable owner, or bracket and identity-pin
  the full authority set, with deterministic HEAD/index/A -> B -> A transition tests.

### AGG14-05 — the harness reopens mutable capture-path source after identity proof

- **Severity / confidence:** Medium / High.
- **Evidence:** `device-tests/run.py:1473-1494`;
  `device-tests/dtest/contracts.py:452-525,612-620`.
- **Failure:** `CAPTURE_SUBDIR` is parsed by pathname after APK/source matching, so the evidence run
  can query or mutate B's MediaStore directory while attesting source A.
- **Required fix:** derive and return the capture-subdirectory contract from the already frozen
  source owner and add a mutation barrier test between identity proof and harness setup.

### AGG14-06 — one granular media permission is misclassified as complete access

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/CameraPermissionPolicy.kt:132-195`;
  `app/src/main/kotlin/me/hletrd/telecampro/MainActivity.kt:313-337,703-715`;
  `app/src/test/kotlin/me/hletrd/telecampro/CameraPermissionPolicyTest.kt:181-213`.
- **Failure:** Images-only or Video-only permission returns `FULL`, permanently suppressing the
  missing collection's request while restore queries both collections independently.
- **Required fix:** model collection completeness separately from any-access, request exactly the
  missing broad type(s), preserve selected-media partial access, and test all grant combinations.

### AGG14-07 — callback detachment is not an in-flight teardown barrier

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4123-4137,
  6171-6199`; `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3395-3429,
  3586-3607`.
- **Failure:** a worker can fetch a callback before `detachCallbacks()`, resume after `onCleared()`
  shuts down `vm-io`, and throw `RejectedExecutionException` or post/mutate after teardown.
- **Required fix:** retire one generation-aware callback sink atomically (or drain admitted calls),
  make residual executor submissions rejection-safe, and force the acquire/clear/resume interleave.

### AGG14-08 — launch media recovery is unbounded across rows and Engine generations

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:127-131,
  6041-6059,6290-6296`; `app/src/main/kotlin/me/hletrd/telecampro/storage/
  MediaStoreWriter.kt:606-703`; `app/src/main/kotlin/me/hletrd/telecampro/ui/
  CameraViewModel.kt:1065-1070`.
- **Failure:** a wedged provider query survives Engine shutdown; same-process relaunches create more
  daemon workers and repeat an unbounded all-row scan, multiplying blocked Binder calls and recovery
  bursts.
- **Required fix:** use one process-wide single-flight recovery coordinator, bounded row batches and
  coalesced subscribers; test a blocked provider through repeated Engine replacement and large sets.

### AGG14-09 — canceled review overlays can accumulate heavy bitmap decodes

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:187,254-321,
  499-504`.
- **Failure:** coroutine cancellation does not interrupt blocking stream decode/EXIF/rotation. Rapid
  close/reopen can run several tens-of-MiB decodes concurrently and discard results without an
  explicit bitmap owner, producing memory spikes, jank, or OOM.
- **Required fix:** add a finite identity-owned decode lane that coalesces the newest request and
  disposes stale results; test A cancel -> B/C with a one-heavy-decode ceiling.

### AGG14-10 — queued SurfaceTexture callbacks still perform duplicate full draws

- **Severity / confidence:** Low / Medium.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/gl/GlPipeline.kt:434-445,908-1311`;
  `docs/BACKLOG.md:1431`.
- **Failure:** when GL/encoder work falls behind, the first callback latches the newest texture but
  every older queued notification still performs preview/encoder/readback work, amplifying load.
- **Required fix:** coalesce framework notifications behind one dirty/scheduled owner, preserve
  real-frame-only encoder/analysis semantics, and add a blocked-draw scheduler test.

### AGG14-11 — active Loupe source/test guidance still teaches the obsolete route gate

- **Severity / confidence:** Low / High.
- **Agreement:** test/document specialist and designer/QA.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraActions.kt:158-166`;
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1983-1985,2903-2905,
  6097-6103`; `app/src/test/kotlin/me/hletrd/telecampro/ui/controls/
  LoupeOverviewGateTest.kt:17-28`; `tools/check_docs.py:595-605`.
- **Failure:** comments still say TELE + Photo + 4:3 although runtime truth is enabled + punch-in +
  (TELE or unified >= 3x), with Photo requiring 4:3 and Video ignoring still aspect.
- **Required fix:** align every cited comment and add a negative docs guard plus composed coverage or
  a precisely scoped Compose-test contract.

### AGG14-12 — Architecture installs the mutable APK that its evidence contract forbids

- **Severity / confidence:** Medium / High.
- **Evidence:** `docs/ARCHITECTURE.md:1179-1210`; `device-tests/README.md:15-24,72-82`;
  `tools/check_docs.py:497-514`.
- **Failure:** the as-built authority tells operators to install the ordinary Gradle output after
  requiring the immutable wrapper's printed APK, allowing manual checks against unprovable bytes.
- **Required fix:** make the command block use one defined immutable evidence path throughout and
  add a docs guard rejecting mutable output in the evidence block.

### AGG14-13 — declared-current Play screenshots show superseded UI and source truth

- **Severity / confidence:** Medium / High.
- **Agreement:** test/document specialist and designer/QA.
- **Evidence:** `docs/assets/play/screenshots/02-pro-settings.png`;
  `docs/assets/play/screenshots/06-video-settings.png`; `docs/play-store-listing.md:306-323`;
  `docs/play-console-submit.md:602-624`; current strings and `ProSheet.kt:736,832,1260,1367-1385`.
- **Failure:** the declared-current images show obsolete labels and claim profiles are applied to
  an SDR stream, contradicting the shipping HLG10-source route and current UI.
- **Required fix:** prevent these stale frames from being treated as submission-ready, record the
  exact recapture requirement/provenance, and make asset validity fail closed when semantic copy or
  source identity changes. Actual device recapture remains subject to the no-deploy directive.

### AGG14-14 — active backlog records obsolete GitHub About truth

- **Severity / confidence:** Low / High.
- **Evidence:** `docs/BACKLOG.md:1011-1020`; current route authorities and live repository metadata
  checked by the specialist on 2026-08-24.
- **Failure:** the DONE owner-action record says DNG exists only in TELE mode and quotes an old
  tagline, inviting a regression of already-correct live metadata and future release copy.
- **Required fix:** update the active completion record to the current any-RAW-lens route/tagline,
  preserve the old wording only as superseded history, and reject the obsolete active-board claim.

## Deferred findings

None. All 14 findings must be scheduled in the cycle-14 implementation plan. The screenshot finding
can be closed host-side only by fail-closing the stale asset set and recording an explicit immutable
device recapture gate; no device work or deployment is authorized in this run. Existing owner-cleared
deferrals in `docs/BACKLOG.md` and earlier completed plans are unchanged and were not re-filed.

## Agent failures

None. The five-worker slot ceiling required grouping adjacent roles; no named role or report was
dropped.
