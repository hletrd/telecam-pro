# Document-specialist review: RPL cycle 3 (HEAD e3a2bdd4)

Angle: doc/code mismatches in CLAUDE.md, docs/ARCHITECTURE.md, docs/FIELD_CHECKS.md, README.md, KDoc,
the toolchain table, string resources, and the docs gate itself. Prior-cycle DOC2-* items were
checked against the current files first. DOC2-1 (AE "≤0.35 stop/tick"), DOC2-3, DOC2-7, and DOC2-8
("zero-byte read retries") are fixed on disk. Note: the on-disk CLAUDE.md is newer than the copy
injected into agent context. Every finding below was verified against the on-disk file.

## Findings

### DOC3-1: The docs gate is red in this worktree because it scans untracked review notes under a "tracked docs" label
- Severity: Medium. Confidence: High. Status: Confirmed (ran `python3 tools/check_docs.py`: 189 checks, 1 failed).
- Where: `tools/check_docs.py:1542-1558` (`password_property_scan_paths` globs `.context/**/*.md` from the
  worktree). `tools/verify_host.py:138` runs it as part of the authoritative host gate.
- Problem: the check's label says "tracked docs state no password length, character class, or delivery
  channel", but the check reads every `.context/**/*.md` on disk. That includes gitignored, untracked
  review files. Two files fail it right now:
  - `.context/reviews/archive-rpl-cycle1-2026-10-02/security-reviewer.md` is untracked (not in
    `git ls-files`) and has a "≥20-char generated p…" sentence.
  - `.context/reviews/security-reviewer.md` (cycle 3, written this cycle) suggests applying password
    rules.
- Failure scenario: the gate lane runs `python3 tools/verify_host.py` for cycle 3 and gets a nonzero
  exit from check_docs. The cause is reviewer notes, not code or committed docs. Each new security
  review can make the gate red again. A clean clone passes, so the gate result depends on the
  worktree. The cycle-1 security review was probably left out of the archive commit for this reason,
  which also drops review history.
- Fix: decide what the check protects.
  - If it protects committed docs only: build the scan list from `git ls-files` (as the label says),
    or exclude `.context/reviews/**`.
  - If review archives are meant to be committed and policed: keep the scan, and before archiving,
    redact the two sentences above so they name no password properties.
  - Either way, add a host test that an untracked file under `.context/` cannot change the result.

### DOC3-2: The authority docs describe PMA110 DeviceProfile quirks as universal, and one statement is wrong for every non-PMA110 device
- Severity: Medium. Confidence: High. Status: Confirmed.
- Where:
  - `docs/ARCHITECTURE.md:610-611`: "The 4 s ceiling … is applied to EVERY route as a conservative
    assumption."
  - `CLAUDE.md:363-365` (`HAL_SAFE_MAX_STILL_EXPOSURE_NS` "clamps the advertised range at the CAPS
    seam", no qualifier).
  - `docs/ARCHITECTURE.md:630-631`: "A logical photo session uses preview + `YUV_420_888` … It never
    requests RAW".
  - `docs/ARCHITECTURE.md:702` route table: "Photo, TC off, RAW/DNG wanted → Standalone rear lens".
  - `CLAUDE.md` DNG bullet: "Wanting RAW is what MOVES photo off the logical seamless camera".
- Code:
  - `camera/DeviceProfile.kt:64-80`: GENERIC has `stillExposureCeilingNs = null`,
    `logicalStillRequiresYuv = false`, `rawRequiresStandalone = false`, `vendorTcSessionType = false`.
  - `deviceProfileForRoute` (`DeviceProfile.kt:89`) gives EXTERNAL routes GENERIC even on PMA110.
  - `CaptureCapabilities.kt:405-418`: a null ceiling trusts the advertised range.
  - `CameraState.kt:536-543`: `standaloneRouteWanted = videoMode || (rawWanted && rawForcesStandalone)`.
  - `CameraController.kt:2793`: `useRaw` admits RAW on the logical camera when `!rawStandaloneOnly`.
  - `resolveMustUseYuvStill` keys YUV on the profile.
- Problem: the 4 s clamp, the YUV logical still, the standalone-only RAW law, and the move off the
  seamless camera for DNG are PMA110 profile flags. On every other device the opposite happens: the
  advertised exposure range is trusted, DNG stays on the seamless logical camera, and HAL JPEG is used.
  Five of the six profile fields (`stillExposureCeilingNs`, `logicalStillRequiresYuv`,
  `rawRequiresStandalone`, `vendorTcSessionType`, `vendorOplusRequestHints`) appear in neither
  CLAUDE.md nor ARCHITECTURE.md. Only `frontStreamPreMirrored` is documented.
- Failure scenario: someone working on a Pixel or Galaxy report reads ARCHITECTURE.md:610 and expects
  a 4 s cap on long exposures. Or they expect DNG to reopen onto a standalone lens and "fix" the
  seamless-camera DNG path as a bug. That re-imposes a PMA110 workaround speculatively, which the
  multi-device constraint forbids ("add quirks only with measurements").
- Fix: add a DeviceProfile table to ARCHITECTURE.md (field, PMA110, GENERIC, which seam reads it).
  Qualify the four statements above with "PMA110 profile; GENERIC trusts the advertised value / keeps
  RAW on the logical route". Optionally add a check_docs assertion that every `DeviceProfile` field
  name appears in ARCHITECTURE.md.

### DOC3-3: CLAUDE.md still says the TC OIS profile is UNVERIFIED and needs a shake A/B, but FIELD_CHECKS C3 closed that A/B
- Severity: Low. Confidence: High. Status: Confirmed.
- Where: `CLAUDE.md:806-807` ("UNVERIFIED: whether the OIS profile actually differs at 300 mm (needs a
  physical shake A/B with the converter mounted)") vs `docs/FIELD_CHECKS.md:208-219` ("C3. TC OIS —
  ✅ CLOSED 2026-07-28 (operator; no observable difference)").
- Problem: the two documents disagree on whether a field check is outstanding. FIELD_CHECKS calls
  itself the exhaustive committed ledger.
- Failure scenario: an agent schedules a field check that is already done, or reports it as an open
  residual in a release summary.
- Fix: in CLAUDE.md, replace the UNVERIFIED sentence with "Operator handheld A/B 2026-07-28 found no
  observable difference (FIELD_CHECKS C3); a distinct 300 mm profile is not demonstrated."

### DOC3-4: A stale "debug-gated 10-bit EXPERIMENT" comment sits on the shipping non-SDR video rung
- Severity: Low. Confidence: High. Status: Confirmed.
- Where: `camera/CameraController.kt:2740` (`sessionAttemptPlan`): "10-bit EXPERIMENT rung (debug-gated
  upstream)".
- Code: `tenBitVideoOnly = tenBitHlg && caps.supportsHlg10()` (`CameraController.kt:672`), and
  `tenBitHlg = tenBitSessionWanted(videoMode, transfer)` (`CameraEngine.kt:2484, 4362`). This is the
  RELEASE path for every non-SDR video session. The only debug gate, `tenBitExperimentEnabled()`
  (`CameraEngine.kt:7697`), controls the RGBA1010102 EGL target, not this rung. Its own KDoc says it
  stopped feeding `tenBitHlg` (AGG-45).
- Problem: this is the drift CLAUDE.md's 10-bit bullet warns about. A maintainer may treat the rung
  as a debug toy and relax the "drop both still readers" rule. Per CLAUDE.md that crashes the HAL
  (HLG10 + JPEG + RAW).
- Fix: change the comment to "Shipping non-SDR video rung (`tenBitSessionWanted`)". Keep the crash
  rationale.

### DOC3-5: The DNG route-input bullet (item 2) no longer covers the TELE no-reopen path added in cycle 2
- Severity: Low. Confidence: High. Status: Confirmed.
- Where: `CLAUDE.md:183-189`: "`setRawWanted` begins its own optics transaction with `overrideId =
  userCameraPin`. It is the ONLY reopen whose route ANSWER changes".
- Code: since `5e4cc454`, `setRawWanted` (`CameraEngine.kt:4055-4068`) opens a transaction only when
  `dngIntentChangesRearRoute(video, teleconverter, …)` is true. That function is `!teleconverter && …`
  (`CameraEngine.kt:8484-8492`). TELE, Video, FRONT/EXTERNAL, pre-start, and GENERIC
  (`rawLaw = false`) all take the direct-write path (`rawWantedDirectWrites`). This is correct
  because TELE's standalone session carries RAW by route.
- Problem: CLAUDE.md describes the transaction as unconditional. The rollback rule that keeps a newer
  direct write (`keepNewerDirectWrite` / `rollbackRawWanted`) is documented nowhere in CLAUDE.md or
  ARCHITECTURE.md.
- Failure scenario: a later review flags the TELE DNG toggle as "skips the re-resolving transaction
  CLAUDE.md requires" and reinstates the reopen. That brings back the black dip per toggle (AGG2-10).
- Fix: add one sentence to item 2: "only when the route answer actually flips
  (`dngIntentChangesRearRoute`); TELE, Video, FRONT/EXTERNAL and non-RAW-law devices are direct writes
  that a later rollback preserves (`rollbackRawWanted`)."

### DOC3-6: CLAUDE.md's "covered glyph set in use" is incomplete
- Severity: Low. Confidence: High. Status: Confirmed (fontTools cmap check of all three bundled Inter
  faces).
- Where: `CLAUDE.md:574` lists `§ © ° · ± × γ — … → ∞ ≈`.
- Code: user-facing literals also use U+2019 ’ (`values/strings.xml:125, 351`) and U+2191 ↑ / U+2193 ↓
  (`ui/controls/ProSheet.kt:1898-1899`). All three faces cover these, so there is no rendering bug.
  The list is just no longer the "set in use". `values-ko` has no non-Hangul glyph outside Inter.
- Fix: add `’ ↑ ↓` to the list, or have check_docs derive the set from resources and assert coverage
  against the TTF cmaps. fontTools is available on the host.

### DOC3-7: VideoFrameRate KDoc and code keep an 8K (≥4320) rule that the 3840-wide shipping selector cannot reach
- Severity: Low. Confidence: Medium. Status: Confirmed (dead path, not a defect).
- Where: `camera/CameraState.kt:1083-1106` (`is8k && r.fps > 30`) vs the selector cap
  `CameraEngine.kt:7894, 7905` (`capWidth = 3840`) and CLAUDE.md ("shipping selector capped at 3840
  pixels wide").
- Problem: the KDoc presents the 8K cap as a live gating rule. It is unreachable, so a reader may
  think 8K is offered somewhere.
- Fix: mark it "defensive, unreachable while the selector caps at 3840 wide", or drop it with its
  test.

### DOC3-8: The private TESTING.md still pins Robolectric 4.16.1
- Severity: Low. Confidence: High. Status: Confirmed (private doc, optional in clean clones).
- Where: `docs/TESTING.md:119, 138` ("Robolectric 4.16.1", "the exact 4.16.1 pin") vs
  `gradle/libs.versions.toml` `robolectric = "4.17"` (bumped in `da659328`). The android-all
  coordinate `16-robolectric-13921718-i7` is unchanged, so only the version number is stale.
- Fix: change it to 4.17. This is the same class of drift as DOC2-4/DOC2-6. check_docs skips
  PRIVATE_DOCS, so nothing catches it.

### DOC3-9: ColorProfiles KDoc says "the engine forces SDR when AVC is selected", but the ViewModel normalizes and the recorder refuses
- Severity: Low. Confidence: High. Status: Confirmed.
- Where: `video/ColorProfiles.kt:24-25`.
- Code: `CameraViewModel.onVideoCodec` (`:3021-3030`) applies `normalizedForEncoder`.
  `encoderSelectionAdmitsTransfer` (`VideoRecorder.kt:1857-1863`) refuses AVC with non-SDR. The engine
  itself never coerces.
- Problem: whoever adds another codec-selection door (MR recall, settings restore) may rely on an
  engine coercion that does not exist.
- Fix: change it to "the ViewModel normalizes transfer per codec (`normalizedForEncoder`); the
  recorder refuses a mismatched packet (`encoderSelectionAdmitsTransfer`)."

## Verified consistent (no finding)
- Toolchain table (CLAUDE.md:65-71), README badges and table, and ARCHITECTURE.md:1286-1292 all match
  `libs.versions.toml` (AGP 9.4.1, Kotlin 2.4.20, BOM 2026.09.00), the wrapper (Gradle 9.8.0), and
  `app/build.gradle.kts` (37/36/33).
- These numeric claims match their constants: PREVIEW_FLUIDITY 1/15 s, PREVIEW_SAFE 500 ms, still 4 s,
  digital gain ×16, AE 0.30/1.20/0.5 schedule, ZSL 1/6 stop / 2% / 400 ms / depth 3, FrameGap 200 ms,
  log budgets 180/120, 3A 3 s/15 s, FLAT 4.9 (½ g), LEVEL 2.5, TELE_MAX 60×, FINDER_MIN 3×, coalescing
  16 ms / quiet 250 ms / tail 700 ms / throttle 40 ms / ticker 33 ms / save debounce 500 ms, watchdog
  8 s + 8 s margin, DNG allocation 8 s, still-publication backlog 2, rejected-output backlog 8,
  pre-native backlog 4, standby handoff 400 ms, ≤3 standby recreates, MACRO_HOLD 700 ms, focus stats
  1 s, FocusDetail tile 16 / lag 32 / coverage 30%, preview recovery ≤3.
- The session ladder (non-TELE full → no RAW → no HLG → preview-only; TELE vendor ×3, regular ×3, then
  preview-only vendor and regular) matches `sessionAttemptPlan`.
- `AudioReadPolicy` matches CLAUDE.md (zero → retry, negative → failure, after stop → Stopped).
- EN/KO string parity: 0 missing, 0 extra. All non-ASCII glyphs are covered by the bundled Inter faces.
- README codec/bitrate/audio claims hold: HEVC Main for SDR, Main10 otherwise. 0.40 bpp × 4K × 29.97
  ≈ 99 Mbps. AAC 48 kHz stereo.

## Files examined
- Docs: CLAUDE.md (on disk), docs/ARCHITECTURE.md, docs/FIELD_CHECKS.md, README.md,
  docs/TESTING.md (private), docs/plans/2026-10-02-rpl-cycle2.md,
  .context/reviews/archive-rpl-cycle2-2026-10-02/_aggregate.md.
- Build: gradle/libs.versions.toml, gradle/wrapper/gradle-wrapper.properties, app/build.gradle.kts
  (SDK lines), app/src/test/resources/robolectric.properties, app/src/main/AndroidManifest.xml.
- Tools: tools/check_docs.py (scan paths, password check; full run), tools/verify_host.py (check_docs
  invocation), tools/android_sdk.py.
- Code: camera/DeviceProfile.kt, CaptureCapabilities.kt, CameraController.kt (sessionAttemptPlan,
  setPinAutoFps edge, constants), CameraEngine.kt (setRawWanted, dngIntentChangesRearRoute,
  tenBitExperimentEnabled, cachedCaps, standby handoff, constants), CameraState.kt
  (standaloneRouteWanted, tenBitSessionWanted, VideoCodec/BitrateLevel/VideoFrameRate,
  videoBitRate), AutoExposure.kt, ManualControls.kt, ZslAdmission.kt, DiagnosticTelemetry.kt,
  StandbyAudioController.kt, MacroProximity.kt, gl/FocusDetail.kt, stab/GyroEis.kt,
  video/AudioReadPolicy.kt, video/ColorProfiles.kt, video/VideoRecorder.kt
  (encoderSelectionAdmitsTransfer), ui/CameraViewModel.kt (timers, onVideoCodec),
  ui/controls/ProSheet.kt (glyphs), res/values/strings.xml, res/values-ko/strings.xml,
  res/font/*.ttf (cmap).
- Commits read for drift: 5e4cc454, c91d741f, plus the cycle-2 log e3a2bdd4..c2892dda.
