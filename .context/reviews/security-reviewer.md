# Security review — cycle 37

Date: 2026-08-24
Reviewed revision: `4e4a3b0515d8926482cf6f5d7d2798d019d4c082`
Workspace: isolated detached worktree `/private/tmp/find-x9-cycle37.AoQoKx`

## Scope and complete inventory

I reviewed the detached `origin/main` tree only. I read `CLAUDE.md`,
`docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` first, then used the retained reviews and
completed cycle-36 plan only as a resolved-history ledger. In particular, the DUMP protection on
debug activities, ownerless-media consent path, dual-open restoration change, and device-harness
optimized-Python rejection were revalidated on current HEAD rather than re-filed.

The current repository contains 489 tracked paths: 101 production Kotlin files, three debug Kotlin
files, four instrumented-test files, 220 JVM/Robolectric/Compose test files, 32 Python files, 69
Markdown files, 16 binary assets, and 44 remaining resource/configuration/script/license/metadata
files. Every tracked path participated in the type, Git-mode/no-symlink, SHA-256, and repository-wide
source/policy searches. All executable text was included in the ingress, mutation, secret, process,
and failure-handling census; current authority docs and all historical plan/review headings were
checked so completed findings were not presented as live defects.

The security pass traced:

- release/debug merged component exposure, permissions, launcher intent ingress, debug command
  mailbox, obscured-touch and hardware-input boundaries, and external navigation;
- runtime camera/microphone/media grants, app-owned versus owner-null MediaStore restore grammar,
  exact-file system consent, family deletion/tombstone authority, and late-output handling;
- bounded still/video review parsing, provider IPC ownership, preferences/backup exclusions, and
  process-lifetime queues;
- tracked secrets and signing inputs, Gradle dependency verification, immutable debug/release
  source and output ownership, subprocess construction, device evidence, and attestation;
- network, WebView, dynamic loading, unsafe deserialization, arbitrary-file ingress, and native-code
  surfaces. The shipping manifest still removes `INTERNET` and `ACCESS_NETWORK_STATE`; no WebView,
  dynamic loader, plaintext credential, private key, or untrusted shell construction was found.

## Finding

### SEC37-01 — the documentation evidence gate accepts optimized Python while relying on `assert`

- **Severity / confidence:** Low / High.
- **Classification:** Current evidence-integrity/fail-open tooling defect; not a shipping-app
  authorization bypass. This is distinct from resolved AGG36-02, which added the optimized-runtime
  guard only to `device-tests/run.py`.
- **Exact evidence:** `tools/check_docs.py:320,323,362,364,433` uses Python assertions for required
  parsed authorities and an executable exact-millisecond verdict. The script has no
  `sys.flags.optimize` rejection and ends solely from its accumulated `FAILURES` list at
  `tools/check_docs.py:1505-1506`; stripped assertions never enter that list. The authoritative host
  gate inherits the caller environment and launches this script with `sys.executable` at
  `tools/verify_host.py:62-67,81`. Direct reproduction on current HEAD,
  `python3 -O tools/check_docs.py`, exited 0 and reported `112 checks, 0 failed`; there are eight
  assertion statements in the operational script.
- **Failure scenario:** a maintainer or CI environment sets `PYTHONOPTIMIZE=1` (which propagates
  through `verify_host.py`) or directly invokes the documented checker with `python3 -O`.
  `check_docs.py:364` is then removed before execution, so a non-integral-millisecond ZSL authority
  is silently floored at line 365 and can agree with all three integer prose consumers. More
  generally, newly added assertion-owned policy checks would silently disappear under an execution
  mode the current tool accepts. The Cycle-36 device guard does not protect this process.
- **Suggested fix:** reject `sys.flags.optimize != 0` at the beginning of `check_docs.py` with a
  stable non-green exit, and add subprocess coverage for both `python -O` and environment-only
  `PYTHONOPTIMIZE=1`. Convert the existing operational assertions to explicit exceptions or `check`
  verdicts as defense in depth. If the repository intends `verify_host.py` to reject optimization
  globally, enforce the same guard at its outer entry as well.

## Verification evidence

- Focused current-HEAD tests for debug-component security, ownerless delete lifecycle/operation,
  dual-open cleanup/restorability, and EXIF orientation passed: six selected suites, zero failures.
- `python3 -O tools/check_docs.py` reproduced SEC37-01 with exit 0. Normal current input is green, so
  the finding is the accepted stripped-verdict execution mode, not a claim that today's prose is
  already inconsistent.
- Current Git modes contain only regular tracked files; no tracked symlink was found. The worktree
  was clean before these two requested report replacements. No source, plan, git history, device,
  deployment, network, or external-service state was changed.

## Final missed-issue sweep and count

The final sweep rechecked every exported component and permission, intent/URI boundary, overlay and
hardware input, owner-null provenance reducer, provider mutation, parser/allocation bound, private
durable state, secret/log/network surface, build/signing input, immutable artifact owner, subprocess
call, and current cycle-36 delta. No Critical, High, or Medium security issue survived validation.

**New finding count: 1 — one Low (High confidence).**
