# Critic review — cycle 50

Date: 2026-08-25

Reviewed revision: `2388819d` (`origin/main`)

Workspace: isolated clone `/tmp/find-x9-ultra-cycle50.ZrnMqN`

Role: critic; source review plus focused read-only host verification; no implementation or commit

## Inventory before review

I generated the inventory from all 535 tracked paths before examining behavior. The review did not
sample only the last commit:

| Surface | Paths | Treatment |
|---|---:|---|
| Production Kotlin/Java | 103 | Every module inventoried from the architecture map; Camera2, GL, capture, storage, video, and UI ownership boundaries traced |
| Main resources | 15 | Resource/localization/policy assets inventoried |
| Debug + instrumentation sources | 8 | Export/security and device-probe boundaries inventoried |
| JVM/Robolectric/Compose tests | 238 | Test intent and false-green seams compared with production call sites |
| Host/release tooling | 25 | SDK, immutable-build, artifact, docs, permissions, and coverage gates inventoried |
| Device harness | 14 | Attestation, ADB, media, selector, and case boundaries inventoried |
| Product/architecture/release docs and assets | 64 | Current authorities and field-evidence limits checked; historical plans/assets classified separately |
| Prior review provenance | 44 | Consulted only after the independent inventory to avoid re-reporting fixed findings |
| Build/root/remaining tracked files | 24 | Manifests, Gradle, wrapper, signing example, privacy/publication, license, and repository policy inventoried |

The Cycle 49 executable change surface was then examined completely: `CameraEngine.kt`,
`CameraState.kt`, `CameraScreen.kt`, `MediaReview.kt`, `tools/check_docs.py`, all six changed focused
test files, and `docs/play-console-submit.md`. Cross-file traces additionally covered
`CameraViewModel.kt`, `CameraActions.kt`, `MainActivity.kt`, `EncoderCaps.kt`, `VideoRecorder.kt`,
the optics commit/rollback gates, capture-family producer settlement, modal focus, the committed PNG
manifests, and the current architecture/field-check authorities.

## Finding

### C50-CV-01 — the “fully validate” PNG gate still accepts specification-invalid ancillary ordering

- **Severity / confidence:** Low / High
- **Classification:** Confirmed tooling false-green; current checked-in screenshots themselves pass
  and this is not evidence that a present asset is corrupt.
- **Exact region:** `tools/check_docs.py:111-185`, especially the generic ancillary branch at
  `tools/check_docs.py:180-185`; the mutation suite covers PLTE-specific ordering only at
  `tools/tests/test_tool_contracts.py:1270-1309`.
- **Evidence:** `png_metadata()` describes itself as fully validating the PNG, but after the first
  IDAT every unknown ancillary chunk merely sets `idat_ended = True` and remains accepted as long as
  no later IDAT appears. PNG ancillary chunks have chunk-specific placement rules; for example,
  `tRNS` must precede the first IDAT. I inserted a CRC-correct six-byte truecolor `tRNS` immediately
  before IEND in the current 1440x2880 phone screenshot. The direct production predicate returned
  `(1440, 2880, 8, 2)` instead of `None`. The same class also includes malformed/late `sRGB`, `iCCP`,
  `gAMA`, and related color-space chunks whose structure/order is not validated.
- **Concrete failure scenario:** A screenshot export or metadata-rewrite step emits a late or
  malformed color/transparency ancillary chunk, and the maintainer refreshes the manifest digest as
  required by the normal recapture workflow. Digest, dimensions, CRCs, decompressed raster length,
  and the current gate all pass, while a strict decoder/store ingestion path can reject or
  reinterpret the file. This reopens the release-tool false assurance the Cycle 49 PNG work claimed
  to close.
- **Suggested fix:** Prefer a deterministic production image decoder that performs a complete load,
  then independently enforce the exact dimensions/color contract. If keeping the parser, validate
  the chunk type grammar plus every admitted ancillary chunk's length, value, multiplicity, and
  ordering (or reject all nonessential ancillary chunks). Add digest-refreshed mutations for a
  post-IDAT `tRNS`, malformed/late `iCCP` and `sRGB`, and an invalid chunk-type reserved bit.

## Critic verification and final sweep

- Focused Android tests passed for the changed runtime surfaces:
  `CameraStateTest`, `ModeRollbackOwnershipRobolectricTest`,
  `ViewfinderAccessibilityComposeTest`, `ModalFocusComposeTest`, and
  `MainActivityTouchDispatchTest`.
- `python3 -m unittest tools.tests.test_tool_contracts` passed all 55 configured tests.
- `python3 tools/check_docs.py` passed 152 checks with 24 documented optional-private skips.
- `git diff --check` passed.
- The video-pipeline monitor now covers derivation through publication; the captured rollback packet
  restores codec, ordered candidates, requested transfer, active transfer, UI publication, and REC
  filtering consistently in the examined interleavings.
- Release capture tracing is inert before trace payload construction, and producer-lease/family
  settlement remains outside diagnostics on every terminal path examined.
- Delete-dialog Back/Cancel share one dismissal owner and restore the exact Delete focus requester;
  outside dismissal reaches that same callback. Viewfinder focus-key repeats do not re-run the
  action. No second runtime defect survived the cross-file ownership sweep.
- No device, Camera2 HAL, converter, HDR display, off-axis audio scene, or real MediaProvider consent
  surface was available. Open field checks A3/A4/A5/D1/E1/E2 remain manual and were not promoted.
