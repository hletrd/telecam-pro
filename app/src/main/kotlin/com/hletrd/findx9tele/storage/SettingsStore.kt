package com.hletrd.findx9tele.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.EisStrength
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoFrameRate

/**
 * App-level settings that aren't part of [ManualControls] but should still persist across launches
 * (transfer curve, save formats, mode, teleconverter/EIS/aspect/grid, video codec/bitrate).
 * Defaults mirror [com.hletrd.findx9tele.camera.CameraUiState] so a missing key restores the same
 * value a fresh install would show.
 */
data class ExtraSettings(
    val transfer: ColorTransfer = ColorTransfer.HLG,
    val heif: Boolean = true,
    val jpeg: Boolean = false,
    val dngRaw: Boolean = true,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val teleconverter: Boolean = true,
    val videoStabMode: VideoStabMode = VideoStabMode.ENHANCED,
    val eisStrength: EisStrength = EisStrength.MEDIUM,
    val aspectRatio: AspectRatio = AspectRatio.W4_3,
    val grid: GridType = GridType.THIRDS,
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val bitrateLevel: BitrateLevel = BitrateLevel.MEDIUM,
    val videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT,
    val openGate: Boolean = false,
    val audioScene: AudioScene = AudioScene.STANDARD,
)

/**
 * Persists the full pro-control state ([ManualControls] + [ExtraSettings]) across process death via
 * SharedPreferences, gated by [rememberEnabled] (the user's "Remember settings" toggle). Enums are
 * stored by name and restored defensively (an unknown name falls back to the field default), so a
 * schema change or a renamed enum constant degrades gracefully instead of crashing.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)

    // Default ON: photographers expect their setup to survive an app restart out of the box.
    var rememberEnabled: Boolean
        get() = prefs.getBoolean(K_REMEMBER, true)
        set(value) { prefs.edit { putBoolean(K_REMEMBER, value) } }

    fun save(c: ManualControls, e: ExtraSettings) {
        prefs.edit {
            putString("focusMode", c.focusMode.name)
            putFloat("focusDiopters", c.focusDistanceDiopters)
            putBoolean("afLock", c.afLock)
            putBoolean("autoExposure", c.autoExposure)
            putInt("iso", c.iso)
            putLong("exposureTimeNs", c.exposureTimeNs)
            putString("shutterMode", c.shutterMode.name)
            putFloat("shutterAngle", c.shutterAngle)
            putInt("exposureCompensation", c.exposureCompensation)
            putBoolean("aeLock", c.aeLock)
            putString("antibanding", c.antibanding.name)
            putInt("fps", c.fps)
            putString("exposureStep", c.exposureStep.name)
            putString("wbMode", c.wbMode.name)
            putInt("wbKelvin", c.wbKelvin)
            putInt("wbTint", c.wbTint)
            putBoolean("awbLock", c.awbLock)
            putString("meteringMode", c.meteringMode.name)
            putString("edge", c.edge.name)
            putString("noiseReduction", c.noiseReduction.name)
            putString("colorEffect", c.colorEffect.name)
            putString("flash", c.flash.name)
            putBoolean("oisEnabled", c.oisEnabled)
            putFloat("zoomRatio", c.zoomRatio)
            putInt("jpegQuality", c.jpegQuality)
            putString("transfer", e.transfer.name)
            putBoolean("heif", e.heif)
            putBoolean("jpeg", e.jpeg)
            putBoolean("dngRaw", e.dngRaw)
            putString("mode", e.mode.name)
            putBoolean("teleconverter", e.teleconverter)
            putString("videoStabMode", e.videoStabMode.name)
            putString("eisStrength", e.eisStrength.name)
            putString("aspectRatio", e.aspectRatio.name)
            putString("grid", e.grid.name)
            putString("videoCodec", e.videoCodec.name)
            putString("bitrateLevel", e.bitrateLevel.name)
            putString("videoFrameRate", e.videoFrameRate.name)
            putBoolean("openGate", e.openGate)
            putString("audioScene", e.audioScene.name)
            putBoolean(K_HAS, true)
        }
    }

    /** Returns the persisted state, or null if nothing was ever saved. Never throws. */
    fun load(): Loaded? {
        if (!prefs.getBoolean(K_HAS, false)) return null
        return runCatching {
            val d = ManualControls()
            val controls = ManualControls(
                focusMode = enumOr(prefs.getString("focusMode", null), d.focusMode),
                focusDistanceDiopters = prefs.getFloat("focusDiopters", d.focusDistanceDiopters),
                afLock = prefs.getBoolean("afLock", d.afLock),
                autoExposure = prefs.getBoolean("autoExposure", d.autoExposure),
                iso = prefs.getInt("iso", d.iso),
                exposureTimeNs = prefs.getLong("exposureTimeNs", d.exposureTimeNs),
                shutterMode = enumOr(prefs.getString("shutterMode", null), d.shutterMode),
                shutterAngle = prefs.getFloat("shutterAngle", d.shutterAngle),
                exposureCompensation = prefs.getInt("exposureCompensation", d.exposureCompensation),
                aeLock = prefs.getBoolean("aeLock", d.aeLock),
                antibanding = enumOr(prefs.getString("antibanding", null), d.antibanding),
                fps = prefs.getInt("fps", d.fps),
                exposureStep = enumOr(prefs.getString("exposureStep", null), d.exposureStep),
                wbMode = enumOr(prefs.getString("wbMode", null), d.wbMode),
                wbKelvin = prefs.getInt("wbKelvin", d.wbKelvin),
                wbTint = prefs.getInt("wbTint", d.wbTint),
                awbLock = prefs.getBoolean("awbLock", d.awbLock),
                meteringMode = enumOr(prefs.getString("meteringMode", null), d.meteringMode),
                edge = enumOr(prefs.getString("edge", null), d.edge),
                noiseReduction = enumOr(prefs.getString("noiseReduction", null), d.noiseReduction),
                colorEffect = enumOr(prefs.getString("colorEffect", null), d.colorEffect),
                flash = enumOr(prefs.getString("flash", null), d.flash),
                oisEnabled = prefs.getBoolean("oisEnabled", d.oisEnabled),
                zoomRatio = prefs.getFloat("zoomRatio", d.zoomRatio),
                jpegQuality = prefs.getInt("jpegQuality", d.jpegQuality),
            )
            val ed = ExtraSettings()
            val extras = ExtraSettings(
                transfer = enumOr(prefs.getString("transfer", null), ed.transfer),
                heif = prefs.getBoolean("heif", ed.heif),
                jpeg = prefs.getBoolean("jpeg", ed.jpeg),
                dngRaw = prefs.getBoolean("dngRaw", ed.dngRaw),
                mode = enumOr(prefs.getString("mode", null), ed.mode),
                teleconverter = prefs.getBoolean("teleconverter", ed.teleconverter),
                videoStabMode = enumOr(prefs.getString("videoStabMode", null), ed.videoStabMode),
                eisStrength = enumOr(prefs.getString("eisStrength", null), ed.eisStrength),
                aspectRatio = enumOr(prefs.getString("aspectRatio", null), ed.aspectRatio),
                grid = enumOr(prefs.getString("grid", null), ed.grid),
                videoCodec = enumOr(prefs.getString("videoCodec", null), ed.videoCodec),
                bitrateLevel = enumOr(prefs.getString("bitrateLevel", null), ed.bitrateLevel),
                videoFrameRate = enumOr(prefs.getString("videoFrameRate", null), ed.videoFrameRate),
                openGate = prefs.getBoolean("openGate", ed.openGate),
                audioScene = enumOr(prefs.getString("audioScene", null), ed.audioScene),
            )
            Loaded(controls, extras)
        }.getOrNull()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    data class Loaded(val controls: ManualControls, val extras: ExtraSettings)

    private companion object {
        const val K_REMEMBER = "rememberSettings"
        const val K_HAS = "hasSaved"
    }
}
