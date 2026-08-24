# Verifier review — cycle 39

Date: 2026-08-24

Reviewed revision: `5ee6b2133fb4ab07fb3605fd5576b087f5f43224`

Role: evidence-based behavioral correctness verification

## Verification scope

Built a complete inventory of all 493 tracked paths and checked the committed authorities against
production call sites, resources, tests, and host tooling. The behavioral pass covered every
production Kotlin module and concentrated on Camera2/GL generation ownership, accepted capability
and output truth, exposure/zoom/rotation laws, still-family durability, recording admission and
finalization, native quarantine, review work lanes, UI guards and semantics, EN/KO presentation,
privacy, and immutable artifact provenance.

For the current change set, verified these concrete contracts:

- `videoStabModeChangeRequiresReconfigure` compares the exact HAL values resolved from the current
  accepted caps; unsupported ENHANCED→STANDARD/OFF normalization is therefore side-effect-free,
  while genuine OFF/ON/PREVIEW transitions remain reconfiguring. A pre-capability restore only
  stores the requested label, and every later open reads that stored value.
- `finderRect` no longer advertises `FINDER_BOTTOM_MARGIN`; its `y` is the maximum of top-anchor,
  frame-height floor, and finite non-negative measured clearance. Tests now assert position changes,
  size invariance, and GL/Compose density scaling.
- The shared review-pool exhaustion fixture starts four independent lane owners before probing the
  healthy lane, so latest-wins replacement cannot retire a blocker before its start handshake. The
  analogous two-worker setup test handshakes A before submitting B.
- Selected-disabled focal chips retain `HudPlate` before the quiet wash, and native Compose coverage
  renders all four selected/enabled states over both white and black frame fixtures.

Independent host evidence passed: 120 documentation checks, 99 tooling tests, nine coverage-tool
tests, 184 device-harness self-tests, and `git diff --check`. Android/device behavior was not run or
inferred in this specialist pass; open items in `docs/FIELD_CHECKS.md` remain explicitly open.

## Findings

No new confirmed findings.

## Final verification sweep

Rechecked the repaired seams against adjacent call paths rather than only their focused tests, then
swept the remaining authorities for conflicting claims, stale parameters, missing route replay,
unowned async publication, and fail-open evidence behavior. No current correctness, security,
data-loss, or maintainability defect survived evidence checking.
