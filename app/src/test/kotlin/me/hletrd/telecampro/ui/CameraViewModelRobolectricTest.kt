package me.hletrd.telecampro.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.camera.CameraReadyPublication
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureFamilyDeleteDurability
import me.hletrd.telecampro.camera.CaptureFamilyDeleteIntent
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.DeletedStillPublication
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.EngineCallbackKey
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.camera.RetainedStillDeletionOwner
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.TeleconverterProfile
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.storage.ExtraSettings
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.CaptureFamilyMedia
import me.hletrd.telecampro.storage.MediaProvenance
import me.hletrd.telecampro.storage.SettingsStore
import me.hletrd.telecampro.video.CodecComponent
import me.hletrd.telecampro.video.CodecInventory
import me.hletrd.telecampro.video.buildCodecInventory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric-driven construction/init-contract tests for [CameraViewModel] — the first host tests
 * that execute the ViewModel CLASS BODY (Partition B by policy; see docs/TESTING.md) instead of its
 * extracted pure policy.
 *
 * ENGINE STRATEGY (the truthful option of the two considered for this phase): `CameraEngine` is a
 * FINAL class, so a subclassed fake is not constructible without adding production `open` markers.
 * These tests therefore inject a REAL `CameraEngine(app)` through the ViewModel's constructor seam
 * and NEVER call `onStart()` — the camera only opens on `resume()` (CLAUDE.md lifecycle chain), and
 * everything the VM touches during construction/interaction is pre-open safe by design: every
 * controller call is `controller?.`-guarded, `GlPipeline.post` is a no-op before `start()`, and the
 * engine's executors only run inert bookkeeping (the orphan sweep runs against Robolectric's empty
 * MediaStore, where the missing provider cursor is swallowed by its own `runCatching` per
 * collection). Holding the injected engine reference also lets tests drive the VM through the
 * exact callback fields the engine would use (`onStatus` etc.), not through test-only side doors.
 *
 * Compose UI remains OUT of scope for this phase: the compose-ui-test deps land with the build
 * infra, but semantics-tree tests are a separately gated decision (lane report).
 */
@RunWith(RobolectricTestRunner::class)
class CameraViewModelRobolectricTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var vm: CameraViewModel? = null
    private var engine: CameraEngine? = null

    private fun createViewModel(): Pair<CameraViewModel, CameraEngine> {
        RobolectricEglSentinels.ensure() // GlPipeline field init reads EGL14.EGL_NO_SURFACE
        val e = CameraEngine(app)
        val v = CameraViewModel(app, e)
        vm = v
        engine = e
        return v to e
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun armCountdown(v: CameraViewModel, seconds: Int = 3) {
        CameraViewModel::class.java.getDeclaredMethod("startCountdown", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(v, seconds)
    }

    private fun clearViewModel(v: CameraViewModel) {
        CameraViewModel::class.java.getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(v)
    }

    @After fun tearDown() {
        // Detaches engine callbacks and hands the (never-started) engine to its release thread.
        // onCleared is protected (androidx contract) — reflection is the lifecycle-honest teardown
        // without dragging a ViewModelProvider/Store harness into every test.
        runCatching {
            vm?.let(::clearViewModel)
        }
    }

    // ---- Construction / init contract ----

    @Test fun `fresh launch publishes the documented CameraUiState defaults`() {
        val (v, _) = createViewModel()
        val s = v.state.value
        // Fresh launch is the 1× main lens, rear camera, TELE off, photo mode (CLAUDE.md contract).
        assertEquals(CaptureMode.PHOTO, s.mode)
        assertEquals(LensChoice.MAIN, s.lens)
        assertEquals(CameraFacing.BACK, s.facing)
        assertFalse(s.teleconverterMode)
        // Camera health starts NOT ready — only an owned engine Ready publication may set it.
        assertFalse(s.cameraReady)
        assertNull(s.status)
        assertEquals(ShutterTimer.OFF, s.timer)
        assertEquals(0, s.timerCountdownSec)
        // "Remember settings" defaults ON even when nothing was ever saved.
        assertTrue(s.rememberSettings)
        // init's refreshProgramAppSide: photo PROGRAM with flash OFF runs the APP-SIDE program line
        // (the HAL AE takes no min-shutter hint), published synchronously during construction.
        assertEquals(ExposureMode.PROGRAM, s.controls.exposureMode)
        assertTrue(s.controls.programAppSide)
        assertEquals(1f, s.controls.zoomRatio)
    }

    @Test fun `ordinary partial delete publishes survivor and capture retry from one decision`() {
        val survivorUri = Uri.parse("content://media/survivor")
        val tracker = CaptureOutputTracker<Uri>(maxCaptureHistory = 4)
        assertTrue(
            tracker.seedPriorCapture(
                outputs = listOf(
                    PriorCaptureOutput(
                        output = survivorUri,
                        kind = CaptureOutputKind.RAW,
                        provenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
                    ),
                ),
                preferredOutput = survivorUri,
                deleteScope = MediaDeleteScope.FILE_ONLY,
            ),
        )
        val plan = tracker.beginDelete(survivorUri)
        val survivor = checkNotNull(tracker.restoreDeleteSurvivors(plan, setOf(survivorUri)))

        val delivery = resolveDeleteSurvivorDelivery(
            current = CameraUiState(),
            survivor = survivor,
            captureOutputs = tracker,
        )

        assertEquals(DeleteRetryDestination.CAPTURE, delivery.retryDestination)
        assertEquals(survivorUri, delivery.state.lastMediaUri)
        assertEquals(MediaProvenance.LEGACY_FORMAT_UNVERIFIED, delivery.state.lastMediaProvenance)
        assertEquals(MediaDeleteScope.FILE_ONLY, delivery.state.lastMediaDeleteScope)
    }

    @Test fun `superseded delete survivor preserves newer packet and selects gallery retry`() {
        val oldUri = Uri.parse("content://media/old")
        val newerUri = Uri.parse("content://media/newer")
        val tracker = CaptureOutputTracker<Uri>(maxCaptureHistory = 4)
        tracker.record(31, oldUri, CaptureOutputKind.DISPLAYABLE)
        val plan = tracker.beginDelete(oldUri)
        val restored = checkNotNull(tracker.restoreDeleteSurvivors(plan, setOf(oldUri)))
        // This is the exact uncovered boundary: restore completed on the provider lane, then a
        // newer capture became owner before the queued main-thread delivery ran.
        tracker.record(32, newerUri, CaptureOutputKind.DISPLAYABLE)
        val newerState = CameraUiState(
            lastMediaUri = newerUri,
            lastMediaProvenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
            lastMediaDeleteScope = MediaDeleteScope.FILE_ONLY,
        )

        val delivery = resolveDeleteSurvivorDelivery(
            current = newerState,
            survivor = restored,
            captureOutputs = tracker,
        )

        assertEquals(DeleteRetryDestination.GALLERY, delivery.retryDestination)
        assertEquals(newerState, delivery.state)
        assertEquals(newerUri, delivery.state.lastMediaUri)
        assertEquals(MediaProvenance.LEGACY_FORMAT_UNVERIFIED, delivery.state.lastMediaProvenance)
        assertEquals(MediaDeleteScope.FILE_ONLY, delivery.state.lastMediaDeleteScope)
    }

    @Test fun `engine route publication installs explicit external truth without hidden tele`() {
        val (v, e) = createViewModel()
        e.onCameraRouteInventory?.invoke(
            CameraRouteInventory(back = false, front = false, external = true),
            CameraRoute.EXTERNAL,
        )

        val state = v.state.value
        assertEquals(CameraRoute.EXTERNAL, state.activeCameraRoute)
        assertEquals(CameraFacing.BACK, state.facing)
        assertFalse(state.teleconverterMode)
        assertEquals(1f, state.controls.zoomRatio)
    }

    @Test fun `init wires every engine callback the ViewModel depends on`() {
        val (_, e) = createViewModel()
        // The init block's callback assignments are the VM↔engine contract: a missing wire means a
        // whole feature silently dies (status toasts, review ownership, Ready gating, tap AF...).
        assertNotNull(e.onStatus)
        assertNotNull(e.onTapFocusChange)
        assertNotNull(e.onCapsReady)
        assertNotNull(e.onVideoSizeChosen)
        assertNotNull(e.onPreviewAspect)
        assertNotNull(e.onCameraReadyChange)
        assertNotNull(e.onOpticsRollback)
        assertNotNull(e.onAfIndication)
        assertNotNull(e.onAnalysis)
        assertNotNull(e.onAudioLevel)
        assertNotNull(e.onAudioRoute)
        assertNotNull(e.onStandbyAudioAvailable)
        assertNotNull(e.onStandbyAudioUnavailable)
        assertNotNull(e.onRecordingStarted)
        assertNotNull(e.onRecordingTerminated)
        assertNotNull(e.onExposureInfo)
        assertNotNull(e.onFocusDistance)
        assertNotNull(e.onMediaSaved)
        assertNotNull(e.onRawSaved)
        assertNotNull(e.onLensInventory)
        assertNotNull(e.onCameraPolicyBlocked)
        assertNotNull(e.onTimelapseRun)
    }

    @Test fun `onCleared detaches every current Engine callback field`() {
        val (v, e) = createViewModel()
        assertEquals(EngineCallbackKey.entries.size, e.attachedCallbackCount())

        clearViewModel(v)
        vm = null // tearDown must not invoke the lifecycle edge twice

        assertEquals(0, e.attachedCallbackCount())
    }

    @Test fun `callback fetched before onCleared cannot publish after teardown`() {
        val (v, e) = createViewModel()
        val saved = e.onMediaSaved
        assertNotNull(saved)

        clearViewModel(v)
        vm = null
        saved!!.invoke(Uri.parse("content://media/external/images/media/404"), 404)

        assertNull(v.state.value.lastMediaUri)
    }

    @Test fun `deleted still publication remains Engine-owned after ViewModel detach`() {
        val (v, e) = createViewModel()
        val captureId = 909
        val output = Uri.parse("content://media/external/images/media/909")
        val deletionFinished = CountDownLatch(1)
        val callerThread = Thread.currentThread()
        val completionThread = AtomicReference<Thread>()
        e.markCaptureDeleted(
            CaptureFamilyDeleteIntent(
                familyKey = CaptureFamilyKey(
                    CaptureFamilyMedia.STILL,
                    1_700_000_000_909L,
                    captureId.toLong(),
                ),
                scope = MediaDeleteScope.CAPTURE_FAMILY,
                liveStillCaptureId = captureId,
            ),
        ) {
            completionThread.set(Thread.currentThread())
            assertEquals(CaptureFamilyDeleteDurability.DURABLE, it)
            deletionFinished.countDown()
        }
        assertTrue(deletionFinished.await(5, TimeUnit.SECONDS))
        assertFalse("durable delete commit ran on the caller/UI thread", completionThread.get() === callerThread)

        clearViewModel(v)
        vm = null // lifecycle edge has run; the UI callback graph is now absent
        val ownerField = CameraEngine::class.java.getDeclaredField("retainedStillDeletionOwner")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val owner = ownerField.get(e) as RetainedStillDeletionOwner<Uri>
        var providerPublishCalled = false

        val result = owner.publishIfLive(output, captureId) {
            providerPublishCalled = true
            true
        }

        assertFalse("deleted output reached provider publication after detach", providerPublishCalled)
        assertTrue(
            result == DeletedStillPublication.DISCARD_DELETED_CAPTURE ||
                result == DeletedStillPublication.DISCARD_RETRY_PENDING,
        )
    }

    @Test fun `video family deletion never claims or poisons the retained still gate`() {
        val (_, e) = createViewModel()
        val finished = CountDownLatch(1)
        val availability = AtomicReference<Boolean>()
        e.onStillCaptureAdmissionChanged = { availability.set(it) }

        e.markCaptureDeleted(
            CaptureFamilyDeleteIntent(
                familyKey = CaptureFamilyKey(CaptureFamilyMedia.VIDEO, 1_700_000_001_000L, 1_000L),
                scope = MediaDeleteScope.CAPTURE_FAMILY,
                liveStillCaptureId = null,
            ),
        ) {
            assertEquals(CaptureFamilyDeleteDurability.DURABLE, it)
            finished.countDown()
        }

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(true, availability.get())
        val ownerField = CameraEngine::class.java.getDeclaredField("retainedStillDeletionOwner")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val owner = ownerField.get(e) as RetainedStillDeletionOwner<Uri>
        assertTrue(owner.canAdmitCapture())
    }

    @Test fun `a persisted settings packet restores through the store during construction`() {
        // Seed the REAL SharedPreferences file ("camera_settings") the VM's own store reads —
        // the exact restore wiring, not a fake around it.
        SettingsStore(app).save(
            ManualControls(exposureMode = ExposureMode.MANUAL, iso = 1600),
            ExtraSettings(mode = CaptureMode.VIDEO),
        )
        val (v, _) = createViewModel()
        val s = v.state.value
        assertTrue(s.rememberSettings)
        assertEquals(CaptureMode.VIDEO, s.mode)
        assertEquals(ExposureMode.MANUAL, s.controls.exposureMode)
        assertEquals(1600, s.controls.iso)
        // Facing is deliberately never persisted — a restored packet is rear-route optics.
        assertEquals(CameraFacing.BACK, s.facing)
    }

    @Test fun `live HEVC HLG to AVC transition atomically normalizes gamma to SDR`() {
        val (v, _) = createViewModel()
        val inventory = buildCodecInventory(
            listOf(
                CodecComponent(
                    name = "vendor.hevc",
                    encoder = true,
                    supportedTypes = setOf(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC),
                    hardwareAccelerated = true,
                    hevcProfiles = setOf(
                        android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                    ),
                ),
                CodecComponent(
                    name = "vendor.avc",
                    encoder = true,
                    supportedTypes = setOf(android.media.MediaFormat.MIMETYPE_VIDEO_AVC),
                    hardwareAccelerated = true,
                    hevcProfiles = emptySet(),
                ),
            ),
        )
        CameraViewModel::class.java.getDeclaredMethod(
            "applyEncoderInventory",
            CodecInventory::class.java,
        ).apply { isAccessible = true }.invoke(v, inventory)

        v.onVideoCodec(VideoCodec.HEVC)
        v.onTransfer(ColorTransfer.HLG)
        assertEquals(ColorTransfer.HLG, v.state.value.transfer)

        v.onVideoCodec(VideoCodec.AVC)
        assertEquals(VideoCodec.AVC, v.state.value.videoCodec)
        assertEquals(ColorTransfer.SDR, v.state.value.transfer)
    }

    // ---- Status auto-clear (Handler-timed, deterministic via the shadow main looper) ----

    @Test fun `engine status publications auto-clear after the ordinary display duration`() {
        val (v, e) = createViewModel()
        val guidance = CameraStatusMessage.STOP_RECORDING_FIRST.status()
        e.onStatus!!.invoke(guidance)
        assertEquals(guidance, v.state.value.status)
        idleFor(2_499)
        assertEquals(guidance, v.state.value.status)
        idleFor(1) // ordinary messages clear at 2.5 s (statusDisplayDurationMs)
        assertNull(v.state.value.status)
    }

    @Test fun `the cold-start progress status waits for Ready, not for a timer`() {
        val (v, e) = createViewModel()
        val starting = CameraStatusMessage.STARTING_CAMERA.status()
        e.onStatus!!.invoke(starting)
        // Deliberately far past every display duration in the policy (the longest is 6 s). Before
        // this fix the pill cleared at 2.5 s regardless of the camera, which is what made a ~950 ms
        // bring-up read as a multi-second wait: the user was watching the timer, not the camera.
        idleFor(10_000)
        assertEquals(starting, v.state.value.status)

        e.onCameraReadyChange!!.invoke(
            CameraReadyPublication(
                sequence = 1L,
                ready = true,
                opticsGeneration = 0L,
                sessionGeneration = 0L,
                photoOutputs = PhotoSessionOutputs(processed = true),
            )
        )
        idleFor(0)
        assertNull(v.state.value.status)
    }

    @Test fun `Ready does not swallow a status published during bring-up`() {
        val (v, e) = createViewModel()
        e.onStatus!!.invoke(CameraStatusMessage.STARTING_CAMERA.status())
        // Anything published after it owns the pill; the progress clear is not a blanket reset, or
        // a message arriving in the bring-up window would vanish the instant the camera came up.
        val saved = CameraStatusMessage.VIDEO_SAVED.status()
        e.onStatus!!.invoke(saved)
        e.onCameraReadyChange!!.invoke(
            CameraReadyPublication(
                sequence = 1L,
                ready = true,
                opticsGeneration = 0L,
                sessionGeneration = 0L,
                photoOutputs = PhotoSessionOutputs(processed = true),
            )
        )
        idleFor(0)
        assertEquals(saved, v.state.value.status)
        idleFor(1_500) // and it still keeps its own timer
        assertNull(v.state.value.status)
    }

    @Test fun `saved-confirmation statuses clear on the shorter timer and re-arm per message`() {
        val (v, e) = createViewModel()
        val videoSaved = CameraStatusMessage.VIDEO_SAVED.status()
        e.onStatus!!.invoke(videoSaved)
        idleFor(1_400)
        assertEquals(videoSaved, v.state.value.status)
        // A newer message replaces the text AND re-arms its own timer; the old deadline is dead.
        val wbSet = CameraStatusMessage.CUSTOM_WB_SET.status()
        e.onStatus!!.invoke(wbSet)
        idleFor(1_499)
        assertEquals(wbSet, v.state.value.status)
        idleFor(1) // "saved" confirmations clear at 1.5 s
        assertNull(v.state.value.status)
    }

    @Test fun `every shared modal input owner cancels a one-shot timer with no late capture attempt`() {
        val (v, _) = createViewModel()

        for (owner in listOf("Settings", "Fn", "permission or dialog")) {
            // Arm the private scheduler seam directly: camera readiness belongs to the Engine and
            // is irrelevant to whether a modal owns an already-running countdown.
            armCountdown(v)
            assertEquals("$owner precondition", 3, v.state.value.timerCountdownSec)

            v.onCameraInputBlockedChange(true)
            assertEquals("$owner did not cancel synchronously", 0, v.state.value.timerCountdownSec)
            v.onCameraInputBlockedChange(false)
            idleFor(3_100)

            // With this never-started engine, any leaked deadline calls capturePhoto and publishes
            // CAMERA_RECONFIGURING. Null therefore proves the scheduled shutter call never ran;
            // timer state alone could pass even after a late attempt reset it to zero.
            assertNull("$owner leaked a late capture attempt", v.state.value.status)
            assertEquals(0, v.state.value.shutterFlashTick)
        }
    }

    @Test fun `review ownership cancels a one-shot timer before pinning and never fires later`() {
        val (v, _) = createViewModel()
        armCountdown(v)
        assertEquals(3, v.state.value.timerCountdownSec)

        v.onReviewOpenChange(true, Uri.parse("content://telecam.test/previous"))
        assertEquals(0, v.state.value.timerCountdownSec)
        assertTrue(v.state.value.cameraInputBlocked)
        idleFor(3_100)

        assertNull("review leaked a late capture attempt", v.state.value.status)
        assertEquals(0, v.state.value.shutterFlashTick)
    }

    // ---- Mode change ----

    @Test fun `mode change publishes the video optics and returns photo faithfully`() {
        val (v, _) = createViewModel()
        assertTrue(v.state.value.controls.programAppSide)
        v.onModeChange(CaptureMode.VIDEO)
        val video = v.state.value
        assertEquals(CaptureMode.VIDEO, video.mode)
        // Video PROGRAM stays on the HAL AE (flash metering/cadence); only photo-P runs app-side.
        assertFalse(video.controls.programAppSide)
        v.onModeChange(CaptureMode.PHOTO)
        val photo = v.state.value
        assertEquals(CaptureMode.PHOTO, photo.mode)
        assertTrue(photo.controls.programAppSide)
        // The photographer's Photo shutter survives the round trip (the hidden photo bank).
        assertEquals(ManualControls().exposureTimeNs, photo.controls.exposureTimeNs)
    }

    // ---- The focus-ruler loupe assist must not become a persisted setting ----

    @Test fun `the focus-ruler loupe assist never reaches the saved settings`() {
        val (v, _) = createViewModel()
        assertFalse("precondition: the operator has the loupe off", v.state.value.punchIn)

        // Opening the Focus ruler magnifies by itself — visible immediately...
        v.onAutoPunchIn(true)
        assertTrue("the assist must actually punch in", v.state.value.punchIn)

        // ...and now force a save to actually LAND while the assist owns the loupe. An unrelated
        // user action is the honest way: without one, the fixed code writes nothing at all and the
        // assertion below would pass for the wrong reason — which is exactly how the first version
        // of this test failed to catch the defect it was written for.
        v.onGridType(GridType.NONE)
        idleFor(600)

        val saved = SettingsStore(app).load()
        assertNotNull("a save must have landed, or this test proves nothing", saved)
        assertFalse(
            "a save during the Focus-ruler assist persisted a loupe nobody asked for",
            saved!!.extras.punchIn,
        )
    }

    @Test fun `MR tele focal uses the device host lens instead of a 70 mm default`() {
        val extras = ExtraSettings(
            teleconverter = true,
            teleconverterProfile = TeleconverterProfile.CUSTOM,
            teleconverterCustomMagnification = 4f,
        )
        assertEquals(340f, memoryPresetFocalMm(extras, hostTeleEquivMm = 85f), 0.001f)
        assertEquals(
            LensChoice.MAIN.targetEquivMm,
            memoryPresetFocalMm(extras.copy(teleconverter = false), hostTeleEquivMm = 85f),
            0.001f,
        )
    }

    @Test fun `an operator toggle during the assist is theirs to keep`() {
        val (v, _) = createViewModel()
        v.onAutoPunchIn(true)
        // The operator reaches into the sheet mid-assist and turns it ON deliberately. That ends the
        // assist's ownership — "manual sheet toggles mid-drag win" — so it is what persists.
        v.onTogglePunchIn(true)
        idleFor(600)
        val saved = SettingsStore(app).load()
        assertNotNull(saved)
        assertTrue(
            "an explicit operator toggle during the assist must still be saved",
            saved!!.extras.punchIn,
        )
    }

    @Test fun `closing the ruler restores the operator value without saving it`() {
        val (v, _) = createViewModel()
        v.onAutoPunchIn(true)
        assertTrue(v.state.value.punchIn)
        v.onAutoPunchIn(false)
        assertFalse("closing the ruler must undo the assist", v.state.value.punchIn)
    }
}
