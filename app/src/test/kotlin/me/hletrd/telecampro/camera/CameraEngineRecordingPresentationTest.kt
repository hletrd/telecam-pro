package me.hletrd.telecampro.camera

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AGG5-39 / TE6-7: the Engine's recording terminal presentation goes through publishOrStale. An
 * in-REC snapshot takes a newer capture id while the clip rolls; the clip's terminal must still
 * reach review and still announce a failed or retained take.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineRecordingPresentationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engines = mutableListOf<CameraEngine>()

    init {
        RobolectricEglSentinels.ensure()
    }

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.release() } }
    }

    @Test
    fun `a stale failed clip still announces its failure`() {
        val (statuses, saved) = present(RecordingStorageTerminalDisposition.FAILED, uri = null)

        assertEquals(listOf(CameraStatusMessage.VIDEO_SAVE_FAILED), statuses)
        assertEquals(emptyList<Pair<Uri, Int>>(), saved)
    }

    @Test
    fun `a stale saved clip reaches review without claiming saved`() {
        val uri = Uri.parse("content://video/10")
        val (statuses, saved) = present(RecordingStorageTerminalDisposition.SAVED, uri)

        assertEquals(emptyList<CameraStatusMessage>(), statuses)
        assertEquals(listOf(uri to 10), saved)
    }

    private fun present(
        disposition: RecordingStorageTerminalDisposition,
        uri: Uri?,
    ): Pair<List<CameraStatusMessage>, List<Pair<Uri, Int>>> {
        val engine = CameraEngine(app).also(engines::add)
        val statuses = mutableListOf<CameraStatusMessage>()
        val saved = mutableListOf<Pair<Uri, Int>>()
        engine.onStatus = { it?.message?.let(statuses::add) }
        engine.onMediaSaved = { savedUri, id -> saved += savedUri to id }
        val presentation = CameraEngine::class.java.getDeclaredField("recordingStoragePresentation")
            .apply { isAccessible = true }.get(engine) as RecordingStoragePresentationReducer<*>
        presentation.observeCapture(10) // the clip
        presentation.observeCapture(11) // an in-REC snapshot taken while it rolled

        CameraEngine::class.java.declaredMethods.single { it.name == "presentRecordingStorageResult" }
            .apply { isAccessible = true }
            .invoke(engine, RecordingStorageTerminalResult(10, uri, disposition))

        return statuses to saved
    }
}
