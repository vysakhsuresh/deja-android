package com.layerbit.deja.data.scan

import android.content.Context
import android.provider.MediaStore

/**
 * A cheap "has anything changed?" test for the media volume.
 *
 * MediaStore keeps a version counter that moves whenever media is added, edited or removed. Asking
 * for it costs nothing, so a launch where the number is unchanged can skip the scan entirely
 * instead of walking the whole library again - which is what made Deja look like it re-scanned
 * from scratch every time it opened.
 *
 * It counts the whole volume, not just screenshots, so an unrelated photo also moves it. That only
 * ever costs a MediaStore query that finds nothing new; it never causes a re-read.
 */
object MediaGeneration {

    const val UNKNOWN = -1L

    fun current(context: Context): Long = runCatching {
        MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }.getOrDefault(UNKNOWN)
}
