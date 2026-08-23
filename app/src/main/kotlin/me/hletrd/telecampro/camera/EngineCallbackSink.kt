package me.hletrd.telecampro.camera

import java.util.EnumMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Every Engine -> UI callback participates in one atomic retirement/drain boundary. */
internal enum class EngineCallbackKey {
    CAMERA_READY, OPTICS_ROLLBACK, AF_INDICATION, TAP_FOCUS, STATUS, CAPS_READY,
    CAMERA_ROUTES, LENS_INVENTORY, VIDEO_SIZE, ENCODER_SIZE, PREVIEW_ASPECT, ANALYSIS,
    AUDIO_LEVEL, CAMERA_POLICY, TIMELAPSE, AUDIO_ROUTE, STANDBY_AUDIO_AVAILABLE,
    STANDBY_AUDIO_UNAVAILABLE, RECORDING_TERMINATED, RECORDING_STARTED, EXPOSURE_INFO,
    FOCUS_DISTANCE, MEDIA_SAVED, RAW_SAVED, CAPTURE_FAMILY, STILL_ADMISSION,
}

/**
 * A getter returns a guarded function rather than the installed function itself. A caller that
 * fetched the property immediately before [closeAndDrain] therefore still rechecks terminal truth
 * when it invokes. Calls admitted before close hold the read lease through the user callback;
 * close takes the write lease, refuses future calls atomically, and drains admitted calls.
 */
internal class EngineCallbackSink {
    private val lock = ReentrantReadWriteLock(true)
    private val callbacks = EnumMap<EngineCallbackKey, Any>(EngineCallbackKey::class.java)
    private var closed = false

    fun <T : Any> install(key: EngineCallbackKey, callback: T?) = lock.write {
        if (closed) return@write
        if (callback == null) callbacks.remove(key) else callbacks[key] = callback
    }

    fun callbackCount(): Int = lock.read { callbacks.size }

    fun closeAndDrain() = lock.write {
        closed = true
        callbacks.clear()
    }

    fun function0(key: EngineCallbackKey): (() -> Unit)? = guarded<() -> Unit>(key) {
        dispatch<() -> Unit>(key) { callback -> callback() }
    }

    fun <A> function1(key: EngineCallbackKey): ((A) -> Unit)? = guarded<(A) -> Unit>(key) { a ->
        dispatch<(A) -> Unit>(key) { callback -> callback(a) }
    }

    fun <A, B> function2(key: EngineCallbackKey): ((A, B) -> Unit)? = guarded<(A, B) -> Unit>(key) { a, b ->
        dispatch<(A, B) -> Unit>(key) { callback -> callback(a, b) }
    }

    fun <A, B, C> function3(key: EngineCallbackKey): ((A, B, C) -> Unit)? =
        guarded<(A, B, C) -> Unit>(key) { a, b, c ->
            dispatch<(A, B, C) -> Unit>(key) { callback -> callback(a, b, c) }
        }

    fun <A, B, C, D> function4(key: EngineCallbackKey): ((A, B, C, D) -> Unit)? =
        guarded<(A, B, C, D) -> Unit>(key) { a, b, c, d ->
            dispatch<(A, B, C, D) -> Unit>(key) { callback -> callback(a, b, c, d) }
        }

    fun <A, B, C, D, E, F, G, H, I, J> function10(
        key: EngineCallbackKey,
    ): ((A, B, C, D, E, F, G, H, I, J) -> Unit)? =
        guarded<(A, B, C, D, E, F, G, H, I, J) -> Unit>(key) { a, b, c, d, e, f, g, h, i, j ->
            dispatch<(A, B, C, D, E, F, G, H, I, J) -> Unit>(key) { callback ->
                callback(a, b, c, d, e, f, g, h, i, j)
            }
        }

    private fun <T : Any> guarded(key: EngineCallbackKey, wrapper: T): T? = lock.read {
        if (closed || key !in callbacks) null else wrapper
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> dispatch(key: EngineCallbackKey, invoke: (T) -> Unit) = lock.read {
        if (closed) return@read
        val callback = callbacks[key] as T? ?: return@read
        invoke(callback)
    }
}
