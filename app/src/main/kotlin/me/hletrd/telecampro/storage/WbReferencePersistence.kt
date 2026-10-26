package me.hletrd.telecampro.storage

import android.content.Context
import android.content.SharedPreferences
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

    fun load(): Map<WbRouteKey, DoubleArray> =
        decodeWbReferences(runCatching { prefs.getString(KEY_TRANSFORMS, null) }.getOrNull())

    /** Synchronous commit, like SettingsStore: callers are already off the main thread. */
    fun save(references: Map<WbRouteKey, DoubleArray>): Boolean =
        runCatching { prefs.edit().putString(KEY_TRANSFORMS, encodeWbReferences(references)).commit() }
            .getOrDefault(false)

    companion object {
        const val PREFS_NAME = "wb_route_reference"
        const val KEY_TRANSFORMS = "transforms"
    }
}

/** `key=v0,…,v8` entries joined by ';', keys from [WbRouteKey.encode] (no ';', '=' or ','). */
internal fun encodeWbReferences(references: Map<WbRouteKey, DoubleArray>): String =
    references.entries
        .filter { (_, t) -> plausibleWbTransform(t) }
        .sortedBy { it.key.encode() }
        .joinToString(";") { (k, t) -> k.encode() + "=" + t.joinToString(",") { "%.5f".format(java.util.Locale.ROOT, it) } }

/** Defensive inverse of [encodeWbReferences]: anything malformed or implausible is skipped. */
internal fun decodeWbReferences(raw: String?): Map<WbRouteKey, DoubleArray> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val key = WbRouteKey.decode(entry.substring(0, eq)) ?: return@mapNotNull null
        val values = entry.substring(eq + 1).split(',').map { it.toDoubleOrNull() ?: return@mapNotNull null }
        val transform = values.toDoubleArray().takeIf(::plausibleWbTransform) ?: return@mapNotNull null
        key to transform
    }.toMap()
}
