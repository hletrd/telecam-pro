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
    /**
     * How the MAKER numbers zoom on this phone ([MakerZoom]). Null only for [OTHER], which declares
     * no maker and keeps the measured-main rule ([oneXReferenceMm]); a test pins it non-null on every
     * named phone, so a new row cannot silently fall back to the generic scale.
     */
    val makerZoom: MakerZoom? = null,
) {
    // Build.MODEL codes: Google Play supported-devices CSV (play_public/supported_devices.csv,
    // fetched 2026-10-25, last modified 2026-10-24). Telephoto focals: the makers' own spec pages.
    // A phone with NO code in that list is still offered for MANUAL selection — detection only
    // preselects, so a missing code costs the auto-pick and nothing else.
    // Maker zoom rows (2026-10-25): lens focals, the focal the maker calls 1x and its rail chips,
    // from the same spec pages; each row names its lenses (ultrawide / main / tele) and its source.
    // A chip list marked "unverified" was not found on an official page: those rows keep 0.6x for
    // the ultrawide and the enumeration's say over 10x. MakerZoom(1x mm, declared main mm, chips).
    // OPPO: Find X9 Ultra / X9 Pro (oppo.com product pages), Find X9s Pro and Find X10 / X10 Pro Max
    // (oppo.com/cn and oppo.com/en, 2026-09-22 launch). PMA120 = Find X9 Ultra satellite edition.
    // Find X9 Ultra: 14 / 23 / 70 / 230 mm, 1x = 23 mm (oppo.com); stock chips 0.6 · 1 · 2 · 3 · 6 ·
    // 10, of which the rail has kept 0.6 / 1 / 3 / 10 since PMA110 shipped.
    FIND_X9_ULTRA(
        70f,
        listOf("PMA110", "CPH2841", "PMA120"),
        MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 3f),
    ),
    // Find X9 Pro: 15 / 23 / 70 mm, 1x = 23 mm (oppo.com); chips unverified. OPPO prints its kit's
    // TC stops as 10 / 20 / 40x: the 10x is the optical floor, 20 / 40 snap. (Literal lists, not a
    // shared top-level val: a PhoneModel row reading TeleconverterKt would initialise that file
    // class mid-enum, and its DEFAULT_TELECONVERTER_PROFILE would then build TeleconverterProfile
    // against PhoneModel entries not yet assigned.)
    FIND_X9_PRO(
        70f,
        listOf("PLG110", "PLG120", "CPH2791"),
        MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 3f, teleZoomStops = listOf(20f, 40f)),
    ),
    // Find X9s Pro: 15 / 23 / 65 mm, 1x = 23 mm; official chips 0.6 · 1 · 2 · 2.8 · 5.6 (oppo.com/cn).
    FIND_X9S_PRO(65f, listOf("PME110"), MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 2.8f, hidesTenX = true)),
    // Find X10: 15 / 23 / 65 mm, 1x = 23 mm; official chips 0.6 · 1 · 2 · 2.8 · 5.6 (oppo.com/cn, /en).
    // TC stops 11 / 20 / 40x, where 11x is a crop of the kit's ~9.3x optical floor: 20 / 40 snap.
    FIND_X10(
        65f,
        listOf("PMW110", "CPH2911", "OPG09"),
        MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 2.8f, hidesTenX = true, teleZoomStops = listOf(20f, 40f)),
    ),
    // Find X10 Pro Max: 13 / 24 / 70 mm, 1x = 24 mm; official chips 0.5 · 1 · 2 · 3 · 6 (oppo.com).
    // TC stops 10 / 20 / 40x (the 10x is the optical floor, ≈ 9.6x on the 24 mm scale).
    FIND_X10_PRO_MAX(
        70f,
        listOf("PMX110", "CPH2913"),
        MakerZoom(24f, 24f, ultraWideChip = 0.5f, teleChip = 3f, hidesTenX = true, teleZoomStops = listOf(20f, 40f)),
    ),

    // vivo: the ZEISS extenders are built for an 85 mm telephoto (vivo FAQ 1925 / 2137); the X300
    // Ultra's 14/35/85 mm and the X500's 72 mm are from vivo.com / vivo.com.cn spec pages.
    // V2547DA is also the X300 Ultra satellite edition; V2602DA the X500 Pro Max satellite edition.
    // The X300 and X300s carry a 70 mm telephoto, not 85 (vivo.com.cn: "3x光学变焦"; the X300s page
    // adds "200mm 需要进行数码裁切后实现" — their ZEISS 200 mm needs an in-camera crop).
    // X200 Ultra: 14 / 35 / 85 mm; vivo.com.cn "以 23mm 为 1x", so the 35 mm main is 1.5x and the
    // 85 mm tele 3.7x; chips otherwise unverified.
    VIVO_X200_ULTRA(
        85f,
        listOf("V2454A", "V2454DA"),
        MakerZoom(23f, 35f, ultraWideChip = 0.6f, mainChip = 1.5f, teleChip = 3.7f),
    ),
    // X300: 15 / 23 / 70 mm, 1x = 23 mm (vivo.com.cn, "3x光学变焦"); chips unverified.
    VIVO_X300(70f, listOf("V2509A", "V2515"), MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 3f)),
    // X300 Pro: 15 / 24 / 85 mm, 1x = 24 mm (vivo.com.cn, "3.5x"); 0.6x, 3.5x and 10x seen there.
    VIVO_X300_PRO(
        85f,
        listOf("V2502A", "V2502DA", "V2514"),
        MakerZoom(24f, 24f, ultraWideChip = 0.6f, teleChip = 3.5f),
    ),
    // X300s: 15 / 23 / 70 mm, 1x = 23 mm (vivo.com.cn, "3x"); chips unverified.
    VIVO_X300S(70f, listOf("V2548A"), MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 3f)),
    // X300 Ultra: 14 / 35 / 85 mm; vivo.com.cn "*1x 指 23mm", so the 35 mm main is 1.5x and the
    // 85 mm tele 3.7x; chips otherwise unverified.
    VIVO_X300_ULTRA(
        85f,
        listOf("V2547A", "V2547DA", "V2562"),
        MakerZoom(23f, 35f, ultraWideChip = 0.6f, mainChip = 1.5f, teleChip = 3.7f),
    ),
    // X500: 15 / 23 / 72 mm, 1x = 23 mm (vivo.com.cn, "3.1x"); chips unverified.
    VIVO_X500(72f, listOf("V2609A"), MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 3.1f)),
    // X500 Pro and X500 Pro Max: 15 / 24 / 85 mm, 1x = 24 mm (vivo.com.cn, "3.5x"); chips unverified.
    VIVO_X500_PRO(
        85f,
        listOf("V2608A", "V2608DA", "V2621", "V2622"),
        MakerZoom(24f, 24f, ultraWideChip = 0.6f, teleChip = 3.5f),
    ),
    VIVO_X500_PRO_MAX(
        85f,
        listOf("V2602A", "V2602DA", "V2610"),
        MakerZoom(24f, 24f, ultraWideChip = 0.6f, teleChip = 3.5f),
    ),

    // HONOR: honor.com kit and phone pages. Only the global Magic8 Pro is in Google Play's list.
    // Magic8 Pro: 12 / 23 / 85 mm, 1x = 23 mm (honor.com, "3.7x"); chips unverified — its 12 mm
    // ultrawide is 12 ÷ 23 = 0.52, which no maker prints as 0.6x.
    HONOR_MAGIC8_PRO(85f, listOf("BKQ-N49"), MakerZoom(23f, 23f, ultraWideChip = 0.5f, teleChip = 3.7f)),
    // Magic8 RSR: ? / 23 / 85 mm, 1x = 23 mm (honor.com/cn, "3.7x"); ultrawide and chips unverified.
    HONOR_MAGIC8_RSR(85f, makerZoom = MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 3.7f)),
    // Magic9: ? / 23 / 80 mm, 1x = 23 mm (honor.com/cn, "3.5X"); ultrawide and chips unverified.
    HONOR_MAGIC9(80f, makerZoom = MakerZoom(23f, 23f, ultraWideChip = 0.6f, teleChip = 3.5f)),
    // Magic9 Pro Max: ? / 24 / 85 mm, 1x = 24 mm (honor.com/cn, "3.5X"); ultrawide and chips
    // unverified.
    HONOR_MAGIC9_PRO_MAX(85f, makerZoom = MakerZoom(24f, 24f, ultraWideChip = 0.6f, teleChip = 3.5f)),

    /**
     * Anything without a first-party kit. Offers only the generic clip-ons and CUSTOM, so it needs
     * no host focal of its own — those magnifications are intrinsic. It declares no [makerZoom]
     * either: an unknown phone's 1× is its MEASURED main lens ([oneXReferenceMm]).
     */
    // Labelled just "Other": the row it renders in is already labelled "Phone", so the old
    // "Other phone" read back as "Phone   Other phone".
    OTHER(70f),
    ;

    /** The converters that clamp onto this phone, plus the ones that fit anything. */
    fun converters(): List<TeleconverterProfile> =
        TeleconverterProfile.entries.filter { it.phone == null || it.phone == this }
}

/**
 * How one maker numbers zoom on its own phone (owner decision 2026-10-25: "follow how the maker does
 * it"). The phone is the operator's DECLARATION — [detectPhone] only preselects it — so this is a
 * product fact about the stock camera's scale, never a capability: which lenses exist and what the
 * zoom range covers are still ENUMERATED from Camera2 (`LensInventory`).
 */
data class MakerZoom(
    /**
     * The 35 mm-equivalent focal the maker calls "1×" (23 mm, or 24 mm on the phones built around a
     * 24 mm main). Every main-relative DISPLAY number divides by it ([oneXReferenceMm]): the HUD pill,
     * the Fn/ruler values and the TC marks read the maker's own scale on that phone.
     */
    val oneXEquivMm: Float,
    /**
     * The DECLARED main lens. It equals [oneXEquivMm] except on the vivo X200 / X300 Ultra, whose
     * 35 mm main is the maker's "1.5×". Only the stand-in for the logical camera's MEASURED
     * equivalent until the lens enumeration publishes one ([logicalZoomDisplayFactor]).
     */
    val mainEquivMm: Float,
    /**
     * The rail chip numbers, on the maker's 1×-relative scale: what the ULTRAWIDE, MAIN and TELE3X
     * chips read and the zoom they apply (`ZoomPresets`). The ultrawide is round(ultrawide ÷ 1×) in
     * the maker's own style — 0.6× for its 14–15 mm lenses — the main is 1× unless the main is not
     * the 1× lens (vivo's 35 mm "1.5×"), and the tele is the "N×" the maker prints for it.
     */
    val ultraWideChip: Float,
    val mainChip: Float = 1f,
    val teleChip: Float,
    /**
     * True where the maker's OFFICIAL chip row has no 10× and the phone has no 10× lens: TELE10X is
     * then not offered, however far the zoom range reaches. False defers to the enumeration, which
     * is also the answer for every row whose chips are unverified.
     */
    val hidesTenX: Boolean = false,
    /**
     * The maker's TC magnetic stops, in total magnification, where its kit page prints them; null
     * keeps the generic [TELE_ZOOM_SNAPS] ([teleZoomSnapsFor]).
     */
    val teleZoomStops: List<Float>? = null,
)

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
     * The kit OPPO sells for its 65 mm telephotos (Find X10; Find X9s Pro). OPPO's Find X10 launch
     * press release states "3.28x optical magnification" for the series' kits, and its product page
     * prints "253mm" with footnote 5: equivalent focals are "based on OPPO laboratory testing and
     * software cropping" (253 = 11 × 23 mm). So the glass is 3.28× and the app reports the OPTICAL
     * 3.286 × 65 ≈ 214 mm; the label keeps OPPO's "253 mm" so the owner recognises the kit. The X9s
     * Pro's own page prints no focal for its kit; 3.28× there is from secondary sources (163.com).
     * OPPO warns the X10 and X10 Pro Max kits are NOT interchangeable, hence separate entries.
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

    /**
     * The same 2.35× glass on the rest of the X300 line (vivo FAQ 2137). The X300 Pro has the 85 mm
     * telephoto it was computed on; the X300 and X300s have a 70 mm one, where vivo's "200 mm" is
     * "需要进行数码裁切后实现" (reached with an in-camera crop). The glass stays 2.35×, so on those two
     * the app reports the OPTICAL 2.353 × 70 ≈ 165 mm.
     */
    ZEISS_200_X300_BASE(200f, 0f, PhoneModel.VIVO_X300, designHostMm = 85f),
    ZEISS_200_X300_PRO(200f, 0f, PhoneModel.VIVO_X300_PRO),
    ZEISS_200_X300S(200f, 0f, PhoneModel.VIVO_X300S, designHostMm = 85f),

    /**
     * Gen 2 on the X500, whose telephoto is 72 mm. vivo.com.cn prints "等效 200mm vivo 蔡司增距镜 G2*"
     * with the footnote "等效焦段切换数据源自 vivo 实验室，结合机内拍照模式，数码裁切而得" — the 200 mm
     * includes an in-camera digital crop. The glass is the 2.35× optic vivo states on its 85 mm
     * bodies, so the app reports the OPTICAL 2.353 × 72 ≈ 169 mm.
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
     * HONOR G200 / G500. honor.com/cn prints "叠加 85mm 镜头，等效 200mm" / "等效 500mm" for the Magic9
     * Pro Max, so the magnifications are 200 / 85 = 2.35× and 500 / 85 = 5.88× — the same derivation
     * every kit here uses. The Magic9's own G200 SKU prints "200mm ～ 5400mm … 基于荣耀Magic9 产品换算"
     * with the footnote that equivalent zoom is "结合机内拍摄模式，数码裁切而得" (an in-camera crop), so
     * on its 80 mm telephoto the 2.35× glass is OPTICALLY 2.353 × 80 ≈ 188 mm, the figure reported.
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

    /**
     * [teleDisplayBase] for THIS declaration on a device whose measured main lens is
     * [measuredMainEquivMm] (the inventory's MAIN preset focal; null before enumeration). The
     * declaration already owns the magnification, the host focal and the PHONE whose 1× the scale
     * divides by ([oneXReferenceMm]), so the engine and the settings/MR restore path read the scale
     * through here instead of re-pairing halves — `CameraUiState.teleDisplayBase` is the same law
     * over the live UI state.
     */
    internal fun displayBase(measuredMainEquivMm: Float?): Float =
        teleDisplayBase(magnification, hostTeleEquivMm, oneXReferenceMm(phone, measuredMainEquivMm))
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
 * TELE mode's DISPLAY zoom scale (converter-equivalent, main-relative): local 1.0 on the converter's
 * host lens is ([hostTeleEquivMm] ÷ [mainEquivMm]) × [magnification] — the 4.286× kit on PMA110's
 * 70 mm periscope over its 23 mm main ≈ 13×. Formerly the top-level `TELE_DISPLAY_BASE` constant; it
 * became a function when the magnification became selectable, and took its two lens focals when the
 * converter stopped being PMA110-only.
 *
 * The one-argument form hardwired PMA110's NOMINAL 70 ÷ 23, so on every other host every TOTAL-
 * magnification number was wrong while the focal readout beside it was right: ZEISS 4.7× on a vivo
 * X300 Ultra's 85 mm periscope drew a "14×" floor instead of ~17×, and the 13×/30×/60× marks, the
 * magnetic snaps, the pill and the local-zoom ceiling all inherited that error. The inputs are now
 * the SAME ones the focal readout uses:
 *  - [hostTeleEquivMm] is `CameraUiState.teleconverterHostEquivMm` / [TeleconverterDeclaration.
 *    hostTeleEquivMm] — the DECLARED phone's tele for a named kit phone, the MEASURED host lens
 *    ([LensInventory.teleHostEquivMm]) for [PhoneModel.OTHER];
 *  - [mainEquivMm] is [oneXReferenceMm] — the focal the declared phone's MAKER calls 1×
 *    ([MakerZoom.oneXEquivMm]: 23 mm, or 24 mm on a 24 mm-main phone), and on [PhoneModel.OTHER]
 *    the nominal 23 mm while the measured main is within [LENS_CAPTION_ROUNDING_BAND] of it, the
 *    measured main otherwise (the same rule the standalone zoom pill divides by, so TC and non-TC
 *    readouts share one "1×"). So the ZEISS 2.35× on a vivo X300 Ultra floors at 85 × 2.353 ÷ 23 ≈
 *    8.7× and the Gen 2 Ultra at ≈ 17.4× — vivo's own numbers — not ÷ its measured 35 mm main.
 *
 * PMA110 is byte-identical by construction: FIND_X9_ULTRA declares exactly 70 mm and a 23 mm 1×,
 * so this evaluates the very `(70f / 23f) * magnification` expression it replaced. A non-finite or
 * non-positive [hostTeleEquivMm] / [mainEquivMm] falls back to that nominal 70 / 23 rather than
 * producing an infinite or NaN scale; [magnification] is passed through untouched because
 * [effectiveMagnification] already guarantees it finite and in range.
 * There are deliberately NO default parameter values (AGG4-47 / AGG5-50): an omitted lens input
 * used to compile into the PMA110 answer, which is exactly the defect this signature removes.
 *
 * [TELE_MAX_DISPLAY_ZOOM] stays a FIXED display ceiling rather than scaling with the converter, so
 * the local-zoom ceiling (`TELE_MAX_DISPLAY_ZOOM / teleDisplayBase`) moves inversely with
 * magnification: a weaker converter earns more digital zoom before hitting the same total. That is
 * the intended reading — 60× is a cap on total magnification, not on the digital portion — and the
 * ordinary capability reconciliation still clamps the result to what the lens actually offers.
 */
fun teleDisplayBase(magnification: Float, hostTeleEquivMm: Float, mainEquivMm: Float): Float {
    val host = hostTeleEquivMm.takeIf { it.isFinite() && it > 0f } ?: LensChoice.TELE3X.targetEquivMm
    val main = mainEquivMm.takeIf { it.isFinite() && it > 0f } ?: LensChoice.MAIN.targetEquivMm
    return (host / main) * magnification
}
