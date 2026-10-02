package me.hletrd.telecampro.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.util.Size
import me.hletrd.telecampro.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only camera capability logger. It records each camera's facing, focal lengths, sensor size
 * and physical ids, plus the read-only concurrent/physical finder probes, to Logcat under [TAG].
 *
 * Run `adb logcat -s TeleCamProVendor` while the app is open to confirm which Camera2 capabilities are
 * available on the device.
 *
 * ONCE PER PROCESS, at INFO (AGG5-37 / DB5-16). Every row is a capability FACT, not a fault, and the
 * dump used to go out at warning level on every Engine start — so ~20 rows per start crossed the
 * 120-row RESERVED warning/error owner, and a debug soak with a few Engine re-creations could leave
 * no room for the real onError/configure/teardown warnings that owner exists to keep. The facts
 * cannot change inside a process, so the first dump is the only one worth its rows; it now spends
 * the recurring information owner like every other repeatable diagnostic — but only within a fixed
 * [CAPABILITY_DUMP_ROW_SHARE] of it, camera facts first, and the process claim is kept only once a
 * row was actually admitted (AGG6-21, [CapabilityDumpRows]).
 */
object VendorTagInspector {
    const val TAG = "TeleCamProVendor"

    private val processDumpClaimed = AtomicBoolean(false)

    /** True exactly once per [claim] owner; production binds the process-lifetime flag. */
    internal fun claimDump(claim: AtomicBoolean = processDumpClaimed): Boolean =
        claim.compareAndSet(false, true)

    private const val LOGICAL_CAMERA_ID = "0"
    private const val WIDE_CAMERA_ID = "2"
    private val PIP_ACTIVE_CAMERA_IDS = listOf(LOGICAL_CAMERA_ID, "4", "5")
    private val PIP_PHYSICAL_TELE_IDS = listOf("4", "5")
    private val PIP_BASELINE_SIZE = Size(640, 480)
    private val PIP_PRIMARY_SIZE = Size(1920, 1440)

    // The dump in flight; only the one setupExecutor task that won [claimDump] touches it.
    private var rows = CapabilityDumpRows()

    /** The ONE emission point: charged against the shared owner inside the dump's share only. */
    private fun log(message: String) {
        if (rows.admit { recurringDiagnosticAllowed(BuildConfig.DEBUG) }) android.util.Log.i(TAG, message)
    }

    fun logAll(manager: CameraManager) {
        if (!claimDump()) return
        rows = CapabilityDumpRows()
        try {
            runCatching {
                // Camera facts first: they are what the dump is read for, so a capped share spends
                // its rows on them before the speculative finder probes.
                val ids = manager.cameraIdList
                for (id in ids) {
                    logCamera(manager, id)
                    val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                    // A physical id that is ALSO a standalone id already has its own row.
                    for (pid in chars.physicalCameraIds) if (pid !in ids) logCamera(manager, pid, parent = id)
                }
                val concurrentSets = manager.concurrentCameraIds
                    .map { it.sorted() }
                    .sortedBy { it.joinToString(",") }
                log("Concurrent camera sets=$concurrentSets")
                runCatching { logWideFinderSupport(manager, concurrentSets) }
                    .onFailure { log("ConcurrentProbe result=ERROR ${it.javaClass.simpleName}: ${it.message}") }
                runCatching { logLogicalPhysicalFinderSupport(manager) }
                    .onFailure { log("PhysicalProbe result=ERROR ${it.javaClass.simpleName}: ${it.message}") }
            }.onFailure { log("logAll failed: ${it.message}") }
            if (rows.admitTruncationNote { recurringDiagnosticAllowed(BuildConfig.DEBUG) }) {
                android.util.Log.i(TAG, "capability dump truncated at its $CAPABILITY_DUMP_ROW_SHARE-row share")
            }
        } finally {
            rows.settleClaim(processDumpClaimed)
        }
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
        // The output-only SessionConfiguration ctor these queries build is API 35+. Debug-only
        // diagnostics on an older device (minSdk 33, multi-device) simply skip — nothing runtime
        // depends on the probe result.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            log("ConcurrentProbe result=SKIP_API_${Build.VERSION.SDK_INT}")
            return
        }
        val ids = manager.cameraIdList.toSet()
        if (WIDE_CAMERA_ID !in ids) {
            log("ConcurrentProbe wide=$WIDE_CAMERA_ID result=MISSING_CAMERA")
            return
        }
        PIP_ACTIVE_CAMERA_IDS.filter { it in ids }.forEach { activeId ->
            val advertised = concurrentSets.any { WIDE_CAMERA_ID in it && activeId in it }
            logConcurrentMandatoryPrivateStreams(manager, activeId)
            if (!advertised) {
                log("ConcurrentProbe pair=$WIDE_CAMERA_ID+$activeId result=NOT_ADVERTISED")
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
                    log(
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
                        log(
                            "ConcurrentProbe pair=$WIDE_CAMERA_ID+$activeId profile=$profile " +
                                "active=${activeSize.width}x${activeSize.height} " +
                                "pip=${wideSize.width}x${wideSize.height} advertised=true " +
                                "sizes=true query=$supported",
                        )
                    }
                    .onFailure { error ->
                        log(
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
        // CameraDeviceSetup + the output-only SessionConfiguration ctor are API 35+ (see
        // logWideFinderSupport); skip the probe below that.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            log("PhysicalProbe result=SKIP_API_${Build.VERSION.SDK_INT}")
            return
        }
        val ids = manager.cameraIdList.toSet()
        if (LOGICAL_CAMERA_ID !in ids) {
            log("PhysicalProbe logical=$LOGICAL_CAMERA_ID result=MISSING_CAMERA")
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
            log(
                "PhysicalProbe logical=$LOGICAL_CAMERA_ID logicalMulti=$logicalMultiCamera " +
                    "queryVersion=$queryVersion result=ERROR ${error.javaClass.simpleName}: ${error.message}",
            )
            return
        }
        log(
            "PhysicalProbe logical=$LOGICAL_CAMERA_ID physicalIds=${physicalIds.sorted()} " +
                "logicalMulti=$logicalMultiCamera queryVersion=$queryVersion setup=$setupSupported",
        )
        if (!logicalMultiCamera || !setupSupported) {
            log("PhysicalProbe logical=$LOGICAL_CAMERA_ID result=QUERY_UNAVAILABLE")
            return
        }

        val setup = manager.getCameraDeviceSetup(LOGICAL_CAMERA_ID)
        PIP_PHYSICAL_TELE_IDS.forEach { teleId ->
            if (WIDE_CAMERA_ID !in physicalIds || teleId !in physicalIds) {
                log("PhysicalProbe pair=$WIDE_CAMERA_ID+$teleId result=NOT_LOGICAL_PHYSICAL_PAIR")
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
                    log(
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
                        log(
                            "PhysicalProbe pair=$WIDE_CAMERA_ID+$teleId profile=$profile " +
                                "tele=${teleSize.width}x${teleSize.height} " +
                                "pip=${wideSize.width}x${wideSize.height} query=$supported",
                        )
                    }
                    .onFailure { error ->
                        log(
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

    // Output-only SessionConfiguration ctor: both callers are SDK-gated at entry; the annotation
    // carries that contract for lint (it cannot see cross-function guards).
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
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
        log("ConcurrentMandatory camera=$cameraId private=[$privateSizes]")
    }

    private fun logCamera(manager: CameraManager, id: String, parent: String? = null) {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val header = if (parent == null) "Camera $id" else "Camera $id (physical of $parent)"
        // One row per camera (it was two): the dump's rows are a fixed share of the shared owner.
        log("== $header facing=$facing focalsMm=$focals sensorMm=$size physicalIds=${chars.physicalCameraIds} ==")
    }
}
