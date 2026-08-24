package me.hletrd.telecampro

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityTouchDispatchTest {
    @Test
    fun `real Activity dispatch leaves ordinary touch behavior unchanged`() {
        RobolectricEglSentinels.ensure()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            var sensitiveActions = 0
            installActionTarget(activity) { sensitiveActions += 1 }

            assertTrue(dispatchTap(activity, flags = 0, toolType = MotionEvent.TOOL_TYPE_FINGER))
            assertTrue(dispatchTap(activity, flags = 0, toolType = MotionEvent.TOOL_TYPE_STYLUS))
            assertTrue(dispatchTwoPointerGesture(activity))

            assertEquals(3, sensitiveActions)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `real Activity dispatch keeps sensitive actions inert for both overlay flags`() {
        RobolectricEglSentinels.ensure()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            var sensitiveActions = 0
            installActionTarget(activity) { sensitiveActions += 1 }

            assertFalse(
                dispatchTap(
                    activity,
                    flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED,
                    toolType = MotionEvent.TOOL_TYPE_FINGER,
                ),
            )
            assertFalse(
                dispatchTap(
                    activity,
                    flags = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
                    toolType = MotionEvent.TOOL_TYPE_FINGER,
                ),
            )

            assertEquals(0, sensitiveActions)
        } finally {
            controller.destroy()
        }
    }

    private fun installActionTarget(activity: MainActivity, action: () -> Unit) {
        val target = View(activity).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) action()
                true
            }
        }
        activity.setContentView(target)
        val exact100 = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        activity.window.decorView.measure(exact100, exact100)
        activity.window.decorView.layout(0, 0, 100, 100)
    }

    private fun dispatchTap(activity: MainActivity, flags: Int, toolType: Int): Boolean {
        val down = event(MotionEvent.ACTION_DOWN, flags, toolType)
        val up = event(MotionEvent.ACTION_UP, flags, toolType)
        return try {
            val downHandled = activity.dispatchTouchEvent(down)
            val upHandled = activity.dispatchTouchEvent(up)
            downHandled && upHandled
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun dispatchTwoPointerGesture(activity: MainActivity): Boolean {
        val events = listOf(
            event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER),
            event(
                MotionEvent.ACTION_POINTER_DOWN or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                0,
                MotionEvent.TOOL_TYPE_FINGER,
                pointerCount = 2,
            ),
            event(
                MotionEvent.ACTION_POINTER_UP or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                0,
                MotionEvent.TOOL_TYPE_FINGER,
                pointerCount = 2,
            ),
            event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER),
        )
        return try {
            events.all(activity::dispatchTouchEvent)
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    private fun event(
        action: Int,
        flags: Int,
        toolType: Int,
        pointerCount: Int = 1,
    ): MotionEvent {
        val properties = Array(pointerCount) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                this.toolType = toolType
            }
        }
        val coordinates = Array(pointerCount) { index ->
            MotionEvent.PointerCoords().apply {
                x = 10f + index
                y = 10f
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            1L,
            2L,
            action,
            pointerCount,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            flags,
        )
    }
}
