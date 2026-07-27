package me.hletrd.findx9tele.camera

/**
 * Pure device/lens labels written into saved-file EXIF.
 *
 * These used to be literals and id tables measured on one phone: `TAG_MAKE`/`TAG_MODEL` were
 * hardcoded to "OPPO" / "OPPO Find X9 Ultra", and the lens description keyed off camera ids
 * "2"/"3"/"4"/"5" with `else -> "tele"`. On any other handset that wrote a FALSE camera model and a
 * FALSE lens name into the user's files — a rear lens the table did not know became "tele", and the
 * focal band came from a phone the shot was not taken on.
 *
 * Everything here derives from the running build and from the MEASURED 35 mm-equivalent focal
 * length, which is the same rule `CameraSelector2` already follows for picking hardware: resolve by
 * what the camera advertises, never by an id or a model string.
 */

/**
 * EXIF `Make`, or null when the build reports nothing usable (the tag is then omitted rather than
 * written empty).
 */
internal fun exifMake(manufacturer: String?): String? =
    manufacturer?.trim()?.takeIf { it.isNotEmpty() }

/**
 * EXIF `Model`. This is the model IDENTIFIER, which is what the tag means — photo software maps it
 * to a marketing name from its own database. The previous code imitated the stock app by writing a
 * market name instead, which is only knowable for phones we happen to have a table for.
 */
internal fun exifModel(model: String?): String? =
    model?.trim()?.takeIf { it.isNotEmpty() }

/**
 * "OPPO PMA110" — the device half of the lens description. Avoids repeating the manufacturer when
 * the model already carries it (Google reports "Pixel 8 Pro" for a "Google" make; OPPO reports the
 * bare code), and degrades to whichever half exists.
 */
internal fun deviceLabel(manufacturer: String?, model: String?): String {
    val make = exifMake(manufacturer)
    val mdl = exifModel(model)
    return when {
        make == null -> mdl.orEmpty()
        mdl == null -> make
        mdl.startsWith(make, ignoreCase = true) -> mdl
        else -> "$make $mdl"
    }
}

/**
 * Lens name for the EXIF lens description, from the MEASURED 35 mm-equivalent focal length.
 *
 * Bands follow ordinary photographic naming rather than any one phone's lineup, so a handset with
 * two ultra-wides or no periscope still gets a truthful word. [frontFacing] short-circuits because
 * facing is enumerated and a selfie camera is never a "tele" whatever its focal length is.
 */
internal fun lensNameForEquiv(equivMm: Float, frontFacing: Boolean): String = when {
    frontFacing -> "front"
    equivMm <= 0f -> "camera"
    equivMm < 20f -> "ultra-wide"
    equivMm < 40f -> "wide"
    equivMm < 100f -> "tele"
    else -> "periscope tele"
}

/**
 * The full EXIF `LensModel`, e.g. "OPPO PMA110 wide camera 23mm f/1.6".
 *
 * [equivMm] is the measured 35 mm equivalent and [apertureF] the lens f-number; the f-number is
 * TRUNCATED, not rounded, matching how lens barrels are marked (f/2.26 reads "f/2.2").
 */
internal fun exifLensModel(
    manufacturer: String?,
    model: String?,
    equivMm: Float,
    apertureF: Float,
    frontFacing: Boolean,
): String {
    val device = deviceLabel(manufacturer, model)
    val name = lensNameForEquiv(equivMm, frontFacing)
    val mm = Math.round(equivMm.coerceAtLeast(0f))
    val fTrunc = kotlin.math.floor(apertureF * 10f) / 10f
    val fText = "%.1f".format(java.util.Locale.US, fTrunc)
    return listOf(device, "$name camera", "${mm}mm", "f/$fText")
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
