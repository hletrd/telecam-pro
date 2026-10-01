# Cycle 51 security review

Date: 2026-08-25
Reviewed revision: `7eb4ee95` (`origin/main` at review start)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle51.WTu2dW`

## Authority, complete inventory, and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` completely before reviewing
implementation. The optional private maintainer documents named by those authorities are absent and
were not treated as required. I inventoried the complete tracked tree with `git ls-files` and
`git ls-files -s`: 538 regular files, comprising 531 mode-100644 files and seven mode-100755 files,
with no symlink, submodule, FIFO, socket, or device entry. The review-relevant inventory is:

- 102 production Kotlin files and one production Java file (55,429 lines), plus three debug Kotlin
  files (608 lines) and four instrumented-test Kotlin files (588 lines);
- 239 JVM/Robolectric/Compose test files (46,920 lines), 22 Python/shell host-tool files (10,022
  lines), and 13 Python device-harness files (14,570 lines);
- 95 Markdown authority/review/plan files (16,227 lines) and 59 build, manifest, resource, asset,
  license, wrapper, and configuration inputs (7,363 reviewable text lines plus binary identity).

Binary fonts, PNGs, and the Gradle wrapper participated in mode, digest, packaging, and artifact-
boundary checks rather than being treated as executable source text. Every tracked path participated
in the final credential/private-key, component/permission, backup, network/location, dynamic-code,
deserialization, subprocess/command, URI/path, logging, parser, exception, suppression, and dangerous-
API sweeps. Direct cross-file review covered:

- merged release/debug components, DUMP protection, ordinary launcher/debug intent ingress,
  obscured and hardware input, camera/microphone/visual-media permissions, external navigation,
  private preferences/SQLite state, backup rules, privacy prose, and Play Data Safety;
- current-package versus owner-null MediaStore restoration, filename/MIME recognition, exact-file
  system consent, capture-family/DISCARD authority, pending-row recovery probes, still/video review
  decoders, and the cycle-50 immutable review snapshot change;
- Camera2 route/session generations, pseudo-ZSL, GL/EGL/shader ownership, codec/muxer/audio admission
  and quarantine, lifecycle continuations, finite worker owners, and the complete cycle-50 production
  delta;
- dependency verification, release signing inputs, immutable debug/release export boundaries,
  packaged permission/source checks, ADB evidence tooling, PNG/Play-asset validation, and the matching
  test and documentation contracts.

This offline app has no account-authentication surface. Its applicable authorization boundary is
Android component permissions plus MediaStore ownership/system consent. The release manifest has no
INTERNET, network-state, location, legacy external-storage, all-files, overlay, package-install, or
query-all-packages permission. Backup remains disabled and both rule formats exclude preferences and
databases. Debug exported activities are DUMP-protected. I found no deployable secret/private key,
plaintext credential, dynamic-code loader, WebView/JavaScript bridge, unsafe object deserializer, or
shell-evaluated application input.

## Findings

### SEC51-01 — extreme still dimensions overflow the new review sampling calculation

- **Severity / confidence:** Low / Medium.
- **Classification:** Confirmed arithmetic defect with a likely local availability impact; actual
  platform-decoder reachability requires an adversarial owner-null HEIF/device check.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:455-468` computes
  ceiling division as `(longest + sample - 1) / sample` in signed `Int`. For a positive decoded
  bound near `Int.MAX_VALUE`, the second iteration (`sample == 2`) overflows the numerator negative,
  exits the loop, and returns `2` instead of the roughly million-scale sample required by the 3000 px
  contract. For example, the pure helper returns the wrong branch mathematically for
  `width=Int.MAX_VALUE, height=1, maxDim=3000`. The final decoded-size check at lines 488-491 occurs
  only after `BitmapFactory.decodeByteArray` has acted on that undersized sample. The new tests at
  `MediaReviewSizingTest.kt:283-293` cover 6001 px and invalid non-positive values, but no overflow
  boundary. Owner-null `.heic`/`.heif` rows are deliberately admitted by
  `LatestCaptureReducer.kt:184-205` after contextual media access, so file bytes are not trusted as
  app-authored.
- **Concrete failure scenario:** An imported owner-null TeleCam-named HEIF carries a positive image
  dimension large enough to overflow the helper and is selected for review. If the platform exposes
  that bound before pixel allocation, the app asks the native decoder for a vastly larger raster than
  the promised 3000 px maximum. The decode can create severe memory pressure or terminate the process
  before the post-decode rejection runs. Whether supported MediaProvider/BitmapFactory builds expose
  such an extreme HEIF bound rather than rejecting it earlier remains manual validation.
- **Suggested fix:** Perform the comparison and ceiling division in `Long`, or use an overflow-free
  quotient/remainder form. Add `Int.MAX_VALUE`/large-HEIF boundary tests and retain the existing final
  decoded-size recycle check as defense in depth.

### SEC51-02 — the screenshot validator accepts `tRNS` before a later `PLTE`

- **Severity / confidence:** Low / High.
- **Classification:** Confirmed release/tooling integrity defect; repository-owned input, not a
  remotely exploitable application path.
- **Evidence:** `tools/check_docs.py:162-174` admits a truecolor `PLTE` whenever it precedes IDAT, but
  does not reject an earlier `tRNS`; the `tRNS` branch at lines 243-252 checks color type, length,
  multiplicity, and pre-IDAT placement but not the relative PLTE order. PNG requires `tRNS` to follow
  `PLTE` when a palette is present. I executed the production `png_metadata` function unchanged
  against a CRC-correct 1x1 RGB8 stream ordered `IHDR,tRNS,PLTE,IDAT,IEND`; it returned
  `(1, 1, 8, 2)`. The cycle-50 test is named
  `test_committed_export_rejects_illegal_png_chunk_types_and_ancillary_order`, but its ordering cases
  cover only post-IDAT metadata (`tools/tests/test_tool_contracts.py:1341-1411`), so it gives false
  assurance for this legal-relative-order axis.
- **Concrete failure scenario:** A screenshot export/mutation adds an optional truecolor palette
  after its transparency chunk and refreshes the validity digest. The committed documentation/Play-
  asset gate returns green for a non-conforming PNG rather than producing its expected bounded
  failure, allowing malformed release evidence into the workflow.
- **Suggested fix:** Record that `tRNS` has appeared and reject any subsequent `PLTE` (truecolor PLTE
  remains optional, so `tRNS` cannot simply require one). Add a committed-export mutation with the
  exact `IHDR,tRNS,PLTE,IDAT,IEND` order and assert failure without traceback.

## Validation evidence and limitations

- `python3 tools/check_docs.py` passed all 153 committed checks; 24 explicitly optional private checks
  were skipped. The synthetic out-of-order PNG above still passed the production parser, confirming
  SEC51-02 despite the green gate.
- `python3 -m compileall -q tools device-tests`, `git diff --check`, `git fsck`, Git-mode/symlink
  inspection, and credential/private-key scans passed.
- Cycle 50's exact debug, release, and authoritative host gates were green at this reviewed revision.
  I did not rerun device, EGL fault injection, MediaProvider mutation, production signing, upload,
  deployment, or destructive media operations during this review.
- Open field checks A3, A4, A5, D1, E1, and E2 remain manual obligations. SEC51-01 does not claim
  platform reachability until an adversarial owner-null HEIF is exercised on a supported device.

## Final missed-issue and file-coverage sweep

The final sweep revisited every exported component and permission, incoming/outgoing intent,
obscured/hardware-input edge, permission owner, owner-null restore/consent route, exact family and
DISCARD transition, provider/decoder/parser bound, private store and backup rule, camera/GL/codec/
audio terminal, finite worker owner, process invocation, immutable source/output seal, package-
private signature guard, PNG chunk grammar/order, and every cycle-50 security-relevant change. Test
names and comments were checked against production branches; the PNG order gap above is the one
confirmed false-green contract. Prior resolved findings and explicit field-only evidence were not
re-filed.

**New security finding count: 2 — one confirmed Low/High tooling-integrity defect and one
Low/Medium local-availability risk whose arithmetic defect is confirmed but platform reach needs
manual validation.**
