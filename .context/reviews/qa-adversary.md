# QA adversary review — cycle 35

Date: 2026-08-24

Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518`

Workspace: clean detached cycle worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Coverage and method

The repository-local `.claude/agents/qa-adversary.md` runbook is absent in this clean worktree, so I
applied the committed fallback QA contract in `docs/ARCHITECTURE.md` and `device-tests/README.md`.
Inventoried all 472 repository paths and inspected production UI/resources/media-review flows,
relevant ViewModel/storage/audio seams, host UI tests, the four instrumented probes, all 24 registered
device cases, harness contracts/selectors/media parsers, field checks, and current review/plan history.
Adversarial scenarios emphasized exact thresholds, cross-layer transforms, ownerless/imported bytes,
locale/RTL, accessibility state, state restoration, failure/timeout paths, and whether tests exercise
the same representation production consumes. No device run, deployment, media write, settings
mutation, or destructive action was performed.

## Findings

### QA35-01 — the audio-clipping oracle bypasses the lossy production boundary

- **Severity / confidence / status:** Medium / High / Confirmed
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/video/AudioLevels.kt:61-99` correctly holds
  exact peak evidence, and tests prove raw full-scale producer output. Production then rounds peaks
  through `quantizeLevels` at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1008-1027` before
  `audioAccessibilityStates` applies the exact `32767/32768` clipping threshold at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:658-679`. The tests at
  `app/src/test/kotlin/me/hletrd/telecampro/ui/overlays/InstrumentAccessibilityComposeTest.kt:32-75`
  call the classifier with unquantized peaks, skipping
  this representation change. Numerically, 32704/32768 rounds to 1.0 and is misclassified as
  clipping; 32766 is likewise false-positive, while only 32767/-32768 should reach the terminal.
- **Concrete failure:** all producer, reducer, and Compose tests can remain green while the shipping
  ViewModel emits a false accessibility alarm over a real near-limit PCM buffer.
- **Fix:** make classification survive the cross-layer boundary exactly, and add an integration
  oracle over sample values bracketing both near-clipping and clipping thresholds rather than only
  testing each layer in isolation.

### QA35-02 — review orientation tests cover only the app's rotation-only output subset

- **Severity / confidence / status:** Medium / High / Confirmed
- **Evidence:** the review decoder's switch at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:459-480` supports EXIF 1, 3, 6,
  and 8 but treats 2, 4, 5, and 7 as identity. The restore reducer explicitly acknowledges that an
  imported other-app file can imitate the public TeleCam directory/name/MIME grammar
  (`app/src/main/kotlin/me/hletrd/telecampro/storage/LatestCaptureReducer.kt:58-65`;
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:305-316`) and still returns
  it for display. No review test references the four mirrored EXIF constants.
- **Concrete failure:** the current suite validates app-authored parity while missing a reachable
  adversarial input admitted by the production restore query; the accepted file is presented with
  the wrong handedness/axis in both thumbnail and review.
- **Fix:** generate a small asymmetric JPEG fixture for each of the eight orientation tags, route it
  through the actual decode/thumbnail entry points, and assert final corner colors/dimensions. Treat
  all admitted ownerless bytes as untrusted interoperability inputs, not as guaranteed old app output.

### QA35-03 — docs gate has no oracle for two active cross-authority drifts

- **Severity / confidence / status:** Low / High / Confirmed
- **Evidence:** `python3 tools/check_docs.py` passes 107 checks, yet
  `docs/ARCHITECTURE.md:1250-1253` says AGP 9.3.1 while the catalog is 9.3.2, and
  `docs/ARCHITECTURE.md:584-587` points to a logical-camera exposure check absent from the exact
  field dashboard/body (`docs/FIELD_CHECKS.md:9-14,75-103,198-251`). Current checks compare only
  selected version locations and validate the field ledger internally, not active references into it.
- **Concrete failure:** a green documentation gate certifies internally consistent subsets while
  leaving clean-clone operators with both stale build guidance and an unexecutable open validation.
- **Fix:** mechanically compare every active toolchain reference with the version catalog and resolve
  every active field-check reference to a body identifier/status.

## Final missed-issue sweep

Re-ran documentation contracts, checked resource-key parity/non-translatable exceptions, audited
state-changing device-case approval annotations, reviewed exact media/recording preconditions and
cleanup ownership, searched current UI/source/tests for stale TODO/FIXME/retired claims, and traced
the two confirmed data representations end to end. No stale previously-fixed cycle finding was
re-filed, and no host-only observation was promoted to device evidence.
