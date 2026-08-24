package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicBoolean
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraEngineTimelapseIntervalTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `running timelapse rereads an edited interval at every schedule boundary`() {
        RobolectricEglSentinels.ensure()
        val scheduler = ManualTimelapseScheduler()
        val engine = CameraEngine(
            app,
            timelapseOverrides = TimelapseEngineOverrides(scheduler::schedule),
        )
        try {
            engine.setDriveMode(DriveMode.TIMELAPSE)
            engine.setIntervalSec(1)
            CameraEngine::class.java.getDeclaredMethod("startTimelapse", PhotoFormats::class.java)
                .apply { isAccessible = true }
                .invoke(engine, PhotoFormats())
            assertEquals(listOf(0L), scheduler.delays())

            engine.setIntervalSec(30)
            scheduler.fire(0)
            assertEquals(listOf(0L, 30L), scheduler.delays())

            engine.setIntervalSec(7)
            scheduler.fire(1)
            assertEquals(listOf(0L, 30L, 7L), scheduler.delays())

            engine.stopTimelapse()
            assertTrue(scheduler.tasks.last().cancelled.get())
        } finally {
            engine.release()
        }
    }

    private class ManualTimelapseScheduler {
        val tasks = mutableListOf<Task>()

        fun schedule(delaySeconds: Long, action: () -> Unit): TimelapseCancellation {
            val task = Task(delaySeconds, action)
            tasks += task
            return TimelapseCancellation { task.cancelled.set(true) }
        }

        fun delays(): List<Long> = tasks.map(Task::delaySeconds)

        fun fire(index: Int) {
            tasks[index].takeUnless { it.cancelled.get() }?.action?.invoke()
        }
    }

    private data class Task(
        val delaySeconds: Long,
        val action: () -> Unit,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )
}
