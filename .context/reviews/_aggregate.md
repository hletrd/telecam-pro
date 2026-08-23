# Review-plan-fix cycle 13 — aggregate review

Date: 2026-08-23
Reviewed HEAD: `f467a15`
Mode: host-only; deployment and device interaction disabled

## Review provenance

- `code-reviewer-architect-cycle13.md`
- `perf-reviewer-tracer-cycle13.md`
- `security-reviewer-debugger-cycle13.md`
- `critic-verifier-cycle13.md`
- `test-engineer-document-specialist-cycle13.md`
- `designer-qa-adversary-cycle13.md`

The six specialist passes produced 12 raw findings. This aggregate deduplicates them into nine
current-HEAD findings, preserving the highest reported severity and confidence. The designer/QA
pass ran the static debug assembly and JVM-test gate successfully; all device gates were blocked by
the explicit no-deploy directive and are not treated as failures.

## Merged findings

### AGG13-01 — pending recorder setup can permanently lose preview recovery

- **Severity / confidence:** High / High.
- **Agreement:** critic/verifier and security/debugger.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraEngine.kt:1225-1239,
  5521-5532,5728-5851`; `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:
  1122-1187,1231-1264`.
- **Failure:** `resume()` or a replacement Engine's sole preview-surface callback can be refused
  during the finite native REC-setup token. Token retirement has no observer/replay, so the app can
  remain Not Ready or black until another unrelated lifecycle/surface edge.
- **Required fix:** retain desired surface/start truth before refusal and publish an identity-gated,
  exactly-once terminal replay for a still-live foreground Engine. Test same-Engine pause/resume and
  replacement-Engine surface delivery through clean retirement, cancellation, and quarantine.

### AGG13-02 — immutable debug compilation accepts snapshot A -> B -> A mutation

- **Severity / confidence:** High / High.
- **Agreement:** test/document specialist and security/debugger.
- **Evidence:** `tools/build_immutable_debug.py:144-221`; `app/build.gradle.kts:246-373,427-464,
  612-617`; `tools/tests/test_immutable_debug.py:54-133`.
- **Failure:** the private snapshot remains writable. A source can change during compiler reads and
  be restored before the post-build digest, exporting B-derived bytecode beneath A provenance.
- **Required fix:** give compilation a sealed source owner or an equivalent independently enforced
  mutation barrier, and add a deterministic mutation of the actual snapshot during compilation.

### AGG13-03 — imported device runner bypasses outer snapshot authority

- **Severity / confidence:** High / High.
- **Evidence:** `device-tests/run.py:440-554,1305-1319,1383-1390,1444-1583`;
  `device-tests/tests/test_attestation.py:80-118,491-517`.
- **Failure:** importing `run.py` and calling `main()` executes live modules without inherited child
  authority but can still emit an attestation claiming private snapshot execution.
- **Required fix:** require unforgeable inherited authority at every full-run entry, separate outer
  orchestration from child execution, and prove import-and-call refusal before APK/device work.

### AGG13-04 — checkout source matching reopens mutable paths after validation

- **Severity / confidence:** High / High.
- **Evidence:** `device-tests/dtest/contracts.py:196-280`;
  `device-tests/tests/test_contracts.py:136-195,219-242`; `device-tests/run.py:1449-1465`.
- **Failure:** `rglob`/`is_symlink`/`stat`/`read_bytes` pathname operations do not pin leaf or parent
  identities. File, parent, disappearance/addition, and A -> B -> A races can bind an evidence APK
  to bytes not owned by the checkout at the decision boundary.
- **Required fix:** use a bounded descriptor-rooted no-follow source snapshot with a frozen member
  set and post-read identity checks, then add deterministic adversarial swaps.

### AGG13-05 — failed final evidence recheck leaves a green attestation pair

- **Severity / confidence:** Medium / High.
- **Evidence:** `device-tests/run.py:1201-1233,1352-1441`;
  `device-tests/tests/test_attestation.py:906-936,1000-1059`.
- **Failure:** JSON and its valid sidecar are published before the final report-set recheck. A late
  failure exits nonzero but leaves an independently consumable pair claiming success.
- **Required fix:** complete every failure-producing check before publishing a pass pair, or ensure
  a failed terminal marker makes a surviving pair unambiguously invalid; test the post-sidecar race.

### AGG13-06 — a blocked standby AudioRecord read can retain the process microphone forever

- **Severity / confidence:** High / High for the code-level liveness gap; trigger requires device
  fault injection/manual validation.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/camera/StandbyAudioController.kt:
  255-320,354-361,410-441,493-533`; `app/src/main/kotlin/me/hletrd/telecampro/camera/
  CameraEngine.kt:4755-4762`; `app/src/main/kotlin/me/hletrd/telecampro/video/VideoRecorder.kt:
  1075-1099`.
- **Failure:** disable/handoff changes intent but owns no live input to unblock a wedged blocking
  `read`. The release latch and process token can remain forever, producing repeated mic-busy REC
  refusal and foreign-owner lockout after Engine replacement.
- **Required fix:** add a release-visible live-input terminal owner that requests a safe unblock off
  main, classifies the exact generation, and prevents an old worker from releasing a newer owner;
  test blocked read, REC/pause/release, replacement, and late completion.

### AGG13-07 — lifecycle info sampling can accumulate an unbounded shared-I/O backlog

- **Severity / confidence:** Medium / High.
- **Evidence:** `app/src/main/kotlin/me/hletrd/telecampro/ui/CameraViewModel.kt:405-416,622-628,
  3338-3378,3405-3416,3519-3556`.
- **Failure:** a task is submitted every ten seconds to an unbounded single-thread executor without
  single-flight. A blocked provider/filesystem task and repeated starts accumulate stale telemetry
  ahead of user-facing restore/delete/codec work.
- **Required fix:** coalesce info refresh to one in-flight/pending request, generation-gate results,
  and prove bounded cardinality through blocked-worker ticks and stop/start cycles.

### AGG13-08 — Loupe Overview authorities describe a superseded gate

- **Severity / confidence:** Low / High.
- **Agreement:** code/architect and designer/QA.
- **Evidence:** `CLAUDE.md:233-248`; `docs/ARCHITECTURE.md:693-719`;
  `app/src/main/kotlin/me/hletrd/telecampro/camera/CameraState.kt:390-400,506-584`;
  `app/src/test/kotlin/me/hletrd/telecampro/camera/TeleFinderVisibilityTest.kt:68-99`.
- **Failure:** authorities say TELE + Photo + 4:3 and claim the zoom floor was removed, while the
  current executable/tested contract is enabled + punch-in + (TELE or unified zoom >= 3x), with
  Photo requiring 4:3 and Video ignoring still aspect.
- **Required fix:** align CLAUDE, architecture, source KDoc/test wording, and docs checks with the
  executable matrix without changing runtime behavior.

### AGG13-09 — the device-harness runbook contradicts the immutable-debug contract

- **Severity / confidence:** Low / High.
- **Evidence:** `device-tests/README.md:16-34,72-79,111-113`;
  `tools/build_immutable_debug.py:156-243`; `docs/ARCHITECTURE.md:1179-1184`.
- **Failure:** the runbook says the wrapper requires clean committed source and later points evidence
  runs at the mutable default APK, although the implementation snapshots clean or dirty scoped inputs
  and requires the printed evidence APK path.
- **Required fix:** state the clean-or-dirty immutable snapshot contract consistently and add docs
  guards against the two obsolete claims.

## Deferred findings

None. All nine findings are scheduled for implementation in the cycle-13 plan. Existing owner-cleared
deferrals in `docs/BACKLOG.md` and earlier completed plans are unchanged and were not re-filed.

## Agent failures

None.
