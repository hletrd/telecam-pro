# Cycle 50 security review

Date: 2026-08-25
Reviewed revision: `2388819d` (`origin/main` at review start)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`

## Authority, inventory, and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` as the committed authority
before reviewing implementation. The optional private maintainer documents described by that authority
are absent and were not treated as required. I inventoried all 535 tracked paths with `git ls-files`:
528 regular mode-100644 files and seven regular mode-100755 files, with no symlink, submodule, FIFO,
or device entry. The code inventory is 102 production Kotlin files, one production Java file, three
debug Kotlin files, four instrumented-test Kotlin files, 237 JVM/Robolectric/Compose test files, 33
Python files, two shell scripts, 94 Markdown files, and 59 build/resource/asset/license/configuration
inputs. Binary fonts, PNGs, and the Gradle wrapper participated in identity, mode, digest, packaging,
and artifact-boundary checks rather than being treated as executable source text.

Every tracked path participated in the final mode, credential/private-key, permission/component,
backup, network/location, dynamic-code/deserialization, process/command, URI/path, logging, parser,
exception, and dangerous-API sweeps. Direct cross-file review covered:

- merged release/debug components, exported intent ingress, DUMP protection, obscured/hardware input,
  camera/microphone/visual-media permission ownership, external navigation, backup/extraction rules,
  private preferences/databases, and privacy/Data Safety claims;
- current-package versus owner-null MediaStore restoration, public filename/MIME recognition,
  exact-file system delete consent, family/DISCARD authorization, pending-row recovery and bounded
  JPEG/DNG/HEIF/video probing, plus still/video review decoder inputs;
- Camera2 route/session generations, GL/EGL and shader ownership, MediaCodec/MediaMuxer/AudioRecord
  admission and teardown, finite executors, lifecycle callbacks, settings rollback, and the complete
  cycle-49 production delta;
- dependency verification, signing inputs, immutable debug/release exports, packaged permission and
  source-identity checks, subprocess construction, ADB evidence tooling, PNG/store-asset validation,
  and documentation gates.

This offline camera app has no account authentication surface. Its applicable authorization boundary
is Android component permissions plus MediaStore ownership/system consent. The release manifest has
no INTERNET, network-state, location, legacy external-storage, all-files, overlay, package-install,
or query-all-packages permission. Backup is disabled and both rule formats exclude preferences and
databases. Debug-only exported activities are DUMP-protected. I found no deployable secret/private
key, plaintext credential, dynamic-code loader, WebView/JavaScript bridge, unsafe object
deserializer, or shell-evaluated application input.

## Findings

### SEC50-01 — the screenshot PNG gate accepts illegal chunk type codes

- **Severity / confidence:** Low / High.
- **Classification:** Confirmed release/tooling integrity defect. Repository-owned input, not a
  remotely exploitable application path.
- **Evidence:** `tools/check_docs.py:124-185` reads every four-byte chunk type and uses only bit 5 of
  the first byte to decide whether an unknown chunk is ancillary. It never enforces PNG's chunk-type
  grammar: all four bytes must be ASCII letters and the reserved bit (bit 5 of the third byte) must
  be zero. I extracted the production `png_metadata` function unchanged and passed three CRC-correct
  1x1 truecolor PNGs containing zero-length unknown chunks named `abcd`, `abca`, and `12x4` between
  IDAT and IEND. All three returned `(1, 1, 8, 2)`, although `abcd`/`abca` violate the reserved-bit
  rule and `12x4` is not a legal PNG chunk type at all. Existing mutation tests cover PLTE ordering,
  CRCs, IEND, IHDR methods, truncation, geometry, and decoded overrun, but not type-code grammar.
- **Concrete failure scenario:** A malformed screenshot exporter or asset mutation produces a
  CRC-correct file with an illegal ancillary-looking chunk and the validity digest is refreshed.
  The documentation/Play-asset gate claims full PNG validation and returns green, so malformed store
  evidence can be committed and carried into the release workflow rather than producing the expected
  bounded failure.
- **Suggested fix:** Before dispatching any chunk, require each type byte to be in `A-Z` or `a-z`
  and require `(kind[2] & 0x20) == 0`; retain the existing first-byte ancillary classification only
  after that validation. Add digest-refreshed fixtures for a lowercase reserved third byte and a
  non-letter type, asserting a normal failed check without traceback.

### SEC50-02 — owner-null still review sizing is not bound to one immutable file snapshot

- **Severity / confidence:** Low / Medium.
- **Classification:** Likely local availability risk; requires manual/adversarial MediaProvider
  validation. It is not a confirmed remote exploit.
- **Evidence:** `MediaStoreWriter.kt:294-339` deliberately restores owner-null rows matching the
  public TeleCam directory/name/MIME grammar after contextual visual-media access;
  `LatestCaptureReducer.kt:184-220` explicitly states that such a row may be an imported lookalike.
  `ui/review/MediaReview.kt:437-449` then opens the URI once to obtain dimensions and a second time to
  decode pixels with a sample derived from the first open. No stable descriptor/version identity is
  held across those opens, invalid bounds (`outWidth/outHeight <= 0`) are not rejected, and the
  decoded bitmap is not checked against the requested maximum as the video-thumbnail sibling is at
  `MediaReview.kt:421-434`.
- **Concrete failure scenario:** An owner-null lookalike row is selected/restored, and a privileged
  gallery/provider writer replaces its bytes between the bounds open and decode open (or a provider
  returns different content per open). A small first image selects `inSampleSize=1`; the second open
  can then cause a full native bitmap allocation well beyond the advertised 3000 px review bound,
  producing memory pressure or process death. Whether the platform MediaProvider permits that exact
  rewrite race on supported builds remains to be measured.
- **Suggested fix:** Decode dimensions and pixels from one stable `ParcelFileDescriptor`/seekable
  snapshot (or copy owner-unverified input into a size-bounded private snapshot), reject non-positive
  bounds, cap encoded byte/file size, and verify/recycle any decoded bitmap that violates the final
  dimension/allocation contract. Add a fake-provider test that returns different bytes for successive
  opens, then run a real owner-null replacement race as a field/security check.

## Validation evidence and limitations

- Focused cycle-49 camera-state, pipeline rollback, keyboard, modal focus, ownerless-delete, and all
  video JVM/Robolectric tests passed (`BUILD SUCCESSFUL`, 2026-08-25).
- The two new PNG fixes' existing focused mutation tests passed, and `python3 tools/check_docs.py`
  passed 152 checks with zero failures (24 optional-private checks skipped). The illegal chunk-code
  reproducer above still returned success from the production parser, establishing SEC50-01.
- `git diff --check` was clean before the provenance reports were written. No device, deployment,
  production signing, Play upload, external communication, or destructive provider action occurred.
- Open field checks A3, A4, A5, D1, E1, and E2 remain manual evidence obligations. In particular,
  SEC50-02 does not claim owner-null provider rewrite semantics without an adversarial device run.

## Final missed-issue sweep

The final sweep revisited every exported component and permission, incoming/outgoing intent,
obscured/hardware input edge, permission owner, owner-null consent route, exact family/DISCARD
transition, provider mutation and structural probe, decoder/allocation bound, private store and backup
rule, camera/GL/codec/audio terminal, finite worker owner, process invocation, immutable source/output
seal, package-private signature guard, PNG parser boundary, and every cycle-49 security-relevant
change. Prior resolved findings and explicit field-only evidence were not re-filed.

**New security finding count: 2 — one confirmed Low/High tooling defect and one Low/Medium local
availability risk requiring manual provider validation.**
