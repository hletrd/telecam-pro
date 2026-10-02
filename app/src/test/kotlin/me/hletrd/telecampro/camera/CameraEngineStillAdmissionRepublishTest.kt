package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.CaptureFamilyMedia
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PR6-1 (AGG6-3): the producer-terminal edge republishes still admission from a read taken AFTER
 * its mark. The racing marker worker can leave the delivered value `false` while the live owner
 * already says `true`; a gate read before the mark then saw "admitting" and stayed silent forever.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineStillAdmissionRepublishTest {
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
    fun `a producer-terminal edge repairs a stale closed publication`() {
        val engine = CameraEngine(app).also(engines::add)
        val delivered = mutableListOf<Boolean>()
        engine.onStillCaptureAdmissionChanged = { delivered += it }
        val owner = getField(engine, "retainedStillDeletionOwner") as RetainedStillDeletionOwner<*>
        owner.registerCaptureFamily(1, family(1))
        owner.registerCaptureFamily(2, family(2))
        // The marker worker closes admission for the live capture 1 and delivers `false`.
        owner.markCaptureDeletedInMemory(1)
        owner.completeDeletionDurability(1, durable = false)
        invoke(engine, "publishProcessStillAdmission")
        // Capture 1's terminal mark landed between the old gate's read and the worker's publish:
        // the live owner reopens with nothing republished.
        owner.markCaptureProducersTerminal(1)

        invoke(engine, "markStillProducersTerminal", 2)

        assertEquals(true, delivered.last())
    }

    private fun family(id: Int) = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_300_000L + id, id.toLong())

    private fun invoke(target: Any, name: String, vararg args: Any) {
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }
            .invoke(target, *args)
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
