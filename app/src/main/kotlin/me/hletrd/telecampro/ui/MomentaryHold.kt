package me.hletrd.telecampro.ui

/**
 * Press-and-hold ownership of one boolean setting by a MOMENTARY hardware binding (AEL, PUNCH_IN).
 *
 * The bindings used to be "set to the key state": the release edge always wrote `false`, through
 * the operator's own persistent toggle (markChanged + the 500 ms settings save). A latched AE lock
 * was unlocked by one tap of a key bound to AEL, and a sheet-enabled punch-in was switched off AND
 * persisted off by one light press (AGG4-74). A hold now snapshots the prior value on the press,
 * restores exactly that on the release, and never counts as a setting change: while held,
 * [persistedValue] answers the operator's value so a save landing mid-hold writes THAT.
 *
 * The hold is OWNED by the physical keys pressing it (AGG6-29): two keys can be bound to the same
 * action, and the hold ends only when the LAST of them lets go — or, on a rebind, only when the
 * rebound key is one of them. Ending the action-wide hold whenever ANY key left AEL/PUNCH_IN ended
 * a half-press hold still under the operator's finger because the volume key was rebound.
 *
 * Main-thread confined, like every ViewModel input path.
 */
internal class MomentaryHold {
    private var prior: Boolean? = null
    private val holders = mutableSetOf<HardwareKeySource>()

    /** Press edge of [key]: the value to apply (always on). Any later press keeps the FIRST snapshot. */
    fun press(current: Boolean, key: HardwareKeySource): Boolean {
        if (prior == null) prior = current
        holders += key
        return true
    }

    /**
     * Release edge of [key] (also a rebind of [key]): the value to restore once no key holds the
     * setting any more, else null — a key that does not hold it, or another key still holding it.
     */
    fun release(key: HardwareKeySource): Boolean? {
        if (!holders.remove(key) || holders.isNotEmpty()) return null
        return prior.also { prior = null }
    }

    /** Every holder lets go at once (onStop: a backgrounded Activity receives no key-up). */
    fun releaseAll(): Boolean? {
        holders.clear()
        return prior.also { prior = null }
    }

    /** The operator's value for persistence: the snapshot while held, otherwise [live]. */
    fun persistedValue(live: Boolean): Boolean = prior ?: live

    /**
     * An explicit operator toggle mid-hold takes ownership; the release must not overwrite it.
     * Returns the cancelled snapshot (null when nothing was held), so a door that can still be rolled
     * back can put the operator's value back if it fails (AGG5-24).
     */
    fun cancel(): Boolean? {
        holders.clear()
        return prior.also { prior = null }
    }
}

/** The three reassignable hardware bindings, as the physical sources a [MomentaryHold] tracks. */
internal enum class HardwareKeySource { FULL_KEY, HALF_PRESS, QUICK_BUTTON }
