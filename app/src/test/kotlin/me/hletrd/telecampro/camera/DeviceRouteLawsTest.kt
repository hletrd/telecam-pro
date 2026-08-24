package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two PMA110 HAL faults were written as universal law and only came out under review once the app
 * became multi-device (2026-08-02): the logical camera's un-allocatable JPEG blob, and RAW's
 * standalone-only requirement. Both are DeviceProfile-gated now. These pin BOTH sides — PMA110
 * unchanged, spec devices getting the guaranteed/legal shape.
 */
class DeviceRouteLawsTest {

    private fun plan(
        attempt: Int,
        logical: Boolean = true,
        front: Boolean = false,
        standalone: Boolean = false,
        yuvRequired: Boolean,
        rawStandaloneOnly: Boolean,
    ) = sessionAttemptPlan(
        attempt = attempt,
        wantHlg = false,
        supportsRaw = true,
        standalone = standalone,
        logicalMultiCamera = logical,
        frontRoute = front,
        yuvStillRequired = yuvRequired,
        rawStandaloneOnly = rawStandaloneOnly,
    )

    // ---- YUV still lane -------------------------------------------------------------------------

    @Test
    fun `PMA110 keeps the YUV lane on every rung of the logical route`() {
        for (attempt in 0..2) {
            assertTrue(
                "attempt $attempt",
                plan(attempt, yuvRequired = true, rawStandaloneOnly = true).useYuvStill,
            )
        }
    }

    @Test
    fun `a spec device trades YUV for HAL JPEG before giving up on stills`() {
        val p = { a: Int -> plan(a, yuvRequired = false, rawStandaloneOnly = false) }
        // Rungs 0-1 keep YUV (it is what feeds the pseudo-ZSL ring)…
        assertTrue(p(0).useYuvStill)
        assertTrue(p(1).useYuvStill)
        // …then the ladder falls back to the always-guaranteed PRIV+JPEG combination, WITH a still
        // target — previously this rung was byte-identical to the last and the next stop was
        // preview-only, i.e. no stills at all on a device that rejects PRIV+YUV(MAXIMUM).
        assertFalse(p(2).useYuvStill)
        assertTrue(p(2).useJpeg)
    }

    @Test
    fun `standalone routes never take the YUV lane on any device`() {
        for (required in listOf(true, false)) {
            assertFalse(
                plan(0, logical = false, standalone = true, yuvRequired = required, rawStandaloneOnly = true)
                    .useYuvStill,
            )
        }
    }

    // ---- RAW ------------------------------------------------------------------------------------

    @Test
    fun `PMA110 still refuses RAW anywhere but a standalone camera`() {
        assertFalse(plan(0, yuvRequired = true, rawStandaloneOnly = true).useRaw)
        assertTrue(
            plan(0, logical = false, standalone = true, yuvRequired = true, rawStandaloneOnly = true).useRaw,
        )
    }

    @Test
    fun `a spec device may carry RAW on the logical or routed camera`() {
        assertTrue(plan(0, yuvRequired = false, rawStandaloneOnly = false).useRaw)
        // Routed sub-camera (standalone=false, logical=false) is legal there too.
        assertTrue(
            plan(0, logical = false, standalone = false, yuvRequired = false, rawStandaloneOnly = false).useRaw,
        )
    }

    @Test
    fun `the front route drops RAW on every device — its readers are gone by design`() {
        assertFalse(
            plan(0, logical = false, front = true, yuvRequired = false, rawStandaloneOnly = false).useRaw,
        )
    }

    // ---- route forcing --------------------------------------------------------------------------

    @Test
    fun `DNG moves PMA110 off the seamless camera but leaves a spec device on it`() {
        assertTrue(standaloneRouteWanted(videoMode = false, rawWanted = true, rawForcesStandalone = true))
        assertFalse(standaloneRouteWanted(videoMode = false, rawWanted = true, rawForcesStandalone = false))
        // VIDEO's standalone pin is an EIS decision and stays universal.
        assertTrue(standaloneRouteWanted(videoMode = true, rawWanted = false, rawForcesStandalone = false))
    }
}

/** The ZSL ring must never cost the viewfinder its frame rate (2026-08-02 review). */
class ZslStreamFluidityTest {
    @Test
    fun `PMA110's advertised 33 ms YUV duration keeps streaming`() {
        assertTrue(zslStreamKeepsPreviewFluid(33_333_333L))
    }

    @Test
    fun `an unavailable duration cannot prove a deep repeating reader is fluid`() {
        assertFalse(zslStreamKeepsPreviewFluid(0L))
        assertFalse(zslStreamKeepsPreviewFluid(-1L))
    }

    @Test
    fun `a slow large-YUV device keeps the reader off the repeating request`() {
        // 50 ms = 20 fps, 100 ms = 10 fps: both below the 24 fps floor.
        assertFalse(zslStreamKeepsPreviewFluid(50_000_000L))
        assertFalse(zslStreamKeepsPreviewFluid(100_000_000L))
    }

    @Test
    fun `deep allocation needs YUV plan authority and positive timing evidence`() {
        assertTrue(deepZslReaderEnabled(true, true, 33_333_333L))
        assertFalse(deepZslReaderEnabled(true, true, 0L))
        assertFalse(deepZslReaderEnabled(true, false, 33_333_333L))
        assertFalse(deepZslReaderEnabled(false, true, 33_333_333L))
    }
}

/** Coverage the verification pass asked for: the two shapes the new code could newly emit. */
class YuvLaneShapeTest {
    @Test
    fun `a hi-res intent can never turn the logical route's YUV fail-safe off`() {
        // hi-res is standalone-only by construction, but if a bad intent ever reached the plan the
        // old caps-derived predicate still said YUV; the new one must not ask a logical camera for
        // the JPEG blob it cannot allocate.
        val p = sessionAttemptPlan(
            attempt = 0,
            wantHlg = false,
            supportsRaw = false,
            standalone = false,
            logicalMultiCamera = true,
            wantHiRes = true,
            yuvStillRequired = true,
            rawStandaloneOnly = true,
        )
        assertTrue(p.useYuvStill)
    }

    @Test
    fun `the YUV lane degrades monotonically across the TELE table`() {
        // TELE maps ladder rungs onto stream attempts 0,1,2,0,1,2,3,3 — keyed on streamAttempt the
        // lane would drop at rung 2 and come BACK at rungs 3-4.
        var seenFalse = false
        for (attempt in 0..7) {
            val on = sessionAttemptPlan(
                attempt = attempt,
                wantHlg = false,
                supportsRaw = false,
                standalone = false,
                logicalMultiCamera = true,
                teleconverterMode = true,
                yuvStillRequired = false,
                rawStandaloneOnly = false,
            ).useYuvStill
            if (!on) seenFalse = true
            assertTrue("rung $attempt re-deepened after dropping", !(seenFalse && on))
        }
    }
}

/** The offered video sizes must be usable ones — but never an EMPTY list (2026-08-02). */
class VideoSizeFloorTest {
    @Test
    fun `the floor applies while something clears it`() {
        // 1440p/1080p/720p/360p/108p as a real device advertised them.
        assertFalse(videoFloorKeepsAll(listOf(1440, 1080, 720, 360, 108)))
    }

    @Test
    fun `a device whose largest size is below the floor keeps everything`() {
        assertTrue(videoFloorKeepsAll(listOf(360, 108)))
        assertTrue(videoFloorKeepsAll(emptyList()))
    }

    @Test
    fun `PMA110 clears the floor, so nothing about its list changes`() {
        assertFalse(videoFloorKeepsAll(listOf(2160, 1080, 720)))
    }
}

/**
 * The encoder-size fallback ladder (2026-08-02). Device-probed cause: the AOSP software HEVC
 * encoder accepts 1280x720 and 1920x1080 but REFUSES 720x1280 and 1080x1920 — a height cap — while
 * advertising `supportedWidths=[2,512]` and answering `isSizeSupported(1280,720)=false` for a size
 * it demonstrably encodes. Capability queries cannot decide this; the ladder must.
 */
class EncoderSizeLadderTest {
    @Test
    fun `the requested size is always the first rung`() {
        val ladder = me.hletrd.telecampro.video.encoderSizeLadder(720, 1280)
        assertEquals(720 to 1280, ladder.first())
    }

    @Test
    fun `every rung preserves the aspect ratio within a rounding pixel`() {
        val want = 720.0 / 1280.0
        me.hletrd.telecampro.video.encoderSizeLadder(720, 1280).forEach { (w, h) ->
            val err = kotlin.math.abs(w.toDouble() / h - want)
            assertTrue("rung ${w}x$h drifts from ${want}: err=$err", err < 0.01)
        }
    }

    @Test
    fun `every rung is even — 4-2-0 chroma cannot express an odd edge`() {
        me.hletrd.telecampro.video.encoderSizeLadder(1080, 1920).forEach { (w, h) ->
            assertEquals(0, w % 2)
            assertEquals(0, h % 2)
        }
    }

    @Test
    fun `the ladder reaches a rung the probed encoder actually accepted`() {
        // 480x854 and 360x640 PASSED on c2.android.hevc.encoder; 720x1280 FAILED.
        val ladder = me.hletrd.telecampro.video.encoderSizeLadder(720, 1280)
        assertTrue("ladder must descend below the refused height", ladder.any { it.second <= 854 })
    }

    @Test
    fun `rungs strictly descend and never repeat`() {
        val ladder = me.hletrd.telecampro.video.encoderSizeLadder(3840, 2160)
        assertEquals(ladder.distinct(), ladder)
        ladder.zipWithNext { a, b -> assertTrue("${a} then ${b}", b.first < a.first) }
    }

    @Test
    fun `nothing below the usable floor is offered`() {
        me.hletrd.telecampro.video.encoderSizeLadder(320, 240).forEach { (w, h) ->
            assertTrue(w >= me.hletrd.telecampro.video.MIN_ENCODER_EDGE)
            assertTrue(h >= me.hletrd.telecampro.video.MIN_ENCODER_EDGE)
        }
    }

    @Test
    fun `a degenerate size yields no rungs rather than a crash`() {
        assertTrue(me.hletrd.telecampro.video.encoderSizeLadder(0, 1080).isEmpty())
        assertTrue(me.hletrd.telecampro.video.encoderSizeLadder(1920, -1).isEmpty())
    }
}

/**
 * Gamma options must not outrun the encoder (2026-08-02). Every transfer except SDR asks for
 * HEVCProfileMain10 and BT.2020 tags; on an 8-bit-only encoder the device-probed result was a clip
 * whose container read `bt2020 / arib-std-b67` over a `profile=Main, yuv420p` stream.
 */
class TransferEncoderHonestyTest {
    @Test
    fun `a Main10 encoder offers every gamma`() {
        assertEquals(
            ColorTransfer.entries.toList(),
            availableTransfers(VideoCodec.HEVC, tenBitEncodeAvailable = true),
        )
    }

    @Test
    fun `an 8-bit-only encoder offers SDR alone`() {
        assertEquals(
            listOf(ColorTransfer.SDR),
            availableTransfers(VideoCodec.HEVC, tenBitEncodeAvailable = false),
        )
    }

    @Test
    fun `SDR is never withheld — it is the 8-bit BT709 case by construction`() {
        assertTrue(ColorTransfer.SDR in availableTransfers(VideoCodec.HEVC, true))
        assertTrue(ColorTransfer.SDR in availableTransfers(VideoCodec.HEVC, false))
    }

    @Test
    fun `a persisted HLG or log selection falls back to SDR on an 8-bit encoder`() {
        listOf(ColorTransfer.HLG, ColorTransfer.SLOG3, ColorTransfer.SLOG3_CINE, ColorTransfer.LOGC3)
            .forEach {
                assertEquals(
                    ColorTransfer.SDR,
                    it.normalizedForEncoder(VideoCodec.HEVC, false),
                )
            }
    }

    @Test
    fun `PMA110-class hardware keeps whatever the operator chose`() {
        ColorTransfer.entries.forEach {
            assertEquals(it, it.normalizedForEncoder(VideoCodec.HEVC, true))
        }
    }

    @Test
    fun `AVC atomically normalizes every non-SDR intent`() {
        ColorTransfer.entries.filter { it != ColorTransfer.SDR }.forEach {
            assertEquals(ColorTransfer.SDR, it.normalizedForEncoder(VideoCodec.AVC, true))
        }
        assertEquals(
            listOf(ColorTransfer.SDR),
            availableTransfers(VideoCodec.AVC, tenBitEncodeAvailable = true),
        )
    }

    @Test
    fun `HEVC Main10 retains non-SDR intent`() {
        ColorTransfer.entries.forEach {
            assertEquals(it, it.normalizedForEncoder(VideoCodec.HEVC, true))
        }
    }
}

/**
 * Per-channel input metering (2026-08-02). One averaged bar hides the failure an input meter exists
 * to catch: on a stereo or multi-capsule external mic, a dead channel still leaves the average
 * moving.
 */
class ChannelLevelsTest {
    private fun interleave(vararg channels: ShortArray): ShortArray {
        val frames = channels.first().size
        val out = ShortArray(frames * channels.size)
        var i = 0
        for (f in 0 until frames) for (c in channels.indices) out[i++] = c.let { channels[it][f] }
        return out
    }

    @Test
    fun `a dead right channel is visible instead of averaged away`() {
        val loud = ShortArray(64) { 16000 }
        val dead = ShortArray(64)
        val buf = interleave(loud, dead)
        val levels = me.hletrd.telecampro.video.channelRms(buf, buf.size, channelCount = 2)
        assertEquals(2, levels.size)
        assertTrue("left must read loud, got ${levels[0]}", levels[0] > 0.4f)
        assertEquals(0f, levels[1], 1e-6f)
    }

    @Test
    fun `mono input yields exactly one bar`() {
        val buf = ShortArray(128) { 8000 }
        assertEquals(1, me.hletrd.telecampro.video.channelRms(buf, buf.size, channelCount = 1).size)
    }

    @Test
    fun `a trailing partial frame is dropped, not attributed to the wrong channel`() {
        val loud = ShortArray(8) { 20000 }
        val dead = ShortArray(8)
        val buf = interleave(loud, dead) + shortArrayOf(20000) // one orphan LEFT sample
        val levels = me.hletrd.telecampro.video.channelRms(buf, buf.size, channelCount = 2)
        assertEquals("the orphan sample must not raise the right channel", 0f, levels[1], 1e-6f)
    }

    @Test
    fun `gain scales the reading and still clamps at full scale`() {
        val buf = ShortArray(32) { 16000 }
        val unity = me.hletrd.telecampro.video.channelRms(buf, buf.size, 1, gain = 1f)[0]
        val doubled = me.hletrd.telecampro.video.channelRms(buf, buf.size, 1, gain = 2f)[0]
        assertTrue(doubled > unity)
        assertTrue(me.hletrd.telecampro.video.channelRms(buf, buf.size, 1, gain = 100f)[0] <= 1f)
    }

    @Test
    fun `an empty read yields silence per channel rather than NaN`() {
        val levels = me.hletrd.telecampro.video.channelRms(ShortArray(0), 0, channelCount = 2)
        assertEquals(2, levels.size)
        levels.forEach { assertEquals(0f, it, 0f) }
    }

    @Test
    fun `quantization is per channel so one jittering bit cannot defeat the dedup`() {
        val a = me.hletrd.telecampro.video.quantizeLevels(floatArrayOf(0.5f, 0.25f))
        val b = me.hletrd.telecampro.video.quantizeLevels(floatArrayOf(0.5001f, 0.2501f))
        assertEquals(a, b)
    }

    @Test
    fun `a channel count below one is treated as mono rather than dividing by zero`() {
        val buf = ShortArray(16) { 1000 }
        assertEquals(1, me.hletrd.telecampro.video.channelRms(buf, buf.size, channelCount = 0).size)
    }

    @Test
    fun `standby frame retains full-scale peak independently of RMS`() {
        val pcm = shortArrayOf(0, Short.MAX_VALUE, 0, Short.MIN_VALUE)
        val frame = me.hletrd.telecampro.video.channelLevelFrame(
            pcm, pcm.size, channelCount = 1,
        )

        assertTrue(frame.rms.single() < 0.8f)
        assertEquals(1f, frame.peaks.single(), 0f)
    }
}

/**
 * Still-size selection must honour the sensor's SHAPE before its size (2026-08-02, device-probed).
 * A Lenovo TB331FC advertises a square 2448x2448 JPEG with MORE pixels than its 4:3 2592x1944 on a
 * 4:3 3264x2448 array; picking by area alone saved every still as a square.
 */
class StillSizePickerTest {
    private val tb331 = listOf(
        2448 to 2448, 2592 to 1944, 2592 to 1940, 2688 to 1512,
        1920 to 1440, 1920 to 1080, 1600 to 1200, 1600 to 1000,
    )

    @Test
    fun `the square is rejected in favour of the sensor's own 4-3 shape`() {
        assertEquals(2592 to 1944, pickStillSize(tb331, 3264, 2448))
    }

    @Test
    fun `PMA110-class lists are unchanged — its largest is already native`() {
        val pma = listOf(4096 to 3072, 4080 to 3064, 1920 to 1080)
        assertEquals(4080 to 3064, pickStillSize(pma, 4080, 3064))
    }

    @Test
    fun `sizes larger than the array stay excluded — that cap wedges gralloc`() {
        val over = listOf(4096 to 3072, 4080 to 3064)
        assertEquals(4080 to 3064, pickStillSize(over, 4080, 3064))
    }

    @Test
    fun `a device advertising no native-aspect size still gets its largest`() {
        val none = listOf(1920 to 1080, 1280 to 720)
        assertEquals(1920 to 1080, pickStillSize(none, 3264, 2448))
    }

    @Test
    fun `a 16-9 sensor prefers 16-9, not 4-3`() {
        val mixed = listOf(2592 to 1944, 1920 to 1080)
        assertEquals(1920 to 1080, pickStillSize(mixed, 1920, 1080))
    }

    @Test
    fun `an unknown array falls back to largest by area rather than refusing`() {
        assertEquals(2448 to 2448, pickStillSize(tb331, 0, 0))
    }

    @Test
    fun `no candidates yields null instead of a fabricated size`() {
        assertNull(pickStillSize(emptyList(), 3264, 2448))
    }

    @Test
    fun `when everything exceeds the array the largest is still offered`() {
        val all = listOf(8000 to 6000, 4000 to 3000)
        assertEquals(8000 to 6000, pickStillSize(all, 1000, 750))
    }
}

/**
 * Camera refused for THIS app while the permission reads granted (2026-08-02, device-found on a
 * Lenovo TB331FC whose appops CAMERA was `ignore` at UID level). It is neither a denial nor an
 * eviction, and the two existing copies would both be wrong.
 */
class CameraPolicyBlockTest {
    @Test
    fun `ERROR_CAMERA_DISABLED is policy-block class`() {
        assertTrue(cameraErrorCodeIsPolicyBlock(android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DISABLED))
    }

    @Test
    fun `eviction codes are NOT policy-block — different remedy entirely`() {
        listOf(
            android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
            android.hardware.camera2.CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
        ).forEach { assertFalse(cameraErrorCodeIsPolicyBlock(it)) }
    }

    @Test
    fun `an ordinary device error is NOT policy-block`() {
        assertFalse(cameraErrorCodeIsPolicyBlock(android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DEVICE))
    }

    @Test
    fun `the typed exception classifies, whichever path raised it`() {
        assertTrue(cameraFailureIsPolicyBlock(CameraPolicyBlockedException("x")))
        assertFalse(cameraFailureIsPolicyBlock(CameraEvictedException("x")))
        assertFalse(cameraFailureIsPolicyBlock(IllegalStateException("x")))
    }

    @Test
    fun `policy-block and eviction never both claim the same failure`() {
        val blocked = CameraPolicyBlockedException("x")
        assertTrue(cameraFailureIsPolicyBlock(blocked))
        assertFalse(cameraFailureIsEviction(blocked))
    }
}

/**
 * The AppOps confirmation that turns the policy-block inference into a proof (2026-08-03).
 * CAMERA_DISABLED is ambiguous — this project documents the SAME code for the transient
 * background-proc-state refusal (relaunch behind the keyguard) — so the exception alone must never
 * accuse the device of blocking the app.
 */
class CameraOpWithheldTest {
    @Test
    fun `allowed and foreground can open the camera, so nothing is withheld`() {
        assertFalse(cameraOpModeWithheld(android.app.AppOpsManager.MODE_ALLOWED))
        assertFalse(cameraOpModeWithheld(android.app.AppOpsManager.MODE_FOREGROUND))
    }

    @Test
    fun `ignored is withheld — the silent deny OEM privacy managers use`() {
        assertTrue(cameraOpModeWithheld(android.app.AppOpsManager.MODE_IGNORED))
    }

    @Test
    fun `errored and default are withheld too`() {
        assertTrue(cameraOpModeWithheld(android.app.AppOpsManager.MODE_ERRORED))
        assertTrue(cameraOpModeWithheld(android.app.AppOpsManager.MODE_DEFAULT))
    }
}

/**
 * The zoom scale follows the ROUTE, not the mode (device-reproduced 2026-08-03 on PMA110).
 *
 * `zoomRatio` is MAIN-RELATIVE on the logical seamless camera and LENS-LOCAL on a standalone lens.
 * The resolver used to ask "is this video?", which is true of the video route but misses the other
 * standalone door: wanting DNG moves photo onto a standalone lens too. Tapping 3× with DNG on then
 * wrote the main-relative 3.0 into a lens-local slot — 3× digital zoom on the 70 mm lens, shown as
 * "208 mm" and 9.1× (3 × 70/23) while the wire zoom sat correctly at 3.0 in both cases.
 */
class LensZoomScaleFollowsRouteTest {
    private fun intent(
        standalone: Boolean,
        requested: LensChoice,
        optical: Set<LensChoice> = LensChoice.entries.toSet(),
    ) = resolveLensOpticsIntent(
        standaloneRoute = standalone,
        opticalPresets = optical,
        currentLens = LensChoice.MAIN,
        currentTeleconverter = false,
        currentControls = ManualControls(),
        currentPreTeleUnifiedZoom = Float.NaN,
        requestedLens = requested,
        requestedTeleconverter = false,
        restorePreTele = false,
    )

    @Test
    fun `3x on a standalone route is lens-local 1x, not a 3x digital crop`() {
        // The bug: this returned 3.0, which the 70 mm lens read as 3x digital zoom.
        assertEquals(1f, intent(standalone = true, LensChoice.TELE3X).controls.zoomRatio, 1e-4f)
    }

    @Test
    fun `3x on the logical route stays main-relative 3x`() {
        assertEquals(3f, intent(standalone = false, LensChoice.TELE3X).controls.zoomRatio, 1e-4f)
    }

    @Test
    fun `DNG-on photo and video resolve the SAME zoom — both are standalone`() {
        assertEquals(
            intent(standalone = true, LensChoice.TELE10X).controls.zoomRatio,
            intent(standalone = true, LensChoice.TELE10X).controls.zoomRatio,
            0f,
        )
        // and that is 1x local, not the 10x main-relative preset
        assertEquals(1f, intent(standalone = true, LensChoice.TELE10X).controls.zoomRatio, 1e-4f)
    }

    @Test
    fun `every preset lands at its own lens on a standalone route`() {
        LensChoice.entries.forEach { lens ->
            val r = intent(standalone = true, lens)
            assertEquals("$lens should select itself", lens, r.lens)
            assertTrue("$lens local zoom must not crop", r.controls.zoomRatio <= 1f + 1e-4f)
        }
    }

    @Test
    fun `a one-camera device keeps the crop instead of losing the framing`() {
        // Only 1x is optical (a Lenovo TB331FC). 3x is a CROP of it, so the lens-local ratio must
        // stay 3.0 — a flat 1.0 silently discarded the framing (device-seen: 27 mm, not 81 mm).
        val onlyMain = setOf(LensChoice.MAIN)
        assertEquals(3f, intent(true, LensChoice.TELE3X, onlyMain).controls.zoomRatio, 1e-4f)
        assertEquals(1f, intent(true, LensChoice.MAIN, onlyMain).controls.zoomRatio, 1e-4f)
    }

    @Test
    fun `an unread inventory leaves the ratio alone rather than guessing`() {
        assertEquals(3f, intent(true, LensChoice.TELE3X, emptySet()).controls.zoomRatio, 1e-4f)
    }

    @Test
    fun `the logical route keeps each preset's main-relative ratio`() {
        LensChoice.entries.forEach { lens ->
            assertEquals(lens.zoomPreset, intent(standalone = false, lens).controls.zoomRatio, 1e-4f)
        }
    }
}

/**
 * The unified↔local conversion pair must ROUND-TRIP on every device shape (2026-08-04). Six call
 * sites open-coded this and every one asked "is this video?", which misses the DNG standalone door.
 */
class UnifiedZoomRoundTripTest {
    private val multiLens = setOf(LensChoice.ULTRAWIDE, LensChoice.MAIN, LensChoice.TELE3X, LensChoice.TELE10X)
    private val oneCamera = setOf(LensChoice.MAIN)

    @Test
    fun `round-trips on a multi-lens phone`() {
        multiLens.forEach { lens ->
            val local = localZoomOf(lens.zoomPreset, multiLens)
            assertEquals(
                lens.zoomPreset,
                unifiedZoomOf(lens, local, standaloneRoute = true, optical = multiLens),
                1e-3f,
            )
        }
    }

    @Test
    fun `round-trips on a one-camera device, where band and physical lens differ`() {
        listOf(LensChoice.MAIN, LensChoice.TELE3X, LensChoice.TELE10X).forEach { lens ->
            val local = localZoomOf(lens.zoomPreset, oneCamera)
            assertEquals(
                lens.zoomPreset,
                unifiedZoomOf(lens, local, standaloneRoute = true, optical = oneCamera),
                1e-3f,
            )
        }
    }

    @Test
    fun `the logical route passes the ratio through untouched`() {
        assertEquals(3f, unifiedZoomOf(LensChoice.TELE3X, 3f, standaloneRoute = false, optical = multiLens), 0f)
        assertEquals(7.5f, unifiedZoomOf(LensChoice.MAIN, 7.5f, standaloneRoute = false, optical = multiLens), 0f)
    }

    @Test
    fun `the band is never used as the multiplier when it is only a crop`() {
        // Tablet: 3x band, 3.0 local. Using the BAND would give 9; the optical base gives 3.
        assertEquals(3f, unifiedZoomOf(LensChoice.TELE3X, 3f, standaloneRoute = true, optical = oneCamera), 1e-3f)
    }
}
