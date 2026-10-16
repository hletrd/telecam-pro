package me.hletrd.telecampro.storage;

import android.content.SharedPreferences;

/**
 * Synchronous preference mutations whose caller must observe the disk-commit result.
 *
 * <p>AndroidX's Kotlin {@code edit(commit = true)} helper returns {@code Unit}; these storage gates
 * need the Boolean returned by {@link SharedPreferences.Editor#commit()} so a failed durable write
 * remains fail-closed. Keeping the two operations here preserves that result without suppressing
 * Kotlin's KTX lint recommendation.
 */
final class SharedPreferencesDurableEdit {
    private SharedPreferencesDurableEdit() {}

    static boolean putString(SharedPreferences preferences, String key, String value) {
        return preferences.edit().putString(key, value).commit();
    }

    static boolean remove(SharedPreferences preferences, String key) {
        return preferences.edit().remove(key).commit();
    }
}
