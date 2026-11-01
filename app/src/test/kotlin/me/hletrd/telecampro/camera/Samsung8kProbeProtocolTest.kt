package me.hletrd.telecampro.camera

import android.hardware.camera2.params.SessionConfiguration
import android.util.Size
import me.hletrd.findx9tele.probe.Samsung8kProbeOpenLifecycle
import me.hletrd.findx9tele.probe.closeSamsung8kProbeOnTerminal
import me.hletrd.findx9tele.probe.runSamsung8kProbeStage
import me.hletrd.findx9tele.probe.samsung8kProbeProtocolForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Samsung8kProbeProtocolTest {
    @Test fun `S20 uses camera 50 at 24 fps`() {
        val p = samsung8kProbeProtocolForTest("SM-G981N")!!

        assertEquals("50", p.cameraId)
        assertEquals(24, p.recordingFps)
    }

    @Test fun `S23 uses camera 56 at 30 fps`() {
        val p = samsung8kProbeProtocolForTest("SM-S918N")!!

        assertEquals("56", p.cameraId)
        assertEquals(30, p.recordingFps)
    }

    @Test fun `unsupported models have no probe`() {
        assertNull(samsung8kProbeProtocolForTest("SM-F741N"))
        assertNull(samsung8kProbeProtocolForTest("PMA110"))
    }

    @Test fun `both probes use the measured stock 8K record options`() {
        listOf("SM-G981N", "SM-S918N").forEach { model ->
            val p = samsung8kProbeProtocolForTest(model)!!

            assertEquals(Size(7680, 4320), p.recordSize)
            assertEquals(0x410000, p.recordStreamUseCase)
            assertEquals(0x104, p.recordDataspace)
        }
    }

    @Test fun `both probes use a default session without physical camera routing`() {
        listOf("SM-G981N", "SM-S918N").forEach { model ->
            val p = samsung8kProbeProtocolForTest(model)!!

            assertEquals(SessionConfiguration.SESSION_REGULAR, p.sessionType)
            assertNull(p.physicalCameraId)
        }
    }

    @Test fun `both probes start with the stock preview stream`() {
        listOf("SM-G981N", "SM-S918N").forEach { model ->
            val p = samsung8kProbeProtocolForTest(model)!!

            assertEquals(Size(1920, 1080), p.previewSize)
        }
    }

    @Test fun `stock auxiliary streams use the measured per-model sizes and dataspace`() {
        val s20 = samsung8kProbeProtocolForTest("SM-G981N")!!
        assertEquals(Size(1920, 1080), s20.previewCallbackSize)
        assertEquals(Size(4032, 2268), s20.jpegCaptureSize)
        assertEquals(Size(4032, 2268), s20.yuvCaptureSize)

        val s23 = samsung8kProbeProtocolForTest("SM-S918N")!!
        assertEquals(Size(1920, 1080), s23.previewCallbackSize)
        assertEquals(Size(4000, 2252), s23.jpegCaptureSize)
        assertEquals(Size(4000, 2252), s23.yuvCaptureSize)

        listOf(s20, s23).forEach { p ->
            assertEquals(0x08c20000, p.auxiliaryDataspace)
            assertEquals(0x10000, p.previewStreamUseCase)
            assertEquals(0x20000, p.captureStreamUseCase)
            assertEquals(0x41, p.recordStreamOption)
        }
    }

    @Test fun `legacy session parameters preserve their measured names and values`() {
        listOf("SM-G981N" to 24, "SM-S918N" to 30).forEach { (model, fps) ->
            val p = samsung8kProbeProtocolForTest(model)!!

            assertEquals(
                mapOf(
                    "samsungcamera" to true,
                    "shootingmode" to 3,
                    "recording-fps" to fps,
                    "sw-vdis" to true,
                    "recording_dr_mode" to "sdr",
                    "stream_type" to 0,
                ),
                p.legacySessionParameters,
            )
        }
    }

    @Test fun `S20 session keys preserve the accepted vendor values and types`() {
        val p = samsung8kProbeProtocolForTest("SM-G981N")!!
        val prefix = "samsung.android.control."

        assertEquals(
            mapOf(
                "${prefix}ssmShotMode" to 0,
                "${prefix}fastCaptureOption" to 0,
                "${prefix}swSuperVideoStabilization" to 0.toByte(),
                "${prefix}specialImageQualityPolicy" to 0L,
                "${prefix}videoBeautyFace" to 0.toByte(),
                "${prefix}swVideoStabilization" to 1.toByte(),
                "${prefix}recordingMaxFps" to 24,
                "${prefix}cameraClient" to 0,
                "${prefix}recordingDrMode" to 0.toByte(),
            ),
            p.sessionParameters,
        )
    }

    @Test fun `S23 session keys preserve the accepted vendor values and types`() {
        val p = samsung8kProbeProtocolForTest("SM-S918N")!!
        val prefix = "samsung.android.control."

        assertEquals(
            mapOf(
                "${prefix}recordingDrMode" to 0.toByte(),
                "${prefix}recordingMaxFps" to 30,
                "${prefix}recordingMinFps" to 30,
                "${prefix}recordingExtraMode" to 0,
                "${prefix}externalLensType" to 0,
                "${prefix}swVideoStabilization" to 1.toByte(),
                "${prefix}specialImageQualityPolicy" to 2L,
                "${prefix}cameraClient" to 0,
                "${prefix}videoBeautyFace" to 0.toByte(),
                "${prefix}swSuperVideoStabilization" to 0.toByte(),
                "${prefix}sensorFlipMode" to 0,
                "${prefix}initialZoomRatio" to 1.0f,
                "${prefix}fastCaptureOption" to 0,
                "${prefix}adaptivePixelMode" to 0,
                "${prefix}ssmShotMode" to 0,
                "${prefix}colorSpaceMode" to 0,
                "${prefix}sensor.streamType" to 0,
            ),
            p.sessionParameters,
        )
    }

    @Test fun `repeating requests carry the registered shooting mode`() {
        listOf("SM-G981N", "SM-S918N").forEach { model ->
            val p = samsung8kProbeProtocolForTest(model)!!

            assertEquals(
                mapOf("samsung.android.control.shootingMode" to 3),
                p.repeatingParameters,
            )
        }
    }

    @Test fun `terminal camera callback closes the supplied device before failure cleanup`() {
        val events = mutableListOf<String>()
        val device = AutoCloseable { events += "device_close" }

        closeSamsung8kProbeOnTerminal(device) { events += "failure_cleanup" }

        assertEquals(listOf("device_close", "failure_cleanup"), events)
    }

    @Test fun `destroy during an open waits for its terminal callback before thread shutdown`() {
        val lifecycle = Samsung8kProbeOpenLifecycle()
        lifecycle.openStarted()

        assertFalse(lifecycle.destroy())
        assertTrue(lifecycle.openTerminalReached())
    }

    @Test fun `destroy without an open in flight permits immediate thread shutdown`() {
        assertTrue(Samsung8kProbeOpenLifecycle().destroy())
    }

    @Test fun `stage runner reports the actual failing setup stage`() {
        var reportedStage: String? = null

        val completed = runSamsung8kProbeStage(
            "create_encoder",
            { stage, _ -> reportedStage = stage },
        ) { throw IllegalStateException("encoder refused") }

        assertFalse(completed)
        assertEquals("create_encoder", reportedStage)
    }
}
