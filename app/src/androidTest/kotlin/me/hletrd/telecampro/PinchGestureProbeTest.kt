package me.hletrd.telecampro

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.hletrd.telecampro.ui.CameraViewModel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives a REAL two-finger pinch so the mid-gesture submit policy can be measured on device.
 *
 * Why this exists: `adb shell input` is single-pointer (`input motionevent` takes one x/y), and
 * `sendevent` on the touchpanel is refused by SELinux even though shell sits in the `input` group —
 * both verified on this device. Instrumentation is the one route left: `UiAutomation` holds
 * INJECT_EVENTS, so it can post multi-pointer `MotionEvent`s into our own activity.
 *
 * Like [me.hletrd.telecampro.video.EncoderProfileLevelProbeTest] this is a PROBE: it measures and logs under
 * [TAG] and must never fail the build. The thing being measured is a HAL stall, so an assertion here
 * would encode a timing threshold as a correctness claim.
 *
 * Read the result by clearing logcat, running this class, and grepping `PinchProbe` alongside
 * `GlPipeline`'s FrameGap and `CameraController`'s ZoomTrace. The expectation after the cycle-9
 * change: `ZoomTrace: submit` lines appear only at the gesture EDGES, and the FrameGaps that used to
 * pepper the gesture disappear from between them.
 */
@RunWith(AndroidJUnit4::class)
class PinchGestureProbeTest {

    @Before
    fun wakeDevice() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test
    fun pinchProbe() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            if (!awaitCameraReady(vm)) {
                Log.i(TAG, "VERDICT: camera never became ready; nothing measured")
                return
            }
            var w = 0
            var h = 0
            scenario.onActivity { a ->
                w = a.window.decorView.width
                h = a.window.decorView.height
            }
            if (w <= 0 || h <= 0) {
                Log.i(TAG, "VERDICT: no decor size; nothing measured")
                return
            }
            // Vertically centred in the viewfinder, well clear of the top chrome and the bottom
            // rail so the gesture lands on the preview and not on a control.
            val cx = w / 2f
            val cy = h * 0.45f

            Log.i(TAG, "BEGIN zoom-in pinch (fingers apart) zoom=${vm.state.value.controls.zoomRatio}")
            pinch(cx, cy, fromGap = 120f, toGap = 620f, steps = 40, stepMs = 16L)
            SystemClock.sleep(1_200)
            Log.i(TAG, "END zoom-in pinch zoom=${vm.state.value.controls.zoomRatio}")

            // The accepted risk of suppressing mid-gesture submits: zooming OUT past the 1.2x wide
            // aim has no delivered field left to show. Drive it deliberately and record where it
            // lands, so the degradation is described by a measurement instead of a guess.
            Log.i(TAG, "BEGIN zoom-out pinch (fingers together) zoom=${vm.state.value.controls.zoomRatio}")
            pinch(cx, cy, fromGap = 620f, toGap = 120f, steps = 40, stepMs = 16L)
            SystemClock.sleep(1_200)
            Log.i(TAG, "END zoom-out pinch zoom=${vm.state.value.controls.zoomRatio}")

            Log.i(TAG, "VERDICT: pinch injected; correlate ZoomTrace/FrameGap over the BEGIN..END spans")
        }
    }

    /** Injects a two-pointer gesture whose finger separation runs [fromGap] → [toGap]. */
    private fun pinch(cx: Float, cy: Float, fromGap: Float, toGap: Float, steps: Int, stepMs: Long) {
        val down = SystemClock.uptimeMillis()
        var gap = fromGap
        sendPointers(down, down, MotionEvent.ACTION_DOWN, cx, cy, gap, pointers = 1)
        sendPointers(
            down, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            cx, cy, gap, pointers = 2,
        )
        for (i in 1..steps) {
            gap = fromGap + (toGap - fromGap) * (i.toFloat() / steps)
            sendPointers(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, cx, cy, gap, pointers = 2)
            SystemClock.sleep(stepMs)
        }
        sendPointers(
            down, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            cx, cy, gap, pointers = 2,
        )
        sendPointers(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, cx, cy, gap, pointers = 1)
    }

    private fun sendPointers(
        downTime: Long,
        eventTime: Long,
        action: Int,
        cx: Float,
        cy: Float,
        gap: Float,
        pointers: Int,
    ) {
        val props = Array(pointers) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers) { i ->
            MotionEvent.PointerCoords().apply {
                // Pointer 0 above centre, pointer 1 below: a vertical spread keeps both fingers
                // inside the portrait preview at the widest separation used here.
                x = cx
                y = if (i == 0) cy - gap / 2f else cy + gap / 2f
                pressure = 1f
                size = 1f
            }
        }
        val ev = MotionEvent.obtain(
            downTime, eventTime, action, pointers, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(ev, true)
        ev.recycle()
    }

    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CameraViewModel {
        lateinit var vm: CameraViewModel
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[CameraViewModel::class.java]
        }
        return vm
    }

    private fun awaitCameraReady(vm: CameraViewModel): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (vm.state.value.cameraReady) return true
            SystemClock.sleep(200L)
        }
        return false
    }

    private fun shell(command: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private companion object {
        const val TAG = "PinchProbe"
    }
}
