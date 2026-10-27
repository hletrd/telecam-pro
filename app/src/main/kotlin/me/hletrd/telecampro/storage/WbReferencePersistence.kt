package me.hletrd.telecampro.storage

import android.content.Context
import android.content.SharedPreferences
import me.hletrd.telecampro.camera.WbReferenceSample
import me.hletrd.telecampro.camera.WbReferenceSnapshot
import me.hletrd.telecampro.camera.WbRouteKey
import me.hletrd.telecampro.camera.plausibleWbTransform

/**
 * Mirrors the engine's [me.hletrd.telecampro.camera.WbReferenceStore] — each route's own colour
 * transform, learned from AWB-AUTO frames — so a relaunch straight into manual white balance does
 * not have to run AUTO first to learn it again ("priming", WhiteBalance.kt). A cache of camera
 * facts, not a user setting: it lives in its own file, outside "Remember Settings" and the MR banks,
 * and a lost or corrupt entry only costs one priming detour.
 */
internal class WbReferencePersistence(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): WbReferenceSnapshot = WbReferenceSnapshot(
        samples = decodeWbReferences(runCatching { prefs.getString(KEY_TRANSFORMS, null) }.getOrNull()),
        swept = decodeWbSwept(runCatching { prefs.getString(KEY_SWEPT, null) }.getOrNull()),
    )

    /** Synchronous commit, like SettingsStore: callers are already off the main thread. */
    fun save(references: WbReferenceSnapshot): Boolean =
        runCatching {
            prefs.edit()
                .putString(KEY_TRANSFORMS, encodeWbReferences(references.samples))
                .putString(KEY_SWEPT, encodeWbSwept(references.swept))
                .commit()
        }.getOrDefault(false)

    companion object {
        const val PREFS_NAME = "wb_route_reference"
        const val KEY_TRANSFORMS = "transforms"
        const val KEY_SWEPT = "swept"
    }
}

/**
 * `key@kelvin=v0,…,v8` entries joined by ';' (`key=v0,…,v8` for a sample with no white point — the
 * only shape stored before 2026-10-27), keys from [WbRouteKey.encode] (no ';', '=', '@' or ',').
 */
internal fun encodeWbReferences(references: Map<WbRouteKey, List<WbReferenceSample>>): String =
    references.entries
        .sortedBy { it.key.encode() }
        .flatMap { (k, samples) ->
            // Kept in the store's order, newest first: a reload must rank them the same way.
            samples.filter { plausibleWbTransform(it.transform) }
                .map { sample ->
                    val kelvin = sample.kelvin?.let { "@" + Math.round(it) } ?: ""
                    k.encode() + kelvin + "=" + sample.transform.joinToString(",") { "%.5f".format(java.util.Locale.ROOT, it) }
                }
        }
        .joinToString(";")

/** Defensive inverse of [encodeWbReferences]: anything malformed or implausible is skipped. */
internal fun decodeWbReferences(raw: String?): Map<WbRouteKey, List<WbReferenceSample>> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val head = entry.substring(0, eq)
        val at = head.indexOf('@')
        val key = WbRouteKey.decode(if (at < 0) head else head.substring(0, at)) ?: return@mapNotNull null
        val kelvin = if (at < 0) {
            null
        } else {
            head.substring(at + 1).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
        }
        val values = entry.substring(eq + 1).split(',').map { it.toDoubleOrNull() ?: return@mapNotNull null }
        val transform = values.toDoubleArray().takeIf(::plausibleWbTransform) ?: return@mapNotNull null
        key to WbReferenceSample(kelvin, transform)
    }.groupBy({ it.first }, { it.second })
}

/** Swept route keys joined by ';'. */
internal fun encodeWbSwept(swept: Set<WbRouteKey>): String = swept.map { it.encode() }.sorted().joinToString(";")

internal fun decodeWbSwept(raw: String?): Set<WbRouteKey> =
    if (raw.isNullOrBlank()) emptySet() else raw.split(';').mapNotNull(WbRouteKey::decode).toSet()
