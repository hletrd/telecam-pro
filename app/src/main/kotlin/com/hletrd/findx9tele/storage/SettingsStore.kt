package com.hletrd.findx9tele.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.hletrd.findx9tele.camera.AfSpotSize
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.AudioInputPreference
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.WbGains
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoFrameRate

/**
 * App-level settings that aren't part of [ManualControls] but should still persist across launches
 * (transfer curve, save formats, mode, teleconverter/aspect/grid, video codec/bitrate).
 * Defaults mirror [com.hletrd.findx9tele.camera.CameraUiState] so a missing key restores the same
 * value a fresh install would show.
 */
data class ExtraSettings(
    val transfer: ColorTransfer = ColorTransfer.HLG,
    val heif: Boolean = true,
    val jpeg: Boolean = false,
    val dngRaw: Boolean = true,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val lens: LensChoice = LensChoice.TELE3X,
    val teleconverter: Boolean = true,
    val videoStabMode: VideoStabMode = VideoStabMode.ENHANCED,
    val aspectRatio: AspectRatio = AspectRatio.W4_3,
    val grid: GridType = GridType.THIRDS,
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val bitrateLevel: BitrateLevel = BitrateLevel.ULTRA,
    val videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT,
    val openGate: Boolean = false,
    val audioScene: AudioScene = AudioScene.STANDARD,
    val audioInputPreference: AudioInputPreference = AudioInputPreference.AUTO,
    val photoFnSlots: List<FnSlot> = FnSlot.PHOTO_DEFAULT,
    val videoFnSlots: List<FnSlot> = FnSlot.VIDEO_DEFAULT,
    val myMenuSlots: List<FnSlot> = FnSlot.MY_MENU_DEFAULT,
    val volumeKeyAction: HardwareKeyAction = HardwareKeyAction.SHUTTER,
    val halfPressAction: HardwareKeyAction = HardwareKeyAction.AF_ON,
    val gammaAssist: Boolean = false,
    val frameLines: FrameLineType = FrameLineType.OFF,
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
        // commit (synchronous), NOT apply: saves fire on user actions (e.g. a mode switch) and the
        // very next thing the user may do is swipe-kill the app — apply()'s async disk write dies
        // with the process and the change is silently lost ("last mode not remembered" bug). The
        // file is tiny; the write is a few ms.
        prefs.edit(commit = true) { putLoaded("", c, e); putBoolean(K_HAS, true) }
    }

    /** Returns the persisted state, or null if nothing was ever saved. Never throws. */
    fun load(): Loaded? = loadWithPrefix("", K_HAS)

    fun savePreset(slot: MemorySlot, c: ManualControls, e: ExtraSettings, name: String, summary: String) {
        val prefix = presetPrefix(slot)
        prefs.edit(commit = true) {
            putLoaded(prefix, c, e)
            putString("${prefix}name", name)
            putString("${prefix}summary", summary)
            putBoolean("${prefix}hasSaved", true)
        }
    }

    fun loadPreset(slot: MemorySlot): Loaded? =
        loadWithPrefix(presetPrefix(slot), "${presetPrefix(slot)}hasSaved")

    fun savedPresetSlots(): Set<MemorySlot> =
        MemorySlot.entries.filterTo(mutableSetOf()) { prefs.getBoolean("${presetPrefix(it)}hasSaved", false) }

    fun savedPresetInfo(): Map<MemorySlot, PresetInfo> =
        MemorySlot.entries.mapNotNull { slot ->
            val prefix = presetPrefix(slot)
            if (!prefs.getBoolean("${prefix}hasSaved", false)) return@mapNotNull null
            slot to PresetInfo(
                name = prefs.getString("${prefix}name", null)?.takeIf { it.isNotBlank() } ?: slot.label,
                summary = prefs.getString("${prefix}summary", null).orEmpty(),
            )
        }.toMap()

    private fun loadWithPrefix(prefix: String, hasKey: String): Loaded? {
        if (!prefs.getBoolean(hasKey, false)) return null
        return runCatching {
            val d = ManualControls()
            val controls = ManualControls(
                focusMode = enumOr(prefs.getString("${prefix}focusMode", null), d.focusMode),
                focusDistanceDiopters = prefs.getFloat("${prefix}focusDiopters", d.focusDistanceDiopters),
                afLock = prefs.getBoolean("${prefix}afLock", d.afLock),
                exposureMode = enumOr(prefs.getString("${prefix}exposureMode", null), d.exposureMode),
                iso = prefs.getInt("${prefix}iso", d.iso),
                exposureTimeNs = prefs.getLong("${prefix}exposureTimeNs", d.exposureTimeNs),
                shutterMode = enumOr(prefs.getString("${prefix}shutterMode", null), d.shutterMode),
                shutterAngle = prefs.getFloat("${prefix}shutterAngle", d.shutterAngle),
                exposureCompensation = prefs.getInt("${prefix}exposureCompensation", d.exposureCompensation),
                aeLock = prefs.getBoolean("${prefix}aeLock", d.aeLock),
                antibanding = enumOr(prefs.getString("${prefix}antibanding", null), d.antibanding),
                fps = prefs.getInt("${prefix}fps", d.fps),
                exposureStep = enumOr(prefs.getString("${prefix}exposureStep", null), d.exposureStep),
                wbMode = enumOr(prefs.getString("${prefix}wbMode", null), d.wbMode),
                wbKelvin = prefs.getInt("${prefix}wbKelvin", d.wbKelvin),
                wbTint = prefs.getInt("${prefix}wbTint", d.wbTint),
                awbLock = prefs.getBoolean("${prefix}awbLock", d.awbLock),
                meteringMode = enumOr(prefs.getString("${prefix}meteringMode", null), d.meteringMode),
                afSpotSize = enumOr(prefs.getString("${prefix}afSpotSize", null), d.afSpotSize),
                customWbGains = if (prefs.getBoolean("${prefix}hasCustomWb", false)) {
                    WbGains(
                        r = prefs.getFloat("${prefix}customWbR", 1f),
                        gEven = prefs.getFloat("${prefix}customWbGe", 1f),
                        gOdd = prefs.getFloat("${prefix}customWbGo", 1f),
                        b = prefs.getFloat("${prefix}customWbB", 1f),
                    )
                } else {
                    null
                },
                edge = enumOr(prefs.getString("${prefix}edge", null), d.edge),
                noiseReduction = enumOr(prefs.getString("${prefix}noiseReduction", null), d.noiseReduction),
                colorEffect = enumOr(prefs.getString("${prefix}colorEffect", null), d.colorEffect),
                flash = enumOr(prefs.getString("${prefix}flash", null), d.flash),
                oisEnabled = prefs.getBoolean("${prefix}oisEnabled", d.oisEnabled),
                zoomRatio = prefs.getFloat("${prefix}zoomRatio", d.zoomRatio),
                jpegQuality = prefs.getInt("${prefix}jpegQuality", d.jpegQuality),
            )
            val ed = ExtraSettings()
            val extras = ExtraSettings(
                transfer = enumOr(prefs.getString("${prefix}transfer", null), ed.transfer),
                heif = prefs.getBoolean("${prefix}heif", ed.heif),
                jpeg = prefs.getBoolean("${prefix}jpeg", ed.jpeg),
                dngRaw = prefs.getBoolean("${prefix}dngRaw", ed.dngRaw),
                mode = enumOr(prefs.getString("${prefix}mode", null), ed.mode),
                lens = enumOr(prefs.getString("${prefix}lens", null), ed.lens),
                teleconverter = prefs.getBoolean("${prefix}teleconverter", ed.teleconverter),
                videoStabMode = enumOr(prefs.getString("${prefix}videoStabMode", null), ed.videoStabMode),
                aspectRatio = enumOr(prefs.getString("${prefix}aspectRatio", null), ed.aspectRatio),
                grid = enumOr(prefs.getString("${prefix}grid", null), ed.grid),
                videoCodec = enumOr(prefs.getString("${prefix}videoCodec", null), ed.videoCodec),
                bitrateLevel = enumOr(prefs.getString("${prefix}bitrateLevel", null), ed.bitrateLevel),
                videoFrameRate = enumOr(prefs.getString("${prefix}videoFrameRate", null), ed.videoFrameRate),
                openGate = prefs.getBoolean("${prefix}openGate", ed.openGate),
                audioScene = enumOr(prefs.getString("${prefix}audioScene", null), ed.audioScene),
                audioInputPreference = enumOr(prefs.getString("${prefix}audioInputPreference", null), ed.audioInputPreference),
                photoFnSlots = enumListOr(
                    prefs.getString("${prefix}photoFnSlots", null) ?: prefs.getString("${prefix}fnSlots", null),
                    ed.photoFnSlots,
                ),
                videoFnSlots = enumListOr(
                    prefs.getString("${prefix}videoFnSlots", null),
                    ed.videoFnSlots,
                ),
                myMenuSlots = enumListOr(prefs.getString("${prefix}myMenuSlots", null), ed.myMenuSlots),
                volumeKeyAction = enumOr(prefs.getString("${prefix}volumeKeyAction", null), ed.volumeKeyAction),
                halfPressAction = enumOr(prefs.getString("${prefix}halfPressAction", null), ed.halfPressAction),
                gammaAssist = prefs.getBoolean("${prefix}gammaAssist", ed.gammaAssist),
                frameLines = enumOr(prefs.getString("${prefix}frameLines", null), ed.frameLines),
            )
            Loaded(controls, extras)
        }.getOrNull()
    }

    private fun SharedPreferences.Editor.putLoaded(prefix: String, c: ManualControls, e: ExtraSettings) {
        putString("${prefix}focusMode", c.focusMode.name)
        putFloat("${prefix}focusDiopters", c.focusDistanceDiopters)
        putBoolean("${prefix}afLock", c.afLock)
        putString("${prefix}exposureMode", c.exposureMode.name)
        putInt("${prefix}iso", c.iso)
        putLong("${prefix}exposureTimeNs", c.exposureTimeNs)
        putString("${prefix}shutterMode", c.shutterMode.name)
        putFloat("${prefix}shutterAngle", c.shutterAngle)
        putInt("${prefix}exposureCompensation", c.exposureCompensation)
        putBoolean("${prefix}aeLock", c.aeLock)
        putString("${prefix}antibanding", c.antibanding.name)
        putInt("${prefix}fps", c.fps)
        putString("${prefix}exposureStep", c.exposureStep.name)
        putString("${prefix}wbMode", c.wbMode.name)
        putInt("${prefix}wbKelvin", c.wbKelvin)
        putInt("${prefix}wbTint", c.wbTint)
        putBoolean("${prefix}awbLock", c.awbLock)
        putString("${prefix}meteringMode", c.meteringMode.name)
        putString("${prefix}afSpotSize", c.afSpotSize.name)
        putBoolean("${prefix}hasCustomWb", c.customWbGains != null)
        c.customWbGains?.let { g ->
            putFloat("${prefix}customWbR", g.r)
            putFloat("${prefix}customWbGe", g.gEven)
            putFloat("${prefix}customWbGo", g.gOdd)
            putFloat("${prefix}customWbB", g.b)
        }
        putString("${prefix}edge", c.edge.name)
        putString("${prefix}noiseReduction", c.noiseReduction.name)
        putString("${prefix}colorEffect", c.colorEffect.name)
        putString("${prefix}flash", c.flash.name)
        putBoolean("${prefix}oisEnabled", c.oisEnabled)
        putFloat("${prefix}zoomRatio", c.zoomRatio)
        putInt("${prefix}jpegQuality", c.jpegQuality)
        putString("${prefix}transfer", e.transfer.name)
        putBoolean("${prefix}heif", e.heif)
        putBoolean("${prefix}jpeg", e.jpeg)
        putBoolean("${prefix}dngRaw", e.dngRaw)
        putString("${prefix}mode", e.mode.name)
        putString("${prefix}lens", e.lens.name)
        putBoolean("${prefix}teleconverter", e.teleconverter)
        putString("${prefix}videoStabMode", e.videoStabMode.name)
        putString("${prefix}aspectRatio", e.aspectRatio.name)
        putString("${prefix}grid", e.grid.name)
        putString("${prefix}videoCodec", e.videoCodec.name)
        putString("${prefix}bitrateLevel", e.bitrateLevel.name)
        putString("${prefix}videoFrameRate", e.videoFrameRate.name)
        putBoolean("${prefix}openGate", e.openGate)
        putString("${prefix}audioScene", e.audioScene.name)
        putString("${prefix}audioInputPreference", e.audioInputPreference.name)
        putString("${prefix}photoFnSlots", e.photoFnSlots.joinToString(",") { it.name })
        putString("${prefix}videoFnSlots", e.videoFnSlots.joinToString(",") { it.name })
        putString("${prefix}myMenuSlots", e.myMenuSlots.joinToString(",") { it.name })
        putString("${prefix}volumeKeyAction", e.volumeKeyAction.name)
        putString("${prefix}halfPressAction", e.halfPressAction.name)
        putBoolean("${prefix}gammaAssist", e.gammaAssist)
        putString("${prefix}frameLines", e.frameLines.name)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private inline fun <reified T : Enum<T>> enumListOr(raw: String?, default: List<T>): List<T> {
        val parsed = raw
            ?.split(',')
            ?.mapNotNull { name -> runCatching { enumValueOf<T>(name) }.getOrNull() }
            ?.distinct()
            .orEmpty()
        return parsed.ifEmpty { default }
    }

    data class Loaded(val controls: ManualControls, val extras: ExtraSettings)
    data class PresetInfo(val name: String, val summary: String)

    private companion object {
        const val K_REMEMBER = "rememberSettings"
        const val K_HAS = "hasSaved"
        fun presetPrefix(slot: MemorySlot): String = "preset_${slot.name}_"
    }
}
