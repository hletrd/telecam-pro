# Cycle 29 aggregate review

Date: 2026-08-24
Reviewed revision: `5fc6fbe1225428039551402678fa6f85eb92e25a`
Workspace: clean detached clone `/tmp/review-plan-fix-cycle29.cWiWLi/repo`

## Review coverage

All required specialist roles returned: code-reviewer, architect, perf-reviewer, tracer,
security-reviewer, critic, verifier, debugger, test-engineer, document-specialist, and designer.
The reviewers inventoried the complete 426-file tracked repository and traced the Camera2/GL,
capture/storage/deletion, ViewModel/UI, audio, documentation, build, release, and privacy surfaces.
The native Android designer pass used Compose/resource and host-test evidence; browser tooling was
not applicable to this non-web UI.

## Deduplicated findings

### AGG29-01 — partial-delete retry copy is not bound to the survivor's review ownership

- **Severity / confidence:** Medium / High
- **Agreement:** code-reviewer, architect, security-reviewer, critic, verifier, debugger,
  test-engineer, and document-specialist independently reported this defect.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:3375-3394`
  restores the survivor in one `_state.update`, caches `DeleteRetryDestination`, then calls
  `showStatus` in a separate state update. `recordCaptureOutput` at `:3401-3419` can advance review
  ownership from independent capture/save callbacks between those updates or while the six-second
  capture-specific status remains visible. The cycle-28 tests cover supersession before the pure
  resolver, not these later interleavings.
- **Failure:** capture A's partial-delete survivor selects the Capture retry copy; capture B then
  becomes the gallery owner, so the UI tells the operator to open the capture while that action
  opens B and A is reachable only in the system Gallery.
- **Fix:** make the retry destination immune to later ownership changes. A universal Gallery retry
  is safe; otherwise bind the status to the exact survivor identity and reclassify it on every newer
  review publication. Add deterministic regression coverage.

### AGG29-02 — RAW terminal completions can grow the Engine I/O queue without a bound

- **Severity / confidence:** Medium / High
- **Agreement:** perf-reviewer and tracer.
- **Evidence:** `CameraEngine.kt:119-126` creates an unbounded single-thread `ioExecutor`.
  RAW-only SINGLE publication correctly uses finite process capacity, but every terminal family
  calls `scheduleDeletedFamilyRetirement` (`:4395-4422`), which submits another closure to that
  unbounded Engine queue (`:4179-4209`). The finite retained-still lane is used only after executor
  rejection/shutdown, not while a live but wedged executor continues accepting work.
- **Failure:** one processed save blocks the Engine I/O head while independently completed RAW-only
  shots append one retirement closure per family indefinitely; Engine replacement can leave the old
  worker and queue resident.
- **Fix:** send retirement through finite process-owned capacity even while the Engine is live.
  Overflow must leave the durable family marker for launch recovery and never run provider work
  inline. Test active+queued cardinality with a blocked worker and repeated retirement submissions.

### AGG29-03 — committed contributor instructions require private files absent from clean clones

- **Severity / confidence:** Medium / High
- **Agreement:** test-engineer and document-specialist; the other specialists also recorded the
  missing private files as a review constraint.
- **Evidence:** `CLAUDE.md:3-5`, `:52-55`, and `:1115-1121` present `docs/BACKLOG.md`,
  `docs/UX_POLICY.md`, and a historical specification as required/current pointers, while
  `.gitignore:63-69` intentionally excludes them and `tools/check_docs.py` accepts their absence.
- **Failure:** a fresh-clone contributor cannot follow the mandatory authority order and can repeat
  deferred work or miss UI policy while the repository's authoritative gate remains green.
- **Fix:** explicitly label private documents optional/maintainer-only and name committed fallbacks,
  or track public-safe copies. Add a clean-clone documentation contract for the wording.

### AGG29-04 — authoritative audio documentation describes the removed per-buffer RMS gate

- **Severity / confidence:** Low / High
- **Agreement:** code-reviewer and architect.
- **Evidence:** `CLAUDE.md:918-943` still describes the old `audioGain != 1f || emitDue` RMS rule,
  while `VideoRecorder.kt:789-803,2072-2080` applies gain to every required buffer but constructs
  RMS only when a meter callback exists and the emission cadence is due. `AudioGainTest` pins the
  new gain-only result.
- **Failure:** maintainers following the authoritative instructions can reintroduce continuous RMS
  CPU/allocation work at non-unity gain.
- **Fix:** document the current split between always-required gain and cadence-gated level
  measurement.

## Agent failures

None. Every spawned specialist returned and wrote its provenance review.

## Final sweep result

No additional actionable security or UI/UX finding survived evidence checking. No device-only
behavior was inferred from host tests.
