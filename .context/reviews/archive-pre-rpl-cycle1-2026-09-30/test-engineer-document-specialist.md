# Cycle 33 test-engineer + document-specialist review

Date: 2026-08-24

Reviewed revision: `984424cb36ebe409e67bb2e69c8605ea6f41c4bb`

Workspace: clean worktree `/tmp/find-x9-cycle33-latest.Vc7rke`

Mode: host-only review; no deployment, external communication, or physical-device claim

## Inventory and method

I read `CLAUDE.md` completely before reviewing the repository, then read the committed current
authorities and release/operator material: `README.md`, all 1,342 lines of
`docs/ARCHITECTURE.md`, all of `docs/FIELD_CHECKS.md`, `PRIVACY.md`, the published privacy HTML,
Play submission/data-safety material, every completed plan through cycle 32, and
`device-tests/README.md`. The private maintainer files named by `CLAUDE.md` are absent in this clean
worktree, which is an explicitly supported repository state.

The inventory contains 447 tracked paths: 98 production Kotlin files, 205 host Kotlin test files,
four Android instrumented test files, 30 Python files spanning the device harness/build/release/
coverage tooling and self-tests, 65 tracked documentation/asset paths, Gradle and manifest inputs,
and paired EN/KO resources. I inspected the full test/build/tool inventories, mapped the executable
device-case registry to its documented matrix, checked resource parity, reviewed clone/export and
release-checker contracts, and swept current and historical plans to avoid refiling completed work.

`python3 tools/check_docs.py` passed all 96 public checks with 21 private-document skips. A first
clean-worktree invocation of `python3 tools/verify_host.py` failed before compilation because the
checkout had neither `local.properties` nor an Android SDK environment variable, even though the
SDK existed at the conventional macOS path. Re-running with `ANDROID_HOME`/`ANDROID_SDK_ROOT`
explicitly set reached Gradle compilation/tests. Its coverage report was then concurrently replaced
by another specialist's targeted test run in the shared cycle worktree, so that resulting residual
drift is not treated as repository evidence. No source or tracked documentation was modified.

## Findings

### TD33-01 — the committed clean-clone field authority still requires the absent private backlog

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed clone-safety and current-authority defect; the documentation gate
  produces a false green.
- **Exact regions:** clean-clone contract `CLAUDE.md:3-9`; unqualified private references in
  `docs/FIELD_CHECKS.md:141-146,234-238`, `docs/ARCHITECTURE.md:1337-1342`, and
  `device-tests/README.md:206-213`; checker behavior at `tools/check_docs.py:593-606`.
- **Problem:** `CLAUDE.md` says the absent private backlog must not block work and that the committed
  architecture plus field checks are self-contained fallbacks. The field authority nevertheless
  says the genuinely-wide design is in `docs/BACKLOG.md` and, more importantly, instructs an
  operator to record every new field outcome under a matching backlog entry. Architecture's final
  See Also section likewise presents the absent backlog as the release/deferred-work authority, and
  the device-harness non-coverage section says visual judgments are tracked there without an
  optional qualifier or committed fallback. `check_docs.py` recognizes the missing path as private
  and merely emits a skip; it verifies neither the qualifier nor a usable fallback outside one
  narrowly sliced Architecture overview paragraph.
- **Concrete failure scenario:** A contributor or device operator using the explicitly supported
  clean clone completes E1/E2, follows the committed instruction to record the evidence, and finds
  neither the file nor the promised matching entry. They must invent a location, lose the evidence,
  or block on private maintainer context. Meanwhile the authoritative documentation check remains
  green and labels `FIELD_CHECKS.md` as referencing only files that exist.
- **Suggested fix:** Give every committed reference to private context an explicit `optional when
  present` qualifier and a concrete committed fallback. In particular, make
  `docs/FIELD_CHECKS.md` itself own a compact results ledger (or name another tracked results file)
  for clean-clone runs. Extend `check_docs.py` to scan every committed authority section—not only
  `architecture_overview`—and fail any absent-private reference that lacks both the qualifier and
  fallback. Add an exported-tree negative test covering `FIELD_CHECKS.md`'s recording instruction.

### TD33-02 — the field-check dashboard omits two validation obligations that its own body leaves open

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed evidence-state/documentation mismatch; checker coverage gap.
- **Exact regions:** dashboard `docs/FIELD_CHECKS.md:9-12`; front tap-AF limitation
  `docs/FIELD_CHECKS.md:49-57`; newly open owner-null consent check
  `docs/FIELD_CHECKS.md:212-230`; cycle-32 plan that introduced and explicitly leaves that device
  validation open, `docs/plans/2026-08-24-rpf-cycle32.md:39-44,121-123`.
- **Problem:** The top-level status says exactly three checks remain—A3, D1, and E1—and its status
  row ends at E1. The same current document now contains `E2 ... OPEN 2026-08-24`, whose real
  MediaProvider/system-consent semantics cannot be closed by host tests. A1 also says its PASS proves
  only the horizontal half and that the front route's rotation term remains uncalibrated as a
  separate finding, but there is no separately numbered open check or dashboard entry for it.
  `check_docs.py` verifies selected field-check wording but never reconciles status tokens/open
  headings/declared residuals.
- **Concrete failure scenario:** A release reviewer reads only the dashboard, completes A3/D1/E1,
  and concludes all physical checks are closed. API-33/API-36 owner-null deletion can still launch
  the wrong consent path, delete the wrong scope, or mishandle cancellation without any claimed
  device evidence; the front tap rotation residual can likewise disappear from the work queue.
- **Suggested fix:** Add E2 to the status row/count and turn the front tap rotation residual into an
  explicit numbered check or explicitly state why it is no longer required. Machine-parse the
  status row and `OPEN`/half-done headings in `check_docs.py`, rejecting omissions, duplicate IDs,
  and a prose count that disagrees with the body. Add a negative fixture for adding an open field
  check without updating the dashboard.

### TD33-03 — the OIS field authority calls an effect “confirmed working” while saying it was never verified

- **Severity / confidence:** Medium / High
- **Classification:** Confirmed device-evidence overclaim.
- **Exact regions:** dashboard pass marker `docs/FIELD_CHECKS.md:9-12`; C3 heading and contract
  `docs/FIELD_CHECKS.md:163-173`; the more limited as-built claim in `CLAUDE.md:766-774`.
- **Problem:** C3 is titled `TC OIS ... CONFIRMED WORKING`, and the dashboard marks it passed, but
  its first sentence says the repository never verified whether session type `0x80b4` actually
  engages a different 300 mm OIS profile. The procedure then says an indistinguishable A/B is a
  legitimate result that merely closes the item. That can establish “no observable difference,”
  not “confirmed working.” `CLAUDE.md` remains more accurate: session acceptance and ordinary OIS
  metadata are verified, while a distinct 300 mm profile needs physical shake A/B evidence.
- **Concrete failure scenario:** Release copy, support guidance, or a later engineer cites the green
  C3 heading as proof that the teleconverter-specific stabilization profile is active, even though
  the recorded contract admits that exact distinction was never observed. A regression that leaves
  only ordinary stabilization would still satisfy the documented “indistinguishable” closure.
- **Suggested fix:** Record the actual operator observation. If there was no visible difference,
  label C3 `CLOSED — no observable profile difference` and preserve only the verified ordinary OIS/
  session-acceptance facts; reserve `CONFIRMED WORKING` for a reproducible visible or measured A/B.
  Add a documentation check that rejects a PASS/confirmed heading whose current body contains
  `never verified`, `unverified`, or an equivalent unresolved qualification unless the heading is
  explicitly historical/superseded.

### TD33-04 — clean-clone build and evidence commands omit the Android SDK authority they require

- **Severity / confidence:** Low / High
- **Classification:** Reproduced clean-clone setup failure and missing tool contract.
- **Exact regions:** ignored SDK pointer `.gitignore:1-7`; public build instructions
  `README.md:107-118`; field-evidence command `docs/FIELD_CHECKS.md:14-24`; harness command
  `device-tests/README.md:13-26`; Java-only environment setup `tools/verify_host.py:18-30,52-68`;
  optional local input in `tools/build_immutable_debug.py:24-37`.
- **Problem:** A clean clone cannot contain `local.properties`, and none of the three active command
  blocks tells the user to export `ANDROID_HOME`/`ANDROID_SDK_ROOT` or create that file. The
  consolidated host runner helpfully discovers/configures JDK 21 but does not perform or diagnose
  Android SDK discovery before invoking Gradle. The immutable debug wrapper copies
  `local.properties` only when one already exists. Consequently the exact documented commands are
  not self-contained even on a host with Platform 37/Build Tools installed.
- **Concrete failure scenario:** In this clean worktree, with the SDK installed at
  `/Users/hletrd/Library/Android/sdk`, `python3 tools/verify_host.py` failed at
  `:app:compileDebugJavaWithJavac` with `SDK location not found` until both SDK environment variables
  were supplied manually. The field and device-harness snippets fail at the same wrapper build
  before producing the evidence APK, while users are told only that the SDK is required.
- **Suggested fix:** Add one canonical clean-clone SDK setup block to README and reference/reuse it
  from the field/harness commands. Prefer a `verify_host.py` preflight that accepts existing Gradle
  authority, then checks environment variables and conventional SDK locations and emits a precise
  remediation before starting Gradle. Add fixture tests for environment, `local.properties`,
  conventional-path discovery (if supported), and the no-SDK error. Keep local path files ignored.

### TD33-05 — the device-harness README disagrees with both Git and the executable case registry

- **Severity / confidence:** Low / High
- **Classification:** Confirmed harness-documentation drift; missing inventory regression test.
- **Exact regions:** stale ignore claim `device-tests/README.md:86-101`; actual ignore policy
  `.gitignore:50-73`; executable localized smoke case `device-tests/cases.py:2016-2035`;
  documented case table `device-tests/README.md:131-157`; registry owner
  `device-tests/dtest/framework.py:13-58`.
- **Problem:** The README says the entire `device-tests/` directory “remains ignored because
  historical evidence is several gigabytes,” but the harness source and README are tracked; only
  reports and caches are ignored. The same README's supposedly exhaustive tier/case table omits the
  registered `localized_camera_semantics` smoke case between `launch_preview_live` and
  `session_configured_3a`. The 182 harness self-tests and documentation checker validate many
  selector/attestation details but do not compare the live registry with this table.
- **Concrete failure scenario:** A contributor assumes harness source changes are local-only and
  fails to commit a required case fix, or uses the table to choose/audit smoke coverage and misses
  the EN/KO semantic case that will run and can fail. Future case additions/removals can continue to
  drift silently while all host gates remain green.
- **Suggested fix:** Change line 89 to say generated reports/evidence/caches remain ignored while
  executable harness source is versioned, and add `localized_camera_semantics` to the table. Add a
  self-test that imports the registry in a snapshot-safe way, parses the README table, and requires
  exact one-to-one `(tier, case)` order/membership; separately assert every registered case carries
  a nonempty assertion summary.

## Final missed-file sweep

I re-inventoried every tracked test, tool, documentation, plan, string/resource, manifest, Gradle,
and release/checker path; checked the executable device registry against docs; searched for stale
private-authority, ignored/versioned, evidence-state, permission, release-output, and open/manual-
validation claims; and revisited cycle-28 through cycle-32 findings so resolved issues were not
reported again. No additional current finding met the source-evidence threshold. In particular,
EN/KO resources have exact parity apart from the 18 declared `translatable="false"` exceptions,
privacy/data-safety permission facts match the current manifest, the screenshot manifest and
not-ready submission state agree, and instrumented sources are compiled (not falsely claimed as
executed) by the host gate.

**Finding count: 5 total — 3 Medium, 2 Low.**
