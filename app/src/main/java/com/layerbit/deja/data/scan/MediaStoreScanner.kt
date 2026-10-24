package com.layerbit.deja.data.scan

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class ScannedShot(
    val mediaId: Long,
    val uri: Uri,
    val displayName: String,
    val dateTakenMillis: Long,
    val sizeBytes: Long
)

/**
 * Finds screenshots through MediaStore. Deja deliberately reads nothing else on the device: the
 * queries below are scoped to the Screenshots bucket, not the whole image library - plus the one
 * folder Deja writes itself, where images shared into it are kept.
 */
class MediaStoreScanner(private val context: Context) {

    /** Every screenshot on the device, newest first. */
    fun scan(): List<ScannedShot> = query(SCOPE, SCOPE_ARGS)

    /**
     * The subset of [mediaIds] that are screenshots. Used when MediaStore reports which rows
     * changed: most changes on a phone are camera photos and downloads, and those are none of
     * Deja's business.
     */
    fun screenshotsAmong(mediaIds: Collection<Long>): List<ScannedShot> {
        if (mediaIds.isEmpty()) return emptyList()
        val ids = mediaIds.joinToString(",")
        return query("($SCOPE) AND ${MediaStore.Images.Media._ID} IN ($ids)", SCOPE_ARGS)
    }

    /** Screenshots added in the last [seconds]. The fallback when MediaStore does not say which. */
    fun addedWithin(seconds: Long): List<ScannedShot> {
        val since = System.currentTimeMillis() / 1000L - seconds
        return query(
            "($SCOPE) AND ${MediaStore.Images.Media.DATE_ADDED} >= ?",
            SCOPE_ARGS + since.toString()
        )
    }

    private fun query(selection: String, args: Array<String>): List<ScannedShot> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )

        val results = mutableListOf<ScannedShot>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                // DATE_TAKEN is already in milliseconds but is often unset for screenshots;
                // DATE_ADDED is seconds and always present.
                val taken = if (cursor.isNull(takenColumn)) {
                    cursor.getLong(addedColumn) * 1000L
                } else {
                    cursor.getLong(takenColumn)
                }
                results += ScannedShot(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    displayName = cursor.getString(nameColumn) ?: "Screenshot",
                    dateTakenMillis = taken,
                    sizeBytes = cursor.getLong(sizeColumn)
                )
            }
        }
        return results
    }

    companion object {
        /** Where images shared into Deja are saved, so they live in MediaStore like any other. */
        const val SHARED_RELATIVE_PATH = "Pictures/Deja/"

        // OEMs disagree on where screenshots live - Pictures/Screenshots and DCIM/Screenshots are
        // both common - so match on the path or the bucket name rather than one fixed folder.
        private val SCOPE = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ? OR " +
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        private val SCOPE_ARGS = arrayOf("%Screenshots%", "Screenshots", SHARED_RELATIVE_PATH)
    }
}
