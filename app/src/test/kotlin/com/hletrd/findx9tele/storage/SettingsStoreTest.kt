package com.hletrd.findx9tele.storage

import android.content.SharedPreferences
import com.hletrd.findx9tele.camera.AfSpotSize
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.AudioInputPreference
import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.ExposureStep
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.PeakingColor
import com.hletrd.findx9tele.camera.PeakingLevel
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoFrameRate
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.WbGains
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.ZebraLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the SettingsStore persistence contract against an in-memory SharedPreferences fake (the
 * SharedPreferences seam the primary constructor exposes). The full-round-trip test is the main net
 * for a copy-paste key mismatch among the ~50 keys.
 */
class SettingsStoreTest {

    // ---- in-memory SharedPreferences fake (only what SettingsStore touches) ----

    private class FakePrefs(
        private val store: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        var commitCount: Int = 0
            private set
        var applyCount: Int = 0
            private set

        override fun getString(key: String?, defValue: String?): String? =
            if (store.containsKey(key)) store[key] as String? else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (store.containsKey(key)) store[key] as MutableSet<String>? else defValues

        override fun getInt(key: String?, defValue: Int): Int =
            if (store.containsKey(key)) store[key] as Int else defValue

        override fun getLong(key: String?, defValue: Long): Long =
            if (store.containsKey(key)) store[key] as Long else defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            if (store.containsKey(key)) store[key] as Float else defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (store.containsKey(key)) store[key] as Boolean else defValue

        override fun contains(key: String?): Boolean = store.containsKey(key)
        override fun getAll(): MutableMap<String, *> = store
        override fun edit(): SharedPreferences.Editor = FakeEditor(
            store = store,
            onCommit = { commitCount++ },
            onApply = { applyCount++ },
        )
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(
        private val store: MutableMap<String, Any?>,
        private val onCommit: () -> Unit,
        private val onApply: () -> Unit,
    ) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { staged[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { staged[key!!] = values }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { staged[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { staged[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { staged[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { staged[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { removed += key!! }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean { onCommit(); flush(); return true }
        override fun apply() { onApply(); flush() }

        private fun flush() {
            if (clearAll) store.clear()
            removed.forEach { store.remove(it) }
            store.putAll(staged)
            staged.clear(); removed.clear(); clearAll = false
        }
    }

    // A ManualControls with every PERSISTED field non-default. programAppSide is intentionally left
    // at its default: it is derived state, NOT persisted, so a non-default value there would never
    // round-trip.
    private val nonDefaultControls = ManualControls(
        focusMode = FocusMode.MANUAL,
        focusDistanceDiopters = 2.5f,
        afLock = true,
        exposureMode = ExposureMode.MANUAL,
        iso = 1600,
        exposureTimeNs = 4_000_000L,
        shutterMode = ShutterMode.ANGLE,
        shutterAngle = 172.8f,
        exposureCompensation = 2,
        aeLock = true,
        antibanding = Antibanding.HZ60,
        fps = 60,
        exposureStep = ExposureStep.HALF,
        wbMode = WbMode.MANUAL,
        wbKelvin = 3200,
        wbTint = -10,
        // Distinct gEven/gOdd so a customWbGe/customWbGo copy-paste swap is caught.
        customWbGains = WbGains(r = 1.8f, gEven = 1.0f, gOdd = 1.02f, b = 2.4f),
        awbLock = true,
        meteringMode = MeteringMode.SPOT,
        afSpotSize = AfSpotSize.LARGE,
        edge = ProcessingLevel.HIGH_QUALITY,
        noiseReduction = ProcessingLevel.FAST,
        colorEffect = ColorEffect.MONO,
        flash = FlashMode.ON,
        oisEnabled = false,
        zoomRatio = 4.286f,
        jpegQuality = 80,
    )

    @Test
    fun rememberToggleCommitsBothValuesSynchronously() {
        val prefs = FakePrefs()
        val store = SettingsStore(prefs)

        store.rememberEnabled = false
        assertFalse(store.rememberEnabled)
        assertEquals(1, prefs.commitCount)
        assertEquals(0, prefs.applyCount)

        store.rememberEnabled = true
        assertTrue(store.rememberEnabled)
        assertEquals(2, prefs.commitCount)
        assertEquals(0, prefs.applyCount)
    }

    private val nonDefaultExtras = ExtraSettings(
        transfer = ColorTransfer.LOG,
        heif = false,
        jpeg = true,
        dngRaw = false,
        mode = CaptureMode.VIDEO,
        photoExposureTimeNs = 750_000_000L,
        lens = LensChoice.TELE3X,
        teleconverter = true,
        videoStabMode = VideoStabMode.STANDARD,
        aspectRatio = AspectRatio.W16_9,
        timer = ShutterTimer.SEC10,
        driveMode = DriveMode.TIMELAPSE,
        intervalSec = 17,
        focusPeaking = true,
        peakingLevel = PeakingLevel.HIGH,
        peakingColor = PeakingColor.BLUE,
        zebra = true,
        zebraLevel = ZebraLevel.IRE70,
        falseColor = true,
        histogram = true,
        waveform = true,
        grid = GridType.GOLDEN,
        level = true,
        punchIn = true,
        teleFinder = true,
        videoCodec = VideoCodec.AVC,
        bitrateLevel = BitrateLevel.MAX,
        videoFrameRate = VideoFrameRate.FPS_24,
        videoResolution = "1920x1080",
        openGate = true,
        recordAudio = false,
        audioGain = 1.5f,
        audioScene = AudioScene.SOUND_FOCUS,
        audioInputPreference = AudioInputPreference.USB,
        // Reordered from PHOTO_DEFAULT — catches a photoFnSlots/videoFnSlots/fnSlots key mixup.
        photoFnSlots = listOf(FnSlot.WB, FnSlot.ISO, FnSlot.EXPOSURE_MODE, FnSlot.EV, FnSlot.FOCUS, FnSlot.SHUTTER),
        videoFnSlots = listOf(FnSlot.TRANSFER, FnSlot.STABILIZATION, FnSlot.AUDIO_SCENE),
        myMenuSlots = listOf(FnSlot.PEAKING, FnSlot.ZEBRA),
        volumeKeyAction = HardwareKeyAction.AF_ON,
        halfPressAction = HardwareKeyAction.AEL,
        gammaAssist = true,
        frameLines = FrameLineType.CINEMA,
        preserveLensSelection = false,
        preserveTeleconverter = false,
    )

    @Test
    fun saveThenLoad_roundTripsEqual() {
        val store = SettingsStore(FakePrefs())
        store.save(nonDefaultControls, nonDefaultExtras)
        assertEquals(SettingsStore.Loaded(nonDefaultControls, nonDefaultExtras), store.load())
    }

    @Test
    fun load_onEmptyStore_isNull() {
        assertNull(SettingsStore(FakePrefs()).load())
    }

    @Test
    fun load_migratesLegacySharedShutterIntoPhotoExposureMemory() {
        val legacyExposureNs = 500_000_000L
        val prefs = FakePrefs(
            mutableMapOf(
                "hasSaved" to true,
                "mode" to CaptureMode.VIDEO.name,
                "exposureTimeNs" to legacyExposureNs,
            ),
        )

        val loaded = requireNotNull(SettingsStore(prefs).load())
        assertEquals(legacyExposureNs, loaded.extras.photoExposureTimeNs)
    }

    @Test
    fun load_migratesLegacyFnSlotsKey() {
        // Old installs stored the still Fn bar under "fnSlots"; with no "photoFnSlots" key present,
        // load() must fall back to the legacy key.
        val prefs = FakePrefs(mutableMapOf("hasSaved" to true, "fnSlots" to "ISO,WB"))
        val loaded = SettingsStore(prefs).load()
        assertEquals(listOf(FnSlot.ISO, FnSlot.WB), loaded?.extras?.photoFnSlots)
    }

    @Test
    fun enumOr_defaultsOnUnknownOrNull_roundTripsValid() {
        assertEquals(WbMode.MANUAL, enumOr("MANUAL", WbMode.AUTO))
        assertEquals(WbMode.AUTO, enumOr("NOT_A_MODE", WbMode.AUTO))
        assertEquals(WbMode.AUTO, enumOr(null, WbMode.AUTO))
    }

    @Test
    fun enumListOr_dropsGarbage_keepsValid_defaultsWhenAllGarbage() {
        assertEquals(listOf(FnSlot.ISO, FnSlot.WB), enumListOr("ISO,GARBAGE,WB", emptyList<FnSlot>()))
        assertEquals(listOf(FnSlot.WB), enumListOr("NOPE,ALSO_NOPE", listOf(FnSlot.WB)))
        assertEquals(listOf(FnSlot.EV), enumListOr(null, listOf(FnSlot.EV)))
    }

    @Test
    fun presets_roundTripPerSlot_noCrossSlotBleed() {
        val store = SettingsStore(FakePrefs())
        val cA = nonDefaultControls.copy(iso = 200)
        val cB = nonDefaultControls.copy(iso = 6400)
        store.savePreset(MemorySlot.MR1, cA, nonDefaultExtras, "A", "sumA")
        store.savePreset(MemorySlot.MR2, cB, nonDefaultExtras, "B", "sumB")
        assertEquals(SettingsStore.Loaded(cA, nonDefaultExtras), store.loadPreset(MemorySlot.MR1))
        assertEquals(SettingsStore.Loaded(cB, nonDefaultExtras), store.loadPreset(MemorySlot.MR2))
        assertNull("an unsaved slot stays empty", store.loadPreset(MemorySlot.MR3))
    }

    @Test
    fun load_clampsPersistedZoomRatio() {
        // A persisted non-positive zoom would feed the zoom-ease ticker's log-space math a 0 -> clamp.
        val prefs = FakePrefs(mutableMapOf("hasSaved" to true, "zoomRatio" to 0f))
        val loaded = SettingsStore(prefs).load()
        assertTrue("zoomRatio must be clamped >= 0.1f", (loaded?.controls?.zoomRatio ?: 0f) >= 0.1f)
    }
}
