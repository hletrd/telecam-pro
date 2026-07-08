package com.hletrd.findx9tele.camera

import android.content.Context
import android.util.Log
import com.oplus.ocs.camera.CameraDeviceInfo
import com.oplus.ocs.camera.CameraParameter
import com.oplus.ocs.camera.CameraUnit
import com.oplus.ocs.camera.CameraUnitClient

/**
 * POC probe for the OPPO CameraUnit / OCS SDK. Does NOT open the camera (query-only), so it can run
 * alongside our Camera2 preview. The make-or-break question: does the ColorOS opencapabilityservice
 * AUTHENTICATE our (OPPO-unregistered) app? If it does, and it reports SUPER_STABILIZATION on the tele,
 * then routing the teleconverter through CameraUnit is the path to the stock 300 mm OIS profile that
 * raw Camera2 can't reach. All results go to Logcat under [TAG].
 */
object OcsProbe {
    const val TAG = "OcsProbe"

    // Flip to true to re-run the auth probe AFTER the app's package + signing cert are registered on
    // OPPO's CameraUnit developer portal. Kept false so the shipping build doesn't attempt (and fail
    // 1004) auth on every launch. Device-verified 2026-07-08: unregistered → AUTH FAILED errorCode=1004.
    const val ENABLED = false

    fun run(context: Context) {
        if (!ENABLED) return
        runCatching {
            val client = CameraUnit.getCameraClient(context)
            if (client == null) {
                Log.e(TAG, "getCameraClient == null → OCS platform NOT supported / SDK absent")
                return
            }
            Log.i(TAG, "getCameraClient OK; isSupportAsyncAuthenticate=${CameraUnitClient.isSupportAsyncAuthenticate(context)}")

            client.addOnConnectionSucceedListener {
                Log.i(TAG, "★ AUTH SUCCEEDED — opencapabilityservice accepted our app")
                runCatching { dumpCapabilities(client) }
                    .onFailure { Log.w(TAG, "capability dump failed: ${it.message}") }
            }
            client.addOnConnectionFailedListener { result ->
                Log.e(TAG, "✗ AUTH FAILED — errorCode=${runCatching { result.errorCode }.getOrNull()} " +
                    "(app likely not registered with OPPO — this is the wall)")
            }
            Log.i(TAG, "connection listeners registered; waiting for auth callback…")
        }.onFailure { Log.e(TAG, "OcsProbe threw: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun dumpCapabilities(client: CameraUnitClient) {
        // Query the tele-ish camera types in VIDEO_MODE: does CameraUnit expose super-steady there?
        val types = listOf(
            CameraUnitClient.CameraType.REAR_SAT,
            CameraUnitClient.CameraType.REAR_MAIN,
        )
        for (type in types) {
            val info: CameraDeviceInfo? = runCatching {
                client.getCameraDeviceInfo(type, CameraUnitClient.CameraMode.VIDEO_MODE)
            }.getOrNull()
            if (info == null) {
                Log.i(TAG, "  type=$type VIDEO_MODE → no CameraDeviceInfo")
                continue
            }
            val stab = runCatching { info.isSupportConfigureParameter(CameraParameter.VIDEO_STABILIZATION_MODE) }.getOrNull()
            Log.i(TAG, "  type=$type VIDEO_MODE: supportsVideoStabilization=$stab")
            // Enumerate the supported stabilization values (looking for super_stabilization).
            runCatching {
                val range = info.getConfigureParameterRange(CameraParameter.VIDEO_STABILIZATION_MODE)
                Log.i(TAG, "    stabilization values=${range?.joinToString()}")
            }
        }
    }
}
