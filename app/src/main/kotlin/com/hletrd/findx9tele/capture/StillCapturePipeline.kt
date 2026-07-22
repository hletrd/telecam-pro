package com.hletrd.findx9tele.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.CameraCaps
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.RotationMath
import com.hletrd.findx9tele.camera.TeleSelection
import com.hletrd.findx9tele.camera.centerCropBox
import com.hletrd.findx9tele.storage.CaptureFamilyKey
import com.hletrd.findx9tele.storage.MediaStoreWriter

/** Immutable request-time state consumed by every output belonging to one shutter press. */
internal data class ShotSpec(
    val controls: ManualControls,
    val caps: CameraCaps?,
    val selection: TeleSelection?,
    val teleconverter: Boolean,
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
    val takenAtMs: Long,
)

/** A fully written DNG awaiting MediaStore publication on the I/O lane. */
internal data class PendingDngPublication(
    val uri: android.net.Uri,
    val captureId: Int,
    val completionMarkerDurable: Boolean,
)

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
    private val emitStatus: (String) -> Unit,
    private val emitMediaSaved: (android.net.Uri, Int) -> Unit,
    private val emitRawSaved: (android.net.Uri, Int) -> Unit,
) {

    /**
     * ONE decode → center-crop to [ShotSpec.aspectRatio] (processed stills only; [saveDng]'s RAW
     * output always stays full-frame) → rotate pass feeding BOTH processed encoders (PERF4-5): the
     * old per-format lanes each decoded/cropped/rotated the SAME bytes into a ~50 MB ARGB
     * intermediate, so a HEIF+JPEG shot paid the whole pixel pipeline twice serially on
     * [ioExecutor]. Each encoder keeps its own failure isolation (a HEIF write error must not cost
     * the JPEG) and the publish-or-delete policy documented on [writeProcessedHeif].
     */
    fun saveProcessedStills(
        bytes: ByteArray,
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
                runCatching { writePassthroughJpeg(bytes, spec, exifShot) }
                    .onFailure {
                        Log.e("StillCapturePipeline", "JPEG save failed", it)
                        emitStatus("JPEG save failed")
                    }
            }
            return
        }
        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (d == null) { emitStatus("Photo save failed"); return }
            decoded = d
            val ar = spec.aspectRatio
            val base = if (ar != AspectRatio.W4_3) { // W4_3 = full sensor, no crop needed
                val c = centerCrop(d, ar.w, ar.h)
                cropped = c
                c
            } else d
            val r = rotateBitmap(base, spec.rotationDegrees)
            rotated = r
            if (wantHeif) runCatching { writeProcessedHeif(r, spec, exifShot) }
                .onFailure {
                    Log.e("StillCapturePipeline", "HEIF save failed", it)
                    emitStatus("HEIF save failed")
                }
            if (wantJpeg) runCatching { writeProcessedJpeg(r, spec, exifShot) }
                .onFailure {
                    Log.e("StillCapturePipeline", "JPEG save failed", it)
                    emitStatus("JPEG save failed")
                }
        } catch (t: Throwable) {
            if (t is ThreadDeath || t is VirtualMachineError && t !is OutOfMemoryError) throw t
            Log.e("StillCapturePipeline", "Photo processing failed", t)
            emitStatus("Photo save failed")
        } finally {
            val rr = rotated
            val cc = cropped
            val dd = decoded
            if (rr != null && rr !== cc && rr !== dd) rr.recycle()
            if (cc != null && cc !== dd) cc.recycle()
            dd?.recycle()
        }
    }

    /**
     * HEIF encode of the shared rotated bitmap. A fully written artifact is marked complete before
     * publication; persistent provider failure leaves it pending for launch recovery rather than
     * deleting a valuable take.
     */
    private fun writeProcessedHeif(rotated: Bitmap, spec: ShotSpec, exifShot: ExifShot) {
        val exifData = buildHeifExifData(exifShot, rotated.width, rotated.height)
        val u = MediaStoreWriter.createPendingImage(
            context,
            spec.familyKey.displayName("heic"),
            "image/heic",
        )
        if (u == null) { emitStatus("HEIF save failed"); return }
        // The Setup quality slider governs BOTH still containers (it used to silently apply only
        // to JPEG, leaving the DEFAULT photo format pinned at the encoder's 95).
        val quality = spec.jpegQuality
        val wrote = runCatching {
            MediaStoreWriter.openParcelFd(context, u, "rw")?.use { pfd ->
                HeifCapture.writeHeif(pfd.fileDescriptor, rotated, quality, exifData); true
            } ?: false
        }.getOrElse { failure -> MediaStoreWriter.delete(context, u); throw failure }
        if (!wrote) { MediaStoreWriter.delete(context, u); emitStatus("HEIF save failed"); return }
        val completion = MediaStoreWriter.markWriteComplete(context, u)
        if (!MediaStoreWriter.publish(context, u)) {
            emitStatus(retainedSaveStatus("HEIF", completion.durable))
            return
        }
        emitMediaSaved(u, spec.captureId)
    }

    /**
     * JPEG re-encode of the SAME rotated bitmap the HEIF got, so the two frame identically.
     * Same publish-or-delete policy as [writeProcessedHeif].
     */
    private fun writeProcessedJpeg(rotated: Bitmap, spec: ShotSpec, exifShot: ExifShot) {
        val u = MediaStoreWriter.createPendingImage(
            context,
            spec.familyKey.displayName("jpg"),
            "image/jpeg",
        )
        if (u == null) { emitStatus("JPEG save failed"); return }
        val quality = spec.jpegQuality
        val wrote = runCatching {
            MediaStoreWriter.openOutputStream(context, u)?.use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
            } ?: false
        }.getOrElse { failure -> MediaStoreWriter.delete(context, u); throw failure }
        if (!wrote) { MediaStoreWriter.delete(context, u); emitStatus("JPEG save failed"); return }
        // Bitmap.compress strips all metadata, so stamp the exposure EXIF back before publishing
        // (best-effort — a failed EXIF write must never lose the image itself).
        runCatching { writeJpegExif(u, exifShot) }
        val completion = MediaStoreWriter.markWriteComplete(context, u)
        if (!MediaStoreWriter.publish(context, u)) {
            emitStatus(retainedSaveStatus("JPEG", completion.durable))
            return
        }
        emitMediaSaved(u, spec.captureId)
    }

    /**
     * Hi-res JPEG lane: the HAL bytes go to disk verbatim (no decode, no crop, no pixel rotate),
     * then EXIF is stamped with TAG_ORIENTATION carrying the full capture rotation — the DNG
     * approach, because at ~200MP the ordinary pixel-upright pass is a guaranteed OOM. Same
     * publish-or-delete policy as [writeProcessedJpeg].
     */
    private fun writePassthroughJpeg(bytes: ByteArray, spec: ShotSpec, exifShot: ExifShot) {
        val u = MediaStoreWriter.createPendingImage(
            context,
            spec.familyKey.displayName("jpg"),
            "image/jpeg",
        )
        if (u == null) { emitStatus("JPEG save failed"); return }
        val wrote = runCatching {
            MediaStoreWriter.openOutputStream(context, u)?.use { out ->
                out.write(bytes)
                true
            } ?: false
        }.getOrElse { failure -> MediaStoreWriter.delete(context, u); throw failure }
        if (!wrote) { MediaStoreWriter.delete(context, u); emitStatus("JPEG save failed"); return }
        // Best-effort like the processed lane — a failed EXIF write must never lose the image. The
        // orientation tag is the one exception a viewer NEEDS for uprightness, but a passthrough
        // with EXIF missing still beats a deleted take.
        runCatching { writeJpegExif(u, exifShot, exifOrientationFor(spec.rotationDegrees)) }
        val completion = MediaStoreWriter.markWriteComplete(context, u)
        if (!MediaStoreWriter.publish(context, u)) {
            emitStatus(retainedSaveStatus("JPEG", completion.durable))
            return
        }
        emitMediaSaved(u, spec.captureId)
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
    ): PendingDngPublication {
        val uri = MediaStoreWriter.createPendingImage(
            context,
            spec.familyKey.displayName("dng"),
            "image/x-adobe-dng",
        )
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        var outputComplete = false
        try {
            val out = MediaStoreWriter.openOutputStream(context, uri)
                ?: throw IllegalStateException("Failed to open output stream")
            out.use {
                DngCapture.writeDng(it, raw, chars, result, exifOrientationFor(spec.rotationDegrees))
            }
            outputComplete = true
            val completion = MediaStoreWriter.markWriteComplete(context, uri)
            return PendingDngPublication(
                uri = uri,
                captureId = spec.captureId,
                completionMarkerDurable = completion.durable,
            )
        } catch (t: Throwable) {
            // A fully-written DNG is journalled COMPLETE and handed to the publication lane.
            // Interrupted writes remain REGISTERED and are deleted.
            if (!outputComplete) MediaStoreWriter.delete(context, uri)
            throw t
        }
    }

    /** Publishes a completed DNG off the camera thread, including retry backoff and callbacks. */
    fun publishDng(pending: PendingDngPublication) {
        if (!MediaStoreWriter.publish(context, pending.uri)) {
            emitStatus(retainedSaveStatus("DNG", pending.completionMarkerDurable))
            return
        }
        emitRawSaved(pending.uri, pending.captureId)
    }

    private fun retainedSaveStatus(kind: String, markerDurable: Boolean): String =
        if (markerDurable) "$kind save delayed. Will retry."
        else "$kind save retained. Recovery marker failed."

    private fun writeJpegExif(
        uri: android.net.Uri,
        shot: ExifShot,
        // NORMAL for the processed lane (pixels are rotated upright before encode); the hi-res
        // passthrough lane overrides with the capture rotation's tag, DNG-style.
        orientation: Int = RotationMath.ORIENTATION_NORMAL,
    ) {
        MediaStoreWriter.openParcelFd(context, uri, "rw")?.use { pfd ->
            val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
            applyExifAttributes(exif, shot)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                orientation.toString(),
            )
            exif.saveAttributes()
        }
    }

    private fun buildHeifExifData(shot: ExifShot, width: Int, height: Int): ByteArray {
        val temp = File.createTempFile("x9-heif-exif-", ".jpg", context.cacheDir)
        return try {
            val seed = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                FileOutputStream(temp).use { out ->
                    check(seed.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "EXIF seed encode failed" }
                }
            } finally {
                seed.recycle()
            }
            val exif = androidx.exifinterface.media.ExifInterface(temp)
            applyExifAttributes(exif, shot)
            // ExifInterface learned ImageWidth/ImageLength from the 1x1 JPEG seed above. If those
            // values ride into the HEIF unchanged, MediaStore indexes a valid full-resolution HEIF
            // as 1x1. Replace both primary and compressed-image dimension pairs with the already
            // cropped/rotated bitmap's true encoded size before extracting the APP1 payload.
            heifExifDimensionAttributes(width, height).forEach { (tag, value) ->
                exif.setAttribute(tag, value)
            }
            exif.saveAttributes()
            extractExifApp1(temp.readBytes()) ?: error("EXIF APP1 payload missing")
        } finally {
            // App-private cache scratch only; failure to remove it is harmless and never touches
            // user media. The normal output remains in MediaStore's pending lifecycle.
            runCatching { temp.delete() }
        }
    }

    private fun applyExifAttributes(
        exif: androidx.exifinterface.media.ExifInterface,
        shot: ExifShot,
    ) {
        fun set(tag: String, value: String) = exif.setAttribute(tag, value)

        if (shot.iso > 0) set(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, shot.iso.toString())
        if (shot.expNs > 0) {
            val sec = shot.expNs / 1_000_000_000.0
            set(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, sec.toString())
            // APEX shutter speed = -log2(t), rational, matching the stock sample (6.908 at 1/120).
            val apex = -Math.log(sec) / Math.log(2.0)
            set(
                androidx.exifinterface.media.ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                "${Math.round(apex * 1000)}/1000",
            )
        }
        if (shot.lensApertureF > 0f) {
            set(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, shot.lensApertureF.toString())
            // APEX aperture = 2·log2(F) (stock: 2.35 at f/2.2).
            val apexAv = 2.0 * Math.log(shot.lensApertureF.toDouble()) / Math.log(2.0)
            set(
                androidx.exifinterface.media.ExifInterface.TAG_APERTURE_VALUE,
                "${Math.round(apexAv * 100)}/100",
            )
            set(
                androidx.exifinterface.media.ExifInterface.TAG_MAX_APERTURE_VALUE,
                "${Math.round(apexAv * 100)}/100",
            )
        }
        if (shot.lensFocalMm > 0f) {
            // Real lens focal (20.1 mm on the 3×), rational millimeters like the stock sample.
            set(
                androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH,
                "${Math.round(shot.lensFocalMm * 1000)}/1000",
            )
        }
        if (shot.focal35mm > 0) {
            set(
                androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                shot.focal35mm.toString(),
            )
        }
        set(
            androidx.exifinterface.media.ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            "${Math.round(shot.digitalZoom * 10000)}/10000",
        )
        // EV bias in sixths, the stock sample's denominator (0/6).
        set(
            androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            "${Math.round(shot.evBiasStops * 6)}/6",
        )
        set(
            androidx.exifinterface.media.ExifInterface.TAG_METERING_MODE,
            when (shot.meteringMode) {
                MeteringMode.MATRIX -> "5" // pattern
                MeteringMode.CENTER -> "2" // center-weighted (the stock default)
                MeteringMode.SPOT -> "3"
            },
        )
        // 0x1 = fired; 0x10 = "did not fire, compulsory off" (the stock sample's value).
        set(androidx.exifinterface.media.ExifInterface.TAG_FLASH, if (shot.flashFired) "1" else "16")
        set(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_PROGRAM, shot.exposureProgram.toString())
        set(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_MODE, if (shot.manualExposure) "1" else "0")
        set(androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE, if (shot.manualWb) "1" else "0")
        set(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL, shot.lensModel)
        set(androidx.exifinterface.media.ExifInterface.TAG_COLOR_SPACE, "1") // sRGB

        val dt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(shot.takenAtMs))
        val offset = java.text.SimpleDateFormat("XXX", java.util.Locale.US)
            .format(java.util.Date(shot.takenAtMs))
        set(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, dt)
        set(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, dt)
        set(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED, dt)
        set(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME, offset)
        set(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        // Pixels are rotated upright before encode — the orientation tag must say NORMAL,
        // not the invalid 0 exifinterface leaves when the tag was never present.
        set(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "1")
        // The stock sample writes the MARKET name, not the ro.product.model code (PMA110).
        set(androidx.exifinterface.media.ExifInterface.TAG_MAKE, "OPPO")
        set(androidx.exifinterface.media.ExifInterface.TAG_MODEL, "OPPO Find X9 Ultra")
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Maps a clockwise rotation (0/90/180/270) to the matching EXIF/TIFF orientation tag for DNG. */
    private fun exifOrientationFor(degrees: Int): Int = RotationMath.exifOrientationFor(degrees)

    /** Returns the largest [ratioW]:[ratioH] rect centered within [src], cropped out of it (HEIF + JPEG paths). */
    private fun centerCrop(src: Bitmap, ratioW: Int, ratioH: Int): Bitmap {
        val (x, y, cropW, cropH) = centerCropBox(src.width, src.height, ratioW, ratioH)
        return Bitmap.createBitmap(src, x, y, cropW, cropH)
    }
}
