package me.hletrd.telecampro.camera

import me.hletrd.telecampro.gl.MakerLogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The EXPERIMENTAL vivo maker Log seam (MakerLog.kt). Nothing here was measured on a vivo device;
 * these pin the static-analysis protocol, the gates that keep it opt-in and capability-enumerated,
 * and — first of all — that OPPO/PMA110 and GENERIC-with-the-toggle-off stay exactly as they were.
 */
class VivoMakerLogTest {
    private val vivoSessionKeys = setOf(
        VivoMakerLogProtocol.UI_MODULE,
        VivoMakerLogProtocol.UI_SWITCH_STATE,
        VivoMakerLogProtocol.INIT_RATIO,
        VivoMakerLogProtocol.NUM_HDR_EXPOSURE,
        VivoMakerLogProtocol.ENABLE_AUTO_HDR,
        VivoMakerLogProtocol.ENABLE_SHDR,
        VivoMakerLogProtocol.ENABLE_HDR_DCG_MODE,
        VivoMakerLogProtocol.HDR_MODE_PREFERENCE,
        VivoMakerLogProtocol.HDR_MODE,
        VivoMakerLogProtocol.SEAMLESS_CONTROL,
        VivoMakerLogProtocol.USE_AEC_10,
    )

    @Test fun `OPPO wins on its profile and vivo needs both the opt-in and the capability`() {
        // PMA110 / CPH2841 / PMA120: OPPO whatever the toggle or the advertised names say.
        for (toggle in listOf(false, true)) for (capable in listOf(false, true)) {
            assertEquals(MakerLogVendor.OPPO, resolveMakerLogVendor(true, toggle, capable))
        }
        assertEquals(MakerLogVendor.VIVO, resolveMakerLogVendor(false, true, true))
        // GENERIC with the toggle OFF (the default) is byte-identical: no maker vendor at all.
        assertNull(resolveMakerLogVendor(false, false, true))
        assertNull(resolveMakerLogVendor(false, true, false))
        assertNull(resolveMakerLogVendor(false, false, false))
    }

    @Test fun `the TC route follows the vendor's own ceiling rule`() {
        val vivoTc = VivoMakerLogCapability(capable = true, teleWireCeiling = 20f)
        val vivoNoTc = VivoMakerLogCapability(capable = true, teleWireCeiling = null)
        assertTrue(makerLogTeleAvailable(MakerLogVendor.OPPO, oppoTeleMeasured = true, vivo = VivoMakerLogCapability.NONE))
        assertFalse(makerLogTeleAvailable(MakerLogVendor.OPPO, oppoTeleMeasured = false, vivo = vivoTc))
        assertTrue(makerLogTeleAvailable(MakerLogVendor.VIVO, oppoTeleMeasured = false, vivo = vivoTc))
        assertFalse(makerLogTeleAvailable(MakerLogVendor.VIVO, oppoTeleMeasured = true, vivo = vivoNoTc))
        assertFalse(makerLogTeleAvailable(null, oppoTeleMeasured = true, vivo = vivoTc))
    }

    @Test fun `capability needs the Log switch and a UI module key under either name`() {
        assertTrue(vivoMakerLogCapable(setOf(VivoMakerLogProtocol.UI_SWITCH_STATE, VivoMakerLogProtocol.UI_MODULE)))
        assertTrue(vivoMakerLogCapable(setOf(VivoMakerLogProtocol.UI_SWITCH_STATE, VivoMakerLogProtocol.UI_MODULE_LEGACY)))
        assertFalse(vivoMakerLogCapable(setOf(VivoMakerLogProtocol.UI_SWITCH_STATE)))
        assertFalse(vivoMakerLogCapable(setOf(VivoMakerLogProtocol.UI_MODULE, VivoMakerLogProtocol.UI_MODULE_LEGACY)))
        // The request-key twin of the switch is not the session key.
        assertFalse(vivoMakerLogCapable(setOf(VivoMakerLogProtocol.UI_SWITCH_STATE_PREVIEW, VivoMakerLogProtocol.UI_MODULE)))
        assertFalse(vivoMakerLogCapable(emptySet()))
        // PMA110-like advertisement: OPPO and QTI names only.
        assertFalse(vivoMakerLogCapable(setOf(MakerLogProtocol.LOG_MODE, MakerLogProtocol.CAMERA_MODE,
            VivoMakerLogProtocol.NUM_HDR_EXPOSURE)))
    }

    @Test fun `only vivo and QTI session names survive the capability read`() {
        val kept = makerLogVendorKeyNames(listOf(
            "vivo.control.session.uiSwitch_state", "com.vivo.current_ui_module", "vcf.parameter.initRatio",
            "org.codeaurora.qcamera3.sessionParameters.EnableSHDR",
            "com.oplus.log.video.mode", "com.qti.stats.awbwrapper.AWBCCT", "samsung.android.control.x",
            "android.control.videoStabilizationMode", "vivox.not.a.prefix",
        ))
        assertEquals(setOf(
            "vivo.control.session.uiSwitch_state", "com.vivo.current_ui_module", "vcf.parameter.initRatio",
            "org.codeaurora.qcamera3.sessionParameters.EnableSHDR",
        ), kept)
    }

    @Test fun `session parameters follow stock order and the non-DCG variant`() {
        val params = VivoMakerLogProtocol.sessionParameters(vivoSessionKeys, initialWireZoom = 3.7f)
        assertEquals(listOf(
            IntParameter(VivoMakerLogProtocol.UI_MODULE, 51),
            IntParameter(VivoMakerLogProtocol.UI_SWITCH_STATE, 0x20),
            FloatParameter(VivoMakerLogProtocol.INIT_RATIO, 3.7f),
            IntParameter(VivoMakerLogProtocol.NUM_HDR_EXPOSURE, 2),
            IntParameter(VivoMakerLogProtocol.ENABLE_AUTO_HDR, 0),
            IntParameter(VivoMakerLogProtocol.ENABLE_SHDR, 1),
            IntParameter(VivoMakerLogProtocol.ENABLE_HDR_DCG_MODE, 2),
            IntParameter(VivoMakerLogProtocol.SEAMLESS_CONTROL, 2),
            IntParameter(VivoMakerLogProtocol.USE_AEC_10, 1),
            IntArrayParameter(VivoMakerLogProtocol.HDR_MODE_PREFERENCE, listOf(1)),
            IntParameter(VivoMakerLogProtocol.HDR_MODE, 1),
        ), params)
        // Never the extender module, never the HAL Log-LUT preview bit.
        assertTrue(params.none { it is IntParameter && it.value == VivoMakerLogProtocol.UI_MODULE_EXTERNAL_TELE_VIDEO })
        val switch = params.single { it.name == VivoMakerLogProtocol.UI_SWITCH_STATE } as IntParameter
        assertEquals(0, switch.value and VivoMakerLogProtocol.UI_SWITCH_LOG_LUT_PREVIEW)
    }

    @Test fun `the DCG variant follows the advertised DCG key`() {
        val params = VivoMakerLogProtocol.sessionParameters(
            vivoSessionKeys + VivoMakerLogProtocol.ENABLE_DCG_HDR, initialWireZoom = 1f,
        )
        assertEquals(listOf(
            IntParameter(VivoMakerLogProtocol.ENABLE_DCG_HDR, 1),
            IntArrayParameter(VivoMakerLogProtocol.HDR_MODE_PREFERENCE, listOf(4)),
            IntParameter(VivoMakerLogProtocol.HDR_MODE, 0),
        ), params.takeLast(3))
    }

    @Test fun `every key is sent only when its name is advertised`() {
        val minimal = setOf(VivoMakerLogProtocol.UI_MODULE_LEGACY, VivoMakerLogProtocol.UI_SWITCH_STATE)
        assertEquals(listOf(
            IntParameter(VivoMakerLogProtocol.UI_MODULE_LEGACY, 51),
            IntParameter(VivoMakerLogProtocol.UI_SWITCH_STATE, 0x20),
        ), VivoMakerLogProtocol.sessionParameters(minimal, initialWireZoom = 2f))
        assertTrue(VivoMakerLogProtocol.sessionParameters(emptySet(), 2f).isEmpty())
        // Both module names advertised: the current one is preferred and only one is sent.
        val both = VivoMakerLogProtocol.sessionParameters(minimal + VivoMakerLogProtocol.UI_MODULE, 2f)
        assertEquals(VivoMakerLogProtocol.UI_MODULE, both.first().name)
        assertEquals(1, both.count { it.name.endsWith("current_ui_module") })
        // A DCG key advertised without its companions sends only itself.
        assertEquals(listOf(IntParameter(VivoMakerLogProtocol.ENABLE_DCG_HDR, 1)),
            VivoMakerLogProtocol.sessionParameters(setOf(VivoMakerLogProtocol.ENABLE_DCG_HDR), 2f))
    }

    @Test fun `initRatio is omitted unless the wire zoom is positive and finite`() {
        for (zoom in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue("$zoom", VivoMakerLogProtocol.sessionParameters(vivoSessionKeys, zoom)
                .none { it.name == VivoMakerLogProtocol.INIT_RATIO })
        }
        assertEquals(FloatParameter(VivoMakerLogProtocol.INIT_RATIO, 0.6f),
            VivoMakerLogProtocol.sessionParameters(vivoSessionKeys, 0.6f).single { it.name == VivoMakerLogProtocol.INIT_RATIO })
    }

    @Test fun `the repeating request carries only the advertised preview Log bit`() {
        assertEquals(listOf(IntParameter(VivoMakerLogProtocol.UI_SWITCH_STATE_PREVIEW, 0x20)),
            VivoMakerLogProtocol.repeatingParameters(setOf(VivoMakerLogProtocol.UI_SWITCH_STATE_PREVIEW)))
        assertTrue(VivoMakerLogProtocol.repeatingParameters(vivoSessionKeys).isEmpty())
        assertTrue(VivoMakerLogProtocol.repeatingParameters(emptySet()).isEmpty())
    }

    @Test fun `no OPPO key rides a vivo set and no vivo key is an OPPO name`() {
        val everything = vivoSessionKeys + VivoMakerLogProtocol.ENABLE_DCG_HDR + VivoMakerLogProtocol.UI_MODULE_LEGACY +
            VivoMakerLogProtocol.UI_SWITCH_STATE_PREVIEW
        val vivoNames = (VivoMakerLogProtocol.sessionParameters(everything, 3f) +
            VivoMakerLogProtocol.repeatingParameters(everything)).map { it.name }
        val oppoNames = setOf(
            MakerLogProtocol.SDK_PACKAGE, MakerLogProtocol.FROM_EXTENSION, MakerLogProtocol.CAMERA_MODE,
            MakerLogProtocol.LOG_MODE, MakerLogProtocol.FROM_MAIN_MENU, MakerLogProtocol.CAPTURE_TYPE,
            MakerLogProtocol.WB_CCT, MakerLogProtocol.WB_TONE, MakerLogProtocol.GAMMA_INFO,
            MakerLogProtocol.OPERATION_MODE,
        )
        assertTrue(vivoNames.none { it in oppoNames || it.startsWith("com.oplus.") || it.startsWith("com.qti.") })
        // And the reverse: no OPPO name falls under a prefix the vivo capability read keeps.
        assertTrue(makerLogVendorKeyNames(oppoNames).isEmpty())
    }

    @Test fun `the OPPO protocol, session type and confirmation stay exactly as measured`() {
        assertEquals(0x830b, MakerLogProtocol.SESSION_TYPE)
        assertEquals("com.oplus.is.sdk.camera.package", MakerLogProtocol.SDK_PACKAGE)
        assertEquals("com.oplus.is.from.extension", MakerLogProtocol.FROM_EXTENSION)
        assertEquals("com.oplus.camera.mode", MakerLogProtocol.CAMERA_MODE)
        assertEquals("com.oplus.log.video.mode", MakerLogProtocol.LOG_MODE)
        assertEquals("com.oplus.camera.is.from.main.menu", MakerLogProtocol.FROM_MAIN_MENU)
        assertEquals("pro_video_log_mode", MakerLogProtocol.MODE_NAME)
        assertEquals("com.oplus.burst.capture.type", MakerLogProtocol.CAPTURE_TYPE)
        assertEquals(9.8f, MAKER_LOG_TELE_MAX_WIRE_ZOOM_PMA110, 0f)
        // Session types: OPPO's maker operation mode wins (even over the TC flag, as before); a vivo
        // attempt is always REGULAR; every ordinary attempt keeps the regular / TC type it always had.
        assertEquals(0x830b, captureSessionType(MakerLogVendor.OPPO, vendorOperationMode = false))
        assertEquals(0x830b, captureSessionType(MakerLogVendor.OPPO, vendorOperationMode = true))
        assertEquals(0, captureSessionType(MakerLogVendor.VIVO, vendorOperationMode = false))
        assertEquals(0, captureSessionType(MakerLogVendor.VIVO, vendorOperationMode = true))
        assertEquals(0, captureSessionType(null, vendorOperationMode = false))
        assertEquals(0x80b4, captureSessionType(null, vendorOperationMode = true))
        // OPPO still needs its LUT before Ready: no echo-free acceptance.
        assertFalse(makerLogSourceConfirmed(0x830b, 0, null))
        assertTrue(makerLogSourceConfirmed(0x830b, 0, IntArray(1024) { if (it < 256) 754 + it else 0 }))
        // The PMA110 profile resolves OPPO and keeps the measured TC span.
        val pma110 = DeviceProfile.resolve("PMA110")
        assertTrue(pma110.makerLogPipeline)
        assertEquals(MakerLogVendor.OPPO, resolveMakerLogVendor(pma110.makerLogPipeline, true, true))
        assertEquals(9.8f, pma110.makerLogTeleMaxWireZoom)
        // GENERIC never resolves a vendor of its own.
        assertFalse(DeviceProfile.resolve("V2505A").makerLogPipeline)
    }

    @Test fun `accepted outputs record the vendor and claim ten bits only for OPPO`() {
        val plan = sessionAttemptPlan(
            attempt = 0, wantHlg = false, supportsRaw = false, standalone = true, logicalMultiCamera = true,
            teleconverterMode = false, wantHiRes = false, tenBitVideoOnly = false, frontRoute = false,
            yuvStillRequired = false, rawStandaloneOnly = false,
        )
        // Pre-seam callers (no vendor argument) keep their meaning: an OPPO maker session.
        val oppo = acceptedPhotoSessionOutputs(plan, false, false, false, makerLogPresent = true)
        assertEquals(MakerLogVendor.OPPO, oppo.makerLogVendor)
        assertTrue(oppo.tenBitNativeLog)
        assertTrue(oppo.makerLog)
        val vivo = acceptedPhotoSessionOutputs(plan, false, false, false, makerLogPresent = true,
            makerLogVendor = MakerLogVendor.VIVO)
        assertEquals(MakerLogVendor.VIVO, vivo.makerLogVendor)
        assertFalse("an unmeasured stream never reads as ten-bit", vivo.tenBitNativeLog)
        assertTrue(vivo.makerLog)
        val none = acceptedPhotoSessionOutputs(plan, false, false, false, makerLogVendor = MakerLogVendor.VIVO)
        assertNull(none.makerLogVendor)
        assertFalse(none.makerLog)
    }

    @Test fun `the vivo TC ceiling needs the host to be the longest back lens`() {
        // vivo X300 Ultra-like: ~14 / 35 / 85 mm on logical 0, plus standalone twins.
        val ultra = listOf(
            TeleSelection("0", "2", 14f), TeleSelection("0", "3", 35f), TeleSelection("0", "4", 85f),
            TeleSelection("4", null, 85.4f), TeleSelection("2", null, 14f),
        )
        assertEquals(20f, vivoMakerTeleWireCeiling(ultra, "0", "4", 20f))
        // A longer periscope anywhere on the back withholds TC + vivo maker Log.
        val periscope = ultra + TeleSelection("0", "5", 200f)
        assertNull(vivoMakerTeleWireCeiling(periscope, "0", "4", 20f))
        assertNull(vivoMakerTeleWireCeiling(ultra + TeleSelection("6", null, 200f), "0", "4", 20f))
        // Unknown host, unreadable host focal, or no advertised range: not offered.
        assertNull(vivoMakerTeleWireCeiling(ultra, "0", null, 20f))
        assertNull(vivoMakerTeleWireCeiling(ultra, "0", "9", 20f))
        assertNull(vivoMakerTeleWireCeiling(ultra, "0", "4", null))
        assertNull(vivoMakerTeleWireCeiling(ultra, "0", "4", Float.NaN))
        assertNull(vivoMakerTeleWireCeiling(listOf(TeleSelection("0", "4", 0f)), "0", "4", 20f))
        // A standalone reading of the SAME lens within rounding is not "longer".
        assertEquals(20f, vivoMakerTeleWireCeiling(ultra + TeleSelection("7", null, 86.5f), "0", "4", 20f))
        // The window that ceiling produces reaches past the host's own field (digital on the host).
        val window = makerTeleLocalZoomWindow(0.6f, 20f, wireScale = 3.7f, wireCeiling = 20f)!!
        assertEquals(1f, window.start, 0f)
        assertEquals(20f / 3.7f, window.endInclusive, 1e-5f)
    }

    @Test fun `a refused or switched-off vivo request reads as its S-Log3 fallback`() {
        val vivo = VivoMakerLogCapability(capable = true, teleWireCeiling = 20f)
        val accepted = CameraUiState(
            mode = CaptureMode.VIDEO, transfer = ColorTransfer.MAKER_LOG, cameraReady = true,
            vivoMakerLogExperimental = true, vivoMakerLog = vivo,
            photoSessionOutputs = PhotoSessionOutputs(makerLog = true, makerLogVendor = MakerLogVendor.VIVO),
        )
        assertEquals(MakerLogVendor.VIVO, accepted.makerLogVendor)
        assertTrue(accepted.makerLogOffered)
        assertEquals(VideoColorMode.MAKER_LOG, accepted.videoColorMode)
        assertEquals(ColorTransfer.MAKER_LOG, accepted.effectiveVideoTransfer)
        // Refused: the accepted session is the ordinary one.
        val refused = accepted.copy(photoSessionOutputs = PhotoSessionOutputs())
        assertEquals(VideoColorMode.LOG, refused.videoColorMode)
        assertEquals(ColorTransfer.SLOG3, refused.effectiveVideoTransfer)
        // Opt-in switched off: the intent is kept, the row and file are S-Log3, OEM Log disappears.
        val off = accepted.copy(vivoMakerLogExperimental = false, photoSessionOutputs = PhotoSessionOutputs())
        assertNull(off.makerLogVendor)
        assertFalse(off.makerLogOffered)
        assertFalse(VideoColorMode.MAKER_LOG in off.videoColorModes)
        assertEquals(VideoColorMode.LOG, off.videoColorMode)
        assertEquals(ColorTransfer.SLOG3, off.effectiveVideoTransfer)
        assertEquals(ColorTransfer.MAKER_LOG, off.transfer)
        // TC: offered with a ceiling, withheld without one; the Setup row only on vivo cameras.
        assertTrue(accepted.copy(teleconverterMode = true).makerLogOffered)
        assertFalse(accepted.copy(teleconverterMode = true, vivoMakerLog = vivo.copy(teleWireCeiling = null)).makerLogOffered)
        assertTrue(accepted.vivoMakerLogSettingVisible)
        assertFalse(CameraUiState().vivoMakerLogSettingVisible)
        assertFalse(accepted.copy(makerLogSupported = true).vivoMakerLogSettingVisible)
    }

    @Test fun `the default UI state and the OPPO profile state read exactly as before`() {
        val generic = CameraUiState(mode = CaptureMode.VIDEO, transfer = ColorTransfer.MAKER_LOG)
        assertNull(generic.makerLogVendor)
        assertFalse(generic.makerLogOffered)
        val oppo = CameraUiState(mode = CaptureMode.VIDEO, transfer = ColorTransfer.MAKER_LOG,
            makerLogSupported = true, makerLogTeleSupported = true,
            // Even a vivo capability and opt-in (impossible on PMA110) cannot move the OPPO answer.
            vivoMakerLogExperimental = true, vivoMakerLog = VivoMakerLogCapability(true, null))
        assertEquals(MakerLogVendor.OPPO, oppo.makerLogVendor)
        assertTrue(oppo.makerLogTeleAvailable)
        assertTrue(oppo.copy(teleconverterMode = true).makerLogOffered)
    }

    @Test fun `GL inverts O-Log2 for OPPO and nothing for vivo`() {
        assertEquals(MakerLogSource.CURVE_OLOG2, MakerLogSource.curveFor(MakerLogVendor.OPPO))
        assertEquals(MakerLogSource.CURVE_OLOG2, MakerLogSource.curveFor(null))
        assertEquals(MakerLogSource.CURVE_AS_DELIVERED, MakerLogSource.curveFor(MakerLogVendor.VIVO))
        // The renderer default is OPPO's curve, so every pre-seam draw is unchanged.
        assertEquals(MakerLogSource.CURVE_OLOG2, RendererConfig().sourceMakerCurve)
        val store = RendererConfigStore()
        store.update { it.copy(sourceMaker = true, sourceMakerCurve = MakerLogSource.CURVE_AS_DELIVERED) }
        store.update { it.copy(zebra = true) }
        assertEquals(MakerLogSource.CURVE_AS_DELIVERED, store.snapshot().sourceMakerCurve)
        assertTrue(store.snapshot().sourceMaker)
    }

    @Test fun `vivo sessions keep their own WB transform shape`() {
        val key = WbRouteKey("0", WbSessionShape.VIVO_MAKER_LOG, "4")
        assertEquals(key, WbRouteKey.decode(key.encode()))
        assertTrue(WbSessionShape.VIVO_MAKER_LOG != WbSessionShape.MAKER_LOG)
    }

    @Test fun `the echo probe samples three frames and logs only changes`() {
        val probe = VivoMakerLogEchoProbe()
        val sampled = (1..200).filter { probe.sampleThisFrame() }
        assertEquals(listOf(2, 30, 150), sampled)
        val first = VivoMakerLogEchoProbe()
        repeat(2) { first.sampleThisFrame() }
        val row = first.row(echoPresent = true, echoValue = 0x20, feedbackNames = listOf("vivo.feedback.b", "vivo.feedback.a"))
        assertEquals("VivoLog: frame=2 echo(vivo.control.uiSwitch_state_preview)=0x20 " +
            "feedback=[vivo.feedback.a, vivo.feedback.b]", row)
        repeat(28) { first.sampleThisFrame() }
        assertNull("same observation, later frame", first.row(true, 0x20, listOf("vivo.feedback.a", "vivo.feedback.b")))
        assertEquals("VivoLog: frame=30 echo(vivo.control.uiSwitch_state_preview)=absent feedback=[]",
            first.row(false, null, emptyList()))
        assertTrue(first.row(true, null, emptyList())!!.contains("=unreadable"))
    }
}
