package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
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
    fun `an unreported duration is treated as no constraint`() {
        assertTrue(zslStreamKeepsPreviewFluid(0L))
    }

    @Test
    fun `a slow large-YUV device keeps the reader off the repeating request`() {
        // 50 ms = 20 fps, 100 ms = 10 fps: both below the 24 fps floor.
        assertFalse(zslStreamKeepsPreviewFluid(50_000_000L))
        assertFalse(zslStreamKeepsPreviewFluid(100_000_000L))
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
        assertEquals(ColorTransfer.entries.toList(), availableTransfers(tenBitEncodeAvailable = true))
    }

    @Test
    fun `an 8-bit-only encoder offers SDR alone`() {
        assertEquals(listOf(ColorTransfer.SDR), availableTransfers(tenBitEncodeAvailable = false))
    }

    @Test
    fun `SDR is never withheld — it is the 8-bit BT709 case by construction`() {
        assertTrue(ColorTransfer.SDR in availableTransfers(true))
        assertTrue(ColorTransfer.SDR in availableTransfers(false))
    }

    @Test
    fun `a persisted HLG or log selection falls back to SDR on an 8-bit encoder`() {
        listOf(ColorTransfer.HLG, ColorTransfer.SLOG3, ColorTransfer.SLOG3_CINE, ColorTransfer.LOGC3)
            .forEach { assertEquals(ColorTransfer.SDR, it.normalizedForEncoder(false)) }
    }

    @Test
    fun `PMA110-class hardware keeps whatever the operator chose`() {
        ColorTransfer.entries.forEach { assertEquals(it, it.normalizedForEncoder(true)) }
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
}
