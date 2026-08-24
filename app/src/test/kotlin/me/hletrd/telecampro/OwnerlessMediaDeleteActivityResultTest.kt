package me.hletrd.telecampro

import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import me.hletrd.telecampro.ui.OwnerlessMediaDeleteConsentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerlessMediaDeleteActivityResultTest {
    private val convertedAction =
        ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST

    @Test fun `OK is approval even when result data is absent`() {
        assertEquals(
            OwnerlessMediaDeleteConsentResult.APPROVED,
            classifyOwnerlessMediaDeleteActivityResult(Activity.RESULT_OK, null, false),
        )
    }

    @Test fun `ordinary canceled result remains genuine cancellation`() {
        assertEquals(
            OwnerlessMediaDeleteConsentResult.CANCELED,
            classifyOwnerlessMediaDeleteActivityResult(Activity.RESULT_CANCELED, null, false),
        )
        assertFalse(convertedSendIntentExceptionMarker(convertedAction, false))
        assertFalse(convertedSendIntentExceptionMarker("lookalike", true))
    }

    @Test fun `AndroidX action plus exception extra is launch failure`() {
        assertTrue(convertedSendIntentExceptionMarker(convertedAction, true))
        assertEquals(
            OwnerlessMediaDeleteConsentResult.LAUNCH_FAILED,
            classifyOwnerlessMediaDeleteActivityResult(
                Activity.RESULT_CANCELED,
                convertedAction,
                true,
            ),
        )
    }
}

