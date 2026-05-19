package com.devin.messenger.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

object MediaUtils {

    data class CopiedFile(
        val file: File,
        val mime: String,
        val displayName: String?,
        val sizeBytes: Long,
    )

    /**
     * Copy a content:// or file:// URI into the app cache and return the resulting File
     * along with metadata. Returns null if the URI cannot be opened.
     */
    fun copyUriToCache(context: Context, uri: Uri, prefix: String): CopiedFile? {
        val resolver = context.contentResolver
        val rawMime = resolver.getType(uri) ?: "application/octet-stream"
        val mime = rawMime.split(";").first().trim().ifEmpty { "application/octet-stream" }

        var displayName: String? = null
        var sizeFromCursor: Long = -1
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx >= 0) displayName = c.getString(nameIdx)
                    if (sizeIdx >= 0) sizeFromCursor = c.getLong(sizeIdx)
                }
            }
        }

        val ext = extensionForMime(mime) ?: displayName?.substringAfterLast('.', missingDelimiterValue = "") ?: "bin"
        val target = File(context.cacheDir, "$prefix-${UUID.randomUUID()}.${ext.ifEmpty { "bin" }}")

        val written = resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        return CopiedFile(
            file = target,
            mime = mime,
            displayName = displayName,
            sizeBytes = if (sizeFromCursor >= 0) sizeFromCursor else written,
        )
    }

    fun extensionForMime(mime: String): String? {
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    }

    fun durationMsFor(file: File): Int? = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.toInt()
        }
    }.getOrNull()

    fun formatDuration(ms: Int?): String {
        val v = ms ?: 0
        val totalSec = v / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    fun formatSize(bytes: Long?): String {
        val b = bytes ?: 0L
        if (b <= 0L) return ""
        if (b < 1024) return "$b B"
        if (b < 1024 * 1024) return "%.1f KB".format(b / 1024.0)
        if (b < 1024L * 1024 * 1024) return "%.1f MB".format(b / (1024.0 * 1024))
        return "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    }
}

private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T {
    try {
        return block(this)
    } finally {
        runCatching { release() }
    }
}
