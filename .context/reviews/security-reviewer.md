# Cycle 49 security review

Date: 2026-08-25
Reviewed revision: `69c9c64a` (`origin/main` at review start)
Workspace: isolated clone `/tmp/find-x9-ultra-cycle49.oXnMVe/repo`

## Authority, inventory, and method

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` as the committed authority
before reviewing implementation. Optional private maintainer documents are absent and were not
treated as required. The review inventoried all 534 tracked paths with `git ls-files`: 527 regular
mode-100644 files and seven regular mode-100755 files, with no symlink, submodule, FIFO, or device
entry. The implementation surface includes 102 production Kotlin files, one production Java file,
three debug Kotlin files, four instrumented-test Kotlin files, 237 JVM/Robolectric/Compose test
files, and 33 Python files; tracked resources, manifests, Gradle inputs, shell tools, binary assets,
licenses, plans, and historical reviews were included in boundary and provenance sweeps.

The direct review covered the merged release/debug component boundary, exported intent ingress,
DUMP protection, obscured and hardware input, camera/microphone/visual-media permissions, backup
and extraction rules, external navigation, owner-null MediaStore consent, exact-family deletion,
pending-row recovery, parser/allocation bounds, private state, Camera2/GL/codec/audio ownership,
release signing and immutable-output tooling, dependency verification, subprocess construction,
ADB tooling, and the complete cycle-48 delta. Authentication accounts do not exist in this offline
camera app; the applicable authorization surface is Android component permissions plus MediaStore
ownership/system consent.

No deployable credential, private key, dynamic-code loader, WebView/JavaScript bridge, unsafe
deserializer, shell-evaluated application input, release network permission, location permission,
overlay permission, package-install permission, all-files access, or backup exposure was found.
The debug-only exported activities remain DUMP-protected, the ordinary launcher does not consume
debug command extras, and the cycle-48 obscured-stream cancel now clears only the hostile overlay
flags while retaining the original pointer identity and timing.

## Finding

### SEC49-01 — the new PNG validator accepts invalid chunk order and is not total for overlong decoded data

- **Severity / confidence:** Low / High.
- **Classification:** Confirmed release/tooling validation defect. The inputs are repository-owned,
  so this is not a remote application exploit; it is a false-green/crash seam in the Play-asset
  integrity boundary.
- **Evidence:** `tools/check_docs.py:111-196` records an `IDAT` end after any later chunk, but its
  `PLTE` exception at lines 166-171 still accepts `PLTE` after `IDAT`, does not reject `PLTE` for
  color type 6, and does not validate its size/cardinality. PNG requires `PLTE` before the first
  `IDAT` and forbids it for truecolor-with-alpha. A CRC-correct 1x1 truecolor fixture with
  `IHDR -> IDAT -> PLTE -> IEND` was passed directly through the production function and returned
  `(1, 1, 8, 2)`. Independently, lines 184-186 decompress with a maximum of
  `expected_size + 1` and then call `inflater.flush(expected_size + 1 - len(pixels))`; an image
  whose zlib stream expands to exactly one byte beyond the declared raster makes that argument
  zero. Python raises uncaught `ValueError: length must be greater than zero` because only
  `zlib.error` is caught. The production function reproduced both outcomes.
- **Concrete scenario:** A failed or malformed screenshot export is committed and its validity
  digest is refreshed. An illegal post-IDAT palette can pass the claimed full PNG validation, while
  a one-byte-overlong decoded raster aborts `tools/check_docs.py` instead of producing the normal
  bounded failed check. Existing tests cover truncation, CRC, missing IEND, IHDR methods, and wrong
  geometry (`tools/tests/test_tool_contracts.py:1124-1248`) but neither malformed case.
- **Suggested fix:** Make the parser a total predicate: enforce one legal `PLTE` in the correct
  pre-IDAT position with color-type and length rules, reject every other illegal critical-chunk
  order, and avoid `flush(0)` (or catch `ValueError`) before checking exact decompressed length,
  EOF, tails, and filter bytes. Add digest-refreshed fixtures for post-IDAT/duplicate/forbidden/
  malformed `PLTE` and an exactly-one-byte-overlong raster, asserting an ordinary nonzero gate
  result rather than an uncaught traceback.

## Final security sweep and limitations

The final sweep revisited every exported component and permission, incoming intent, obscured touch
edge, permission owner, owner-null consent route, provider mutation, URI/family authorization,
private store and backup rule, network/location/secret surface, process invocation, immutable
source/output seal, package-private signature guard, parser bound, and cycle-48 security fix. No
additional current security defect survived source validation. The open A3/A4/A5/D1/E1/E2 field
checks remain manual evidence obligations; no device, MediaProvider, physical converter, microphone
scene, production signing key, deployment, or external service was used.

**New security-reviewer finding count: 1 — Low severity, High confidence, confirmed.**
