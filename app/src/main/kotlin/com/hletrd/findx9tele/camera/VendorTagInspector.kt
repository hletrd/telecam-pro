package com.hletrd.findx9tele.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.Log
import android.util.Size

/**
 * Debug-only camera capability logger. It records camera characteristics plus available
 * capture-request and session keys to Logcat under tag [TAG].
 *
 * Run `adb logcat -s X9TeleVendor` while the app is open to confirm which Camera2 capabilities are
 * available on the device.
 */
object VendorTagInspector {
    const val TAG = "X9TeleVendor"

    private const val LOGICAL_CAMERA_ID = "0"
    private const val WIDE_CAMERA_ID = "2"
    private val PIP_ACTIVE_CAMERA_IDS = listOf(LOGICAL_CAMERA_ID, "4", "5")
    private val PIP_PHYSICAL_TELE_IDS = listOf("4", "5")
    private val PIP_BASELINE_SIZE = Size(640, 480)
    private val PIP_PRIMARY_SIZE = Size(1920, 1440)

    fun logAll(manager: CameraManager) {
        runCatching {
            val concurrentSets = manager.concurrentCameraIds
                .map { it.sorted() }
                .sortedBy { it.joinToString(",") }
            Log.w(TAG, "Concurrent camera sets=$concurrentSets")
            runCatching { logWideFinderSupport(manager, concurrentSets) }
                .onFailure { Log.w(TAG, "ConcurrentProbe result=ERROR ${it.javaClass.simpleName}: ${it.message}") }
            runCatching { logLogicalPhysicalFinderSupport(manager) }
                .onFailure { Log.w(TAG, "PhysicalProbe result=ERROR ${it.javaClass.simpleName}: ${it.message}") }
            for (id in manager.cameraIdList) {
                logCamera(manager, id)
                val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                for (pid in chars.physicalCameraIds) logCamera(manager, pid, parent = id)
            }
        }.onFailure { Log.w(TAG, "logAll failed: ${it.message}") }
    }

    /**
     * Read-only feasibility check for a true 1x finder. Deferred PRIVATE outputs describe the
     * intended streams without allocating a SurfaceTexture, opening another camera, or configuring
     * a session. Never query an unadvertised pair: unsupported rear+rear dual-open is exactly the
     * kind of speculative HAL path this diagnostic exists to avoid.
     */
    // CameraEngine invokes diagnostics only after the app's CAMERA grant has admitted startup;
    // SecurityException is still isolated and logged by each query below.
    @SuppressLint("MissingPermission")
    private fun logWideFinderSupport(manager: CameraManager, concurrentSets: List<List<String>>) {
        val ids = manager.cameraIdList.toSet()
        if (WIDE_CAMERA_ID !in ids) {
            Log.w(TAG, "ConcurrentProbe wide=$WIDE_CAMERA_ID result=MISSING_CAMERA")
            return
        }
        PIP_ACTIVE_CAMERA_IDS.filter { it in ids }.forEach { activeId ->
            val advertised = concurrentSets.any { WIDE_CAMERA_ID in it && activeId in it }
            logConcurrentMandatoryPrivateStreams(manager, activeId)
            if (!advertised) {
                Log.w(TAG, "ConcurrentProbe pair=$WIDE_CAMERA_ID+$activeId result=NOT_ADVERTISED")
                return@forEach
            }
            listOf(
                "baseline" to PIP_BASELINE_SIZE,
                "target" to PIP_PRIMARY_SIZE,
            ).forEach { (profile, activeSize) ->
                val wideSize = PIP_BASELINE_SIZE
                val activeAvailable = supportsPrivateSize(manager, activeId, activeSize)
                val wideAvailable = supportsPrivateSize(manager, WIDE_CAMERA_ID, wideSize)
                if (!activeAvailable || !wideAvailable) {
                    Log.w(
                        TAG,
                        "ConcurrentProbe pair=$WIDE_CAMERA_ID+$activeId profile=$profile " +
                            "active=${activeSize.width}x${activeSize.height} " +
                            "pip=${wideSize.width}x${wideSize.height} advertised=true " +
                            "sizes=false result=SKIP_UNAVAILABLE",
                    )
                    return@forEach
                }
                val sessions = linkedMapOf(
                    activeId to privateSession(activeSize),
                    WIDE_CAMERA_ID to privateSession(wideSize),
                )
                runCatching { manager.isConcurrentSessionConfigurationSupported(sessions) }
                    .onSuccess { supported ->
                        Log.w(
                            TAG,
                            "ConcurrentProbe pair=$WIDE_CAMERA_ID+$activeId profile=$profile " +
                                "active=${activeSize.width}x${activeSize.height} " +
                                "pip=${wideSize.width}x${wideSize.height} advertised=true " +
                                "sizes=true query=$supported",
                        )
                    }
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "ConcurrentProbe pair=$WIDE_CAMERA_ID+$activeId profile=$profile " +
                                "result=ERROR ${error.javaClass.simpleName}: ${error.message}",
                        )
                    }
            }
        }
        logConcurrentMandatoryPrivateStreams(manager, WIDE_CAMERA_ID)
    }

    /**
     * Metadata-only query for one logical-camera session carrying a real 1x physical stream beside
     * the 3x or 10x physical stream. CameraDeviceSetup does not open a CameraDevice or configure the
     * proposed session. That distinction matters on this phone: actually configuring physical
     * outputs has previously crashed the vendor HAL, so this diagnostic must remain query-only.
     */
    @SuppressLint("MissingPermission")
    private fun logLogicalPhysicalFinderSupport(manager: CameraManager) {
        val ids = manager.cameraIdList.toSet()
        if (LOGICAL_CAMERA_ID !in ids) {
            Log.w(TAG, "PhysicalProbe logical=$LOGICAL_CAMERA_ID result=MISSING_CAMERA")
            return
        }
        val logicalChars = manager.getCameraCharacteristics(LOGICAL_CAMERA_ID)
        val physicalIds = logicalChars.physicalCameraIds
        val capabilities = logicalChars
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val logicalMultiCamera =
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities
        val queryVersion = logicalChars
            .get(CameraCharacteristics.INFO_SESSION_CONFIGURATION_QUERY_VERSION)
        val setupSupported = runCatching {
            manager.isCameraDeviceSetupSupported(LOGICAL_CAMERA_ID)
        }.getOrElse { error ->
            Log.w(
                TAG,
                "PhysicalProbe logical=$LOGICAL_CAMERA_ID logicalMulti=$logicalMultiCamera " +
                    "queryVersion=$queryVersion result=ERROR ${error.javaClass.simpleName}: ${error.message}",
            )
            return
        }
        Log.w(
            TAG,
            "PhysicalProbe logical=$LOGICAL_CAMERA_ID physicalIds=${physicalIds.sorted()} " +
                "logicalMulti=$logicalMultiCamera queryVersion=$queryVersion setup=$setupSupported",
        )
        if (!logicalMultiCamera || !setupSupported) {
            Log.w(TAG, "PhysicalProbe logical=$LOGICAL_CAMERA_ID result=QUERY_UNAVAILABLE")
            return
        }

        val setup = manager.getCameraDeviceSetup(LOGICAL_CAMERA_ID)
        PIP_PHYSICAL_TELE_IDS.forEach { teleId ->
            if (WIDE_CAMERA_ID !in physicalIds || teleId !in physicalIds) {
                Log.w(TAG, "PhysicalProbe pair=$WIDE_CAMERA_ID+$teleId result=NOT_LOGICAL_PHYSICAL_PAIR")
                return@forEach
            }
            listOf(
                "baseline" to PIP_BASELINE_SIZE,
                "target" to PIP_PRIMARY_SIZE,
            ).forEach { (profile, teleSize) ->
                val wideSize = PIP_BASELINE_SIZE
                val teleAvailable = supportsPrivateSize(manager, teleId, teleSize)
                val wideAvailable = supportsPrivateSize(manager, WIDE_CAMERA_ID, wideSize)
                if (!teleAvailable || !wideAvailable) {
                    Log.w(
                        TAG,
                        "PhysicalProbe pair=$WIDE_CAMERA_ID+$teleId profile=$profile " +
                            "tele=${teleSize.width}x${teleSize.height} " +
                            "pip=${wideSize.width}x${wideSize.height} sizes=false " +
                            "result=SKIP_UNAVAILABLE",
                    )
                    return@forEach
                }
                val session = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(
                        physicalPrivateOutput(wideSize, WIDE_CAMERA_ID),
                        physicalPrivateOutput(teleSize, teleId),
                    ),
                )
                runCatching { setup.isSessionConfigurationSupported(session) }
                    .onSuccess { supported ->
                        Log.w(
                            TAG,
                            "PhysicalProbe pair=$WIDE_CAMERA_ID+$teleId profile=$profile " +
                                "tele=${teleSize.width}x${teleSize.height} " +
                                "pip=${wideSize.width}x${wideSize.height} query=$supported",
                        )
                    }
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "PhysicalProbe pair=$WIDE_CAMERA_ID+$teleId profile=$profile " +
                                "result=ERROR ${error.javaClass.simpleName}: ${error.message}",
                        )
                    }
            }
        }
    }

    private fun supportsPrivateSize(manager: CameraManager, cameraId: String, size: Size): Boolean =
        manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.contains(size) == true

    private fun privateSession(size: Size): SessionConfiguration = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        listOf(OutputConfiguration(size, SurfaceTexture::class.java)),
    )

    private fun physicalPrivateOutput(size: Size, cameraId: String): OutputConfiguration =
        OutputConfiguration(size, SurfaceTexture::class.java).apply {
            setPhysicalCameraId(cameraId)
        }

    private fun logConcurrentMandatoryPrivateStreams(manager: CameraManager, cameraId: String) {
        val combinations = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS)
            .orEmpty()
        val privateSizes = combinations
            .flatMap { it.streamsInformation }
            .filter { !it.isInput && it.format == ImageFormat.PRIVATE }
            .flatMap { it.availableSizes }
            .distinct()
            .sortedWith(compareBy<Size> { it.width }.thenBy { it.height })
            .joinToString { "${it.width}x${it.height}" }
        Log.w(TAG, "ConcurrentMandatory camera=$cameraId private=[$privateSizes]")
    }

    private fun logCamera(manager: CameraManager, id: String, parent: String? = null) {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val header = if (parent == null) "Camera $id" else "Camera $id (physical of $parent)"
        Log.i(TAG, "== $header facing=$facing focalsMm=$focals sensorMm=$size ==")

        Log.i(TAG, "   physicalIds=${chars.physicalCameraIds}")
    }
}
