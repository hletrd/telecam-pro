package com.hletrd.findx9tele.camera

import android.content.Context

/**
 * Release no-op counterpart of the debug-only CameraUnit/OCS availability probe.
 *
 * The real probe (app/src/debug) links OPPO's closed-source OCS SDK, which is a
 * `debugImplementation` dependency: the release AAB must not carry an OEM SDK it never invokes
 * (supply-chain surface + it undercut the Play Data-Safety "no network-capable SDKs" story).
 * This stub keeps the single main-source call site (`MainActivity`) variant-agnostic.
 */
object OcsProbe {
    @Suppress("UNUSED_PARAMETER")
    fun run(context: Context) = Unit
}
