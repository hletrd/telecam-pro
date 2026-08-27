# Cycle 54 security-reviewer + debugger

Reviewed revision: `bf40ae2c56c154072691815f83b7090a31f0c424`

## Inventory and coverage

Inventoried all 594 repository paths, then examined the complete Android manifest/build and
permission surface, release/signing and evidence tools, MediaStore identity/journal/recovery paths,
review decoders, native camera/GL/codec/audio ownership, exported debug components, secrets/network
boundaries, and their tests and current architecture/field authorities. Cross-checked the cycle-53
storage, review-source, standby-quarantine, and telemetry changes against their callers and terminal
paths. The release manifest remains network-free, debug exports remain DUMP-protected, no committed
secret was found, and no resolved historical finding is repeated below. A final unsafe-provider,
resource-lifetime, and missed-file sweep produced one current finding.

## Finding SD54-01 — review metadata bypasses the immutable source and unverified-size bound

- **Severity / confidence:** Medium / High (confirmed mechanism; exploitability of pathological
  MediaProvider input is device/provider dependent).
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:469-505` freezes
  still bytes through `openReviewDecodeSource`, applying the 64 MiB owner-unverified ceiling and the
  trusted-source disk/process budgets. But `MediaReview.kt:609-631` separately calls
  `contentResolver.openInputStream(uri)` and constructs `ExifInterface` directly for the metadata
  plate, before the bounded decode path at `MediaReview.kt:699-748`. That second path has neither the
  immutable spool nor a compressed-input ceiling. The cycle-53 test
  `app/src/test/kotlin/me/hletrd/telecampro/ui/review/ReviewDecodeSourceTest.kt:70-118` proves only
  bounds/pixels/orientation EXIF within `decodeReviewBitmap`; it never enters `loadMetadata`.
- **Failure scenario:** an admitted owner-null row or mutable app-owned row returns benign bytes to
  descriptor EXIF parsing and different bytes to the later frozen decoder, so the plate can show
  ISO/shutter/focal metadata from a different file identity than the pixels. A hostile or corrupted
  owner-null still can also feed an arbitrarily large/pathological EXIF stream to `ExifInterface`
  outside the 64 MiB policy that is supposed to bound unverified review input, consuming the finite
  descriptor workers for native/heap-heavy parsing before decode admission.
- **Suggested fix:** freeze one compressed still source once per review request and derive both
  orientation and the displayed exposure metadata from it; keep MediaStore column queries separate
  but identity-check or snapshot their result. Apply the provenance-specific byte ceiling before any
  EXIF parser sees unverified bytes. Add a mutation fixture in which descriptor EXIF and later
  provider bytes differ, plus an over-ceiling unverified EXIF fixture, and prove only one provider
  content open occurs for all still byte-derived data.

## Final sweep

No additional current security or latent-debugger issue survived validation. Device-only behavior
under `docs/FIELD_CHECKS.md` remains evidence work, not a code finding.
