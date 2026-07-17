package com.hletrd.findx9tele.camera

/**
 * Desired renderer-only state, independent of any particular GL thread generation.
 *
 * Settings restore runs before [com.hletrd.findx9tele.gl.GlPipeline.start], so sending only
 * handler messages loses the values. [RendererConfigStore] remains authoritative while the GL
 * handler is absent and lets each new pipeline generation replay one complete snapshot.
 */
internal data class RendererConfig(
    val peaking: Boolean = false,
    val peakingLevel: PeakingLevel = PeakingLevel.MEDIUM,
    val peakingColor: PeakingColor = PeakingColor.MAGENTA,
    val zebra: Boolean = false,
    val zebraLevel: ZebraLevel = ZebraLevel.IRE95,
    val falseColor: Boolean = false,
    val histogram: Boolean = false,
    val waveform: Boolean = false,
    val punchIn: Boolean = false,
    val teleFinder: Boolean = false,
)

/** Thread-safe copy-on-write owner for [RendererConfig]. */
internal class RendererConfigStore(initial: RendererConfig = RendererConfig()) {
    @Volatile
    private var current = initial

    fun snapshot(): RendererConfig = current

    @Synchronized
    fun update(block: (RendererConfig) -> RendererConfig): RendererConfig =
        block(current).also { current = it }
}
