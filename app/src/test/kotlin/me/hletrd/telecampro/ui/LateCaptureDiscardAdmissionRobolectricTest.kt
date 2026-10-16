package me.hletrd.telecampro.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AGG4-1: an UNRESOLVED discard of a late capture sibling reports status only. The still-capture
 * admission bit is ENGINE-owned and change-gated; a ViewModel write of `false` was never followed by
 * a `true`, so one failed discard latched the photo and hardware shutters dead for the life of the
 * ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
class LateCaptureDiscardAdmissionRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var viewModel: CameraViewModel? = null
    private val dispatcher = ViewModelMediaDeleteDispatcher(
        ViewModelMediaDeleteCapacityOwner(
            1,
            2,
            ThreadFactory { task -> Thread(task, "late-discard").apply { isDaemon = true } },
        ),
    )

    @After fun tearDown() {
        viewModel?.let(ViewModelTestAccess::clear)
    }

    @Test fun `an unresolved late-sibling discard never latches the primary shutter off`() {
        RobolectricEglSentinels.ensure()
        val vm = CameraViewModel(
            app,
            CameraEngine(app),
            OwnerlessMediaDeleteOverrides(dispatcher = dispatcher),
        ).also { viewModel = it }
        val state = ViewModelTestAccess.state(vm)
        state.value = state.value.copy(
            mode = CaptureMode.PHOTO,
            cameraReady = true,
            photoSessionOutputs = PhotoSessionOutputs(processed = true, raw = false),
            stillCaptureAdmissionAvailable = true,
        )
        assertTrue(vm.state.value.primaryShutterEnabled)

        // A URI with no pending allocation is the canonical UNRESOLVED answer of discardPendingOutput.
        ViewModelTestAccess.invoke(
            vm,
            "deleteLateCaptureOutput",
            Uri.parse("content://media/external/images/media/4242"),
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((dispatcher.activeTaskCount() > 0 || dispatcher.queuedTaskCount() > 0) &&
            System.nanoTime() < deadline
        ) {
            Thread.yield()
        }
        // The worker posts its verdict back to main after it leaves the executor's active count.
        while (vm.state.value.status?.message != CameraStatusMessage.COULD_NOT_DELETE_FILE &&
            System.nanoTime() < deadline
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }

        assertEquals(CameraStatusMessage.COULD_NOT_DELETE_FILE, vm.state.value.status?.message)
        assertTrue(
            "the ViewModel must not write the engine-owned admission bit",
            vm.state.value.stillCaptureAdmissionAvailable,
        )
        assertTrue(vm.state.value.primaryShutterEnabled)
    }
}
