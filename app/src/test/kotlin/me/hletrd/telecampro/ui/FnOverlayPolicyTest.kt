package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.ui.controls.fnSlotLabel
import me.hletrd.telecampro.ui.controls.focusDialStateDescription
import me.hletrd.telecampro.ui.controls.focusModeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FnOverlayPolicyTest {

    @Test
    fun `held tray stays compact while preserving two 48dp raw columns`() {
        val innerWidth = FN_OVERLAY_HELD_WIDTH_DP - 20
        val tileWidth = (innerWidth - 8) / FN_OVERLAY_HELD_COLUMN_COUNT

        assertTrue(tileWidth >= 48)
        assertTrue(FN_OVERLAY_SCRIM_ALPHA <= 0.25f)
    }

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
    fun `Fn entry stays on the physical bottom edge in either hold`() {
        listOf(0, 90, 180, 360, 450).forEach {
            assertEquals(FnEntryAnchor.START, fnEntryAnchor(it))
        }
        listOf(270, -90).forEach {
            assertEquals(FnEntryAnchor.END, fnEntryAnchor(it))
        }
    }

    @Test
    fun `held tray uses raw 2x4 side anchors and preserves physical 4x2 order`() {
        val slots = FnSlot.entries.take(8)

        assertEquals(
            FnOverlayAnchor.CENTER_START,
            fnOverlayAnchor(90),
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
            FnOverlayAnchor.CENTER_END,
            fnOverlayAnchor(270),
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
    fun `portrait keeps the bottom 4-column tray in both upright orientations`() {
        listOf(0, 180).forEach {
            assertEquals(
                FnOverlayAnchor.BOTTOM_CENTER,
                fnOverlayAnchor(it),
            )
        }
    }

    @Test
    fun `portrait rows chunk in reading order and pad the last row with blanks`() {
        val slots = FnSlot.PHOTO_DEFAULT

        assertEquals(
            listOf(
                listOf<FnSlot?>(slots[0], slots[1], slots[2], slots[3]),
                listOf<FnSlot?>(slots[4], slots[5], null, null),
            ),
            fnOverlayGridRows(slots, 0),
        )
    }

    @Test
    fun `held landscape compact identities follow typed camera state`() {
        assertEquals(
            FnOverlayCompactValue.WhiteBalanceDaylight,
            fnOverlayCompactValue(
                FnSlot.WB,
                CameraUiState(controls = CameraUiState().controls.copy(wbMode = WbMode.DAYLIGHT)),
            ),
        )
        assertEquals(
            FnOverlayCompactValue.WhiteBalanceTungsten,
            fnOverlayCompactValue(
                FnSlot.WB,
                CameraUiState(controls = CameraUiState().controls.copy(wbMode = WbMode.INCANDESCENT)),
            ),
        )
        assertEquals(
            FnOverlayCompactValue.Standard,
            fnOverlayCompactValue(FnSlot.STABILIZATION, CameraUiState(videoStabMode = VideoStabMode.STANDARD)),
        )
        assertEquals(
            FnOverlayCompactValue.Timelapse,
            fnOverlayCompactValue(FnSlot.DRIVE, CameraUiState(driveMode = DriveMode.TIMELAPSE)),
        )
        assertEquals(
            FnOverlayCompactValue.SoundFocus,
            fnOverlayCompactValue(FnSlot.AUDIO_SCENE, CameraUiState(audioScene = AudioScene.SOUND_FOCUS)),
        )
        assertEquals(
            FnOverlayCompactValue.SoundStage,
            fnOverlayCompactValue(FnSlot.AUDIO_SCENE, CameraUiState(audioScene = AudioScene.SOUND_STAGE)),
        )
        assertEquals(
            FnOverlayCompactValue.Standard,
            fnOverlayCompactValue(FnSlot.AUDIO_SCENE, CameraUiState(audioScene = AudioScene.STANDARD)),
        )
        assertEquals(
            FnOverlayCompactValue.TeleconverterFocal(300),
            fnOverlayCompactValue(FnSlot.TELECONVERTER, CameraUiState(teleconverterMode = true)),
        )
        assertNull(fnOverlayCompactValue(FnSlot.WB, CameraUiState()))
        assertNull(fnOverlayCompactValue(FnSlot.GRID, CameraUiState()))
        assertNull(fnOverlayCompactValue(FnSlot.TELECONVERTER, CameraUiState()))
        assertEquals("ISO", fnOverlayVisualLabel(FnSlot.ISO, true))
    }

    @Test
    fun `held landscape copy is compact without changing portrait copy`() {
        // Pinned as literals, not as `== fnSlotLabel(slot)`: the portrait branch IS
        // `fnSlotLabel(slot)` for most slots, and comparing it to itself would pass no matter what
        // either side became. STABILIZATION compresses in BOTH axes since the tiles gained icons
        // (2026-07-31): the 14 dp glyph ate the width that let the full word fit portrait tiles.
        assertEquals("Stab", fnOverlayVisualLabel(FnSlot.STABILIZATION, false))
        assertEquals("Stab", fnOverlayVisualLabel(FnSlot.STABILIZATION, true))
        assertEquals("Open Gate", fnOverlayVisualLabel(FnSlot.OPEN_GATE, false))
        assertEquals("Gate", fnOverlayVisualLabel(FnSlot.OPEN_GATE, true))

        val program = CameraUiState()
        assertEquals(FnOverlayCompactValue.Auto("--"), fnOverlayCompactValue(FnSlot.ISO, program))
        assertEquals(FnOverlayCompactValue.Auto("--"), fnOverlayCompactValue(FnSlot.SHUTTER, program))
        val isoPriority = program.copy(
            controls = program.controls.copy(
                exposureMode = ExposureMode.ISO,
                exposureTimeNs = 50_000_000L,
            ),
        )
        assertEquals(
            FnOverlayCompactValue.Auto("1/20s"),
            fnOverlayCompactValue(FnSlot.SHUTTER, isoPriority),
        )
        // A manual value has no auto marker to compact.
        val manual = program.copy(controls = program.controls.copy(exposureMode = ExposureMode.MANUAL))
        assertNull(fnOverlayCompactValue(FnSlot.ISO, manual))
        assertNull(fnOverlayCompactValue(FnSlot.SHUTTER, manual))

        // Stabilization is the longest production label. Since the tiles gained icons (2026-07-31)
        // it compresses in BOTH axes — the 14 dp glyph ate the width that let the full word fit a
        // portrait tile. The semantic node still exports fnSlotLabel(slot) (pinned below), and the
        // alias must not be "Steady": the OSD spends that word on one specific VALUE (ENHANCED).
        assertEquals("Stab", fnOverlayVisualLabel(FnSlot.STABILIZATION, false))
        assertEquals("Stab", fnOverlayVisualLabel(FnSlot.STABILIZATION, true))
    }

    @Test
    fun `one slot has one spoken name across the Fn tray and the persistent dial chip`() {
        // The invariant whose absence let FOCUS and SHUTTER drift: a slot is ONE control shown on two
        // surfaces (the Fn tray tile in CameraScreen, the always-visible dial chip in ManualDials), and
        // both name themselves from fnSlotLabel. The tray tile's contentDescription is fnSlotLabel(slot)
        // directly; the dial chip's accessibleName defaults to its DRAWN label, so the two slots whose
        // pill text is not their name pass fnSlotLabel(slot) explicitly.
        FnSlot.entries.forEach { slot ->
            assertTrue("every slot needs a spoken name, $slot has none", fnSlotLabel(slot).isNotBlank())
        }
        // "One slot, one spoken name" also runs the other way, and THAT is the half a per-slot loop
        // cannot see: two slots sharing a name gives the tray two tiles TalkBack reads identically,
        // with nothing in the node to tell them apart. (The portrait tray's no-shortening rule is a
        // property of fnOverlayVisualLabel and is pinned as literals in the visual-copy test above;
        // this test used to restate its `!heldLandscape -> fnSlotLabel(slot)` branch as
        // `fnSlotLabel(slot) == fnOverlayVisualLabel(slot, false)`, which is the same expression on
        // both sides and so proved nothing about either surface.)
        assertEquals(
            "two Fn slots must never share one spoken name",
            FnSlot.entries.size,
            FnSlot.entries.map { fnSlotLabel(it) }.toSet().size,
        )

        // FOCUS draws the LIVE AF mode, so its drawn label is not (and must never become) its name —
        // a node renamed on every AF cycle is a node TalkBack cannot keep focus on.
        assertEquals("Focus", fnSlotLabel(FnSlot.FOCUS))
        FocusMode.entries.forEach { mode ->
            assertNotEquals(
                "the FOCUS chip's drawn label is a value; the spoken name must not be it",
                fnSlotLabel(FnSlot.FOCUS),
                focusModeLabel(mode),
            )
        }
        // SHUTTER draws "SS", a printed abbreviation with no spoken form.
        assertEquals("Shutter", fnSlotLabel(FnSlot.SHUTTER))
        assertNotEquals("SS", fnSlotLabel(FnSlot.SHUTTER))

        // The held tray is the ONE surface allowed to shorten; it shortens the VISIBLE label only.
        assertEquals("Stab", fnOverlayVisualLabel(FnSlot.STABILIZATION, heldLandscape = true))
        assertEquals("Stabilization", fnSlotLabel(FnSlot.STABILIZATION))
    }

    @Test
    fun `pinning the FOCUS chip's name to its slot must not cost the AF mode`() {
        // The other half of the rule above. FOCUS is the one dial chip whose pill spends its LABEL
        // slot on a live value, so moving the node's NAME to the stable slot name left the mode with
        // nowhere to be spoken: a sighted user read "MF ∞+42" while TalkBack heard "Focus, ∞+42".
        // The mode belongs in the STATE, beside the distance the pill draws next to it.
        //
        // Exhaustive, and as INDEPENDENT literals rather than `contains(focusModeLabel(mode))`: the
        // production string is built from focusModeLabel, so a containment check phrased in those
        // terms would still pass if both sides drifted together. The map must cover every FocusMode,
        // so a new mode fails here instead of shipping a silently modeless chip.
        val expected = mapOf(
            FocusMode.MANUAL to "MF, ∞+42",
            FocusMode.AUTO to "AF, ∞+42",
            FocusMode.CONTINUOUS to "AF-C, ∞+42",
            FocusMode.MACRO to "Macro, ∞+42",
        )
        assertEquals("every FocusMode needs a decided spoken state", FocusMode.entries.toSet(), expected.keys)
        FocusMode.entries.forEach { mode ->
            assertEquals(expected.getValue(mode), focusDialStateDescription(mode, "∞+42"))
        }
        // The distance is passed through, not re-derived: "∞" at the infinity end reads as itself.
        assertEquals("MF, ∞", focusDialStateDescription(FocusMode.MANUAL, "∞"))
    }
}
