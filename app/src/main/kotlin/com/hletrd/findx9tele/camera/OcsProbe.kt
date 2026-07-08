package com.hletrd.findx9tele.camera

import android.content.Context
import android.util.Log
import com.hletrd.findx9tele.BuildConfig
import com.oplus.ocs.camera.CameraDeviceInfo
import com.oplus.ocs.camera.CameraParameter
import com.oplus.ocs.camera.CameraUnit
import com.oplus.ocs.camera.CameraUnitClient

/**
 * Availability check for the OPPO CameraUnit / OCS SDK.
 *
 * Some 300 mm teleconverter stabilization options are exposed through OPPO's CameraUnit extension
 * layer rather than raw Camera2. This check records whether the SDK is available to this package and
 * which documented capability ranges it reports.
 *
 * This check does NOT open the camera. It only initializes the CameraUnit client and, on success,
 * queries documented capability ranges. The result is surfaced as [OcsAuthState] for future UI or
 * diagnostics.
 */
object OcsProbe {
    const val TAG = "OcsProbe"

    /**
     * Run the check on every launch in debug builds. In release builds it is silent: CameraUnit
     * integration is not enabled for production until an official AUTH_CODE is configured.
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
        AUTH_FAILED,            // CameraUnit not enabled for this package
        AUTH_SUCCEEDED          // CameraUnit client ready; capability report available in logcat
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
                Log.i(TAG, "CameraUnit client ready")
                runCatching { logCapabilities(client) }
                    .onFailure { Log.w(TAG, "capability query failed: ${it.message}") }
                transition(OcsAuthState.AUTH_SUCCEEDED)
            }
            client.addOnConnectionFailedListener { result ->
                val code = runCatching { result.errorCode }.getOrNull() ?: -1
                val meaning = authErrorMeaning(code)
                Log.w(TAG, "CameraUnit not enabled; errorCode=$code ($meaning)")
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
     * Error-code names used by the OPPO CameraUnit SDK. The common unconfigured-package result is
     * 1004 (AUTHCODE_EXPECTED).
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

    private fun logCapabilities(client: CameraUnitClient) {
        val cameraTypes = runCatching { client.allSupportCameraType }.getOrNull() ?: emptyList()
        val modes = runCatching { client.allSupportCameraMode }.getOrNull() ?: emptyMap()
        Log.i(TAG, "supported cameraTypes=$cameraTypes")
        Log.i(TAG, "supported modes=$modes")

        // The teleconverter is mounted on the 3×/70 mm periscope. Query the relevant rear camera
        // families exposed by the public SDK.
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

                // SDK 1.1.0 does not expose Explorer-specific typed parameters, so keep the check to
                // documented stabilization ranges only.
                Log.i(TAG, "  type=$type mode=$mode: Explorer params not exposed by SDK 1.1.0")
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
