package me.hletrd.telecampro.camera

/**
 * Desired renderer-only state, independent of any particular GL thread generation.
 *
 * Settings restore runs before [me.hletrd.telecampro.gl.GlPipeline.start], so sending only
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
    /**
     * Whether the CAMERA stream is a 10-bit HLG-encoded buffer (HLG10 / DV session).
     *
     * Route state, not an assist — but it lives in this snapshot for the same reason the assists do:
     * a GL generation replacement re-creates the renderer with defaults, and a value pushed only as
     * a handler message is silently lost. That is exactly how the first attempt at this failed
     * (2026-07-29): the push logged fine and the output never changed.
     */
    val sourceHlg: Boolean = false,
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
