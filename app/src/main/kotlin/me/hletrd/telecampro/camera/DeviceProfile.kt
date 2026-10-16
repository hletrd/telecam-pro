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
     * The phone maker's OWN log format for this handset, offered beside the universal
     * S-Log3 / S-Log3.Cine / LogC3 (owner decision 2026-10-15: native log per phone, never one
     * maker's log for everyone). Unlike every flag above this is not a HAL quirk but a PRODUCT fact
     * — which maker log this phone's owners grade with — so it is keyed from the same model string
     * and needs no measurement. It never changes the camera session: every log rides the same
     * S-Log3 ISP transport curve or GL fallback, and only the GL re-encode differs. Null = none.
     * The models per flavor and their sources are in [resolve].
     */
    val nativeLogFlavor: ColorTransfer? = null,
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
            nativeLogFlavor = ColorTransfer.OLOG2,
            nativeLogEiSupported = false,
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
         * Every other mapped model takes [GENERIC] quirks plus ONLY its [nativeLogFlavor]: no PMA110
         * HAL workaround is extended to hardware it was never measured on.
         *
         * Model codes are the `Build.MODEL` column of Google Play's supported-devices list
         * (https://storage.googleapis.com/play_public/supported_devices.csv, fetched 2026-10-15);
         * which phones carry which log is from the makers' white papers:
         *  - O-Log2 — OPPO "O-Log2 White Paper" (2026-04-17) for the Find X9 Ultra:
         *    PMA110 / PMA120 (China, satellite), CPH2841 (global).
         *  - O-Log (V1) — OPPO "O-Log White Paper" V1 (2025-10-22) lists Find X8 Ultra, OnePlus 15,
         *    Find X9, Find X9 Pro: Find X8 Ultra PKJ110 / PKU110 (China, satellite); OnePlus 15
         *    PLK110 (China), CPH2745 / CPH2747 / CPH2749 (India / global / North America); Find X9
         *    PLJ110 (China), CPH2797 (global), OPG07 (Japan); Find X9 Pro PLG110 / PLG120 (China,
         *    satellite), CPH2791 (global). Both papers: https://www.oppo.com/en/o-log-technical-downloads/
         *  - Mi-Log — Xiaomi "Xiaomi Log Profile White Paper" (Dec 2024) curve, on the owner's model
         *    list (2026-10-15): Xiaomi 15 Ultra 25010PN30C / 25010PN30G / 25010PN30I, 25019PNF3C
         *    (China satellite); Xiaomi 17 Ultra 2512BPNDAC / 2512BPNDAG / 2512BPNDAI, 25128PNA1C
         *    ("by Leica", China). 2512BPNDAG is also Play-listed as "Leitzphone powered by Xiaomi".
         * Deliberately NOT here: OnePlus 15R / Find X9s (different phones with different codes), and
         * any maker without a public curve (CLAUDE.md: Samsung, vivo, Honor, Huawei, Pixel).
         */
        fun resolve(model: String?): DeviceProfile {
            val key = model?.trim()?.uppercase()
            return when (key) {
                "PMA110", "CPH2841", "PMA120" -> PMA110
                in OLOG_MODELS -> GENERIC.copy(nativeLogFlavor = ColorTransfer.OLOG)
                in MILOG_MODELS -> GENERIC.copy(nativeLogFlavor = ColorTransfer.MILOG)
                else -> GENERIC
            }
        }

        // Sourced per [resolve]'s KDoc. Every Find X9 Ultra code carries OLOG2 through [PMA110].
        private val OLOG_MODELS = setOf(
            "PKJ110", "PKU110", // OPPO Find X8 Ultra
            "PLK110", "CPH2745", "CPH2747", "CPH2749", // OnePlus 15
            "PLJ110", "CPH2797", "OPG07", // OPPO Find X9
            "PLG110", "PLG120", "CPH2791", // OPPO Find X9 Pro
        )
        private val MILOG_MODELS = setOf(
            "25010PN30C", "25010PN30G", "25010PN30I", "25019PNF3C", // Xiaomi 15 Ultra
            "2512BPNDAC", "2512BPNDAG", "2512BPNDAI", "25128PNA1C", // Xiaomi 17 Ultra
        )
    }
}

/**
 * External camera devices never inherit quirks keyed from the host handset's model string — and
 * that includes [DeviceProfile.nativeLogFlavor]: a USB camera is not the phone maker's camera. (The
 * transfer PICKER is keyed on the host profile, see [CameraEngine.nativeLogFlavor].)
 */
internal fun deviceProfileForRoute(base: DeviceProfile, externalRoute: Boolean): DeviceProfile =
    if (externalRoute) DeviceProfile.GENERIC else base
