package me.hletrd.findx9tele.camera

/**
 * Teleconverter optics: which converter is clamped to the 3× periscope, and what that does to the
 * effective focal length.
 *
 * ## The setting is a PAIR: phone, then converter
 *
 * A teleconverter is sold as a focal length ("300 mm"), but a focal length is only meaningful next
 * to the lens it was computed for — the Hasselblad kits target a 70 mm periscope and the ZEISS ones
 * an 85 mm, so "200 mm" and "300 mm" are not comparable numbers. The invariant that survives is the
 * ANGULAR MAGNIFICATION, which is a property of the glass alone: sold focal ÷ its own host focal.
 *
 * So the UI asks two questions in order — [PhoneModel], then [TeleconverterProfile] — and the phone
 * answer narrows the converter list to the kits that actually clamp onto it. That is also why the
 * magnification derives from the CONVERTER's own host phone and never from the selected one: moving
 * a 4.286× optic to a different body does not regrind the glass.
 *
 * ## Why this is a SETTING and not a detection
 *
 * An afocal teleconverter is passive glass on a clamp. It has no electrical contact, no ID, and no
 * way to announce itself — so the app can NEVER know whether one is mounted or which one it is.
 * That is why [CameraUiState.teleconverterMode] has always been a manual toggle.
 *
 * The PHONE is different: [android.os.Build.MODEL] is readable, so [detectPhone] resolves it and the
 * phone dropdown starts on the right entry. That is the only automatic part, and it may only choose
 * a starting selection — never gate a capability, a route, or a request. Everywhere else still
 * resolves hardware by ENUMERATING Camera2 capabilities (`CameraSelector2` picks lenses by measured
 * equivalent focal, never by id), and that rule is unchanged.
 */
enum class PhoneModel(
    val label: String,
    /**
     * The 35 mm-equivalent focal of the telephoto its kit converter clamps onto. Used ONLY to derive
     * that kit's magnification — the focal the app reports always comes from the lens actually
     * opened on THIS device (see [effectiveFocalMm]).
     */
    val teleEquivMm: Float,
    /** `Build.MODEL` values for this phone, matched case-insensitively. */
    val deviceModels: List<String> = emptyList(),
) {
    FIND_X9_ULTRA("OPPO Find X9 Ultra", 70f, listOf("PMA110", "CPH2841")),
    FIND_X9_PRO("OPPO Find X9 Pro", 70f, listOf("CPH2791")),
    VIVO_X200_ULTRA("vivo X200 Ultra", 85f, listOf("V2454A", "V2454DA")),
    VIVO_X300_ULTRA("vivo X300 Ultra", 85f, listOf("V2562")),

    /**
     * Anything without a first-party kit. Offers only the generic clip-ons and CUSTOM, so it needs
     * no host focal of its own — those magnifications are intrinsic.
     */
    OTHER("Other phone", 70f),
    ;

    /** The converters that clamp onto this phone, plus the ones that fit anything. */
    fun converters(): List<TeleconverterProfile> =
        TeleconverterProfile.entries.filter { it.phone == null || it.phone == this }
}

/** The phone assumed before detection runs — this app's reason for existing. */
val DEFAULT_PHONE_MODEL = PhoneModel.FIND_X9_ULTRA

enum class TeleconverterProfile(
    val label: String,
    /**
     * The focal this kit is SOLD as, on [phone]'s telephoto. Zero for entries whose magnification is
     * intrinsic rather than derived (the generic clip-ons and [CUSTOM]).
     */
    val soldFocalMm: Float,
    /** Magnification for the intrinsic entries. Zero for kits, which derive it instead. */
    private val intrinsicMagnification: Float,
    /** The phone this kit clamps onto, or null when it fits anything. */
    val phone: PhoneModel?,
) {
    /**
     * Hasselblad "Earth Explorer" 300 mm — the Kepler afocal optic this whole app was built around.
     * 300 ÷ 70 = 4.286, matching the published 4.28×.
     */
    EXPLORER_300("Hasselblad 300 mm", 300f, 0f, PhoneModel.FIND_X9_ULTRA),

    /** Hasselblad Professional Teleconverter Kit: 230 ÷ 70 = 3.286, published as 3.28×. */
    HASSELBLAD_230("Hasselblad 230 mm", 230f, 0f, PhoneModel.FIND_X9_PRO),

    /** vivo ZEISS 2.35× extender: 200 ÷ 85 = 2.353, published as 2.35×. */
    ZEISS_200_X200("ZEISS 200 mm", 200f, 0f, PhoneModel.VIVO_X200_ULTRA),

    /** The same ZEISS 2.35× optic, which the X300 series also takes. */
    ZEISS_200_X300("ZEISS 200 mm", 200f, 0f, PhoneModel.VIVO_X300_ULTRA),

    /** vivo ZEISS 4.7× "G2 Ultra": 400 ÷ 85 = 4.706, published as 4.7×. */
    ZEISS_400("ZEISS 400 mm", 400f, 0f, PhoneModel.VIVO_X300_ULTRA),

    /** Generic clip-ons: the barrel states a magnification, not a focal, so it is intrinsic. */
    GENERIC_1_5("Generic 1.5×", 0f, 1.5f, null),
    GENERIC_2("Generic 2×", 0f, 2f, null),
    GENERIC_3("Generic 3×", 0f, 3f, null),

    /** User-entered magnification; see [effectiveMagnification]. */
    CUSTOM("Custom", 0f, 0f, null),
    ;

    val isCustom: Boolean get() = this == CUSTOM

    /**
     * The converter's angular magnification. For a kit this is (sold focal ÷ ITS OWN host phone's
     * telephoto) — deliberately not the phone currently selected, because the glass does not change
     * when it is moved. [CUSTOM] returns 0; callers must use [effectiveMagnification].
     */
    val magnification: Float
        get() = phone?.let { soldFocalMm / it.teleEquivMm } ?: intrinsicMagnification
}

/** The converter the app assumes when nothing has been chosen or restored. */
val DEFAULT_TELECONVERTER_PROFILE = TeleconverterProfile.EXPLORER_300

/**
 * The Explorer's magnification, kept as a named constant because it is the default, the value every
 * pre-existing test and document quotes, and the anchor for the nominal 300 mm readouts.
 */
const val TELECONVERTER_MAGNIFICATION = 300f / 70f

/**
 * Bounds for a custom magnification. The floor is just above 1× (a converter that magnifies by 1×
 * is not a converter), and the ceiling is far past any real phone clip-on — it exists to keep a
 * corrupt persisted value from producing an absurd focal length or zoom ceiling, not to express an
 * optical opinion.
 */
const val MIN_TELECONVERTER_MAGNIFICATION = 1.1f
const val MAX_TELECONVERTER_MAGNIFICATION = 10f

/**
 * The magnification actually in force: the profile's own value, or the user's custom number when
 * the profile is [TeleconverterProfile.CUSTOM]. Always finite and in range, so every caller can
 * multiply by it without re-checking.
 */
fun effectiveMagnification(profile: TeleconverterProfile, custom: Float): Float =
    if (profile.isCustom) normalizeMagnification(custom) else profile.magnification

/** Clamps [value] into the supported range, mapping non-finite input to the default. */
fun normalizeMagnification(value: Float): Float =
    if (!value.isFinite()) TELECONVERTER_MAGNIFICATION
    else value.coerceIn(MIN_TELECONVERTER_MAGNIFICATION, MAX_TELECONVERTER_MAGNIFICATION)

/**
 * The phone [deviceModel] names, or null when it is not one we carry a kit for. Case-insensitive
 * and whitespace-tolerant because `Build.MODEL` is vendor-formatted.
 */
fun detectPhone(deviceModel: String?): PhoneModel? {
    val model = deviceModel?.trim().orEmpty()
    if (model.isEmpty()) return null
    return PhoneModel.entries.firstOrNull { phone ->
        phone.deviceModels.any { it.equals(model, ignoreCase = true) }
    }
}

/**
 * The converter to select when [phone] becomes the chosen phone: its first kit, or the first generic
 * when it has none. Keeps the two dropdowns consistent — a converter for a phone you are not on is
 * never left selected.
 */
fun defaultConverterFor(phone: PhoneModel): TeleconverterProfile =
    phone.converters().firstOrNull { it.phone == phone }
        ?: phone.converters().first { !it.isCustom }

/**
 * Keeps [profile] only if it clamps onto [phone]; otherwise falls back to that phone's default. A
 * generic or custom entry always survives, because it fits anything.
 */
fun reconcileConverter(phone: PhoneModel, profile: TeleconverterProfile): TeleconverterProfile =
    if (profile.phone == null || profile.phone == phone) profile else defaultConverterFor(phone)

/**
 * The effective 35 mm-equivalent focal length through the converter.
 *
 * [baseEquivMm] is the NOMINAL focal of the lens the converter is clamped to on THIS device (70 mm
 * for the 3×), deliberately not the caps-measured ~69.4 mm: the whole readout chain — OSD, EXIF, the
 * zoom pill marks — is built on round numbers so 13×/30×/60× read as 300/690/1380 mm rather than
 * 680-ish. Changing that here would desynchronise all three.
 *
 * It is also why the UI never repeats a preset's product number: a "ZEISS 200 mm" is 2.35× glass,
 * and 2.35× on THIS phone's 70 mm periscope is 165 mm. Printing 200 would put a false focal in the
 * OSD and in saved EXIF.
 */
fun effectiveFocalMm(magnification: Float, baseEquivMm: Float = LensChoice.TELE3X.targetEquivMm): Float =
    magnification * baseEquivMm

/**
 * TELE mode's DISPLAY zoom scale (converter-equivalent, main-relative): local 1.0 on the 3× lens
 * with the 4.286× converter ≈ 13×. Formerly the top-level `TELE_DISPLAY_BASE` constant; it became a
 * function when the magnification became selectable.
 *
 * [TELE_MAX_DISPLAY_ZOOM] stays a FIXED display ceiling rather than scaling with the converter, so
 * the local-zoom ceiling (`TELE_MAX_DISPLAY_ZOOM / teleDisplayBase`) moves inversely with
 * magnification: a weaker converter earns more digital zoom before hitting the same total. That is
 * the intended reading — 60× is a cap on total magnification, not on the digital portion — and the
 * ordinary capability reconciliation still clamps the result to what the lens actually offers.
 */
fun teleDisplayBase(magnification: Float): Float =
    (LensChoice.TELE3X.targetEquivMm / LensChoice.MAIN.targetEquivMm) * magnification
