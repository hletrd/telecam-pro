# Cycle 54 verifier + test-engineer

Reviewed revision: `bf40ae2c56c154072691815f83b7090a31f0c424`

## Inventory and coverage

Inventoried all 594 paths and mapped production Kotlin/Java modules to unit, Robolectric, Compose,
androidTest, Python-tool, device-harness, documentation-contract, and coverage-partition suites.
Verified the cycle-53 implementation claims against current production entry points and tests,
reviewed every newly changed class/test plus cross-file call paths, and ran the documentation
contract suite (155 checks passed; 24 expected private-context skips). I also inspected the current
manual evidence ledger so open device checks were not mislabeled defects. A final assertion-quality,
false-assurance, missing-integration, and skipped-file sweep found one current gap with production
impact.

## Finding VT54-01 — cycle-53 immutable-review test stops before the user-visible metadata path

- **Severity / confidence:** Medium / High.
- **Evidence:** `ReviewDecodeSourceTest.kt:70-118` asserts one provider open and a frozen EXIF
  orientation only through `openReviewDecodeSource` + `decodeReviewBitmap`. The actual review first
  runs `loadReviewDescriptor` and `loadMetadata` at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:609-667`, where exposure EXIF is
  reopened directly from the provider. The bitmap is frozen later at `MediaReview.kt:699-748`.
  No test references `loadMetadata`, `ReviewMetadata`, or the descriptor-to-decoder byte identity;
  the existing sizing tests validate layout and provider-request geometry, not source consistency.
- **Failure scenario:** all cycle-53 tests stay green while a provider returns capture A to the
  metadata descriptor and capture B to the bitmap lane. Review then presents B's pixels beside A's
  ISO/shutter/focal line. An unverified input can also bypass the tested 64 MiB ceiling solely by
  entering the descriptor EXIF parser.
- **Suggested fix:** extract a testable still-review acquisition packet owning one frozen source and
  the derived metadata/orientation, then test the production descriptor + decode flow with an
  alternating provider and an over-limit owner-unverified stream. Assert one content open for all
  still byte-derived facts and exact disposal on timeout/replacement.

## Final sweep

The documentation gate is green and no other test claim was contradicted by its current production
entry point. Full Gradle and host gates belong to Prompt 3 and were not used to claim device truth.
