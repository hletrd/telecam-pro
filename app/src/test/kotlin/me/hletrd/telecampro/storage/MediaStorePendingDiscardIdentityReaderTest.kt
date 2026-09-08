package me.hletrd.telecampro.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * Mainline MediaProvider answers an `EXTERNAL_CONTENT_URI` insert with a URI on the `external`
 * union pseudo-volume, which is never a mounted volume name (device-found 2026-09-09 on TB331FC and
 * TB336ZU). The reader must resolve that name the way the provider does, or every creation-time
 * identity read is "unavailable" and every capture is refused.
 */
@RunWith(RobolectricTestRunner::class)
class MediaStorePendingDiscardIdentityReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val unionUri = "content://media/external/images/media/94"
    private val primaryUri = "content://media/external_primary/images/media/94"

    @Test
    fun `a union-volume insert uri reads present on the primary volume`() {
        val provider = installProvider(rows = listOf(row(volume = "external_primary")))
        val reader = reader(mounted = setOf("external_primary"))

        val read = reader.read(unionUri)

        assertTrue(read.toString(), read is PendingDiscardIdentityRead.Present)
        val identity = (read as PendingDiscardIdentityRead.Present).identity
        assertEquals("external_primary", identity.volumeName)
        assertEquals("v1", identity.providerVersion)
        assertEquals(94L, identity.rowId)
        assertEquals("IMG_TELECAM_F1_1788909887546_0000000001.heic", identity.displayName)
        assertEquals(listOf("external_primary", "external_primary"), provider.versionVolumes)
    }

    @Test
    fun `a union-volume uri stays unavailable while the primary volume is unmounted`() {
        installProvider(rows = listOf(row(volume = "external_primary")))
        val reader = reader(mounted = emptySet())

        assertEquals(PendingDiscardIdentityRead.Unavailable, reader.read(unionUri))
    }

    @Test
    fun `a row that names another volume than the uri resolves to is ambiguous`() {
        installProvider(rows = listOf(row(volume = "1234-5678")))
        val reader = reader(mounted = setOf("external_primary", "1234-5678"))

        assertEquals(PendingDiscardIdentityRead.Ambiguous, reader.read(unionUri))
    }

    @Test
    fun `a provider that omits the volume column still reads present`() {
        installProvider(rows = listOf(row(volume = null)), includeVolumeColumn = false)
        val reader = reader(mounted = setOf("external_primary"))

        val read = reader.read(unionUri)

        assertTrue(read.toString(), read is PendingDiscardIdentityRead.Present)
        assertEquals("external_primary", (read as PendingDiscardIdentityRead.Present).identity.volumeName)
    }

    @Test
    fun `absence under a union uri is keyed to the resolved primary volume`() {
        installProvider(rows = emptyList())
        val reader = reader(mounted = setOf("external_primary"))

        assertEquals(
            PendingDiscardIdentityRead.Absent("external_primary", "v1"),
            reader.read(unionUri),
        )
    }

    @Test
    fun `a concrete volume uri keeps its own name`() {
        installProvider(rows = listOf(row(volume = "external_primary")))
        val reader = reader(mounted = setOf("external_primary"))

        val read = reader.read(primaryUri)

        assertTrue(read.toString(), read is PendingDiscardIdentityRead.Present)
        assertEquals("external_primary", (read as PendingDiscardIdentityRead.Present).identity.volumeName)
    }

    private fun reader(mounted: Set<String>): MediaStorePendingDiscardIdentityReader =
        MediaStorePendingDiscardIdentityReader(
            context = context,
            mountedVolumes = { mounted },
            providerVersion = { volume -> MediaStore.getVersion(context, volume) },
        )

    private fun row(volume: String?): Array<Any?> = arrayOf(
        94L,
        525L,
        "IMG_TELECAM_F1_1788909887546_0000000001.heic",
        "DCIM/TeleCamPro/",
        "image/heic",
        "me.hletrd.telecampro.debug",
        1788909887546L,
        volume,
    )

    private fun installProvider(
        rows: List<Array<Any?>>,
        includeVolumeColumn: Boolean = true,
    ): IdentityProvider {
        val provider = IdentityProvider(rows, includeVolumeColumn)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        return provider
    }

    private class IdentityProvider(
        private val rows: List<Array<Any?>>,
        private val includeVolumeColumn: Boolean,
    ) : ContentProvider() {
        val versionVolumes = mutableListOf<String>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor = cursor()

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = cursor()

        private fun cursor(): Cursor {
            val columns = mutableListOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.GENERATION_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.DATE_TAKEN,
            )
            if (includeVolumeColumn) columns += MediaStore.MediaColumns.VOLUME_NAME
            return MatrixCursor(columns.toTypedArray()).apply {
                rows.forEach { row -> addRow(if (includeVolumeColumn) row else row.dropLast(1).toTypedArray()) }
            }
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            // MediaStore.GET_VERSION_CALL is hidden from the SDK; this is its platform value.
            if (method != "get_version") return null
            versionVolumes += extras?.getString(android.content.Intent.EXTRA_TEXT) ?: "?"
            return Bundle().apply { putString(android.content.Intent.EXTRA_TEXT, "v1") }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
