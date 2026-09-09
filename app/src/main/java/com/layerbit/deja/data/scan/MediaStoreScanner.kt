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
 * queries below are scoped to the Screenshots bucket, not the whole image library.
 */
class MediaStoreScanner(private val context: Context) {

    fun scan(): List<ScannedShot> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )

        // OEMs disagree on where screenshots live - Pictures/Screenshots and DCIM/Screenshots are
        // both common - so match on the path or the bucket name rather than one fixed folder.
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        val args = arrayOf("%Screenshots%", "Screenshots")

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
}
