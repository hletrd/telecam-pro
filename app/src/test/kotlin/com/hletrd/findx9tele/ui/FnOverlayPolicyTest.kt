package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.FnSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class FnOverlayPolicyTest {

    @Test
    fun `Fn overlay preserves active order while deduplicating and capping at eight`() {
        val active = listOf(
            FnSlot.ISO,
            FnSlot.WB,
            FnSlot.ISO,
            FnSlot.FOCUS,
            FnSlot.SHUTTER,
            FnSlot.EV,
            FnSlot.ZOOM,
            FnSlot.GRID,
            FnSlot.LEVEL,
            FnSlot.ZEBRA,
        )

        assertEquals(
            listOf(
                FnSlot.ISO,
                FnSlot.WB,
                FnSlot.FOCUS,
                FnSlot.SHUTTER,
                FnSlot.EV,
                FnSlot.ZOOM,
                FnSlot.GRID,
                FnSlot.LEVEL,
            ),
            fnOverlaySlots(CaptureMode.PHOTO, active),
        )
    }

    @Test
    fun `empty active list falls back to the selected shooting mode`() {
        assertEquals(FnSlot.PHOTO_DEFAULT, fnOverlaySlots(CaptureMode.PHOTO, emptyList()))
        assertEquals(FnSlot.VIDEO_DEFAULT, fnOverlaySlots(CaptureMode.VIDEO, emptyList()))
    }

    @Test
    fun `quarter turn axes preserve label above value in either hold`() {
        listOf(90, 450).forEach {
            assertEquals(FnTileContentAxis.HELD_LANDSCAPE_VALUE_FIRST_RAW, fnTileContentAxis(it))
        }
        listOf(270, -90).forEach {
            assertEquals(FnTileContentAxis.HELD_LANDSCAPE_LABEL_FIRST_RAW, fnTileContentAxis(it))
        }
        listOf(0, 180, 360, -180).forEach {
            assertEquals(FnTileContentAxis.PORTRAIT, fnTileContentAxis(it))
        }
    }

    @Test
    fun `held tray uses raw 2x4 side anchors and preserves physical 4x2 order`() {
        val slots = FnSlot.entries.take(8)

        assertEquals(
            FnOverlayLayoutPolicy(FN_OVERLAY_HELD_COLUMN_COUNT, FnOverlayAnchor.CENTER_START),
            fnOverlayLayoutPolicy(90),
        )
        assertEquals(
            listOf(
                listOf(slots[4], slots[0]),
                listOf(slots[5], slots[1]),
                listOf(slots[6], slots[2]),
                listOf(slots[7], slots[3]),
            ),
            fnOverlayGridRows(slots, 90),
        )

        assertEquals(
            FnOverlayLayoutPolicy(FN_OVERLAY_HELD_COLUMN_COUNT, FnOverlayAnchor.CENTER_END),
            fnOverlayLayoutPolicy(270),
        )
        assertEquals(
            listOf(
                listOf(slots[3], slots[7]),
                listOf(slots[2], slots[6]),
                listOf(slots[1], slots[5]),
                listOf(slots[0], slots[4]),
            ),
            fnOverlayGridRows(slots, 270),
        )
    }

    @Test
    fun `short held lists retain blank cells instead of changing physical rows`() {
        val slots = FnSlot.PHOTO_DEFAULT

        assertEquals(
            listOf(
                listOf(slots[4], slots[0]),
                listOf(slots[5], slots[1]),
                listOf(null, slots[2]),
                listOf(null, slots[3]),
            ),
            fnOverlayGridRows(slots, 90),
        )
    }

    @Test
    fun `held landscape copy is compact without changing portrait copy`() {
        assertEquals("Stabilization", fnOverlayVisualLabel(FnSlot.STABILIZATION, false))
        assertEquals("Steady", fnOverlayVisualLabel(FnSlot.STABILIZATION, true))
        assertEquals("Gate", fnOverlayVisualLabel(FnSlot.OPEN_GATE, true))

        assertEquals("A 12750", fnOverlayVisualValue(FnSlot.ISO, "A 12750", false))
        assertEquals("A12750", fnOverlayVisualValue(FnSlot.ISO, "A 12750", true))
        assertEquals("1/60", fnOverlayVisualValue(FnSlot.SHUTTER, "A 1/60", true))
        assertEquals("Std", fnOverlayVisualValue(FnSlot.STABILIZATION, "Standard", true))
        assertEquals("Focus", fnOverlayVisualValue(FnSlot.AUDIO_SCENE, "Sound Focus", true))
        assertEquals("300mm", fnOverlayVisualValue(FnSlot.TELECONVERTER, "300 mm", true))

        // Stabilization is the longest production label. The tray uses a compact visual alias while
        // the semantic node still exports fnSlotLabel(slot), and Text ellipsizes any future overflow.
        assertEquals("Stabilization", fnOverlayVisualLabel(FnSlot.STABILIZATION, false))
        assertEquals("Steady", fnOverlayVisualLabel(FnSlot.STABILIZATION, true))
    }
}
