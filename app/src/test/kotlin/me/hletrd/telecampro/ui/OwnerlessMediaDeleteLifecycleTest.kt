package me.hletrd.telecampro.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.storage.KnownOutputProviderDisposition
import me.hletrd.telecampro.storage.MediaProvenance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OwnerlessMediaDeleteLifecycleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var viewModel: CameraViewModel? = null
    private var releaseBlocker: CountDownLatch? = null

    @After fun tearDown() {
        releaseBlocker?.countDown()
        viewModel?.let { vm ->
            CameraViewModel::class.java.getDeclaredMethod("onCleared")
                .apply { isAccessible = true }
                .invoke(vm)
        }
    }

    @Test fun `blocked request construction returns immediately and deadline restores input`() {
        val providerEntered = CountDownLatch(1)
        val allowProvider = CountDownLatch(1).also { releaseBlocker = it }
        val lateReturned = CountDownLatch(1)
        val vm = createViewModel(
            createRequest = { _, _ ->
                providerEntered.countDown()
                allowProvider.await()
                lateReturned.countDown()
                pendingIntent()
            },
        )
        val request = freezeOwnerless(vm, uri(101))

        val before = System.nanoTime()
        vm.beginOwnerlessMediaDeleteRequestCreation(request)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
        assertTrue(providerEntered.await(2, TimeUnit.SECONDS))
        assertTrue("caller blocked ${elapsedMs}ms", elapsedMs < 250L)

        idleProviderDeadline()
        assertFalse(vm.state.value.ownerlessDeleteConsentPending)
        assertFalse(vm.state.value.cameraInputBlocked)
        assertEquals(request.uri, vm.state.value.lastMediaUri)
        assertEquals(
            CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
            vm.state.value.status?.message,
        )
        assertNull(vm.ownerlessMediaDeleteLaunch.value)

        allowProvider.countDown()
        assertTrue(lateReturned.await(2, TimeUnit.SECONDS))
        idleMain()
        assertNull(vm.ownerlessMediaDeleteLaunch.value)
        assertEquals(request.uri, vm.state.value.lastMediaUri)
    }

    @Test fun `request construction exception terminalizes once as authorization unavailable`() {
        val vm = createViewModel(
            createRequest = { _, _ -> throw SecurityException("provider policy") },
        )
        val request = freezeOwnerless(vm, uri(105))

        vm.beginOwnerlessMediaDeleteRequestCreation(request)
        waitUntil {
            idleMain()
            !vm.state.value.ownerlessDeleteConsentPending
        }

        assertFalse(vm.state.value.cameraInputBlocked)
        assertEquals(request.uri, vm.state.value.lastMediaUri)
        assertEquals(
            CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
            vm.state.value.status?.message,
        )
        assertNull(vm.ownerlessMediaDeleteLaunch.value)
    }

    @Test fun `finite request lane rejection restores without running provider inline`() {
        val capacity = ViewModelMediaDeleteCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = daemonFactory("rejected-request"),
        )
        val dispatcher = ViewModelMediaDeleteDispatcher(capacity)
        val blockerEntered = CountDownLatch(1)
        val allowBlocker = CountDownLatch(1).also { releaseBlocker = it }
        assertEquals(
            ViewModelMediaDeleteDispatch.ACCEPTED,
            dispatcher.dispatch(Runnable {
                blockerEntered.countDown()
                allowBlocker.await()
            }),
        )
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
        assertEquals(
            ViewModelMediaDeleteDispatch.ACCEPTED,
            dispatcher.dispatch(Runnable {}),
        )
        val providerRan = AtomicBoolean()
        val vm = createViewModel(
            dispatcher = dispatcher,
            createRequest = { _, _ ->
                providerRan.set(true)
                pendingIntent()
            },
        )
        val request = freezeOwnerless(vm, uri(106))

        vm.beginOwnerlessMediaDeleteRequestCreation(request)
        idleMain()

        assertFalse(providerRan.get())
        assertFalse(vm.state.value.ownerlessDeleteConsentPending)
        assertFalse(vm.state.value.cameraInputBlocked)
        assertEquals(request.uri, vm.state.value.lastMediaUri)
        assertEquals(
            CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
            vm.state.value.status?.message,
        )
    }

    @Test fun `consent reconciliation queued behind blocker times out independently`() {
        val capacity = ViewModelMediaDeleteCapacityOwner(
            workerCount = 1,
            backlogCapacity = 2,
            threadFactory = daemonFactory("queued-consent"),
        )
        val dispatcher = ViewModelMediaDeleteDispatcher(capacity)
        val blockerEntered = CountDownLatch(1)
        val allowBlocker = CountDownLatch(1).also { releaseBlocker = it }
        assertEquals(
            ViewModelMediaDeleteDispatch.ACCEPTED,
            dispatcher.dispatch(Runnable {
                blockerEntered.countDown()
                allowBlocker.await()
            }),
        )
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
        val queryRan = AtomicBoolean()
        val vm = createViewModel(
            dispatcher = dispatcher,
            queryPresence = { _, _ ->
                queryRan.set(true)
                KnownOutputProviderDisposition.PRESENT
            },
        )
        val request = freezeOwnerless(vm, uri(102))

        vm.onOwnerlessMediaDeleteConsentResult(
            request,
            OwnerlessMediaDeleteConsentResult.CANCELED,
        )
        idleProviderDeadline()

        assertFalse(queryRan.get())
        assertCanceledAndRestored(vm, request.uri)
        allowBlocker.countDown()
        waitUntil { queryRan.get() }
        idleMain()
        assertCanceledAndRestored(vm, request.uri)
    }

    @Test fun `actively wedged presence query times out and late answer is inert`() {
        val queryEntered = CountDownLatch(1)
        val allowQuery = CountDownLatch(1).also { releaseBlocker = it }
        val queryReturned = CountDownLatch(1)
        val vm = createViewModel(
            dispatcher = localDispatcher("active-consent"),
            queryPresence = { _, _ ->
                queryEntered.countDown()
                allowQuery.await()
                queryReturned.countDown()
                KnownOutputProviderDisposition.ALREADY_ABSENT
            },
        )
        val request = freezeOwnerless(vm, uri(103))

        vm.onOwnerlessMediaDeleteConsentResult(
            request,
            OwnerlessMediaDeleteConsentResult.LAUNCH_FAILED,
        )
        assertTrue(queryEntered.await(2, TimeUnit.SECONDS))
        idleProviderDeadline()

        assertFalse(vm.state.value.ownerlessDeleteConsentPending)
        assertFalse(vm.state.value.cameraInputBlocked)
        assertEquals(request.uri, vm.state.value.lastMediaUri)
        assertEquals(
            CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
            vm.state.value.status?.message,
        )

        allowQuery.countDown()
        assertTrue(queryReturned.await(2, TimeUnit.SECONDS))
        idleMain()
        assertEquals(request.uri, vm.state.value.lastMediaUri)
        assertEquals(
            CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
            vm.state.value.status?.message,
        )
    }

    @Test fun `ready request is claimed once and stale launch cannot escape`() {
        val vm = createViewModel()
        val request = freezeOwnerless(vm, uri(104))
        vm.beginOwnerlessMediaDeleteRequestCreation(request)
        waitUntil {
            idleMain()
            vm.ownerlessMediaDeleteLaunch.value != null
        }
        val launch = checkNotNull(vm.ownerlessMediaDeleteLaunch.value)

        assertTrue(vm.claimOwnerlessMediaDeleteLaunch(launch))
        assertFalse(vm.claimOwnerlessMediaDeleteLaunch(launch))
        vm.onOwnerlessMediaDeleteConsentResult(
            request,
            OwnerlessMediaDeleteConsentResult.APPROVED,
        )
        assertFalse(vm.claimOwnerlessMediaDeleteLaunch(launch))
    }

    private fun createViewModel(
        createRequest: (android.content.ContentResolver, Uri) -> PendingIntent = { _, _ ->
            pendingIntent()
        },
        queryPresence: (android.content.Context, Uri) -> KnownOutputProviderDisposition = { _, _ ->
            KnownOutputProviderDisposition.PRESENT
        },
        dispatcher: ViewModelMediaDeleteDispatcher = localDispatcher("ownerless-delete"),
    ): CameraViewModel {
        RobolectricEglSentinels.ensure()
        return CameraViewModel(
            app,
            CameraEngine(app),
            OwnerlessMediaDeleteOverrides(
                createDeleteRequest = createRequest,
                queryPresence = queryPresence,
                dispatcher = dispatcher,
            ),
        ).also { viewModel = it }
    }

    private fun freezeOwnerless(vm: CameraViewModel, uri: Uri): OwnerlessMediaDeleteRequest {
        tracker(vm).seedPriorCapture(
            outputs = listOf(
                PriorCaptureOutput(
                    output = uri,
                    kind = CaptureOutputKind.DISPLAYABLE,
                    provenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
                ),
            ),
            preferredOutput = uri,
            deleteScope = MediaDeleteScope.FILE_ONLY,
        )
        state(vm).value = state(vm).value.copy(
            lastMediaUri = uri,
            lastMediaProvenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
            lastMediaDeleteScope = MediaDeleteScope.FILE_ONLY,
        )
        return (vm.prepareOwnerlessMediaDelete(
            uri,
            MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
        ) as OwnerlessMediaDeletePreparation.ConsentRequired).request
    }

    private fun assertCanceledAndRestored(vm: CameraViewModel, uri: Uri) {
        assertFalse(vm.state.value.ownerlessDeleteConsentPending)
        assertFalse(vm.state.value.cameraInputBlocked)
        assertEquals(uri, vm.state.value.lastMediaUri)
        assertEquals(CameraStatusMessage.DELETE_CANCELED, vm.state.value.status?.message)
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getActivity(
        app,
        0,
        Intent(app, me.hletrd.telecampro.MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun localDispatcher(name: String): ViewModelMediaDeleteDispatcher =
        ViewModelMediaDeleteDispatcher(
            ViewModelMediaDeleteCapacityOwner(1, 2, daemonFactory(name)),
        )

    private fun daemonFactory(name: String) = ThreadFactory { task ->
        Thread(task, name).apply { isDaemon = true }
    }

    private fun idleProviderDeadline() {
        shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(OWNERLESS_MEDIA_DELETE_PROVIDER_TIMEOUT_MS + 1L),
        )
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!predicate() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(predicate())
    }

    @Suppress("UNCHECKED_CAST")
    private fun tracker(vm: CameraViewModel): CaptureOutputTracker<Uri> =
        CameraViewModel::class.java.getDeclaredField("captureOutputs")
            .apply { isAccessible = true }
            .get(vm) as CaptureOutputTracker<Uri>

    @Suppress("UNCHECKED_CAST")
    private fun state(vm: CameraViewModel): MutableStateFlow<CameraUiState> =
        CameraViewModel::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<CameraUiState>

    private fun uri(id: Int): Uri = Uri.parse("content://media/external/images/media/$id")
}
