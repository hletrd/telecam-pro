package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class FnMenuGlyphRotationTest {

    @Test
    fun compactFnGlyphFollowsExactAnimatedOverlayAngle() {
        listOf(-90f, 0f, 90f, 270f, 360f, 450f).forEach { overlayRotation ->
            assertEquals(
                "Fn must preserve the shared unwrapped +deviceOrientation angle",
                overlayRotation,
                fnMenuGlyphRotation(overlayRotation),
                0f,
            )
        }
    }
}
