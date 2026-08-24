package me.hletrd.telecampro

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.lang.reflect.Proxy
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.CameraScreen
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import me.hletrd.telecampro.ui.controls.CameraSlider
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config

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
            idleMain()
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
            idleMain()
        }
    }

    @Test
    fun `either obscuration edge sends one clean cancel and next gesture starts fresh`() {
        listOf(
            MotionEvent.FLAG_WINDOW_IS_OBSCURED,
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
        ).forEach { obscurationFlag ->
            RobolectricEglSentinels.ensure()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            try {
                val childEvents = mutableListOf<MotionEvent>()
                installTouchTarget(activity) { childEvents += MotionEvent.obtain(it) }
                val cleanDown = event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER)
                val obscuredMove = event(
                    MotionEvent.ACTION_MOVE,
                    obscurationFlag,
                    MotionEvent.TOOL_TYPE_FINGER,
                )
                val taintedCleanUp = event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER)
                val nextDown = event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER)
                val nextUp = event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER)
                try {
                    assertTrue(activity.dispatchTouchEvent(cleanDown))
                    assertFalse(activity.dispatchTouchEvent(obscuredMove))
                    assertFalse(activity.dispatchTouchEvent(taintedCleanUp))
                    assertTrue(activity.dispatchTouchEvent(nextDown))
                    assertTrue(activity.dispatchTouchEvent(nextUp))
                } finally {
                    listOf(cleanDown, obscuredMove, taintedCleanUp, nextDown, nextUp)
                        .forEach(MotionEvent::recycle)
                }

                assertEquals(
                    listOf(
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_CANCEL,
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_UP,
                    ),
                    childEvents.map { it.actionMasked },
                )
                val cancel = childEvents.single { it.actionMasked == MotionEvent.ACTION_CANCEL }
                assertEquals(0, cancel.flags and obscurationFlag)
                assertEquals(1, cancel.pointerCount)
                assertEquals(10f, cancel.getX(0), 0f)
                assertEquals(InputDevice.SOURCE_TOUCHSCREEN, cancel.source)
                childEvents.forEach(MotionEvent::recycle)
            } finally {
                controller.destroy()
                idleMain()
            }
        }
    }

    @Test
    @Config(qualifiers = "w480dp-h1056dp-xxhdpi")
    fun `production viewfinder tap and pinch recover after either obscuration edge`() {
        listOf(
            MotionEvent.FLAG_WINDOW_IS_OBSCURED,
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
        ).forEach { obscurationFlag ->
            RobolectricEglSentinels.ensure()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            var tapFocuses = 0
            var pinchTicks = 0
            var pinchEnds = 0
            lateinit var preview: View
            val actions = Proxy.newProxyInstance(
                CameraActions::class.java.classLoader,
                arrayOf(CameraActions::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "onTapFocus" -> tapFocuses++
                    "onPinchZoom" -> pinchTicks++
                    "onPinchEnd" -> pinchEnds++
                }
                if (method.returnType == java.lang.Boolean.TYPE) false else null
            } as CameraActions
            try {
                activity.setContent {
                    TeleCamProTheme {
                        CameraScreen(
                            state = CameraUiState(),
                            actions = actions,
                            previewViewFactory = { View(it).also { view -> preview = view } },
                            windowRotationOverrideDeg = 0,
                        )
                    }
                }
                layoutActivity(activity)
                val location = IntArray(2).also(preview::getLocationOnScreen)
                val cx = location[0] + preview.width / 2f
                val cy = location[1] + preview.height / 2f
                assertTrue(preview.width > 0 && preview.height > 0)

                dispatchSequence(
                    activity,
                    event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(cx to cy)),
                    event(MotionEvent.ACTION_MOVE, obscurationFlag, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(cx to cy)),
                    event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(cx to cy)),
                )
                dispatchSequence(
                    activity,
                    event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(cx to cy)),
                    event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(cx to cy)),
                )
                idleMain()
                assertEquals(1, tapFocuses)

                val left = cx - preview.width * 0.12f
                val right = cx + preview.width * 0.12f
                dispatchSequence(
                    activity,
                    event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(left to cy)),
                    event(
                        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        0,
                        MotionEvent.TOOL_TYPE_FINGER,
                        pointerCount = 2,
                        positions = listOf(left to cy, right to cy),
                    ),
                    event(
                        MotionEvent.ACTION_MOVE,
                        0,
                        MotionEvent.TOOL_TYPE_FINGER,
                        pointerCount = 2,
                        positions = listOf((left - 30f) to cy, (right + 30f) to cy),
                    ),
                    event(
                        MotionEvent.ACTION_MOVE,
                        obscurationFlag,
                        MotionEvent.TOOL_TYPE_FINGER,
                        pointerCount = 2,
                        positions = listOf((left - 40f) to cy, (right + 40f) to cy),
                    ),
                    event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(left to cy)),
                )
                val ticksAfterCancel = pinchTicks
                dispatchSequence(
                    activity,
                    event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(left to cy)),
                    event(
                        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        0,
                        MotionEvent.TOOL_TYPE_FINGER,
                        pointerCount = 2,
                        positions = listOf(left to cy, right to cy),
                    ),
                    event(
                        MotionEvent.ACTION_MOVE,
                        0,
                        MotionEvent.TOOL_TYPE_FINGER,
                        pointerCount = 2,
                        positions = listOf((left - 40f) to cy, (right + 40f) to cy),
                    ),
                    event(
                        MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        0,
                        MotionEvent.TOOL_TYPE_FINGER,
                        pointerCount = 2,
                        positions = listOf((left - 40f) to cy, (right + 40f) to cy),
                    ),
                    event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(left to cy)),
                )
                idleMain()
                assertTrue(pinchTicks > ticksAfterCancel)
                assertTrue(pinchEnds >= 1)
            } finally {
                controller.destroy()
                idleMain()
            }
        }
    }

    @Test
    @Config(qualifiers = "w480dp-h1056dp-xxhdpi")
    fun `production slider drag recovers after full and partial obscuration`() {
        listOf(
            MotionEvent.FLAG_WINDOW_IS_OBSCURED,
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
        ).forEach { obscurationFlag ->
            RobolectricEglSentinels.ensure()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            val fractions = mutableListOf<Float>()
            try {
                activity.setContent {
                    TeleCamProTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CameraSlider(
                                fraction = 0.5f,
                                onFraction = fractions::add,
                                enabled = true,
                                semanticLabel = "Production ruler",
                                valueDescription = "50 percent",
                            )
                        }
                    }
                }
                layoutActivity(activity)
                val decor = activity.window.decorView
                val cy = decor.height / 2f
                val left = decor.width * 0.25f
                val right = decor.width * 0.75f
                dispatchSequence(
                    activity,
                    event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(left to cy)),
                    event(MotionEvent.ACTION_MOVE, obscurationFlag, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(right to cy)),
                    event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(right to cy)),
                )
                val emissionsAfterCancel = fractions.size
                dispatchSequence(
                    activity,
                    event(MotionEvent.ACTION_DOWN, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(left to cy)),
                    event(MotionEvent.ACTION_MOVE, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(right to cy)),
                    event(MotionEvent.ACTION_UP, 0, MotionEvent.TOOL_TYPE_FINGER, positions = listOf(right to cy)),
                )
                idleMain()
                assertTrue(fractions.size > emissionsAfterCancel)
                assertTrue(fractions.last() > 0.5f)
            } finally {
                controller.destroy()
                idleMain()
            }
        }
    }

    private fun installActionTarget(activity: MainActivity, action: () -> Unit) {
        installTouchTarget(activity) { event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) action()
        }
    }

    private fun installTouchTarget(activity: MainActivity, onEvent: (MotionEvent) -> Unit) {
        val target = View(activity).apply {
            setOnTouchListener { _, event -> onEvent(event); true }
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
        positions: List<Pair<Float, Float>> = List(pointerCount) { index ->
            (10f + index) to 10f
        },
    ): MotionEvent {
        require(positions.size == pointerCount)
        val properties = Array(pointerCount) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                this.toolType = toolType
            }
        }
        val coordinates = Array(pointerCount) { index ->
            MotionEvent.PointerCoords().apply {
                x = positions[index].first
                y = positions[index].second
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

    private fun dispatchSequence(activity: MainActivity, vararg events: MotionEvent) {
        try {
            events.forEach { event ->
                activity.dispatchTouchEvent(event)
                idleMain()
            }
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun layoutActivity(activity: MainActivity) {
        idleMain()
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        idleMain()
    }
}
