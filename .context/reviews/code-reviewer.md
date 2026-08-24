# Code-reviewer report — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518` (`origin/main`)
Workspace: clean detached worktree `/tmp/find-x9-ultra-rpf35.0PSzsb`

## Scope and inventory

I read all 1,143 lines of `CLAUDE.md`, all 1,351 lines of the required
`docs/ARCHITECTURE.md`, and all 262 lines of `docs/FIELD_CHECKS.md` before reviewing code. The
tracked inventory is 471 paths: 101 production Kotlin files (53,618 lines), 216 JVM/Robolectric/
Compose test files (42,483 lines), four instrumented-test files, three debug Kotlin files, 32 Python
files, two shell files, 55 Markdown files, and 23 Gradle/TOML/properties/XML configuration files.

I inventoried every review-relevant file and examined the complete production surface by package,
its test counterparts, build/release/device tools, current authorities, and completed cycle plans.
Cross-file tracing covered Camera2 selection/session/teardown, GL/EGL ownership, zoom/focus/exposure,
processed/RAW capture, durable MediaStore publication/deletion/recovery, recorder/audio ownership,
ViewModel/Compose lifecycle, settings/permissions, debug components, and build evidence. The final
sweep rechecked recent cycle-34 changes and excluded already-fixed historical findings.

## Findings

### CODE35-01 — supersession can orphan the outgoing CameraDevice after a candidate callback clears ownership

- **Severity:** High
- **Confidence:** High
- **Status:** Likely race; the ownership break is demonstrated by a legal source interleaving, but
  needs a deterministic production-boundary test or device stress run for runtime reproduction.
- **Exact regions:**
  - Candidate publication and outgoing owner capture:
    `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:3574-3624`.
  - Candidate error callback clears `controller` on native-acquisition refusal:
    `CameraEngine.kt:3667-3684`, especially lines 3672-3674.
  - The new polling wait exposes prompt supersession:
    `CameraEngine.kt:3702-3718`.
  - Supersession restores `old` only when `controller === next`:
    `CameraEngine.kt:3746-3757`.
  - Existing tests cover the pure polling helper only:
    `app/src/test/kotlin/me/hletrd/telecampro/camera/DualOpenWaitTest.kt:1-72`.
- **Problem:** The dual-open task stores `old`, installs `next` into the single controller field, and
  waits. A newer optics transaction can invalidate the task while `next`'s camera-thread callback is
  simultaneously handling `NativeAcquisitionRefusedException`. That callback changes the field from
  `next` to `null` and closes `next`. The setup task then observes supersession, closes `next` again,
  but restores `old` only inside `if (controller === next)`. Because the callback already wrote
  `null`, it returns without restoring or closing `old`. The outgoing camera is still streaming but
  has become unreachable from the Engine.
- **Concrete failure scenario:** During a Photo/Video/front/back switch, native acquisition is
  revoked while the operator immediately requests another optics route. The next queued generation
  sees `controller == null` and attempts another open while the orphaned outgoing CameraDevice still
  owns the HAL. The open can fail with `CAMERA_IN_USE`; the leaked device cannot be deliberately
  closed until process death, contrary to the repository's strict exact-device ownership rule.
- **Suggested fix:** Give the complete dual-open attempt one identity-owned terminal containing both
  `old` and `next`. Candidate callbacks and the post-wait supersession branch must CAS through that
  terminal rather than independently mutating the shared controller field. A superseded attempt must
  finish in exactly one of two proved states: `old` restored as current, or `old` strictly closed.
  Add an interleaving test where the candidate callback clears itself between the ownership poll and
  supersession cleanup, asserting no owner becomes unreachable and the following generation cannot
  dual-open over it.

### CODE35-02 — capture-admission refusal reports an unrelated delete failure

- **Severity:** Medium
- **Confidence:** High
- **Status:** Confirmed current behavior.
- **Exact regions:** `CameraEngine.capturePhoto` at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:4006-4012`; the correct still-status
  identity is used at `CameraEngine.kt:4021-4024` and maps independently at
  `app/src/main/kotlin/me/hletrd/telecampro/ui/LocalizedStatus.kt:37-40,71-76`.
- **Problem:** The defense-in-depth admission branch returns `false` from a photo request but emits
  `COULD_NOT_DELETE_FILE`, whose EN/KO copy is a deletion error. No deletion was requested. The same
  method already owns `STILL_CAPTURE_UNAVAILABLE` for an unusable accepted output mask.
- **Concrete failure scenario:** The retained/deferred-output safety owners temporarily exhaust
  capture admission while the UI shutter is still reachable through a hardware edge or a publication
  race. Pressing the shutter produces “Could not delete file,” making the operator believe a prior
  gallery action failed even though the rejected action was capture. This also sends support/debugging
  toward the wrong subsystem.
- **Suggested fix:** Emit `STILL_CAPTURE_UNAVAILABLE`, or add a dedicated truthful status such as
  “Finishing previous media cleanup” if the product wants to expose the admission reason. Add an
  Engine/ViewModel integration test that forces each half of `stillOutputAdmissionAvailable()` false
  and asserts both the rejected return and the localized status identity.

### CODE35-03 — executable pseudo-ZSL freshness is 400 ms, but source and governing authorities still claim 250 ms

- **Severity:** Low
- **Confidence:** High
- **Status:** Confirmed documentation/source-comment defect.
- **Exact regions:** executable constant and rationale at
  `app/src/main/kotlin/me/hletrd/telecampro/camera/ZslAdmission.kt:25-42`; stale active source guidance
  at `CameraEngine.kt:4066-4074`; stale governing claims at `CLAUDE.md:210-224` and
  `docs/ARCHITECTURE.md:68`.
- **Problem:** `ZSL_MAX_FRAME_AGE_NS` was deliberately raised and tested at 400 ms, but both mandatory
  authorities and a hot-path comment still state 250 ms. This is not historical prose: the text
  describes the current admission contract future changes are instructed to preserve.
- **Concrete failure scenario:** A maintainer investigating a 300–400 ms buffered serve follows
  `CLAUDE.md` and treats correct current behavior as a regression, then lowers the constant and
  reintroduces the measured dim-scene refusal that the 400 ms change fixed. Conversely, device
  evidence recorded against the written 250 ms promise would be interpreted incorrectly.
- **Suggested fix:** Update all three active descriptions to 400 ms and add a documentation contract
  that parses `ZSL_MAX_FRAME_AGE_NS` (or a generated/source-of-truth value) instead of duplicating a
  numeric literal.

## Final missed-issue sweep

The final pass rechecked every production package and its tests for silent catches, unsafe assertions,
unbounded executors, callback-under-lock behavior, route/model hardcodes, wrong-clock comparisons,
mutable-state publication, resource terminals, and current documentation contradictions. Recent
audio peak, settings migration, debug-manifest, coverage, and dual-open polling changes were checked
against their focused tests. No additional current correctness finding met the evidence threshold.

## Totals

- New findings: 3
- Severity: 1 High, 1 Medium, 1 Low
