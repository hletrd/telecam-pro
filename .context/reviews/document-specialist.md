# Document specialist review — cycle 37

Date: 2026-08-24

Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082` (`origin/main`)

Workspace: isolated detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Coverage

I inventoried all 489 tracked paths and read the current authorities, README, field ledger,
privacy/data-safety/Play material, release plans, resources/manifests, relevant implementation/tests,
and prior reviews. Cycle-36 history is resolved. `tools/check_docs.py` passes 112 checks with 24
optional-private skips; its current contracts do not cover the mismatches below.

## Findings

### DOC37-01 — the documented shared capability projection is false for Gamma quick controls

- **Severity / confidence:** Medium / High.
- **Exact regions:** authority at `CLAUDE.md:916-934` and `docs/ARCHITECTURE.md:1201-1206`; transfer projection at `camera/CameraState.kt:1164-1187`; correct menu filtering at `ui/controls/ProControls.kt:919-975`; contrary quick paths at `ui/controls/ControlCycles.kt:230-244`, `ui/controls/FnQuickActions.kt:129`, and `ui/controls/ManualDials.kt:621-632`.
- **Mismatch:** authorities say settings/Fn share a capability projection and cycle only advertised choices. A no-Main10 HEVC encoder has `[SDR]`, but quick Gamma stays enabled, requests the global next value, and `ui/CameraViewModel.kt:2283-2299` normalizes it back to SDR.
- **Failure scenario:** maintainers treat Fn parity as enforced while a user sees a hot silent no-op across three quick surfaces.
- **Suggested fix:** make every surface consume `availableTransfers`; add a docs/test contract for singleton lists; retain the authority once executable parity is restored.

### DOC37-02 — stabilization is documented as capability-gated while UI/OSD expose requested fiction

- **Severity / confidence:** Medium / High.
- **Exact regions:** `camera/CameraState.kt:145-169`; `CLAUDE.md:916-929`; `docs/ARCHITECTURE.md:1201-1206`; fallback at `camera/CaptureCapabilities.kt:244-250,551-562`; unfiltered UI at `ui/controls/ProSheet.kt:1246-1269`, `ui/controls/FnQuickActions.kt:122`, `ui/controls/ManualDials.kt:574-587`, and `ui/overlays/Overlays.kt:962-970`.
- **Mismatch:** OFF-only hardware can be labeled Standard/Active while the request is OFF; without PREVIEW_STABILIZATION the UI can claim Active/extra crop while the request is Standard ON. The clean-clone authority overstates as-built UI truth.
- **Failure scenario:** a maintainer or reviewer trusts OSD as accepted session truth even though acceptance remains private to controller fallback and never reconciles UI state.
- **Suggested fix:** project exact choices into menu/Fn/state/OSD and reconcile restore on target caps. Add a docs contract tying UI projection to `videoStabModes`.

### DOC37-03 — `PRIVACY.md` is less complete than the same-date published and in-app policies

- **Severity / confidence:** Low / High.
- **Exact regions:** `PRIVACY.md:1-38`; `privacy-policy/index.html:225-247`; `app/src/main/res/values/strings.xml:125`; Korean counterpart `app/src/main/res/values-ko/strings.xml:112`.
- **Mismatch:** HTML and EN/KO fallback disclose ordinary camera/lens/exposure/time metadata, no location/GPS, and local library read without transmission. Markdown, with the identical update date, omits those facts.
- **Failure scenario:** repository and published/in-app readers receive different current-policy disclosures; the green docs checker gives no drift signal.
- **Suggested fix:** synchronize Markdown, preferably generate presentations from one fact set, and require metadata/no-location/on-device-read facts in all active copies.

## Final sweep

Toolchain versions, Android floor, release readiness, stale screenshot blockers, field status,
permissions, ownerless-media caveats, Loupe exception, DNG routing, and cycle-36 evidence agree with
current source. Historical evidence is clearly labeled superseded. No additional drift survived.

## Totals

- New findings: 3
- Severity: 2 Medium, 1 Low
- Confidence: 3 High
