# Aggregate review — RPL cycle 1 (2026-10-02, HEAD ba5b16e7)

Sources (per-agent files kept as-is for provenance): architect, code-reviewer, critic, debugger,
designer, document-specialist, perf-reviewer, security-reviewer, test-engineer, tracer, verifier
(all dated 2026-09-30, from the interrupted first attempt of this cycle and re-used after a
completeness check), plus fd-code-reviewer and qa-adversary (run 2026-10-02).

Severity/confidence below is the HIGHEST any agent assigned to a duplicate. "Agents" lists every
agent that independently flagged the item (multi-agent = higher signal). Nothing here is
device-verified; every camera/GL/HAL item stays PENDING DEVICE until measured on PMA110.

## A. Correctness — DNG is a route input but is not handled as an optics door (5 agents)

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-1 | DNG toggle flips logical↔standalone (zoom scale) without converting `zoomRatio`/lens; 3× becomes 9× (DNG on) or 1× (DNG off); no `invalidateOpticsDerivedState`/`cancelPendingControls` (`CameraViewModel.onSetPhotoFormats`, `CameraEngine.setRawWanted`) | High / High | code-reviewer CR-1, architect A1, tracer T3 |
| AGG-2 | `restoredOptics` PHOTO branch ignores the DNG standalone route: restore/MR recall reads a lens-local ratio as unified and drops the lens band (`ZoomMath.kt:372-402`) | High / High | code-reviewer CR-2, tracer T1, verifier V1 |
| AGG-3 | Engine same-camera fast paths (`setVideoMode`, `setResolvedOptics` terminal mutations) still use `!video` instead of `!standaloneRouteWanted(...)` before `LensChoice.forZoom` (`CameraEngine.kt:2665`, `:2818`) | High / High | verifier V2, tracer T4 |
| AGG-4 | `rawWanted` is outside `OpticsSnapshot`/rollback/recall packet; a failed DNG reopen leaves DNG on over a logical session and blocks re-selection; recall is split into two transactions | Medium / Medium | architect A2, tracer T5, perf P11 (not volatile) |
| AGG-5 | `setRawWanted` while started+paused leaves stale `overrideId` for `resume()` | Low / Low | tracer T6 (verifier: unreachable today) |
| AGG-6 | `setRawWanted` on FRONT runs a pointless full reopen | Low / High | tracer T7 |
| AGG-7 | Timelapse keeps start-time formats while DNG toggles move the route mid-run | Low-Med / High | tracer T8 |

## B. Correctness — exposure

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-8 | ANGLE shutter reachable in ISO priority / app-side P (Fn toggle, `onShutterMode`, `applyLoaded` for ISO); AE loop writes to the ignored `exposureTimeNs` | Med-High / High | code-reviewer CR-3, critic CR-3 |
| AGG-9 | Entering ISO from M+ANGLE seeds the loop from a stale `exposureTimeNs` instead of the effective angle exposure | Low / High | critic CR-4 |
| AGG-10 | P→S/ISO/M handoff seeds from traded preview wire values even when P was app-side | Medium / High | code-reviewer CR-4 |
| AGG-11 | 10-bit HLG video preview/meter/zebra read HLG code values as SDR; app-side video AE mis-exposes | Med-High / Medium | code-reviewer CR-5 |
| AGG-12 | AEL hardware binding is a silent no-op in app-side modes | Medium / High | code-reviewer CR-6 |
| AGG-13 | App-side AE loop keeps running during a manual AEB bracket | Medium / Medium | code-reviewer CR-7 |
| AGG-14 | App-side P 1/focal rule ignores digital zoom and the declared host focal | Low-Med / High | code-reviewer CR-12 |
| AGG-15 | Angle clamp silently moves a long Photo exposure by up to ~6 stops on the unit toggle | Low / High | critic CR-7 |

## C. Correctness — capture, storage, recording (data loss / diagnosability)

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-16 | Degraded-audio stop path deletes a good video take when the readable-track probe throws transiently (`MediaStoreWriter.hasReadableVideoTrack`) | Medium / Medium | debugger D4 |
| AGG-17 | Launch recovery stops at the first persistently failing media row; later pages and the DISCARD stage never run | Medium / Medium | code-reviewer CR-11 |
| AGG-18 | Deleting a restored, fully owned prior-process family tombstones the synthetic prior id and blocks every later gallery restore | Medium / Med-High | code-reviewer CR-10 |
| AGG-19 | A null `rawChars` (transient characteristics failure at open) fails every still for the controller's lifetime | Medium / Medium | debugger D7 |
| AGG-20 | `VideoRecorder.start` discards the encoder/muxer setup exception (no release log) | High / High | debugger D1 |
| AGG-21 | Every audio degrade-to-silent edge is unlogged in release | High / High | debugger D2 |
| AGG-22 | `openParcelFd`/`openOutputStream` swallow provider exceptions (still/video saves fail with no log) | High / High | debugger D3 |
| AGG-23 | `publish` drops every `update()` failure cause | Medium / Medium | debugger D5, critic CR-5 |
| AGG-24 | Still snapshot/encode failure has no log | Medium / High | debugger D6 |
| AGG-25 | Remaining silent exits on the identity/registration path | Low / High | critic CR-5 |
| AGG-26 | New identity-read warnings are per-retry and drain the 120-row reserved budget | Medium / High | critic CR-2, test-engineer note |
| AGG-27 | Diagnostic budget is process-LIFETIME on every device | Medium / Medium | architect A5 |
| AGG-28 | Pending-token admission widened to the whole Engine owner (GL/Camera2 now admitted during REC setup) | Medium / Medium | critic CR-1, critic CR-8 (test gap) |
| AGG-29 | `tryComplete` may deliver `onPhoto` then `onError`; a throwing `onError` escapes onto the camera thread | Low / Low | debugger D8 |
| AGG-30 | `runCatching { Thread.sleep }` clears the interrupt flag in retry loops | Low / Low | debugger D9 |
| AGG-31 | `DngWriteResult.Failed` reports a complete, recoverable DNG as "save failed" | Low / Low | debugger D10 |
| AGG-32 | SettingsStore ignores the `commit()` result | Low / Low | debugger D11 |
| AGG-33 | Persisted `exposureTimeNs`/`fps`/`wbKelvin` restored unbounded | Low / Low | debugger D12 |
| AGG-34 | Settings save before encoder inventory loads persists degraded formats/transfer | Low / Medium | code-reviewer CR-14 |
| AGG-35 | Missing `phoneModel` key restores FIND_X9_ULTRA on unknown hardware | Low / Medium | code-reviewer CR-17, verifier V6 |
| AGG-36 | Persisted/MR `videoResolution` is the engine's fallback, not the operator's request | Medium / Medium | tracer T2 |
| AGG-37 | MR recall/restore doesn't clear `AUDIO_OFF_BY_DENIAL` | Low / Medium | tracer T9 |
| AGG-38 | A full identity-recovery owner closes all capture with a generic failure message | Low / Medium | critic CR-6 |
| AGG-39 | Audio/video PTS on two clocks; dropped mic samples shift audio early | Medium / Medium | code-reviewer CR-8, perf P6 |
| AGG-40 | Offered frame rates are not gated on the selected size or codec | Medium / High (code) | code-reviewer CR-9 |
| AGG-41 | EXIF build failure aborts the whole HEIF save | Low / Medium | code-reviewer CR-16 |
| AGG-42 | Drain loops have no self-deadline after stop; missing EOS escalates to quarantine | Medium / Medium | perf P7 |
| AGG-43 | Stop/failure paths take `muxerLock` unbounded | Low / Medium | perf P9 |

## D. Session ladder / route consistency

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-44 | After the 10-bit still-less rung fails, attempt 1 is HLG+JPEG, not the "ordinary 8-bit ladder" the comment promises | Medium / Medium | verifier V4 |
| AGG-45 | Debug `nativelog` flag arms the still-less HLG session in PHOTO, contradicting its KDoc | Low / High | verifier V5 |
| AGG-46 | FRONT has facing special cases for RAW and the Loupe Overview that CLAUDE.md says do not exist; rationale stale | Medium / High (mismatch) | verifier V3 |
| AGG-47 | The two model seams disagree on CPH2841 (detectPhone = X9 Ultra, DeviceProfile = GENERIC) | Medium / Medium | architect A3 |
| AGG-48 | DeviceProfile resolved twice; UI mirror reads a live getter rather than the published route | Medium (design) / Low | architect A4 |
| AGG-49 | 14 optics doors re-implement the scale-transition checklist by hand | Medium (design) | architect A7 |
| AGG-50 | Cold-start route resolution runs off `setupExecutor` (GL/timelapse thread) → duplicate optics transaction | Medium / Medium | perf P1 |
| AGG-51 | `gyro.start()` on the GL thread can undo `pause()`'s `gyro.stop()` | Medium / Medium | perf P2 |
| AGG-52 | `applyResolvedCameraRoute` RMW of `controls` outside the Engine monitor | Medium / Medium | perf P8 |
| AGG-53 | `GlPipeline.thread/handler` plain fields read cross-thread | Low / Low | perf P12 |
| AGG-54 | Window rotation stale on a direct 90↔270 flip (large screens) | Low-Med / Medium | code-reviewer CR-13 |
| AGG-55 | Loupe Overview tap-exclusion rect ignores the measured bottom clearance | Low / High | code-reviewer CR-15 |

## E. Performance / UI

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-56 | Orientation animation recomposes the whole `CameraScreen` per frame | Medium / High | perf P3 |
| AGG-57 | 10 Hz level ticker and REC audio levels recompose open modal sheets | Medium / High | perf P4 |
| AGG-58 | Review pinch/pan recomposes the whole review overlay per touch event | Medium / Med-High | perf P5 |
| AGG-59 | Scope DrawScope allocations per redraw | Low / High | perf P10 |
| AGG-60 | Per-read allocations / zero-read spin in audio loops | Low / Medium | perf P13 |
| AGG-61 | `LatestHeavyWorkLane` class is test-only | Low (hygiene) | perf P14 |
| AGG-62 | Settings tab-rail labels break mid-word at large font scale | Medium / Medium | designer DSN-R1-01 |
| AGG-63 | Status plate auto-dismiss ignores the accessibility timeout | Medium / Medium | designer DSN-R1-02 |
| AGG-64 | Critical status plate has no horizontal margin | Low / Medium | designer DSN-R1-03 |
| AGG-65 | Zebra/false-colour use Rec.2020 luma weights on BT.709 input | Low / Medium | code-reviewer CR-18 |
| AGG-66 | `onAspectRatio` has no `rejectIfRecording` despite the stated contract | Low / High | code-reviewer CR-18 |

## F. Security / release tooling

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-67 | Upload-key custody weak (owner action; details withheld from tracked files) | High / High | security SEC-01 |
| AGG-68 | Rotation gate enforced by only one of two release entry points | Medium / High | security SEC-02 |
| AGG-69 | Internal reviews/plans committed to the public repo despite `.gitignore` | Medium / High | security SEC-03 |
| AGG-70 | Scoped-secret stdin credentials silently overridden by `keystore.properties` | Low / Medium | security SEC-04 |
| AGG-71 | Release artifact checker doesn't assert debuggable/exported/backup posture | Low / High | security SEC-05 |
| AGG-72 | Checksum-only dependency verification | Low / High | security SEC-06 |
| AGG-73 | Debug exported components rely on DUMP; comments call it signature-only | Low / High | security SEC-07 |
| AGG-74 | LAN/Tailscale addresses in tracked tooling | Low / High | security SEC-08 |
| AGG-75 | Content URIs in release warning logs | Info | security SEC-09 |
| AGG-76 | Legacy `adb tcpip 5555` on the fleet | Info / Medium | security SEC-10 |
| AGG-77 | `verify_host.py` doesn't check JDK version/keytool; `adb_proxy.py` leaks fds | Low / High | code-reviewer CR-18 |
| AGG-78 | Discard identity includes `DATE_TAKEN`, possibly rewritten by scan | Low / needs-device | code-reviewer CR-18 |

## G. Tests

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-79 | `LatestHeavyWorkLaneTest` retry must finish within real 100 ms + unchecked cast | Medium (flaky) | test-engineer TE-1 |
| AGG-80 | Ownerless delete tests use a 250 ms wall-clock budget | Medium (flaky) | TE-2 |
| AGG-81 | `DiagnosticLogTest` order-dependent / vacuous once budget is spent | Medium | TE-3 |
| AGG-82 | `PendingAllocationIdentityRecoveryTest` plain list sink on a process signal | Medium (flaky) | TE-4 |
| AGG-83 | No per-test/suite timeout; untimed joins hang the gate | Medium | TE-5 |
| AGG-84 | Encoder `MediaFormat` builders never exercised (PQ-tag trap unguarded) | Medium | TE-6 |
| AGG-85 | 2eb57e4e diagnostics untested | Low-Med | TE-7 |
| AGG-86 | Pure exposure math outside the coverage gate | Low-Med | TE-8 |
| AGG-87 | `DigitalGainTest` tautological | Low | TE-9 |
| AGG-88 | `StartupTraceTest` restores a different seam; one tautological test | Low | TE-10 |
| AGG-89 | Process singletons not asserted idle after tests | Low | TE-11 |
| AGG-90 | Negative waits prove little under load | Low | TE-12 |
| AGG-91 | `tap_af_aim.py` untested; stale first 3A reading | Low | TE-13 |
| AGG-92 | `release_permissions.py` sdk-23 path untested | Low | TE-14 |

## H. Documentation drift

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-93 | `setFrontStreamPreMirrored` does not exist (it is `setFrontMirrorConvention`); "becomes a DeviceProfile flag" already happened | Medium / High | document-specialist DS-1 |
| AGG-94 | `statusDisplayDurationMs` no longer exists | Low / High | DS-2 |
| AGG-95 | Glyph-coverage rule contradicts EN+KO (Inter carries no Hangul) | Medium / High | DS-3 |
| AGG-96 | Toolchain pins behind latest stable (AGP 9.4.1, Kotlin 2.4.20, BOM 2026.09.00, Gradle 9.8.0, core-ktx 1.19.1, Robolectric 4.17) | Medium / High | DS-4 |
| AGG-97 | Dead English `focusConfidenceLabel` is what the tests pin | Low / High | DS-5 |
| AGG-98 | Latent English fallbacks on localized UI paths | Low / Medium | DS-6 |
| AGG-99 | Privacy contact address looks like a placeholder | Info | DS-7 |
| AGG-100 | Documented timings are unnamed literals | Info | DS-8 |
| AGG-101 | Lens-match tolerance is ×1.35 log-symmetric, not "±35%" | Low / High | verifier V7 |
| AGG-102 | Stale comments: `seedPhoneModel` KDoc, "ONE Build.MODEL read", "DNG only in TELE" | Low / High | verifier V8, architect A6 |
| AGG-103 | `driveProgram` doc says "one stop" per tick, code is ±0.35 | Low / High | code-reviewer CR-18 |

## I. Gate / QA (qa-adversary, fd-code-reviewer)

| ID | Finding | Sev / Conf | Agents |
|---|---|---|---|
| AGG-104 | `muxer!!.addTrack` vs `muxer?.writeSampleData` + unconditional `wroteAudioSample = true` in the drain loops (`VideoRecorder.kt:587, :859, :874`) | Low / Low | qa-adversary F1 |
| AGG-105 | Lint warnings: 2 version-currency (= AGG-96), 5 `ModifierParameter` (`CameraScreen.kt:1693, 3161, 3248`, `ManualDials.kt:222, 332`), 1 `UsableSpace` (`MediaReview.kt:456`), 1 `UseKtx` (`PendingDiscardJournal.kt:575`) | Low (warnings) | qa-adversary |

Gate baseline at HEAD `ba5b16e7` (qa-adversary): `python3 tools/verify_host.py` PASS — 2271/2271
unit tests, lint 0 errors / 9 warnings, Partition A 99.84%, Python suites 347/347, check_docs 188/188.

fd-code-reviewer found no additional finding at confidence >= 80 and independently confirmed AGG-2.

## Cross-agent agreement (highest signal)
- DNG-as-route-input cluster AGG-1..AGG-4: code-reviewer, architect, tracer, verifier, fd-code-reviewer.
- ANGLE shutter in loop-owned modes AGG-8: code-reviewer, critic.
- Silent failure diagnostics AGG-20..AGG-26: debugger, critic, test-engineer.
- A/V clock AGG-39: code-reviewer, perf-reviewer.
- Phone-model fallback AGG-35: code-reviewer, verifier.

## AGENT FAILURES
None. The interrupted first attempt never received fd-code-reviewer or qa-adversary; both were
re-run on 2026-10-02 and returned. The 11 other per-agent files were re-used after checking that
each was complete (summary/count section present) and dated 2026-09-30.

Total distinct findings: 105.
