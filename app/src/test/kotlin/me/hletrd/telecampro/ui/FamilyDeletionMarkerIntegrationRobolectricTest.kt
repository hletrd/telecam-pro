package me.hletrd.telecampro.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureFamilyDeleteDurability
import me.hletrd.telecampro.camera.CaptureFamilyDeleteIntent
import me.hletrd.telecampro.camera.FamilyDeletionMarkerCapacityOwner
import me.hletrd.telecampro.camera.FamilyDeletionMarkerDispatcher
import me.hletrd.telecampro.camera.FamilyDeletionMarkerDispatch
import me.hletrd.telecampro.camera.FamilyDeletionMarkerEngineOverrides
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.camera.ProcessRetainedStillDiscardOwner
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.CaptureFamilyMedia
import me.hletrd.telecampro.storage.FamilyDeletionRetirementResult
import me.hletrd.telecampro.storage.MediaProvenance
import me.hletrd.telecampro.storage.MediaStoreWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FamilyDeletionMarkerIntegrationRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var viewModel: CameraViewModel? = null
    private var standaloneEngine: CameraEngine? = null

    init {
        RobolectricEglSentinels.ensure()
    }

    @After
    fun tearDown() {
        viewModel?.let { current ->
            CameraViewModel::class.java.getDeclaredMethod("onCleared")
                .apply { isAccessible = true }
                .invoke(current)
        }
        standaloneEngine?.release()
    }

    @Test
    fun `pre-marker overflow promptly restores the exact frozen review`() {
        val releaseMarker = CountDownLatch(1)
        val markerEntered = CountDownLatch(1)
        val capacity = FamilyDeletionMarkerCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "vm-family-marker-blocker").apply { isDaemon = true }
            },
        )
        val blocker = FamilyDeletionMarkerDispatcher(capacity)
        val first = blocker.reserve(family(1))
        assertTrue(
            checkNotNull(first.reservation).submit(
                Runnable {
                    markerEntered.countDown()
                    releaseMarker.await()
                },
            ),
        )
        assertTrue(markerEntered.await(5, TimeUnit.SECONDS))
        val queued = blocker.reserve(family(2))
        assertTrue(checkNotNull(queued.reservation).submit(Runnable {}))
        assertEquals(2, capacity.admittedFamilyCount())

        val engine = CameraEngine(
            app,
            familyDeletionMarkerOverrides = FamilyDeletionMarkerEngineOverrides(capacity),
        )
        val vm = CameraViewModel(app, engine).also { viewModel = it }
        val uri = Uri.parse("content://media/external/images/media/333")
        val reviewedFamily = family(333)
        assertTrue(
            captureTracker(vm).seedPriorCapture(
                outputs = listOf(
                    PriorCaptureOutput(
                        output = uri,
                        kind = CaptureOutputKind.DISPLAYABLE,
                        provenance = MediaProvenance.APP_OWNED,
                    ),
                ),
                preferredOutput = uri,
                deleteScope = MediaDeleteScope.CAPTURE_FAMILY,
                familyKey = reviewedFamily,
            ),
        )
        updateState(vm) {
            it.copy(
                lastMediaUri = uri,
                lastMediaProvenance = MediaProvenance.APP_OWNED,
                lastMediaDeleteScope = MediaDeleteScope.CAPTURE_FAMILY,
            )
        }

        try {
            vm.onDeleteLastMedia(uri, MediaProvenance.APP_OWNED)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

            assertEquals(uri, vm.state.value.lastMediaUri)
            assertEquals(MediaProvenance.APP_OWNED, vm.state.value.lastMediaProvenance)
            assertEquals(MediaDeleteScope.CAPTURE_FAMILY, vm.state.value.lastMediaDeleteScope)
            assertEquals(CameraStatusMessage.COULD_NOT_DELETE_FILE, vm.state.value.status?.message)
            assertEquals(2, capacity.admittedFamilyCount())
            assertEquals(
                FamilyDeletionMarkerDispatch.OVERFLOW,
                blocker.reserve(reviewedFamily).dispatch,
            )
        } finally {
            releaseMarker.countDown()
            blocker.shutdown()
        }
    }

    @Test
    fun `accepted family marker completes while still encoding executor is blocked`() {
        val ioRelease = CountDownLatch(1)
        val ioEntered = CountDownLatch(1)
        val markerFinished = CountDownLatch(1)
        val markerThread = AtomicReference<String>()
        val markerResult = AtomicReference<CaptureFamilyDeleteDurability>()
        val capacity = FamilyDeletionMarkerCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "engine-family-marker-test").apply { isDaemon = true }
            },
        )
        val engine = CameraEngine(
            app,
            familyDeletionMarkerOverrides = FamilyDeletionMarkerEngineOverrides(capacity),
        ).also { standaloneEngine = it }
        val ioExecutor = CameraEngine::class.java.getDeclaredField("ioExecutor")
            .apply { isAccessible = true }
            .get(engine) as ExecutorService
        ioExecutor.execute {
            ioEntered.countDown()
            ioRelease.await()
        }
        assertTrue(ioEntered.await(5, TimeUnit.SECONDS))
        val deletedFamily = family(444)

        try {
            engine.markCaptureDeleted(
                CaptureFamilyDeleteIntent(
                    familyKey = deletedFamily,
                    scope = MediaDeleteScope.CAPTURE_FAMILY,
                    liveStillCaptureId = null,
                ),
            ) { result ->
                markerThread.set(Thread.currentThread().name)
                markerResult.set(result)
                markerFinished.countDown()
            }

            assertTrue(markerFinished.await(5, TimeUnit.SECONDS))
            assertEquals(CaptureFamilyDeleteDurability.DURABLE, markerResult.get())
            assertEquals("engine-family-marker-test", markerThread.get())
            assertEquals(0, capacity.admittedFamilyCount())
        } finally {
            ioRelease.countDown()
            val retirement = MediaStoreWriter.retireFamilyDeletionMarker(
                context = app,
                family = deletedFamily,
                producersTerminal = true,
                exactFamilyAbsent = { true },
            )
            assertTrue(
                retirement == FamilyDeletionRetirementResult.RETIRED ||
                    retirement == FamilyDeletionRetirementResult.ALREADY_ABSENT,
            )
            ProcessRetainedStillDiscardOwner.reconcileFamilyRetirement(deletedFamily, retirement)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun captureTracker(vm: CameraViewModel): CaptureOutputTracker<Uri> =
        CameraViewModel::class.java.getDeclaredField("captureOutputs")
            .apply { isAccessible = true }
            .get(vm) as CaptureOutputTracker<Uri>

    @Suppress("UNCHECKED_CAST")
    private fun updateState(
        vm: CameraViewModel,
        transform: (CameraUiState) -> CameraUiState,
    ) {
        val state = CameraViewModel::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<CameraUiState>
        state.value = transform(state.value)
    }

    private fun family(sequence: Long) = CaptureFamilyKey(
        media = CaptureFamilyMedia.STILL,
        capturedAtEpochMillis = 1_700_041_000_000L + sequence,
        sequence = sequence,
    )
}
