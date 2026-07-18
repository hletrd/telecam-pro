package com.hletrd.findx9tele.camera

import com.hletrd.findx9tele.gl.GlPipeline

/**
 * Owns renderer-only state across GL thread generations.
 *
 * Settings restore can call these setters before [GlPipeline.start], when handler posts are
 * intentionally dropped. Every setter therefore updates [config] before posting to GL, while
 * [replayAll] is the single generation-replay authority invoked by CameraEngine's input-ready
 * callback.
 */
internal class RendererAssists(private val gl: GlPipeline) {
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

    fun isPunchInEnabled(): Boolean = config.snapshot().punchIn

    fun setFalseColor(enabled: Boolean) {
        config.update { it.copy(falseColor = enabled) }
        gl.setFalseColor(enabled)
    }

    fun setPeaking(enabled: Boolean) {
        config.update { it.copy(peaking = enabled) }
        gl.setPeaking(enabled)
    }

    fun setZebra(enabled: Boolean) {
        config.update { it.copy(zebra = enabled) }
        gl.setZebra(enabled)
    }

    // Threshold and color share one GL call, so either setter replays both from one snapshot.
    private fun applyPeaking(snapshot: RendererConfig = config.snapshot()) =
        gl.setPeakingParams(
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
        gl.setZebraThreshold(level.threshold)
    }

    fun setAnalysis(histogram: Boolean, waveform: Boolean) {
        config.update { it.copy(histogram = histogram, waveform = waveform) }
        gl.setAnalysisEnabled(histogram, waveform)
    }

    /**
     * Change-gated because the ViewModel calls this for every control mutation, including
     * 60-120 Hz gestures, while the value normally changes only at a mode boundary.
     */
    fun setAeMetering(enabled: Boolean) {
        if (aeMetering == enabled) return
        aeMetering = enabled
        gl.setAeMetering(enabled)
    }

    fun setGammaAssist(enabled: Boolean) {
        gammaAssist = enabled
        gl.setGammaAssist(enabled)
    }

    fun setPunchIn(enabled: Boolean) {
        config.update { it.copy(punchIn = enabled) }
        gl.setPunchIn(enabled)
    }

    fun setTeleFinderIntent(enabled: Boolean) {
        teleFinderEnabled = enabled
    }

    fun isTeleFinderEnabled(): Boolean = teleFinderEnabled

    fun setTeleFinderResolved(enabled: Boolean) {
        config.update { it.copy(teleFinder = enabled) }
        gl.setTeleFinder(enabled)
    }

    /** Replays all desired handler-backed assists into exactly one fresh GL generation. */
    fun replayAll(snapshot: RendererConfig = config.snapshot()) {
        gl.setAeMetering(aeMetering)
        gl.setGammaAssist(gammaAssist)
        gl.setPeaking(snapshot.peaking)
        applyPeaking(snapshot)
        gl.setZebra(snapshot.zebra)
        gl.setZebraThreshold(snapshot.zebraLevel.threshold)
        gl.setFalseColor(snapshot.falseColor)
        gl.setAnalysisEnabled(snapshot.histogram, snapshot.waveform)
        gl.setPunchIn(snapshot.punchIn)
        gl.setTeleFinder(snapshot.teleFinder)
    }
}
