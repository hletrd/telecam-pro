package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import me.hletrd.telecampro.ui.overlays.faceFrameCornerPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Face Priority AF: capability gate, request-key decision table, readout throttle, and the
 * sensor → view geometry, which must be the exact inverse of the tap → metering-region mapping.
 */
class FacePriorityTest {

    private val eps = 1e-4f

    // PMA110 as measured from dumpsys 2026-10-14: [OFF, SIMPLE], 10 faces, FACE_PRIORITY advertised.
    private val pma110 = FaceCapability(
        faceDetectModes = intArrayOf(
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF,
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE,
        ),
        maxFaceCount = 10,
        sceneModes = intArrayOf(
            CameraMetadata.CONTROL_SCENE_MODE_DISABLED,
            CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY,
        ),
    )

    // ---- capability gate ----

    @Test
    fun `gate requires a non-OFF detect mode, a positive face count, and FACE_PRIORITY`() {
        assertTrue(facePriorityAfAvailable(pma110))
        assertFalse(
            "OFF alone is not face detection",
            facePriorityAfAvailable(pma110.copy(faceDetectModes = intArrayOf(CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF))),
        )
        assertFalse(facePriorityAfAvailable(pma110.copy(faceDetectModes = IntArray(0))))
        assertFalse(facePriorityAfAvailable(pma110.copy(maxFaceCount = 0)))
        assertFalse(
            "detection without the scene mode cannot steer AF",
            facePriorityAfAvailable(pma110.copy(sceneModes = intArrayOf(CameraMetadata.CONTROL_SCENE_MODE_DISABLED))),
        )
        assertTrue(
            "FULL-only routes qualify",
            facePriorityAfAvailable(pma110.copy(faceDetectModes = intArrayOf(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL))),
        )
    }

    @Test
    fun `SIMPLE is preferred over FULL and OFF is never chosen`() {
        assertEquals(
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE,
            preferredFaceDetectMode(
                intArrayOf(
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL,
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE,
                ),
            ),
        )
        assertEquals(
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL,
            preferredFaceDetectMode(intArrayOf(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL)),
        )
        assertNull(preferredFaceDetectMode(intArrayOf(CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)))
    }

    @Test
    fun `settings row is enabled only on a supporting route in an AF focus mode`() {
        val caps = CameraControlCapabilities(face = pma110)
        assertTrue(controlAvailability(caps, ManualControls(focusMode = FocusMode.CONTINUOUS)).facePriorityAfEnabled)
        assertTrue(controlAvailability(caps, ManualControls(focusMode = FocusMode.AUTO)).facePriorityAfEnabled)
        // A sparse caps object advertises no AF modes; normalization keeps the neutral CONTINUOUS.
        val unsupported = controlAvailability(CameraControlCapabilities(), ManualControls(facePriorityAf = true))
        assertFalse(unsupported.facePriorityAfAvailable)
        assertFalse(unsupported.facePriorityAfEnabled)
        val preCaps = controlAvailability(null, ManualControls(facePriorityAf = true))
        assertFalse(preCaps.facePriorityAfAvailable)
        assertFalse(preCaps.facePriorityAfEnabled)
    }

    @Test
    fun `settings row is disabled in MANUAL and MACRO focus`() {
        val caps = CameraControlCapabilities(
            supportsManualFocus = true,
            hasFocusDistanceRange = true,
            focusDistanceMaxDiopters = 10f,
            afModes = intArrayOf(
                CameraMetadata.CONTROL_AF_MODE_OFF,
                CameraMetadata.CONTROL_AF_MODE_AUTO,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CameraMetadata.CONTROL_AF_MODE_MACRO,
            ),
            face = pma110,
        )
        assertFalse(controlAvailability(caps, ManualControls(focusMode = FocusMode.MANUAL)).facePriorityAfEnabled)
        assertFalse(controlAvailability(caps, ManualControls(focusMode = FocusMode.MACRO)).facePriorityAfEnabled)
        assertTrue(controlAvailability(caps, ManualControls(focusMode = FocusMode.MANUAL)).facePriorityAfAvailable)
    }

    @Test
    fun `normalization never erases the persisted preference on an unsupported route`() {
        val wanted = ManualControls(facePriorityAf = true)
        assertTrue(wanted.normalizedFor(CameraControlCapabilities()).facePriorityAf)
        assertFalse(facePriorityAfEffective(true, FaceCapability(IntArray(0), 0, IntArray(0)), FocusMode.CONTINUOUS))
        assertFalse(facePriorityAfEffective(true, null, FocusMode.CONTINUOUS))
    }

    // ---- request decision table ----

    @Test
    fun `decision table - setting x focus mode x override x exposure mode`() {
        val exposurePackets = listOf(
            ManualControls(exposureMode = ExposureMode.PROGRAM), // HAL AE
            ManualControls(exposureMode = ExposureMode.PROGRAM, programAppSide = true), // AE OFF
            ManualControls(exposureMode = ExposureMode.SHUTTER),
            ManualControls(exposureMode = ExposureMode.ISO),
            ManualControls(exposureMode = ExposureMode.MANUAL),
        )
        for (enabled in listOf(false, true)) {
            for (focus in FocusMode.entries) {
                for (override in listOf(false, true)) {
                    val perExposure = exposurePackets.map { base ->
                        facePriorityKeysFor(
                            base.copy(facePriorityAf = enabled, focusMode = focus),
                            pma110,
                            afOverrideActive = override,
                        )
                    }
                    // The exposure axis never changes the keys: FACE_PRIORITY keeps aeMode active
                    // by spec, so AE-OFF packets carry exactly what HAL-AE packets carry.
                    assertTrue("AE axis moved the keys", perExposure.distinct().size == 1)
                    val keys = perExposure.first()
                    val afMode = focus == FocusMode.AUTO || focus == FocusMode.CONTINUOUS
                    if (!enabled || !afMode) {
                        assertNull("$enabled/$focus/$override must write nothing", keys)
                    } else {
                        assertNotNull(keys)
                        assertEquals(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE, keys!!.faceDetectMode)
                        // A held tap or AF Lock keeps precedence: the scene half drops to AUTO,
                        // detection stays on so the frames remain drawn.
                        assertEquals(!override, keys.sceneModeFacePriority)
                    }
                }
            }
        }
    }

    @Test
    fun `the default packet writes no face key at all`() {
        // Default OFF must leave the request byte-identical to the pre-feature build.
        assertNull(facePriorityKeysFor(ManualControls(), pma110, afOverrideActive = false))
        assertNull(
            "an unsupported route writes nothing even when wanted",
            facePriorityKeysFor(
                ManualControls(facePriorityAf = true),
                pma110.copy(sceneModes = IntArray(0)),
                afOverrideActive = false,
            ),
        )
    }

    @Test
    fun `face priority yields to the AF override exactly where afOverrideFor yields an override`() {
        // The controller feeds `override != null` from afOverrideForRequest; pin both live shapes.
        val lock = afOverrideFor(
            touchAfUsesAuto = true,
            afLock = true,
            focusMode = FocusMode.CONTINUOUS,
            supportsManualFocus = true,
            afOffAdvertised = true,
            lastFocusDistance = 0.4f,
        )
        val tap = afOverrideFor(
            touchAfUsesAuto = true,
            afLock = false,
            focusMode = FocusMode.CONTINUOUS,
            supportsManualFocus = true,
            afOffAdvertised = true,
            lastFocusDistance = 0.4f,
        )
        val none = afOverrideFor(
            touchAfUsesAuto = false,
            afLock = false,
            focusMode = FocusMode.CONTINUOUS,
            supportsManualFocus = true,
            afOffAdvertised = true,
            lastFocusDistance = 0.4f,
        )
        val c = ManualControls(facePriorityAf = true)
        assertFalse(facePriorityKeysFor(c, pma110, lock != null)!!.sceneModeFacePriority)
        assertFalse(facePriorityKeysFor(c, pma110, tap != null)!!.sceneModeFacePriority)
        assertTrue(facePriorityKeysFor(c, pma110, none != null)!!.sceneModeFacePriority)
    }

    @Test
    fun `toggling the setting is never a fast-path delta`() {
        // A fast path reuses the cached builder; the toggle must rebuild from a fresh template so a
        // null-keys edge cannot leave stale scene keys on the wire.
        val off = ManualControls()
        val on = off.copy(facePriorityAf = true)
        assertFalse(sensorOnlyControlsDelta(off, on))
        assertEquals(ControlsApplyPlan.FULL_REBUILD, controlsApplyPlan(off, on, manualAe = true))
        assertEquals(RetainedOpticsApplyPlan.FULL_REBUILD, retainedOpticsApplyPlan(off, on, false))
    }

    // ---- readout throttle / change gate ----

    private fun face(l: Float, t: Float, r: Float, b: Float, score: Int = 100) =
        SensorFace(NormalizedRect(l, t, r, b), score)

    @Test
    fun `readout samples at most every 100 ms`() {
        val gate = FaceReadoutGate()
        assertTrue(gate.shouldSample(1_000))
        assertFalse(gate.shouldSample(1_050))
        assertFalse(gate.shouldSample(1_099))
        assertTrue(gate.shouldSample(1_100))
        assertFalse(gate.shouldSample(1_150))
    }

    @Test
    fun `publication is change-gated on material moves and count`() {
        val gate = FaceReadoutGate()
        val a = face(0.2f, 0.2f, 0.4f, 0.5f)
        assertTrue(gate.offer(listOf(a)))
        assertFalse("detector jitter is not a change", gate.offer(listOf(face(0.21f, 0.2f, 0.41f, 0.5f))))
        assertTrue("a real move is", gate.offer(listOf(face(0.25f, 0.2f, 0.45f, 0.5f))))
        val b = face(0.6f, 0.1f, 0.7f, 0.3f)
        assertTrue("a new face is", gate.offer(listOf(face(0.25f, 0.2f, 0.45f, 0.5f), b)))
        assertFalse(
            "HAL reordering the same faces is not",
            gate.offer(listOf(b, face(0.25f, 0.2f, 0.45f, 0.5f))),
        )
        assertTrue("losing every face publishes one clear", gate.offer(emptyList()))
        assertFalse("and never repeats it", gate.offer(emptyList()))
    }

    @Test
    fun `a clear is owed only after a non-empty publication`() {
        val gate = FaceReadoutGate()
        assertFalse(gate.clearOwed())
        gate.offer(emptyList())
        assertFalse(gate.clearOwed())
        gate.offer(listOf(face(0.1f, 0.1f, 0.2f, 0.2f)))
        assertTrue(gate.clearOwed())
        gate.reset()
        assertFalse(gate.clearOwed())
        assertTrue("reset re-arms the sampler", gate.shouldSample(0))
    }

    @Test
    fun `primary face is the largest, ties broken by score`() {
        assertEquals(-1, primaryFaceIndex(emptyList()))
        val small = face(0.1f, 0.1f, 0.2f, 0.2f)
        val big = face(0.5f, 0.5f, 0.8f, 0.8f, score = 40)
        assertEquals(1, primaryFaceIndex(listOf(small, big)))
        // Binary-exact quarters so the two areas tie exactly in Float.
        val twinLow = face(0.25f, 0.25f, 0.5f, 0.5f, score = 50)
        val twinHigh = face(0.5f, 0.5f, 0.75f, 0.75f, score = 90)
        assertEquals(1, primaryFaceIndex(listOf(twinLow, twinHigh)))
    }

    @Test
    fun `face rect normalization uses the metering-region convention and rejects degenerate input`() {
        val r = normalizeFaceRect(1024, 768, 2048, 1536, 0, 0, 4096, 3072)!!
        assertEquals(0.25f, r.left, eps)
        assertEquals(0.25f, r.top, eps)
        assertEquals(0.5f, r.right, eps)
        assertEquals(0.5f, r.bottom, eps)
        // applyMetering centers a tap at `left + p·width`; a face whose center normalizes to p
        // therefore sits exactly where a tap at p would meter.
        val offset = normalizeFaceRect(110, 10, 210, 110, 10, 10, 1000, 1000)!!
        assertEquals(0.1f, offset.left, eps)
        assertNull(normalizeFaceRect(10, 10, 10, 20, 0, 0, 100, 100))
        assertNull(normalizeFaceRect(0, 0, 10, 10, 0, 0, 0, 100))
    }

    // ---- geometry ----

    private fun geometry(
        sensorOrientation: Int = 90,
        tele: Boolean = false,
        punch: Boolean = false,
        center: Pair<Float, Float> = 0.5f to 0.5f,
        mirror: Boolean = false,
        window: Int = 0,
        crop: NormalizedRect = NormalizedRect.FULL,
    ) = FaceViewGeometry(sensorOrientation, tele, punch, center, mirror, window, crop)

    @Test
    fun `rotateViewPoint is the exact inverse of unrotateViewPoint`() {
        for (w in listOf(0, 90, 180, 270, -90, 450)) {
            for ((x, y) in listOf(0.1f to 0.2f, 0.7f to 0.35f, 0.5f to 0.9f)) {
                val (ux, uy) = RotationMath.unrotateViewPoint(x, y, w)
                val (rx, ry) = RotationMath.rotateViewPoint(ux, uy, w)
                assertEquals("w=$w", x, rx, eps)
                assertEquals("w=$w", y, ry, eps)
            }
        }
    }

    @Test
    fun `sensor to view round-trips the tap to metering mapping across every geometry term`() {
        val points = listOf(0.15f to 0.2f, 0.5f to 0.5f, 0.8f to 0.3f, 0.35f to 0.85f)
        for (sensor in listOf(0, 90, 180, 270)) {
            for (tele in listOf(false, true)) {
                for (mirror in listOf(false, true)) {
                    for (window in listOf(0, 90, 180, 270)) {
                        for ((punch, center) in listOf(false to (0.5f to 0.5f), true to (0.4f to 0.6f))) {
                            for ((nx, ny) in points) {
                                val tap = mapTapFocusGeometry(
                                    nx = nx,
                                    ny = ny,
                                    sensorOrientation = sensor,
                                    teleconverter = tele,
                                    punchActive = punch,
                                    sensorCenter = center,
                                    loupeCenter = 0.5f to 0.5f,
                                    previewRotationDegrees = RotationMath.previewRotationDegrees(tele),
                                    mirrorX = false,
                                    meteringMirrorX = mirror,
                                    windowRotationDeg = window,
                                )
                                val (vx, vy) = sensorPointToViewPoint(
                                    tap.sensorPoint.first,
                                    tap.sensorPoint.second,
                                    geometry(sensor, tele, punch, center, mirror, window),
                                )
                                val label = "s=$sensor tele=$tele mirror=$mirror w=$window punch=$punch ($nx,$ny)"
                                assertEquals(label, nx, vx, eps)
                                assertEquals(label, ny, vy, eps)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `afocal 180 point-mirrors a face through the frame center`() {
        val plain = sensorPointToViewPoint(0.2f, 0.3f, geometry(tele = false))
        val tele = sensorPointToViewPoint(0.2f, 0.3f, geometry(tele = true))
        assertEquals(0.7f, plain.first, eps)
        assertEquals(0.2f, plain.second, eps)
        assertEquals(1f - plain.first, tele.first, eps)
        assertEquals(1f - plain.second, tele.second, eps)
    }

    @Test
    fun `front metering mirror flips the frame horizontally in the view`() {
        val plain = sensorPointToViewPoint(0.2f, 0.3f, geometry(sensorOrientation = 270))
        val mirrored = sensorPointToViewPoint(0.2f, 0.3f, geometry(sensorOrientation = 270, mirror = true))
        assertEquals(1f - plain.first, mirrored.first, eps)
        assertEquals(plain.second, mirrored.second, eps)
    }

    @Test
    fun `a face rect maps to an ordered axis-aligned view rect`() {
        // Sensor 90 swaps the axes and reverses one, yet the rect comes back ordered.
        val view = sensorFaceToView(NormalizedRect(0.1f, 0.2f, 0.3f, 0.6f), geometry())!!
        assertTrue(view.left < view.right && view.top < view.bottom)
        assertEquals(0.4f, view.left, eps)
        assertEquals(0.1f, view.top, eps)
        assertEquals(0.8f, view.right, eps)
        assertEquals(0.3f, view.bottom, eps)
        // The same face under the converter is the 180° image of that rect.
        val tele = sensorFaceToView(NormalizedRect(0.1f, 0.2f, 0.3f, 0.6f), geometry(tele = true))!!
        assertEquals(1f - view.right, tele.left, eps)
        assertEquals(1f - view.bottom, tele.top, eps)
    }

    @Test
    fun `the loupe magnifies faces around its center and drops faces outside it`() {
        val g = geometry(sensorOrientation = 0, punch = true, center = 0.5f to 0.5f)
        val inside = sensorFaceToView(NormalizedRect(0.45f, 0.45f, 0.55f, 0.55f), g)!!
        // span 0.4 → 2.5× magnification about the center.
        assertEquals(0.375f, inside.left, eps)
        assertEquals(0.625f, inside.right, eps)
        assertNull("outside the loupe field", sensorFaceToView(NormalizedRect(0.0f, 0.0f, 0.1f, 0.1f), g))
        val partial = sensorFaceToView(NormalizedRect(0.25f, 0.4f, 0.35f, 0.6f), g)!!
        assertEquals("clipped at the view edge", 0f, partial.left, eps)
    }

    @Test
    fun `stream crop - 16-9 video shows only the middle of a 4-3 array`() {
        // The engine derives the crop from the readout's own array size, carried on FaceFrame.
        val frame = FaceFrame(listOf(face(0.4f, 0.125f, 0.6f, 0.875f)), arrayWidth = 4096, arrayHeight = 3072)
        val crop = streamSourceCrop(3840, 2160, frame.arrayWidth, frame.arrayHeight)
        assertEquals(0f, crop.left, eps)
        assertEquals(0.125f, crop.top, eps)
        assertEquals(0.875f, crop.bottom, eps)
        assertEquals(NormalizedRect.FULL, streamSourceCrop(4096, 3072, 4096, 3072))
        assertEquals(NormalizedRect.FULL, streamSourceCrop(0, 0, 4096, 3072))
        val pillar = streamSourceCrop(1000, 1000, 4000, 3000)
        assertEquals(0.125f, pillar.left, eps)
        assertEquals(0.875f, pillar.right, eps)
        // A face spanning the visible band fills the view's matching axis (sensor 0 → no swap).
        val view = sensorFaceToView(frame.faces.single().bounds, geometry(sensorOrientation = 0, crop = crop))!!
        assertEquals(0f, view.top, eps)
        assertEquals(1f, view.bottom, eps)
    }

    @Test
    fun `boxes carry the primary flag through the mapping`() {
        val boxes = faceBoxesForView(
            listOf(face(0.1f, 0.1f, 0.2f, 0.2f), face(0.4f, 0.4f, 0.7f, 0.7f)),
            geometry(),
        )
        assertEquals(listOf(false, true), boxes.map { it.primary })
    }

    // ---- overlay gates ----

    @Test
    fun `frames show only with the feature in force, a live session, and no modal`() {
        val boxes = listOf(FaceBox(NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f), primary = true))
        fun visible(
            enabled: Boolean = true,
            cap: FaceCapability? = pma110,
            focus: FocusMode = FocusMode.CONTINUOUS,
            ready: Boolean = true,
            modal: Boolean = false,
            b: List<FaceBox> = boxes,
        ) = faceFramesVisible(enabled, cap, focus, ready, modal, b)
        assertTrue(visible())
        assertFalse(visible(enabled = false))
        assertFalse(visible(cap = null))
        assertFalse(visible(focus = FocusMode.MANUAL))
        assertFalse(visible(ready = false))
        assertFalse(visible(modal = true))
        assertFalse(visible(b = emptyList()))
    }

    @Test
    fun `green only for the primary face, focused, with face priority owning AF`() {
        val primary = FaceBox(NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f), primary = true)
        val other = primary.copy(primary = false)
        assertTrue(faceFrameFocused(primary, AfIndication.FOCUSED, tapFocusHeld = false, afLock = false))
        assertFalse(faceFrameFocused(other, AfIndication.FOCUSED, tapFocusHeld = false, afLock = false))
        assertFalse(faceFrameFocused(primary, AfIndication.SCANNING, tapFocusHeld = false, afLock = false))
        assertFalse(faceFrameFocused(primary, AfIndication.FOCUSED, tapFocusHeld = true, afLock = false))
        assertFalse(faceFrameFocused(primary, AfIndication.FOCUSED, tapFocusHeld = false, afLock = true))
    }

    @Test
    fun `corner arms scale with the face and stay bounded`() {
        assertEquals(25f, faceFrameCornerPx(100f, 200f, 10f, 40f), eps)
        assertEquals(10f, faceFrameCornerPx(8f, 8f, 10f, 40f), eps)
        assertEquals(40f, faceFrameCornerPx(1000f, 1000f, 10f, 40f), eps)
    }
}
