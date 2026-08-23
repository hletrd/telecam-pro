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
) {
    companion object {
        val PMA110 = DeviceProfile(
            frontStreamPreMirrored = true,
            vendorTcSessionType = true,
            stillExposureCeilingNs = HAL_SAFE_MAX_STILL_EXPOSURE_NS,
            vendorOplusRequestHints = true,
            logicalStillRequiresYuv = true,
            rawRequiresStandalone = true,
        )

        val GENERIC = DeviceProfile(
            frontStreamPreMirrored = false,
            vendorTcSessionType = false,
            stillExposureCeilingNs = null,
            vendorOplusRequestHints = false,
            logicalStillRequiresYuv = false,
            rawRequiresStandalone = false,
        )

        /** Pure resolver so the mapping is host-testable; callers pass [android.os.Build.MODEL]. */
        fun resolve(model: String?): DeviceProfile =
            if (model?.trim()?.equals("PMA110", ignoreCase = true) == true) PMA110 else GENERIC
    }
}

/** External camera devices never inherit quirks keyed from the host handset's model string. */
internal fun deviceProfileForRoute(base: DeviceProfile, externalRoute: Boolean): DeviceProfile =
    if (externalRoute) DeviceProfile.GENERIC else base
