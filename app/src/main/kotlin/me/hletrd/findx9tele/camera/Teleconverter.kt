package me.hletrd.findx9tele.camera

/**
 * Teleconverter optics: which converter is clamped to the 3× periscope, and what that does to the
 * effective focal length.
 *
 * ## Why this is a SETTING and not a detection
 *
 * An afocal teleconverter is passive glass on a clamp. It has no electrical contact, no ID, and no
 * way to announce itself — so the app can NEVER know whether one is mounted or which one it is.
 * That is why [CameraUiState.teleconverterMode] has always been a manual toggle, and it is why the
 * magnification is a manual choice too.
 *
 * What the app CAN read is the PHONE ([android.os.Build.MODEL]). So [detectProfile] does the only
 * honest thing available: it matches the phone against [TeleconverterProfile.deviceModels] and
 * pre-selects the converter that ships for it. The UI must present that as a suggested default —
 * "this phone's kit converter" — never as "a converter was detected".
 *
 * Note this is the one place in the codebase that keys off a model string. Everywhere else resolves
 * hardware by ENUMERATING Camera2 capabilities (`CameraSelector2` picks lenses by measured
 * equivalent focal, never by id), and that rule still stands: a model match here only changes which
 * entry starts selected, and the user can override it to anything. No capability, route, or request
 * decision may ever branch on a model string.
 *
 * ## Why the catalog covers phones this build does not run on
 *
 * The shipping app is Find X9 Ultra-only, so most [deviceModels] entries can never match here. They
 * are still worth carrying: what a preset really contributes is a MAGNIFICATION, and a magnification
 * is a property of the glass, not of the phone it was sold with. Mounting a 2.35× converter on this
 * phone's 70 mm periscope yields 165 mm, not the 200 mm printed on the barrel — the UI therefore
 * always states the computed focal for the ACTIVE lens rather than repeating the product name's
 * number. The device lists then cost nothing and are already correct if this ever ships wider.
 */
enum class TeleconverterProfile(
    val label: String,
    /**
     * Angular magnification of the converter, written as (converter focal ÷ the host tele focal it
     * was designed for) so the arithmetic is auditable against the manufacturer's own number.
     * [CUSTOM] carries no fixed value — its magnification comes from the user's
     * [CameraUiState.teleconverterCustomMagnification] instead.
     */
    val magnification: Float,
    /** The product and host phone this magnification comes from. Empty for the generic entries. */
    val kit: String = "",
    /**
     * Phone models this converter is sold for, matched case-insensitively against `Build.MODEL`.
     * Empty means "fits nothing in particular" — a generic clip-on, or an optic whose host phone
     * already has a different default (see [ZEISS_400]).
     */
    val deviceModels: List<String> = emptyList(),
) {
    /**
     * Hasselblad "Earth Explorer" 300 mm, for the Find X9 Ultra's 3× (~70 mm) periscope: a Kepler
     * afocal converter with 16 glass elements, sold as 300 mm / 4.28×. 300 ÷ 70 = 4.286 reproduces
     * that. This is the optic the whole app was built around and the historical value of the former
     * `TELECONVERTER_MAGNIFICATION` constant.
     */
    EXPLORER_300("Hasselblad 300", 300f / 70f, "Find X9 Ultra kit", listOf("PMA110", "CPH2841")),

    /**
     * Hasselblad Professional Teleconverter Kit for the Find X9 Pro, whose 3× telephoto is also
     * ~70 mm equivalent: sold as 230 mm / 3.28×, and 230 ÷ 70 = 3.286. NOT interchangeable with the
     * Ultra's optic — each kit clamps to its own phone — but the magnification is the magnification.
     * Only the global model code is listed; the Chinese variant code was not confirmed, and an
     * unlisted model simply falls back to a manual pick.
     */
    HASSELBLAD_230("Hasselblad 230", 230f / 70f, "Find X9 Pro kit", listOf("CPH2791")),

    /**
     * vivo ZEISS 2.35× telephoto extender ("200 mm G2"), APO-certified, for the 85 mm periscope on
     * the X200 Ultra and the X300 series: 200 ÷ 85 = 2.353, matching the published 2.35×. Claims the
     * X300 Ultra as well as the X200 Ultra because it is that phone's BASE kit lens — see [ZEISS_400].
     */
    ZEISS_200(
        "ZEISS 200",
        200f / 85f,
        "vivo X200/X300 Ultra kit",
        listOf("V2454A", "V2454DA", "V2562"),
    ),

    /**
     * vivo ZEISS 4.7× telephoto extender ("400 mm G2 Ultra") for the X300 Ultra's 85 mm periscope:
     * 400 ÷ 85 = 4.706, matching the published 4.7×. Deliberately claims NO device even though its
     * host phone is known: that phone takes TWO official converters, [detectProfile] can only return
     * one, and defaulting to the exotic long optic over the base kit would be the wrong guess. A
     * user who mounts this one picks it explicitly.
     */
    ZEISS_400("ZEISS 400", 400f / 85f, "vivo X300 Ultra long optic"),

    /** Generic clip-on converters, for anything that is not a first-party kit. */
    GENERIC_1_5("1.5×", 1.5f),
    GENERIC_2("2×", 2f),
    GENERIC_3("3×", 3f),

    /** User-entered magnification; see [effectiveMagnification]. */
    CUSTOM("Custom", 0f),
    ;

    val isCustom: Boolean get() = this == CUSTOM
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
 * The converter that ships with [deviceModel], or null when the phone is not one we know a kit
 * optic for. Case-insensitive and whitespace-tolerant because `Build.MODEL` is vendor-formatted.
 */
fun detectProfile(deviceModel: String?): TeleconverterProfile? {
    val model = deviceModel?.trim().orEmpty()
    if (model.isEmpty()) return null
    return TeleconverterProfile.entries.firstOrNull { profile ->
        profile.deviceModels.any { it.equals(model, ignoreCase = true) }
    }
}

/**
 * The effective 35 mm-equivalent focal length through the converter.
 *
 * [baseEquivMm] is the NOMINAL focal of the lens the converter is clamped to (70 mm for the 3×),
 * deliberately not the caps-measured ~69.4 mm: the whole readout chain — OSD, EXIF, the zoom pill
 * marks — is built on round numbers so 13×/30×/60× read as 300/690/1380 mm rather than 680-ish.
 * Changing that here would desynchronise all three.
 *
 * This is why the UI never repeats a preset's product number: a "ZEISS 200" on THIS phone's 70 mm
 * periscope is 165 mm, and saying otherwise would put a false focal in the OSD and the EXIF.
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
