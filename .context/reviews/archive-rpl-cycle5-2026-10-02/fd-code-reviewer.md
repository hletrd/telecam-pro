# FD5 — high-confidence bug finder (RPL cycle 5)

Role: report only Medium-or-higher-confidence issues that matter (correctness, crash, data loss,
races). Scope: storage/MediaStoreWriter.kt, storage/PendingDiscardJournal.kt,
storage/SettingsStore.kt, capture/StillCapturePipeline.kt (+ capture/HeifExif.kt, which cycle 4
moved the JPEG EXIF splice into), video/VideoRecorder.kt, camera/StandbyAudioController.kt,
camera/RecordingPreNativeAllocation.kt, camera/RetainedStill*.kt, ui/CaptureOutputTracker.kt,
ui/review/MediaReview.kt, ui/review/LatestHeavyWorkLane.kt, storage/LatestCaptureReducer.kt.

Method: I started from the cycle-4 diff (887d39fb..ea7d4374) for these files, because that is
where new defects are most likely: the AGG4-6 single-write JPEG EXIF splice, the AGG4-3/AGG4-4
moov-presence walk, the AGG4-28 SIZE-column removal, and the AGG4-4/AGG4-5 bounded re-arm. I then
read the full pure ownership seams (CaptureOutputTracker, LatestCaptureReducer,
RecordingPreNativeAllocation) and traced their state transitions by hand. I did not run Gradle, as
the preamble instructs.

Result: **no Critical, High, or Medium findings.** One Low finding has high confidence about the
mechanism but a narrow window, on a lane that is dormant on PMA110. One Info item follows it.

---

## FD5-1 — JPEG recovery tail probe can adopt a header-only passthrough JPEG whose APP1 ends in the thumbnail's EOI

- Severity: **Low**. Confidence: **Medium**. Status: **Likely** (mechanism is confirmed from code;
  the ExifInterface thumbnail placement needs a host fixture to pin).
- Citations:
  - `app/src/main/kotlin/me/hletrd/telecampro/capture/HeifExif.kt` `writeJpegWithExifApp1`: SOI +
    APP1 marker/length, then `out.write(payload)`, then the plan ranges in separate writes.
  - `app/src/main/kotlin/me/hletrd/telecampro/capture/StillCapturePipeline.kt`
    `writePassthroughJpeg` → `composePassthroughExifApp1(extractExifApp1(bytes))`. Tier 1 seeds the
    composer with the HAL EXIF, including its IFD1 JPEG thumbnail.
  - `app/src/main/kotlin/me/hletrd/telecampro/storage/MediaStoreWriter.kt:1832-1847`
    `probeCompleteJpeg`: VALID iff the file is at least 4 bytes and its last two bytes are `FF D9`.
- Why it matters: AGG4-6 exists because the tail-only probe adopts any file ending in `FF D9`. The
  single-write splice fixed the in-place rewrite case, but it left one prefix that still ends in
  `FF D9`. When ExifInterface re-serializes an APP1 that carries a thumbnail, it writes the
  thumbnail JPEG bytes after the IFDs, at the end of the EXIF block. The composed passthrough
  payload therefore ends in the thumbnail's own EOI. A process death after `out.write(payload)`
  returns and before the first plan range lands leaves this file on disk: `FF D8 FF E1 <len> <EXIF
  … thumbnail … FF D9>`. That is a JPEG with no SOF and no SOS.
- Failure scenario: the hi-res passthrough lane is active on a capable device, and the
  process is killed (swipe-kill, low-memory kill, or crash elsewhere) in the gap between the APP1
  write and the body write. The row is still REGISTERED, so launch recovery probes it, sees the
  `FF D9` tail, returns VALID, and ADOPTs it. The user's gallery then shows a broken JPEG, and the
  real pixels are lost. The AGG4-6 comment ("no byte of the pending row is ever rewritten") implies
  that a crashed JPEG can never be adopted, and this case contradicts it.
- Why only Low: the window is between two `write` calls (the ~40 MB body write is long, but a kill
  inside it ends on arbitrary entropy-coded bytes, which cannot form `FF D9`). The lane is
  capability-gated and dormant on PMA110 (200 MP is not exposed). The processed lane's composer has
  `sourceExifApp1 = null` and a 1×1 seed, so its payload carries no thumbnail. I did not find a
  path for the processed lane to end in `FF D9`.
- Suggested fix (host-testable, no PMA110 behavior change): make `probeCompleteJpeg` structural as
  well as tail-based. Walk the header with the same segment walker as `exifSplicePlan` (or a
  shared "reaches SOS" helper), and require that an SOS (`FF DA`) is reached before accepting the
  `FF D9` tail. A host test can feed `SOI + APP1(payload ending FF D9)` and assert INVALID, and feed
  a real encoded JPEG and assert VALID. The walk reads only header bytes, so the probe cost stays
  bounded. Optionally, write the whole spliced file through one buffered stream to shrink the
  window, but the probe change is the real fix.
- PMA110 impact: none for live captures. Recovery would reject strictly more files, and only
  header-only ones.

## FD5-2 (Info) — processed JPEG lane now holds roughly three copies of the encoded image

- Severity: **Info**. Confidence: High. Status: Confirmed.
- Citation: `capture/StillCapturePipeline.kt` `writeProcessedJpeg`: `ByteArrayOutputStream()` with
  no size hint, then `toByteArray()`.
- Detail: the stream starts at 32 bytes and doubles as it grows, so at the end the backing array
  can be up to about 2× the JPEG size, and `toByteArray()` adds another copy, all next to the live
  rotated `Bitmap`. On PMA110 (about 12.5 MP, a few MB of JPEG) this is harmless. On a larger
  processed frame it raises peak heap during bursts. This is not a correctness bug.
- Suggested fix: presize the stream (for example `width * height / 4`), or splice from the stream's
  internal buffer without the `toByteArray()` copy.

---

## Examined and found sound (no finding)

- **exifSplicePlan / writeJpegWithExifApp1 / spliceExifApp1**: SOI handling, fill bytes (a kept
  lone `FF` before a marker is a legal fill), TEM/RST, overrun and EOI-before-SOS refusal, removal
  of multiple Exif APP1s, XMP APP1 preserved, 64 KiB segment cap. `exifApp1WithoutThumbnailIfd`
  bounds-checks IFD0 and the link before zeroing.
- **composePassthroughExifApp1 tiers**: a thrown tier falls through, an unspliceable payload falls
  through, and the last tier's throw reaches `bestEffortHeifExif`. A failed bounds decode (−1 dims)
  makes every tier throw on `require`, which means a logged save without EXIF and no lost image.
- **classifyFinalizedVideoTrack**: the first descriptor is always closed before the retry. A fresh
  reopen failure is INDETERMINATE. Only a proven-ABSENT walk after two parse throws is INVALID.
- **probeMp4MoovPresence**: the MPEG4Writer `????` 32-bit and `????????` 64-bit mdat placeholders
  either overrun (ABSENT) or walk into sample bytes, which ends ABSENT/UNKNOWN in practice. A
  `moov` that overruns is ABSENT, and the 4096-box bound gives UNKNOWN. The tracked MRG4-4 residual
  is unchanged.
- **parcelFdMoovPresence**: `FileInputStream(FileDescriptor)` is not the fd owner on Android, and
  its channel's close goes through the parent stream, so the walk does not close the
  ParcelFileDescriptor the extractor used.
- **orphanDisposition / keptRowReassertsPending**: a COMPLETE row is never deleted on a probe, a
  provider-empty COMPLETE row is probed rather than published on the marker alone, DISCARD is never
  re-armed, and an UNAVAILABLE journal re-arms without probing.
- **CaptureOutputTracker**: record/trim eviction gives TRACK_ONLY, the pinned family is excluded
  from the ordinary limit, the PRIOR slot is never tombstoned, `deletedPriorOutputs` is cleared for
  confirmed survivors, FILE_ONLY preserved siblings come back, and a newer live capture keeps
  review across a survivor restore.
- **LatestCaptureReducer**: Proven groups key on the exact family, Legacy groups are one row each
  (no proximity grouping), ranking is total, and FILE_ONLY applies whenever any family row is not
  owned.
- **RecordingPreNativeAllocation**: first-wins WAITING/ALLOCATED/CLAIMED/RETIRED transitions, a
  late value is handed out exactly once, and cancellation attached after retirement fires
  immediately. A throwing task is swallowed by `FutureTask`, but the armed deadline retires the
  attempt (by design).
- **SettingsStore**: every write goes through `commitEdit` (synchronous commit with an observed
  result), and reads are `runCatching`-defaulted.

## Final sweep (commonly missed)

I swept for the following and found no new defect: swallowed exceptions on the insert/identity
path (cycle-4 code logs them), fd leaks on early returns (`use` everywhere in the new probes),
interrupt-flag clearing (`sleepPreservingInterrupt` is used in the parse retry), lock-order
inversions in RetainedStillDeletionOwner (each method takes only its single `lock`; publish runs
outside it), and per-frame logging (none added).

Coverage depth: full reads of CaptureOutputTracker, LatestCaptureReducer,
RecordingPreNativeAllocation, the cycle-4 diffs of StillCapturePipeline/HeifExif/VideoRecorder/
MediaStoreWriter, and the probe/recovery section of MediaStoreWriter. RetainedStillDeletionOwner
and SettingsStore got a structural pass (lock and commit discipline). MediaReview,
LatestHeavyWorkLane, StandbyAudioController, PendingDiscardJournal, and the rest of VideoRecorder
were outside this 25-minute budget beyond the cycle-4 diff and were **not** deeply reviewed. Treat
them as unreviewed by this lane.
