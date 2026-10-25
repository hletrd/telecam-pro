package me.hletrd.telecampro.video

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE PROBE (not a shipping assertion): what can THIS device's microphones deliver to a
 * third-party AudioRecord — which sources, sample formats, rates and channel layouts are GRANTED
 * (built and started at the requested shape), whether the platform declares UNPROCESSED capture, and
 * where each microphone sits (AudioManager.getMicrophones: position, orientation, channel mapping).
 * A grant is not resolution: PMA110's built-in input profiles are all 16-bit ≤ 48 kHz
 * (`dumpsys media.audio_policy`), so a granted float/24-bit/96 kHz record is converted from that.
 * ColorOS feeds an instrumentation process zeros after the first ~40 ms even with the app's own
 * context (PMA110, 2026-10-25), so the PCM statistics below say nothing about content here.
 *
 * Needs RECORD_AUDIO granted to the debug app. Never fails the build. Results:
 * `adb shell run-as <pkg> cat cache/audio_input_probe.txt`.
 */
@RunWith(AndroidJUnit4::class)
class AudioInputProbeTest {

    private val out = StringBuilder()
    private fun line(s: String) {
        out.append(s).append('\n')
    }

    @Test
    fun probe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val am = context.getSystemService(AudioManager::class.java)
        line("UNPROCESSED supported: ${am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}")
        runCatching {
            for (mic in am.microphones) {
                line(
                    "mic id=${mic.id} type=${mic.type} addr=${mic.address} desc=${mic.description} " +
                        "location=${mic.location} group=${mic.group}/${mic.indexInTheGroup} " +
                        "pos=(${mic.position.x},${mic.position.y},${mic.position.z}) " +
                        "orient=(${mic.orientation.x},${mic.orientation.y},${mic.orientation.z}) " +
                        "dir=${mic.directionality} sens=${mic.sensitivity} maxSpl=${mic.maxSpl} " +
                        "channels=${mic.channelMapping.map { "${it.first}:${it.second}" }}",
                )
            }
        }.onFailure { line("getMicrophones failed: $it") }
        for (dev in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            line(
                "input dev type=${dev.type} name=${dev.productName} addr=${dev.address} " +
                    "rates=${dev.sampleRates.toList()} counts=${dev.channelCounts.toList()} " +
                    "encodings=${dev.encodings.toList()} indexMasks=${dev.channelIndexMasks.toList()}",
            )
        }
        val sources = linkedMapOf(
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
            "VOICE_PERFORMANCE" to MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            "MIC" to MediaRecorder.AudioSource.MIC,
        )
        val encodings = linkedMapOf(
            "PCM16" to AudioFormat.ENCODING_PCM_16BIT,
            "PCM24" to AudioFormat.ENCODING_PCM_24BIT_PACKED,
            "PCM32" to AudioFormat.ENCODING_PCM_32BIT,
            "FLOAT" to AudioFormat.ENCODING_PCM_FLOAT,
        )
        for ((sourceName, source) in sources) {
            for ((encName, enc) in encodings) {
                for (rate in intArrayOf(48_000, 96_000)) {
                    line("$sourceName $encName ${rate / 1000}k stereo: ${tryRecord(source, enc, rate, stereoMask = true)}")
                }
            }
            // Four-channel index mask: every capsule, unmixed, if the HAL grants it.
            line("$sourceName PCM16 48k index4: ${tryRecord(source, AudioFormat.ENCODING_PCM_16BIT, 48_000, stereoMask = false)}")
        }
        val file = File(context.cacheDir, "audio_input_probe.txt")
        file.writeText(out.toString())
        Log.i("AudioInputProbe", "wrote ${file.absolutePath}")
    }

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tryRecord(source: Int, encoding: Int, rate: Int, stereoMask: Boolean): String {
        val builder = AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding)
        if (stereoMask) builder.setChannelMask(AudioFormat.CHANNEL_IN_STEREO) else builder.setChannelIndexMask(0xF)
        val format = runCatching { builder.build() }.getOrElse { return "format rejected" }
        @Suppress("MissingPermission")
        val record = runCatching {
            // setContext: attribute to the app package (it holds RECORD_AUDIO); the test APK is fed zeros.
            AudioRecord.Builder().setContext(appContext).setAudioSource(source).setAudioFormat(format)
                .setBufferSizeInBytes(rate * 8 * 4).build()
        }.getOrElse { return "build refused (${it.javaClass.simpleName})" }
        return try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return "uninitialized"
            record.startRecording()
            val buf = ByteBuffer.allocateDirect(rate * 8 * 4 / 2).order(ByteOrder.nativeOrder())
            var total = 0
            val deadline = System.nanoTime() + 1_500_000_000L
            while (total < buf.capacity() && System.nanoTime() < deadline) {
                val n = record.read(buf, buf.capacity() - total, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) return "read error $n"
                total += n
                buf.position(total)
                Thread.sleep(20)
            }
            buf.flip()
            var nonZero = 0
            var max = 0.0
            when (record.format.encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val f = buf.asFloatBuffer()
                    while (f.hasRemaining()) {
                        val v = kotlin.math.abs(f.get().toDouble())
                        if (v > 0) nonZero++
                        max = maxOf(max, v)
                    }
                }
                AudioFormat.ENCODING_PCM_16BIT -> {
                    val sb = buf.asShortBuffer()
                    while (sb.hasRemaining()) {
                        val v = kotlin.math.abs(sb.get().toInt()) / 32768.0
                        if (v > 0) nonZero++
                        max = maxOf(max, v)
                    }
                }
                else -> nonZero = -1
            }
            "OK got=${record.format.sampleRate}Hz enc=${record.format.encoding} ch=${record.channelCount} " +
                "bytes=$total nonZero=${if (nonZero >= 0) "%.0f%%".format(100.0 * nonZero / maxOf(1, total / (if (record.format.encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2))) else "n/a"} peak=${"%.4f".format(max)} routed=${record.routedDevice?.productName}/${record.routedDevice?.type}"
        } catch (t: Exception) {
            "error ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
