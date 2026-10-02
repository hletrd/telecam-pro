package me.hletrd.telecampro.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import me.hletrd.telecampro.camera.DiagnosticLog as Log
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.CameraCaps
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraStatus
import me.hletrd.telecampro.camera.CameraStatusArgument
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.DeletedStillPublication
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.RetainedStillDisposition
import me.hletrd.telecampro.camera.RotationMath
import me.hletrd.telecampro.camera.TeleSelection
import me.hletrd.telecampro.camera.CropBox
import me.hletrd.telecampro.camera.centerCropBox
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.MediaStoreWriter
import me.hletrd.telecampro.storage.PendingOutputDiscardResult
import me.hletrd.telecampro.storage.PendingOutputAllocation

/** Immutable request-time state consumed by every output belonging to one shutter press. */
internal data class ShotSpec(
    val controls: ManualControls,
    val caps: CameraCaps?,
    val selection: TeleSelection?,
    val teleconverter: Boolean,
    // The mounted converter's magnification, snapshotted with [teleconverter] so this shot's EXIF
    // 35 mm focal describes the optic it was actually taken through (see Teleconverter.kt).
    val teleconverterMagnification: Float,
    // The declared phone's host tele focal the magnification multiplies (70 OPPO / 85 vivo kits);
    // snapshotted for the same EXIF honesty (review 2026-08-01 — the 70 default wrote false
    // focals on non-PMA110 hosts).
    val hostTeleEquivMm: Float = me.hletrd.telecampro.camera.LensChoice.TELE3X.targetEquivMm,
    val aspectRatio: AspectRatio,
    val jpegQuality: Int,
    val rotationDegrees: Int,
    val captureId: Int,
    val familyKey: CaptureFamilyKey,
    val requestedAtMs: Long,
    val takenAtMs: Long,
    // Fired against a hi-res (full-sensor) session: the processed JPEG saves PASSTHROUGH — the
    // decode→crop→rotate lane would inflate a ~200MP JPEG to an ~800 MB ARGB bitmap (OOM), so
    // orientation travels in EXIF like DNG and the 16:9 crop cannot apply (the 4:3 admission gate
    // upstream guarantees it is never wanted).
    val hiRes: Boolean = false,
    // Snapshotted at dispatch like [teleconverter]: a facing flip mid-save must not relabel this
    // shot's EXIF lens model or rotation. Front stills save UNMIRRORED (only the preview mirrors).
    val frontFacing: Boolean = false,
    // First-class route identity: EXTERNAL must never inherit the host handset's EXIF identity.
    val route: CameraRoute = if (frontFacing) CameraRoute.FRONT else CameraRoute.BACK,
)

/**
 * Everything the JPEG EXIF stamp needs, snapshotted AT THE SHOT (capture result + the controls
 * and optics active for that frame). Field set mirrors the stock camera's 3× reference sample
 * (FNumber/FocalLength/35 mm/LensModel/APEX values/metering/flash/program/zoom).
 */
internal data class ExifShot(
    val iso: Int,
    val expNs: Long,
    val lensFocalMm: Float,
    val lensApertureF: Float,
    val focal35mm: Int,
    val digitalZoom: Float,
    val evBiasStops: Float,
    val meteringMode: MeteringMode,
    val flashFired: Boolean,
    val exposureProgram: Int, // EXIF: 1=manual, 2=program, 4=shutter priority
    val manualExposure: Boolean,
    val manualWb: Boolean,
    val lensModel: String,
    /**
     * EXIF Make/Model straight from the running build (null when it reports nothing usable, in
     * which case the tag is omitted rather than written empty). Carried on the shot instead of read
     * inside [exifAttributeList] so that formatter stays a pure function of its input — it is
     * covered by plain JVM tests with no Android framework.
     */
    val deviceMake: String?,
    val deviceModel: String?,
    val takenAtMs: Long,
)

/** A fully written DNG awaiting MediaStore publication on the I/O lane. */
internal data class PendingDngPublication(
    val allocation: PendingOutputAllocation,
    val captureId: Int,
    val familyKey: CaptureFamilyKey,
    val completionMarkerDurable: Boolean,
) {
    val uri: android.net.Uri get() = allocation.uri
}

internal sealed interface DngWriteResult {
    data class Complete(val publication: PendingDngPublication) : DngWriteResult
    data class Rejected(
        val allocation: PendingOutputAllocation,
        val failure: Throwable,
    ) : DngWriteResult
    /**
     * The bytes are COMPLETE (the stream closed) but the durable COMPLETE marker threw. The row is
     * a structurally complete private DNG that launch recovery adopts, so it carries the same
     * publication packet as marker exhaustion — never a bare failure the engine would report as
     * "save failed" over a file the next launch publishes (RPL cycle 2, AGG-31).
     */
    data class Failed(
        val publication: PendingDngPublication,
        val failure: Throwable,
    ) : DngWriteResult
}

/**
 * The STILL SAVE LANES, extracted from CameraEngine (ARCH4-3 step 1 of the god-object plan):
 * decode/crop/rotate + HEIF/JPEG encode, DNG write, and JPEG EXIF re-stamp. Runs entirely on the
 * caller's executors (ioExecutor for processed stills/publication; the camera callback for the DNG
 * write, whose DngCreator needs the live Image), reads only immutable [ShotSpec]/[ExifShot]
 * snapshots, and
 * touches NO engine monitor — the zero-ownership-risk slice the extraction plan lands first. The
 * emit callbacks read the engine's live listeners at invoke time, so late listener wiring behaves
 * exactly as before the move.
 */
internal class StillCapturePipeline(
    private val context: Context,
    private val emitStatus: (CameraStatus) -> Unit,
    private val emitMediaSaved: (android.net.Uri, Int) -> Unit,
    private val emitRawSaved: (android.net.Uri, Int) -> Unit,
    /** Engine-owned check/publish/recheck boundary; survives ViewModel callback detachment. */
    private val publishStillOutput: (
        PendingOutputAllocation,
        Int,
        publish: () -> Boolean,
    ) -> DeletedStillPublication,
    /** Releases the successful-publication race owner only after the saved callback has returned. */
    private val finishPublishedStill: (PendingOutputAllocation, Int) -> Unit,
    // A COMPLETE output whose MediaStore publish failed. The same Engine owner that guards the
    // pre-publication boundary receives this retained result, so a family tombstoned during the
    // provider call still takes DISCARD instead of becoming recoverable media.
    private val emitPublishRetained: (PendingOutputAllocation, Int) -> RetainedStillDisposition,
    private val onRejectedOutputDisposition: (PendingOutputDiscardResult) -> Unit,
) {

    private fun discardRejectedOutput(allocation: PendingOutputAllocation) {
        val dispatch = MediaStoreWriter.dispatchRejectedOutput(
            context,
            allocation,
            onRejectedOutputDisposition,
        )
        if (dispatch != me.hletrd.telecampro.storage.RejectedOutputCleanupDispatch.ACCEPTED) {
            // REGISTERED remains durable launch-recovery ownership. Saturation never runs provider
            // or SQLite work inline on the save/camera caller.
            onRejectedOutputDisposition(PendingOutputDiscardResult.UNRESOLVED)
        }
    }

    /**
     * The JPEG is the first [length] bytes of [bytes] (a YUV snapshot hands over its encoder's
     * backing array, AGG6-35). ONE decode → center-crop to [ShotSpec.aspectRatio] (processed stills only; [saveDng]'s RAW
     * output always stays full-frame) → rotate pass feeding BOTH processed encoders (PERF4-5): the
     * old per-format lanes each decoded/cropped/rotated the SAME bytes into a ~50 MB ARGB
     * intermediate, so a HEIF+JPEG shot paid the whole pixel pipeline twice serially on
     * [ioExecutor]. Each encoder keeps its own failure isolation (a HEIF write error must not cost
     * the JPEG) and the publish-or-delete policy documented on [writeProcessedHeif].
     */
    fun saveProcessedStills(
        bytes: ByteArray,
        length: Int,
        spec: ShotSpec,
        exifShot: ExifShot,
        wantHeif: Boolean,
        wantJpeg: Boolean,
    ) {
        if (!wantHeif && !wantJpeg) return
        if (spec.hiRes) {
            // Hi-res passthrough lane (see [ShotSpec.hiRes]): the HAL JPEG bytes are written
            // UNMODIFIED and only EXIF (incl. the rotation as an orientation TAG) is stamped after.
            // HEIF is unavailable here — format normalization already collapsed it, and running it
            // anyway would be the exact 200MP decode this branch exists to avoid.
            if (wantJpeg) {
                // The passthrough source is a HAL JPEG snapshot, whose array IS the JPEG; the trim
                // is a guard, never a copy on that path.
                val exact = if (length == bytes.size) bytes else bytes.copyOf(length)
                runCatching { writePassthroughJpeg(exact, spec, exifShot) }
                    .onFailure {
                        Log.e("StillCapturePipeline", "JPEG save failed", it)
                        emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status())
                    }
            }
            return
        }
        var decoded: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, length)
            if (d == null) {
                // decodeByteArray returns null (it does not throw) on a corrupt/truncated JPEG — a
                // StillSnapshot YUV→JPEG repack fault or a garbage HAL blob. Every other failure edge
                // here logs; this one used to toast "Photo save failed" with NO app line, the exact
                // signature CLAUDE.md names for swallowed save defects (AGG2-22). One reserved row
                // per failed shot.
                Log.e("StillCapturePipeline", "processed decode returned null ($length bytes, hiRes=${spec.hiRes})")
                emitStatus(CameraStatusMessage.PHOTO_SAVE_FAILED.status())
                return
            }
            decoded = d
            // ONE createBitmap does crop AND rotate (perf review #3b): the old crop-then-rotate
            // chain materialized a ~37 MB 16:9 intermediate that existed only to be re-read by the
            // rotate. Bitmap.createBitmap(src, x, y, w, h, m, filter) applies the source rect and
            // the matrix in a single allocation; a null matrix is a plain crop, and the
            // no-crop/no-rotate case returns [d] itself (createBitmap may also return the source
            // when the op is an identity — the === guards below already tolerate aliasing).
            val ar = spec.aspectRatio
            val degrees = spec.rotationDegrees % 360
            val r = if (ar == AspectRatio.W4_3 && degrees == 0) { // full sensor, upright: no-op
                d
            } else {
                val (x, y, cropW, cropH) = if (ar != AspectRatio.W4_3) {
                    centerCropBox(d.width, d.height, ar.w, ar.h)
                } else {
                    CropBox(0, 0, d.width, d.height)
                }
                val m = if (degrees != 0) Matrix().apply { postRotate(degrees.toFloat()) } else null
                Bitmap.createBitmap(d, x, y, cropW, cropH, m, true)
            }
            rotated = r
            // Release the decode the ENCODERS never read as soon as [rotated] exists (perf review
            // #3a): holding decoded (~50 MB ARGB) through the whole 1-3 s HEIF+JPEG encode kept a
            // ~127-152 MB working set live per shot and inflated the ART heap target — the most
            // plausible owner of the soak-observed Java growth. The finally block stays as the
            // safety net for every earlier-exit path.
            if (r !== d) {
                d.recycle()
                decoded = null
            }
            writeProcessedStillFormats(
                wantHeif = wantHeif,
                wantJpeg = wantJpeg,
                composeExif = { uprightStillExif(r, exifShot) },
                writeHeif = { exif -> writeProcessedHeif(r, spec, exif) },
                writeJpeg = { exif -> writeProcessedJpeg(r, spec, exif) },
                onHeifFailure = {
                    Log.e("StillCapturePipeline", "HEIF save failed", it)
                    emitStatus(CameraStatusMessage.HEIF_SAVE_FAILED.status())
                },
                onJpegFailure = {
                    Log.e("StillCapturePipeline", "JPEG save failed", it)
                    emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status())
                },
            )
        } catch (t: Throwable) {
            if (t is ThreadDeath || t is VirtualMachineError && t !is OutOfMemoryError) throw t
            Log.e("StillCapturePipeline", "Photo processing failed", t)
            emitStatus(CameraStatusMessage.PHOTO_SAVE_FAILED.status())
        } finally {
            val rr = rotated
            val dd = decoded
            if (rr != null && rr !== dd) rr.recycle()
            dd?.recycle()
        }
    }

    /**
     * HEIF encode of the shared rotated bitmap. A fully written artifact is marked complete before
     * publication; persistent provider failure leaves it pending for launch recovery rather than
     * deleting a valuable take.
     */
    private fun writeProcessedHeif(rotated: Bitmap, spec: ShotSpec, exifData: ByteArray?) {
        // EXIF is best-effort here exactly as in the JPEG and passthrough lanes: the payload is
        // composed through a cache temp file, a 1×1 encode and an ExifInterface save, so a full
        // cache or an I/O hiccup used to abort the whole save (HEIF_SAVE_FAILED, frame lost). The
        // pixels are already rotated, so a HEIF without EXIF is still an upright, valid photo; a
        // null [exifData] is that best-effort miss, composed once per shot by the caller.
        val allocation = MediaStoreWriter.createPendingImageAllocation(
            context,
            spec.familyKey.displayName("heic"),
            "image/heic",
        )
        if (allocation == null) { emitStatus(CameraStatusMessage.HEIF_SAVE_FAILED.status()); return }
        val u = allocation.uri
        // The Setup quality slider governs BOTH still containers (it used to silently apply only
        // to JPEG, leaving the DEFAULT photo format pinned at the encoder's 95).
        val quality = spec.jpegQuality
        val wrote = runCatching {
            MediaStoreWriter.openParcelFd(context, u, "rw")?.use { pfd ->
                HeifCapture.writeHeif(pfd.fileDescriptor, rotated, quality, exifData); true
            } ?: false
        }.getOrElse { failure -> discardRejectedOutput(allocation); throw failure }
        if (!wrote) {
            discardRejectedOutput(allocation)
            emitStatus(CameraStatusMessage.HEIF_SAVE_FAILED.status())
            return
        }
        val completion = MediaStoreWriter.markWriteComplete(context, u)
        completeStillPublication(
            kind = "HEIF",
            output = allocation,
            captureId = spec.captureId,
            markerDurable = completion.durable,
            effects = publicationEffects(spec.familyKey),
        )
    }

    /**
     * JPEG re-encode of the SAME rotated bitmap the HEIF got, so the two frame identically.
     * Same publish-or-delete policy as [writeProcessedHeif].
     *
     * `Bitmap.compress` strips all metadata, so the EXIF goes back in — but into the ENCODED
     * BUFFER, before the pending row sees a byte (AGG4-6 / AGG3-24): see [exifSplicePlan] for why the
     * old in-place `saveAttributes()` rewrite of the row could leave a corrupt file recovery adopts.
     * The APP1 is the SAME payload the HEIF lane got — composed once per shot
     * ([writeProcessedStillFormats], AGG5-35) — so both formats carry one tag set by construction.
     *
     * The encode lands in a pre-sized [EncodedJpegBuffer] that is spliced IN PLACE (AGG5-34): a
     * default 32-byte stream grew by ~19 doublings and then `toByteArray()` copied the whole JPEG
     * again, ~3× the encoded size live beside the ~50 MB rotated bitmap on every BURST frame.
     */
    private fun writeProcessedJpeg(rotated: Bitmap, spec: ShotSpec, exifPayload: ByteArray?) {
        val encoded = EncodedJpegBuffer(processedJpegInitialCapacity(rotated.width, rotated.height))
        if (!rotated.compress(Bitmap.CompressFormat.JPEG, spec.jpegQuality, encoded)) {
            Log.e("StillCapturePipeline", "JPEG encode returned false (${rotated.width}x${rotated.height})")
            emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status())
            return
        }
        writeSingleJpeg(encoded.buffer(), encoded.size(), exifPayload, passthroughOrientation = null, spec = spec)
    }

    /**
     * The EXIF APP1 for a pixel-upright processed still (HEIF and processed JPEG alike): orientation
     * NORMAL, no source EXIF, best-effort — null (logged) rather than a lost image.
     */
    private fun uprightStillExif(rotated: Bitmap, exifShot: ExifShot): ByteArray? =
        bestEffortHeifExif(
            build = {
                composeStillExifApp1(
                    context.cacheDir,
                    exifShot,
                    rotated.width,
                    rotated.height,
                    RotationMath.ORIENTATION_NORMAL,
                    sourceExifApp1 = null,
                )
            },
            onFailure = { failure ->
                Log.w("StillCapturePipeline", "processed still EXIF payload failed; saving without EXIF", failure)
            },
        )

    /**
     * The one write both JPEG lanes share: allocate, write the first [length] bytes of [encoded]
     * with [exifPayload] spliced in ([writeJpegOnce]), mark COMPLETE, publish. The row is opened for
     * writing exactly once and never reopened "rw".
     */
    private fun writeSingleJpeg(
        encoded: ByteArray,
        length: Int,
        exifPayload: ByteArray?,
        passthroughOrientation: Int?,
        spec: ShotSpec,
    ) {
        val allocation = MediaStoreWriter.createPendingImageAllocation(
            context,
            spec.familyKey.displayName("jpg"),
            "image/jpeg",
        )
        if (allocation == null) { emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status()); return }
        val u = allocation.uri
        val wrote = runCatching {
            writeJpegOnce(
                open = { MediaStoreWriter.openOutputStream(context, u) },
                encoded = encoded,
                length = length,
                exifPayload = exifPayload,
                passthroughOrientation = passthroughOrientation,
                onSpliceRefused = { message -> Log.w("StillCapturePipeline", message) },
            )
        }.getOrElse { failure -> discardRejectedOutput(allocation); throw failure }
        if (!wrote) {
            discardRejectedOutput(allocation)
            emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status())
            return
        }
        val completion = MediaStoreWriter.markWriteComplete(context, u)
        completeStillPublication(
            kind = "JPEG",
            output = allocation,
            captureId = spec.captureId,
            markerDurable = completion.durable,
            effects = publicationEffects(spec.familyKey),
        )
    }

    /**
     * Hi-res JPEG lane: the HAL bytes go to disk verbatim (no decode, no crop, no pixel rotate),
     * with an EXIF APP1 carrying TAG_ORIENTATION for the full capture rotation — the DNG approach,
     * because at ~200MP the ordinary pixel-upright pass is a guaranteed OOM. The HAL's own EXIF is
     * the composer's seed, so its tags survive under ours exactly as the old in-place
     * `saveAttributes()` merge kept them — but the merged APP1 is spliced in before the single write
     * (AGG4-6). Same publish-or-delete policy as [writeProcessedJpeg].
     */
    private fun writePassthroughJpeg(bytes: ByteArray, spec: ShotSpec, exifShot: ExifShot) {
        // Best-effort like the processed lane — a failed EXIF build must never lose the image. But
        // the HAL bytes carry the HAL's OWN APP1, so "without our EXIF" must never mean "with
        // theirs" (AGG6-14): writeJpegOnce then strips it and writes an orientation-only APP1.
        val exifPayload = bestEffortHeifExif(
            build = { composePassthroughStillExif(context.cacheDir, bytes, exifShot, spec.rotationDegrees) },
            onFailure = { failure ->
                Log.w(
                    "StillCapturePipeline",
                    "passthrough JPEG EXIF payload failed; saving with orientation-only EXIF",
                    failure,
                )
            },
        )
        writeSingleJpeg(
            bytes,
            bytes.size,
            exifPayload,
            passthroughOrientation = RotationMath.exifOrientationFor(spec.rotationDegrees),
            spec = spec,
        )
    }

    /**
     * Writes RAW synchronously while [raw] is live and attempts the bounded durable marker. The
     * returned publication carries that outcome to [publishDng]; interrupted writes are deleted.
     */
    fun saveDng(
        raw: Image,
        chars: CameraCharacteristics,
        result: TotalCaptureResult,
        spec: ShotSpec,
        allocation: PendingOutputAllocation,
    ): DngWriteResult {
        require(allocation.familyKey == spec.familyKey) {
            "DNG allocation family does not match the captured shot"
        }
        val uri = allocation.uri
        var outputComplete = false
        try {
            val out = MediaStoreWriter.openOutputStream(context, uri)
                ?: throw IllegalStateException("Failed to open output stream")
            out.use {
                DngCapture.writeDng(it, raw, chars, result, exifOrientationFor(spec.rotationDegrees))
            }
            outputComplete = true
            val completion = MediaStoreWriter.markWriteComplete(context, uri)
            return DngWriteResult.Complete(
                PendingDngPublication(
                    allocation = allocation,
                    captureId = spec.captureId,
                    familyKey = spec.familyKey,
                    completionMarkerDurable = completion.durable,
                ),
            )
        } catch (t: Throwable) {
            // A fully-written DNG is handed to the publication lane with its marker outcome.
            // Interrupted writes remain REGISTERED and are deleted; marker exhaustion is returned.
            return if (!outputComplete) {
                DngWriteResult.Rejected(allocation, t)
            } else {
                DngWriteResult.Failed(
                    PendingDngPublication(
                        allocation = allocation,
                        captureId = spec.captureId,
                        familyKey = spec.familyKey,
                        // The marker attempt is what threw, so durability is unproven.
                        completionMarkerDurable = false,
                    ),
                    t,
                )
            }
        }
    }

    /** Publishes a completed DNG off the camera thread, including retry backoff and callbacks. */
    fun publishDng(pending: PendingDngPublication) {
        completeStillPublication(
            kind = "DNG",
            output = pending.allocation,
            captureId = pending.captureId,
            markerDurable = pending.completionMarkerDurable,
            effects = publicationEffects(pending.familyKey, emitSaved = emitRawSaved),
        )
    }

    private fun publicationEffects(
        familyKey: CaptureFamilyKey,
        emitSaved: (android.net.Uri, Int) -> Unit = emitMediaSaved,
    ) = StillPublicationEffects<PendingOutputAllocation>(
        withFamilyPublicationAuthority = { deleted, unavailable, live ->
            MediaStoreWriter.withFamilyPublicationAuthority(
                context = context,
                family = familyKey,
                deleted = deleted,
                unavailable = unavailable,
                live = live,
            )
        },
        discardDeletedFamily = { output -> MediaStoreWriter.discardPendingOutput(context, output) },
        publishOwned = { output, captureId ->
            publishStillOutput(output, captureId) { MediaStoreWriter.publish(context, output.uri) }
        },
        finishPublished = finishPublishedStill,
        emitSaved = { output, captureId -> emitSaved(output.uri, captureId) },
        emitRetained = emitPublishRetained,
        emitStatus = emitStatus,
    )

    /** Maps a clockwise rotation (0/90/180/270) to the matching EXIF/TIFF orientation tag for DNG. */
    private fun exifOrientationFor(degrees: Int): Int = RotationMath.exifOrientationFor(degrees)
}

/** Injectable still-caller effects for the durable-before-publication contract. */
internal data class StillPublicationEffects<T>(
    /**
     * Runs exactly one deleted/unavailable/live branch under the output's process-wide exact-family
     * authority. The production branch spans the provider publish and saved callback; pure tests
     * default to a live family unless they explicitly select another branch.
     */
    val withFamilyPublicationAuthority: (
        deleted: () -> StillOutputPublication,
        unavailable: () -> StillOutputPublication,
        live: () -> StillOutputPublication,
    ) -> StillOutputPublication = { _, _, live -> live() },
    val discardDeletedFamily: (T) -> PendingOutputDiscardResult = {
        PendingOutputDiscardResult.UNRESOLVED
    },
    val publishOwned: (T, Int) -> DeletedStillPublication,
    val finishPublished: (T, Int) -> Unit,
    val emitSaved: (T, Int) -> Unit,
    val emitRetained: (T, Int) -> RetainedStillDisposition,
    val emitStatus: (CameraStatus) -> Unit,
)

/** Complete still result including deleted-family ownership, distinct from video publication. */
internal enum class StillOutputPublication {
    PUBLISHED,
    RETAINED_MARKER_UNAVAILABLE,
    RETAINED_PUBLICATION_UNAVAILABLE,
    DISCARDED_DELETED_CAPTURE,
    DISCARD_RETRY_PENDING,
}

/**
 * Completes the HEIF/JPEG/DNG caller contract without conflating retained bytes with publication.
 * A non-durable COMPLETE marker bypasses [StillPublicationEffects.publishOwned] entirely.
 */
internal fun <T> completeStillPublication(
    kind: String,
    output: T,
    captureId: Int,
    markerDurable: Boolean,
    effects: StillPublicationEffects<T>,
): StillOutputPublication = effects.withFamilyPublicationAuthority(
    {
        // The family marker itself is the durable launch veto. Exact-URI DISCARD/delete remains a
        // best-effort cleanup; even its double-failure must never fall through to publication.
        effects.discardDeletedFamily(output)
        StillOutputPublication.DISCARDED_DELETED_CAPTURE
    },
    {
        // An unreadable family journal is neither LIVE nor DELETED. Preserve complete bytes
        // privately and reuse the Engine's retained-output ownership so a same-Engine tombstone
        // that landed independently can still take the exact DISCARD path.
        effects.emitStatus(retainedSaveStatus(kind, markerDurable = markerDurable))
        effects.emitRetained(output, captureId).toStillOutputPublication(
            live = if (markerDurable) {
                StillOutputPublication.RETAINED_PUBLICATION_UNAVAILABLE
            } else {
                StillOutputPublication.RETAINED_MARKER_UNAVAILABLE
            },
        )
    },
    {
        if (!markerDurable) {
            effects.emitStatus(retainedSaveStatus(kind, markerDurable = false))
            effects.emitRetained(output, captureId).toStillOutputPublication(
                live = StillOutputPublication.RETAINED_MARKER_UNAVAILABLE,
            )
        } else {
            when (effects.publishOwned(output, captureId)) {
                DeletedStillPublication.LIVE_PUBLISHED -> {
                    try {
                        effects.emitSaved(output, captureId)
                    } finally {
                        effects.finishPublished(output, captureId)
                    }
                    StillOutputPublication.PUBLISHED
                }
                DeletedStillPublication.LIVE_PUBLICATION_FAILED -> {
                    effects.emitStatus(retainedSaveStatus(kind, markerDurable = true))
                    effects.emitRetained(output, captureId).toStillOutputPublication(
                        live = StillOutputPublication.RETAINED_PUBLICATION_UNAVAILABLE,
                    )
                }
                DeletedStillPublication.DISCARD_DELETED_CAPTURE ->
                    StillOutputPublication.DISCARDED_DELETED_CAPTURE
                DeletedStillPublication.DISCARD_RETRY_PENDING -> {
                    effects.emitStatus(CameraStatusMessage.COULD_NOT_DELETE_FILE.status())
                    StillOutputPublication.DISCARD_RETRY_PENDING
                }
            }
        }
    },
)

private fun RetainedStillDisposition.toStillOutputPublication(
    live: StillOutputPublication,
): StillOutputPublication = when (this) {
    RetainedStillDisposition.RETAIN_FOR_RECOVERY -> live
    RetainedStillDisposition.DISCARD_DELETED_CAPTURE ->
        StillOutputPublication.DISCARDED_DELETED_CAPTURE
    RetainedStillDisposition.DISCARD_RETRY_PENDING ->
        StillOutputPublication.DISCARD_RETRY_PENDING
}

/** Truthful retained-take status for either durable gate that left a completed artifact private. */
internal fun retainedSaveStatus(kind: String, markerDurable: Boolean): CameraStatus =
    (if (markerDurable) CameraStatusMessage.OUTPUT_SAVED_PENDING
    else CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY)
        .status(CameraStatusArgument.Text(kind))

/**
 * The complete tag→value list one [ExifShot] stamps into a processed still, hoisted out of the
 * ExifInterface apply loop (the [heifExifDimensionAttributes] precedent) so the APEX/rational math
 * pinned against the stock camera's 3× reference sample is host-testable. Pure java.* + TAG_*
 * String constants only; [composeStillExifApp1] replays it verbatim.
 */
internal fun exifAttributeList(shot: ExifShot): List<Pair<String, String>> = buildList {
    if (shot.iso > 0) add(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to shot.iso.toString())
    if (shot.expNs > 0) {
        val sec = shot.expNs / 1_000_000_000.0
        add(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME to sec.toString())
        // APEX shutter speed = -log2(t), rational, matching the stock sample (6.908 at 1/120).
        val apex = -Math.log(sec) / Math.log(2.0)
        add(
            androidx.exifinterface.media.ExifInterface.TAG_SHUTTER_SPEED_VALUE to
                "${Math.round(apex * 1000)}/1000",
        )
    }
    if (shot.lensApertureF > 0f) {
        add(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER to shot.lensApertureF.toString())
        // APEX aperture = 2·log2(F) (stock: 2.35 at f/2.2).
        val apexAv = 2.0 * Math.log(shot.lensApertureF.toDouble()) / Math.log(2.0)
        add(
            androidx.exifinterface.media.ExifInterface.TAG_APERTURE_VALUE to
                "${Math.round(apexAv * 100)}/100",
        )
        add(
            androidx.exifinterface.media.ExifInterface.TAG_MAX_APERTURE_VALUE to
                "${Math.round(apexAv * 100)}/100",
        )
    }
    if (shot.lensFocalMm > 0f) {
        // Real lens focal (20.1 mm on the 3×), rational millimeters like the stock sample.
        add(
            androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH to
                "${Math.round(shot.lensFocalMm * 1000)}/1000",
        )
    }
    if (shot.focal35mm > 0) {
        add(
            androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to
                shot.focal35mm.toString(),
        )
    }
    add(
        androidx.exifinterface.media.ExifInterface.TAG_DIGITAL_ZOOM_RATIO to
            "${Math.round(shot.digitalZoom * 10000)}/10000",
    )
    // EV bias in sixths, the stock sample's denominator (0/6).
    add(
        androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_BIAS_VALUE to
            "${Math.round(shot.evBiasStops * 6)}/6",
    )
    add(
        androidx.exifinterface.media.ExifInterface.TAG_METERING_MODE to
            when (shot.meteringMode) {
                MeteringMode.MATRIX -> "5" // pattern
                MeteringMode.CENTER -> "2" // center-weighted (the stock default)
                MeteringMode.SPOT -> "3"
            },
    )
    // 0x1 = fired; 0x10 = "did not fire, compulsory off" (the stock sample's value).
    add(androidx.exifinterface.media.ExifInterface.TAG_FLASH to if (shot.flashFired) "1" else "16")
    add(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_PROGRAM to shot.exposureProgram.toString())
    add(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_MODE to if (shot.manualExposure) "1" else "0")
    add(androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE to if (shot.manualWb) "1" else "0")
    add(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL to shot.lensModel)
    add(androidx.exifinterface.media.ExifInterface.TAG_COLOR_SPACE to "1") // sRGB

    val dt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(shot.takenAtMs))
    // EXIF 2.31 OffsetTime is always "±HH:MM". ISO pattern `XXX` prints "Z" for UTC+0 (AGG4-22),
    // which strict readers reject; java.time's `xxx` is the same field without the Z special case
    // (java.text.SimpleDateFormat has no `x` letter). The zone is the same default zone [dt] uses,
    // resolved at the same instant, so the pair can never disagree.
    val offset = java.time.format.DateTimeFormatter.ofPattern("xxx", java.util.Locale.US)
        .format(java.time.Instant.ofEpochMilli(shot.takenAtMs).atZone(java.time.ZoneId.systemDefault()))
    add(androidx.exifinterface.media.ExifInterface.TAG_DATETIME to dt)
    add(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL to dt)
    add(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED to dt)
    add(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME to offset)
    add(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL to offset)
    // Pixels are rotated upright before encode — the orientation tag must say NORMAL,
    // not the invalid 0 exifinterface leaves when the tag was never present.
    add(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION to "1")
    // From the running build, not a literal: these were "OPPO" / "OPPO Find X9 Ultra", which wrote a
    // false camera model into every file taken on any other handset. TAG_MODEL is the model
    // IDENTIFIER by definition — photo software resolves the marketing name from it — so imitating
    // the stock app's market name here was both wrong off-device and wrong in principle.
    shot.deviceMake?.let { add(androidx.exifinterface.media.ExifInterface.TAG_MAKE to it) }
    shot.deviceModel?.let { add(androidx.exifinterface.media.ExifInterface.TAG_MODEL to it) }
}

/**
 * The ONE EXIF APP1 composer for every processed still (HEIF payload and both JPEG lanes), so ISO /
 * exposure / 35 mm focal / make / model stay in parity across formats by construction.
 *
 * ExifInterface can only SERIALIZE through a file, so the attributes are applied to a cache-only
 * scratch JPEG and its APP1 body is extracted; no user media is ever opened here. The scratch is a
 * 1×1 seed, optionally carrying [sourceExifApp1] (the hi-res passthrough lane's HAL EXIF) so the
 * source's own tags survive underneath [exifAttributeList]. [orientation] is applied last, and the
 * seed's 1×1 dimensions are replaced with the real [width]×[height] ([heifExifDimensionAttributes])
 * — otherwise MediaStore indexes a full-resolution still as 1×1.
 */
internal fun composeStillExifApp1(
    cacheDir: File,
    shot: ExifShot,
    width: Int,
    height: Int,
    orientation: Int,
    sourceExifApp1: ByteArray?,
): ByteArray {
    val dimensions = heifExifDimensionAttributes(width, height)
    val seedStream = java.io.ByteArrayOutputStream()
    val seedBitmap = createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    try {
        check(seedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, seedStream)) { "EXIF seed encode failed" }
    } finally {
        seedBitmap.recycle()
    }
    val seed = sourceExifApp1?.let { source ->
        spliceExifApp1(seedStream.toByteArray(), source) ?: error("source EXIF APP1 cannot seed the composer")
    } ?: seedStream.toByteArray()
    val temp = File.createTempFile("still-exif-", ".jpg", cacheDir)
    return try {
        FileOutputStream(temp).use { it.write(seed) }
        val exif = androidx.exifinterface.media.ExifInterface(temp)
        // A HAL's own APP1 is not ours to republish wholesale (AGG5-51): the app has no location
        // permission and never requests JPEG_GPS_LOCATION, yet a vendor or non-conforming HAL can
        // still embed cached GPS, a body/lens serial, an owner name, a unique id, or a MakerNote with
        // device identifiers. Blank them before our tags go on; a null value removes the tag.
        if (sourceExifApp1 != null) {
            PASSTHROUGH_PRIVACY_STRIPPED_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
        }
        exifAttributeList(shot).forEach { (tag, value) -> exif.setAttribute(tag, value) }
        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, orientation.toString())
        dimensions.forEach { (tag, value) -> exif.setAttribute(tag, value) }
        exif.saveAttributes()
        extractExifApp1(temp.readBytes()) ?: error("EXIF APP1 payload missing")
    } finally {
        // App-private cache scratch only; failure to remove it is harmless and never touches
        // user media.
        runCatching { temp.delete() }
    }
}

/**
 * The processed-still orchestration (AGG5-35, TE5-7): the shot's upright EXIF APP1 is composed AT
 * MOST ONCE — lazily, on the first lane that wants it — and the identical payload feeds both
 * formats. Each lane composed its own before, so a HEIF+JPEG shot paid two cache temp files and two
 * ExifInterface parse/save passes on the serial `ioExecutor` for byte-identical output, delaying
 * the JPEG publication and the next timelapse tick. Each lane keeps its own failure isolation: a
 * HEIF write error must not cost the JPEG. [composeExif] is the caller's best-effort composer
 * (null = no EXIF, never a lost image).
 */
internal fun writeProcessedStillFormats(
    wantHeif: Boolean,
    wantJpeg: Boolean,
    composeExif: () -> ByteArray?,
    writeHeif: (ByteArray?) -> Unit,
    writeJpeg: (ByteArray?) -> Unit,
    onHeifFailure: (Throwable) -> Unit,
    onJpegFailure: (Throwable) -> Unit,
) {
    val exif by lazy(LazyThreadSafetyMode.NONE, composeExif)
    if (wantHeif) runCatching { writeHeif(exif) }.onFailure(onHeifFailure)
    if (wantJpeg) runCatching { writeJpeg(exif) }.onFailure(onJpegFailure)
}

/**
 * Writes the first [length] bytes of [encoded] through ONE [open] (AGG4-6, TE5-7): SOI + an APP1
 * carrying [exifPayload] + every non-Exif header segment and the scan, in a single pass — or the
 * bytes verbatim when there is no payload or it cannot be spliced ([onSpliceRefused] reports why;
 * never a lost image). False when [open] yields no stream. There is no second open and no in-place
 * rewrite: that `saveAttributes()` rewrite is what once left a shifted body recovery adopted.
 *
 * A non-null [passthroughOrientation] marks the HAL passthrough lane, whose [encoded] header is the
 * HAL's, and makes the privacy strip a property of the BYTES WRITTEN rather than of the composer
 * (AGG6-14): the header keeps only allow-listed segments ([exifSplicePlan] `privacyStrip`), and a
 * missing or unspliceable payload is replaced by [minimalOrientationExifApp1] instead of falling
 * back to the verbatim HAL APP1 (GPS, serials, MakerNote, XMP). A header that cannot be walked
 * cannot be proven free of that APP1, so it is refused (false) rather than written verbatim.
 */
internal fun writeJpegOnce(
    open: () -> java.io.OutputStream?,
    encoded: ByteArray,
    length: Int,
    exifPayload: ByteArray?,
    passthroughOrientation: Int?,
    onSpliceRefused: (String) -> Unit,
): Boolean {
    val privacyStrip = passthroughOrientation != null
    val plan = if (exifPayload != null || privacyStrip) exifSplicePlan(encoded, length, privacyStrip) else null
    val composed = exifPayload?.let { payload ->
        plan?.takeIf { isSpliceableExifPayload(payload) }?.let { it to payload }
            ?: run {
                onSpliceRefused(
                    "JPEG EXIF splice refused ($length B image, ${payload.size} B APP1); saving without EXIF",
                )
                null
            }
    }
    val splice = composed ?: passthroughOrientation?.let { orientation ->
        if (plan == null) {
            onSpliceRefused("passthrough JPEG header unwalkable ($length B); refusing to write HAL metadata")
            return false
        }
        plan to minimalOrientationExifApp1(orientation)
    }
    return open()?.use { out ->
        if (splice != null) {
            writeJpegWithExifApp1(out, encoded, splice.first, splice.second)
        } else {
            out.write(encoded, 0, length)
        }
        true
    } ?: false
}

/**
 * A [java.io.ByteArrayOutputStream] whose filled prefix is read IN PLACE ([buffer] + [size]) rather
 * than through a `toByteArray()` copy (AGG5-34). [buffer] is the live backing array: valid only
 * until the next write, and only its first [size] bytes are the JPEG.
 */
internal class EncodedJpegBuffer(initialCapacity: Int) : java.io.ByteArrayOutputStream(initialCapacity) {
    fun buffer(): ByteArray = buf
}

/**
 * Initial capacity for a processed still's JPEG encode: half a byte per pixel covers typical q90-q97
 * output in at most one doubling (the [StillSnapshot] YUV repack pre-sizes for the same reason),
 * floored for tiny frames and capped below the array limit.
 */
internal fun processedJpegInitialCapacity(width: Int, height: Int): Int =
    (width.toLong() * height / 2).coerceIn(MIN_JPEG_ENCODE_CAPACITY.toLong(), MAX_JPEG_ENCODE_CAPACITY.toLong()).toInt()

private const val MIN_JPEG_ENCODE_CAPACITY = 64 * 1024
private const val MAX_JPEG_ENCODE_CAPACITY = Int.MAX_VALUE - 8

/**
 * The passthrough lane's EXIF build (TE5-7): the HAL's own APP1 is the composer's seed — so its
 * tags survive under ours — at the real frame bounds, with the capture rotation as the orientation
 * TAG (the passthrough never pixel-rotates). Degrades in tiers via [composePassthroughExifApp1].
 */
internal fun composePassthroughStillExif(
    cacheDir: File,
    bytes: ByteArray,
    shot: ExifShot,
    rotationDegrees: Int,
): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return composePassthroughExifApp1(extractExifApp1(bytes)) { source ->
        composeStillExifApp1(
            cacheDir,
            shot,
            bounds.outWidth,
            bounds.outHeight,
            RotationMath.exifOrientationFor(rotationDegrees),
            sourceExifApp1 = source,
        )
    }
}

/**
 * Identifying tags a HAL-seeded composition never republishes (AGG5-51): the whole GPS directory,
 * the body/lens serials, the owner name, the unique image id, the opaque MakerNote, and (AGG6-14)
 * the embedded XMP packet plus every free-text tag. Only the
 * hi-res passthrough lane seeds from a HAL APP1 (capability-gated, dormant on PMA110); every other
 * lane composes from a blank 1×1 seed and carries none of these to begin with.
 */
internal val PASSTHROUGH_PRIVACY_STRIPPED_TAGS: List<String> = listOf(
    ExifInterface.TAG_GPS_VERSION_ID,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_SATELLITES,
    ExifInterface.TAG_GPS_STATUS,
    ExifInterface.TAG_GPS_MEASURE_MODE,
    ExifInterface.TAG_GPS_DOP,
    ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_SPEED,
    ExifInterface.TAG_GPS_TRACK_REF,
    ExifInterface.TAG_GPS_TRACK,
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_IMG_DIRECTION,
    ExifInterface.TAG_GPS_MAP_DATUM,
    ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LATITUDE,
    ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LONGITUDE,
    ExifInterface.TAG_GPS_DEST_BEARING_REF,
    ExifInterface.TAG_GPS_DEST_BEARING,
    ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
    ExifInterface.TAG_GPS_DEST_DISTANCE,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_GPS_AREA_INFORMATION,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_DIFFERENTIAL,
    ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    ExifInterface.TAG_BODY_SERIAL_NUMBER,
    ExifInterface.TAG_LENS_SERIAL_NUMBER,
    ExifInterface.TAG_CAMERA_OWNER_NAME,
    ExifInterface.TAG_IMAGE_UNIQUE_ID,
    ExifInterface.TAG_MAKER_NOTE,
    // Free text and embedded XMP (AGG6-14): a vendor HAL can write a location, a device id or an
    // owner into any of them, and IFD0's XMP packet can carry `exif:GPS*` outright.
    ExifInterface.TAG_XMP,
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_USER_COMMENT,
    ExifInterface.TAG_IMAGE_DESCRIPTION,
)

/**
 * The passthrough lane's EXIF, degraded in tiers so the one tag a viewer NEEDS — Orientation, the
 * passthrough's only rotation — survives (MRG4-9). A HAL EXIF whose IFD1 thumbnail sits near the
 * 64 KiB APP1 cap grows past it once our tags are added; the composed payload was then refused at the
 * splice and the hi-res JPEG saved with NO EXIF, i.e. sideways. Tiers: the full HAL EXIF under ours;
 * the same without its thumbnail directory ([exifApp1WithoutThumbnailIfd]); ours alone. A tier that
 * throws (ExifInterface refuses an oversized APP1) or yields an unspliceable payload falls through;
 * the last tier's failure propagates to the caller's best-effort handler.
 */
internal fun composePassthroughExifApp1(
    sourceExifApp1: ByteArray?,
    compose: (source: ByteArray?) -> ByteArray,
): ByteArray {
    val sources = buildList {
        if (sourceExifApp1 != null) {
            add(sourceExifApp1)
            exifApp1WithoutThumbnailIfd(sourceExifApp1)?.let(::add)
        }
    }
    for (source in sources) {
        val payload = try {
            compose(source)
        } catch (tierFailure: Exception) {
            // Fall through to the next, smaller tier; only the last tier's failure is reported.
            continue
        }
        if (isSpliceableExifPayload(payload)) return payload
    }
    return compose(null)
}

/**
 * HEIF EXIF is metadata, not the photograph: a payload that cannot be composed (full cache, I/O
 * hiccup in the 1×1 seed or ExifInterface save) reports once and yields null so the HEIF is still
 * written — the JPEG and passthrough lanes already treat EXIF this way.
 */
internal fun bestEffortHeifExif(
    build: () -> ByteArray,
    onFailure: (Exception) -> Unit,
): ByteArray? = try {
    build()
} catch (failure: Exception) {
    onFailure(failure)
    null
}
