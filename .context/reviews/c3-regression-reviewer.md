# RPL cycle 4 — cycle-3 regression review

Reviewer: c4 cycle-3 regression reviewer (read-only; no Gradle run, no device).
Scope: every commit in `git log --reverse e3a2bdd4..887d39fb` (42 commits), judged against
`docs/plans/2026-10-02-rpl-cycle3.md`, `.context/reviews/archive-rpl-cycle3-2026-10-02/_aggregate.md`,
CLAUDE.md and `docs/ARCHITECTURE.md`. Each diff was read with its surrounding code and callers.

Summary: 10 findings (0 High, 3 Medium, 7 Low). No PMA110 byte-identity break was found in the
camera-route commits (A.1/A.2/A.6/A.7 change PMA110 behaviour only where the plan meant them to).
All 42 commits are GPG-signed (`%G? = G`), and none carries an attribution trailer.

---

## Findings

### REG4-1 — Bare-reopen preflight failure is Not-Ready again, with no recovery path (AGG2-4 reopened for four doors)
- Commit: `c5bfd1c8` (+ `bf76c0ee`). `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:993-1005`
  (`rollbackOpticsAfterPreflight`), callers `:4259-4295`, Not-Ready branch `:1142-1151`.
- Why: AGG3-7 correctly noticed that a `currentOpticsReconfiguration()` token snapshots AFTER the
  bare door wrote its field. Its fix was to send those doors back to the "pre-cycle-2 Not-Ready
  outcome", and that outcome is the AGG2-4 bug (High/High in cycle 2). The Not-Ready branch bumps
  `cameraSessionGeneration`, clears `cameraReady`/`readyController`/`acceptedCameraSession`, and
  leaves the outgoing controller open and streaming. Nothing schedules a retry: `scheduleColdStartRetry`
  runs only for `recoverColdPreflight = startup || controller == null`, and a bare door has a live
  controller. The comment at `:4268` still says "The outgoing controller is still open and
  streaming: restore its Ready", and the status shown is `CAMERA_UNAVAILABLE_CAMERA_UNCHANGED`
  ("Camera unavailable; camera unchanged"). Neither is true for these doors any more.
- Failure scenario: in Video, the operator changes resolution, stabilization, aspect/hi-res or
  frame rate (`reopenForSession()` with no argument). `selectCurrentLens()` or `cachedCaps()` fails
  once, for example from a transient `CameraAccessException` during the uncached characteristics
  read. The preview keeps moving, the shutter and REC are inert, and the UI says the camera is
  "unchanged". It stays that way until some other door, a pause/resume, or a camera error reopens
  the camera.
- Fix: give the bare doors a pre-mutation baseline instead of a Not-Ready publication. Either
  route them through `beginOpticsTransaction { field = value }`, so `baselinePrecedesMutation` is
  true and the AGG2-4 restore is honest, or schedule the same bounded retry the cold path uses when
  a bare token rolls back Not-Ready. Also correct the `:4268` comment. Add a test that drives a
  bare door with a null selection and asserts that the engine converges, either back to Ready or
  through a scheduled retry, not just `cameraReady == false`.
- Confidence: Medium. Status: Likely. The state machine is clear from the code. How often the
  failure happens is unmeasured, because caps are cached after the first read, so it is rare.
  Severity: Medium.

### REG4-2 — Re-arming `IS_PENDING` on every launch makes never-judgeable orphans permanent
- Commit: `5ebe8b07`. `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:1128-1156`
  (`reassertPending`), `:1470-1477` (KEEP_PENDING branch); probe `:1717-1735`.
- Why: KEEP_PENDING covers every row whose probe is INDETERMINATE on every launch, not only
  transiently undecidable ones. Three examples: a clip from a crash mid-REC (no `moov`, so
  `MediaExtractor.setDataSource` throws, and `pendingProbeOutcome` maps that to INDETERMINATE; the
  AGG3-6 KDoc itself says recovery "maps the same throw to INDETERMINATE on every launch"), any
  mime type routed to `PendingMediaProbeKind.KEEP_PENDING`, and HEIF layouts the probe refuses to
  judge. Before this commit, MediaProvider's pending expiry was the only garbage collector for
  those rows. If the update does re-arm `DATE_EXPIRES` (the commit's own premise, PENDING DEVICE),
  such a row now survives as long as the app launches at least once a week. It is hidden from the
  gallery and from the app's own review, and nothing in the UI can delete it.
- Failure scenario: the process dies during a 4K recording (OOM, thermal kill, or force stop). The
  multi-GB pending MP4 has no `moov`. Each launch probes it as INDETERMINATE, re-asserts
  `IS_PENDING=1` and keeps it. The storage is never reclaimed, and each further crash adds another
  such file. If the update does NOT re-arm the expiry, the commit is a no-op that costs one provider
  update per kept row per launch, which is another reason to settle this on a device.
- Fix: bound the re-arm by evidence. Re-assert only rows whose durable journal says the bytes were
  finished (`COMPLETE`, or REGISTERED with a positive structural signal). Alternatively, persist a
  first-kept epoch or keep count per URI in the journal and stop re-arming after N launches or D
  days, so MediaProvider's expiry still collects rows that can never be decided. A
  REGISTERED-not-COMPLETE MP4 whose extractor throws on an opened, non-empty file is the same
  evidence AGG3-6 already treats as INVALID on the live path. Add a host test that a
  no-`moov`/REGISTERED video row is not re-armed indefinitely.
- Confidence: Medium. Status: Needs-manual-validation (the `DATE_EXPIRES` effect is PENDING DEVICE).
  Severity: Medium (silent unbounded storage loss for a common crash class).

### REG4-3 — The password-property doc scan no longer sees new, not-yet-added docs before commit
- Commit: `5398acd8`. `tools/check_docs.py:1546-1575` (`tracked_markdown`, `password_property_scan_paths`).
- Why: the scan moved from a disk glob to `git ls-files` (index only), which skips the
  gitignored `.context/` notes as intended. It also drops every untracked file that is NOT ignored,
  which is exactly the state a new doc is in when an author runs `python3 tools/verify_host.py`
  before `git add`/commit. The workflow is to commit and push right after each change, so the
  first gate run that sees the new file is after it is already pushed.
- Failure scenario: an author adds `docs/release-rotation.md` with a password length or delivery
  channel. `verify_host.py` is green ("tracked docs state no password …"), so the file is committed
  and pushed. Only the next run turns red, after publication.
- Fix: `git ls-files -z --cached --others --exclude-standard -- docs .context`. That keeps ignored
  scratch notes out while scanning new docs. Extend the fixture test with an untracked,
  non-ignored doc that must turn the gate red.
- Confidence: High. Status: Confirmed (from the code). Severity: Low-Medium (the gate is weakened in
  the direction it exists to protect).

### REG4-4 — New stacked KDoc: `sleepPreservingInterrupt` lost its doc (the AGG3-56 pattern, reintroduced)
- Commit: `dd91413c`. `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:2400-2410`.
- Why: `FINALIZED_VIDEO_PARSE_RETRY_MS` and its one-line KDoc were inserted between
  `sleepPreservingInterrupt`'s KDoc ("Retry backoff that keeps the interrupt contract …") and the
  function. The function's real KDoc now dangles above a second KDoc, and Dokka/IDE attach neither
  to `sleepPreservingInterrupt`. This cycle spent four commits (`58eb4f10`, `f32a5bbd`, `f603a208`,
  `0e9e1e84`, plus `01025551`) removing exactly this pattern. A scan of every main source file the
  cycle touched (compared against `e3a2bdd4`) finds this as the ONLY newly introduced stack.
- Fix: move the constant (with its KDoc) above the `sleepPreservingInterrupt` KDoc, or below the
  function. A check_docs/lint rule for `*/` followed by `/**` would stop the next one.
- Confidence: High. Status: Confirmed. Severity: Low.

### REG4-5 — AGG3-30 "interrupt-preserving recovery backoff" is unreachable, and its stated rationale is false
- Commit: `ce60c1a8`. `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:7609-7614`;
  `LaunchMediaRecoveryCoordinator.kt:244-301`, `:215-233`.
- Why: the code comment and KDoc say the old `runCatching { Thread.sleep() }` meant "a retired
  owner's recovery kept doing provider work". Nothing interrupts that thread, though. The thread is
  `ProcessLaunchMediaRecovery`'s single daemon executor, which is never `shutdownNow()`. The
  deadline only calls `exhaust()` and `future.cancel(false)`. The coordinator KDoc says outright
  that Engine recreation "can neither start another provider scan nor interrupt the scan". The new
  `backoff → false → EXHAUSTED` branch is therefore dead in production. The test drives only the
  pure loop with `backoff = { false }`, so it proves nothing about owner retirement.
- Failure scenario: no runtime failure. The defect is false documentation that a future change
  will rely on, for example assuming a retired Engine stops recovery, when recovery actually runs
  to completion or to the 120 s deadline.
- Fix: keep the helper, which is harmless, but reword both comments to "defensive: no current
  caller interrupts this thread". If owner retirement is really meant to stop recovery, design that
  explicitly; do not imply it.
- Confidence: High. Status: Confirmed. Severity: Low.

### REG4-6 — The per-shot characteristics re-read brings back the cycle-2 PERF2-5 cost, and the gate's KDoc now describes a fixed problem as current
- Commit: `f32a5bbd`. `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraController.kt:2277-2282`,
  `:2422-2432`, `:2490-2512` (`LazyReadRetryGate` KDoc), `:2515-2527`.
- Why: AGG2-19/PERF2-5 rate-limited this exact read because, while the read kept failing, it was a
  `getCameraCharacteristics` Binder call "inside `tryComplete` on EVERY still … each frame of a
  BURST/AEB chain, with the live Images held". `shot = true` now bypasses the gate on every
  completion, so that behaviour is back by design. The new KDoc says the cost is "bounded by the
  shutter". `LazyReadRetryGate`'s KDoc still names that per-still Binder call as the reason the gate
  exists, but the gate no longer governs that call.
- Failure scenario: after a failed open-time read with the HAL still returning errors, a 20-frame
  BURST issues 20 Binder calls on the camera handler while each frame's Images are held. Before
  `f32a5bbd` it issued one.
- Fix: allow one gate-bypassing re-read per capture CHAIN (or per `CaptureRequest` sequence) rather
  than per completion, which still meets AGG3-9 (the first held shot always gets a fresh read), and
  update the `LazyReadRetryGate` KDoc to say which callers it governs now.
- Confidence: High. Status: Confirmed (code). Severity: Low (the trigger is a rare persistent read failure).

### REG4-7 — The architecture table cites a symbol that does not exist and the wrong RAW-law source
- Commit: `3d0946ab`. `docs/ARCHITECTURE.md:715`.
- Why: the `rawRequiresStandalone` row says the UI reads "`CameraCaps.rawForcesStandalone`". There
  is no such member. Since `176e2199` (AGG3-13), every UI route decision reads
  `CameraEngine.rawForcesStandalone` (`CameraEngine.kt:1764`, via `CameraViewModel.standaloneRouteFor`),
  and `CameraUiState.rawForcesStandalone` (`CameraState.kt:1728`) is display-only (it feeds
  `CameraUiState.unifiedZoom`). The new check_docs rule (`3d0946ab`) verifies only that each field
  name has a row, not that the cited seams are real, so the error passed the gate.
- Fix: cite `CameraEngine.rawForcesStandalone` (route decisions) and `CameraUiState.rawForcesStandalone`
  (display copy, PMA110 default until the first inventory).
- Confidence: High. Status: Confirmed. Severity: Low.

### REG4-8 — `partition_report --write-regions` can launder exactly the drift the line-identity gate was added to catch
- Commit: `c70af129` (used by `6ec34b3d`, `777b0ee1`). `tools/coverage/partition_report.py:330-355`
  (`regenerated_manifest`), `:376-436`.
- Why: the gate exists because "a reviewed guard [could] be deleted and an unreviewed one added
  elsewhere in the same class while the manifest kept 'explaining' a line that no longer existed"
  (TE3-1). `--write-regions` recomputes every region from the report and copies the OLD rationale by
  class name, so that same substitution goes green with one command. The diff shows only shifted
  line numbers, which reviewers routinely approve as "regions moved". I checked the three regions
  regenerated this cycle: they still cite the guards their rationales describe
  (`CameraController.kt:2687`, `CameraState.kt:916`, `MediaStoreWriter.kt:3224-3225`). So nothing
  was laundered this cycle, but the escape hatch has no forcing function.
- Fix: carry the rationale only when the cited lines' SOURCE TEXT (normalized and hashed) is
  unchanged, and write `UNREVIEWED:` otherwise. Alternatively, record a per-row text hash in the
  manifest and gate on it, so a pure line shift regenerates silently and a content change cannot.
- Confidence: Medium. Status: Likely. Severity: Low.

### REG4-9 — The injected-signing and approval refusals cover a hard-coded task set that may not be AGP's whole signing surface
- Commits: `638b666a`, `4e603375`. `app/build.gradle.kts:772-818` (`releaseSigningTasks` = package,
  packageBundle, packageUniversalApk, signBundle).
- Why: SEC3-1's premise is that AGP REPLACES the variant signing config under
  `android.injected.signing.*`. Every task that signs with that config therefore needs the refusal.
  Bundle-to-APK tasks (`makeApkFromBundleFor<Variant>` / `extractApksFor<Variant>`, used by
  install-from-bundle) also sign with the variant config and are not in the set. The set predates
  cycle 3 (the approval gate), but cycle 3 reused it as the "every task that PACKAGES OR SIGNS
  release bytes" list and documents it that way.
- Failure scenario: `./gradlew :app:extractApksForRelease -Pandroid.injected.signing.…` (or IDE
  "deploy from bundle" with injected signing) produces release-signed APKs with neither refusal
  firing. Release evidence is unaffected, because `build_immutable_release.py` allowlists only
  assemble/bundle outputs. Developer-side signed artifacts are not.
- Fix: match by the task's signing-config input or by type instead of by name. For example, refuse
  any release-variant task whose name contains `Apk` or `Bundle` and that has a `signingConfig`
  input, or enumerate from `tasks.withType<…>` and log each name. Then pin the set with a
  `./gradlew :app:tasks --all` check.
- Confidence: Low. Status: Needs-manual-validation (confirm the AGP 9.3.2 task names and their
  signing inputs). Severity: Low.

### REG4-10 — The release wrapper now refuses weak passwords on the CURRENT approved key, and this was never exercised
- Commits: `b67e9ab5`, `086f4f76`. `tools/build_immutable_release.py:748-764`.
- Why: `require_approved_upload_key` now applies `meets_generated_secret_floor` to the effective
  store and key passwords of whatever key is approved today. Cycle 3's final gate ran
  `verify_host.py` without `--release` (it needs local signing credentials), so the documented
  release path (`verify_host.py --release`, `build_immutable_release.py`) never ran against the real
  `keystore.properties`. If the currently approved key's passwords predate the generated-secret
  helper (for example a manually chosen password that the floor would now reject), legitimate releases now stop
  with "release store password does not meet the strong-key policy", and the only remedy is a key
  rotation, which is an owner decision (AGG-67).
- Fix: before the next release cut, the maintainer runs `python3 tools/verify_host.py --release`
  locally and records the outcome in the plan. If the floor rejects the approved key, decide
  explicitly (rotate, or apply the floor only to keys approved after a cutoff fingerprint) rather
  than discovering it on release day.
- Confidence: Low. Status: Needs-manual-validation. Severity: Low.

### Residual (incomplete, not a regression) — `effectiveFor` erases `normalizedFor`'s RAW-only fallback
- Commits: `6cae37ed`, `928745b3`. `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:1512-1513`.
- `normalizedFor` maps a processed-only request on a RAW-only session to `{dng}`, and the trailing
  `.copy(dngRaw = dngRaw && outputs.raw)` turns that back into `{}`, so the OSD shows no format and
  the storage estimate is 0 while capture writes a DNG. The plan never yields RAW without the
  processed reader (`useRaw` needs `streamAttempt < 1`, which implies `useJpeg`), so this is
  unreachable today. It is also better than the pre-cycle-3 behaviour, which wrote `{}` INTO the
  request. Since MRG3-3, the simplest correct form is `normalizedFor(outputs)`. Not counted.

---

## Commits checked and judged clean

| SHA | One line |
|---|---|
| `41208a06` | Plan scaffold only; scope matches the aggregate. |
| `f566c8db` | Review archive/record move only; no source. |
| `8d0d6f11` | `init_fixture_repo` covers all six fixture `git init` sites; `gc.auto=0` + `maintenance.auto=false` are the right knobs. |
| `828b796f` | Plan log only. |
| `58eb4f10` | `!tenBitVideoOnly` on `useRaw` closes the TELE regular-full rung; test iterates every rung × tele × hiRes; PMA110 non-10-bit plans unchanged; KDoc unstacked correctly. |
| `6ba15217` | Lens-based divisor is the exact inverse of the lens-based entry snapshot; `retainedRearWireZoom` delegates (identical for its old inputs); crop-only tablets still divide by MAIN. |
| `bf76c0ee` | Test constructors updated for the new flag. (Behavioural concern is REG4-1.) |
| `f603a208` | `currentRawWanted()` read on main after the engine commit mirrors `currentRequestedVideoSize`; generation guard still returns early for a newer transaction. |
| `04fad2e8` | `PhotoSessionOutputs.hlg` comes from the accepted plan's `useHlg`; no equality-sensitive consumer of `PhotoSessionOutputs` exists; caption test drives the real plan. |
| `176e2199` | Every VM route decision now reads `engine.rawForcesStandalone`; remaining state reads are display (`unifiedZoom`); test runs GENERIC engine vs PMA110 state default. |
| `9cfd288a` | Defaults removed from `restoredRouteUsesCurrentCaps` and all ownership helpers; compile-time enforcement as intended. |
| `0b2a6e99` | `dispatchDngPreCaptureAllocation` preserves construct → register → start → `dispatchResult` order; the test now drives the production wiring. |
| `ce60c1a8` | Code is correct and harmless (see REG4-5 for the false rationale). |
| `2295ea79` | Construction-0 primary verdicts unchanged (non-primary fields read leniently, never failing); idat-relative bounds correct; INVALID only on provable range errors or an absent idat; no read budget to exhaust. |
| `01c42176` | INVALID only when `muxerStopThrew` AND the provider opened; claim that SKIPPED is set only by the tolerated stop throw verified at `VideoRecorder.kt:466-473`. |
| `5ebe8b07` | Age gate (`processStartSecs`) keeps live rows out of `reassertPending`, so there is no race with a live publish. (Policy concern is REG4-2.) |
| `a36c5889` | Provenance recorded only via the Activity wrapper (the sole UI store path); absent key = null keeps the AGG2-26 rule; main blob removes the key. |
| `5a3b89e1` | `processedCleared` only fires when every displayed processed chip is cleared; the sole caller is the pre-inventory path. |
| `6cae37ed` | Engine capture still normalizes at `CameraEngine.kt:5000/5192`; every readout switched to `effectivePhotoFormats`. |
| `cb62f4ea` | The real `runNativeWithPublication` does not release on a `publicationOwner` throw, so the finally's direct release is the only one; the per-generation token claim is accurate. |
| `6abb57ac` | Single caller (`CameraEngine.kt:5751`) never retries `jpegBytes()`; dropping the pixels on failure is safe. |
| `9ddf41bf` | No remaining caller of `cleanupOrphanedPending`; KDoc retargeted to the batch function; architecture doc updated. |
| `0e9e1e84` | Both recorder KDocs reattached to their functions. |
| `c70af129` | Line-identity drift checks are sound and only stricter (laundering concern is REG4-8). |
| `638b666a` | Task-path regex refuses `-`-prefixed and empty argv; every documented invocation (verify_host, ARCHITECTURE, BACKLOG, play docs) uses plain task paths plus `--output` before the tasks; refusal is value-free. (Coverage concern is REG4-9.) |
| `b67e9ab5` | Floor logic moved byte-for-byte; the effective-key-password resolution matches Gradle `signingValue … ?: storePassword`. (Operational concern is REG4-10.) |
| `7b8fbd0a` | File-over-env alias precedence and the `CHANGE_ME` placeholder match `app/build.gradle.kts:369-399`; duplicate `keyAlias` refused. |
| `4e603375` | No-signing refusal now attaches to the package/sign tasks; `releaseSigningTasks` is top-level and in scope. |
| `54abb79b` | All four retained-take messages have EN+KO; the `when` over the new `RETAINED_UNVALIDATED` is exhaustive at its only consumer; 6 s duration covers the two-sentence copy. |
| `1607bfeb` | check_docs follows the floor to `upload_key_policy.py` and both wrappers; substrings survive `086f4f76`'s re-indent. |
| `3d0946ab` | Profile table covers all six `DeviceProfile` fields; the newly listed `’ ↑ ↓` glyphs ARE present in all three bundled Inter faces (fontTools cmap check); other cited symbols exist. (One wrong symbol is REG4-7.) |
| `6ec34b3d` | Index-skip test is real; the three regenerated regions still cite the described guards. |
| `01025551` | Rollback KDoc reattached to `rollbackRestorableSessionGeneration`. |
| `928745b3` | Readout now hides DNG when the session has no RAW; request untouched; tests updated consistently. |
| `dd91413c` | One confirming re-parse only on the destructive branch; non-stop-threw path unchanged. (Stacked KDoc is REG4-4.) |
| `086f4f76` | Approval/fingerprint are now reported before the floor; floor still precedes keytool. |
| `5107ae84` | `checkNotNull` removes the platform-type warning without changing the assertion. |
| `777b0ee1` | HeifBoundedReader region moved by +18 to the same `byteCount !in 0..8` guard. |
| `887d39fb` | Plan/merge-review record; claims match the commits (minor: "29 signed commits" for a 30-commit range). |
| `c5bfd1c8` | The predicate and wiring do what AGG3-7 says (behavioural regression is REG4-1). |
| `f32a5bbd` | AGG3-9 behaviour is correct as specified (cost regression is REG4-6). |
