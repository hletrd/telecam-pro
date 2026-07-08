package com.hletrd.findx9tele.camera

import android.content.Context
import android.util.Log
import com.hletrd.findx9tele.BuildConfig
import com.oplus.ocs.camera.CameraDeviceInfo
import com.oplus.ocs.camera.CameraParameter
import com.oplus.ocs.camera.CameraUnit
import com.oplus.ocs.camera.CameraUnitClient

/**
 * Probe + capability gatherer for the OPPO CameraUnit / OCS SDK.
 *
 * The stock OPPO camera app routes the 300 mm teleconverter OIS amplification through authenticated
 * OCS `ConfigureKey`s (`com.oplus.configure.video.stabilization` → `super_stabilization`,
 * `com.oplus.explorer.chip.state`, etc.). Those keys are not exposed to raw Camera2, so the only
 * known way to reach the stock 300 mm profile is through a CameraUnit session after OPPO has
 * authenticated this package.
 *
 * This probe does NOT open the camera. It binds to `com.oplus.ocs.service.OpenAuthenticateService`,
 * reports whether the app is accepted, and (on success) queries which private capabilities the
 * device advertises. The result is surfaced as [OcsAuthState] so the UI can explain why the
 * teleconverter OIS profile is unavailable and what the user must do to unlock it.
 */
object OcsProbe {
    const val TAG = "OcsProbe"

    /**
     * Run the probe on every launch in debug builds. In release builds it is silent: we do not want
     * to spam unregistered users with auth-failure logs, and the CameraUnit path is not ready for
     * production without an AUTH_CODE anyway.
     */
    val ENABLED = BuildConfig.DEBUG

    /** Current authentication/capability state. Updated on the main thread. */
    @Volatile
    var state: OcsAuthState = OcsAuthState.UNKNOWN
        private set

    private val listeners = mutableListOf<(OcsAuthState) -> Unit>()

    enum class OcsAuthState {
        UNKNOWN,
        NOT_SUPPORTED,          // CameraUnit SDK / service not present on this device
        AUTH_FAILED,            // service rejected us (errorCode 1004/1002 → not registered)
        AUTH_SUCCEEDED          // accepted; capability report available in logcat
    }

    data class CapabilityReport(
        val supportsExplorerChipState: Boolean,
        val supportsExplorerEnable: Boolean,
        val supportsVideoStabilization: Boolean,
        val stabilizationValues: List<String>,
        val cameraTypes: List<String>,
        val modes: Map<String, List<String>>
    )

    @Volatile
    var lastReport: CapabilityReport? = null
        private set

    fun addListener(listener: (OcsAuthState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (OcsAuthState) -> Unit) {
        listeners.remove(listener)
    }

    fun run(context: Context) {
        if (!ENABLED) return
        runCatching {
            val client = CameraUnit.getCameraClient(context)
            if (client == null) {
                Log.w(TAG, "getCameraClient == null → CameraUnit service/SDK not available")
                transition(OcsAuthState.NOT_SUPPORTED)
                return
            }
            Log.i(TAG, "getCameraClient OK; isSupportAsyncAuthenticate=${CameraUnitClient.isSupportAsyncAuthenticate(context)}")

            client.addOnConnectionSucceedListener {
                Log.i(TAG, "★ AUTH SUCCEEDED — opencapabilityservice accepted our app")
                runCatching { dumpCapabilities(client) }
                    .onFailure { Log.w(TAG, "capability dump failed: ${it.message}") }
                transition(OcsAuthState.AUTH_SUCCEEDED)
            }
            client.addOnConnectionFailedListener { result ->
                val code = runCatching { result.errorCode }.getOrNull() ?: -1
                val meaning = authErrorMeaning(code)
                Log.e(TAG, "✗ AUTH FAILED — errorCode=$code ($meaning)")
                transition(OcsAuthState.AUTH_FAILED)
            }
            Log.i(TAG, "connection listeners registered; waiting for auth callback…")
        }.onFailure { e ->
            Log.e(TAG, "OcsProbe threw: ${e.javaClass.simpleName}: ${e.message}")
            transition(OcsAuthState.NOT_SUPPORTED)
        }
    }

    private fun transition(newState: OcsAuthState) {
        state = newState
        listeners.forEach { runCatching { it(newState) } }
    }

    /**
     * Error-code meanings recovered from the stock OPPO camera app decompilation
     * (`androidx.appcompat.app.z.g(int)`). The most common one for an unregistered third-party app
     * is 1004 (AUTHCODE_EXPECTED).
     */
    private fun authErrorMeaning(code: Int): String = when (code) {
        1001 -> "AUTHENTICATE_SUCCESS"
        1002 -> "AUTHENTICATE_FAIL"
        1003 -> "TIME_EXPIRED"
        1004 -> "AUTHCODE_EXPECTED"
        1005 -> "VERSION_INCOMPATIBLE"
        1006 -> "AUTHCODE_RECYCLE"
        1007 -> "AUTHCODE_INVALID"
        1008 -> "CAPABILITY_EXCEPTION"
        1009 -> "STATUS_EXCEPTION"
        1010 -> "INTERNAL_EXCEPTION"
        else -> "UNKNOWN"
    }

    private fun dumpCapabilities(client: CameraUnitClient) {
        val cameraTypes = runCatching { client.allSupportCameraType }.getOrNull() ?: emptyList()
        val modes = runCatching { client.allSupportCameraMode }.getOrNull() ?: emptyMap()
        Log.i(TAG, "supported cameraTypes=$cameraTypes")
        Log.i(TAG, "supported modes=$modes")

        // The teleconverter is mounted on the 3×/70 mm periscope. CameraUnit exposes it as
        // REAR_TELE (or possibly REAR_SAT). Query both for the private keys we need.
        val targetTypes = listOf(
            CameraUnitClient.CameraType.REAR_TELE,
            CameraUnitClient.CameraType.REAR_SAT,
            CameraUnitClient.CameraType.REAR_MAIN
        ).filter { it in cameraTypes }

        for (type in targetTypes) {
            for (mode in listOf(
                CameraUnitClient.CameraMode.VIDEO_MODE,
                CameraUnitClient.CameraMode.PHOTO_MODE,
                CameraUnitClient.CameraMode.PROFESSIONAL_MODE
            )) {
                val info: CameraDeviceInfo? = runCatching {
                    client.getCameraDeviceInfo(type, mode)
                }.getOrNull()
                if (info == null) {
                    Log.i(TAG, "  type=$type mode=$mode → no CameraDeviceInfo")
                    continue
                }
                val stabSupported = runCatching {
                    info.isSupportConfigureParameter(CameraParameter.VIDEO_STABILIZATION_MODE)
                }.getOrNull() ?: false
                val stabValues = runCatching {
                    info.getConfigureParameterRange(CameraParameter.VIDEO_STABILIZATION_MODE)
                }.getOrNull() ?: emptyList<String>()
                Log.i(TAG, "  type=$type mode=$mode: VIDEO_STABILIZATION_MODE supported=$stabSupported values=$stabValues")

                // Our public SDK (1.1.0) does not expose KEY_EXPLORER_CHIP_STATE / KEY_EXPLORER_ENABLE.
                // The stock app uses a newer embedded SDK. We therefore cannot query those keys
                // through the typed API, but we log the SDK limitation here.
                Log.i(TAG, "  type=$type mode=$mode: explorer keys not in public SDK 1.1.0")
            }
        }

        lastReport = CapabilityReport(
            supportsExplorerChipState = false, // not queryable with SDK 1.1.0
            supportsExplorerEnable = false,
            supportsVideoStabilization = true, // key exists in SDK
            stabilizationValues = listOf(
                CameraParameter.VideoStabilizationMode.VIDEO_STABILIZATION,
                CameraParameter.VideoStabilizationMode.SUPER_STABILIZATION
            ),
            cameraTypes = cameraTypes,
            modes = modes
        )
    }
}
