package me.hletrd.telecampro.camera

import me.hletrd.telecampro.gl.GlPipeline
import java.util.concurrent.atomic.AtomicReference

internal data class MotionEvidenceReplay(
    val armed: Boolean,
    val rotationProvider: ((Long, Long) -> FloatArray?)?,
    val evidenceEpoch: Long,
)

/** Atomic publication of the three values that define one motion-evidence generation. */
internal class MotionEvidenceReplayStore {
    private val state = AtomicReference(MotionEvidenceReplay(false, null, 0L))

    fun publish(
        armed: Boolean,
        rotationProvider: (Long, Long) -> FloatArray?,
        resetEvidence: Boolean,
    ): MotionEvidenceReplay {
        while (true) {
            val old = state.get()
            val newEpoch = if (resetEvidence || old.armed != armed ||
                old.rotationProvider !== rotationProvider
            ) {
                old.evidenceEpoch + 1L
            } else {
                old.evidenceEpoch
            }
            val next = MotionEvidenceReplay(armed, rotationProvider, newEpoch)
            if (state.compareAndSet(old, next)) return next
        }
    }

    fun snapshot(): MotionEvidenceReplay = state.get()
}

/**
 * Owns renderer-only state across GL thread generations.
 *
 * Settings restore can call these setters before [GlPipeline.start], when handler posts are
 * intentionally dropped. Every setter therefore updates [config] before posting to GL, while
 * [replayAll] is the single generation-replay authority invoked by CameraEngine's input-ready
 * callback.
 */
internal class RendererAssists(private val currentGl: () -> GlPipeline) {
    private val config = RendererConfigStore()

    // Remembered independently from RendererConfig because only the resolved
    // toggle && TELE && PHOTO && 4:3 value is ever pushed to GL or replayed.
    @Volatile
    private var teleFinderEnabled = false

    // Remembered so replayAll can re-seed values set before the GL thread exists.
    @Volatile
    private var aeMetering = false

    @Volatile
    private var gammaAssist = false

    // Remembered for the same reason as [aeMetering]: GlPipeline.post is a silent no-op before
    // start(), so a value set during settings restore would otherwise only take effect after the
    // first GL restart — the exact shape of the old log-preview bug.
    @Volatile
    private var focusDetail = false
    private val motionEvidence = MotionEvidenceReplayStore()

    // The user's punch-in INTENT, remembered independently of the resolved value for the same
    // reason as [teleFinderEnabled]: only the route-resolved flag is pushed to GL or replayed, but
    // the intent has to survive a front trip so returning to the rear restores the loupe.
    @Volatile
    private var punchInEnabled = false

    /** The RESOLVED punch-in actually applied to the preview — false on the front route. */
    fun isPunchInEnabled(): Boolean = config.snapshot().punchIn

    /** The user's toggle, regardless of whether the current route applies it. */
    fun isPunchInIntended(): Boolean = punchInEnabled

    fun setPunchInIntent(enabled: Boolean) {
        punchInEnabled = enabled
    }

    fun setPunchInResolved(resolved: Boolean) {
        config.update { it.copy(punchIn = resolved) }
        currentGl().setPunchIn(resolved)
    }

    fun setFalseColor(enabled: Boolean) {
        config.update { it.copy(falseColor = enabled) }
        currentGl().setFalseColor(enabled)
    }

    fun setPeaking(enabled: Boolean) {
        config.update { it.copy(peaking = enabled) }
        currentGl().setPeaking(enabled)
    }

    fun setZebra(enabled: Boolean) {
        config.update { it.copy(zebra = enabled) }
        currentGl().setZebra(enabled)
    }

    // Threshold and color share one GL call, so either setter replays both from one snapshot.
    private fun applyPeaking(
        snapshot: RendererConfig = config.snapshot(),
        gl: GlPipeline = currentGl(),
    ) = gl.setPeakingParams(
        snapshot.peakingLevel.threshold,
        snapshot.peakingColor.r,
        snapshot.peakingColor.g,
        snapshot.peakingColor.b,
    )

    fun setPeakingLevel(level: PeakingLevel) {
        val snapshot = config.update { it.copy(peakingLevel = level) }
        applyPeaking(snapshot)
    }

    fun setPeakingColor(color: PeakingColor) {
        val snapshot = config.update { it.copy(peakingColor = color) }
        applyPeaking(snapshot)
    }

    fun setZebraLevel(level: ZebraLevel) {
        config.update { it.copy(zebraLevel = level) }
        currentGl().setZebraThreshold(level.threshold)
    }

    fun setAnalysis(histogram: Boolean, waveform: Boolean) {
        config.update { it.copy(histogram = histogram, waveform = waveform) }
        currentGl().setAnalysisEnabled(histogram, waveform)
    }

    /**
     * Change-gated because the ViewModel calls this for every control mutation, including
     * 60-120 Hz gestures, while the value normally changes only at a mode boundary.
     */
    fun setAeMetering(enabled: Boolean) {
        if (aeMetering == enabled) return
        aeMetering = enabled
        currentGl().setAeMetering(enabled)
    }

    /**
     * Change-gated for the same reason as [setAeMetering]: the ViewModel recomputes this on every
     * control mutation (including 60-120 Hz gestures) while the value only moves at a focus-mode or
     * recording boundary.
     */
    fun setFocusDetail(enabled: Boolean) {
        if (focusDetail == enabled) return
        focusDetail = enabled
        currentGl().setFocusDetailEnabled(enabled)
    }

    /**
     * Arms/disarms the motion-inversion rider, carrying the gyro drain with it.
     *
     * Lives here rather than as engine state because it is REPLAYED STATE: every GL generation must
     * be re-armed, and [replayAll] is the single authority that guarantees it. The provider is
     * re-supplied on each call so a replay after a GL restart binds the live drain rather than a
     * captured stale one.
     */
    fun setMotionInversion(
        enabled: Boolean,
        rotationProvider: (Long, Long) -> FloatArray?,
        resetEvidence: Boolean = false,
    ) {
        val replay = motionEvidence.publish(enabled, rotationProvider, resetEvidence)
        currentGl().setMotionInversionEnabled(
            replay.armed,
            replay.rotationProvider,
            replay.evidenceEpoch,
        )
    }

    fun setGammaAssist(enabled: Boolean) {
        gammaAssist = enabled
        currentGl().setGammaAssist(enabled)
    }

    fun setTeleFinderIntent(enabled: Boolean) {
        teleFinderEnabled = enabled
    }

    fun isTeleFinderEnabled(): Boolean = teleFinderEnabled

    /** How the ACCEPTED camera session encoded its buffers; selects the GL source linearisation. */
    fun setSourceHlg(enabled: Boolean) {
        config.update { it.copy(sourceHlg = enabled) }
        currentGl().setSourceHlg(enabled)
    }

    fun setTeleFinderResolved(enabled: Boolean) {
        config.update { it.copy(teleFinder = enabled) }
        currentGl().setTeleFinder(enabled)
    }

    /**
     * Change-gated: pushed from every finder re-resolve (zoom writes at pinch rate included), while
     * the value only moves at a TC toggle or a converter-profile change.
     */
    fun setFinderFieldScale(scale: Float) {
        if (config.snapshot().finderFieldScale == scale) return
        config.update { it.copy(finderFieldScale = scale) }
        currentGl().setFinderFieldScale(scale)
    }

    /** Layout-measured chrome overlap, as a fraction of preview height. See GlPipeline's setter. */
    fun setFinderBottomClearanceFraction(fraction: Float) {
        if (config.snapshot().finderBottomClearanceFraction == fraction) return
        config.update { it.copy(finderBottomClearanceFraction = fraction) }
        currentGl().setFinderBottomClearanceFraction(fraction)
    }

    /**
     * The window's rotation away from natural. Inert (0) on a portrait-locked phone, so the PMA110
     * preview path is unchanged; non-zero only on the large screens where Android 16+ ignores
     * `screenOrientation`.
     */
    fun setWindowRotation(degrees: Int) {
        val normalized = RotationMath.normalize(degrees)
        config.update { it.copy(windowRotationDeg = normalized) }
        currentGl().setWindowRotation(normalized)
    }

    /** The window rotation currently applied to the preview — read by tap mapping. */
    fun windowRotationDegrees(): Int = config.snapshot().windowRotationDeg

    /** Replays all desired handler-backed assists into exactly one fresh GL generation. */
    fun replayAll(
        gl: GlPipeline = currentGl(),
        snapshot: RendererConfig = config.snapshot(),
    ) {
        gl.setAeMetering(aeMetering)
        gl.setFocusDetailEnabled(focusDetail)
        // Replayed like every other assist. A GL restart drops the arming AND the frame history, so
        // the new generation starts pairing from scratch — correct, since frames either side of the
        // restart are not comparable anyway.
        val motion = motionEvidence.snapshot()
        motion.rotationProvider?.let {
            gl.setMotionInversionEnabled(motion.armed, it, motion.evidenceEpoch)
        }
        gl.setGammaAssist(gammaAssist)
        gl.setPeaking(snapshot.peaking)
        applyPeaking(snapshot, gl)
        gl.setZebra(snapshot.zebra)
        gl.setZebraThreshold(snapshot.zebraLevel.threshold)
        gl.setFalseColor(snapshot.falseColor)
        gl.setAnalysisEnabled(snapshot.histogram, snapshot.waveform)
        gl.setPunchIn(snapshot.punchIn)
        gl.setTeleFinder(snapshot.teleFinder)
        gl.setSourceHlg(snapshot.sourceHlg)
        gl.setFinderFieldScale(snapshot.finderFieldScale)
        gl.setFinderBottomClearanceFraction(snapshot.finderBottomClearanceFraction)
        gl.setWindowRotation(snapshot.windowRotationDeg)
    }
}
