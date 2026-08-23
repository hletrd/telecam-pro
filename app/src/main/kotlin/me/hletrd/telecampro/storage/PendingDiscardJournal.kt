package me.hletrd.telecampro.storage

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
    private val removeLegacyEntries: (Set<String>) -> Unit = { keys ->
        legacyPreferences.edit(commit = true) { keys.forEach(::remove) }
    },
) {
    private val applicationContext = context.applicationContext

    /** Commits the SQLite marker before removing any older preference value for this URI. */
    fun mark(uri: String): Boolean = synchronized(databaseLock) {
        runCatching {
            withWritableDatabase { database ->
                database.beginTransaction()
                try {
                    database.execSQL(
                        "INSERT OR IGNORE INTO $DISCARD_TABLE ($URI_COLUMN) VALUES (?)",
                        arrayOf(uri),
                    )
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            // A failed cleanup is harmless: SQLite is already the durable authority, and a later
            // journal page removes the duplicate preference key idempotently.
            runCatching { removeLegacyEntries(setOf(uri)) }
            true
        }.getOrDefault(false)
    }

    /** A database failure is not evidence that an exact delete owner is absent. */
    fun lookup(uri: String): DiscardJournalLookup = synchronized(databaseLock) {
        lookupLocked(uri)
    }

    /**
     * Serializes a decision with [mark] so a caller can perform its terminal provider transition
     * only while exact SQLite DISCARD authority is authoritatively absent.
     */
    fun <T> withLookupAuthority(uri: String, block: (DiscardJournalLookup) -> T): T =
        synchronized(databaseLock) { block(lookupLocked(uri)) }

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
    fun remove(uri: String): Boolean = synchronized(databaseLock) {
        runCatching {
            withWritableDatabase { database ->
                database.delete(DISCARD_TABLE, "$URI_COLUMN = ?", arrayOf(uri))
                database.query(
                    DISCARD_TABLE,
                    arrayOf(URI_COLUMN),
                    "$URI_COLUMN = ?",
                    arrayOf(uri),
                    null,
                    null,
                    null,
                    "1",
                ).use { !it.moveToFirst() }
            }
        }.getOrDefault(false)
    }

    /**
     * Reads at most [batchLimit] + 1 indexed rows. The extra row proves continuation without a
     * table-sized materialization or sort in app memory.
     */
    fun page(afterKey: String?, batchLimit: Int): DiscardJournalPage = synchronized(databaseLock) {
        require(batchLimit > 0)
        migrateLegacyDiscardMarkers()
        withReadableDatabase { database ->
            val queryLimit = batchLimit + 1
            val candidates = buildList(queryLimit) {
                database.query(
                    DISCARD_TABLE,
                    arrayOf(URI_COLUMN),
                    "$URI_COLUMN > ?",
                    arrayOf(afterKey.orEmpty()),
                    null,
                    null,
                    "$URI_COLUMN ASC",
                    queryLimit.toString(),
                ).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            val keys = candidates.take(batchLimit)
            // Import completion is already durable. Preference cleanup is deliberately a separate,
            // bounded, idempotent operation: a failed commit is retried when this page is visited
            // on a later launch, without ever re-enumerating or re-importing the full legacy set.
            runCatching { removeLegacyEntries(keys.toSet()) }
            DiscardJournalPage(
                keys = keys,
                nextAfterKey = keys.lastOrNull() ?: afterKey,
                hasMore = candidates.size > batchLimit,
                rowsRead = candidates.size,
            )
        }
    }

    /**
     * Imports every legacy preference DISCARD and records import completion in one transaction.
     * Preference cleanup happens separately in bounded [page] chunks after that commit, so a
     * cleanup outage cannot repeat the whole import on every page.
     */
    private fun migrateLegacyDiscardMarkers() {
        if (withReadableDatabase { database -> migrationComplete(database) }) return

        val legacyKeys = legacyPreferences.all
            .filterValues { it == LEGACY_DISCARD_VALUE }
            .keys
            .toSet()

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
                "CREATE TABLE $DISCARD_TABLE ($URI_COLUMN TEXT NOT NULL PRIMARY KEY)",
            )
            database.execSQL(
                "CREATE TABLE $METADATA_TABLE (" +
                    "$METADATA_KEY_COLUMN TEXT NOT NULL PRIMARY KEY, " +
                    "$METADATA_VALUE_COLUMN TEXT NOT NULL)",
            )
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported pending-discard schema upgrade $oldVersion->$newVersion")
        }
    }

    companion object {
        internal const val DATABASE_NAME = "pending_discard_journal.db"
        internal const val LEGACY_PREFERENCES_NAME = "pending_media_journal"
        internal const val LEGACY_DISCARD_VALUE = "discard"
        private const val DATABASE_VERSION = 1
        private const val DISCARD_TABLE = "pending_discards"
        private const val URI_COLUMN = "uri"
        private const val METADATA_TABLE = "journal_metadata"
        private const val METADATA_KEY_COLUMN = "metadata_key"
        private const val METADATA_VALUE_COLUMN = "metadata_value"
        private const val LEGACY_MIGRATION_KEY = "legacy_preferences_migrated"
        private const val MIGRATION_COMPLETE_VALUE = "1"
        private val databaseLock = Any()
    }
}

internal enum class DiscardJournalLookup { PRESENT, ABSENT, UNAVAILABLE }
