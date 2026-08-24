# Security review — cycle 35

Date: 2026-08-24
Reviewed revision: `87e4ac4a0de23a309b810a0076945a6b44430518`
Workspace: clean detached review worktree `/private/tmp/find-x9-ultra-rpf35.0PSzsb`

## Scope and inventory

I read `CLAUDE.md` completely before review, followed by the committed as-built and field
authorities in `docs/ARCHITECTURE.md` and `docs/FIELD_CHECKS.md`, the public privacy and Play data
safety material, the current aggregate, the retained specialist reports, and the completed cycle
30-34 plans. Historical findings were used only as regression leads; none was re-filed without
current-HEAD evidence.

The complete tracked inventory at this revision is 471 paths: 101 production Kotlin files, 216 host
Kotlin tests, four instrumented-test files, and 38 host/device tool or harness paths, plus manifests,
resources, Gradle inputs, documentation, and assets. The security pass covered the full inventory by
file census and systematic source/configuration sweeps, then traced the security-relevant paths
across files:

- merged debug/release component and permission surfaces; launcher and all debug-only intent inputs;
- runtime camera, microphone, and visual-media grants; full/partial obscured-touch rejection;
- owner-null MediaStore admission, provenance, exact-file system consent, app-owned family deletion,
  pending/discard/family journals, launch recovery, and review decoding/playback;
- provider URI construction and mutation, filename/path/owner/MIME bounds, preference/database backup
  exclusion, EXIF/location behavior, logging, and outgoing navigation;
- tracked secrets, signing inputs, dependency verification, immutable debug/release source and output
  boundaries, subprocess construction, device-test source/APK attestation, and evidence output paths;
- network, WebView, dynamic-code, native-loading, crypto, and arbitrary-file APIs (all absent from the
  shipping application except the expected build-tool subprocess and fixed app-private/debug seams).

Cycle-34 deltas received a separate regression trace: the `DUMP`-protected debug command activity and
mailbox, transitive debug activity overrides, ownerless-delete result classification, finite
family-marker dispatcher, audio peak frame, settings migration default, and Android SDK/toolchain
preflight.

## Verification evidence

- The current merged debug manifest exposes only the ordinary launcher without a permission; every
  additional exported debug/tooling activity and the profile installer receiver requires
  `android.permission.DUMP`. The merged provider is not exported, the test host activity is forced
  unexported, and neither `INTERNET` nor `ACCESS_NETWORK_STATE` survives the merge.
- MainActivity consumes no launcher extras. Camera-control values enter only through the debug
  variant's `DUMP`-protected activity and a process-local atomic mailbox; release has no exported
  producer and the consumer is additionally gated by `BuildConfig.DEBUG`.
- Owner-null media remains admitted only by exact capture directory, filename grammar, collection,
  extension, MIME, published state, and null owner. It is labeled
  `LEGACY_FORMAT_UNVERIFIED`, frozen to one exact file, and routed through
  `MediaStore.createDeleteRequest`; it never reaches the direct resolver-delete path.
- App-owned deletion reserves finite pre-marker capacity before publishing a tombstone. Provider and
  journal uncertainty fail closed, and process-wide producer/publication leases prevent a late
  sibling from escaping an exact durable family veto.
- No tracked credential/private key or shipping network/dynamic-code surface was found. Backup is
  disabled and both backup rule formats exclude the installation-local preferences and database.
- Focused current-HEAD tests passed for `DebugCameraControlSecurityTest`,
  `OwnerlessMediaDeleteActivityResultTest`, `OwnerlessMediaDeleteLifecycleTest`,
  `FamilyDeletionMarkerDispatcherTest`, `DualOpenWaitTest`, and `AudioGainTest`.

## Findings

No actionable security finding survives current-HEAD verification.

The remaining device checks in `docs/FIELD_CHECKS.md` are explicitly labeled manual-validation
obligations and are not code-backed security claims; this review does not relabel those open field
checks as defects or as host-proven behavior.

## Final missed-issue sweep

The final sweep rechecked all exported components and permissions, every intent ingress/egress,
overlay/touch policy, URI/provider mutation, owner-null boundary, parser/native review path,
app-private durable state, secrets/log/network surface, build and signing inputs, immutable artifact
ownership, subprocess calls, and device-evidence contracts. It also re-ran the cycle-34 change
surface against the current merged manifest and focused tests. Previously fixed findings remain
fixed; no Critical, High, Medium, or Low security finding remains in this specialist scope.

**New finding count: 0.**
