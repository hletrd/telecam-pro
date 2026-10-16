package me.hletrd.telecampro.ui.review

import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaReviewPlatformGestureTest {
    @Test
    fun `production double tap distance uses the platform double tap threshold`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val platform = ViewConfiguration.get(context)

        assertEquals(platform.scaledDoubleTapSlop.toFloat(), reviewDoubleTapSlop(context), 0f)
        assertTrue(reviewDoubleTapSlop(context) > platform.scaledTouchSlop)
    }
}
