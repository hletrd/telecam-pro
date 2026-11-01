package me.hletrd.findx9tele.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import kotlin.concurrent.thread
import me.hletrd.telecampro.BuildConfig
import org.json.JSONObject

/**
 * Isolated DEBUG-only Camera2 probe. It never enters CameraEngine or the product UI.
 *
 * One exact stock-shaped attempt is allowed: a soft configure refusal and every device/encoder
 * failure are terminal evidence, not reasons to walk a fallback ladder. The encoder's first real
 * output buffer is the high-resolution first-frame witness; a SurfaceTexture preview callback
 * alone cannot prove that the 8K record stream arrived.
 */
class Samsung8kProbeActivity : Activity() {
    private lateinit var protocol: Samsung8kProbeProtocol
    private lateinit var evidence: Samsung8kProbeEvidence
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private val cameraExecutor = Executor { command ->
        if (::cameraHandler.isInitialized) cameraHandler.post(command)
    }

    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var encoderInputSurface: Surface? = null
    private var recordReader: ImageReader? = null
    private var recordWriter: ImageWriter? = null
    private var previewCallbackReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    private val audioPcmHandoff = Samsung8kPcmHandoff(AUDIO_PCM_QUEUE_CAPACITY)
    private val audioInputBufferIndices = ArrayDeque<Int>()
    private var muxer: MediaMuxer? = null
    private var videoFile: File? = null
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var encodedWidth = 0
    private var encodedHeight = 0
    private var firstAudioFrame = false

    private var surfaceAvailable = false
    private var openStarted = false
    @Volatile private var closed = false
    private var firstPreviewCallbackFrame = false
    private var firstRecordFrame = false
    private val openLifecycle = Samsung8kProbeOpenLifecycle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        val resolved = samsung8kProbeProtocolForTest(Build.MODEL)
        if (resolved == null) {
            finish()
            return
        }
        protocol = resolved
        val evidenceDirectory = File(getExternalFilesDir(null) ?: filesDir, "samsung-8k-probe")
        evidence = Samsung8kProbeEvidence(evidenceDirectory)
        evidence.record(
            "probe_start",
            mapOf(
                "model" to protocol.model,
                "cameraId" to protocol.cameraId,
                "recordingFps" to protocol.recordingFps,
                "evidenceFile" to evidence.file.absolutePath,
            ),
        )
        cameraThread = HandlerThread("Samsung8kProbe").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
        textureView = TextureView(this).also { view ->
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    surface.setDefaultBufferSize(protocol.previewSize.width, protocol.previewSize.height)
                    previewSurface = Surface(surface)
                    surfaceAvailable = true
                    maybeOpenCamera()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    surfaceAvailable = false
                    cameraHandler.post { closeProbe("preview_surface_destroyed") }
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
        setContentView(textureView)
        if (!hasProbePermissions()) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                PROBE_PERMISSION_REQUEST,
            )
        } else {
            maybeOpenCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PROBE_PERMISSION_REQUEST) return
        if (hasProbePermissions()) {
            maybeOpenCamera()
        } else {
            evidence.record("probe_permission_denied")
            finish()
        }
    }

    private fun hasProbePermissions(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onStop() {
        super.onStop()
        if (::cameraHandler.isInitialized) cameraHandler.post { closeProbe("activity_stop") }
    }

    override fun onDestroy() {
        if (::cameraHandler.isInitialized) {
            val quitAfterCleanup = openLifecycle.destroy()
            cameraHandler.post {
                closeProbe("activity_destroy")
                if (quitAfterCleanup) cameraThread.quitSafely()
            }
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // The hasProbePermissions guard above includes CAMERA.
    private fun maybeOpenCamera() {
        if (openStarted || closed || !surfaceAvailable || !hasProbePermissions()) return
        openStarted = true
        openLifecycle.openStarted()
        cameraHandler.post {
            if (closed) {
                if (openLifecycle.openTerminalReached()) cameraThread.quitSafely()
                return@post
            }
            evidence.record("open_camera", mapOf("cameraId" to protocol.cameraId))
            try {
                getSystemService(CameraManager::class.java).openCamera(
                    protocol.cameraId,
                    cameraStateCallback,
                    cameraHandler,
                )
            } catch (failure: Throwable) {
                val quitAfterFailure = openLifecycle.openTerminalReached()
                synchronousFailure("open_camera", failure)
                if (quitAfterFailure) cameraThread.quitSafely()
            }
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            val quitAfterClose = openLifecycle.openTerminalReached()
            if (closed) {
                device.close()
                if (quitAfterClose) cameraThread.quitSafely()
                return
            }
            camera = device
            evidence.record("camera_opened", mapOf("cameraId" to device.id))
            configureSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            val quitAfterClose = openLifecycle.openTerminalReached()
            evidence.record("camera_disconnected", mapOf("cameraId" to device.id))
            closeSamsung8kProbeOnTerminal(device) {
                fail("camera_disconnected", IllegalStateException("Camera disconnected"))
            }
            if (quitAfterClose) cameraThread.quitSafely()
        }

        override fun onError(device: CameraDevice, error: Int) {
            val quitAfterClose = openLifecycle.openTerminalReached()
            evidence.record("camera_device_error", mapOf("cameraId" to device.id, "error" to error))
            closeSamsung8kProbeOnTerminal(device) {
                fail("camera_device_error", IllegalStateException("Camera device error $error"))
            }
            if (quitAfterClose) cameraThread.quitSafely()
        }

        override fun onClosed(device: CameraDevice) {
            evidence.record("camera_closed", mapOf("cameraId" to device.id))
        }
    }

    private fun configureSession(device: CameraDevice) {
        if (!runSamsung8kProbeStage("create_image_readers", ::synchronousFailure) {
            createImageReaders()
        }) return
        if (!runSamsung8kProbeStage("create_encoder", ::synchronousFailure) {
            createEncoder()
        }) return
        if (!runSamsung8kProbeStage("create_audio_encoder", ::synchronousFailure) {
            createAudioEncoder()
        }) return
        try {
            val outputs = listOf(
                outputConfiguration(
                    surface = requireNotNull(previewSurface),
                    streamUseCase = protocol.previewStreamUseCase,
                ),
                outputConfiguration(
                    surface = requireNotNull(recordSurface),
                    streamUseCase = protocol.recordStreamUseCase,
                ),
                outputConfiguration(
                    surface = requireNotNull(previewCallbackReader).surface,
                    streamUseCase = protocol.previewStreamUseCase,
                ),
                outputConfiguration(
                    surface = requireNotNull(jpegReader).surface,
                    streamUseCase = protocol.captureStreamUseCase,
                ),
                outputConfiguration(
                    surface = requireNotNull(yuvReader).surface,
                    streamUseCase = protocol.captureStreamUseCase,
                ),
            )
            val sessionConfiguration = SessionConfiguration(
                protocol.sessionType,
                outputs,
                cameraExecutor,
                sessionStateCallback,
            )
            val sessionParameters = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            sessionParameters.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(protocol.recordingFps, protocol.recordingFps),
            )
            applySamsungParameters(sessionParameters, protocol.sessionParameters)
            // Camera3-Device.setParameters is not public. Preserve the measured legacy fields as
            // named request keys for this probe rather than smuggling in that hidden entry point.
            applySamsungParameters(sessionParameters, protocol.legacySessionParameters)
            sessionConfiguration.setSessionParameters(sessionParameters.build())
            evidence.record("configure_attempt", configurationEvidence())
            device.createCaptureSession(sessionConfiguration)
        } catch (failure: Throwable) {
            synchronousFailure("create_capture_session", failure)
        }
    }

    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            if (closed) {
                session.close()
                return
            }
            captureSession = session
            evidence.record("configure_accepted", configurationEvidence())
            try {
                val request = requireNotNull(camera).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(requireNotNull(previewSurface))
                    addTarget(requireNotNull(recordSurface))
                    addTarget(requireNotNull(previewCallbackReader).surface)
                    set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(protocol.recordingFps, protocol.recordingFps),
                    )
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                    )
                    set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                    )
                    applySamsungParameters(this, protocol.repeatingParameters)
                }.build()
                session.setRepeatingRequest(request, null, cameraHandler)
                evidence.record("repeating_started")
                cameraHandler.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS)
            } catch (failure: Throwable) {
                synchronousFailure("set_repeating_request", failure)
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            evidence.record("configure_failed", configurationEvidence())
            fail("onConfigureFailed", IllegalStateException("Stock-shaped session rejected"))
        }
    }

    private val firstFrameTimeout = Runnable {
        if (!closed && !firstRecordFrame) {
            fail("first_record_frame_timeout", IllegalStateException("No real 8K encoder frame"))
        }
    }

    @SuppressLint("WrongConstant") // Stock uses the vendor 0x08c20000 auxiliary dataspace.
    private fun createImageReaders() {
        previewCallbackReader = ImageReader.Builder(
            protocol.previewCallbackSize.width,
            protocol.previewCallbackSize.height,
        )
            .setImageFormat(android.graphics.ImageFormat.YUV_420_888)
            .setMaxImages(3)
            .setDefaultDataSpace(protocol.auxiliaryDataspace)
            .build()
            .also { reader ->
                reader.setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val timestamp = image.timestamp
                    image.close()
                    if (!firstPreviewCallbackFrame) {
                        firstPreviewCallbackFrame = true
                        evidence.record("first_preview_callback_frame", mapOf("sensorTimestamp" to timestamp))
                    }
                }, cameraHandler)
            }
        jpegReader = ImageReader.Builder(protocol.jpegCaptureSize.width, protocol.jpegCaptureSize.height)
            .setImageFormat(android.graphics.ImageFormat.JPEG)
            .setMaxImages(5)
            .setDefaultDataSpace(protocol.auxiliaryDataspace)
            .build()
        yuvReader = ImageReader.Builder(protocol.yuvCaptureSize.width, protocol.yuvCaptureSize.height)
            .setImageFormat(android.graphics.ImageFormat.YUV_420_888)
            .setMaxImages(3)
            .setDefaultDataSpace(protocol.auxiliaryDataspace)
            .build()
    }

    @SuppressLint("WrongConstant") // Stock's record reader carries the measured 0x104 dataspace.
    private fun createEncoder() {
        val encoderInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isHardwareAccelerated && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
        val encoderInfo = encoderInfos.firstOrNull { info ->
            runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    .videoCapabilities?.isSizeSupported(protocol.recordSize.width, protocol.recordSize.height) == true
            }.getOrDefault(false)
        } ?: encoderInfos.firstOrNull()
        requireNotNull(encoderInfo) { "No hardware HEVC encoder for the 8K probe" }
        val outputFile = File(evidence.file.parentFile, "samsung8k-probe.mp4")
        videoFile = outputFile
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            protocol.recordSize.width,
            protocol.recordSize.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, RECORD_BITRATE_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, protocol.recordingFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(encoderInfo.name)
        videoCodec = codec
        val characteristics = getSystemService(CameraManager::class.java)
            .getCameraCharacteristics(protocol.cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val orientationHint = samsung8kRecordingRotationDegrees(sensorOrientation, displayDegrees)
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(orientationHint)
        }
        evidence.record(
            "recording_orientation",
            mapOf(
                "sensorOrientation" to sensorOrientation,
                "displayDegrees" to displayDegrees,
                "orientationHint" to orientationHint,
            ),
        )
        codec.setCallback(videoCodecCallback, cameraHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = codec.createInputSurface()
        codec.start()
        // OutputConfiguration has no public dataspace setter. The public reader/writer bridge is
        // therefore the record transport: the CAMERA stream carries the measured 0x104 dataspace,
        // and each detached hardware buffer is attached unchanged to the encoder input queue.
        val reader = ImageReader.Builder(protocol.recordSize.width, protocol.recordSize.height)
            .setImageFormat(android.graphics.ImageFormat.PRIVATE)
            .setMaxImages(8)
            .setDefaultDataSpace(protocol.recordDataspace)
            .setUsage(HardwareBuffer.USAGE_VIDEO_ENCODE)
            .build()
        recordReader = reader
        recordSurface = reader.surface
        recordWriter = ImageWriter.newInstance(
            requireNotNull(encoderInputSurface),
            4,
            android.graphics.ImageFormat.PRIVATE,
        )
        reader.setOnImageAvailableListener(::forwardRecordImage, cameraHandler)
        evidence.record(
            "encoder_started",
            mapOf(
                "encoder" to encoderInfo.name,
                "size" to sizeString(protocol.recordSize),
                "fps" to protocol.recordingFps,
                "recordTransport" to "ImageReader/ImageWriter",
                "recordDataspace" to hex(protocol.recordDataspace),
                "output" to outputFile.absolutePath,
            ),
        )
    }

    @SuppressLint("MissingPermission") // maybeOpenCamera admits this path only with RECORD_AUDIO.
    private fun createAudioEncoder() {
        val minBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "AudioRecord stereo buffer unavailable: $minBuffer" }
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(8192))
            .build()
        audioRecord = record
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord did not initialize" }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec = codec
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE_BPS)
        }
        codec.setCallback(audioCodecCallback, cameraHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        evidence.record(
            "audio_encoder_started",
            mapOf(
                "encoder" to codec.name,
                "sampleRate" to AUDIO_SAMPLE_RATE,
                "channels" to AUDIO_CHANNEL_COUNT,
                "bitrate" to AUDIO_BITRATE_BPS,
                "profile" to "AAC-LC",
            ),
        )
        audioRunning = true
        audioThread = thread(name = "Samsung8kProbeAudio") {
            try {
                runAudio(record, codec)
            } catch (failure: Throwable) {
                cameraHandler.post {
                    if (!closed) fail("audio_input", failure)
                }
            }
        }
    }

    private fun runAudio(record: AudioRecord, codec: MediaCodec) {
        if (!audioRunning) return
        record.startRecording()
        val audioStartNs = SystemClock.elapsedRealtimeNanos()
        evidence.record("audio_recording_started", mapOf("startNs" to audioStartNs))
        var totalSamples = 0L
        while (audioRunning) {
            val pcm = ByteArray(AUDIO_PCM_READ_BYTES)
            val read = record.read(pcm, 0, pcm.size)
            if (read > 0) {
                val presentationTimeUs =
                    (audioStartNs + totalSamples * 1_000_000_000L / AUDIO_SAMPLE_RATE) / 1_000L
                val packet = Samsung8kPcmPacket(pcm, read, presentationTimeUs)
                check(audioPcmHandoff.offer(packet)) { "AAC PCM handoff saturated" }
                totalSamples += read / (2 * AUDIO_CHANNEL_COUNT)
                // MediaCodec is asynchronous. Only its callback thread may pair an advertised input
                // index with this PCM; the recorder thread never dequeues or queues a codec buffer.
                cameraHandler.post { pumpAudioInput(codec) }
            } else if (read < 0) {
                if (audioRunning) throw IllegalStateException("AudioRecord read failed: $read")
                break
            }
        }
    }

    private fun pumpAudioInput(codec: MediaCodec) {
        if (closed) {
            audioInputBufferIndices.clear()
            return
        }
        try {
            while (audioInputBufferIndices.isNotEmpty()) {
                val packet = audioPcmHandoff.poll() ?: return
                val inputIndex = audioInputBufferIndices.removeFirst()
                val input = codec.getInputBuffer(inputIndex)
                if (input == null) {
                    codec.queueInputBuffer(inputIndex, 0, 0, packet.presentationTimeUs, 0)
                    continue
                }
                input.clear()
                check(packet.size <= input.remaining()) {
                    "AAC input buffer ${input.remaining()} smaller than PCM packet ${packet.size}"
                }
                input.put(packet.bytes, 0, packet.size)
                codec.queueInputBuffer(inputIndex, 0, packet.size, packet.presentationTimeUs, 0)
            }
        } catch (failure: Throwable) {
            fail("audio_input", failure)
        }
    }

    private fun forwardRecordImage(source: ImageReader) {
        val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return
        if (closed) {
            image.close()
            return
        }
        try {
            // queueInputImage owns an attached image; a refused image remains ours to close.
            requireNotNull(recordWriter).queueInputImage(image)
        } catch (failure: Throwable) {
            image.close()
            fail("record_bridge", failure)
        }
    }

    private val videoCodecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            var failure: Throwable? = null
            try {
                val realFrame = samsung8kProbeRealEncodedSample(info.size, info.flags)
                if (muxerStarted && realFrame) {
                    muxer?.writeSampleData(
                        videoTrackIndex,
                        requireNotNull(codec.getOutputBuffer(index)),
                        info,
                    )
                }
                if (realFrame && !firstRecordFrame) {
                    firstRecordFrame = true
                    evidence.record(
                        "first_real_record_frame",
                        mapOf(
                            "width" to encodedWidth,
                            "height" to encodedHeight,
                            "encodedBytes" to info.size,
                            "presentationTimeUs" to info.presentationTimeUs,
                        ),
                    )
                }
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            failure?.let { fail("encoder_output", it) }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            evidence.record("encoder_error", failureEvidence(error))
            fail("encoder_error", error)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            try {
                check(!muxerStarted) { "Encoder output format changed after muxer start" }
                encodedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                encodedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                videoTrackIndex = requireNotNull(muxer).addTrack(format)
                maybeStartMuxer()
                evidence.record(
                    "encoder_output_format",
                    mapOf(
                        "width" to encodedWidth,
                        "height" to encodedHeight,
                        "format" to format.toString(),
                    ),
                )
            } catch (failure: Throwable) {
                fail("encoder_output_format", failure)
            }
        }
    }

    private val audioCodecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (closed) return
            audioInputBufferIndices.addLast(index)
            pumpAudioInput(codec)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            var failure: Throwable? = null
            try {
                val realFrame = samsung8kProbeRealEncodedSample(info.size, info.flags)
                if (!closed && muxerStarted && realFrame) {
                    muxer?.writeSampleData(
                        audioTrackIndex,
                        requireNotNull(codec.getOutputBuffer(index)),
                        info,
                    )
                    if (!firstAudioFrame) {
                        firstAudioFrame = true
                        evidence.record(
                            "first_real_audio_frame",
                            mapOf(
                                "encodedBytes" to info.size,
                                "presentationTimeUs" to info.presentationTimeUs,
                            ),
                        )
                    }
                }
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            failure?.let { fail("audio_encoder_output", it) }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            evidence.record("audio_encoder_error", failureEvidence(error))
            fail("audio_encoder_error", error)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (closed) return
            try {
                check(!muxerStarted) { "Audio output format changed after muxer start" }
                audioTrackIndex = requireNotNull(muxer).addTrack(format)
                evidence.record("audio_output_format", mapOf("format" to format.toString()))
                maybeStartMuxer()
            } catch (failure: Throwable) {
                fail("audio_output_format", failure)
            }
        }
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || !samsung8kProbeMuxerReady(videoTrackIndex, audioTrackIndex)) return
        requireNotNull(muxer).start()
        muxerStarted = true
        evidence.record(
            "muxer_started",
            mapOf("videoTrack" to videoTrackIndex, "audioTrack" to audioTrackIndex),
        )
    }

    @SuppressLint("WrongConstant") // Samsung's measured 0x410000 vendor stream use case.
    private fun outputConfiguration(
        surface: Surface,
        streamUseCase: Int,
    ): OutputConfiguration = OutputConfiguration(surface).apply {
        setStreamUseCase(streamUseCase.toLong())
    }

    private fun applySamsungParameters(
        builder: CaptureRequest.Builder,
        parameters: Map<String, Any>,
    ) {
        parameters.forEach { (name, value) ->
            when (value) {
                is Boolean -> builder.set(CaptureRequest.Key(name, Boolean::class.javaObjectType), value)
                is Byte -> builder.set(CaptureRequest.Key(name, Byte::class.javaObjectType), value)
                is Int -> builder.set(CaptureRequest.Key(name, Int::class.javaObjectType), value)
                is Long -> builder.set(CaptureRequest.Key(name, Long::class.javaObjectType), value)
                is Float -> builder.set(CaptureRequest.Key(name, Float::class.javaObjectType), value)
                is String -> builder.set(CaptureRequest.Key(name, String::class.java), value)
                else -> throw IllegalArgumentException("Unsupported Samsung parameter ${name}: ${value.javaClass}")
            }
        }
    }

    private fun configurationEvidence(): Map<String, Any?> = mapOf(
        "sessionType" to protocol.sessionType,
        "operationMode" to 0,
        "physicalCameraId" to protocol.physicalCameraId,
        "streams" to listOf(
            streamEvidence("preview", protocol.previewSize, android.graphics.ImageFormat.PRIVATE,
                0x1, protocol.previewStreamUseCase, 0),
            streamEvidence("record", protocol.recordSize, android.graphics.ImageFormat.PRIVATE,
                protocol.recordStreamOption, protocol.recordStreamUseCase, protocol.recordDataspace),
            streamEvidence("preview_callback", protocol.previewCallbackSize,
                android.graphics.ImageFormat.YUV_420_888, 0x1,
                protocol.previewStreamUseCase, protocol.auxiliaryDataspace),
            streamEvidence("jpeg_capture", protocol.jpegCaptureSize, android.graphics.ImageFormat.JPEG,
                0x2, protocol.captureStreamUseCase, protocol.auxiliaryDataspace),
            streamEvidence("yuv_capture", protocol.yuvCaptureSize, android.graphics.ImageFormat.YUV_420_888,
                0x2, protocol.captureStreamUseCase, protocol.auxiliaryDataspace),
        ),
        "vendorSessionParameters" to protocol.sessionParameters,
        "legacySessionParameters" to protocol.legacySessionParameters,
        "repeatingParameters" to protocol.repeatingParameters,
    )

    private fun streamEvidence(
        role: String,
        size: Size,
        format: Int,
        option: Int,
        useCase: Int,
        dataspace: Int,
    ): Map<String, Any?> = mapOf(
        "role" to role,
        "size" to sizeString(size),
        "format" to hex(format),
        "streamOption" to hex(option),
        "streamUseCase" to hex(useCase),
        "dataspace" to hex(dataspace),
        "physicalCameraId" to null,
    )

    private fun synchronousFailure(stage: String, failure: Throwable) {
        evidence.record("synchronous_error", failureEvidence(failure) + ("stage" to stage))
        fail(stage, failure)
    }

    private fun fail(stage: String, failure: Throwable) {
        if (closed) return
        evidence.record("terminal_failure", failureEvidence(failure) + ("stage" to stage))
        closeProbe(stage)
        runOnUiThread { finish() }
    }

    private fun closeProbe(reason: String) {
        if (closed) return
        closed = true
        evidence.record("stop", mapOf("reason" to reason))
        runCatching { captureSession?.close() }
        runCatching { camera?.close() }
        runCatching { recordReader?.setOnImageAvailableListener(null, null) }
        runCatching { recordReader?.close() }
        runCatching { recordWriter?.close() }
        runCatching { previewCallbackReader?.close() }
        runCatching { jpegReader?.close() }
        runCatching { yuvReader?.close() }
        runCatching { previewSurface?.release() }
        audioRunning = false
        runCatching { audioRecord?.stop() }
        if (samsung8kProbeJoinAudioWorker(audioThread, timeoutMs = 1_000)) {
            runCatching { audioRecord?.release() }
            runCatching { audioCodec?.stop() }
                .onFailure { evidence.record("audio_codec_stop_error", failureEvidence(it)) }
            runCatching { audioCodec?.release() }
        } else {
            // A live worker may still be inside AudioRecord.read, so its native owners cannot be
            // released safely. Abandon both rather than racing the unterminated thread.
            evidence.record(
                "audio_native_owners_abandoned",
                mapOf("audioThreadAlive" to (audioThread?.isAlive == true)),
            )
        }
        runCatching { videoCodec?.stop() }
            .onFailure { evidence.record("codec_stop_error", failureEvidence(it)) }
        runCatching { encoderInputSurface?.release() }
        runCatching { videoCodec?.release() }
        if (muxerStarted) {
            runCatching { muxer?.stop() }
                .onFailure { evidence.record("muxer_stop_error", failureEvidence(it)) }
        }
        runCatching { muxer?.release() }
        evidence.record(
            "stopped",
            mapOf(
                "reason" to reason,
                "firstRecordFrame" to firstRecordFrame,
                "firstAudioFrame" to firstAudioFrame,
                "output" to videoFile?.absolutePath,
                "outputBytes" to (videoFile?.takeIf(File::exists)?.length() ?: 0L),
            ),
        )
    }

    private companion object {
        const val PROBE_PERMISSION_REQUEST = 71
        const val RECORD_BITRATE_BPS = 100_000_000
        const val AUDIO_SAMPLE_RATE = 48_000
        const val AUDIO_CHANNEL_COUNT = 2
        const val AUDIO_BITRATE_BPS = 192_000
        const val AUDIO_PCM_READ_BYTES = 4_096
        const val AUDIO_PCM_QUEUE_CAPACITY = 16
        const val FIRST_FRAME_TIMEOUT_MS = 15_000L
    }
}

internal data class Samsung8kPcmPacket(
    val bytes: ByteArray,
    val size: Int,
    val presentationTimeUs: Long,
)

/** Bounded, nonblocking recorder-to-codec handoff; saturation is terminal evidence, never a wait. */
internal class Samsung8kPcmHandoff(capacity: Int) {
    private val queue = ArrayBlockingQueue<Samsung8kPcmPacket>(capacity)

    fun offer(packet: Samsung8kPcmPacket): Boolean = queue.offer(packet)

    fun poll(): Samsung8kPcmPacket? = queue.poll()
}

internal fun samsung8kProbeJoinAudioWorker(worker: Thread?, timeoutMs: Long): Boolean {
    if (worker == null) return true
    return try {
        worker.join(timeoutMs)
        !worker.isAlive
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

/** Tracks the one outstanding open so Activity teardown cannot strand its callback device. */
internal class Samsung8kProbeOpenLifecycle {
    private var openInFlight = false
    private var destroyed = false

    @Synchronized
    fun openStarted() {
        openInFlight = true
    }

    /** Returns true only when destruction is waiting for this terminal callback. */
    @Synchronized
    fun openTerminalReached(): Boolean {
        openInFlight = false
        return destroyed
    }

    /** Returns true when no outstanding open needs the callback thread kept alive. */
    @Synchronized
    fun destroy(): Boolean {
        destroyed = true
        return !openInFlight
    }
}

internal fun closeSamsung8kProbeOnTerminal(device: AutoCloseable, failureCleanup: () -> Unit) {
    // onError/onDisconnected can arrive before onOpened installs the Activity's camera field.
    runCatching { device.close() }
    failureCleanup()
}

internal inline fun runSamsung8kProbeStage(
    stage: String,
    onFailure: (String, Throwable) -> Unit,
    block: () -> Unit,
): Boolean = try {
    block()
    true
} catch (failure: Throwable) {
    onFailure(stage, failure)
    false
}

private class Samsung8kProbeEvidence(directory: File) {
    val file = File(directory, "samsung8k-probe-evidence.jsonl")
    private val startedAt = SystemClock.elapsedRealtime()

    init {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("")
        }.onFailure { Log.e(TAG, "Cannot initialize evidence file", it) }
    }

    @Synchronized
    fun record(event: String, details: Map<String, Any?> = emptyMap()) {
        val row = JSONObject()
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
            .put("event", event)
        details.forEach { (key, value) -> row.put(key, value ?: JSONObject.NULL) }
        runCatching { file.appendText(row.toString() + "\n") }
            .onFailure { Log.e(TAG, "Cannot append probe evidence", it) }
        Log.i(TAG, row.toString())
    }

    private companion object {
        const val TAG = "Samsung8kProbe"
    }
}

internal fun samsung8kProbeMuxerReady(videoTrack: Int, audioTrack: Int): Boolean =
    videoTrack >= 0 && audioTrack >= 0

internal fun samsung8kProbeRealEncodedSample(size: Int, flags: Int): Boolean =
    size > 0 &&
        flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
        flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0

internal fun samsung8kRecordingRotationDegrees(sensorOrientation: Int, displayDegrees: Int): Int =
    (sensorOrientation - displayDegrees + 360) % 360

private fun failureEvidence(failure: Throwable): Map<String, Any?> = mapOf(
    "class" to failure.javaClass.name,
    "message" to failure.message,
    "cameraAccessReason" to (failure as? CameraAccessException)?.reason,
)

private fun sizeString(size: Size): String = "${size.width}x${size.height}"

private fun hex(value: Int): String = "0x" + value.toString(16)
