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
import me.hletrd.telecampro.camera.CameraPolicyPublication
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
import me.hletrd.telecampro.storage.DeletedFamilySweepResult
import me.hletrd.telecampro.storage.DiscardMarkerCleanupDisposition
import me.hletrd.telecampro.storage.KnownOutputDeletionResult
import me.hletrd.telecampro.storage.KnownOutputProviderDisposition
import me.hletrd.telecampro.storage.MediaProvenance
import me.hletrd.telecampro.storage.RestoredCapture
import me.hletrd.telecampro.storage.RestoredCaptureOutput
import me.hletrd.telecampro.storage.RestoredDeleteScope
import me.hletrd.telecampro.storage.SettingsStore
import me.hletrd.telecampro.storage.StoredMediaOutputKind
import me.hletrd.telecampro.video.CodecComponent
import me.hletrd.telecampro.video.CodecInventory
import me.hletrd.telecampro.video.buildCodecInventory
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class QueuedTasks {
        private val tasks = ArrayDeque<Runnable>()

        fun submit(task: Runnable): Boolean {
            tasks.addLast(task)
            return true
        }

        fun runNext() = tasks.removeFirst().run()
        fun size(): Int = tasks.size
    }

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

    private fun setRecordingPresentation(
        v: CameraViewModel,
        recording: Boolean,
        starting: Boolean,
        finalizing: Boolean = false,
    ) {
        @Suppress("UNCHECKED_CAST")
        val state = CameraViewModel::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(v) as MutableStateFlow<CameraUiState>
        state.value = state.value.copy(
            isRecording = recording,
            isRecordingStarting = starting,
            isRecordingFinalizing = finalizing,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun captureTracker(v: CameraViewModel): CaptureOutputTracker<Uri> =
        CameraViewModel::class.java.getDeclaredField("captureOutputs")
            .apply { isAccessible = true }
            .get(v) as CaptureOutputTracker<Uri>

    @Suppress("UNCHECKED_CAST")
    private fun setState(v: CameraViewModel, transform: (CameraUiState) -> CameraUiState) {
        val state = CameraViewModel::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(v) as MutableStateFlow<CameraUiState>
        state.value = transform(state.value)
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

    @Test fun `AndroidViewModelFactory retains the public Application constructor`() {
        assertNotNull(CameraViewModel::class.java.getConstructor(Application::class.java))
    }

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

    @Test fun `production gallery action bounds repeated restore requests and drops satisfied tail`() {
        RobolectricEglSentinels.ensure()
        val provider = QueuedTasks()
        val completion = QueuedTasks()
        val restoredUri = Uri.parse("content://media/external/images/media/501")
        val output = RestoredCaptureOutput(
            output = restoredUri,
            kind = StoredMediaOutputKind.DISPLAYABLE,
            displayName = "TCP_20260824_120000_001.heic",
            provenance = MediaProvenance.APP_OWNED,
        )
        var queries = 0
        val e = CameraEngine(app)
        val v = CameraViewModel(
            app,
            e,
            LatestCaptureRestoreOverrides(
                submit = provider::submit,
                postCompletion = completion::submit,
                query = {
                    queries += 1
                    RestoredCapture(
                        preferred = output,
                        outputs = listOf(output),
                        familyKey = null,
                        deleteScope = RestoredDeleteScope.FILE_ONLY,
                    )
                },
            ),
        )
        vm = v
        engine = e

        repeat(100) { v.onGalleryAccessRequested() }

        assertEquals(1, provider.size())
        provider.runNext()
        assertEquals(1, completion.size())
        completion.runNext()

        assertEquals(1, queries)
        assertEquals(restoredUri, v.state.value.lastMediaUri)
        assertEquals(0, provider.size())
    }

    @Test fun `ViewModel clear makes an already-posted restore completion inert`() {
        RobolectricEglSentinels.ensure()
        val provider = QueuedTasks()
        val completion = QueuedTasks()
        val restoredUri = Uri.parse("content://media/external/images/media/502")
        val output = RestoredCaptureOutput(
            output = restoredUri,
            kind = StoredMediaOutputKind.DISPLAYABLE,
            displayName = "TCP_20260824_120000_002.heic",
            provenance = MediaProvenance.APP_OWNED,
        )
        val e = CameraEngine(app)
        val v = CameraViewModel(
            app,
            e,
            LatestCaptureRestoreOverrides(
                submit = provider::submit,
                postCompletion = completion::submit,
                query = {
                    RestoredCapture(
                        preferred = output,
                        outputs = listOf(output),
                        familyKey = null,
                        deleteScope = RestoredDeleteScope.FILE_ONLY,
                    )
                },
            ),
        )
        vm = v
        engine = e

        repeat(20) { v.onGalleryAccessRequested() }
        provider.runNext()
        assertEquals(1, completion.size())

        clearViewModel(v)
        vm = null
        engine = null
        completion.runNext()

        assertNull(v.state.value.lastMediaUri)
        assertEquals(0, provider.size())
    }

    @Test fun `ordinary partial delete restores survivor with ownership-safe gallery retry`() {
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

        val state = resolveDeleteSurvivorState(
            current = CameraUiState(),
            survivor = survivor,
            captureOutputs = tracker,
        )

        assertEquals(
            CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
            deleteResultStatus(false, DeletedFamilySweepResult()),
        )
        assertEquals(survivorUri, state.lastMediaUri)
        assertEquals(MediaProvenance.LEGACY_FORMAT_UNVERIFIED, state.lastMediaProvenance)
        assertEquals(MediaDeleteScope.FILE_ONLY, state.lastMediaDeleteScope)
    }

    @Test fun `ownerless review freezes for system consent and approval never issues direct delete`() {
        val (v, _) = createViewModel()
        val uri = Uri.parse("content://media/external/images/media/41")
        assertTrue(
            captureTracker(v).seedPriorCapture(
                outputs = listOf(
                    PriorCaptureOutput(
                        output = uri,
                        kind = CaptureOutputKind.DISPLAYABLE,
                        provenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
                    ),
                ),
                preferredOutput = uri,
                deleteScope = MediaDeleteScope.FILE_ONLY,
            ),
        )
        setState(v) {
            it.copy(
                lastMediaUri = uri,
                lastMediaProvenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
                lastMediaDeleteScope = MediaDeleteScope.FILE_ONLY,
            )
        }

        assertEquals(
            OwnerlessMediaDeletePreparation.ConsentRequired::class.java,
            v.prepareOwnerlessMediaDelete(uri, MediaProvenance.LEGACY_FORMAT_UNVERIFIED)::class.java,
        )
        assertEquals(
            OwnerlessMediaDeletePreparation.Rejected,
            v.prepareOwnerlessMediaDelete(uri, MediaProvenance.LEGACY_FORMAT_UNVERIFIED),
        )
        assertTrue(v.state.value.ownerlessDeleteConsentPending)
        assertTrue(v.state.value.cameraInputBlocked)
        assertNull(v.state.value.lastMediaUri)

        // RESULT_OK means MediaStore's PendingIntent already completed deletion. This edge clears
        // tracker/UI ownership synchronously and performs no ContentResolver.delete call.
        v.onOwnerlessMediaDeleteConsentResult(OwnerlessMediaDeleteConsentResult.APPROVED)
        assertFalse(v.state.value.ownerlessDeleteConsentPending)
        assertFalse(v.state.value.cameraInputBlocked)
        assertNull(v.state.value.lastMediaUri)
        assertEquals(CameraStatusMessage.DELETED, v.state.value.status?.message)
    }

    @Test fun `app-owned review stays on direct deletion and ownerless callers cannot bypass consent`() {
        val (v, _) = createViewModel()
        val ownerless = Uri.parse("content://media/external/images/media/43")
        assertTrue(
            captureTracker(v).seedPriorCapture(
                outputs = listOf(
                    PriorCaptureOutput(
                        ownerless,
                        CaptureOutputKind.DISPLAYABLE,
                        MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
                    ),
                ),
                preferredOutput = ownerless,
                deleteScope = MediaDeleteScope.FILE_ONLY,
            ),
        )
        v.onDeleteLastMedia(ownerless, MediaProvenance.LEGACY_FORMAT_UNVERIFIED)
        assertEquals(
            CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
            v.state.value.status?.message,
        )
        assertTrue(captureTracker(v).isCurrentReviewOutput(ownerless))

        val owned = Uri.parse("content://media/external/images/media/42")
        captureTracker(v).record(42, owned, CaptureOutputKind.DISPLAYABLE)
        assertEquals(
            OwnerlessMediaDeletePreparation.DirectAppOwned,
            v.prepareOwnerlessMediaDelete(owned, MediaProvenance.APP_OWNED),
        )
        assertFalse(v.state.value.ownerlessDeleteConsentPending)
    }

    @Test fun `delete authorization routing and terminal results preserve provenance honestly`() {
        assertEquals(
            MediaDeleteAuthorizationRoute.DIRECT_APP_OWNED,
            mediaDeleteAuthorizationRoute(
                trackedProvenance = MediaProvenance.APP_OWNED,
                presentedProvenance = MediaProvenance.APP_OWNED,
            ),
        )
        // Either the tracker or the frozen review identifying an ownerless row must win over a stale
        // APP_OWNED value on the other side of the UI boundary.
        assertEquals(
            MediaDeleteAuthorizationRoute.SYSTEM_CONSENT,
            mediaDeleteAuthorizationRoute(
                trackedProvenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
                presentedProvenance = MediaProvenance.APP_OWNED,
            ),
        )
        assertEquals(
            MediaDeleteAuthorizationRoute.SYSTEM_CONSENT,
            mediaDeleteAuthorizationRoute(
                trackedProvenance = null,
                presentedProvenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
            ),
        )

        assertEquals(
            OwnerlessMediaDeleteResolution(false, CameraStatusMessage.DELETED),
            ownerlessMediaDeleteResolution(
                OwnerlessMediaDeleteConsentResult.APPROVED,
                KnownOutputProviderDisposition.PRESENT,
            ),
        )
        assertEquals(
            OwnerlessMediaDeleteResolution(true, CameraStatusMessage.DELETE_CANCELED),
            ownerlessMediaDeleteResolution(
                OwnerlessMediaDeleteConsentResult.CANCELED,
                KnownOutputProviderDisposition.PRESENT,
            ),
        )
        assertEquals(
            OwnerlessMediaDeleteResolution(false, CameraStatusMessage.FILE_ALREADY_REMOVED),
            ownerlessMediaDeleteResolution(
                OwnerlessMediaDeleteConsentResult.CANCELED,
                KnownOutputProviderDisposition.ALREADY_ABSENT,
            ),
        )
        assertEquals(
            OwnerlessMediaDeleteResolution(
                true,
                CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
            ),
            ownerlessMediaDeleteResolution(
                OwnerlessMediaDeleteConsentResult.LAUNCH_FAILED,
                KnownOutputProviderDisposition.UNKNOWN,
            ),
        )
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

        val state = resolveDeleteSurvivorState(
            current = newerState,
            survivor = restored,
            captureOutputs = tracker,
        )

        assertEquals(
            CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
            deleteResultStatus(false, DeletedFamilySweepResult()),
        )
        assertEquals(newerState, state)
        assertEquals(newerUri, state.lastMediaUri)
        assertEquals(MediaProvenance.LEGACY_FORMAT_UNVERIFIED, state.lastMediaProvenance)
        assertEquals(MediaDeleteScope.FILE_ONLY, state.lastMediaDeleteScope)
    }

    @Test fun `delete result reports success only when no survivor remains`() {
        assertEquals(
            CameraStatusMessage.DELETED,
            deleteResultStatus(true, DeletedFamilySweepResult()),
        )
        assertEquals(
            CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
            deleteResultStatus(false, DeletedFamilySweepResult()),
        )
        assertEquals(
            CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
            deleteResultStatus(
                true,
                DeletedFamilySweepResult(discovered = 1, unresolved = 1),
            ),
        )
        assertEquals(
            CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
            deleteResultStatus(true, DeletedFamilySweepResult.QUERY_FAILED),
        )
    }

    @Test fun `known output composition restores only confirmed provider survivors`() {
        val deletedCleanupRetry = Uri.parse("content://media/deleted-cleanup-retry")
        val absentCleanupRetry = Uri.parse("content://media/absent-cleanup-retry")
        val survivor = Uri.parse("content://media/confirmed-survivor")
        val unknown = Uri.parse("content://media/provider-unknown")

        val composition = knownOutputDeleteComposition(
            linkedMapOf(
                deletedCleanupRetry to KnownOutputDeletionResult(
                    provider = KnownOutputProviderDisposition.DELETED,
                    markerCleanup = DiscardMarkerCleanupDisposition.RETAINED_FOR_RETRY,
                ),
                absentCleanupRetry to KnownOutputDeletionResult(
                    provider = KnownOutputProviderDisposition.ALREADY_ABSENT,
                    markerCleanup = DiscardMarkerCleanupDisposition.RETAINED_FOR_RETRY,
                ),
                survivor to KnownOutputDeletionResult(
                    provider = KnownOutputProviderDisposition.PRESENT,
                    markerCleanup = DiscardMarkerCleanupDisposition.NOT_ATTEMPTED,
                ),
                unknown to KnownOutputDeletionResult(
                    provider = KnownOutputProviderDisposition.UNKNOWN,
                    markerCleanup = DiscardMarkerCleanupDisposition.NOT_ATTEMPTED,
                ),
            ),
        )

        assertEquals(setOf(survivor), composition.survivors)
        assertEquals(setOf(deletedCleanupRetry, absentCleanupRetry), composition.cleanupRetry)
        assertEquals(setOf(unknown), composition.providerUnknown)
        assertFalse(composition.providerDeletionComplete)
        assertEquals(
            CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
            deleteResultStatus(composition.providerDeletionComplete, DeletedFamilySweepResult()),
        )

        val absentOnly = knownOutputDeleteComposition(
            mapOf(
                deletedCleanupRetry to KnownOutputDeletionResult(
                    provider = KnownOutputProviderDisposition.DELETED,
                    markerCleanup = DiscardMarkerCleanupDisposition.RETAINED_FOR_RETRY,
                ),
                absentCleanupRetry to KnownOutputDeletionResult(
                    provider = KnownOutputProviderDisposition.ALREADY_ABSENT,
                    markerCleanup = DiscardMarkerCleanupDisposition.RETAINED_FOR_RETRY,
                ),
            ),
        )
        assertTrue(absentOnly.survivors.isEmpty())
        assertTrue(absentOnly.providerDeletionComplete)
        assertEquals(
            CameraStatusMessage.DELETED,
            deleteResultStatus(absentOnly.providerDeletionComplete, DeletedFamilySweepResult()),
        )
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
        assertNotNull(e.onRecordingFinalizing)
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

    @Test fun `camera condition progress waits for Ready not a timer`() {
        val (v, e) = createViewModel()
        val conditions = listOf(
            CameraStatusMessage.STARTING_CAMERA,
            CameraStatusMessage.CAMERA_RECONFIGURING,
            CameraStatusMessage.PREVIEW_INTERRUPTED_RECOVERING,
            CameraStatusMessage.CAMERA_ERROR_RECOVERING,
            CameraStatusMessage.PREVIEW_UNAVAILABLE_RETRYING,
            CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING,
        )
        conditions.forEach { message ->
            val status = message.status()
            e.onStatus!!.invoke(status)
            // Deliberately farther than the former longest timer: a condition remains until an
            // exact terminal/new event, however fast or slow the Camera2 path is.
            idleFor(10_000)
            assertEquals(message.name, status, v.state.value.status)
        }

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

    @Test fun `unavailable hardware shutter preserves the terminal camera verdict`() {
        val (v, e) = createViewModel()
        val terminals = listOf(
            CameraStatusMessage.PREVIEW_UNAVAILABLE_REOPEN,
            CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN,
        )
        terminals.forEach { message ->
            val terminal = message.status()
            e.onStatus!!.invoke(terminal)

            v.onHardwareFullKey(true)
            v.onHardwareFullKey(false)
            v.onHardwareQuickButton(true)
            v.onHardwareQuickButton(false)

            assertEquals(message.name, terminal, v.state.value.status)
            assertFalse(v.state.value.isRecording)
            assertEquals(0, v.state.value.shutterFlashTick)
            idleFor(5_999)
            assertEquals(message.name, terminal, v.state.value.status)
            idleFor(1)
            assertNull(v.state.value.status)
        }
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

    @Test fun `replacement Compose release preserves restored microphone permission ownership`() {
        val (v, _) = createViewModel()
        v.onCameraInputBlockOwnerChange(CameraInputBlockOwner.MICROPHONE_PERMISSION, true)
        assertTrue(v.state.value.cameraInputBlocked)

        // Fresh CameraScreen composition publishes modalVisible=false. It owns only COMPOSE_MODAL.
        v.onCameraInputBlockedChange(false)
        assertTrue(v.state.value.cameraInputBlocked)

        v.onCameraInputBlockOwnerChange(CameraInputBlockOwner.MICROPHONE_PERMISSION, false)
        assertFalse(v.state.value.cameraInputBlocked)
    }

    @Test fun `review ownership cancels a one-shot timer before pinning and never fires later`() {
        val (v, _) = createViewModel()
        armCountdown(v)
        assertEquals(3, v.state.value.timerCountdownSec)

        val uri = Uri.parse("content://telecam.test/previous")
        v.onReviewOpenChange(true, uri)
        assertEquals(0, v.state.value.timerCountdownSec)
        assertTrue(v.state.value.cameraInputBlocked)
        assertEquals(uri, v.state.value.openReview?.uri)
        assertEquals(MediaDeleteScope.FILE_ONLY, v.state.value.openReview?.deleteScope)
        idleFor(3_100)

        assertNull("review leaked a late capture attempt", v.state.value.status)
        assertEquals(0, v.state.value.shutterFlashTick)
    }

    @Test fun `policy replacement preserves exact review until its own close retires every owner`() {
        val (v, e) = createViewModel()
        val uri = Uri.parse("content://telecam.test/policy-review")
        assertFalse("untracked fixture is file-only", v.onReviewOpenChange(true, uri))
        val frozen = v.state.value.openReview
        assertNotNull(frozen)

        // MainActivity replaces CameraScreen with PermissionGate for this Engine state and installs
        // a separate owner. The exact review is ViewModel state, so composition replacement cannot
        // discard its URI while retaining only the pin/block Boolean.
        e.onCameraPolicyBlocked!!.invoke(CameraPolicyPublication(1L, true))
        v.onCameraInputBlockOwnerChange(CameraInputBlockOwner.CAMERA_POLICY, true)
        assertEquals(frozen, v.state.value.openReview)
        assertTrue(v.state.value.cameraInputBlocked)

        e.onCameraPolicyBlocked!!.invoke(CameraPolicyPublication(2L, false))
        e.onCameraPolicyBlocked!!.invoke(CameraPolicyPublication(1L, true))
        assertFalse("older policy terminal repainted replacement truth", v.state.value.cameraPolicyBlocked)
        v.onCameraInputBlockOwnerChange(CameraInputBlockOwner.CAMERA_POLICY, false)
        assertEquals(frozen, v.state.value.openReview)
        assertTrue("the reconstructed review still owns input", v.state.value.cameraInputBlocked)

        v.onReviewOpenChange(false, uri)
        assertNull(v.state.value.openReview)
        assertFalse(v.state.value.reviewOpen)
        assertFalse(v.state.value.cameraInputBlocked)
    }

    @Test fun `review gate covers every starting and active video combination`() {
        val (v, _) = createViewModel()
        val uri = Uri.parse("content://telecam.test/previous-video")

        data class Case(val starting: Boolean, val recording: Boolean, val refused: Boolean)
        val cases = listOf(
            Case(starting = false, recording = true, refused = true),
            Case(starting = true, recording = false, refused = true),
            Case(starting = true, recording = true, refused = true),
            // Keep idle last: it deliberately acquires modal ownership for the untracked fixture.
            Case(starting = false, recording = false, refused = false),
        )
        for ((starting, recording, refused) in cases) {
            setRecordingPresentation(v, recording = recording, starting = starting)
            val pinned = v.onReviewOpenChange(true, uri)
            assertFalse("untracked fixture family is never pinnable", pinned)
            if (refused) {
                assertFalse("review opened for starting=$starting recording=$recording", v.state.value.reviewOpen)
                assertFalse(v.state.value.cameraInputBlocked)
                assertEquals(CameraStatusMessage.STOP_RECORDING_FIRST, v.state.value.status?.message)
            } else {
                assertTrue(v.state.value.reviewOpen)
                assertTrue(v.state.value.cameraInputBlocked)
            }
        }
    }

    @Test fun `native finalization blocks review after visible REC clears`() {
        val (v, e) = createViewModel()
        val uri = Uri.parse("content://telecam.test/previous-video")
        setRecordingPresentation(v, recording = false, starting = false)

        e.onRecordingFinalizing?.invoke(true)
        assertTrue(v.state.value.isRecordingFinalizing)
        assertFalse(v.state.value.isRecording)
        assertFalse(v.onReviewOpenChange(true, uri))
        assertFalse(v.state.value.reviewOpen)
        assertEquals(CameraStatusMessage.STOP_RECORDING_FIRST, v.state.value.status?.message)

        e.onRecordingFinalizing?.invoke(false)
        assertFalse(v.state.value.isRecordingFinalizing)
        assertFalse("untracked fixture is not pinnable", v.onReviewOpenChange(true, uri))
        assertTrue("terminal native release restores review ownership", v.state.value.reviewOpen)
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

    @Test fun `MR tele focal uses the measured device host only for an OTHER phone`() {
        val extras = ExtraSettings(
            teleconverter = true,
            phoneModel = me.hletrd.telecampro.camera.PhoneModel.OTHER,
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
