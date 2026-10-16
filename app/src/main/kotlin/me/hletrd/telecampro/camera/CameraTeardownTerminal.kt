package me.hletrd.telecampro.camera

import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Terminal proof returned by [CameraController.close]. */
internal enum class CameraControllerCloseResult {
    /** The exact CameraDevice reported [android.hardware.camera2.CameraDevice.StateCallback.onClosed]. */
    STRICTLY_RELEASED,

    /** Release was not proved before the deadline; native acquisition is refused until restart. */
    QUARANTINED,

    /** Teardown was initiated on the camera callback lane; only an off-lane owner may await it. */
    PENDING,
}

/**
 * Exactly-once classification for one CameraController teardown.
 *
 * A timeout is not a weak success: it first closes process native admission through [quarantine],
 * then publishes [CameraControllerCloseResult.QUARANTINED]. A late onClosed callback cannot turn
 * that terminal back into a strict release or authorize a replacement graph.
 */
internal class CameraTeardownTerminal(
    private val onQuarantine: () -> Unit,
) {
    private val completed = CountDownLatch(1)

    @Volatile
    private var result: CameraControllerCloseResult? = null

    fun strictlyReleased(): CameraControllerCloseResult = classify(
        CameraControllerCloseResult.STRICTLY_RELEASED,
    )

    fun quarantine(): CameraControllerCloseResult = classify(
        CameraControllerCloseResult.QUARANTINED,
    )

    fun await(timeout: Long, unit: TimeUnit): CameraControllerCloseResult {
        val finished = try {
            completed.await(timeout.coerceAtLeast(0L), unit)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return if (finished) {
            checkNotNull(result)
        } else {
            quarantine()
        }
    }

    fun currentOrPending(): CameraControllerCloseResult = result ?: CameraControllerCloseResult.PENDING

    private fun classify(candidate: CameraControllerCloseResult): CameraControllerCloseResult =
        synchronized(this) {
            result?.let { return@synchronized it }
            if (candidate == CameraControllerCloseResult.QUARANTINED) onQuarantine()
            result = candidate
            completed.countDown()
            candidate
        }
}

/**
 * Exact-identity CameraDevice retirement owner used by the production StateCallback.
 *
 * [armClose] arms ownership before invoking a platform `close()`. A returned close call is not
 * terminal proof: only [onClosed] for the first callback-supplied identity may publish strict
 * release. Every callback-supplied handle that needs retirement is closed at most once, including
 * error/disconnect-before-open and late-callback races.
 */
internal class ExactCameraDeviceCloseOwner<D : Any>(
    private val terminal: CameraTeardownTerminal,
    private val closeDevice: (D) -> Unit,
) {
    private val closeIssued = IdentityHashMap<D, Unit>()
    private var expectedDevice: D? = null
    private var closing = false
    private var noDeviceWillArrive = false

    /** Claims callback identity before any observer effect; [retire] requests its one close. */
    fun claim(device: D, retire: Boolean = false): Boolean {
        val decision = synchronized(this) {
            if (expectedDevice == null && !noDeviceWillArrive) expectedDevice = device
            val expected = expectedDevice === device
            val shouldClose = (closing || retire) && !closeIssued.containsKey(device)
            if (shouldClose) closeIssued[device] = Unit
            expected to shouldClose
        }
        if (decision.second) runCatching { closeDevice(device) }
        return decision.first
    }

    /**
     * Arms close ownership before any platform close call can run. [installedDevice] is retired by
     * the ordered teardown; an identity claimed in the onOpened/close race is retired immediately.
     */
    fun armClose(installedDevice: D?) {
        val racedDevice = synchronized(this) {
            closing = true
            expectedDevice?.takeIf { it !== installedDevice && !closeIssued.containsKey(it) }
        }
        racedDevice?.let(::retire)
        publishNoDeviceReleaseIfProved()
    }

    /** Closes one callback-supplied identity at most once. */
    fun retire(device: D) {
        claim(device, retire = true)
    }

    /** Exact expected-device proof; wrong, duplicate, and late post-quarantine callbacks are inert. */
    fun onClosed(device: D): CameraControllerCloseResult? {
        val exact = synchronized(this) { expectedDevice === device }
        return if (exact) terminal.strictlyReleased() else null
    }

    /** Proves a synchronous/refused open created no CameraDevice that could still own Camera2. */
    fun proveNoDeviceWillArrive() {
        synchronized(this) {
            if (expectedDevice == null) noDeviceWillArrive = true
        }
        publishNoDeviceReleaseIfProved()
    }

    private fun publishNoDeviceReleaseIfProved() {
        val proved = synchronized(this) { closing && noDeviceWillArrive && expectedDevice == null }
        if (proved) terminal.strictlyReleased()
    }
}

/** Production queue seam: initiate teardown now, but leave terminal waiting to a non-camera lane. */
internal fun dispatchCameraTeardown(
    onCameraThread: Boolean,
    beginQueueClose: (closeQueue: () -> Unit) -> Unit,
    postTeardown: (Runnable) -> Boolean,
    closeQueue: () -> Unit,
    teardown: Runnable,
    onQueueFailure: (Throwable) -> Unit,
) {
    var teardownPosted = false
    runCatching {
        beginQueueClose {
            if (!onCameraThread) {
                teardownPosted = runCatching { postTeardown(teardown) }.getOrDefault(false)
            }
            closeQueue()
        }
    }.onFailure(onQueueFailure)
    if (onCameraThread || !teardownPosted) teardown.run()
}

/** A local cleanup failure is logged, then exact-device callback proof remains authoritative. */
internal fun runCameraTeardownCleanup(
    cleanup: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    runCatching(cleanup).onFailure(onFailure)
}

/** Only a proved release (or no outgoing controller) can authorize replacement acquisition. */
internal fun cameraReplacementMayAcquire(result: CameraControllerCloseResult?): Boolean =
    result == null || result == CameraControllerCloseResult.STRICTLY_RELEASED
