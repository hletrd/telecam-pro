# Native Android designer review — cycle 35

Date: 2026-08-24

Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518`

Workspace: clean detached cycle worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Coverage

This is a native Android/Jetpack Compose app, so browser skills are not applicable. Inventoried and
examined the 30 production UI Kotlin files, 15 main resource files, both 491-entry EN and 473-entry
KO string sets (the 18 EN-only strings are the declared non-translatable names/abbreviations), 82 UI
JVM/Robolectric/Compose test files, four instrumented tests, debug preview/snapshot surfaces, and the
device-harness UI matrix. Reviewed information architecture, camera-body affordances, touch targets,
TalkBack/state semantics, focus and D-pad paths, modal containment, contrast/non-color cues,
responsive/large-screen/freeform behavior, loading/error/empty/review states, EN/KO presentation,
RTL coordinate ownership, system-inset handling, and perceived-performance boundaries using source,
resource, and test evidence. No device-only visual claim is inferred.

## Findings

### DES35-01 — peak quantization makes TalkBack announce clipping before PCM actually clips

- **Severity / confidence / status:** Medium / High / Confirmed
- **Evidence:** producer peaks are exact post-gain normalized PCM magnitudes, but
  `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:1008-1027` sends them through the
  RMS-oriented round-to-nearest 1/256 `quantizeLevels`.
  `app/src/main/kotlin/me/hletrd/telecampro/ui/overlays/Overlays.kt:658-679` then classifies a quantized peak as clipping at
  the exact 16-bit positive full-scale threshold `32767/32768`. A non-clipped magnitude of
  `32704/32768 = 0.998046875` rounds to `1.0`, so every peak from 32704 through 32766 is spoken as
  “clipping” even though no sample reached either signed full-scale endpoint. Existing reducer tests
  at `app/src/test/kotlin/me/hletrd/telecampro/ui/overlays/InstrumentAccessibilityComposeTest.kt:32-75`
  pass raw 0.95/full-scale values directly and do
  not cover the ViewModel quantization boundary.
- **Concrete failure:** a keyboard/TalkBack operator checking an external mic can receive a false
  overload warning for near-full but unsaturated audio, undermining the recent change whose explicit
  purpose is producer-truthful clipping semantics.
- **Fix:** keep the RMS bar quantization, but preserve overload truth separately (for example an
  exact held peak, a `clipped` boolean classified before quantization, or a conservative/floor peak
  bucket). Test real PCM at 32703, 32704, 32766, 32767, and -32768 through producer → ViewModel →
  composed semantics in both EN and KO.

### DES35-02 — restored ownerless stills ignore four standard mirrored EXIF orientations

- **Severity / confidence / status:** Medium / High / Confirmed
- **Evidence:** both full review and the gallery thumbnail call `decodeReviewBitmap` at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:436-449,631-640`. Its EXIF
  transform handles only rotate 90/180/270 and deliberately returns the raw bitmap for all other
  values at `app/src/main/kotlin/me/hletrd/telecampro/ui/review/MediaReview.kt:459-480`, omitting
  `FLIP_HORIZONTAL`, `FLIP_VERTICAL`, `TRANSPOSE`, and `TRANSVERSE`. That assumption holds for the
  app's own current save lanes, but the restore boundary explicitly admits owner-null imported or
  other-app lookalikes as displayable legacy-format candidates at
  `app/src/main/kotlin/me/hletrd/telecampro/storage/LatestCaptureReducer.kt:58-65,319-343` and
  `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:305-316`.
- **Concrete failure:** an imported JPEG/HEIF named like a TeleCam capture with EXIF orientation 2,
  4, 5, or 7 is accepted and labeled “origin unverified,” then appears mirrored or transposed
  incorrectly in both the shooting-screen thumbnail and full review while standards-compliant
  gallery apps show it correctly.
- **Fix:** apply all eight EXIF orientation transforms on the already bounded bitmap (rotation plus
  the required X/Y reflection), or narrow the restore contract so files the renderer cannot present
  truthfully are not admitted. Add asymmetric-pixel fixtures for all eight tags and assert thumbnail
  and full-review parity.

## Confirmed strengths and final sweep

The current surface has unusually strong coverage for 48 dp targets, role/selection/state semantics,
modal focus exclusion, D-pad sliders/review panning, 2x font layouts, compact/freeform settings,
large-screen window rotation, RTL physical-vs-reading geometry, EN/KO resource parity, HUD contrast,
non-color AF cues, and loading/error/delete states. A final sweep of hardcoded UI literals,
modifier ordering, focus requesters, pointer recognizers, scroll containers, live regions, status
urgency, insets, and resource parity produced no additional current finding. The optional private
UX policy is absent; review used the committed Sony-style quiet-viewfinder policy.
