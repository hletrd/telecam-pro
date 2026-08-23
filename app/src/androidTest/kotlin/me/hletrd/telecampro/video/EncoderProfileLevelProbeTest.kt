package me.hletrd.telecampro.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.VideoCodec
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE PROBE (not a shipping assertion): which of the keys in [ColorProfiles.videoFormat] does
 * THIS device's HEVC encoder actually accept?
 *
 * Written because an Android 13 emulator (`c2.android.hevc.encoder`, the AOSP software encoder)
 * refused every recording with `configure failed BAD_VALUE` at 720p/30/8 Mbps — a shape the encoder
 * obviously supports — while the same binary records fine on the PMA110's QTI encoder. The logcat
 * showed the encoder renegotiating the LEVEL it was never given (`Given level 6000 does not cover
 * current configuration: adjusting to 6001 … 6004`) and then rejecting the resulting parameter set.
 *
 * That points at `KEY_PROFILE` being set without `KEY_LEVEL`, but "points at" is not proof, and the
 * fix (drop the profile? pair it with a level? gate the transfer?) differs per cause. So this probe
 * bisects it on hardware instead of guessing: it configures the SAME size/rate/bitrate five ways and
 * reports which succeed.
 *
 * Like every probe in this source set, it NEVER fails the build — a device that lacks the codec must not
 * break CI. Read the verdict with `adb logcat -s EncProbe`.
 */
@RunWith(AndroidJUnit4::class)
class EncoderProfileLevelProbeTest {

    private companion object {
        const val TAG = "EncProbe"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC
        const val W = 720
        const val H = 1280
        const val RATE = 30f
        const val BITRATE = 8_000_000
    }

    private fun base(): MediaFormat = MediaFormat.createVideoFormat(MIME, W, H).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        setFloat(MediaFormat.KEY_FRAME_RATE, RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }

    private fun sdrColor(f: MediaFormat) = f.apply {
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    }

    /** Configure-only: no input surface, no start. That is the exact call that was failing. */
    private fun tryConfigure(codecName: String, label: String, fmt: MediaFormat): Boolean {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.i(TAG, "PASS  component=$codecName  $label")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "FAIL  component=$codecName  $label  -> ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            runCatching { codec?.release() }
        }
    }

    @Test
    fun probeWhichKeysTheEncoderAccepts() {
        val infoByName = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .associateBy { it.name }
        val candidates = EncoderCaps.load().candidatesFor(VideoCodec.HEVC, ColorTransfer.SDR)
        if (candidates.isEmpty()) {
            Log.i(TAG, "VERDICT: no HEVC encoder on this device; nothing to probe")
            return
        }
        // Production retains every exact admitted component and creates each by name. Probe the
        // same hardware-first, registry-stable identity axis; type-selected creation can silently
        // inspect a different component from the capability token under test.
        for (selection in candidates) {
        val info = infoByName[selection.codecName]
        if (info == null) {
            Log.i(TAG, "SKIP  component=${selection.codecName} disappeared after inventory")
            continue
        }
        val caps = info.getCapabilitiesForType(MIME)
        Log.i(TAG, "encoder=${info.name} hardwareAccelerated=${info.isHardwareAccelerated}")
        val advertised = caps.profileLevels.map { it.profile to it.level }
        Log.i(TAG, "advertised profile/level pairs: $advertised")
        val mainLevels = caps.profileLevels
            .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
            .map { it.level }
        val main10Levels = caps.profileLevels
            .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            .map { it.level }
        Log.i(TAG, "Main levels=$mainLevels  Main10 levels=$main10Levels")
        Log.i(TAG, "Main10 SUPPORTED=${main10Levels.isNotEmpty()} (decides whether HLG/log transfers are honest here)")

        // 1. Exactly what the app ships today for SDR: profile pinned, level absent.
        val a = tryConfigure(info.name, "profile=Main, NO level, SDR color", sdrColor(base()).apply {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        })
        // 2. Same minus the profile — isolates the profile key from the color keys.
        val b = tryConfigure(info.name, "NO profile, NO level, SDR color", sdrColor(base()))
        // 3. Profile paired with the encoder's own highest advertised level for it.
        val c = if (mainLevels.isEmpty()) {
            Log.i(TAG, "SKIP  profile=Main + advertised level (encoder advertises no Main level)"); false
        } else {
            tryConfigure(info.name, "profile=Main + level=${mainLevels.max()}, SDR color", sdrColor(base()).apply {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, mainLevels.max())
            })
        }
        // 4. Bare format, no profile and no color keys — the floor case.
        val d = tryConfigure(info.name, "bare (no profile, no color keys)", base())
        // 5. The 10-bit shape four of the five gamma options ask for.
        val e = tryConfigure(info.name, "profile=Main10, NO level, HLG color", base().apply {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
        })

        Log.i(
            TAG,
            "VERDICT shipping(profile,noLevel)=$a  noProfile=$b  profile+level=$c  bare=$d  main10=$e",
        )

        // Everything above can fail at once, which refutes "it is the profile key" and moves the
        // question to the INPUT MODE and the SHAPE. Bisect those next.
        val vc = caps.videoCapabilities
        Log.i(
            TAG,
            "videoCaps widths=${vc?.supportedWidths} heights=${vc?.supportedHeights} " +
                "alignment=${vc?.widthAlignment}x${vc?.heightAlignment} " +
                "720x1280ok=${runCatching { vc?.isSizeSupported(720, 1280) }.getOrNull()} " +
                "1280x720ok=${runCatching { vc?.isSizeSupported(1280, 720) }.getOrNull()}",
        )
        Log.i(TAG, "colorFormats=${caps.colorFormats.joinToString()} (2135033992=Flexible, 2130708361=Surface)")
        val surfaceSupported = caps.colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }
        Log.i(TAG, "SURFACE INPUT ADVERTISED=$surfaceSupported")

        fun shape(label: String, w: Int, h: Int, colorFormat: Int) = tryConfigure(
            info.name,
            label,
            MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setFloat(MediaFormat.KEY_FRAME_RATE, RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            },
        )
        val surf = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        val flex = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        shape("portrait 720x1280 SURFACE", 720, 1280, surf)
        shape("landscape 1280x720 SURFACE", 1280, 720, surf)
        shape("small 640x480 SURFACE", 640, 480, surf)
        shape("portrait 720x1280 YUV420Flexible", 720, 1280, flex)
        shape("landscape 1280x720 YUV420Flexible", 1280, 720, flex)

        // Is the refusal about ORIENTATION (width must be the long edge) or about the HEIGHT cap?
        // The answer decides whether an aspect-preserving fallback exists at all.
        shape("portrait 480x854 SURFACE", 480, 854, surf)
        shape("portrait 480x640 SURFACE", 480, 640, surf)
        shape("portrait 360x640 SURFACE", 360, 640, surf)
        shape("square 720x720 SURFACE", 720, 720, surf)
        shape("landscape 1920x1080 SURFACE", 1920, 1080, surf)
        shape("portrait 1080x1920 SURFACE", 1080, 1920, surf)
        }
    }
}
