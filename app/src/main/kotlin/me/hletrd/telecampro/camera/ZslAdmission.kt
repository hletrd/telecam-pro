package me.hletrd.telecampro.camera

import kotlin.math.abs
import kotlin.math.ln

/*
 * Pseudo-ZSL admission (cycle 8). The LOGICAL photo route streams its full-res YUV still reader on
 * the repeating request (S4a device-measured 2026-07-25: 29-31 fps lit / 14-16 fps at the dark
 * fluidity cap, zero stalls or errors, negligible thermal over ~10 min), and the controller holds
 * a small ring of recent frames with their TotalCaptureResults. A shutter press serves the newest
 * BUFFERED frame instead of a fresh still request — eliminating the pipeline-depth × frame-duration
 * lag — but ONLY when that frame is truthfully the still the user asked for:
 *
 * The frame's ACTUAL sensor values must match the still's INTENDED values within tolerance. In
 * bright light (no preview trade active) wire == intended and substitution is exact; in low light
 * the fluidity cap deliberately diverges the preview (short exposure + ISO + GL gain) from the
 * intended still (true long exposure), so admission honestly fails and a REAL capture runs at full
 * quality. Quality is never silently degraded — this predicate is the one gate that guarantees it.
 *
 * FRAMING follows the same rule (2026-10-27): a ring frame is framed by the repeating request's
 * video stabilization mode, so it serves only when the real still would carry that same mode
 * (StillFraming.kt). On PMA110 every still does; on a spec device a stabilized session real-captures.
 *
 * Deliberately NOT "HAL-AE only": photo PROGRAM runs app-side AE-OFF with fresh iso/exposure in
 * the control packet, so its bright-light shots — most real shots — are servable. HAL-AE modes
 * (video-P, flash-metered P) have no app-known intended values and always real-capture.
 */

/**
 * Max age of a servable frame.
 *
 * 400 ms, raised from 250 (device-measured 2026-07-31): at the dark 15 fps fluidity cap the newest
 * ring frame WITH a paired TotalCaptureResult measured 243 ms old at press time — results arrive a
 * pipeline-depth (~3.6 frames) behind their images — so the old cap made dim-scene serves
 * structurally marginal even when the sensor values matched exactly. This is the FRAMING axis, not
 * the exposure axis: raising it trades only frame freshness (a ≤0.4 s-old frame, comparable to what
 * the pipeline-lagged preview itself shows), never the value-match honesty that the 1/6-stop
 * tolerance guards — which stays untouched per its own do-not-widen rule.
 */
internal const val ZSL_MAX_FRAME_AGE_NS = 400_000_000L

/** Sensor-value match tolerance (stops) between the frame's actuals and the still's intent. */
internal const val ZSL_VALUE_TOLERANCE_STOPS = 1f / 6f

/** Zoom-ratio mismatch tolerance: a mid-gesture wide-aimed frame (~1.2×) must never serve. */
internal const val ZSL_ZOOM_TOLERANCE = 0.02f

/** Ring depth: enough for result/image arrival skew at 30 fps without hoarding gralloc buffers. */
internal const val ZSL_RING_DEPTH = 3

/** The facts a buffered frame carries (from its own TotalCaptureResult; null = not yet known). */
internal data class ZslFrameFacts(
    val timestampNs: Long,
    val exposureNs: Long?,
    val iso: Int?,
    val zoomRatio: Float?,
    /** The frame's own CONTROL_AWB_MODE result; null when unread. */
    val awbMode: Int?,
)

/** What the shutter press is asking for, snapshotted once at capture dispatch. */
internal data class ZslStillIntent(
    /** applyExposure's own AE-OFF admission — intent values are only meaningful app-side. */
    val manualAe: Boolean,
    val wantProcessed: Boolean,
    val wantRaw: Boolean,
    val flash: FlashMode,
    /** The sensor-clamped exposure the still request would carry. */
    val exposureNs: Long,
    val iso: Int,
    /** The EXACT requested zoom (never the wide-aimed HAL target). */
    val zoomRatio: Float,
    /** A live zoom gesture invalidates buffered framing wholesale (belt to the zoom check). */
    val gestureActive: Boolean,
    /**
     * The real still this press would capture carries the ring's video stabilization mode, so a
     * buffered frame is framed like it ([zslStillFramingMatches]). A ring frame is a REPEATING
     * frame and always carries the session's mode; serving it for a still that would be captured
     * at another mode silently swaps the still's field of view — about 16 % on PMA110's logical 1×
     * (FIELD_CHECKS A23) — for latency, the framing twin of the exposure rule above.
     */
    val framedLikeRing: Boolean,
    /**
     * The CONTROL_AWB_MODE the real still would carry (OFF for an app-owned white point), or null
     * to skip the check. A ring frame rendered in another mode is another white balance: during a
     * preset sweep (WhiteBalance.kt) the ring holds preset frames while the still carries the
     * manual matrix, and the last sweep frames stay young enough to serve after it ends.
     */
    val awbMode: Int?,
    /** A preset sweep is running: every ring frame carries a sweep preset, never the still's WB. */
    val wbSweeping: Boolean,
)

/**
 * Whether this shutter press may consult the ring at all. RAW wants a real capture (the ring holds
 * processed YUV only); AE-driven flash needs the HAL's precapture sequence (TORCH lights the
 * buffered frames exactly like the still, so it stays eligible — and under app-side AE the
 * AUTO/ON variants are wire-inert anyway, this is defense in depth); and a still that would be
 * framed unlike the ring ([ZslStillIntent.framedLikeRing]) must be captured for real.
 */
internal fun zslIntentEligible(intent: ZslStillIntent): Boolean =
    intent.manualAe && intent.wantProcessed && !intent.wantRaw &&
        (intent.flash == FlashMode.OFF || intent.flash == FlashMode.TORCH) &&
        !intent.gestureActive && intent.framedLikeRing && !intent.wbSweeping

/**
 * Whether one buffered frame IS the requested still. [nowNs] shares the SENSOR_TIMESTAMP clock
 * (elapsedRealtimeNanos on this HAL); a negative age means a clock-domain surprise and refuses —
 * the failure mode is "ZSL never admits", visible in the ShutterLag logs, never a wrong frame.
 */
internal fun zslFrameAdmissible(frame: ZslFrameFacts, intent: ZslStillIntent, nowNs: Long): Boolean {
    if (!zslIntentEligible(intent)) return false
    val ageNs = nowNs - frame.timestampNs
    if (ageNs < 0L || ageNs > ZSL_MAX_FRAME_AGE_NS) return false
    val frameExposureNs = frame.exposureNs ?: return false
    val frameIso = frame.iso ?: return false
    val frameZoom = frame.zoomRatio ?: return false
    if (frameExposureNs <= 0L || intent.exposureNs <= 0L) return false
    if (frameIso <= 0 || intent.iso <= 0) return false
    if (intent.zoomRatio <= 0f || frameZoom <= 0f) return false
    if (abs(log2Ratio(frameExposureNs.toDouble(), intent.exposureNs.toDouble())) > ZSL_VALUE_TOLERANCE_STOPS) return false
    if (abs(log2Ratio(frameIso.toDouble(), intent.iso.toDouble())) > ZSL_VALUE_TOLERANCE_STOPS) return false
    if (abs(frameZoom / intent.zoomRatio - 1f) > ZSL_ZOOM_TOLERANCE) return false
    if (intent.awbMode != null && frame.awbMode != intent.awbMode) return false
    return true
}

private fun log2Ratio(a: Double, b: Double): Float = (ln(a / b) / ln(2.0)).toFloat()
