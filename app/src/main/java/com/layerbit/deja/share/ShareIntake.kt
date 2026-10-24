package com.layerbit.deja.share

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.index.ShotRows
import com.layerbit.deja.data.ocr.ScreenshotTextReader
import com.layerbit.deja.data.scan.MediaStoreScanner
import com.layerbit.deja.data.scan.ScannedShot
import com.layerbit.deja.widget.Widgets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes images shared into Deja and indexes them like screenshots.
 *
 * A shared URI is a temporary grant: it stops working once the sending app's task is gone, so
 * indexing the URI itself would leave a row pointing at nothing. Each image is instead saved into
 * Pictures/Deja through MediaStore - a file Deja owns, needs no permission to write, and that
 * shows in the gallery and in the system trash like any other image. From then on it is an
 * ordinary row: searchable, keepable, and removable from Clean up.
 */
object ShareIntake {

    /** Names Deja gives the images it saves, so the screenshot watcher can tell them apart. */
    const val FILE_PREFIX = "Deja_"

    /** @return the index id of each image that was read, in the order shared. */
    suspend fun take(context: Context, uris: List<Uri>): List<Long> {
        val dao = DejaDatabase.get(context).shotDao()
        val reader = lazy { ScreenshotTextReader(context) }
        val ids = mutableListOf<Long>()
        try {
            for ((index, uri) in uris.withIndex()) {
                // Sharing a screenshot Deja has already read - from the gallery, say - should open
                // that one, not make a second copy of it.
                val existing = mediaStoreId(uri)?.let { dao.byMediaId(it) }
                if (existing != null) {
                    ids += existing.id
                    continue
                }
                val saved = save(context, uri, index) ?: continue
                val row = ShotRows.newRow(saved, reader.value.read(saved.uri))
                dao.upsertAll(listOf(row))
                dao.byMediaId(saved.mediaId)?.let { ids += it.id }
            }
        } finally {
            if (reader.isInitialized()) reader.value.close()
        }
        if (ids.isNotEmpty()) Widgets.refresh(context)
        return ids
    }

    private fun mediaStoreId(uri: Uri): Long? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        if (uri.pathSegments.none { it == "images" }) return null
        return runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it > 0 }
    }

    private fun save(context: Context, source: Uri, index: Int): ScannedShot? {
        val resolver = context.contentResolver
        val mime = resolver.getType(source)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val extension = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val name = "$FILE_PREFIX${stamp}_$index.$extension"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, MediaStoreScanner.SHARED_RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val target = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        val bytes = runCatching {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
            }
        }.getOrNull()
        if (bytes == null || bytes == 0L) {
            runCatching { resolver.delete(target, null, null) }
            return null
        }

        resolver.update(
            target,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
        return ScannedShot(
            mediaId = ContentUris.parseId(target),
            uri = target,
            displayName = name,
            dateTakenMillis = now,
            sizeBytes = bytes
        )
    }
}
