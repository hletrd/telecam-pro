package me.hletrd.telecampro.camera

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
    /**
     * The 35 mm-equivalent focal of the telephoto its kit converter clamps onto. Used ONLY to derive
     * that kit's magnification — the focal the app reports always comes from the lens actually
     * opened on THIS device (see [effectiveFocalMm]).
     */
    val teleEquivMm: Float,
    /** `Build.MODEL` values for this phone, matched case-insensitively. */
    val deviceModels: List<String> = emptyList(),
) {
    // Build.MODEL codes: Google Play supported-devices CSV (play_public/supported_devices.csv,
    // fetched 2026-10-25, last modified 2026-10-24). Telephoto focals: the makers' own spec pages.
    // A phone with NO code in that list is still offered for MANUAL selection — detection only
    // preselects, so a missing code costs the auto-pick and nothing else.
    // OPPO: Find X9 Ultra / X9 Pro (oppo.com product pages), Find X9s Pro and Find X10 / X10 Pro Max
    // (oppo.com/cn and oppo.com/en, 2026-09-22 launch). PMA120 = Find X9 Ultra satellite edition.
    FIND_X9_ULTRA(70f, listOf("PMA110", "CPH2841", "PMA120")),
    FIND_X9_PRO(70f, listOf("PLG110", "PLG120", "CPH2791")),
    FIND_X9S_PRO(65f, listOf("PME110")),
    FIND_X10(65f, listOf("PMW110", "CPH2911", "OPG09")),
    FIND_X10_PRO_MAX(70f, listOf("PMX110", "CPH2913")),

    // vivo: the ZEISS extenders are built for an 85 mm telephoto (vivo FAQ 1925 / 2137); the X300
    // Ultra's 14/35/85 mm and the X500's 72 mm are from vivo.com / vivo.com.cn spec pages.
    // V2547DA is also the X300 Ultra satellite edition; V2602DA the X500 Pro Max satellite edition.
    VIVO_X200_ULTRA(85f, listOf("V2454A", "V2454DA")),
    VIVO_X300(85f, listOf("V2509A", "V2515")),
    VIVO_X300_PRO(85f, listOf("V2502A", "V2502DA", "V2514")),
    VIVO_X300S(85f, listOf("V2548A")),
    VIVO_X300_ULTRA(85f, listOf("V2547A", "V2547DA", "V2562")),
    VIVO_X500(72f, listOf("V2609A")),
    VIVO_X500_PRO(85f, listOf("V2608A", "V2608DA", "V2621", "V2622")),
    VIVO_X500_PRO_MAX(85f, listOf("V2602A", "V2602DA", "V2610")),

    // HONOR: honor.com kit and phone pages. Only the global Magic8 Pro is in Google Play's list.
    HONOR_MAGIC8_PRO(85f, listOf("BKQ-N49")),
    HONOR_MAGIC8_RSR(85f),
    HONOR_MAGIC9(80f),
    HONOR_MAGIC9_PRO_MAX(85f),

    /**
     * Anything without a first-party kit. Offers only the generic clip-ons and CUSTOM, so it needs
     * no host focal of its own — those magnifications are intrinsic.
     */
    // Labelled just "Other": the row it renders in is already labelled "Phone", so the old
    // "Other phone" read back as "Phone   Other phone".
    OTHER(70f),
    ;

    /** The converters that clamp onto this phone, plus the ones that fit anything. */
    fun converters(): List<TeleconverterProfile> =
        TeleconverterProfile.entries.filter { it.phone == null || it.phone == this }
}

/** The phone assumed before detection runs — this app's reason for existing. */
val DEFAULT_PHONE_MODEL = PhoneModel.FIND_X9_ULTRA

enum class TeleconverterProfile(
    /**
     * The focal this kit is SOLD as, on [phone]'s telephoto. Zero for entries whose magnification is
     * intrinsic rather than derived (the generic clip-ons and [CUSTOM]).
     */
    val soldFocalMm: Float,
    /** Magnification for the intrinsic entries. Zero for kits, which derive it instead. */
    private val intrinsicMagnification: Float,
    /** The phone this kit clamps onto, or null when it fits anything. */
    val phone: PhoneModel?,
    /**
     * The telephoto the GLASS was designed for, when that is not [phone]'s own: the same optic is
     * sold for bodies whose telephotos differ, and its magnification belongs to the glass. Zero means
     * "[phone]'s telephoto", which is the case for every kit sold with the phone it was computed on.
     * The focal the app REPORTS is still magnification × the lens actually opened ([effectiveFocalMm]),
     * so a 2.35× optic on the 72 mm X500 reads 169 mm even where the maker prints "200 mm".
     */
    private val designHostMm: Float = 0f,
) {
    /**
     * Hasselblad "Earth Explorer" 300 mm — the Kepler afocal optic this whole app was built around.
     * 300 ÷ 70 = 4.286, matching the published 4.28×.
     */
    EXPLORER_300(300f, 0f, PhoneModel.FIND_X9_ULTRA),

    /** Hasselblad Professional Teleconverter Kit: 230 ÷ 70 = 3.286, published as 3.28×. */
    HASSELBLAD_230(230f, 0f, PhoneModel.FIND_X9_PRO),

    /** The Find X10 Pro Max kit: OPPO prints "230 mm" on its 70 mm telephoto, the same 3.28× glass. */
    HASSELBLAD_230_X10_PRO_MAX(230f, 0f, PhoneModel.FIND_X10_PRO_MAX),

    /**
     * The kit OPPO sells for its 65 mm telephotos (Find X10; Find X9s Pro). OPPO prints "253 mm" and
     * footnotes that its teleconverter focals are "based on … software cropping": 253 ÷ 65 = 3.89 is
     * a crop, not glass. Modelled as the 3.28× optic OPPO prints as 230 mm on its 70 mm bodies (the
     * same kit name on the X10 Pro Max; dpreview and kit reviews give 3.28× for the X9s Pro kit), so
     * the app reports the OPTICAL 3.286 × 65 ≈ 214 mm. Labelled "253 mm" so the owner recognises it.
     * Inferred, not printed by OPPO as a magnification — CUSTOM covers a different reading.
     */
    HASSELBLAD_X10(230f, 0f, PhoneModel.FIND_X10, designHostMm = 70f),
    HASSELBLAD_X9S_PRO(230f, 0f, PhoneModel.FIND_X9S_PRO, designHostMm = 70f),

    /** vivo ZEISS 2.35× extender (Gen 1 and Gen 2 are the same 2.35× glass): 200 ÷ 85 = 2.353. */
    ZEISS_200_X200(200f, 0f, PhoneModel.VIVO_X200_ULTRA),

    /** The same ZEISS 2.35× optic, which the X300 series also takes. */
    ZEISS_200_X300(200f, 0f, PhoneModel.VIVO_X300_ULTRA),

    /**
     * vivo ZEISS 4.7× "Gen 2 Ultra": 400 ÷ 85 = 4.706, published as 4.7×. MUST stay declared AFTER
     * [ZEISS_200_X300]: [defaultConverterFor] takes the first kit of a phone, and the X300 Ultra's
     * default must be the base 2.35× optic, not the exotic one (pinned by a test).
     */
    ZEISS_400(400f, 0f, PhoneModel.VIVO_X300_ULTRA),

    /** The same 2.35× glass on the rest of the X300 line (vivo FAQ 2137: 85 → 200 mm on each). */
    ZEISS_200_X300_BASE(200f, 0f, PhoneModel.VIVO_X300),
    ZEISS_200_X300_PRO(200f, 0f, PhoneModel.VIVO_X300_PRO),
    ZEISS_200_X300S(200f, 0f, PhoneModel.VIVO_X300S),

    /**
     * Gen 2 on the X500, whose telephoto is 72 mm. vivo labels it "200 mm-equivalent" there but does
     * not state a different magnification for the same G2 glass, so it stays the 2.35× optic designed
     * for 85 mm: optically 2.353 × 72 ≈ 169 mm, the figure the app reports.
     */
    ZEISS_200_X500(200f, 0f, PhoneModel.VIVO_X500, designHostMm = 85f),
    ZEISS_200_X500_PRO(200f, 0f, PhoneModel.VIVO_X500_PRO),
    ZEISS_200_X500_PRO_MAX(200f, 0f, PhoneModel.VIVO_X500_PRO_MAX),

    /** Gen 2 Ultra, which vivo calls exclusive to the X500 Pro Max in that line. After the base kit. */
    ZEISS_400_X500_PRO_MAX(400f, 0f, PhoneModel.VIVO_X500_PRO_MAX),

    /** HONOR's 2.35× telephoto extender: 200 mm on the Magic8 Pro's 85 mm telephoto (honor.com). */
    HONOR_200_MAGIC8_PRO(200f, 0f, PhoneModel.HONOR_MAGIC8_PRO),
    HONOR_200_MAGIC8_RSR(200f, 0f, PhoneModel.HONOR_MAGIC8_RSR),

    /**
     * HONOR G200 / G500. HONOR prints focals only (200 / 500 mm); the magnifications 2.35× / 5.88× are
     * those focals ÷ the Magic9 Pro Max's 85 mm telephoto, the same derivation every kit here uses.
     * The plain Magic9's telephoto is 80 mm, so the same G200 glass reads 2.353 × 80 ≈ 188 mm there.
     * G500 after G200 for the same default-order reason as the ZEISS pair.
     */
    HONOR_G200_MAGIC9(200f, 0f, PhoneModel.HONOR_MAGIC9, designHostMm = 85f),
    HONOR_G200_MAGIC9_PRO_MAX(200f, 0f, PhoneModel.HONOR_MAGIC9_PRO_MAX),
    HONOR_G500(500f, 0f, PhoneModel.HONOR_MAGIC9_PRO_MAX),

    /** Generic clip-ons: the barrel states a magnification, not a focal, so it is intrinsic. */
    GENERIC_1_5(0f, 1.5f, null),
    GENERIC_2(0f, 2f, null),
    GENERIC_3(0f, 3f, null),

    /** User-entered magnification; see [effectiveMagnification]. */
    CUSTOM(0f, 0f, null),
    ;

    val isCustom: Boolean get() = this == CUSTOM

    /**
     * The converter's angular magnification. For a kit this is (sold focal ÷ the telephoto the glass
     * was designed for — [designHostMm] when set, else ITS OWN host phone's) — deliberately not the
     * phone currently selected, because the glass does not change when it is moved. [CUSTOM] returns
     * 0; callers must use [effectiveMagnification].
     */
    val magnification: Float
        get() = phone?.let { soldFocalMm / (designHostMm.takeIf { mm -> mm > 0f } ?: it.teleEquivMm) }
            ?: intrinsicMagnification
}

/** The converter the app assumes when nothing has been chosen or restored. */
val DEFAULT_TELECONVERTER_PROFILE = TeleconverterProfile.EXPLORER_300

/**
 * The Explorer's magnification, kept as a named constant because it is the default, the value every
 * pre-existing test and document quotes, and the anchor for the nominal 300 mm readouts.
 */
const val TELECONVERTER_MAGNIFICATION = 300f / 70f

/**
 * One complete user declaration of the converter attached to the phone.
 *
 * The four stored fields deliberately travel together. [phone] owns the named host focal,
 * [profile] owns the glass magnification, [customMagnification] owns CUSTOM's value, and
 * [hostTeleEquivMm] is the measured fallback used only when [PhoneModel.OTHER] declares no known
 * host. Splitting these across independent Engine setters let MR recall pair an incoming converter
 * with the outgoing phone's focal and left asynchronous rollback unable to restore either half.
 */
data class TeleconverterDeclaration(
    val phone: PhoneModel,
    val profile: TeleconverterProfile,
    val customMagnification: Float,
    val hostTeleEquivMm: Float,
) {
    val magnification: Float
        get() = effectiveMagnification(profile, customMagnification)
}

/** Builds a reconciled declaration whose named-phone host can never disagree with its identity. */
internal fun teleconverterDeclaration(
    phone: PhoneModel,
    profile: TeleconverterProfile,
    customMagnification: Float,
    measuredOtherHostEquivMm: Float = LensChoice.TELE3X.targetEquivMm,
): TeleconverterDeclaration {
    val host = if (phone == PhoneModel.OTHER) {
        measuredOtherHostEquivMm.takeIf { it.isFinite() && it > 0f }
            ?: LensChoice.TELE3X.targetEquivMm
    } else {
        phone.teleEquivMm
    }
    return TeleconverterDeclaration(
        phone = phone,
        profile = reconcileConverter(phone, profile),
        customMagnification = normalizeMagnification(customMagnification),
        hostTeleEquivMm = host,
    )
}

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
