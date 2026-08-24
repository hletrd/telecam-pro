package me.hletrd.telecampro.storage

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SharedPreferencesDurableEditTest {
    @Test
    fun `putString preserves a failed synchronous commit result`() {
        var written: Pair<String, String>? = null
        var editorProxy: SharedPreferences.Editor? = null
        editorProxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    written = args[0] as String to args[1] as String
                    editorProxy
                }
                "commit" -> false
                else -> error("Unexpected Editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "edit" -> editorProxy
                else -> error("Unexpected SharedPreferences method ${method.name}")
            }
        } as SharedPreferences

        val committed = SharedPreferencesDurableEdit.putString(preferences, "capture", "complete")

        assertEquals("capture" to "complete", written)
        assertFalse(committed)
    }

    @Test
    fun `remove preserves a failed synchronous commit result`() {
        var removed: String? = null
        var editorProxy: SharedPreferences.Editor? = null
        editorProxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "remove" -> {
                    removed = args[0] as String
                    editorProxy
                }
                "commit" -> false
                else -> error("Unexpected Editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "edit" -> editorProxy
                else -> error("Unexpected SharedPreferences method ${method.name}")
            }
        } as SharedPreferences

        val committed = SharedPreferencesDurableEdit.remove(preferences, "capture")

        assertEquals("capture", removed)
        assertFalse(committed)
    }
}
