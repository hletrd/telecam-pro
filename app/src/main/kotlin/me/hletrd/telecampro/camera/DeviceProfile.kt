package me.hletrd.telecampro.camera

/**
 * Per-device HAL quirk flags — with multi-device support (2026-08-01) this is the SECOND sanctioned
 * model-string-keyed seam beside [detectPhone] (which only preselects the Lens-tab phone dropdown).
 *
 * Rules:
 *  - A profile carries only MEASURED deviations from spec behavior on a specific handset — facts
 *    Camera2 cannot express (HAL crashes, advertised values that are lies, stream quirks). Every
 *    flag must cite its measurement.
 *  - Capability, route, and request decisions still ENUMERATE Camera2 capabilities. A profile may
 *    gate a workaround; it must never substitute for a capability read.
 *  - [GENERIC] is Android-as-specified: no workarounds, trust the advertised ranges. A new device
 *    with a matching quirk gets its own entry only after on-device measurement.
 */
internal data class DeviceProfile(
    /**
     * The front camera's SurfaceTexture stream arrives PRE-mirrored, so the preview draw adds no
     * mirror and encoder/analysis invert x (PMA110, device-diagnosed 2026-07-23 via the
     * frontStreamPreMirrored trace). Spec devices deliver the unmirrored scene: preview mirrors.
     */
    val frontStreamPreMirrored: Boolean,
    /**
     * The stock teleconverter operation_mode 0x80b4 is accepted as a SessionConfiguration
     * sessionType and configures a FULL session (PMA110, device-verified 2026-07-14). On any other
     * vendor an arbitrary session type is undefined; the TC route then uses the regular ladder.
     */
    val vendorTcSessionType: Boolean,
    /**
     * Measured still-exposure ceiling where the ADVERTISED upper is a lie: on PMA110 a still
     * request above 4 s errors the whole device (CAMERA_ERROR(3), device-bisected 2026-07-18)
     * although ≥20 s is advertised. Null = trust the advertised range (spec behavior).
     */
    val stillExposureCeilingNs: Long?,
    /**
     * Whether the `com.oplus.*` REQUEST hints (camera.mode Hasselblad-telephoto, original
     * zoomRatio, video-stab vendor mirror) may ride requests. Their SEMANTICS are measured on
     * PMA110 only; another ColorOS handset would ACCEPT the tags (same provider) and could shift
     * 3A/OIS profiles in never-measured ways with the writes invisible behind runCatching —
     * exactly the quirk class this profile exists to contain (review 2026-08-01).
     */
    val vendorOplusRequestHints: Boolean,
    /**
     * The LOGICAL camera cannot allocate a full-size JPEG blob, so stills there must come as YUV and
     * be encoded in-app (PMA110 gralloc, device-observed 2026-07-14: "SnapAlloc: ValidateDescriptor
     * invalid" and the image never arrives). That is a MEASURED quirk of one HAL, not a property of
     * logical cameras — PRIV preview + JPEG(MAXIMUM) is a guaranteed combination at every hardware
     * level, while PRIV + YUV(MAXIMUM) is only guaranteed at FULL. On a GENERIC device the YUV lane
     * is therefore an OPTIMISATION (it is what feeds the pseudo-ZSL ring) that the fallback ladder
     * may abandon for HAL JPEG, rather than the only survivable shape.
     */
    val logicalStillRequiresYuv: Boolean,
    /**
     * RAW must ride a STANDALONE camera: on PMA110 routing it through a physical sub-camera rejects
     * the stream ("DataSpace override not allowed") and requesting it on the plain logical camera
     * errors the whole device ~5 s after the shot (CAMERA_ERROR(3), device-observed 2026-07-14).
     * Both are that HAL's faults. Spec devices commonly expose RAW16 on the logical camera itself —
     * a phone whose rear lenses appear ONLY as physical sub-cameras has no standalone rear
     * candidate at all, so applying this law universally silently removed DNG there.
     */
    val rawRequiresStandalone: Boolean,
    /**
     * The per-size SurfaceTexture minimum frame durations UNDERSTATE what this HAL records: the
     * PMA110 TELE advertises 33.3 ms (30 fps) for 3840×2160 yet delivers ~60 fps there (device-
     * measured 2026-10-05, 16.66–16.80 ms per frame). Gating on that table would remove a working
     * 4K60 mode, so these devices keep the fixed-fps-range rule alone. Spec devices also drop any
     * rate the selected size's advertised minimum duration cannot sustain.
     */
    val videoMinFrameDurationsUnderstated: Boolean,
    /**
     * Whether the ISP's output under the programmed S-Log3 curve scales LINEARLY with sensor
     * exposure, which the Log EI offset needs (it underexposes by k and lifts the decode by the
     * same k). Spec behaviour (TONEMAP_CURVE maps linear input) is assumed elsewhere. PMA110 is
     * MEASURED false (2026-10-15, M mode, AE off, fixed ISO, a lit wall, preview p98 decoded): at
     * exposure ×1/×½/×¼/×⅛ the S-Log3-decoded output fell to 1 / 0.28 / 0.11 / 0.052 instead of
     * 1 / 0.5 / 0.25 / 0.125 — an exposure-dependent gain before the curve (QTI ADRC/GTM; the
     * vendor tags `disableADRC` / `GTMManualCurveTag` exist but are not request keys), so no fixed k
     * restores grey: +2 read ~1.2 stops dark, banded and desaturated. Owner decision: hide the
     * offset where false; the persisted value is ignored (wire offset 0).
     */
    val nativeLogEiSupported: Boolean = true,
    /**
     * Whether the programmed native-log curve (`TONEMAP_CURVE`) has been measured on this handset
     * (PMA110, 2026-10-14/15). Capability is a claim, not a measurement, so an unmeasured handset gets
     * a short warning under Gamma = Log; owner decision 2026-10-26: keep trusting the curve where it is
     * advertised — it is the device's own log path. What a measurement shows is not always the curve's
     * fault: in one dim room (2026-10-26) a chart's white patch decoded to 0.020 linear on PMA110 and
     * 0.015 on the Galaxy Z Flip6, both ~5½ stops under the S-Log3 convention — the same level, from
     * HAL AE plus the exposure-dependent gain ahead of the curve, not a Samsung defect.
     */
    val nativeLogCurveMeasured: Boolean = false,
    /**
     * Whether a native-log VIDEO session may carry its stream as YCBCR_P010 under the STANDARD
     * dynamic-range profile, bridged into GL through an ImageWriter ([P010InputBridge]): ten bits of
     * the ISP's S-Log3 curve WITH the full-res JPEG and RAW readers kept. PMA110 is MEASURED
     * (2026-10-25, standalone probe): P010 + curve + JPEG + RAW configured on cam 2 and on cam 4
     * under 0x80b4; raw Y reached GL at 94 distinct levels against 27 for the same scene through an
     * 8-bit stream; ImageWriter kept the sensor timestamp; 30 fps sustained. Capability alone is not
     * enough — a HAL can advertise (P010, STANDARD) and still reject it in a session or mis-tag it —
     * so GENERIC stays off until a device is measured. The capability gate still applies on top
     * ([tenBitNativeLogBridgeSupported]).
     */
    val tenBitNativeLogBridge: Boolean = false,
    /** Direct Camera2 extension route measured on PMA110: factory O-Log2 in a 10-bit PRIVATE stream. */
    val makerLogPipeline: Boolean = false,
) {
    companion object {
        val PMA110 = DeviceProfile(
            frontStreamPreMirrored = true,
            vendorTcSessionType = true,
            stillExposureCeilingNs = HAL_SAFE_MAX_STILL_EXPOSURE_NS,
            vendorOplusRequestHints = true,
            logicalStillRequiresYuv = true,
            rawRequiresStandalone = true,
            videoMinFrameDurationsUnderstated = true,
            nativeLogEiSupported = false,
            nativeLogCurveMeasured = true,
            tenBitNativeLogBridge = true,
            makerLogPipeline = true,
        )

        val GENERIC = DeviceProfile(
            frontStreamPreMirrored = false,
            vendorTcSessionType = false,
            stillExposureCeilingNs = null,
            vendorOplusRequestHints = false,
            logicalStillRequiresYuv = false,
            rawRequiresStandalone = false,
            videoMinFrameDurationsUnderstated = false,
        )

        /**
         * Pure resolver so the mapping is host-testable; callers pass [android.os.Build.MODEL].
         * CPH2841 is the Find X9 Ultra's global model (the same phone [detectPhone] names) and takes
         * the WHOLE PMA110 set by owner decision 2026-10-06 — inferred from shared hardware, NOT
         * measured there. A CPH2841 report that contradicts a flag reopens that flag for it.
         *
         * PMA120, the Find X9 Ultra satellite edition (same device codename and camera hardware),
         * follows the same owner reasoning as CPH2841 (2026-10-15) and takes the whole PMA110 set.
         *
         * Every other model takes [GENERIC]: no PMA110 HAL workaround is extended to hardware it was
         * never measured on. (The per-phone maker-log table that mapped OPPO / OnePlus / Xiaomi models
         * onto GENERIC plus their maker's curve was removed with those curves, 2026-10-26.)
         */
        fun resolve(model: String?): DeviceProfile {
            val key = model?.trim()?.uppercase()
            return when (key) {
                "PMA110", "CPH2841", "PMA120" -> PMA110
                else -> GENERIC
            }
        }
    }
}

/**
 * External camera devices never inherit quirks keyed from the host handset's model string.
 */
internal fun deviceProfileForRoute(base: DeviceProfile, externalRoute: Boolean): DeviceProfile =
    if (externalRoute) DeviceProfile.GENERIC else base
