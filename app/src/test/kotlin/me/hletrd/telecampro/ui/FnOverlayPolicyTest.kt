package me.hletrd.telecampro.ui

import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.ui.controls.fnSlotLabel
import me.hletrd.telecampro.ui.controls.fnSlotValue
import me.hletrd.telecampro.ui.controls.focusDialStateDescription
import me.hletrd.telecampro.ui.controls.focusModeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FnOverlayPolicyTest {

    private fun lit(text: String, manual: Boolean = false) = FnTileReadout(FnTileText.Literal(text), manual)
    private fun word(res: Int, manual: Boolean = false) = FnTileReadout(FnTileText.Word(res), manual)
    private fun controls(transform: ManualControls.() -> ManualControls) =
        CameraUiState(controls = ManualControls().transform())

    @Test
    fun `the strip is compact and every target keeps the 48 dp floor`() {
        // The owner's complaint (2026-10-28) was tile SIZE: the old tile was a 56 dp-tall card in a
        // stretched 4-column grid. A tile is now 48 dp tall — exactly the touch floor, never under it.
        assertEquals(48, FN_TILE_SHORT_DP)
        assertTrue(FN_TILE_LONG_DP in 48..72)
        assertTrue(FN_CLOSE_TARGET_DP >= 48)

        // Portrait: four tiles + the close column + plate padding, and a tile at full width is the
        // designed FN_TILE_LONG_DP, not whatever the window happens to be.
        val portraitTile = (FN_OVERLAY_MAX_WIDTH_DP - 2 * FN_PANEL_PADDING_DP - FN_CLOSE_TARGET_DP -
            FN_OVERLAY_COLUMN_COUNT * FN_TILE_GAP_DP) / FN_OVERLAY_COLUMN_COUNT
        assertEquals(FN_TILE_LONG_DP, portraitTile)
        assertEquals(332, FN_OVERLAY_MAX_WIDTH_DP)

        // Held: the physical depth stays the 148 dp the device harness's 40 % budget was proven
        // against, and what it buys is two 48 dp raw tile columns plus a line for the info text.
        assertEquals(148, FN_OVERLAY_HELD_WIDTH_DP)
        val heldTile = (FN_OVERLAY_HELD_WIDTH_DP - 2 * FN_PANEL_PADDING_DP - FN_HELD_INFO_LINE_DP -
            FN_OVERLAY_HELD_COLUMN_COUNT * FN_TILE_GAP_DP) / FN_OVERLAY_HELD_COLUMN_COUNT
        assertEquals(FN_TILE_SHORT_DP, heldTile)
        // A 12 sp line at 1.2 leading is 14.4 dp; the line keeps room for it at 2x font scale too.
        assertTrue(FN_HELD_INFO_LINE_DP >= 29)
        // Four raw tile rows of the tile's physical width, then the close row.
        assertEquals(4 * (FN_TILE_LONG_DP + FN_TILE_GAP_DP) + FN_CLOSE_TARGET_DP, FN_HELD_STRIP_LENGTH_DP)
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
    fun `the info line snaps to the hold its layout was built for`() {
        // Held trays run the line along the raw Y axis at exactly the quarter turn.
        listOf(90, 450).forEach { assertEquals(90f, fnOverlayInfoRotation(it)) }
        listOf(270, -90).forEach { assertEquals(270f, fnOverlayInfoRotation(it)) }
        // Upright docking: only 0 or 180 — never an in-between angle that would balloon a wide line
        // inside its portrait slot while the glyph rotation tweens toward a quarter turn.
        listOf(0, 360, 45, 89, -45).forEach { assertEquals(0f, fnOverlayInfoRotation(it)) }
        listOf(180, -180, 91, 135, 225, 269).forEach { assertEquals(180f, fnOverlayInfoRotation(it)) }
        // The same angles that pick the held anchor pick the held rotation, and only they do.
        (-360..720).forEach { angle ->
            val held = fnOverlayAnchor(angle) != FnOverlayAnchor.BOTTOM_CENTER
            val rotation = fnOverlayInfoRotation(angle)
            assertEquals("angle $angle", held, rotation == 90f || rotation == 270f)
        }
    }

    @Test
    fun `the info line names the last touched tile, else the first usable one`() {
        val slots = listOf(FnSlot.ISO, FnSlot.WB, FnSlot.GRID)
        val onlyGrid: (FnSlot) -> Boolean = { it == FnSlot.GRID }

        assertEquals(FnSlot.GRID, fnOverlayInfoSlot(slots, lastTouched = null, enabled = onlyGrid))
        assertEquals(FnSlot.WB, fnOverlayInfoSlot(slots, lastTouched = FnSlot.WB, enabled = onlyGrid))
        // A remembered slot the mode switch dropped from the strip does not linger as the title.
        assertEquals(FnSlot.GRID, fnOverlayInfoSlot(slots, lastTouched = FnSlot.ZEBRA, enabled = onlyGrid))
        // Nothing enabled: still describe the first tile rather than show an empty line.
        assertEquals(FnSlot.ISO, fnOverlayInfoSlot(slots, lastTouched = null, enabled = { false }))
        assertNull(fnOverlayInfoSlot(emptyList(), lastTouched = FnSlot.ISO, enabled = { true }))
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
    fun `exposure tiles print the mode letter, AUTO, or the operator's own value`() {
        mapOf(
            ExposureMode.PROGRAM to "P",
            ExposureMode.SHUTTER to "S",
            ExposureMode.ISO to "ISO",
            ExposureMode.MANUAL to "M",
        ).forEach { (mode, letter) ->
            assertEquals(lit(letter), fnTileReadout(FnSlot.EXPOSURE_MODE, controls { copy(exposureMode = mode) }))
        }

        // P: the camera owns both. S: it owns ISO. ISO-priority: it owns the shutter. A camera-owned
        // value is AUTO on the tile; the live number lives in the info line and the spoken state.
        val auto = word(R.string.fn_tile_auto)
        assertEquals(auto, fnTileReadout(FnSlot.SHUTTER, controls { copy(exposureMode = ExposureMode.PROGRAM) }))
        assertEquals(auto, fnTileReadout(FnSlot.ISO, controls { copy(exposureMode = ExposureMode.PROGRAM) }))
        assertEquals(auto, fnTileReadout(FnSlot.ISO, controls { copy(exposureMode = ExposureMode.SHUTTER) }))
        assertEquals(auto, fnTileReadout(FnSlot.SHUTTER, controls { copy(exposureMode = ExposureMode.ISO) }))

        // The operator's values are printed as a camera body prints them, in the manual amber.
        val manual = controls {
            copy(exposureMode = ExposureMode.MANUAL, iso = 800, exposureTimeNs = 4_000_000L)
        }
        assertEquals(lit("800", manual = true), fnTileReadout(FnSlot.ISO, manual))
        assertEquals(lit("1/250", manual = true), fnTileReadout(FnSlot.SHUTTER, manual))
        assertEquals(
            lit("180°", manual = true),
            fnTileReadout(FnSlot.SHUTTER, controls {
                copy(exposureMode = ExposureMode.SHUTTER, shutterMode = ShutterMode.ANGLE, shutterAngle = 180f)
            }),
        )
        assertEquals(lit("1/20", manual = true), fnTileReadout(FnSlot.SHUTTER, controls {
            copy(exposureMode = ExposureMode.SHUTTER, exposureTimeNs = 50_000_000L)
        }))
    }

    @Test
    fun `a shutter fraction drops its unit but a whole second keeps it`() {
        assertEquals("1/250", fnTileShutter(4_000_000L))
        assertEquals("1/8000", fnTileShutter(125_000L))
        assertEquals("0.8s", fnTileShutter(800_000_000L))
        assertEquals("4.0s", fnTileShutter(4_000_000_000L))
        assertEquals("10s", fnTileShutter(10_000_000_000L))
    }

    @Test
    fun `tile auto-ness never disagrees with the spoken state`() {
        // The tile reads AUTO exactly when fnSlotValue speaks "Auto <live value>" — the two derive
        // from the same branch order, so a mode cannot show a fixed number while saying it is auto.
        ExposureMode.entries.forEach { mode ->
            listOf(ShutterMode.SPEED, ShutterMode.ANGLE).forEach { shutterMode ->
                val state = controls { copy(exposureMode = mode, shutterMode = shutterMode) }
                listOf(FnSlot.SHUTTER, FnSlot.ISO).forEach { slot ->
                    val spokenAuto = fnSlotValue(slot, state).startsWith("Auto ")
                    val drawnAuto = fnTileReadout(slot, state).text == FnTileText.Word(R.string.fn_tile_auto)
                    assertEquals("$slot in $mode/$shutterMode", spokenAuto, drawnAuto)
                    assertEquals("$slot in $mode/$shutterMode", !drawnAuto, fnTileReadout(slot, state).manual)
                }
            }
        }
    }

    @Test
    fun `focus and white balance tiles print camera abbreviations`() {
        assertEquals(lit("MF", manual = true), fnTileReadout(FnSlot.FOCUS, controls { copy(focusMode = FocusMode.MANUAL) }))
        assertEquals(lit("AF-S"), fnTileReadout(FnSlot.FOCUS, controls { copy(focusMode = FocusMode.AUTO) }))
        assertEquals(lit("AF-C"), fnTileReadout(FnSlot.FOCUS, controls { copy(focusMode = FocusMode.CONTINUOUS) }))
        assertEquals(word(R.string.fn_tile_macro), fnTileReadout(FnSlot.FOCUS, controls { copy(focusMode = FocusMode.MACRO) }))

        assertEquals(lit("AWB"), fnTileReadout(FnSlot.WB, controls { copy(wbMode = WbMode.AUTO) }))
        assertEquals(
            lit("5200K", manual = true),
            fnTileReadout(FnSlot.WB, controls { copy(wbMode = WbMode.MANUAL, wbKelvin = 5200) }),
        )
        mapOf(
            WbMode.INCANDESCENT to R.string.fn_tile_wb_tungsten,
            WbMode.FLUORESCENT to R.string.fn_tile_wb_fluorescent,
            WbMode.DAYLIGHT to R.string.fn_tile_wb_daylight,
            WbMode.CLOUDY to R.string.fn_tile_wb_cloudy,
            WbMode.SHADE to R.string.fn_tile_wb_shade,
            WbMode.CUSTOM to R.string.fn_tile_wb_custom,
        ).forEach { (mode, res) ->
            assertEquals(word(res, manual = true), fnTileReadout(FnSlot.WB, controls { copy(wbMode = mode) }))
        }
    }

    @Test
    fun `video tiles print the curve, stabilization, and directionality abbreviations`() {
        assertEquals(lit("709"), fnTileReadout(FnSlot.TRANSFER, CameraUiState(transfer = ColorTransfer.SDR)))
        assertEquals(lit("HLG"), fnTileReadout(FnSlot.TRANSFER, CameraUiState(transfer = ColorTransfer.HLG)))
        assertEquals(lit("SLOG3"), fnTileReadout(FnSlot.TRANSFER, CameraUiState(transfer = ColorTransfer.SLOG3)))
        assertEquals(lit("SG3C"), fnTileReadout(FnSlot.TRANSFER, CameraUiState(transfer = ColorTransfer.SLOG3_CINE)))
        assertEquals(lit("LOGC3"), fnTileReadout(FnSlot.TRANSFER, CameraUiState(transfer = ColorTransfer.LOGC3)))
        // A kept maker-log intent on a device that cannot run it records S-Log3, so the tile says so
        // instead of naming a pipeline the session will not use.
        assertEquals(lit("SLOG3"), fnTileReadout(FnSlot.TRANSFER, CameraUiState(transfer = ColorTransfer.MAKER_LOG)))

        assertEquals(word(R.string.fn_tile_off), fnTileReadout(FnSlot.STABILIZATION, CameraUiState(videoStabMode = VideoStabMode.OFF)))
        assertEquals(word(R.string.fn_tile_standard), fnTileReadout(FnSlot.STABILIZATION, CameraUiState(videoStabMode = VideoStabMode.STANDARD)))
        assertEquals(word(R.string.fn_tile_active), fnTileReadout(FnSlot.STABILIZATION, CameraUiState(videoStabMode = VideoStabMode.ENHANCED)))

        assertEquals(word(R.string.fn_tile_standard), fnTileReadout(FnSlot.AUDIO_SCENE, CameraUiState(audioScene = AudioScene.STANDARD)))
        assertEquals(word(R.string.fn_tile_sound_focus), fnTileReadout(FnSlot.AUDIO_SCENE, CameraUiState(audioScene = AudioScene.SOUND_FOCUS)))
        assertEquals(word(R.string.fn_tile_sound_stage), fnTileReadout(FnSlot.AUDIO_SCENE, CameraUiState(audioScene = AudioScene.SOUND_STAGE)))

        assertEquals(word(R.string.fn_tile_off), fnTileReadout(FnSlot.OPEN_GATE, CameraUiState(openGate = false)))
        assertEquals(lit("4:3"), fnTileReadout(FnSlot.OPEN_GATE, CameraUiState(openGate = true)))

        assertEquals(word(R.string.fn_tile_auto), fnTileReadout(FnSlot.AUDIO_INPUT, CameraUiState()))
        assertEquals(
            word(R.string.fn_tile_mic_phone),
            fnTileReadout(FnSlot.AUDIO_INPUT, CameraUiState(audioInputPreference = AudioInputPreference.BUILT_IN)),
        )
        assertEquals(
            word(R.string.fn_tile_mic_wired),
            fnTileReadout(FnSlot.AUDIO_INPUT, CameraUiState(audioInputPreference = AudioInputPreference.WIRED)),
        )
        assertEquals(lit("USB"), fnTileReadout(FnSlot.AUDIO_INPUT, CameraUiState(audioInputPreference = AudioInputPreference.USB)))
        assertEquals(lit("BT"), fnTileReadout(FnSlot.AUDIO_INPUT, CameraUiState(audioInputPreference = AudioInputPreference.BLUETOOTH)))
    }

    @Test
    fun `assist and drive tiles print short states`() {
        assertEquals(lit("±0.0"), fnTileReadout(FnSlot.EV, CameraUiState()))
        assertEquals(lit("1.0×"), fnTileReadout(FnSlot.ZOOM, CameraUiState()))

        mapOf(
            DriveMode.SINGLE to R.string.fn_tile_drive_single,
            DriveMode.BURST to R.string.fn_tile_drive_burst,
            DriveMode.AEB to R.string.fn_tile_drive_aeb,
            DriveMode.TIMELAPSE to R.string.fn_tile_drive_timelapse,
        ).forEach { (mode, res) -> assertEquals(word(res), fnTileReadout(FnSlot.DRIVE, CameraUiState(driveMode = mode))) }
        mapOf(
            MeteringMode.MATRIX to R.string.fn_tile_meter_matrix,
            MeteringMode.CENTER to R.string.fn_tile_center,
            MeteringMode.SPOT to R.string.fn_tile_meter_spot,
        ).forEach { (mode, res) ->
            assertEquals(word(res), fnTileReadout(FnSlot.METERING, controls { copy(meteringMode = mode) }))
        }

        val on = word(R.string.fn_tile_on)
        val off = word(R.string.fn_tile_off)
        assertEquals(on, fnTileReadout(FnSlot.PEAKING, CameraUiState(focusPeaking = true)))
        assertEquals(off, fnTileReadout(FnSlot.ZEBRA, CameraUiState(zebra = false)))
        assertEquals(on, fnTileReadout(FnSlot.LEVEL, CameraUiState(level = true)))
        assertEquals(off, fnTileReadout(FnSlot.PUNCH_IN, CameraUiState(punchIn = false)))

        assertEquals(off, fnTileReadout(FnSlot.GRID, CameraUiState(grid = GridType.NONE)))
        assertEquals(lit("3×3"), fnTileReadout(FnSlot.GRID, CameraUiState(grid = GridType.THIRDS)))
        assertEquals(word(R.string.fn_tile_grid_golden), fnTileReadout(FnSlot.GRID, CameraUiState(grid = GridType.GOLDEN)))
        assertEquals(word(R.string.fn_tile_grid_square), fnTileReadout(FnSlot.GRID, CameraUiState(grid = GridType.SQUARE)))
        assertEquals(word(R.string.fn_tile_center), fnTileReadout(FnSlot.GRID, CameraUiState(grid = GridType.CENTER)))

        assertEquals(off, fnTileReadout(FnSlot.FRAME_LINES, CameraUiState(frameLines = FrameLineType.OFF)))
        assertEquals(lit("2.39:1"), fnTileReadout(FnSlot.FRAME_LINES, CameraUiState(frameLines = FrameLineType.CINEMA)))
        assertEquals(lit("1:1"), fnTileReadout(FnSlot.FRAME_LINES, CameraUiState(frameLines = FrameLineType.SQUARE)))
        assertEquals(lit("9:16"), fnTileReadout(FnSlot.FRAME_LINES, CameraUiState(frameLines = FrameLineType.VERTICAL)))

        assertEquals(off, fnTileReadout(FnSlot.FLASH, controls { copy(flash = FlashMode.OFF) }))
        assertEquals(word(R.string.fn_tile_auto), fnTileReadout(FnSlot.FLASH, controls { copy(flash = FlashMode.AUTO) }))
        assertEquals(on, fnTileReadout(FnSlot.FLASH, controls { copy(flash = FlashMode.ON) }))
        assertEquals(word(R.string.fn_tile_torch), fnTileReadout(FnSlot.FLASH, controls { copy(flash = FlashMode.TORCH) }))

        assertEquals(off, fnTileReadout(FnSlot.TIMER, CameraUiState(timer = ShutterTimer.OFF)))
        assertEquals(FnTileReadout(FnTileText.Seconds(3)), fnTileReadout(FnSlot.TIMER, CameraUiState(timer = ShutterTimer.SEC3)))
        assertEquals(FnTileReadout(FnTileText.Seconds(10)), fnTileReadout(FnSlot.TIMER, CameraUiState(timer = ShutterTimer.SEC10)))

        assertEquals(lit("4:3"), fnTileReadout(FnSlot.ASPECT, CameraUiState(aspectRatio = AspectRatio.W4_3)))
        assertEquals(lit("16:9"), fnTileReadout(FnSlot.ASPECT, CameraUiState(aspectRatio = AspectRatio.W16_9)))

        assertEquals(off, fnTileReadout(FnSlot.TELECONVERTER, CameraUiState()))
        assertEquals(
            FnTileReadout(FnTileText.FocalMm(300)),
            fnTileReadout(FnSlot.TELECONVERTER, CameraUiState(teleconverterMode = true)),
        )
    }

    @Test
    fun `only operator-fixed exposure, focus, and WB values take the manual amber`() {
        val states = listOf(
            CameraUiState(),
            controls {
                copy(
                    exposureMode = ExposureMode.MANUAL,
                    focusMode = FocusMode.MANUAL,
                    wbMode = WbMode.MANUAL,
                    flash = FlashMode.TORCH,
                    meteringMode = MeteringMode.SPOT,
                )
            }.copy(
                focusPeaking = true,
                zebra = true,
                level = true,
                punchIn = true,
                teleconverterMode = true,
                openGate = true,
                driveMode = DriveMode.AEB,
                timer = ShutterTimer.SEC10,
                videoStabMode = VideoStabMode.ENHANCED,
                transfer = ColorTransfer.SLOG3,
            ),
        )
        val manualSlots = setOf(FnSlot.SHUTTER, FnSlot.ISO, FnSlot.FOCUS, FnSlot.WB)
        states.forEach { state ->
            FnSlot.entries.filterNot { it in manualSlots }.forEach { slot ->
                assertFalse("$slot is not an exposure/focus/WB value", fnTileReadout(slot, state).manual)
            }
        }
        assertTrue(manualSlots.all { fnTileReadout(it, states[1]).manual })
    }

    @Test
    fun `every literal tile glyph is Latin or a symbol the bundled Inter faces carry`() {
        // CLAUDE.md: every non-Hangul symbol in a user-facing literal must stay inside the three
        // bundled Inter faces; the allowed set is the one the 2026-10-02 scan recorded. Walk every
        // slot through a spread of states so a new branch cannot slip in an unbundled glyph.
        val allowedSymbols = "©°·±×—…→∞’↑↓".toSet()
        val states = buildList {
            add(CameraUiState())
            ExposureMode.entries.forEach { mode ->
                ShutterMode.entries.forEach { shutter ->
                    add(controls { copy(exposureMode = mode, shutterMode = shutter, exposureTimeNs = 2_000_000_000L) })
                }
            }
            FocusMode.entries.forEach { add(controls { copy(focusMode = it) }) }
            WbMode.entries.forEach { add(controls { copy(wbMode = it) }) }
            ColorTransfer.entries.forEach { add(CameraUiState(transfer = it, rawLog = true)) }
            GridType.entries.forEach { add(CameraUiState(grid = it)) }
            FrameLineType.entries.forEach { add(CameraUiState(frameLines = it)) }
            AudioInputPreference.entries.forEach { add(CameraUiState(audioInputPreference = it)) }
            AspectRatio.entries.forEach { add(CameraUiState(aspectRatio = it)) }
            add(CameraUiState(openGate = true, teleconverterMode = true))
        }
        states.forEach { state ->
            FnSlot.entries.forEach { slot ->
                val text = fnTileReadout(slot, state).text
                if (text is FnTileText.Literal) {
                    val stray = text.text.filterNot { it in ' '..'~' || it in allowedSymbols }
                    assertTrue("$slot drew '${text.text}' with unbundled glyphs '$stray'", stray.isEmpty())
                    assertTrue("$slot drew an empty tile", text.text.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `one slot has one spoken name across the Fn tray and the persistent dial chip`() {
        // The invariant whose absence let FOCUS and SHUTTER drift: a slot is ONE control shown on two
        // surfaces (the Fn strip tile in CameraScreen, the always-visible dial chip in ManualDials), and
        // both name themselves from fnSlotLabel. The strip tile's contentDescription is the slot label
        // directly; the dial chip's accessibleName defaults to its DRAWN label, so the two slots whose
        // pill text is not their name pass fnSlotLabel(slot) explicitly.
        FnSlot.entries.forEach { slot ->
            assertTrue("every slot needs a spoken name, $slot has none", fnSlotLabel(slot).isNotBlank())
        }
        // "One slot, one spoken name" also runs the other way, and THAT is the half a per-slot loop
        // cannot see: two slots sharing a name gives the strip two tiles TalkBack reads identically,
        // with nothing in the node to tell them apart.
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

        // The strip draws ABBREVIATIONS only ("STD", "ACT"); the spoken name and the info line keep
        // the complete word.
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
