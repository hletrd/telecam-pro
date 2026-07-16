package com.hletrd.findx9tele.storage

import java.util.Locale

/** MediaStore collection owned by one capture family. */
internal enum class CaptureFamilyMedia(
    val filePrefix: String,
) {
    STILL("IMG"),
    VIDEO("VID"),
}

/**
 * Durable identity shared by every file emitted from one shutter/recording admission.
 *
 * The explicit `F1` marker is intentional. Older TeleCam filenames contain timestamps that are
 * close together but were generated once per output, so treating their stems as family identities
 * could combine adjacent burst shots. Only this versioned format is safe to reconstruct.
 */
internal data class CaptureFamilyKey(
    val media: CaptureFamilyMedia,
    val capturedAtEpochMillis: Long,
    val sequence: Long,
) {
    init {
        require(capturedAtEpochMillis in 0..MAX_TIMESTAMP) { "capture timestamp is out of range" }
        require(sequence in 0..MAX_SEQUENCE) { "capture sequence is out of range" }
    }

    fun displayName(extension: String): String {
        val normalizedExtension = extension.removePrefix(".").lowercase(Locale.ROOT)
        require(EXTENSION.matches(normalizedExtension)) { "invalid media extension" }
        return "%s_TELECAM_F1_%013d_%010d.%s".format(
            Locale.ROOT,
            media.filePrefix,
            capturedAtEpochMillis,
            sequence,
            normalizedExtension,
        )
    }

    /** Every filename this app can emit for this exact family, used for a complete bounded query. */
    fun knownOutputDisplayNames(): List<String> = when (media) {
        CaptureFamilyMedia.STILL -> listOf("heic", "heif", "jpg", "jpeg", "dng")
        CaptureFamilyMedia.VIDEO -> listOf("mp4")
    }.map(::displayName)

    companion object {
        private const val MAX_TIMESTAMP = 9_999_999_999_999L
        private const val MAX_SEQUENCE = 9_999_999_999L
        private val EXTENSION = Regex("[a-z0-9]+")
        private val FILE_NAME = Regex(
            "^(IMG|VID)_TELECAM_F1_([0-9]{13})_([0-9]{10})\\.([A-Za-z0-9]+)$",
        )

        fun parse(displayName: String?): ParsedCaptureFile? {
            val match = displayName?.let(FILE_NAME::matchEntire) ?: return null
            val media = when (match.groupValues[1]) {
                "IMG" -> CaptureFamilyMedia.STILL
                "VID" -> CaptureFamilyMedia.VIDEO
                else -> return null
            }
            val timestamp = match.groupValues[2].toLongOrNull() ?: return null
            val sequence = match.groupValues[3].toLongOrNull() ?: return null
            val key = runCatching { CaptureFamilyKey(media, timestamp, sequence) }.getOrNull()
                ?: return null
            return ParsedCaptureFile(
                familyKey = key,
                extension = match.groupValues[4].lowercase(Locale.ROOT),
            )
        }
    }
}

internal data class ParsedCaptureFile(
    val familyKey: CaptureFamilyKey,
    val extension: String,
)
