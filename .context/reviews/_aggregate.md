# Aggregated deep review — cycle 46

Date: 2026-08-25
Reviewed revision: `f03f40c563c3f8dd2ecadf48e4d41f064a4433bd` (`origin/main`)
Workspace: isolated clean clone `/tmp/find-x9-ultra-cycle46.qsEwxz`

## Coverage and aggregation

Five parallel specialist groups covered every required role: code-reviewer, architect,
performance-reviewer, tracer, security-reviewer, debugger, critic, verifier, test-engineer,
document-specialist, and native Android designer. No repository-local reviewer agents were
registered. Each group inventoried all 517 tracked paths, examined its full specialist surface and
cross-file interactions, and completed a final missed-issue sweep. Browser automation was not
applicable to this native Jetpack Compose app. No device behavior was run or inferred.

The reviewers produced nine raw findings. Cross-report comparison found no duplicates: the two
rollback findings affect distinct derived owners, the status finding is a separate timer-publication
race, the two device-harness findings concern independent completeness and identity boundaries, and
the three presentation/documentation items have different user or release surfaces. All nine remain
as deduplicated current findings. No finding was supported by more than one specialist group, and no
agent failed.

## Findings

### AGG46-01 — failed mode rollback leaves target transfer and microphone owners active

- **Severity / confidence:** High / High
- **Source:** code-reviewer/architect
- **Evidence:** `CameraViewModel.kt:2313-2324` applies target-mode transfer and standby-meter
  ownership immediately; `CameraEngine.kt:742-846` restores mode/session without transfer;
  `CameraViewModel.kt:910-953` handles rollback without replaying transfer or microphone ownership.
- **Failure:** a failed Video↔Photo route change restores the accepted mode while GL/Engine transfer
  and standby `AudioRecord` remain owned by the rejected mode, risking wrong HLG/SDR presentation or
  hidden microphone ownership.
- **Plan direction:** make mode-derived effects part of rollback ownership and add forced rollback
  coverage in both directions, including recording configuration after Ready returns.

### AGG46-02 — facing rollback leaves punch-in resolved for the rejected route

- **Severity / confidence:** Medium / High
- **Source:** code-reviewer/architect
- **Evidence:** `CameraEngine.kt:3408-3453` resolves punch-in for the optimistic route;
  `CameraEngine.kt:742-809` restores the route without `pushPunchIn()`; the route-resolution contract
  is documented at `CameraEngine.kt:6554-6567`.
- **Failure:** a rejected front entry can leave rear loupe intent visually off, while a rejected
  front exit can leave the rear-only crop active on the restored front route; the stale value also
  survives GL-generation replay.
- **Plan direction:** replay punch-in after owned route rollback or centralize all route-derived
  renderer state, with entry/exit failure and replacement-GL tests.

### AGG46-03 — MR recall can detach the visible status from timer ownership

- **Severity / confidence:** Medium / High
- **Source:** security-reviewer/debugger
- **Evidence:** `CameraViewModel.kt:1459-1516` writes the recall status and later arms its timer
  outside the serialized status gate, while ordinary publications use the atomic boundary at
  `CameraViewModel.kt:1671-1720,3396-3412`.
- **Failure:** an Engine event can publish between those steps; the stale recall timer then removes
  the current event's timer, refuses to clear its different identity, and leaves the current status
  stuck indefinitely.
- **Plan direction:** publish recalled state and timer through one gate-owned operation and add a
  latch-controlled interleaving regression plus an audit fence for timer callers.

### AGG46-04 — strict full/reliability device runs can exit green with runtime skips

- **Severity / confidence:** Medium / High
- **Source:** critic/verifier/test-engineer
- **Evidence:** `device-tests/dtest/framework.py:122-139,156-166` records caught runtime `Skip` as a
  skip and ignores it after any pass, despite the strict `--allow-partial` contract in
  `device-tests/README.md:50-54`; a pass-plus-runtime-skip reproduction exited 0.
- **Failure:** a full run can lose foreground, skip required later cases, and emit a green
  attestation that downstream users mistake for complete feature evidence.
- **Plan direction:** classify runtime skips as incomplete for strict full/reliability tiers while
  preserving ordinary smoke behavior, and test strict versus explicitly partial runs.

### AGG46-05 — kill-window durability cases do not join recovered media to the attempted operation

- **Severity / confidence:** Medium / High
- **Source:** critic/verifier/test-engineer
- **Evidence:** `device-tests/cases.py:4731-4764` accepts unkeyed post-relaunch rows for stills, and
  `device-tests/cases.py:4826-4854` chooses the last new MP4 without a `RecordingSpec` identity,
  unlike the exact joins at `device-tests/cases.py:1166-1200,4778-4800`.
- **Failure:** a delayed prior save, unrelated MP4, missing still sibling, or wrong recording stem
  can satisfy a durability case while the operation actually initiated by the case was lost.
- **Plan direction:** freeze Single drive/requested formats and exact capture/recording identity,
  require the exact expected delta, and add negative fake-harness cases.

### AGG46-06 — restored timelapse intervals can escape the 1–30 second UI domain

- **Severity / confidence:** Low / High
- **Source:** critic/verifier/test-engineer
- **Evidence:** `SettingsStore.kt:303-308` lower-bounds only; `ProSheet.kt:954-964` clamps only the
  slider thumb; `CameraEngine.kt:2826,4318` schedules the raw restored value.
- **Failure:** a corrupt or legacy `intervalSec=300` displays an irreproducible 300-second setting
  with the thumb at 30 and makes the running sequence wait five minutes.
- **Plan direction:** define one shared interval domain, normalize at storage and Engine ingress,
  and test below/in/above-domain settings and MR values.

### AGG46-07 — a real capture reuses the no-capture pictogram while loading or without pixels

- **Severity / confidence:** Medium / High
- **Source:** document-specialist/designer
- **Evidence:** `MediaReview.kt:654-709` resets each non-null URI to a nullable empty holder;
  `MediaReview.kt:710-779` draws the same no-capture glyph for loading and failed still/video
  thumbnails while semantics announce a real review action.
- **Failure:** immediately after capture, or permanently after a null thumbnail result, the tile
  visually claims no capture exists even though tapping it opens the real photo/video.
- **Plan direction:** model Empty/Loading/Ready/Failed explicitly, reserve the empty glyph for null
  URI, add quiet media-specific fallbacks, and cover every rendered branch.

### AGG46-08 — tracked tablet Play screenshots have no committed validity authority

- **Severity / confidence:** Low / High
- **Source:** document-specialist/designer
- **Evidence:** `docs/assets/play/screenshots/asset-validity.json:1-21` and
  `docs/play-console-submit.md:587-639` govern phone files only; four tracked `tablet/*.png` assets
  have no committed provenance/readiness or do-not-upload verdict; `tools/check_docs.py:1462-1477`
  skips the only tablet check when optional private documentation is absent.
- **Failure:** a clean-clone release operator can upload plausible but unauthenticated tablet assets
  while all committed checks remain green.
- **Plan direction:** commit tablet digest/provenance/readiness authority and submission guidance,
  then validate it even without optional private listing state.

### AGG46-09 — cycle 45 overclaims converter-specific responsive coverage

- **Severity / confidence:** Low / High
- **Source:** document-specialist/designer
- **Evidence:** `docs/plans/2026-08-24-rpf-cycle45.md:93-100,128-134` claims Phone and Converter
  EN/KO/RTL coverage, but `DropdownResponsiveComposeTest.kt:50-87,129-167` emits only hard-coded
  English phone options and never composes the converter consumer/localized values at
  `ProSheet.kt:1250-1297`.
- **Failure:** a converter-specific localization or wrapper regression can pass while the durable
  completion record is treated as proof it was tested.
- **Plan direction:** add the promised production-facing Phone/Converter EN/KO/RTL matrix and append
  a dated correction to the historical plan record.

## Agent failures

None.

## Totals

- Raw specialist findings: 9
- Deduplicated new findings: 9
- Severity: 1 High, 5 Medium, 3 Low
- Confidence: 9 High
- Cross-agent duplicates/agreement: none
- Device/manual evidence was not inferred from host behavior.
