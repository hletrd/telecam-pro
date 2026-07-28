package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smooth-preview boost flip REBUILDS the repeating request, and a rebuild costs this HAL a
 * documented ~180 ms preview stall. [boostFlipChangesFpsDecision] is what lets the flip skip that
 * rebuild when it provably cannot change a request key.
 *
 * These are ROUTE conclusions, not a restatement of the expression: the app-side exposure route —
 * this device's default stills route, since photo PROGRAM runs app-side here — cannot move the fps
 * decision at all, while the two HAL-AE routes still can and must keep rebuilding, because their
 * cadence pin is what stops a 29.97 selection from recording as a real 25 fps in low light.
 */
class BoostFlipFpsDecisionTest {

    private fun control(
        manualSensor: Boolean = true,
        aeModes: IntArray = intArrayOf(
            CameraMetadata.CONTROL_AE_MODE_OFF,
            CameraMetadata.CONTROL_AE_MODE_ON,
        ),
    ) = CameraControlCapabilities(
        supportsManualSensor = manualSensor,
        hasIsoRange = true,
        isoMin = 100,
        isoMax = 12_800,
        hasExposureTimeRange = true,
        exposureTimeMinNs = 100_000L,
        exposureTimeMaxNs = 4_000_000_000L,
        aeModes = aeModes,
    )

    /** App-side exposure: PROGRAM with `programAppSide`, or any of S/ISO/M. */
    private val appSide = ManualControls(exposureMode = ExposureMode.MANUAL)

    /** HAL AE: PROGRAM that is NOT app-side — video-P and flash-metered P. */
    private val halAe = ManualControls(exposureMode = ExposureMode.PROGRAM, programAppSide = false)

    @Test
    fun `an app-side exposure route cannot move the fps decision, so the flip need not rebuild`() {
        // manualAe is already true, so `pinAutoFps || manualAe` is true either way — the request the
        // flip would rebuild is byte-identical to the one already on the wire.
        assertFalse(boostFlipChangesFpsDecision(pinAutoFps = false, c = appSide, control = control()))
    }

    @Test
    fun `a HAL-AE route CAN move it, so the flip must still rebuild`() {
        // The case the boost exists for: here the flip genuinely switches autoFpsRange to
        // fixedFpsRange, which is the pin that protects the selected recording cadence.
        assertTrue(boostFlipChangesFpsDecision(pinAutoFps = false, c = halAe, control = control()))
    }

    @Test
    fun `a route already pinning fps cannot move either, whatever the exposure mode`() {
        // Video mode sets pinAutoFps outright, so the boost's `pinAutoFps || boost` is a no-op.
        assertFalse(boostFlipChangesFpsDecision(pinAutoFps = true, c = halAe, control = control()))
        assertFalse(boostFlipChangesFpsDecision(pinAutoFps = true, c = appSide, control = control()))
    }

    @Test
    fun `an exposure mode the device cannot honour counts as HAL-AE, not app-side`() {
        // The skip keys on the SAME admission the request build uses, not on the user's chosen mode:
        // a route asking for AE-OFF on a camera without manual sensor still rides the HAL AE, so its
        // flip really does change the fps decision and must rebuild. Keying on `exposureMode` alone
        // would have skipped the rebuild there and silently unpinned the cadence.
        assertTrue(
            boostFlipChangesFpsDecision(
                pinAutoFps = false,
                c = appSide,
                control = control(manualSensor = false),
            ),
        )
        assertTrue(
            boostFlipChangesFpsDecision(
                pinAutoFps = false,
                c = appSide,
                control = control(aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON)),
            ),
        )
    }

    @Test
    fun `the two spellings agree, so the wire and the host cannot disagree about the skip`() {
        // manualAeAdmitted carries the same rule; if these ever diverged the controller would skip a
        // rebuild the request build still needed.
        listOf(appSide, halAe).forEach { c ->
            listOf(true, false).forEach { pinned ->
                assertFalse(
                    "skip must equal !pin && manualAe for $c pinned=$pinned",
                    boostFlipChangesFpsDecision(pinned, c, control()) !=
                        (!pinned && !manualAeAdmitted(c, control())),
                )
            }
        }
    }
}
