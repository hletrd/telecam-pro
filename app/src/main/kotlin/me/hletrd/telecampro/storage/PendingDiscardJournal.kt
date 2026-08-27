package me.hletrd.telecampro.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.edit

/** Ordered, cursorable ownership journal for exact MediaStore URIs that must be deleted. */
internal class PendingDiscardJournal(
    context: Context,
    private val databaseName: String = DATABASE_NAME,
    private val databaseVersion: Int = DATABASE_VERSION,
    private val legacyPreferences: SharedPreferences = context.getSharedPreferences(
        LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    ),
    private val readLegacyEntries: () -> Map<String, *> = { legacyPreferences.all },
    private val removeLegacyEntries: (Set<String>) -> Unit = { keys ->
        legacyPreferences.edit(commit = true) { keys.forEach(::remove) }
    },
    private val identityReader: PendingDiscardIdentityReader =
        MediaStorePendingDiscardIdentityReader(context.applicationContext),
) {
    private val applicationContext = context.applicationContext

    /**
     * Captures creation-time provider truth without collapsing stable absence into uncertainty.
     * A present row whose family does not match is uncertain, never absence: the URI may already
     * have been reassigned and no destructive caller may bless that replacement.
     */
    fun captureAllocationResult(
        uri: Uri,
        expectedFamily: CaptureFamilyKey,
    ): PendingAllocationCaptureResult = withUriAuthority(uri.toString()) {
        when (val read = identityReader.read(uri.toString())) {
            is PendingDiscardIdentityRead.Present -> {
                if (read.identity.familyIdentity == expectedFamily.discardIdentity()) {
                    PendingAllocationCaptureResult.Exact(
                        PendingOutputAllocation(uri, expectedFamily, read.identity),
                    )
                } else {
                    PendingAllocationCaptureResult.Uncertain
                }
            }
            is PendingDiscardIdentityRead.Absent -> PendingAllocationCaptureResult.Absent
            PendingDiscardIdentityRead.Ambiguous,
            PendingDiscardIdentityRead.Unavailable,
            -> PendingAllocationCaptureResult.Uncertain
        }
    }

    /** Legacy nullable facade retained for exact-allocation callers outside recovery. */
    fun captureAllocation(uri: Uri, expectedFamily: CaptureFamilyKey): PendingOutputAllocation? =
        (captureAllocationResult(uri, expectedFamily) as? PendingAllocationCaptureResult.Exact)
            ?.allocation

    /**
     * Commits DISCARD only for the immutable allocation owned by the caller and returns the exact
     * record that became durable. A fresh read can confirm the old allocation; it can never replace
     * that caller truth and bless whichever row happens to occupy the URI now.
     */
    fun mark(allocation: PendingOutputAllocation): PendingDiscardRecord? =
        withUriAuthority(allocation.uri.toString()) {
            val current = (identityReader.read(allocation.uri.toString()) as?
                PendingDiscardIdentityRead.Present)?.identity
                ?: return@withUriAuthority null
            if (current != allocation.identity ||
                current.familyIdentity != allocation.familyKey.discardIdentity()
            ) {
                return@withUriAuthority null
            }
            commitIdentity(allocation.uri.toString(), current)
        }

    /** Legacy/test snapshot API. Production destructive callers carry [PendingOutputAllocation]. */
    fun mark(uri: String): Boolean = withUriAuthority(uri) {
        val identity = when (val read = identityReader.read(uri)) {
            is PendingDiscardIdentityRead.Present -> read.identity
            is PendingDiscardIdentityRead.Absent,
            PendingDiscardIdentityRead.Ambiguous,
            PendingDiscardIdentityRead.Unavailable,
            -> return@withUriAuthority false
        }
        commitIdentity(uri, identity) != null
    }

    private fun commitIdentity(uri: String, identity: PendingDiscardIdentity): PendingDiscardRecord? =
        runCatching {
            synchronized(databaseLock) {
                withWritableDatabase { database ->
                    database.beginTransaction()
                    try {
                        database.execSQL(
                            "INSERT OR REPLACE INTO $DISCARD_TABLE (" +
                                "$URI_COLUMN, $RECORD_VERSION_COLUMN, $VOLUME_NAME_COLUMN, " +
                                "$PROVIDER_VERSION_COLUMN, $ROW_ID_COLUMN, " +
                                "$GENERATION_ADDED_COLUMN, $DISPLAY_NAME_COLUMN, " +
                                "$RELATIVE_PATH_COLUMN, $MIME_TYPE_COLUMN, $OWNER_PACKAGE_COLUMN, " +
                                "$FAMILY_IDENTITY_COLUMN, $DATE_TAKEN_COLUMN" +
                                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf<Any?>(
                                uri,
                                IDENTITY_RECORD_VERSION,
                                identity.volumeName,
                                identity.providerVersion,
                                identity.rowId,
                                identity.generationAdded,
                                identity.displayName,
                                identity.relativePath,
                                identity.mimeType,
                                identity.ownerPackageName,
                                identity.familyIdentity,
                                identity.dateTaken,
                            ),
                        )
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                }
            }
            // A failed cleanup is harmless: SQLite is already the durable authority, and a later
            // journal page removes the duplicate preference key idempotently.
            runCatching { removeLegacyEntries(setOf(uri)) }
            PendingDiscardRecord(uri, IDENTITY_RECORD_VERSION, identity)
        }.getOrNull()

    /** A database failure is not evidence that an exact delete owner is absent. */
    fun lookup(uri: String): DiscardJournalLookup = withUriAuthority(uri) {
        synchronized(databaseLock) { lookupLocked(uri) }
    }

    /**
     * Serializes a decision with [mark] and [remove] for this exact URI. The database monitor is
     * released before [block] runs, so provider calls and retry sleeps cannot stall journal work
     * for unrelated URIs.
     */
    fun <T> withLookupAuthority(uri: String, block: (DiscardJournalLookup) -> T): T =
        withUriAuthority(uri) {
            val lookup = synchronized(databaseLock) { lookupLocked(uri) }
            block(lookup)
        }

    /** Reads current provider truth for a page record without owning the SQLite monitor. */
    fun replayIdentity(record: PendingDiscardRecord): DiscardReplayIdentity =
        withReplayIdentityAuthority(record) { it }

    /** Keeps exact-URI publication/marker ordering linearized through the caller's replay action. */
    fun <T> withReplayIdentityAuthority(
        record: PendingDiscardRecord,
        block: (DiscardReplayIdentity) -> T,
    ): T = withUriAuthority(record.uri) {
        val expected = record.identity
        if (record.recordVersion != IDENTITY_RECORD_VERSION || expected == null) {
            return@withUriAuthority block(
                if (record.recordVersion < IDENTITY_RECORD_VERSION) {
                    DiscardReplayIdentity.LEGACY
                } else {
                    DiscardReplayIdentity.UNAVAILABLE
                },
            )
        }
        block(
            when (val current = identityReader.read(record.uri)) {
                is PendingDiscardIdentityRead.Present -> {
                    if (current.identity == expected) {
                        DiscardReplayIdentity.MATCH
                    } else {
                        DiscardReplayIdentity.MISMATCH
                    }
                }
                is PendingDiscardIdentityRead.Absent -> {
                    if (
                        current.volumeName == expected.volumeName &&
                        current.providerVersion == expected.providerVersion
                    ) {
                        DiscardReplayIdentity.ABSENT
                    } else {
                        DiscardReplayIdentity.MISMATCH
                    }
                }
                PendingDiscardIdentityRead.Ambiguous -> DiscardReplayIdentity.AMBIGUOUS
                PendingDiscardIdentityRead.Unavailable -> DiscardReplayIdentity.UNAVAILABLE
            },
        )
    }

    private fun lookupLocked(uri: String): DiscardJournalLookup = runCatching {
        withReadableDatabase { database ->
            if (
                database.query(
                    DISCARD_TABLE,
                    arrayOf(URI_COLUMN),
                    "$URI_COLUMN = ?",
                    arrayOf(uri),
                    null,
                    null,
                    null,
                    "1",
                ).use { it.moveToFirst() }
            ) {
                DiscardJournalLookup.PRESENT
            } else {
                DiscardJournalLookup.ABSENT
            }
        }
    }.getOrDefault(DiscardJournalLookup.UNAVAILABLE)

    /** Returns true only when this exact marker is authoritatively absent afterwards. */
    fun remove(uri: String): Boolean = withUriAuthority(uri) {
        runCatching {
            synchronized(databaseLock) {
                withWritableDatabase { database ->
                    database.delete(DISCARD_TABLE, "$URI_COLUMN = ?", arrayOf(uri))
                    database.query(
                        DISCARD_TABLE,
                        DISCARD_PROJECTION,
                        "$URI_COLUMN = ?",
                        arrayOf(uri),
                        null,
                        null,
                        null,
                        "1",
                    ).use { !it.moveToFirst() }
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Reads at most [batchLimit] + 1 indexed rows. The extra row proves continuation without a
     * table-sized materialization or sort in app memory.
     */
    fun page(afterKey: String?, batchLimit: Int): DiscardJournalPage {
        require(batchLimit > 0)
        // SharedPreferences.all may wait for filesystem-backed state to load. First prove whether a
        // snapshot is needed under the short database monitor, then perform that potentially
        // blocking read without owning unrelated exact-URI database work. A concurrent first page
        // may take the same harmless snapshot; migration rechecks its durable completion row below.
        val legacyKeys = if (legacyMigrationPending()) {
            readLegacyEntries()
                .filterValues { it == LEGACY_DISCARD_VALUE }
                .keys
                .toSet()
        } else {
            emptySet()
        }
        val page = synchronized(databaseLock) {
            migrateLegacyDiscardMarkers(legacyKeys)
            withReadableDatabase { database ->
                val queryLimit = batchLimit + 1
                val candidates = buildList(queryLimit) {
                    database.query(
                        DISCARD_TABLE,
                        DISCARD_PROJECTION,
                        "$URI_COLUMN > ?",
                        arrayOf(afterKey.orEmpty()),
                        null,
                        null,
                        "$URI_COLUMN ASC",
                        queryLimit.toString(),
                    ).use { cursor ->
                        while (cursor.moveToNext()) add(cursor.pendingDiscardRecord())
                    }
                }
                val records = candidates.take(batchLimit)
                DiscardJournalPage(
                    records = records,
                    nextAfterKey = records.lastOrNull()?.uri ?: afterKey,
                    hasMore = candidates.size > batchLimit,
                    rowsRead = candidates.size,
                )
            }
        }
        // Import completion is already durable. Preference cleanup is deliberately a separate,
        // bounded, idempotent operation after database ownership is released: a failed commit is
        // retried when this page is visited on a later launch without blocking unrelated URI work.
        runCatching { removeLegacyEntries(page.keys.toSet()) }
        return page
    }

    private fun Cursor.pendingDiscardRecord(): PendingDiscardRecord {
        val recordVersion = getInt(getColumnIndexOrThrow(RECORD_VERSION_COLUMN))
        val identity = if (
            recordVersion == IDENTITY_RECORD_VERSION &&
            requiredIdentityColumnsPresent()
        ) {
            PendingDiscardIdentity(
                volumeName = getString(getColumnIndexOrThrow(VOLUME_NAME_COLUMN)),
                providerVersion = getString(getColumnIndexOrThrow(PROVIDER_VERSION_COLUMN)),
                rowId = getLong(getColumnIndexOrThrow(ROW_ID_COLUMN)),
                generationAdded = getLong(getColumnIndexOrThrow(GENERATION_ADDED_COLUMN)),
                displayName = getString(getColumnIndexOrThrow(DISPLAY_NAME_COLUMN)),
                relativePath = getString(getColumnIndexOrThrow(RELATIVE_PATH_COLUMN)),
                mimeType = getString(getColumnIndexOrThrow(MIME_TYPE_COLUMN)),
                ownerPackageName = nullableString(OWNER_PACKAGE_COLUMN),
                familyIdentity = nullableString(FAMILY_IDENTITY_COLUMN),
                dateTaken = nullableLong(DATE_TAKEN_COLUMN),
            )
        } else {
            null
        }
        return PendingDiscardRecord(
            uri = getString(getColumnIndexOrThrow(URI_COLUMN)),
            recordVersion = recordVersion,
            identity = identity,
        )
    }

    private fun Cursor.requiredIdentityColumnsPresent(): Boolean = listOf(
        VOLUME_NAME_COLUMN,
        PROVIDER_VERSION_COLUMN,
        ROW_ID_COLUMN,
        GENERATION_ADDED_COLUMN,
        DISPLAY_NAME_COLUMN,
        RELATIVE_PATH_COLUMN,
        MIME_TYPE_COLUMN,
    ).all { column -> !isNull(getColumnIndexOrThrow(column)) }

    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

    private fun Cursor.nullableLong(column: String): Long? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getLong(index) }

    /**
     * Imports every legacy preference DISCARD and records import completion in one transaction.
     * Preference cleanup happens separately in bounded [page] chunks after that commit, so a
     * cleanup outage cannot repeat the whole import on every page.
     */
    private fun legacyMigrationPending(): Boolean = synchronized(databaseLock) {
        withReadableDatabase { database -> !migrationComplete(database) }
    }

    private fun migrateLegacyDiscardMarkers(legacyKeys: Set<String>) {
        if (withReadableDatabase { database -> migrationComplete(database) }) return

        withWritableDatabase { database ->
            database.beginTransaction()
            try {
                legacyKeys.forEach { uri ->
                    database.execSQL(
                        "INSERT OR IGNORE INTO $DISCARD_TABLE ($URI_COLUMN) VALUES (?)",
                        arrayOf(uri),
                    )
                }
                database.execSQL(
                    "INSERT OR REPLACE INTO $METADATA_TABLE ($METADATA_KEY_COLUMN, " +
                        "$METADATA_VALUE_COLUMN) VALUES (?, ?)",
                    arrayOf(LEGACY_MIGRATION_KEY, MIGRATION_COMPLETE_VALUE),
                )
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun migrationComplete(database: SQLiteDatabase): Boolean = database.query(
        METADATA_TABLE,
        arrayOf(METADATA_VALUE_COLUMN),
        "$METADATA_KEY_COLUMN = ?",
        arrayOf(LEGACY_MIGRATION_KEY),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        cursor.moveToFirst() && cursor.getString(0) == MIGRATION_COMPLETE_VALUE
    }

    private inline fun <T> withReadableDatabase(block: (SQLiteDatabase) -> T): T =
        Helper(applicationContext, databaseName, databaseVersion).use { helper ->
            block(helper.readableDatabase)
        }

    private inline fun <T> withWritableDatabase(block: (SQLiteDatabase) -> T): T =
        Helper(applicationContext, databaseName, databaseVersion).use { helper ->
            block(helper.writableDatabase)
        }

    private class Helper(context: Context, name: String, version: Int) :
        SQLiteOpenHelper(context, name, null, version) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE $DISCARD_TABLE (" +
                    "$URI_COLUMN TEXT NOT NULL PRIMARY KEY, " +
                    "$RECORD_VERSION_COLUMN INTEGER NOT NULL DEFAULT $LEGACY_RECORD_VERSION, " +
                    "$VOLUME_NAME_COLUMN TEXT, " +
                    "$PROVIDER_VERSION_COLUMN TEXT, " +
                    "$ROW_ID_COLUMN INTEGER, " +
                    "$GENERATION_ADDED_COLUMN INTEGER, " +
                    "$DISPLAY_NAME_COLUMN TEXT, " +
                    "$RELATIVE_PATH_COLUMN TEXT, " +
                    "$MIME_TYPE_COLUMN TEXT, " +
                    "$OWNER_PACKAGE_COLUMN TEXT, " +
                    "$FAMILY_IDENTITY_COLUMN TEXT, " +
                    "$DATE_TAKEN_COLUMN INTEGER" +
                    ")",
            )
            database.execSQL(
                "CREATE TABLE $METADATA_TABLE (" +
                    "$METADATA_KEY_COLUMN TEXT NOT NULL PRIMARY KEY, " +
                    "$METADATA_VALUE_COLUMN TEXT NOT NULL)",
            )
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != LEGACY_DATABASE_VERSION || newVersion != DATABASE_VERSION) {
                error("Unsupported pending-discard schema upgrade $oldVersion->$newVersion")
            }
            database.execSQL(
                "ALTER TABLE $DISCARD_TABLE ADD COLUMN $RECORD_VERSION_COLUMN " +
                    "INTEGER NOT NULL DEFAULT $LEGACY_RECORD_VERSION",
            )
            listOf(
                "$VOLUME_NAME_COLUMN TEXT",
                "$PROVIDER_VERSION_COLUMN TEXT",
                "$ROW_ID_COLUMN INTEGER",
                "$GENERATION_ADDED_COLUMN INTEGER",
                "$DISPLAY_NAME_COLUMN TEXT",
                "$RELATIVE_PATH_COLUMN TEXT",
                "$MIME_TYPE_COLUMN TEXT",
                "$OWNER_PACKAGE_COLUMN TEXT",
                "$FAMILY_IDENTITY_COLUMN TEXT",
                "$DATE_TAKEN_COLUMN INTEGER",
            ).forEach { definition ->
                database.execSQL("ALTER TABLE $DISCARD_TABLE ADD COLUMN $definition")
            }
        }
    }

    companion object {
        internal const val DATABASE_NAME = "pending_discard_journal.db"
        internal const val LEGACY_PREFERENCES_NAME = "pending_media_journal"
        internal const val LEGACY_DISCARD_VALUE = "discard"
        private const val DATABASE_VERSION = 2
        private const val LEGACY_DATABASE_VERSION = 1
        internal const val IDENTITY_RECORD_VERSION = 2
        private const val LEGACY_RECORD_VERSION = 1
        private const val DISCARD_TABLE = "pending_discards"
        private const val URI_COLUMN = "uri"
        private const val RECORD_VERSION_COLUMN = "record_version"
        private const val VOLUME_NAME_COLUMN = "volume_name"
        private const val PROVIDER_VERSION_COLUMN = "provider_version"
        private const val ROW_ID_COLUMN = "row_id"
        private const val GENERATION_ADDED_COLUMN = "generation_added"
        private const val DISPLAY_NAME_COLUMN = "display_name"
        private const val RELATIVE_PATH_COLUMN = "relative_path"
        private const val MIME_TYPE_COLUMN = "mime_type"
        private const val OWNER_PACKAGE_COLUMN = "owner_package_name"
        private const val FAMILY_IDENTITY_COLUMN = "family_identity"
        private const val DATE_TAKEN_COLUMN = "date_taken"
        private const val METADATA_TABLE = "journal_metadata"
        private const val METADATA_KEY_COLUMN = "metadata_key"
        private const val METADATA_VALUE_COLUMN = "metadata_value"
        private const val LEGACY_MIGRATION_KEY = "legacy_preferences_migrated"
        private const val MIGRATION_COMPLETE_VALUE = "1"
        private val DISCARD_PROJECTION = arrayOf(
            URI_COLUMN,
            RECORD_VERSION_COLUMN,
            VOLUME_NAME_COLUMN,
            PROVIDER_VERSION_COLUMN,
            ROW_ID_COLUMN,
            GENERATION_ADDED_COLUMN,
            DISPLAY_NAME_COLUMN,
            RELATIVE_PATH_COLUMN,
            MIME_TYPE_COLUMN,
            OWNER_PACKAGE_COLUMN,
            FAMILY_IDENTITY_COLUMN,
            DATE_TAKEN_COLUMN,
        )
        private val databaseLock = Any()
        private val uriAuthorityRegistryLock = Any()
        private val uriAuthorities = mutableMapOf<String, UriAuthority>()

        private class UriAuthority(
            val monitor: Any = Any(),
            var users: Int = 0,
        )

        private inline fun <T> withUriAuthority(uri: String, block: () -> T): T {
            val authority = synchronized(uriAuthorityRegistryLock) {
                uriAuthorities.getOrPut(uri, ::UriAuthority).also { it.users += 1 }
            }
            return try {
                synchronized(authority.monitor, block)
            } finally {
                synchronized(uriAuthorityRegistryLock) {
                    authority.users -= 1
                    if (authority.users == 0 && uriAuthorities[uri] === authority) {
                        uriAuthorities.remove(uri)
                    }
                }
            }
        }
    }
}

internal enum class DiscardJournalLookup { PRESENT, ABSENT, UNAVAILABLE }

internal data class PendingDiscardRecord(
    val uri: String,
    val recordVersion: Int,
    val identity: PendingDiscardIdentity?,
)

internal data class PendingDiscardIdentity(
    val volumeName: String,
    val providerVersion: String,
    val rowId: Long,
    val generationAdded: Long,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val ownerPackageName: String?,
    val familyIdentity: String?,
    val dateTaken: Long?,
)

/** Immutable creation-time authority for one app-allocated pending MediaStore row. */
internal data class PendingOutputAllocation(
    val uri: Uri,
    val familyKey: CaptureFamilyKey,
    val identity: PendingDiscardIdentity,
)

/** Typed creation-time read: only [Exact] may authorize discard; [Absent] is metadata-only. */
internal sealed interface PendingAllocationCaptureResult {
    data class Exact(val allocation: PendingOutputAllocation) : PendingAllocationCaptureResult
    data object Absent : PendingAllocationCaptureResult
    data object Uncertain : PendingAllocationCaptureResult
}

internal fun CaptureFamilyKey.discardIdentity(): String =
    "${media.name}|$capturedAtEpochMillis|$sequence"

internal sealed interface PendingDiscardIdentityRead {
    data class Present(val identity: PendingDiscardIdentity) : PendingDiscardIdentityRead
    data class Absent(
        val volumeName: String,
        val providerVersion: String,
    ) : PendingDiscardIdentityRead
    data object Ambiguous : PendingDiscardIdentityRead
    data object Unavailable : PendingDiscardIdentityRead
}

internal fun interface PendingDiscardIdentityReader {
    fun read(uri: String): PendingDiscardIdentityRead
}

internal enum class DiscardReplayIdentity {
    MATCH,
    ABSENT,
    MISMATCH,
    AMBIGUOUS,
    UNAVAILABLE,
    LEGACY,
}

/** Reads one exact MediaStore row only after establishing mounted-volume/provider-version truth. */
private class MediaStorePendingDiscardIdentityReader(
    private val context: Context,
) : PendingDiscardIdentityReader {
    override fun read(uri: String): PendingDiscardIdentityRead = runCatching {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != ContentResolver.SCHEME_CONTENT || parsed.authority != MediaStore.AUTHORITY) {
            return@runCatching PendingDiscardIdentityRead.Unavailable
        }
        val volumeName = MediaStore.getVolumeName(parsed)
        if (volumeName !in MediaStore.getExternalVolumeNames(context)) {
            return@runCatching PendingDiscardIdentityRead.Unavailable
        }
        // The platform docs permit null for an unmounted volume even though the SDK annotation is
        // non-null. Kotlin's generated null check is contained by this fail-closed runCatching.
        val providerVersion = MediaStore.getVersion(context, volumeName)
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val cursor = context.contentResolver.query(
            parsed,
            IDENTITY_PROJECTION,
            queryArgs,
            null,
        ) ?: return@runCatching PendingDiscardIdentityRead.Unavailable
        cursor.use {
            if (!cursor.moveToFirst()) {
                if (MediaStore.getVersion(context, volumeName) != providerVersion) {
                    return@runCatching PendingDiscardIdentityRead.Unavailable
                }
                return@runCatching PendingDiscardIdentityRead.Absent(volumeName, providerVersion)
            }
            val identity = cursor.readIdentity(volumeName, providerVersion)
                ?: return@runCatching PendingDiscardIdentityRead.Unavailable
            if (cursor.moveToNext()) return@runCatching PendingDiscardIdentityRead.Ambiguous
            val uriRowId = runCatching { android.content.ContentUris.parseId(parsed) }.getOrNull()
                ?: return@runCatching PendingDiscardIdentityRead.Unavailable
            if (identity.rowId != uriRowId) return@runCatching PendingDiscardIdentityRead.Ambiguous
            if (MediaStore.getVersion(context, volumeName) != providerVersion) {
                return@runCatching PendingDiscardIdentityRead.Unavailable
            }
            PendingDiscardIdentityRead.Present(identity)
        }
    }.getOrDefault(PendingDiscardIdentityRead.Unavailable)

    private fun Cursor.readIdentity(
        volumeName: String,
        providerVersion: String,
    ): PendingDiscardIdentity? {
        val rowId = requiredLong(MediaStore.MediaColumns._ID) ?: return null
        val generationAdded = requiredLong(MediaStore.MediaColumns.GENERATION_ADDED) ?: return null
        val displayName = requiredString(MediaStore.MediaColumns.DISPLAY_NAME) ?: return null
        val relativePath = requiredString(MediaStore.MediaColumns.RELATIVE_PATH) ?: return null
        val mimeType = requiredString(MediaStore.MediaColumns.MIME_TYPE) ?: return null
        val ownerPackageName = optionalString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val dateTaken = optionalLong(MediaStore.MediaColumns.DATE_TAKEN)
        val familyIdentity = CaptureFamilyKey.parse(displayName)?.familyKey?.discardIdentity()
        return PendingDiscardIdentity(
            volumeName = volumeName,
            providerVersion = providerVersion,
            rowId = rowId,
            generationAdded = generationAdded,
            displayName = displayName,
            relativePath = relativePath,
            mimeType = mimeType,
            ownerPackageName = ownerPackageName,
            familyIdentity = familyIdentity,
            dateTaken = dateTaken,
        )
    }

    private fun Cursor.requiredString(column: String): String? =
        optionalString(column)?.takeIf(String::isNotBlank)

    private fun Cursor.optionalString(column: String): String? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

    private fun Cursor.requiredLong(column: String): Long? =
        optionalLong(column)?.takeIf { it >= 0L }

    private fun Cursor.optionalLong(column: String): Long? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getLong(index) }

    companion object {
        private val IDENTITY_PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
    }
}
