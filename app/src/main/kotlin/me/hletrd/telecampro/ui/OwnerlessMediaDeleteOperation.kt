package me.hletrd.telecampro.ui

import android.app.PendingIntent
import android.net.Uri
import java.util.concurrent.atomic.AtomicReference

internal const val OWNERLESS_MEDIA_DELETE_PROVIDER_TIMEOUT_MS = 5_000L

/** Exact identity shared by request creation, the Activity launcher, and its result. */
internal data class OwnerlessMediaDeleteRequest(
    val uri: Uri,
    val generation: Long,
)

/** One request that is ready for the current Activity's registered IntentSender launcher. */
internal data class OwnerlessMediaDeleteLaunch(
    val request: OwnerlessMediaDeleteRequest,
    val pendingIntent: PendingIntent,
)

/** Terminal outcomes of the provider IPC that constructs MediaStore's delete request. */
internal sealed interface OwnerlessMediaDeleteRequestCreation {
    data class Ready(val pendingIntent: PendingIntent) : OwnerlessMediaDeleteRequestCreation
    data object Failed : OwnerlessMediaDeleteRequestCreation
    data object TimedOut : OwnerlessMediaDeleteRequestCreation
    data object Rejected : OwnerlessMediaDeleteRequestCreation
}

/**
 * Tiny first-wins terminal that drops its callback as soon as any result owns the operation.
 *
 * A provider task may remain blocked in Binder after its UI deadline. Keeping the delivery callback
 * only in this atomic slot means the blocked Runnable retains neither its Activity nor ViewModel
 * after timeout; its eventual late result is an exact no-op.
 */
internal class FirstWinsTerminal<T>(deliver: (T) -> Unit) {
    private val delivery = AtomicReference<(T) -> Unit>(deliver)

    fun complete(value: T): Boolean = delivery.getAndSet(null)?.let { callback ->
        callback(value)
        true
    } ?: false

    fun isPending(): Boolean = delivery.get() != null
}

/** Arms a timeout before provider dispatch; scheduler rejection terminalizes immediately. */
internal fun <T> armFirstWinsTimeout(
    terminal: FirstWinsTerminal<T>,
    timeoutValue: T,
    timeoutMs: Long,
    postDelayed: (Runnable, Long) -> Boolean,
): Boolean {
    require(timeoutMs > 0L)
    val accepted = runCatching {
        postDelayed(Runnable { terminal.complete(timeoutValue) }, timeoutMs)
    }.getOrDefault(false)
    if (!accepted) terminal.complete(timeoutValue)
    return accepted
}
