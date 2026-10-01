package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure route-input rules the optics rollback and the DNG door share (RPL cycle 2, lane A1). */
class RouteInputRollbackTest {
    @Test
    fun `DNG moves only the non-TELE rear photo route under the standalone RAW law`() {
        assertTrue(dngIntentChangesRearRoute(video = false, teleconverter = false, from = false, to = true, rawForcesStandalone = true))
        assertTrue(dngIntentChangesRearRoute(video = false, teleconverter = false, from = true, to = false, rawForcesStandalone = true))
        // TELE already pins the standalone 3×, Video its standalone lens; a spec device keeps RAW logical.
        assertFalse(dngIntentChangesRearRoute(video = false, teleconverter = true, from = false, to = true, rawForcesStandalone = true))
        assertFalse(dngIntentChangesRearRoute(video = true, teleconverter = false, from = false, to = true, rawForcesStandalone = true))
        assertFalse(dngIntentChangesRearRoute(video = false, teleconverter = false, from = false, to = true, rawForcesStandalone = false))
        assertFalse(dngIntentChangesRearRoute(video = false, teleconverter = false, from = true, to = true, rawForcesStandalone = true))
    }

    @Test
    fun `rollback restores the baseline unless a direct write leaves the restored route alone`() {
        fun restore(
            direct: Boolean,
            video: Boolean = false,
            tele: Boolean = false,
            route: CameraRoute = CameraRoute.BACK,
            law: Boolean = true,
        ) = rollbackRawWanted(
            current = true,
            baseline = false,
            directWriteSinceBaseline = direct,
            restoredVideo = video,
            restoredTeleconverter = tele,
            restoredRoute = route,
            rawForcesStandalone = law,
        )
        assertFalse("no direct write: the transaction's own value rolls back", restore(direct = false))
        assertFalse("direct write would move restored logical Photo", restore(direct = true))
        assertTrue(restore(direct = true, video = true))
        assertTrue(restore(direct = true, tele = true))
        assertTrue(restore(direct = true, route = CameraRoute.FRONT))
        assertTrue(restore(direct = true, route = CameraRoute.EXTERNAL))
        assertTrue("a spec device keeps RAW on the logical route", restore(direct = true, law = false))
    }
}
