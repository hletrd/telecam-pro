# Cycle 54 — code reviewer / architect / critic

Reviewed current HEAD `bf40ae2c56c154072691815f83b7090a31f0c424` only. Historical review notes were not used as finding authority.

## Inventory and coverage

The initial inventory contained 404 review-relevant text/code/configuration files and 135,533 lines. Every inventoried path was included in the repository-wide searches and verification sweep. The inventory was partitioned as follows:

| Area | Files | Lines | Coverage performed |
|---|---:|---:|---|
| `app/src/main/kotlin/` | 103 | 57,409 | All production modules inventoried; package/module boundaries, call sites, ownership fields, callbacks, exceptions, executor/handler use, lifecycle edges, MediaStore state, and native-resource paths traced across files. |
| `app/src/main/java/` | 1 | 23 | Durable SharedPreferences commit bridge reviewed with its Kotlin callers. |
| `app/src/test/` | 245 | 49,425 | Full suite executed; tests cross-checked against the production seams and the two uncovered failure paths below. |
| `app/src/androidTest/` | 4 | 588 | All probes/smoke sources inventoried and compiled by the host gate; device execution was not available and is not claimed. |
| `app/src/debug/kotlin/` | 3 | 608 | Debug activities/mailbox/preview paths inventoried and compiled. |
| `device-tests/` | 14 | 14,787 | Harness, contracts, ADB/media helpers, cases, and self-tests included; all 195 harness self-tests passed. |
| `tools/` | 25 | 10,367 | Build, immutable-output, attestation, coverage, docs, SDK, field, and fleet tooling included; tool and coverage suites passed. |
| Build/package/privacy/docs | 9 primary config/manifest files plus committed docs/resources | — | `CLAUDE.md`, `docs/ARCHITECTURE.md`, and `docs/FIELD_CHECKS.md` read completely; README, manifests, Gradle/version/proguard/backup/privacy/Play authorities and resource/localization contracts checked. Binary store assets were covered by the repository's digest/PNG validators rather than treated as source text. Historical `docs/plans/` and `.context/reviews/` were inventoried but excluded as current implementation authority. |

Production source inventory covered the app root plus every file under `camera/`, `capture/`, `focus/`, `gl/`, `stab/`, `storage/`, `ui/`, `ui/controls/`, `ui/overlays/`, `ui/review/`, `ui/theme/`, and `video/`. The largest cross-file traces were `CameraViewModel` -> `CameraEngine` -> `CameraController`/`GlPipeline`/`VideoRecorder`, still and video allocation -> durable journal -> publication/recovery/delete, review tracker -> ownerless/family deletion, callback retirement, route/topology convergence, and standby-microphone -> recorder handoff/quarantine.

Verification: `python3 tools/verify_host.py` passed. This included debug and androidTest assembly, all JVM/Robolectric/Compose tests, lint, shader validation, exact coverage, tool tests, device-harness self-tests, documentation checks, Python compilation, and `git diff --check`. Reported coverage was 19,055/29,362 overall lines (64.90%), Partition A 8,990/9,006 (99.82%), and device-bound Partition B 10,065/20,003 (50.32%). No physical-device behavior is claimed.

## Findings

### 1. High — a constructed standby `AudioRecord` can escape exact native ownership when cleanup throws

- **Location:** `app/src/main/kotlin/me/hletrd/telecampro/camera/StandbyAudioController.kt:411-422`, `:618-637`, and `:264-277`.
- **Confidence:** High.
- **Status:** Confirmed code behavior; the OEM/runtime occurrence of `AudioRecord.release()` throwing requires fault injection or device validation.
- **Failure scenario:** `createAndroidStandbyAudioInput` successfully constructs an `AudioRecord`, finds it uninitialized, calls `runCatching { recorder.release() }`, discards any failure, and returns a resource-free `Failure`. The process publication gate then receives a `Failure`, so `publicationOwner` returns `null`; the concrete recorder is neither registered nor strongly retained. The ordinary terminal path has the same classification hole: `finishAndRelease` wraps `release(value)` in `runCatching`, so the process gate executes its success publication and clears `input` even when native release threw. A vendor audio failure can therefore leave an uncertain recorder alive while releasing the process/standby ownership that admits the next standby meter or REC `AudioRecord`. That violates the architecture's exactly-one-mic and quarantine-before-abandonment contract and can produce a leaked native graph, a second concurrent mic owner, or a misleading video-only degradation on the next take.
- **Suggested fix:** Publish ownership for every successfully constructed `AudioRecord` before validation or cleanup, including the uninitialized branch. Do not convert stop/release exceptions into successful native returns. Make the stop/release operation return typed success/failure (or throw through the gate), and on any non-strict terminal result quarantine and strongly retain the exact `QuarantinedStandbyInput` before logical waiters/process admission are released. Add deterministic tests for (a) uninitialized input whose release throws and (b) normal terminal release throwing; both must close future native admission and must not clear/forget the exact owner.

### 2. Medium — failed review-spool deletion releases its byte budget and becomes unbounded within the process

- **Location:** `app/src/main/kotlin/me/hletrd/telecampro/ui/review/LatestHeavyWorkLane.kt:92-116`, especially `ReviewSourceSpool.close()` at `:112-115`; the same unconditional delete-then-release pattern exists on partial-spool failure at `:269-275`. Startup cleanup is one-shot and bounded at `:159-200`.
- **Confidence:** High.
- **Status:** Confirmed code behavior; reproducing the filesystem failure needs an injected delete seam or device/filesystem fault.
- **Failure scenario:** `ReviewSourceSpool.close()` ignores both a `false` return and an exception from `file.delete()`, then always closes the lease and subtracts the file's bytes from `processReviewSourceBudget`. The orphan still occupies private cache storage but no longer consumes the process budget. The directory owner prepares/cleans only once per process, so repeated delete failures during a long-running process can create arbitrarily many unaccounted 64–512 MiB spool files even though the advertised process ceiling is 1 GiB. Eventually review decoding or unrelated capture/storage work can fail from cache/filesystem exhaustion. Current tests prove only successful deletion and therefore reinforce a ceiling that does not hold on the failure branch.
- **Suggested fix:** Make deletion a typed terminal. Release the byte lease only after authoritative absence; otherwise retain accounting and register the exact orphan with a finite process cleanup owner (or close new spool admission fail-closed until a bounded rescan deletes it). Apply the same rule to partial-file cleanup. Inject file create/delete operations and test `delete == false`, thrown delete, retry success, and a sequence of failed closes to prove disk occupancy can never exceed accounted capacity.

## Final missed-issue sweep

The final pass rechecked every production package against the architecture module map, all lifecycle/native acquisition and teardown call sites, all `runCatching` cleanup boundaries, process-wide finite executors, callback identity gates, MediaStore REGISTERED/COMPLETE/DISCARD transitions, route/mode/zoom packet ownership, settings restore, review/delete ownership, manifests/privacy, build/release tools, and test coverage residuals. No additional current substantive code/architecture findings survived cross-file validation. Open field checks A3/A4/A5/D1/E1/E2/E3 remain manual evidence gaps documented by `docs/FIELD_CHECKS.md`, not code-review defects.
