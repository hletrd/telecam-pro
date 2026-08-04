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
    /**
     * The Loupe Overview's pretend-field scale (see loupeHintRect's fieldScale): the selected
     * converter's magnification while TELE, 1 otherwise. Route state in the replayed snapshot for
     * the same reason as [sourceHlg] — a fresh GL generation must not fall back to 1 and quietly
     * grow the hint by the converter ratio.
     */
    val finderFieldScale: Float = 1f,
    val finderBottomClearanceFraction: Float = 0f,
    /**
     * The app WINDOW's rotation away from the device's natural orientation (`Surface.ROTATION_*` in
     * degrees). 0 on any portrait-locked phone; non-zero only where Android 16+ ignores
     * `screenOrientation` (a display whose smaller side is >= 600dp), which hands this activity a
     * landscape window. Route state in the replayed snapshot for the same reason as [sourceHlg]: a
     * fresh GL generation must not silently fall back to 0 and draw the field sideways.
     *
     * Consumed ONLY by the preview and finder draws, via `FlipRenderer.draw`'s per-call
     * `rotationOverrideDeg`. It must never reach `setRotationDegrees` — see
     * [RotationMath.windowPreviewRotationDegrees] for why the encoder and analysis draws have to keep
     * framing by gravity instead of by window shape.
     */
    val windowRotationDeg: Int = 0,
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
