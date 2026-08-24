# Critic review — cycle 36

Date: 2026-08-24

Reviewed revision: `1f4588744084f1623ad017df1945d7c72a426c54` (`origin/main`)

Workspace: isolated worktree `/tmp/find-x9-cycle36.TOpdQ8`

## Scope and inventory

I read the complete `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` authorities
first, then inventoried all 486 tracked paths. I examined the complete production/debug Android
surface, resources and manifests, host and instrumented tests, build/release tooling, device harness,
privacy/Play material, all current review provenance, and completed plans through cycle 35. The
review challenged the repository from operator, maintainer, evidence, accessibility, failure-mode,
and release-readiness perspectives. No device behavior is inferred from host evidence.

## Findings

### CRIT36-01 — the new dual-open cleanup's Boolean state model is not total for a null outgoing owner

- **Severity / confidence / status:** High / High / Confirmed state-model defect; activation is
  race-timed.
- **Exact regions:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3545,3580-3592,
  3667-3674,3746-3765,7037-7050`; incomplete matrix at
  `app/src/test/kotlin/me/hletrd/telecampro/camera/DualOpenWaitTest.kt:101-136`.
- **Problem:** `reconfigureCamera` explicitly continues when `controller == null`, so `old` may be
  null. If the candidate native-refusal callback clears `controller` before a newer optics intent
  supersedes the attempt, the cleanup call computes both `slotVacant = controller == null` and
  `outgoingOwnsSlot = controller === old` as true: Kotlin's `null === null` is true. The reducer then
  rejects this production-reachable input with `require(...count { it } <= 1)`. The tests hand-build
  mutually exclusive booleans and therefore never exercise the nullable identities from which
  production derives them.
- **Concrete failure scenario:** During cold recovery with no installed controller, a candidate open
  is refused and clears the slot. A rapid lens/mode/override change advances the optics generation
  before the setup task crosses its post-wait boundary. The setup worker throws
  `IllegalArgumentException` instead of restoring a clean vacant baseline; on Android an uncaught
  worker exception is process-fatal, and in every environment this abandons the exact setup
  continuation.
- **Suggested fix:** Do not represent optional-owner identity as independent booleans. At minimum,
  derive outgoing ownership as `old != null && controller === old`; preferably pass the nullable
  identities and outgoing terminal/liveness into one typed cleanup reducer. Add a production-shaped
  matrix covering `old=null/controller=null`, candidate self-clear, outgoing disconnect/close,
  pause/release, supersession, and a genuinely newer controller.

### CRIT36-02 — active custom-control boundaries are deliberately held below the non-text contrast floor

- **Severity / confidence / status:** Medium / High / Confirmed numeric contrast gap; impact is
  strongest for low-vision users.
- **Exact regions:** token and acknowledged ratio at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/theme/Theme.kt:117-126`; settings-chip application at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/controls/ProControls.kt:203-219,340-362,369-400`;
  additional active uses at `ui/controls/ManualDials.kt:431-458`,
  `ui/CameraScreen.kt:2832-2891`, and `ui/review/MediaReview.kt:1715-1734`.
- **Problem:** `AffordanceEdge` is 18% white. Over the opaque `Pill` surface (`#1C1C1E`) its rendered
  edge is approximately `#454546`, only **1.78:1** against the adjacent surface. The code comment
  acknowledges roughly 1.8:1 but treats it as a design floor. These are authored, enabled controls,
  not inactive platform-owned controls: unselected FilterChip outlines, the dial close pill, lens
  rail circles, and review action buttons. The official WCAG 2.2 / WCAG2ICT 1.4.11 guidance requires
  3:1 for visual information needed to identify active UI components or their state
  ([W3C WCAG2ICT 2.2](https://www.w3.org/TR/wcag2ict-22/#non-text-contrast)).
- **Concrete failure scenario:** In the Shoot tab, a low-vision operator sees several white option
  labels on one dark panel but cannot reliably distinguish the 1 dp unselected control boundaries;
  the selected white fill is clear, while the other active choices can read like inert text. The
  same weak edge is the only circular boundary around the compact close/review affordances.
- **Suggested fix:** Give active component boundaries at least 3:1 against their actual adjacent
  surfaces (about 35% white clears 3:1 on `Pill`), or add another high-contrast shape/fill cue while
  retaining the quiet 1 dp weight. Keep disabled styling separately exempt. Add palette math plus
  rendered enabled/selected/disabled tests for every token consumer.

## Balanced assessment and final sweep

The repo remains unusually strong in capability-based routing, finite queues, exact native/media
ownership, EN/KO resource parity, modal focus exclusion, 48 dp target coverage, responsive phone/
tablet geometry, and truthful host-vs-device evidence. I rechecked the cycle-35 audio, EXIF,
capture-status, documentation, and dual-open changes specifically; only the nullable dual-open
state defect survives that pass. The contrast issue is longstanding but was not present in current
review/plan history as an unresolved finding. No additional current issue cleared the evidence bar.

## Totals

- New findings: 2
- Severity: 1 High, 1 Medium
- Confidence: 2 High
