package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.hypot

/**
 * @param physicalId non-null when the tele is a physical sub-camera of a logical multi-camera;
 *                   streams must then be routed with OutputConfiguration.setPhysicalCameraId().
 * @param equivFocalMm 35mm-equivalent focal length used for selection.
 */
data class TeleSelection(
    val logicalId: String,
    val physicalId: String?,
    val equivFocalMm: Float,
)

/**
 * One enumerated LENS_FACING_FRONT camera id. Kept Android-free (id + two plain facts) so
 * [CameraSelector2.pickFrontBest] is JVM-unit-testable like [CameraSelector2.pickBest].
 */
data class FrontCandidate(
    val id: String,
    /** True when the id advertises physical sub-ids; it is still only ever opened PLAINLY. */
    val logicalMultiCamera: Boolean,
    /** SENSOR_INFO_ACTIVE_ARRAY_SIZE area in pixels; 0 when unreadable. */
    val activeArrayArea: Long,
)

/**
 * One device-static projection of the Camera2 inventory, resolved before the first open.
 *
 * BACK remains the preferred route so PMA110 starts exactly as before. A front-only device starts
 * on FRONT instead of exhausting a known-impossible rear retry. EXTERNAL is a conservative final
 * fallback: it is opened plainly under the GENERIC [DeviceProfile], never physical-routed and never
 * given rear-only teleconverter controls.
 */
data class CameraRouteInventory(
    val back: Boolean,
    val front: Boolean,
    val external: Boolean,
    /** False when at least one advertised id could not be classified this attempt. */
    val complete: Boolean = true,
) {
    val any: Boolean get() = back || front || external
    val nonFront: Boolean get() = back || external
    val switchAvailable: Boolean get() = front && nonFront

    internal fun initialRoute(): CameraRoute? = when {
        back -> CameraRoute.BACK
        front -> CameraRoute.FRONT
        external -> CameraRoute.EXTERNAL
        else -> null
    }

    companion object {
        /** Keeps pre-enumeration PMA110 chrome unchanged until the real inventory arrives. */
        val UNKNOWN = CameraRouteInventory(
            back = true,
            front = true,
            external = false,
            complete = false,
        )
    }
}

internal fun cameraRouteInventoryOf(routes: Iterable<CameraRoute>): CameraRouteInventory {
    val set = routes.toSet()
    return CameraRouteInventory(
        back = CameraRoute.BACK in set,
        front = CameraRoute.FRONT in set,
        external = CameraRoute.EXTERNAL in set,
    )
}

/** Route target for settings/MR intent under the currently proved inventory. */
internal fun recalledCameraRoute(
    inventory: CameraRouteInventory,
    current: CameraRoute,
): CameraRoute? = when {
    inventory.back -> CameraRoute.BACK
    current == CameraRoute.FRONT && inventory.front -> CameraRoute.FRONT
    current == CameraRoute.EXTERNAL && inventory.external -> CameraRoute.EXTERNAL
    inventory.external -> CameraRoute.EXTERNAL
    inventory.front -> CameraRoute.FRONT
    else -> null
}

internal data class CameraRouteTopologyDecision(
    val topologyChanged: Boolean,
    val targetRoute: CameraRoute?,
)

/**
 * Membership plus definite physical-identity epochs from [CameraManager.AvailabilityCallback].
 *
 * Camera ids are provider handles, not durable hardware identities: a removed UVC device can be
 * replaced by different hardware that is advertised under the same id. The removal epoch keeps
 * that A -> B transition observable even when callback coalescing sees identical final id sets.
 */
internal data class CameraTopologyStamp(
    val ids: Set<String>,
    val identityEpochs: Map<String, Long> = emptyMap(),
)

internal fun changedCameraTopologyIds(
    previous: CameraTopologyStamp,
    current: CameraTopologyStamp,
): Set<String> = buildSet {
    addAll(previous.ids subtract current.ids)
    addAll(current.ids subtract previous.ids)
    (previous.identityEpochs.keys + current.identityEpochs.keys).forEach { id ->
        if (previous.identityEpochs[id] != current.identityEpochs[id]) add(id)
    }
}

/**
 * Keeps only identity epochs that can still describe a currently enumerated camera handle.
 *
 * A removal epoch is needed until one complete inventory consumes it: it distinguishes an A -> B
 * replacement that reuses the same id even when callback coalescing hides the intermediate absent
 * set. Once a complete inventory proves the id absent, membership itself owns any later arrival and
 * retaining the departed id forever only grows the process map under removable-camera churn.
 */
internal fun retainedCameraIdentityEpochs(
    currentIds: Set<String>,
    identityEpochs: Map<String, Long>,
): Map<String, Long> = identityEpochs.filterKeys { it in currentIds }

internal enum class RecordingTopologyLeaseStage {
    ADMISSION,
    RECORDER,
}

/**
 * Latest-wins topology convergence owner spanning REC admission, recording, and native teardown.
 * Inventory/cache work remains independent; only the camera optics action waits for all leases.
 */
internal class CameraRouteTopologyConvergence {
    private var nextLease = 0L
    private val recordingLeases = mutableMapOf<Long, RecordingTopologyLeaseStage>()
    private var pendingRevision = 0L
    private var closed = false

    @Synchronized
    fun beginRecording(): Long {
        if (closed) return 0L
        return (++nextLease).also { recordingLeases[it] = RecordingTopologyLeaseStage.ADMISSION }
    }

    /** Transfers the exact admission lease to the published recorder/native teardown owner. */
    @Synchronized
    fun transferToRecorder(lease: Long): Boolean {
        if (closed || recordingLeases[lease] != RecordingTopologyLeaseStage.ADMISSION) return false
        recordingLeases[lease] = RecordingTopologyLeaseStage.RECORDER
        return true
    }

    /** Releases only a pre-publication admission owner; a recorder-owned lease is deliberately inert. */
    @Synchronized
    fun finishAdmission(lease: Long): Boolean {
        if (lease == 0L || recordingLeases[lease] != RecordingTopologyLeaseStage.ADMISSION) return false
        recordingLeases.remove(lease)
        return true
    }

    /** Checked native finalization/quarantine is the sole terminal for a recorder-owned lease. */
    @Synchronized
    fun finishRecording(lease: Long): Boolean {
        if (lease == 0L || recordingLeases[lease] != RecordingTopologyLeaseStage.RECORDER) return false
        recordingLeases.remove(lease)
        return true
    }

    @Synchronized
    fun offer(revision: Long) {
        if (!closed && revision > pendingRevision) pendingRevision = revision
    }

    /** Claims exactly one newest action only after the recorder has yielded the session. */
    @Synchronized
    fun claim(): Long? {
        if (closed || recordingLeases.isNotEmpty() || pendingRevision == 0L) return null
        return pendingRevision.also { pendingRevision = 0L }
    }

    @Synchronized
    fun hasClaimableAction(): Boolean = !closed && recordingLeases.isEmpty() && pendingRevision != 0L

    @Synchronized
    internal fun leaseStage(lease: Long): RecordingTopologyLeaseStage? = recordingLeases[lease]

    @Synchronized
    fun close() {
        closed = true
        recordingLeases.clear()
        pendingRevision = 0L
    }
}

/** Pure attach/detach/replacement decision used by the Engine's ordered availability owner. */
internal fun cameraRouteTopologyDecision(
    previousIds: Set<String>,
    currentIds: Set<String>,
    inventory: CameraRouteInventory,
    currentRoute: CameraRoute,
): CameraRouteTopologyDecision = CameraRouteTopologyDecision(
    topologyChanged = previousIds != currentIds,
    targetRoute = currentRoute.takeIf { route ->
        when (route) {
            CameraRoute.BACK -> inventory.back
            CameraRoute.FRONT -> inventory.front
            CameraRoute.EXTERNAL -> inventory.external
        }
    } ?: inventory.initialRoute(),
)

/** One attempted classification, including states that prevent a truthful complete inventory. */
internal enum class CameraRouteObservation {
    BACK,
    FRONT,
    EXTERNAL,
    UNKNOWN,
    READ_FAILED,
}

internal fun cameraRouteInventoryOfObservations(
    observations: Iterable<CameraRouteObservation>,
): CameraRouteInventory {
    val values = observations.toList()
    return CameraRouteInventory(
        back = CameraRouteObservation.BACK in values,
        front = CameraRouteObservation.FRONT in values,
        external = CameraRouteObservation.EXTERNAL in values,
        complete = values.none {
            it == CameraRouteObservation.UNKNOWN || it == CameraRouteObservation.READ_FAILED
        },
    )
}

/**
 * Selects the lens the teleconverter mounts on: the back camera whose 35mm-equivalent focal length
 * is CLOSEST to [TARGET_EQUIV_MM] (the 3x/70mm periscope) — NOT the longest lens.
 *
 * On the Find X9 Ultra the longest native lens is the 230mm 10x periscope; the Explorer
 * teleconverter attaches to the 70mm 3x. Picking by max focal would wrongly select the 10x.
 * A manual override ("logicalId" or "logicalId:physicalId") pins a specific lens.
 */
object CameraSelector2 {
    const val TARGET_EQUIV_MM = 70f
    private const val FULL_FRAME_DIAGONAL_MM = 43.2666f

    fun select(manager: CameraManager, overrideId: String?): TeleSelection? {
        if (!overrideId.isNullOrBlank()) {
            val parts = overrideId.split(":")
            return if (parts.size == 2) {
                TeleSelection(parts[0], parts[1], equivFocalOf(manager, parts[1]))
            } else {
                TeleSelection(overrideId, null, equivFocalOf(manager, overrideId))
            }
        }
        return pickBest(candidatesOf(manager))
    }

    /** Enumerates the route directions the app can actually open. */
    internal fun routeInventory(manager: CameraManager): CameraRouteInventory {
        val ids = runCatching { manager.cameraIdList }.getOrElse {
            return CameraRouteInventory(
                back = false,
                front = false,
                external = false,
                complete = false,
            )
        }
        val observations = ids.map { id ->
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            if (chars == null) {
                return@map CameraRouteObservation.READ_FAILED
            }
            when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> CameraRouteObservation.BACK
                CameraMetadata.LENS_FACING_FRONT -> CameraRouteObservation.FRONT
                CameraMetadata.LENS_FACING_EXTERNAL -> CameraRouteObservation.EXTERNAL
                else -> CameraRouteObservation.UNKNOWN
            }
        }
        return cameraRouteInventoryOfObservations(observations)
    }

    /** Enumerates every back-facing lens as a candidate (standalone ids + logical physical sub-cameras). */
    fun candidatesOf(manager: CameraManager): List<TeleSelection> {
        val candidates = ArrayList<TeleSelection>()
        // cameraIdList throws CameraAccessException on a transient camera-service hiccup — reached on
        // every cold start and lens switch, so degrade to "no candidates" instead of crashing.
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue

            val physicalIds = chars.physicalCameraIds
            if (physicalIds.isEmpty()) {
                candidates.add(TeleSelection(id, null, equivFocalOf(manager, id)))
            } else {
                for (pid in physicalIds) candidates.add(TeleSelection(id, pid, equivFocalOf(manager, pid)))
            }
        }
        return candidates
    }

    /**
     * The back LOGICAL multi-camera id (physical sub-ids present) — the seamless-zoom home. Driving
     * CONTROL_ZOOM_RATIO on this id lets the HAL cross the physical lenses internally (0.6–20× on
     * this device, physIds 3/2/4/5) with digital fill between the optical steps — no reopen, the
     * stock-camera behavior. This is NOT the setPhysicalCameraId ROUTING that crashes the QTI HAL:
     * the logical camera is opened plainly with no per-stream physical routing.
     */
    fun logicalBackId(manager: CameraManager): String? {
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            if (chars.physicalCameraIds.isNotEmpty()) return id
        }
        return null
    }

    /**
     * Resolves the lens whose 35mm-equiv is closest to [targetEquivMm] to a concrete override id
     * string ("logicalId" for a standalone, "logicalId:physicalId" for a routed sub-camera). Returns
     * null when no back lens is readable. Used by the lens switcher to pick UW/main/3×/10×; prefers a
     * standalone id so the QTI-HAL routing crash is avoided (same rule as [pickBest]).
     */
    fun overrideIdForFocal(manager: CameraManager, targetEquivMm: Float): String? {
        val sel = pickClosest(candidatesOf(manager), targetEquivMm) ?: return null
        return if (sel.physicalId != null) "${sel.logicalId}:${sel.physicalId}" else sel.logicalId
    }

    /**
     * The candidate whose 35mm-equiv is closest to [targetEquivMm]; on ties prefers a STANDALONE
     * camera (physicalId == null). Pure, so lens-picker resolution is JVM-unit-testable.
     */
    fun pickClosest(candidates: List<TeleSelection>, targetEquivMm: Float): TeleSelection? =
        candidates.filter { it.equivFocalMm > 0f }
            .minWithOrNull(
                compareBy({ abs(it.equivFocalMm - targetEquivMm) }, { if (it.physicalId == null) 0 else 1 }),
            )
            // Focal metadata can disappear transiently while CameraService is recovering. Never
            // turn that degraded read into a routed physical output: this device's QTI HAL is known
            // to SIGSEGV in configureStreams for that path. A standalone candidate is safe to open;
            // if none exists, fail closed and let the UI report the route unavailable.
            ?: candidates.firstOrNull { it.physicalId == null }

    /**
     * Pure selection over an enumerated candidate list: the one whose 35mm-equiv is CLOSEST to
     * [TARGET_EQUIV_MM]; on ties prefer a STANDALONE camera (physicalId == null) over one reached via
     * logical-multicamera physical routing. On this device the tele is exposed both as physical "0:4"
     * and as standalone id "4"; the routed path crashes the QTI HAL (ChiMulticameraBase
     * configureStreams SIGSEGV), while opening the standalone id works and also permits RAW.
     * Candidates with a non-positive equiv focal (unreadable lens) are excluded. Extracted from
     * [select] so it is JVM-unit-testable (no CameraManager / CameraCharacteristics needed).
     */
    fun pickBest(candidates: List<TeleSelection>): TeleSelection? = pickClosest(candidates, TARGET_EQUIV_MM)

    /**
     * The front (selfie) camera, opened PLAINLY like every other selection — never via
     * setPhysicalCameraId routing (the QTI-HAL crash class documented on [pickBest]). Returns a
     * standalone-shaped [TeleSelection] (physicalId = null) so the whole session/controller path is
     * unchanged; null when the device exposes no readable front camera. Nothing is hardcoded: on
     * the Find X9 Ultra the front is expected to be id "1" (4096×3072), but the id comes from
     * enumeration every time.
     */
    fun pickFront(manager: CameraManager): TeleSelection? {
        val best = pickFrontBest(frontCandidatesOf(manager)) ?: return null
        return TeleSelection(best.id, null, equivFocalOf(manager, best.id))
    }

    /**
     * Plain external-camera fallback for a device with no built-in rear/front route.
     *
     * External ids are never physical-output-routed. Prefer the external camera nearest the normal
     * 1× band when focal metadata exists, otherwise the first stable camera id.
     */
    internal fun pickExternal(manager: CameraManager): TeleSelection? {
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        val candidates = ids.mapNotNull { id ->
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: return@mapNotNull null
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_EXTERNAL) {
                return@mapNotNull null
            }
            TeleSelection(id, null, equivFocalOf(manager, id))
        }
        return pickClosest(candidates, LensChoice.MAIN.targetEquivMm)
    }

    /** Enumerates every LENS_FACING_FRONT id as a plainly-openable candidate (no physical routing). */
    fun frontCandidatesOf(manager: CameraManager): List<FrontCandidate> {
        val candidates = ArrayList<FrontCandidate>()
        // Same degrade-to-empty policy as candidatesOf: a camera-service hiccup must not crash.
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_FRONT) continue
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            candidates.add(
                FrontCandidate(
                    id = id,
                    logicalMultiCamera = chars.physicalCameraIds.isNotEmpty(),
                    activeArrayArea = (activeArray?.width()?.toLong() ?: 0L) * (activeArray?.height() ?: 0),
                ),
            )
        }
        return candidates
    }

    /**
     * Pure front-camera pick: prefer a PLAIN (non-logical) front id — the same "open the standalone,
     * never a routed/logical composite when a plain id exists" discipline as [pickBest] — then the
     * largest active array (the real sensor), then the lowest id for determinism. A logical front is
     * still returned when it is the only candidate (it is opened plainly, which is safe).
     */
    fun pickFrontBest(candidates: List<FrontCandidate>): FrontCandidate? =
        candidates.minWithOrNull(
            compareBy({ if (it.logicalMultiCamera) 1 else 0 }, { -it.activeArrayArea }, { it.id }),
        )

    private fun equivFocalOf(manager: CameraManager, id: String): Float {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return 0f
        val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = physical?.let { hypot(it.width, it.height) } ?: 0f
        return if (diag > 0f) focalMm * FULL_FRAME_DIAGONAL_MM / diag else focalMm
    }
}
