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
 * Main-thread confined, like every ViewModel input path.
 */
internal class MomentaryHold {
    private var prior: Boolean? = null

    /** Press edge: the value to apply (always on). A repeat press keeps the FIRST snapshot. */
    fun press(current: Boolean): Boolean {
        if (prior == null) prior = current
        return true
    }

    /** Release edge: the value to restore, or null when no hold owns the setting. */
    fun release(): Boolean? = prior.also { prior = null }

    /** The operator's value for persistence: the snapshot while held, otherwise [live]. */
    fun persistedValue(live: Boolean): Boolean = prior ?: live

    /**
     * An explicit operator toggle mid-hold takes ownership; the release must not overwrite it.
     * Returns the cancelled snapshot (null when nothing was held), so a door that can still be rolled
     * back can put the operator's value back if it fails (AGG5-24).
     */
    fun cancel(): Boolean? = prior.also { prior = null }
}
