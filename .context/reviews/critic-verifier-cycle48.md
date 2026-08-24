# Combined critic + verifier review — cycle 48

Date: 2026-08-25
Reviewed revision: `ad64188a` (`origin/main` at review start)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle48.Gvbytf`
Review mode: Prompt 1 only; read-only source review plus non-device host verification

## Authority and inventory

I read `CLAUDE.md`, `docs/ARCHITECTURE.md`, and the committed field-evidence ledger
`docs/FIELD_CHECKS.md` before evaluating implementation behavior. I treated code and packaged/tool
behavior as authoritative over comments or tests.

The inventory was generated from all 528 tracked paths, not from the latest diff alone:

| Surface | Tracked paths | Review treatment |
|---|---:|---|
| Production Kotlin/Java | 103 | All package boundaries inventoried; Camera2/GL/capture/storage/video/UI ownership and cross-calls traced |
| Main resources | 15 | Manifest, strings/KO parity, themes, XML policy, fonts/icons and baseline profile inventoried |
| JVM/Robolectric/Compose + instrumentation tests | 242 | Coverage intent and false-green seams checked against production behavior |
| Debug-only application sources | 4 | Export/security and diagnostic ownership checked |
| Device harness | 14 | ADB retry, attestation, media and executable case boundaries inventoried |
| Host/release tooling | 25 | SDK, immutable build, artifact, permission, documentation and coverage gates checked |
| Product/architecture/release documentation and assets | 67 | Current authorities, field-evidence limits, Play assets and release claims checked |
| Build/toolchain configuration | 13 | Gradle, wrapper, dependency verification, manifests, shrinker and stability configuration checked |
| Historical review/context and remaining repository files | 45 | Prior findings/plans checked to avoid re-reporting fixed issues; binaries treated as evidence/assets, not executable source |

The final sweep rechecked transaction completeness, stale callback authority, state/UI/engine
agreement, permission and external-input boundaries, finite queue behavior, persistence after
failure, release false-greens, localization, and manual/device-only claims.

## Findings

### CV48-01 — failed transfer reconfiguration restores Engine truth but leaves UI and persistence on the rejected transfer

- **Severity / confidence:** High / High
- **Classification:** Confirmed current defect; device-independent source trace. The actual Camera2
  rejection is a hardware/runtime trigger, so observing the resulting pixels/file is manual-device
  validation, but the state split after that trigger is deterministic.
- **Evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:2351-2367` sends the new transfer
    to the Engine and then optimistically writes `CameraUiState.transfer = safeTransfer` and schedules
    persistence.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:2473-2504` correctly opens an
    optics transaction for an SDR/non-SDR session-boundary change.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:748-818` restores
    `transfer = before.transfer` and the GL transfer on rollback, but constructs an
    `OpticsRollbackPublication` with no transfer field.
  - `app/src/main/kotlin/me/hletrd/telecampro/camera/OpticsConstraints.kt:22-35` confirms the rollback
    payload has mode/lens/route/controls/declaration but no accepted transfer.
  - `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:911-958` applies every payload
    field it receives, never restores `CameraUiState.transfer`, and then calls
    `scheduleSettingsSave()`. A Ready publication at `CameraViewModel.kt:821-908` updates accepted
    output truth only and cannot repair the transfer.
- **Concrete failure scenario:** Start in Video/SDR, choose HLG (or recall an MR/settings packet that
  changes only SDR versus non-SDR), and let the required 10-bit session reopen fail. Engine rollback
  resumes the accepted SDR controller and resets GL to SDR, while the Video/Fn UI continues to say
  HLG. The rollback's settings save can persist that rejected HLG choice. A subsequent recording is
  configured from Engine SDR truth even though the operator is shown HLG, violating the professional
  color/file-truth contract.
- **Suggested fix:** Add the exact accepted-before `ColorTransfer` to
  `OpticsRollbackPublication`, restore it in the same ViewModel state fold as mode/lens/controls,
  and reconcile the Engine's encoder-candidate list to that restored transfer before allowing REC.
  Add a ViewModel-level forced-failure test that begins with Video/SDR, invokes the public
  `onTransfer(HLG)` path, forces the owned rollback, and asserts Engine transfer, GL intent,
  `CameraUiState.transfer`, candidate admission, and the persisted packet all return to SDR. The
  existing cycle-47 test asserts only Engine fields and therefore misses the split.

### CV48-02 — the shader host gate does not verify the runtime binding names it claims to protect

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed validation gap; current binding literals happen to match. The
  failure scenario is a future source regression that the authoritative host gate would falsely pass.
- **Evidence:**
  - `app/src/main/kotlin/me/hletrd/telecampro/gl/FlipRenderer.kt:67-84` performs the actual
    `glGetAttribLocation` / `glGetUniformLocation` lookups, but does not reject a `-1` location.
  - `app/src/test/kotlin/me/hletrd/telecampro/gl/ShaderProgramCompileTest.kt:13-30` compiles and links
    only the two shader strings.
  - `ShaderProgramCompileTest.kt:72-74,104-109` compares a hand-maintained `REQUIRED_INTERFACE` set
    only with tokens in those shader strings. It never derives or compares the lookup literals in
    `FlipRenderer`.
  - `ShaderProgramCompileTest.kt:51-57` calls its mutation a renamed *runtime* uniform, but mutates
    the shader source, not the runtime lookup. A mutation from
    `glGetUniformLocation(program, "uDigitalGain")` to `"uDigtalGain"`, or removal of that lookup,
    leaves the shader, hard-coded set, compile and link results unchanged, so this gate passes.
- **Concrete failure scenario:** A refactor misspells a `FlipRenderer` uniform binding. GLSL still
  compiles and links, and `missingInterface()` still finds the correctly spelled declaration in the
  shader. GLES returns `-1`; a uniform update is silently ignored (breaking the associated display
  transform), while a missing attribute can produce GL errors/black preview. The authoritative host
  gate remains green and the defect first appears at runtime.
- **Suggested fix:** Make interface names one production authority consumed by both the GLSL
  declarations and `FlipRenderer` lookups, or add a gate that extracts/compares every production
  lookup literal against linked active interfaces. In `FlipRenderer.init`, fail preview health if any
  required attribute/uniform location is negative. Mutation-test the production binding side (typo,
  removal, and extra lookup), not only the shader side.

### CV48-03 — screenshot readiness accepts a malformed/truncated PNG when its first 33 bytes claim the expected IHDR

- **Severity / confidence:** Low / High
- **Classification:** Confirmed release-tool false-green. Current committed screenshots are ordinary
  decodable PNGs; this is a future recapture/update integrity risk, not evidence that today's assets
  are corrupt.
- **Evidence:**
  - `tools/check_docs.py:110-116` checks only total length >= 33, the PNG signature, bytes 12-15 equal
    `IHDR`, and four fields unpacked from bytes 16-25. It does not validate the IHDR length/CRC,
    compression/filter/interlace fields, subsequent chunk framing/CRC, IDAT decompression, IEND, or
    successful image decoding.
  - `tools/check_docs.py:138-162,291-316` combines that header tuple with a manifest digest. The
    digest proves equality to a maintainer-updated manifest, not that the bytes are a valid image.
  - The direct code predicate accepts a 33-byte buffer containing only the signature, an `IHDR`
    marker and expected width/height/depth/type; no image data or valid CRC is required.
- **Concrete failure scenario:** A failed export/truncation writes an invalid file whose leading
  bytes still carry 1440x2880 RGB (or 1920x1200 RGBA). Updating the validity-manifest digest, as the
  normal recapture workflow requires, makes both digest and geometry checks pass. The submission
  sheet can become ready even though Google Play or an image decoder rejects the screenshot.
- **Suggested fix:** Decode every screenshot with a deterministic image decoder and require full
  load/verification plus exact dimensions/mode, or implement complete bounded PNG chunk, CRC and
  zlib validation. Add digest-updated mutations for truncated IDAT, bad CRC, missing IEND, and invalid
  compression/filter/interlace values; the current mutation covers valid PNG bytes with wrong
  geometry only.

## Verification evidence

- Focused Android host tests passed: `ModeRollbackOwnershipRobolectricTest`,
  `MainActivityTouchDispatchTest`, `CameraUiPolicyTest`, and `ShaderProgramCompileTest`.
- Full tooling suite passed with the repository's complete SDK authority:
  `python3 -m unittest discover -s tools/tests -p 'test_*.py'` — 120 tests, OK.
- `python3 tools/check_docs.py` — 151 checks, 0 failed, 24 optional-private checks skipped.
- The first tooling run without an SDK override selected the conventional user SDK, which lacked
  the newly required Emulator validator and produced seven environment errors. Re-running with
  `/opt/homebrew/share/android-commandlinetools` (the complete Platform 37 / Build Tools 36.0.0 /
  Emulator authority) passed; this was classified as environment setup, not a product finding.
- No device, MediaProvider, camera HAL, physical converter, microphone scene, or HDR display was
  available. The open A3/A4/A5/D1/E1/E2 field checks remain manual and were not inferred from host
  success.

## Final missed-issues sweep

No additional current defect survived source-level validation. In particular, the cycle-47
obscured-touch cancellation now delivers one child `ACTION_CANCEL` and taints the remainder; external
input ownership now retires the standby meter; ADB reconnect classification uses endpoint-scoped
canonical errors; packaged release permissions are compared to a closed authority; and EN/KO
translation exceptions are closed. Those conclusions are limited to the executable host/source
contracts inspected here and do not promote any open field check to passed.
