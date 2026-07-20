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
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.PeakingColor
import com.hletrd.findx9tele.camera.PeakingLevel
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.ZebraLevel
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
    val dngRaw: Boolean = false,
    val mode: CaptureMode = CaptureMode.PHOTO,
    // Video stores a frame-rate-clamped live shutter in ManualControls. Keep the last Photo value
    // separately so Photo -> Video -> process death -> Photo does not destroy a long exposure.
    val photoExposureTimeNs: Long = ManualControls().exposureTimeNs,
    val lens: LensChoice = LensChoice.MAIN,
    val teleconverter: Boolean = false,
    val videoStabMode: VideoStabMode = VideoStabMode.ENHANCED,
    val aspectRatio: AspectRatio = AspectRatio.W4_3,
    val timer: ShutterTimer = ShutterTimer.OFF,
    val driveMode: DriveMode = DriveMode.SINGLE,
    val intervalSec: Int = 5,
    val focusPeaking: Boolean = false,
    val peakingLevel: PeakingLevel = PeakingLevel.MEDIUM,
    val peakingColor: PeakingColor = PeakingColor.MAGENTA,
    val zebra: Boolean = false,
    val zebraLevel: ZebraLevel = ZebraLevel.IRE95,
    val falseColor: Boolean = false,
    val histogram: Boolean = false,
    val waveform: Boolean = false,
    val grid: GridType = GridType.THIRDS,
    val level: Boolean = false,
    val punchIn: Boolean = false,
    val teleFinder: Boolean = false,
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val bitrateLevel: BitrateLevel = BitrateLevel.ULTRA,
    val videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT,
    // User-selected recording resolution as "WxH" ("" = never chosen -> the engine auto-picks the
    // largest size for the lens). A plain string keeps ExtraSettings JVM-pure (android.util.Size
    // getters throw on the JVM); the ViewModel parses and re-validates it against the live caps.
    val videoResolution: String = "",
    val openGate: Boolean = false,
    val recordAudio: Boolean = true,
    val audioGain: Float = 1f,
    val audioScene: AudioScene = AudioScene.STANDARD,
    val audioInputPreference: AudioInputPreference = AudioInputPreference.AUTO,
    val photoFnSlots: List<FnSlot> = FnSlot.PHOTO_DEFAULT,
    val videoFnSlots: List<FnSlot> = FnSlot.VIDEO_DEFAULT,
    val myMenuSlots: List<FnSlot> = FnSlot.MY_MENU_DEFAULT,
    val volumeKeyAction: HardwareKeyAction = HardwareKeyAction.SHUTTER,
    val halfPressAction: HardwareKeyAction = HardwareKeyAction.AF_ON,
    val gammaAssist: Boolean = false,
    val frameLines: FrameLineType = FrameLineType.OFF,
    val preserveLensSelection: Boolean = true,
    val preserveTeleconverter: Boolean = true,
)

/**
 * Persists the full pro-control state ([ManualControls] + [ExtraSettings]) across process death via
 * SharedPreferences, gated by [rememberEnabled] (the user's "Remember settings" toggle). Enums are
 * stored by name and restored defensively (an unknown name falls back to the field default), so a
 * schema change or a renamed enum constant degrades gracefully instead of crashing.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    // The SharedPreferences seam exists so the persistence contract is unit-testable with an
    // in-memory fake (the real app uses the Context-backed secondary constructor below).
    constructor(context: Context) : this(context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE))

    // Default ON: photographers expect their setup to survive an app restart out of the box.
    var rememberEnabled: Boolean
        get() = prefs.getBoolean(K_REMEMBER, true)
        // This gate is itself part of the durable settings contract. In particular, disabling it
        // does not trigger save(), so apply() here could resurrect the old true value after a kill.
        set(value) { prefs.edit(commit = true) { putBoolean(K_REMEMBER, value) } }

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

    // Per-field defensive readers (CR4-12): a single type-mismatched key (a field whose stored
    // type changed across an app version makes the typed getter throw ClassCastException) used to
    // discard the WHOLE saved blob via loadWithPrefix's outer runCatching — every remembered
    // setting silently reverted to defaults over one bad key. Each field now degrades alone; the
    // outer runCatching remains only as a last-resort net.
    private fun safeBoolean(key: String, default: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)

    private fun safeInt(key: String, default: Int): Int =
        runCatching { prefs.getInt(key, default) }.getOrDefault(default)

    private fun safeFloat(key: String, default: Float): Float =
        runCatching { prefs.getFloat(key, default) }.getOrDefault(default)

    private fun safeLong(key: String, default: Long): Long =
        runCatching { prefs.getLong(key, default) }.getOrDefault(default)

    private fun safeString(key: String, default: String?): String? =
        runCatching { prefs.getString(key, default) }.getOrDefault(default)

    private fun loadWithPrefix(prefix: String, hasKey: String): Loaded? {
        if (!safeBoolean(hasKey, false)) return null
        return runCatching {
            val d = ManualControls()
            val controls = ManualControls(
                focusMode = enumOr(safeString("${prefix}focusMode", null), d.focusMode),
                // Cap untrusted/corrupt preference numbers before route capabilities are available.
                // The selected route applies its tighter hardware bounds at the capability seam.
                focusDistanceDiopters = safeFloat("${prefix}focusDiopters", d.focusDistanceDiopters)
                    .let {
                        if (it.isFinite()) it.coerceIn(MIN_PERSISTED_FOCUS_DIOPTERS, MAX_PERSISTED_FOCUS_DIOPTERS)
                        else d.focusDistanceDiopters
                    },
                afLock = safeBoolean("${prefix}afLock", d.afLock),
                exposureMode = enumOr(safeString("${prefix}exposureMode", null), d.exposureMode),
                iso = safeInt("${prefix}iso", d.iso).coerceIn(MIN_PERSISTED_ISO, MAX_PERSISTED_ISO),
                exposureTimeNs = safeLong("${prefix}exposureTimeNs", d.exposureTimeNs),
                shutterMode = enumOr(safeString("${prefix}shutterMode", null), d.shutterMode),
                shutterAngle = safeFloat("${prefix}shutterAngle", d.shutterAngle),
                exposureCompensation = safeInt("${prefix}exposureCompensation", d.exposureCompensation)
                    .coerceIn(MIN_PERSISTED_EV_INDEX, MAX_PERSISTED_EV_INDEX),
                aeLock = safeBoolean("${prefix}aeLock", d.aeLock),
                antibanding = enumOr(safeString("${prefix}antibanding", null), d.antibanding),
                fps = safeInt("${prefix}fps", d.fps),
                exposureStep = enumOr(safeString("${prefix}exposureStep", null), d.exposureStep),
                wbMode = enumOr(safeString("${prefix}wbMode", null), d.wbMode),
                wbKelvin = safeInt("${prefix}wbKelvin", d.wbKelvin),
                wbTint = safeInt("${prefix}wbTint", d.wbTint),
                awbLock = safeBoolean("${prefix}awbLock", d.awbLock),
                meteringMode = enumOr(safeString("${prefix}meteringMode", null), d.meteringMode),
                afSpotSize = enumOr(safeString("${prefix}afSpotSize", null), d.afSpotSize),
                customWbGains = if (safeBoolean("${prefix}hasCustomWb", false)) {
                    WbGains(
                        r = safeFloat("${prefix}customWbR", 1f),
                        gEven = safeFloat("${prefix}customWbGe", 1f),
                        gOdd = safeFloat("${prefix}customWbGo", 1f),
                        b = safeFloat("${prefix}customWbB", 1f),
                    )
                } else {
                    null
                },
                edge = enumOr(safeString("${prefix}edge", null), d.edge),
                noiseReduction = enumOr(safeString("${prefix}noiseReduction", null), d.noiseReduction),
                colorEffect = enumOr(safeString("${prefix}colorEffect", null), d.colorEffect),
                flash = enumOr(safeString("${prefix}flash", null), d.flash),
                oisEnabled = safeBoolean("${prefix}oisEnabled", d.oisEnabled),
                // Clamped: this raw float is the ONE zoom path with no UI/caps gate (caps aren't
                // known at load time), and a non-positive/NaN value would feed the zoom ease
                // ticker's log-space math (a 0 makes target/cur = Inf -> NaN loop).
                zoomRatio = safeFloat("${prefix}zoomRatio", d.zoomRatio)
                    .let { if (it.isFinite()) it.coerceIn(0.1f, 100f) else d.zoomRatio },
                jpegQuality = safeInt("${prefix}jpegQuality", d.jpegQuality),
            )
            val ed = ExtraSettings()
            val extras = ExtraSettings(
                transfer = enumOr(safeString("${prefix}transfer", null), ed.transfer),
                heif = safeBoolean("${prefix}heif", ed.heif),
                jpeg = safeBoolean("${prefix}jpeg", ed.jpeg),
                dngRaw = safeBoolean("${prefix}dngRaw", ed.dngRaw),
                mode = enumOr(safeString("${prefix}mode", null), ed.mode),
                // Legacy installs have no separate Photo value; their one saved shutter is the best
                // lossless migration source, especially when they last exited in Video slow-shutter.
                photoExposureTimeNs = safeLong("${prefix}photoExposureTimeNs", controls.exposureTimeNs)
                    .coerceAtLeast(1L),
                lens = enumOr(safeString("${prefix}lens", null), ed.lens),
                teleconverter = safeBoolean("${prefix}teleconverter", ed.teleconverter),
                videoStabMode = enumOr(safeString("${prefix}videoStabMode", null), ed.videoStabMode),
                aspectRatio = enumOr(safeString("${prefix}aspectRatio", null), ed.aspectRatio),
                timer = enumOr(safeString("${prefix}timer", null), ed.timer),
                driveMode = enumOr(safeString("${prefix}driveMode", null), ed.driveMode),
                intervalSec = safeInt("${prefix}intervalSec", ed.intervalSec).coerceAtLeast(1),
                focusPeaking = safeBoolean("${prefix}focusPeaking", ed.focusPeaking),
                peakingLevel = enumOr(safeString("${prefix}peakingLevel", null), ed.peakingLevel),
                peakingColor = enumOr(safeString("${prefix}peakingColor", null), ed.peakingColor),
                zebra = safeBoolean("${prefix}zebra", ed.zebra),
                zebraLevel = enumOr(safeString("${prefix}zebraLevel", null), ed.zebraLevel),
                falseColor = safeBoolean("${prefix}falseColor", ed.falseColor),
                histogram = safeBoolean("${prefix}histogram", ed.histogram),
                waveform = safeBoolean("${prefix}waveform", ed.waveform),
                grid = enumOr(safeString("${prefix}grid", null), ed.grid),
                level = safeBoolean("${prefix}level", ed.level),
                punchIn = safeBoolean("${prefix}punchIn", ed.punchIn),
                teleFinder = safeBoolean("${prefix}teleFinder", ed.teleFinder),
                videoCodec = enumOr(safeString("${prefix}videoCodec", null), ed.videoCodec),
                bitrateLevel = enumOr(safeString("${prefix}bitrateLevel", null), ed.bitrateLevel),
                videoFrameRate = enumOr(safeString("${prefix}videoFrameRate", null), ed.videoFrameRate),
                videoResolution = safeString("${prefix}videoResolution", null) ?: ed.videoResolution,
                openGate = safeBoolean("${prefix}openGate", ed.openGate),
                recordAudio = safeBoolean("${prefix}recordAudio", ed.recordAudio),
                audioGain = safeFloat("${prefix}audioGain", ed.audioGain),
                audioScene = enumOr(safeString("${prefix}audioScene", null), ed.audioScene),
                audioInputPreference = enumOr(safeString("${prefix}audioInputPreference", null), ed.audioInputPreference),
                photoFnSlots = enumListOr(
                    safeString("${prefix}photoFnSlots", null) ?: safeString("${prefix}fnSlots", null),
                    ed.photoFnSlots,
                ),
                videoFnSlots = enumListOr(
                    safeString("${prefix}videoFnSlots", null),
                    ed.videoFnSlots,
                ),
                myMenuSlots = enumListOr(safeString("${prefix}myMenuSlots", null), ed.myMenuSlots),
                volumeKeyAction = enumOr(safeString("${prefix}volumeKeyAction", null), ed.volumeKeyAction),
                halfPressAction = enumOr(safeString("${prefix}halfPressAction", null), ed.halfPressAction),
                gammaAssist = safeBoolean("${prefix}gammaAssist", ed.gammaAssist),
                frameLines = enumOr(safeString("${prefix}frameLines", null), ed.frameLines),
                preserveLensSelection = safeBoolean("${prefix}preserveLensSelection", ed.preserveLensSelection),
                preserveTeleconverter = safeBoolean("${prefix}preserveTeleconverter", ed.preserveTeleconverter),
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
        putLong("${prefix}photoExposureTimeNs", e.photoExposureTimeNs)
        putString("${prefix}lens", e.lens.name)
        putBoolean("${prefix}teleconverter", e.teleconverter)
        putString("${prefix}videoStabMode", e.videoStabMode.name)
        putString("${prefix}aspectRatio", e.aspectRatio.name)
        putString("${prefix}timer", e.timer.name)
        putString("${prefix}driveMode", e.driveMode.name)
        putInt("${prefix}intervalSec", e.intervalSec)
        putBoolean("${prefix}focusPeaking", e.focusPeaking)
        putString("${prefix}peakingLevel", e.peakingLevel.name)
        putString("${prefix}peakingColor", e.peakingColor.name)
        putBoolean("${prefix}zebra", e.zebra)
        putString("${prefix}zebraLevel", e.zebraLevel.name)
        putBoolean("${prefix}falseColor", e.falseColor)
        putBoolean("${prefix}histogram", e.histogram)
        putBoolean("${prefix}waveform", e.waveform)
        putString("${prefix}grid", e.grid.name)
        putBoolean("${prefix}level", e.level)
        putBoolean("${prefix}punchIn", e.punchIn)
        putBoolean("${prefix}teleFinder", e.teleFinder)
        putString("${prefix}videoCodec", e.videoCodec.name)
        putString("${prefix}bitrateLevel", e.bitrateLevel.name)
        putString("${prefix}videoFrameRate", e.videoFrameRate.name)
        putString("${prefix}videoResolution", e.videoResolution)
        putBoolean("${prefix}openGate", e.openGate)
        putBoolean("${prefix}recordAudio", e.recordAudio)
        putFloat("${prefix}audioGain", e.audioGain)
        putString("${prefix}audioScene", e.audioScene.name)
        putString("${prefix}audioInputPreference", e.audioInputPreference.name)
        putString("${prefix}photoFnSlots", e.photoFnSlots.joinToString(",") { it.name })
        putString("${prefix}videoFnSlots", e.videoFnSlots.joinToString(",") { it.name })
        putString("${prefix}myMenuSlots", e.myMenuSlots.joinToString(",") { it.name })
        putString("${prefix}volumeKeyAction", e.volumeKeyAction.name)
        putString("${prefix}halfPressAction", e.halfPressAction.name)
        putBoolean("${prefix}gammaAssist", e.gammaAssist)
        putString("${prefix}frameLines", e.frameLines.name)
        putBoolean("${prefix}preserveLensSelection", e.preserveLensSelection)
        putBoolean("${prefix}preserveTeleconverter", e.preserveTeleconverter)
    }

    data class Loaded(val controls: ManualControls, val extras: ExtraSettings)
    data class PresetInfo(val name: String, val summary: String)

    private companion object {
        const val K_REMEMBER = "rememberSettings"
        const val K_HAS = "hasSaved"
        const val MIN_PERSISTED_FOCUS_DIOPTERS = 0f
        const val MAX_PERSISTED_FOCUS_DIOPTERS = 100f
        const val MIN_PERSISTED_ISO = 1
        const val MAX_PERSISTED_ISO = 1_000_000
        const val MIN_PERSISTED_EV_INDEX = -100
        const val MAX_PERSISTED_EV_INDEX = 100
        fun presetPrefix(slot: MemorySlot): String = "preset_${slot.name}_"
    }
}

// Top-level (not class members): these two carry the file's documented "degrades gracefully"
// contract and are pure JVM code — unit tests exercise them directly.
internal inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

internal inline fun <reified T : Enum<T>> enumListOr(raw: String?, default: List<T>): List<T> {
    val parsed = raw
        ?.split(',')
        ?.mapNotNull { name -> runCatching { enumValueOf<T>(name) }.getOrNull() }
        ?.distinct()
        .orEmpty()
    return parsed.ifEmpty { default }
}
