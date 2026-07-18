package com.hletrd.findx9tele.camera

/**
 * Serializes Camera2 callback dispatch against HandlerThread shutdown.
 *
 * OPPO's legacy message queue can throw (and log a dead-thread warning) when a callback executor
 * calls Handler.post after quitSafely. Holding one small monitor across admission/post and across
 * the close+quit transition gives a strict order: an admitted callback is queued before teardown,
 * or a late callback is rejected without touching the dead queue and can run its cleanup inline.
 */
internal class CameraCallbackDispatchGate {
    private var closing = false

    @Synchronized
    fun dispatch(post: () -> Boolean): Boolean {
        if (closing) return false
        return runCatching(post).getOrDefault(false)
    }

    @Synchronized
    fun beginClose(closeQueue: () -> Unit) {
        if (closing) return
        closing = true
        closeQueue()
    }
}
