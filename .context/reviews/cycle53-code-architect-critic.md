# Cycle 53 code-reviewer + architect + critic + verifier + test-engineer review

Date: 2026-08-25  
Reviewed revision: `fcf7ba2c` (`origin/main`)  
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle53.cJwfCJ`

## Result

One current finding: **1 Medium**, High confidence. It is a review-source trust-boundary regression
introduced while fixing cycle 52's valid-hi-res-file limit. No source, plan, existing review, or Git
history was modified.

## Coverage inventory and method

I inventoried the complete tracked tree before reviewing behavior: **546 paths** (539 regular
non-executable files and seven regular executable files), comprising 120 `app/src/main` paths (102
production Kotlin files, one production Java file, and manifests/resources/assets), 242 JVM/
Robolectric/Compose test paths (241 Kotlin plus one resource), four instrumented-test files, four
debug-source/config paths, 25 `tools/**` paths, 14 `device-tests/**` paths, 67 committed `docs/**`
paths (including 45 completed plans), 48 historical review paths, three app build/config paths, and
16 remaining root/build/privacy/license inputs. There are no tracked symlinks or submodules.

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` as the current authorities,
then used the completed plans and prior reviews only to distinguish current defects from resolved
history. Every tracked path participated in the inventory, mode/type scan, cross-reference and
dangerous-API searches, or the authoritative build/parser/document gates. The direct source trace
covered every production package and its matching tests: Activity/permission/input ingress,
ViewModel/Compose state and semantics, Camera2 route/session/callback ownership, GL/EGL and analysis,
still/RAW/video/audio lifetimes, every provider/durability/delete/recovery lane, review decoding,
settings/localization, device harnesses, and immutable debug/release tooling. Binary fonts, PNGs,
and the Gradle wrapper were assessed through their tracked identity, consumers, validity manifests,
parsers/digests, packaging, and host gates rather than treated as source text.

The cycle-52 repairs were traced through their current callers and tests: camera-policy publication
is now linearized, rollback effects execute after the Engine monitor, PNG truecolor transparency is
range-checked, standby native create/start/stop uncertainty reaches process quarantine, discard
replay is bound to provider/row identity, and stale review spools have bounded no-follow recovery.
Those resolved findings are not repeated below.

Validation evidence:

- `python3 tools/verify_host.py` passed the debug APK and androidTest assembly, the complete JVM/
  Robolectric/Compose suite, debug lint, and exact Partition-A coverage (**8672/8687, 99.83%**).
- The same gate passed **136** tool/release tests, **9** coverage-tool tests, **195** device-harness
  self-tests, and **155** documentation checks; 24 explicitly optional private-document checks were
  skipped because those files are absent in this clean clone.
- Python compilation and `git diff --check HEAD --` passed.
- No physical device, deployment, Camera2/GL/audio HAL, MediaProvider mutation, converter, HDR
  display, or production signing operation was run. Field checks A3, A4, A5, D1, E1, E2, and E3
  remain evidence obligations, not inferred passes.

## Finding

### C53-CA-VT-01 — app-owned review reopens mutable MediaStore bytes across bounds, pixels, and EXIF

- **Severity / confidence:** Medium / High.
- **Classification:** Confirmed trust-boundary and allocation-safety defect. The false immutability
  invariant and multi-open path are source-confirmed; triggering the worst allocation requires an
  in-place external edit during review.
- **Exact evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:463-486` states that a
    package-owned published row is "immutable by contract" and maps `MediaProvenance.APP_OWNED` to
    `FreshProviderReviewSource`, whose every `openInputStream()` call opens the provider again.
    `APP_OWNED` proves MediaStore attribution, not byte immutability. Android explicitly permits
    another app to obtain user-approved write access to existing shared media through
    [`MediaStore.createWriteRequest`](https://developer.android.com/training/data-storage/shared/media#update-other-apps-media).
  - `MediaReview.kt:514-527` opens that source once for bounds, again for pixel decode, and then
    `:538-547` opens it a third time for EXIF. Therefore one logical review does not own one byte
    identity.
  - The allocation guard is ordered too late for changing bytes. `reviewDecodeSampleSize` derives
    `inSampleSize` from the first handle at `:516-520`; the second handle is decoded at `:521`; only
    after allocation does `reviewDecodedFitsBound` reject an oversized result at `:523-526`.
    `runCatching` can turn a thrown decoder/OOM result into `null`, but it cannot make an attempted
    process-scale allocation safe.
  - `app/src/test/kotlin/me/hletrd/telecampro/ui/review/ReviewDecodeSourceTest.kt:28-59` explicitly
    requires at least three fresh opens, but every open reads the same unchanged file. The immutable
    mutation test at `:62-85` applies only to the owner-unverified spool. No test changes app-owned
    bytes between the bounds and pixel handles or between pixels and EXIF.
- **Failure scenario:** TeleCam restores or still tracks a row attributed to its package. A gallery
  or editor that has received Android's per-row write consent overwrites that same shared-media row
  after TeleCam's bounds read. The first handle can report a tiny JPEG and select `inSampleSize=1`,
  while the second now exposes a 100-200 MP JPEG. `BitmapFactory` attempts the full decoded bitmap
  before the post-decode 3000/240 px check can recycle it, causing severe memory pressure or process
  death. A change before the third handle can instead apply EXIF orientation from different bytes to
  the already decoded pixels. Benign truncation/replacement races also turn a valid latest capture
  into an intermittent failed thumbnail/review.
- **Suggested fix:** preserve one immutable compressed-byte snapshot for *both* provenance classes.
  Do not restore the old unconditional 64 MiB rejection for trusted hi-res output; give app-owned
  media a disk-aware/source-size policy that covers every supported save lane, or a truthful
  metadata/external-view fallback when a safe snapshot cannot be admitted. Bounds, pixels, and EXIF
  must all read that one frozen file identity. A merely fresh or duplicated descriptor is
  insufficient if another writer can mutate the same underlying file.

## Critic challenge

- `APP_OWNED` is not an authorization claim against every future writer. It records which package
  MediaStore attributes the row to; Android's system-consent write route deliberately allows other
  apps to update shared media without changing that historical attribution first.
- Opening each stream read-only does not freeze the provider row. Read-only constrains TeleCam's
  handle, not a separately authorized writer or the bytes returned by later opens.
- The bounded four-thread review pool prevents unbounded worker multiplication but does not bound a
  single decoder allocation made with a stale `inSampleSize`.
- This is not a restatement of cycle 52's 64 MiB finding. That limit is gone for app-owned rows; the
  new defect is the chosen replacement's loss of the prior single-byte-snapshot guarantee.

## Verifier and test-engineer assessment

The complete host gate is green, but its current test encodes the vulnerable behavior by asserting
`opens >= 3` while keeping all three opens byte-identical. Add a deterministic alternating-source
test: the first open supplies a small valid JPEG for bounds, the second a materially larger valid
JPEG, and the third different EXIF. The corrected implementation should consume the provider once,
decode to the requested bound from its frozen snapshot, and apply EXIF from the same bytes. Retain a
separate valid-above-64-MiB app-owned fixture so the safety repair cannot regress cycle 52's hi-res
support. A provider integration test should also modify an attributed row through the supported
system-consent path and confirm either snapshot consistency or a truthful bounded fallback.

No ignored/disabled Kotlin tests or unconditional-success assertions were found. The two Python
`skipUnless(mkfifo)` cases are platform-capability guards for POSIX FIFO fixtures, not hidden product
coverage. Host success proves compilation, pure policies, Robolectric/Compose behavior, parsers, and
harness self-contracts; it does not establish the seven current physical/provider field checks.

## Final missed-issue and skipped-file sweep

After drafting the finding I re-ran the complete tracked inventory against the subsystem map and
rechecked all production packages and their matching tests for stale sequence admission, callbacks
or provider/native work under shared monitors, unsynchronized multi-field writers, unbounded queues
or retries, timeout-without-terminal paths, resource cleanup after partial acquisition, row/URI
identity confusion, parser/allocation limits, model-string seams, hard-coded user prose, EN/KO
asymmetry, insecure Android components/permissions, release-source mutation gaps, disabled or
tautological tests, and documentation/code drift. No additional current issue survived the
failure-scenario and prior-fix challenge.

No review-relevant tracked file was skipped. Optional private maintainer documents named by the
authorities are absent and explicitly non-blocking; binary assets were covered at their executable
validity/packaging boundaries as noted above. The clean clone had no project-private device session
authority, so device-only claims were left in `docs/FIELD_CHECKS.md` rather than guessed.

**Finding count: 1 (Medium).**
