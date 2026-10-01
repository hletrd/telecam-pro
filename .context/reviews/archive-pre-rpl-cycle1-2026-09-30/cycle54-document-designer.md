# Cycle 54 document-specialist + native Android designer

Reviewed revision: `bf40ae2c56c154072691815f83b7090a31f0c424`

## Inventory and coverage

Inventoried all 594 paths and reviewed the complete committed product/architecture/field/privacy/
Play authorities against manifests, resources, Compose UI, accessibility semantics, responsive and
RTL layout policy, loading/empty/error/retry states, dark-theme contrast tokens, localization, and
review/media behavior. This is native Jetpack Compose rather than a web app, so browser automation
is not applicable. `tools/check_docs.py` passed all 155 available checks with 24 documented
private-context skips; EN/KO parity, architecture module inventory, privacy permissions, release
state, and field-dashboard membership are current. A final code/doc and user-visible truth sweep
found one mismatch.

## Finding DD54-01 — review's metadata plate is not bound to the displayed frozen still

- **Severity / confidence:** Medium / High.
- **Evidence:** `docs/ARCHITECTURE.md` describes immutable review sources whose bounds, pixels, and
  EXIF share one identity, and `CLAUDE.md` likewise says app-owned and unverified decode inputs are
  frozen. The display path contradicts that user-visible implication: `MediaReview.kt:609-631`
  derives ISO/shutter/focal from a fresh provider stream inside `loadMetadata`, while
  `MediaReview.kt:469-505,699-748` later freezes and decodes another stream for the pixels and
  orientation. The bottom metadata UI consumes that descriptor independently of the decoded bitmap.
- **Failure scenario:** a gallery edit/provider mutation between acquisitions shows one photo with
  another identity's `ISO … · shutter · focal mm` plate. This is especially misleading in a pro
  camera review surface, where those values are operator evidence rather than decorative copy; the
  UI provides no indication that the plate and image were acquired independently.
- **Suggested fix:** derive the still metadata plate from the same frozen compressed source used by
  bounds/pixels/orientation, and update the architecture wording to name that complete packet. On a
  metadata read failure, omit only the unavailable exposure line while keeping file columns and
  review state truthful. Test mutated-provider identity, loading/error behavior, and EN/KO output.

## Final sweep

No additional current accessibility, responsive-layout, localization, privacy-copy, or
documentation-code mismatch survived inspection. Open field checks remain honestly labeled.
