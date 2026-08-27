package me.hletrd.telecampro.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import me.hletrd.telecampro.camera.ColorTransfer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal data class ShaderLocations(
    val aPosition: Int,
    val aTexCoord: Int,
    val uMvp: Int,
    val uTexMatrix: Int,
    val uTexture: Int,
    val uTransfer: Int,
    val uSourceHlg: Int,
    val uPeaking: Int,
    val uPeakThreshold: Int,
    val uPeakColor: Int,
    val uZebra: Int,
    val uZebraThreshold: Int,
    val uFalseColor: Int,
    val uTexel: Int,
    val uDigitalGain: Int,
)

internal fun resolveShaderLocations(
    attributeLookup: (String) -> Int,
    uniformLookup: (String) -> Int,
): ShaderLocations {
    fun required(kind: String, name: String, location: Int): Int {
        check(location >= 0) { "Required shader $kind '$name' is inactive or missing" }
        return location
    }
    fun attribute(name: String) = required("attribute", name, attributeLookup(name))
    fun uniform(name: String) = required("uniform", name, uniformLookup(name))
    return ShaderLocations(
        aPosition = attribute(ShaderBindings.A_POSITION),
        aTexCoord = attribute(ShaderBindings.A_TEX_COORD),
        uMvp = uniform(ShaderBindings.U_MVP),
        uTexMatrix = uniform(ShaderBindings.U_TEX_MATRIX),
        uTexture = uniform(ShaderBindings.U_TEXTURE),
        uTransfer = uniform(ShaderBindings.U_TRANSFER),
        uSourceHlg = uniform(ShaderBindings.U_SOURCE_HLG),
        uPeaking = uniform(ShaderBindings.U_PEAKING),
        uPeakThreshold = uniform(ShaderBindings.U_PEAK_THRESHOLD),
        uPeakColor = uniform(ShaderBindings.U_PEAK_COLOR),
        uZebra = uniform(ShaderBindings.U_ZEBRA),
        uZebraThreshold = uniform(ShaderBindings.U_ZEBRA_THRESHOLD),
        uFalseColor = uniform(ShaderBindings.U_FALSE_COLOR),
        uTexel = uniform(ShaderBindings.U_TEXEL),
        uDigitalGain = uniform(ShaderBindings.U_DIGITAL_GAIN),
    )
}

internal data class PendingGlObjects(
    var vertexShader: Int = 0,
    var fragmentShader: Int = 0,
    var program: Int = 0,
    var buffer: Int = 0,
    var texture: Int = 0,
)

/** Exact failure cleanup shared by shader compile/link and renderer initialization. */
internal fun releasePendingGlObjects(
    pending: PendingGlObjects,
    deleteShader: (Int) -> Unit,
    deleteProgram: (Int) -> Unit,
    deleteBuffer: (Int) -> Unit,
    deleteTexture: (Int) -> Unit,
) {
    if (pending.vertexShader != 0) deleteShader(pending.vertexShader)
    if (pending.fragmentShader != 0) deleteShader(pending.fragmentShader)
    if (pending.program != 0) deleteProgram(pending.program)
    if (pending.buffer != 0) deleteBuffer(pending.buffer)
    if (pending.texture != 0) deleteTexture(pending.texture)
    pending.vertexShader = 0
    pending.fragmentShader = 0
    pending.program = 0
    pending.buffer = 0
    pending.texture = 0
}

/** Narrow injectable GLES surface for transactional renderer initialization and release. */
internal interface FlipRendererGlApi {
    fun getError(): Int
    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun shaderCompileStatus(shader: Int): Int
    fun shaderInfoLog(shader: Int): String
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun programLinkStatus(program: Int): Int
    fun programInfoLog(program: Int): String
    fun attributeLocation(program: Int, name: String): Int
    fun uniformLocation(program: Int, name: String): Int
    fun generateBuffer(): Int
    fun bindBuffer(id: Int)
    fun uploadBuffer(data: FloatBuffer)
    fun generateTexture(): Int
    fun bindExternalTexture(id: Int)
    fun setExternalTextureParameter(name: Int, value: Int)
    fun deleteShader(id: Int)
    fun deleteProgram(id: Int)
    fun deleteBuffer(id: Int)
    fun deleteTexture(id: Int)
}

private object AndroidFlipRendererGlApi : FlipRendererGlApi {
    override fun getError(): Int = GLES20.glGetError()
    override fun createShader(type: Int): Int = GLES20.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES20.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES20.glCompileShader(shader)
    override fun shaderCompileStatus(shader: Int): Int = IntArray(1).also {
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, it, 0)
    }[0]
    override fun shaderInfoLog(shader: Int): String = GLES20.glGetShaderInfoLog(shader)
    override fun createProgram(): Int = GLES20.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun linkProgram(program: Int) = GLES20.glLinkProgram(program)
    override fun programLinkStatus(program: Int): Int = IntArray(1).also {
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, it, 0)
    }[0]
    override fun programInfoLog(program: Int): String = GLES20.glGetProgramInfoLog(program)
    override fun attributeLocation(program: Int, name: String): Int =
        GLES20.glGetAttribLocation(program, name)
    override fun uniformLocation(program: Int, name: String): Int =
        GLES20.glGetUniformLocation(program, name)
    override fun generateBuffer(): Int = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
    override fun bindBuffer(id: Int) = GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
    override fun uploadBuffer(data: FloatBuffer) = GLES20.glBufferData(
        GLES20.GL_ARRAY_BUFFER,
        data.capacity() * 4,
        data,
        GLES20.GL_STATIC_DRAW,
    )
    override fun generateTexture(): Int = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
    override fun bindExternalTexture(id: Int) =
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
    override fun setExternalTextureParameter(name: Int, value: Int) =
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, name, value)
    override fun deleteShader(id: Int) = GLES20.glDeleteShader(id)
    override fun deleteProgram(id: Int) = GLES20.glDeleteProgram(id)
    override fun deleteBuffer(id: Int) = GLES20.glDeleteBuffers(1, intArrayOf(id), 0)
    override fun deleteTexture(id: Int) = GLES20.glDeleteTextures(1, intArrayOf(id), 0)
}

/**
 * Clears inherited error state once, then attributes every later non-throwing GLES failure to the
 * exact initialization operation that produced it. GLES exposes allocation failure through this
 * flag, not through Kotlin exceptions, and a generated object name may still be non-zero.
 */
internal class GlOperationErrorBoundary(private val errorSource: () -> Int) {
    fun begin() {
        drainErrors()
    }

    fun check(operation: String) {
        val errors = drainErrors()
        check(errors.isEmpty()) {
            "$operation failed with GLES error(s) ${errors.joinToString { "0x%04x".format(it) }}"
        }
    }

    private fun drainErrors(): List<Int> {
        val errors = ArrayList<Int>(2)
        repeat(MAX_ERROR_DRAIN) {
            val error = errorSource()
            if (error == GLES20.GL_NO_ERROR) return errors
            errors += error
        }
        error("GLES error state did not clear after $MAX_ERROR_DRAIN reads")
    }

    private companion object {
        const val MAX_ERROR_DRAIN = 16
    }
}

/**
 * Draws the camera external-OES texture to whatever GL surface is current.
 *
 * Two transforms are combined:
 *  - texture coordinates are rotated by [setRotationDegrees]'s afocal-180-only angle about their
 *    center, then multiplied by the SurfaceTexture matrix (which already carries the sensor
 *    orientation — see the field comment below), so the *content* is un-inverted;
 *  - the quad geometry is scaled (center-crop "cover") so the content aspect fills the target
 *    without distortion, for whatever target (preview view or encoder) is being drawn.
 */
class FlipRenderer internal constructor(
    private val initializationGl: FlipRendererGlApi,
) {
    constructor() : this(AndroidFlipRendererGlApi)

    private var program = 0
    private var oesTextureId = 0

    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvp = 0
    private var uTexMatrix = 0
    private var uTexture = 0
    private var uTransfer = 0
    private var uSourceHlg = 0
    private var uPeaking = 0
    private var uPeakThreshold = 0
    private var uPeakColor = 0
    private var uZebra = 0
    private var uZebraThreshold = 0
    private var uFalseColor = 0
    private var uTexel = 0
    private var uDigitalGain = 0

    // Static quad geometry lives in ONE VBO uploaded in [init] (perf review #15): the client-side
    // glVertexAttribPointer form re-pinned and re-copied both arrays through JNI on every draw
    // (~96 draws/s at recording+loupe+finder). Layout: position quad at byte 0, plain texcoords at
    // [TEX_COORD_OFFSET_BYTES], mirrored-x texcoords at [TEX_COORD_MIRRORED_OFFSET_BYTES].
    // Selfie preview mirror: the attribute texcoords enter the rot chain BEFORE the SurfaceTexture
    // matrix, and attr x is display x, so inverting attr x here mirrors the DISPLAYED image
    // horizontally regardless of the sensor orientation the stMatrix bakes in (no sign guesswork
    // per sensor). The x→1−x inversion lives in the pure, tested [texCoordQuad].
    private var quadVbo = 0

    private val mvp = FloatArray(16)
    private val rot = FloatArray(16)
    private val texMatrix = FloatArray(16)
    // Reused (ex, ey) receiver for the per-draw cover-scale (PERF4-4; GL-thread confined).
    private val coverScratch = FloatArray(2)

    private var previewW = 1
    private var previewH = 1
    private var rotationDeg = 0
    private var sourceHlg = false
    // Rotation the camera SurfaceTexture transform already bakes into the sampled image (the sensor
    // orientation). It is NOT re-applied to texcoords (stMatrix does that), but it DOES decide the
    // displayed aspect: a ~90° sensor rotation swaps the shown width/height. Combined with rotationDeg
    // to pick the preview aspect. See CameraEngine.previewRotationDegrees.
    private var sensorOrientationDeg = 0

    /** Compiles the program and allocates the external texture. Must run with an EGL context current. */
    fun init(): Int {
        check(program == 0 && quadVbo == 0 && oesTextureId == 0) {
            "FlipRenderer.init requires released resource fields"
        }
        val pending = PendingGlObjects()
        val errors = GlOperationErrorBoundary(initializationGl::getError)
        errors.begin()
        try {
            pending.program = buildProgram(Shaders.VERTEX, Shaders.FRAGMENT)
            val locations = resolveShaderLocations(
                attributeLookup = { initializationGl.attributeLocation(pending.program, it) },
                uniformLookup = { initializationGl.uniformLocation(pending.program, it) },
            )
            errors.check("shader program initialization")

            // Fresh VBO per GL generation (same replay discipline as RendererConfigStore: init()
            // fully re-seeds everything a replacement context needs; release() deletes it).
            pending.buffer = initializationGl.generateBuffer()
            errors.check("glGenBuffers")
            check(pending.buffer != 0) { "VBO allocation failed" }
            val staging = floatBuffer(
                // x, y  (triangle strip)       | tex u,v (plain)      | tex u,v (mirrored x)
                floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f) +
                    texCoordQuad(mirrorX = false) +
                    texCoordQuad(mirrorX = true),
            )
            initializationGl.bindBuffer(pending.buffer)
            errors.check("glBindBuffer(VBO)")
            initializationGl.uploadBuffer(staging)
            errors.check("glBufferData")
            initializationGl.bindBuffer(0)
            errors.check("glBindBuffer(0)")

            pending.texture = initializationGl.generateTexture()
            errors.check("glGenTextures")
            check(pending.texture != 0) { "External texture allocation failed" }
            initializationGl.bindExternalTexture(pending.texture)
            errors.check("glBindTexture(GL_TEXTURE_EXTERNAL_OES)")
            initializationGl.setExternalTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            errors.check("glTexParameteri(GL_TEXTURE_MIN_FILTER)")
            initializationGl.setExternalTextureParameter(GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            errors.check("glTexParameteri(GL_TEXTURE_MAG_FILTER)")
            initializationGl.setExternalTextureParameter(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            errors.check("glTexParameteri(GL_TEXTURE_WRAP_S)")
            initializationGl.setExternalTextureParameter(GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            errors.check("glTexParameteri(GL_TEXTURE_WRAP_T)")
            initializationGl.bindExternalTexture(0)
            errors.check("glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)")

            // Transfer all ids only after every location and GL object is ready. A retry therefore
            // starts from zero fields and cannot overwrite an unreachable object from this attempt.
            program = pending.program
            quadVbo = pending.buffer
            oesTextureId = pending.texture
            aPosition = locations.aPosition
            aTexCoord = locations.aTexCoord
            uMvp = locations.uMvp
            uTexMatrix = locations.uTexMatrix
            uTexture = locations.uTexture
            uTransfer = locations.uTransfer
            uSourceHlg = locations.uSourceHlg
            uPeaking = locations.uPeaking
            uPeakThreshold = locations.uPeakThreshold
            uPeakColor = locations.uPeakColor
            uZebra = locations.uZebra
            uZebraThreshold = locations.uZebraThreshold
            uFalseColor = locations.uFalseColor
            uTexel = locations.uTexel
            uDigitalGain = locations.uDigitalGain
            pending.program = 0
            pending.buffer = 0
            pending.texture = 0
            return oesTextureId
        } finally {
            initializationGl.bindBuffer(0)
            initializationGl.bindExternalTexture(0)
            releasePendingGlObjects(
                pending = pending,
                deleteShader = initializationGl::deleteShader,
                deleteProgram = initializationGl::deleteProgram,
                deleteBuffer = initializationGl::deleteBuffer,
                deleteTexture = initializationGl::deleteTexture,
            )
        }
    }

    fun setPreviewSize(width: Int, height: Int) {
        previewW = width.coerceAtLeast(1)
        previewH = height.coerceAtLeast(1)
    }

    /** How the accepted camera session encoded its buffers; selects the shader source decode. */
    fun setSourceHlg(enabled: Boolean) {
        if (
            sourceHlg != enabled &&
            me.hletrd.telecampro.camera.recurringDiagnosticAllowed(me.hletrd.telecampro.BuildConfig.DEBUG)
        ) {
            android.util.Log.i("FlipRenderer", "sourceHlg -> $enabled (uniformLoc=$uSourceHlg)")
        }
        sourceHlg = enabled
    }

    /** Extra CW rotation applied to texcoords on top of the SurfaceTexture transform (afocal flip). */
    fun setRotationDegrees(deg: Int) {
        rotationDeg = ((deg % 360) + 360) % 360
    }

    /**
     * The shared content rotation state, so a caller can build a per-call [draw] `rotationOverrideDeg`
     * RELATIVE to it (the preview/finder draws add the window term on top). Reading it is the only
     * way to extend the rotation for one draw role without mutating state every role sees.
     */
    fun contentRotationDegrees(): Int = rotationDeg

    /** Sensor orientation the SurfaceTexture transform already applies; used only for aspect choice. */
    fun setSensorOrientation(deg: Int) {
        sensorOrientationDeg = ((deg % 360) + 360) % 360
    }

    fun draw(
        stMatrix: FloatArray,
        targetWidth: Int,
        targetHeight: Int,
        transfer: ColorTransfer?,
        peaking: Boolean,
        zebra: Boolean,
        falseColor: Boolean = false,
        stabShiftX: Float = 0f,
        stabShiftY: Float = 0f,
        stabRollDeg: Float = 0f,
        crop: Float = 0f,
        // Texcoord point the crop-zoom centers on (0.5,0.5 = frame center). The movable focus loupe
        // sets this to the tapped point so punch-in magnifies an off-center subject.
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        // Adjustable focus-peaking (edge threshold + highlight color) and zebra (clipping threshold).
        peakThreshold: Float = 0.06f,
        peakR: Float = 1f,
        peakG: Float = 0.1f,
        peakB: Float = 0.7f,
        zebraThreshold: Float = 0.95f,
        // Preview-only zoom compensation (requested ÷ HAL-applied): every setRepeatingRequest stalls
        // this HAL's stream ~180 ms, so the LIVE zoom renders here as a texture crop while the HAL
        // catches up at a throttled pace. ≥1 (can't sample beyond the frame); 1 = no-op.
        zoomComp: Float = 1f,
        // Preview-only brightness simulation (cycle 8): linear-light gain for the exposure
        // shortfall the fluidity-capped repeating request cannot carry. Encoder/analysis draw
        // roles keep the default 1 so files and the scope/AE readback never contain the boost.
        digitalGain: Float = 1f,
        // Viewport origin for sub-rect draws (the TELE finder PIP); callers scissor around the draw
        // because the internal glClear is framebuffer-wide otherwise.
        viewportX: Int = 0,
        viewportY: Int = 0,
        // Selfie mirror axis (route state, not an assist). WHICH draws set it derives from
        // FrontMirrorConvention resolves profile truth: PMA110 passes its pre-mirrored preview as-is
        // and un-mirrors encoder/analysis; GENERIC adds only the selfie preview mirror. Callers pass
        // the derived role, never a literal.
        mirrorX: Boolean = false,
        // Per-draw content-rotation override, in place of the shared [rotationDeg] field.
        //
        // Exists for ONE caller: the TELE corner overview deliberately omits the afocal 180° that
        // corrects the magnified main view. Today's overview re-draws the same converter-fed stream,
        // making it raw and inverted relative to the main view; only a
        // future true-wide source can be genuinely upright. Rotation is otherwise renderer STATE,
        // so this stays an explicit per-call opt-in rather than a settable field — null keeps the
        // shared value, and encoder/analysis/main-preview roles never pass it.
        rotationOverrideDeg: Int? = null,
    ) {
        val contentRotationDeg = rotationOverrideDeg ?: rotationDeg
        GLES20.glViewport(viewportX, viewportY, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Center-crop "cover" scale so the content aspect fills the target without distortion.
        // Allocation-free form (PERF4-4): draw runs 1-4x per frame (preview + encoder + finder +
        // analysis) and the Pair-returning coverScale boxed 3 objects per call in the hottest loop.
        coverScaleInto(coverScratch, previewW, previewH, sensorOrientationDeg, contentRotationDeg, targetWidth, targetHeight)
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, coverScratch[0], coverScratch[1], 1f)

        // Texcoord transform about center: content rotation (afocal 180° + sensor) + EIS roll,
        // crop-zoom for stabilization headroom, then EIS translation, then the SurfaceTexture matrix.
        Matrix.setIdentityM(rot, 0)
        Matrix.translateM(rot, 0, centerX + stabShiftX, centerY + stabShiftY, 0f)
        Matrix.rotateM(rot, 0, contentRotationDeg.toFloat() + stabRollDeg, 0f, 0f, 1f)
        val comp = zoomComp.coerceAtLeast(1f)
        Matrix.scaleM(rot, 0, (1f - crop) / comp, (1f - crop) / comp, 1f)
        Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rot, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1i(uTransfer, shaderTransferCode(transfer))
        // Route state: describes the FRAMES, so every draw role must agree. A field, not an
        // argument — preview, encoder, analysis and finder all read the same camera buffers.
        GLES20.glUniform1i(uSourceHlg, if (sourceHlg) 1 else 0)
        GLES20.glUniform1i(uPeaking, if (peaking) 1 else 0)
        GLES20.glUniform1f(uPeakThreshold, peakThreshold)
        GLES20.glUniform3f(uPeakColor, peakR, peakG, peakB)
        GLES20.glUniform1i(uZebra, if (zebra) 1 else 0)
        GLES20.glUniform1f(uZebraThreshold, zebraThreshold)
        GLES20.glUniform1i(uFalseColor, if (falseColor) 1 else 0)
        GLES20.glUniform2f(uTexel, 1f / previewW, 1f / previewH)
        GLES20.glUniform1f(uDigitalGain, digitalGain.coerceAtLeast(1f))

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(
            aTexCoord, 2, GLES20.GL_FLOAT, false, 0,
            if (mirrorX) TEX_COORD_MIRRORED_OFFSET_BYTES else TEX_COORD_OFFSET_BYTES,
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        // Leave no array-buffer binding behind: other GL code in this pipeline (FBO readback, hint
        // clears) assumes default binding state.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (program != 0) initializationGl.deleteProgram(program)
        if (oesTextureId != 0) initializationGl.deleteTexture(oesTextureId)
        if (quadVbo != 0) initializationGl.deleteBuffer(quadVbo)
        program = 0
        oesTextureId = 0
        quadVbo = 0
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val pending = PendingGlObjects()
        try {
            pending.vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
            pending.fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            pending.program = initializationGl.createProgram()
            check(pending.program != 0) { "Program allocation failed" }
            initializationGl.attachShader(pending.program, pending.vertexShader)
            initializationGl.attachShader(pending.program, pending.fragmentShader)
            initializationGl.linkProgram(pending.program)
            check(initializationGl.programLinkStatus(pending.program) == GLES20.GL_TRUE) {
                "Program link failed: ${initializationGl.programInfoLog(pending.program)}"
            }
            val transferred = pending.program
            pending.program = 0
            return transferred
        } finally {
            releasePendingGlObjects(
                pending = pending,
                deleteShader = initializationGl::deleteShader,
                deleteProgram = initializationGl::deleteProgram,
                deleteBuffer = initializationGl::deleteBuffer,
                deleteTexture = initializationGl::deleteTexture,
            )
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        val pending = PendingGlObjects(vertexShader = initializationGl.createShader(type))
        check(pending.vertexShader != 0) { "Shader allocation failed" }
        try {
            initializationGl.shaderSource(pending.vertexShader, src)
            initializationGl.compileShader(pending.vertexShader)
            check(initializationGl.shaderCompileStatus(pending.vertexShader) == GLES20.GL_TRUE) {
                "Shader compile failed: ${initializationGl.shaderInfoLog(pending.vertexShader)}"
            }
            val transferred = pending.vertexShader
            pending.vertexShader = 0
            return transferred
        } finally {
            releasePendingGlObjects(
                pending = pending,
                deleteShader = initializationGl::deleteShader,
                deleteProgram = initializationGl::deleteProgram,
                deleteBuffer = initializationGl::deleteBuffer,
                deleteTexture = initializationGl::deleteTexture,
            )
        }
    }

    private companion object {
        // Byte offsets into the one static [quadVbo]: 8 position floats (32 B), then the plain
        // texcoord quad, then the mirrored-x texcoord quad (8 floats / 32 B each).
        const val TEX_COORD_OFFSET_BYTES = 8 * 4
        const val TEX_COORD_MIRRORED_OFFSET_BYTES = 16 * 4
    }
}

/**
 * The quad's attribute texcoords (triangle-strip order, matching the −1..1 position quad), with an
 * optional horizontal mirror. Mirroring here — x→1−x per vertex, y untouched — happens BEFORE the
 * rot chain and the SurfaceTexture matrix, so it flips the DISPLAYED image about the display's
 * vertical axis for ANY sensor orientation (the selfie mirror convention). Pure and top-level so
 * the inversion is unit-testable without GL.
 */
internal fun texCoordQuad(mirrorX: Boolean): FloatArray {
    val plain = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    if (!mirrorX) return plain
    for (i in plain.indices step 2) plain[i] = 1f - plain[i]
    return plain
}

/**
 * Center-crop "cover" scale factors (ex, ey) for the quad geometry, extracted from [FlipRenderer.draw]
 * as pure Float math (no GL) so the aspect logic is unit-testable. Content aspect is taken AFTER all
 * rotation: the SurfaceTexture transform's sensor orientation PLUS the extra texcoord rotation, so a
 * net 90/270 swaps the displayed width/height. Exactly one axis is scaled >1 to overscan the target;
 * matching aspects return (1, 1). [targetHeight] is floored at 1 to avoid a divide-by-zero.
 */
/**
 * Clamps a punch-in/loupe center so the SAMPLED WINDOW stays inside the texture.
 *
 * [FlipRenderer.draw] samples `center ± halfExtent`, where the half-extent is
 * `((1 - crop) / zoomComp) / 2`. Clamping only the CENTER to 0..1 (which is all
 * `GlPipeline.setPunchInCenter` can do, since it does not know the live crop) still lets the window
 * run off the edge: at the default `PUNCH_IN_CROP` the half-extent is 0.2, so a center of 0 samples
 * from -0.2. Outside the texture the external sampler returns edge-clamped/garbage texels, which is
 * the smeared out-of-bounds artifact the operator sees after dragging the loupe repeatedly against
 * an edge (reported 2026-07-28) — the finder must show real scene or nothing.
 *
 * Returns 0.5 when the window is at least as large as the frame ([halfExtent] >= 0.5): there is then
 * no legal off-center position at all, and a naive `coerceIn(half, 1 - half)` would throw on an
 * inverted range.
 */
internal fun clampPunchInCenter(center: Float, crop: Float, zoomComp: Float): Float {
    val halfExtent = ((1f - crop.coerceIn(0f, 1f)) / zoomComp.coerceAtLeast(1f)) / 2f
    if (halfExtent >= 0.5f) return 0.5f
    return center.coerceIn(halfExtent, 1f - halfExtent)
}

internal fun coverScale(
    previewW: Int,
    previewH: Int,
    sensorOrientationDeg: Int,
    rotationDeg: Int,
    targetWidth: Int,
    targetHeight: Int,
): Pair<Float, Float> {
    val out = FloatArray(2)
    coverScaleInto(out, previewW, previewH, sensorOrientationDeg, rotationDeg, targetWidth, targetHeight)
    return out[0] to out[1]
}

/** Allocation-free form of [coverScale] for the per-frame draw loop: writes (ex, ey) into [out]. */
internal fun coverScaleInto(
    out: FloatArray,
    previewW: Int,
    previewH: Int,
    sensorOrientationDeg: Int,
    rotationDeg: Int,
    targetWidth: Int,
    targetHeight: Int,
) {
    val rotated = (sensorOrientationDeg + rotationDeg) % 180 == 90
    val displayedAspect = if (rotated) previewH.toFloat() / previewW else previewW.toFloat() / previewH
    val viewAspect = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
    var ex = 1f
    var ey = 1f
    if (displayedAspect > viewAspect) ex = displayedAspect / viewAspect else ey = viewAspect / displayedAspect
    out[0] = ex
    out[1] = ey
}

/**
 * ColorTransfer -> fragment-shader `uTransfer` branch code, as ONE exhaustive mapping. The old
 * inline `when` ended in `else -> 0`, so a future ColorTransfer member compiled clean while
 * silently rendering as SDR — unlike the container-tag side (ColorProfiles), where the exhaustive
 * `when` breaks the build.
 *
 * Code 3 is permanently vacant: it was the de-log branch for a scene-referred vendor stream that
 * can no longer arrive (that path was declined 2026-08-04). The remaining codes keep their original
 * numbers rather than closing the gap — renumbering would buy nothing and would silently
 * re-map every value these tests pin.
 */
internal fun shaderTransferCode(transfer: ColorTransfer?): Int = when {
    transfer == null -> 0 // preview/null path: camera frames are already SDR
    else -> when (transfer) {
        ColorTransfer.HLG -> 1
        ColorTransfer.SLOG3 -> 2
        ColorTransfer.SLOG3_CINE -> 4
        ColorTransfer.LOGC3 -> 5
        // SDR = no OETF, same as the null path.
        ColorTransfer.SDR -> 0
    }
}
