package me.hletrd.telecampro

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObscuredTouchPolicyTest {
    @Test
    fun `ordinary input including unrelated flags remains admitted`() {
        assertTrue(touchEventIsUnobscured(0))
        assertTrue(touchEventIsUnobscured(0x4000))
    }

    @Test
    fun `full and partial overlay flags are rejected independently and together`() {
        assertFalse(touchEventIsUnobscured(MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        assertFalse(touchEventIsUnobscured(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
        assertFalse(
            touchEventIsUnobscured(
                MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                    MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
            ),
        )
    }
}
