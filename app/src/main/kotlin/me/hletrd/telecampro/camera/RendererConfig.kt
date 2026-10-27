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
     * Whether the CAMERA stream is S-Log3-encoded by the ISP's own programmed tone curve (a native-log
     * session — NativeLogCurve.kt). Route state replayed with [sourceHlg] for the same reason: a fresh
     * GL generation defaulting to false would encode the log stream as if it were display-referred
     * 709 — log applied twice, a plausible-looking wrong file.
     */
    val sourceLog: Boolean = false,
    val sourceMaker: Boolean = false,
    /**
     * Which maker curve the monitor path inverts for that source (gl/MakerLogSource.kt `curveFor`):
     * O-Log2 by default, "as delivered" for the experimental vivo session. Replayed with [sourceMaker]:
     * a fresh GL generation defaulting to O-Log2 would run a vivo source through OPPO's inverse.
     */
    val sourceMakerCurve: Int = me.hletrd.telecampro.gl.MakerLogSource.CURVE_OLOG2,
    /**
     * The ACHIEVED Log EI offset factor k (≥ 1) of the native-log repeating request: the scene-linear
     * ceiling of the GL native decode the log encode branches read (NativeLogCurve.kt). 1 = no
     * offset, which takes the historical decode path exactly. Replayed with [sourceLog] for the
     * same reason: a fresh generation defaulting to 1 would re-clip the headroom the wire bought.
     */
    val sourceLogHeadroom: Float = 1f,
    /**
     * Whether the accepted session's S-Log3 source is the EXPERIMENTAL RAW log developer's
     * (S-Gamut3.Cine primaries, camera/RawLogBridge.kt), and the buffer transform its EGL frames lack
     * (gl/SourceTransformEmulation.kt flags). Route state replayed with [sourceLog]: a fresh GL
     * generation defaulting to false/0 would read wide primaries as sRGB and draw the field sideways.
     */
    val sourceWide: Boolean = false,
    val sourceTransformEmulation: Int = 0,
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
