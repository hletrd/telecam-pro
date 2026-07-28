package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.reconcileZoomWithCaps
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.TELECONVERTER_MAGNIFICATION
import me.hletrd.telecampro.camera.TELE_MAX_DISPLAY_ZOOM
import me.hletrd.telecampro.camera.normalizedForCaptureMode
import me.hletrd.telecampro.camera.teleDisplayBase
import me.hletrd.telecampro.ui.controls.formatZoomMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ZoomMathTest {
    // The kit optic's display base — the value the deleted TELE_DISPLAY_BASE constant held.
    // Every assertion below stays at its historical number because the kit magnification is
    // passed EXPLICITLY now that the production code takes it as a parameter.
    private val kitBase = teleDisplayBase(TELECONVERTER_MAGNIFICATION)

    private fun kitBounds(lower: Float?, upper: Float?, teleconverter: Boolean): ZoomBounds? =
        effectiveZoomBounds(lower, upper, teleconverter, TELECONVERTER_MAGNIFICATION)

    @Test fun `shared zoom formatter uses photographic glyph and stable decimal`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5×", formatZoomMultiplier(1.5f))
            assertEquals(
                "10.0×",
                formatDisplayZoom(1f, false, TELECONVERTER_MAGNIFICATION, LensChoice.TELE10X.targetEquivMm),
            )
            assertEquals("13.0×", formatDisplayZoom(1f, true, TELECONVERTER_MAGNIFICATION, null))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `tele bounds use the same 60x ceiling as application`() {
        val bounds = kitBounds(1f, 10f, teleconverter = true)!!
        assertEquals(1f, bounds.lower, 0f)
        assertEquals(TELE_MAX_DISPLAY_ZOOM / kitBase, bounds.upper, 0.0001f)
    }

    @Test fun `entering 30x band snaps once`() {
        val bounds = kitBounds(1f, 10f, teleconverter = true)
        val normalized = normalizeZoomRequest(
            requested = 29f / kitBase,
            currentApplied = 25f / kitBase,
            bounds = bounds,
            teleconverter = true,
            teleconverterMagnification = TELECONVERTER_MAGNIFICATION,
        )
        assertEquals(30f, normalized * kitBase, 0.001f)
    }

    @Test fun `small increments can escape an applied 30x snap`() {
        val bounds = kitBounds(1f, 10f, teleconverter = true)
        var current = 30f / kitBase
        repeat(3) {
            current = normalizeZoomRequest(
                current * 1.04f,
                current,
                bounds,
                teleconverter = true,
                teleconverterMagnification = TELECONVERTER_MAGNIFICATION,
            )
        }
        assertTrue(current * kitBase > 33f)
    }

    @Test fun `tele request saturates at 60x`() {
        val bounds = kitBounds(1f, 10f, teleconverter = true)
        val normalized = normalizeZoomRequest(
            10f,
            4f,
            bounds,
            teleconverter = true,
            teleconverterMagnification = TELECONVERTER_MAGNIFICATION,
        )
        assertEquals(60f, normalized * kitBase, 0.001f)
    }

    @Test fun `photo restore keeps unified framing and derives lens band`() {
        val restored = restoredOptics(CaptureMode.PHOTO, LensChoice.MAIN, false, TELECONVERTER_MAGNIFICATION, 10.5f)
        assertEquals(LensChoice.TELE10X, restored.lens)
        assertEquals(10.5f, restored.zoomRatio, 0f)
    }

    @Test fun `video restore keeps selected lens and local zoom`() {
        val restored = restoredOptics(CaptureMode.VIDEO, LensChoice.TELE3X, false, TELECONVERTER_MAGNIFICATION, 2.25f)
        assertEquals(LensChoice.TELE3X, restored.lens)
        assertEquals(2.25f, restored.zoomRatio, 0f)
    }

    @Test fun `video restore clamps legacy local zoom to 10x`() {
        val restored = restoredOptics(CaptureMode.VIDEO, LensChoice.TELE3X, false, TELECONVERTER_MAGNIFICATION, 20f)
        assertEquals(LensChoice.TELE3X, restored.lens)
        assertEquals(10f, restored.zoomRatio, 0f)
    }

    @Test fun `non-finite restore falls back to a valid mode representation`() {
        assertEquals(
            1f,
            restoredOptics(CaptureMode.VIDEO, LensChoice.TELE10X, false, TELECONVERTER_MAGNIFICATION, Float.NaN).zoomRatio,
            0f,
        )
        assertEquals(
            LensChoice.TELE3X.zoomPreset,
            restoredOptics(CaptureMode.PHOTO, LensChoice.TELE3X, false, TELECONVERTER_MAGNIFICATION, Float.POSITIVE_INFINITY).zoomRatio,
            0f,
        )
    }

    @Test fun `tele restore clamps local zoom to converter display ceiling`() {
        val restored = restoredOptics(CaptureMode.PHOTO, LensChoice.MAIN, true, TELECONVERTER_MAGNIFICATION, 9f)
        assertEquals(LensChoice.TELE3X, restored.lens)
        assertTrue(restored.teleconverter)
        assertEquals(60f, restored.zoomRatio * kitBase, 0.001f)
    }

    @Test fun `photo to video remap selects lens band and local zoom`() {
        val remapped = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.MAIN,
            teleconverter = false,
            controls = ManualControls(zoomRatio = 10f),
        )

        assertEquals(LensChoice.TELE10X, remapped.lens)
        assertEquals(1f, remapped.controls.zoomRatio, 0f)
    }

    @Test fun `video to photo remap restores unified framing`() {
        val remapped = remapModeOptics(
            fromMode = CaptureMode.VIDEO,
            toMode = CaptureMode.PHOTO,
            lens = LensChoice.TELE10X,
            teleconverter = false,
            controls = ManualControls(zoomRatio = 2f),
        )

        assertEquals(LensChoice.TELE10X, remapped.lens)
        assertEquals(20f, remapped.controls.zoomRatio, 0f)
    }

    @Test fun `tele mode transition keeps local optics unchanged`() {
        val controls = ManualControls(zoomRatio = 2.5f)
        val remapped = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = controls,
        )

        assertEquals(LensChoice.TELE3X, remapped.lens)
        assertEquals(controls, remapped.controls)
    }

    @Test fun `video entry clamps slow shutter even when tele optics do not remap`() {
        val remapped = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = ManualControls(
                exposureMode = me.hletrd.telecampro.camera.ExposureMode.ISO,
                exposureTimeNs = 500_000_000L,
                fps = 30,
                zoomRatio = 2.5f,
            ),
        )

        assertEquals(LensChoice.TELE3X, remapped.lens)
        assertEquals(2.5f, remapped.controls.zoomRatio, 0f)
        assertEquals(33_333_333L, remapped.controls.exposureTimeNs)
    }

    @Test fun `photo slow shutter survives a tele video round trip`() {
        val photoControls = ManualControls(
            exposureMode = me.hletrd.telecampro.camera.ExposureMode.MANUAL,
            exposureTimeNs = 500_000_000L,
            fps = 30,
            zoomRatio = 2.5f,
        )
        val entering = modeExposureState(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            controls = photoControls,
            rememberedPhotoExposureTimeNs = 8_000_000L,
        )
        val video = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = entering.controls,
        )
        assertEquals(33_333_333L, video.controls.exposureTimeNs)

        val leaving = modeExposureState(
            fromMode = CaptureMode.VIDEO,
            toMode = CaptureMode.PHOTO,
            controls = video.controls,
            rememberedPhotoExposureTimeNs = entering.photoExposureTimeNs,
        )
        val photo = remapModeOptics(
            fromMode = CaptureMode.VIDEO,
            toMode = CaptureMode.PHOTO,
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = leaving.controls,
        )

        assertEquals(500_000_000L, photo.controls.exposureTimeNs)
    }

    @Test fun `angle mode round trip retains its dormant photo speed`() {
        val remembered = modeExposureState(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            controls = ManualControls(
                exposureMode = me.hletrd.telecampro.camera.ExposureMode.SHUTTER,
                shutterMode = me.hletrd.telecampro.camera.ShutterMode.ANGLE,
                shutterAngle = 180f,
                exposureTimeNs = 2_000_000_000L,
                fps = 30,
            ),
            rememberedPhotoExposureTimeNs = 8_000_000L,
        )
        val video = remembered.controls.normalizedForCaptureMode(CaptureMode.VIDEO)
        val restored = modeExposureState(
            fromMode = CaptureMode.VIDEO,
            toMode = CaptureMode.PHOTO,
            controls = video,
            rememberedPhotoExposureTimeNs = remembered.photoExposureTimeNs,
        )

        assertEquals(2_000_000_000L, restored.controls.exposureTimeNs)
        assertEquals(me.hletrd.telecampro.camera.ShutterMode.ANGLE, restored.controls.shutterMode)
    }

    @Test fun `video MR restore never clamps the hidden photo shutter with outgoing caps`() {
        val restored = restoredExposureState(
            targetMode = CaptureMode.VIDEO,
            activeExposureTimeNs = 2_000_000_000L,
            storedPhotoExposureTimeNs = 4_000_000_000L,
            authoritativeMinNs = 10_000L,
            authoritativeMaxNs = 500_000_000L,
        )

        assertEquals(500_000_000L, restored.activeExposureTimeNs)
        assertEquals(4_000_000_000L, restored.photoExposureTimeNs)
    }

    @Test fun `cross-route photo MR waits for target caps before clamping`() {
        assertFalse(
            restoredRouteUsesCurrentCaps(
                cameraReady = true,
                currentMode = CaptureMode.VIDEO,
                currentLens = LensChoice.TELE3X,
                currentTeleconverter = false,
                currentOverrideId = null,
                targetMode = CaptureMode.PHOTO,
                targetLens = LensChoice.MAIN,
                targetTeleconverter = false,
            ),
        )
        val restored = restoredExposureState(
            targetMode = CaptureMode.PHOTO,
            activeExposureTimeNs = 4_000_000_000L,
            storedPhotoExposureTimeNs = 500_000_000L,
            authoritativeMinNs = null,
            authoritativeMaxNs = null,
        )

        assertEquals(4_000_000_000L, restored.activeExposureTimeNs)
        assertEquals(4_000_000_000L, restored.photoExposureTimeNs)
    }

    @Test fun `same logical photo route may use its accepted caps immediately`() {
        assertTrue(
            restoredRouteUsesCurrentCaps(
                cameraReady = true,
                currentMode = CaptureMode.PHOTO,
                currentLens = LensChoice.ULTRAWIDE,
                currentTeleconverter = false,
                currentOverrideId = null,
                targetMode = CaptureMode.PHOTO,
                targetLens = LensChoice.TELE3X,
                targetTeleconverter = false,
            ),
        )
        val restored = restoredExposureState(
            targetMode = CaptureMode.PHOTO,
            activeExposureTimeNs = 6_300_000_000L,
            storedPhotoExposureTimeNs = 500_000_000L,
            authoritativeMinNs = 10_000L,
            authoritativeMaxNs = 4_000_000_000L,
        )

        assertEquals(4_000_000_000L, restored.activeExposureTimeNs)
        assertEquals(4_000_000_000L, restored.photoExposureTimeNs)
    }

    @Test fun `debug camera override never lends caps to an automatic MR route`() {
        assertFalse(
            restoredRouteUsesCurrentCaps(
                cameraReady = true,
                currentMode = CaptureMode.PHOTO,
                currentLens = LensChoice.MAIN,
                currentTeleconverter = false,
                currentOverrideId = "5",
                targetMode = CaptureMode.PHOTO,
                targetLens = LensChoice.MAIN,
                targetTeleconverter = false,
            ),
        )
    }

    @Test fun `live caps reconcile mode contract and narrower camera range`() {
        assertEquals(
            8f,
            reconcileZoomWithCaps(CaptureMode.VIDEO, false, TELECONVERTER_MAGNIFICATION, 10f, 1f, 8f),
            0f,
        )
        assertEquals(
            0.8f,
            reconcileZoomWithCaps(CaptureMode.PHOTO, false, TELECONVERTER_MAGNIFICATION, 0.6f, 0.8f, 20f),
            0f,
        )
    }

    // ---- FRONT (selfie) route: display scale, mode remap, and caps authority ----

    @Test fun `front zoom displays lens-local, never main-relative`() {
        // front-equiv ÷ main-equiv would read "0.9×" at the selfie 1× — the honest front display
        // is the plain local ratio.
        assertEquals(1f, zoomDisplayMultiplier(
            teleconverter = false,
            teleconverterMagnification = TELECONVERTER_MAGNIFICATION,
            equivalentFocalMm = 20f,
            frontFacing = true,
        ), 0f)
        assertEquals("2.0×", formatDisplayZoom(
            2f,
            teleconverter = false,
            teleconverterMagnification = TELECONVERTER_MAGNIFICATION,
            equivalentFocalMm = 20f,
            frontFacing = true,
        ))
    }

    @Test fun `front mode flip keeps lens-local zoom and the retained rear band`() {
        // The unified↔local remap is a REAR concept; on the single front camera it would rewrite
        // the retained rear band (what "leave FRONT" returns to) from a front-local ratio.
        val optics = remapModeOptics(
            fromMode = CaptureMode.PHOTO,
            toMode = CaptureMode.VIDEO,
            lens = LensChoice.MAIN,
            teleconverter = false,
            controls = ManualControls(zoomRatio = 5f),
            frontFacing = true,
        )
        assertEquals(LensChoice.MAIN, optics.lens)
        assertEquals(5f, optics.controls.zoomRatio, 0f)
    }

    @Test fun `front route caps are never authoritative for a recalled rear packet`() {
        // While FRONT the current mode/lens fields can coincidentally equal the recalled target's,
        // but the live caps describe the front camera.
        assertFalse(
            restoredRouteUsesCurrentCaps(
                cameraReady = true,
                currentMode = CaptureMode.PHOTO,
                currentLens = LensChoice.MAIN,
                currentTeleconverter = false,
                currentOverrideId = null,
                targetMode = CaptureMode.PHOTO,
                targetLens = LensChoice.MAIN,
                targetTeleconverter = false,
                currentFrontFacing = true,
            ),
        )
    }

    // ---- TEST4-6: the non-teleconverter branch of effectiveZoomBounds ----

    @Test
    fun `non-TC bounds pass caps through`() {
        assertEquals(ZoomBounds(0.6f, 20f), kitBounds(0.6f, 20f, teleconverter = false))
    }

    @Test
    fun `non-TC bounds are null on missing or inverted caps`() {
        org.junit.Assert.assertNull(kitBounds(null, 20f, teleconverter = false))
        org.junit.Assert.assertNull(kitBounds(0.6f, null, teleconverter = false))
        org.junit.Assert.assertNull(kitBounds(20f, 1f, teleconverter = false))
    }

    // ---- TEST4-7: the crossingMark re-snap path (both endpoints INSIDE the band) ----

    @Test
    fun `crossing the mark inside its own band re-snaps to the mark`() {
        // 30.5x -> 29.5x display: both within the 30x band, but the request crosses the exact
        // mark — the crossingMark predicate (not enteringBand) must land it back on 30x.
        val bounds = kitBounds(1f, 10f, teleconverter = true)
        val normalized = normalizeZoomRequest(
            requested = 29.5f / kitBase,
            currentApplied = 30.5f / kitBase,
            bounds = bounds,
            teleconverter = true,
            teleconverterMagnification = TELECONVERTER_MAGNIFICATION,
        )
        assertEquals(30f, normalized * kitBase, 0.001f)
    }

    // ---- The zoom seam follows the SELECTED converter, not the kit constant ----

    @Test
    fun `display scale, snap marks and local ceiling all move with the converter`() {
        // A generic 2x on the 70 mm host: local 1.0 reads 2x70/23 ≈ 6.09x, not the kit's 13x.
        assertEquals(
            teleDisplayBase(2f),
            zoomDisplayMultiplier(teleconverter = true, teleconverterMagnification = 2f, equivalentFocalMm = null),
            0f,
        )
        // The 60x cap is on TOTAL magnification, so a weaker optic earns MORE digital headroom.
        val weak = effectiveZoomBounds(1f, 100f, teleconverter = true, teleconverterMagnification = 2f)!!
        assertEquals(TELE_MAX_DISPLAY_ZOOM / teleDisplayBase(2f), weak.upper, 0.0001f)
        assertTrue(weak.upper > kitBounds(1f, 100f, teleconverter = true)!!.upper)
        // The magnetic marks stay TOTAL-magnification numbers: 30x lands at a different LOCAL ratio.
        val snapped = normalizeZoomRequest(
            requested = 29f / teleDisplayBase(2f),
            currentApplied = 25f / teleDisplayBase(2f),
            bounds = weak,
            teleconverter = true,
            teleconverterMagnification = 2f,
        )
        assertEquals(30f, snapped * teleDisplayBase(2f), 0.001f)
    }

    @Test
    fun `tele restore clamps against the converter being restored with it`() {
        // 9x local is legal under a weak converter but past the total cap under the kit optic — the
        // restore must clamp against the magnification arriving in the SAME packet, not a constant.
        val weak = restoredOptics(CaptureMode.PHOTO, LensChoice.MAIN, true, 2f, 9f)
        assertEquals(9f, weak.zoomRatio, 0.001f)
        assertTrue(9f * teleDisplayBase(2f) < TELE_MAX_DISPLAY_ZOOM)
        assertEquals(
            60f,
            restoredOptics(CaptureMode.PHOTO, LensChoice.MAIN, true, TELECONVERTER_MAGNIFICATION, 9f)
                .zoomRatio * kitBase,
            0.001f,
        )
    }

    @Test
    fun `rear-route callers omitting frontFacing get the rear main-relative scale`() {
        // The frontFacing parameter defaults to false: the many rear-only call sites use the
        // synthetic default bridge, which must resolve to exactly the rear display scale.
        assertEquals(kitBase, zoomDisplayMultiplier(true, TELECONVERTER_MAGNIFICATION, null), 0f)
        assertEquals(1f, zoomDisplayMultiplier(false, TELECONVERTER_MAGNIFICATION, null), 0f)
        assertEquals(
            LensChoice.TELE10X.targetEquivMm / LensChoice.MAIN.targetEquivMm,
            zoomDisplayMultiplier(false, TELECONVERTER_MAGNIFICATION, LensChoice.TELE10X.targetEquivMm),
            0f,
        )
    }

    // ---------------------------------------------------------------------------
    // TELE rail marks: the rail's TELE face is a digital-zoom picker whose numbers come from the
    // device's own lens information, so these pin the DERIVATION, not a table of constants.
    // ---------------------------------------------------------------------------

    @Test
    fun `kit optic on this device offers its native field, the round stops, and the ceiling`() {
        // PMA110's standalone tele advertises 1-10x digital, which is more headroom than the 60x
        // total cap needs — so the span is the converter's own 13x up to exactly 60x.
        val marks = teleZoomMarks(1f, 10f, TELECONVERTER_MAGNIFICATION)
        assertEquals(3, marks.size)
        assertEquals(kitBase, marks[0], 0.001f)
        assertEquals(30f, marks[1], 0.001f)
        assertEquals(60f, marks[2], 0.01f)
        assertEquals(listOf("13×", "30×", "60×"), marks.map { formatZoomMark(it) })
    }

    @Test
    fun `a weaker converter moves the base without moving the round stops`() {
        // The stops are TOTAL magnifications, so they stay 30/60 while the native field halves —
        // and the weaker optic still reaches 60x because its local ceiling widens inversely.
        val marks = teleZoomMarks(1f, 10f, 2f)
        assertEquals(teleDisplayBase(2f), marks[0], 0.001f)
        assertEquals(listOf("6.1×", "30×", "60×"), marks.map { formatZoomMark(it) })
    }

    @Test
    fun `a lens that cannot reach a stop never draws it`() {
        // 2x digital on the kit optic tops out at ~26x: 30x and 60x are unreachable, and a chip that
        // cannot be activated is worse than a missing one.
        val marks = teleZoomMarks(1f, 2f, TELECONVERTER_MAGNIFICATION)
        assertEquals(2, marks.size)
        assertEquals(kitBase, marks[0], 0.001f)
        assertEquals(2f * kitBase, marks[1], 0.001f)
        assertTrue(marks.none { it >= 30f })
    }

    @Test
    fun `every mark is inside the bounds application clamps to, in ascending order`() {
        // The floor follows the LENS, not the nominal base: a lens whose own zoom floor sits above
        // 1.0 cannot be asked for the converter's native field, so the rail must not offer it.
        listOf(
            Triple(1f, 10f, TELECONVERTER_MAGNIFICATION),
            Triple(1f, 2f, TELECONVERTER_MAGNIFICATION),
            Triple(1.5f, 10f, TELECONVERTER_MAGNIFICATION),
            Triple(1f, 10f, 2f),
            Triple(1f, 4f, 1.5f),
        ).forEach { (lower, upper, magnification) ->
            val bounds = effectiveZoomBounds(lower, upper, true, magnification)!!
            val base = teleDisplayBase(magnification)
            val marks = teleZoomMarks(lower, upper, magnification)
            assertTrue("$magnification: empty", marks.isNotEmpty())
            assertEquals("$magnification: ascending", marks.sorted(), marks)
            marks.forEach { mark ->
                assertTrue("$magnification: $mark below floor", mark >= bounds.lower * base - 0.001f)
                assertTrue("$magnification: $mark above ceiling", mark <= bounds.upper * base + 0.001f)
            }
            // Distinct enough to read as separate chips (a ceiling landing on a stop draws once).
            marks.zipWithNext { a, b -> assertTrue("$magnification: $a and $b collide", b > a * 1.01f) }
        }
    }

    @Test
    fun `a lens pinned to one zoom degenerates to a single mark`() {
        val marks = teleZoomMarks(1f, 1f, TELECONVERTER_MAGNIFICATION)
        assertEquals(listOf("13×"), marks.map { formatZoomMark(it) })
    }

    @Test
    fun `a tapped mark highlights itself after the round trip through the local scale`() {
        val marks = teleZoomMarks(1f, 10f, TELECONVERTER_MAGNIFICATION)
        marks.forEach { mark ->
            // Exactly what activation does: total -> lens-local ratio -> back through the display
            // base for the highlight. Float drift here must never de-select the tapped chip.
            val local = mark / kitBase
            assertEquals(mark, selectedTeleZoomMark(marks, local * kitBase))
        }
    }

    @Test
    fun `a free pinch between marks highlights none of them`() {
        val marks = teleZoomMarks(1f, 10f, TELECONVERTER_MAGNIFICATION)
        assertNull(selectedTeleZoomMark(marks, 22f))
        // A value that deliberately escaped the 6% magnetic snap band reads as unselected rather
        // than keeping the chip lit at a framing it no longer describes.
        assertNull(selectedTeleZoomMark(marks, 30f * 1.05f))
        assertNull(selectedTeleZoomMark(marks, Float.NaN))
    }
}
