package com.layerbit.deja.ambient

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.index.ShotRows
import com.layerbit.deja.data.ocr.ScreenshotTextReader
import com.layerbit.deja.data.scan.MediaStoreScanner
import com.layerbit.deja.share.ShareIntake
import com.layerbit.deja.widget.Widgets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wakes when MediaStore changes, reads any screenshot that was just taken, and - if it holds a
 * code, a password, a booking reference or an amount - says so before you have left the app you
 * took it in.
 *
 * The wake-up is a content-URI trigger, which WorkManager hands to JobScheduler: the system
 * starts Deja when the media table changes and not otherwise. No foreground service, no polling,
 * no wakelock held while waiting. A trigger fires once, so each run re-arms the next.
 */
class NewScreenshotWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val context = applicationContext
        try {
            if (AmbientPreferences(context).notifyNewShots) readNew(context)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        } finally {
            if (AmbientPreferences(context).notifyNewShots) rearm(context)
        }
    }

    private suspend fun readNew(context: Context) {
        val dao = DejaDatabase.get(context).shotDao()
        val scanner = MediaStoreScanner(context)

        // MediaStore names the changed rows when it can, and gives up naming them when a lot
        // changes at once. Either way, only screenshots are of interest.
        val ids = triggeredContentUris.mapNotNull { it.lastPathSegment?.toLongOrNull() }
        val candidates = if (ids.isEmpty()) {
            scanner.addedWithin(RECENT_SECONDS)
        } else {
            scanner.screenshotsAmong(ids)
        }

        val cutoff = System.currentTimeMillis() - RECENT_SECONDS * 1000L
        val already = dao.indexedAmong(candidates.map { it.mediaId }).toSet()
        val fresh = candidates.filter {
            it.mediaId !in already &&
                // Something edited or moved today that was taken last year is not news.
                it.dateTakenMillis >= cutoff &&
                // Shared in through Deja: the share screen indexes and opens it itself.
                !it.displayName.startsWith(ShareIntake.FILE_PREFIX)
        }.take(MAX_PER_RUN)
        if (fresh.isEmpty()) return

        val reader = ScreenshotTextReader(context)
        try {
            for (shot in fresh) {
                val row = ShotRows.newRow(shot, reader.read(shot.uri))
                dao.upsertAll(listOf(row))
                val saved = dao.byMediaId(shot.mediaId) ?: continue
                Highlight.of(saved)?.let { ShotNotifier.post(context, saved, it) }
            }
        } finally {
            reader.close()
        }
        Widgets.refresh(context)
    }

    companion object {
        private const val UNIQUE_NAME = "deja-new-screenshot"

        /** How new a screenshot has to be to be worth telling anyone about. */
        private const val RECENT_SECONDS = 120L

        /** A burst of screenshots gets read; it does not get ten notifications. */
        private const val MAX_PER_RUN = 3

        private fun request() = OneTimeWorkRequestBuilder<NewScreenshotWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    // Screenshots are written in two steps - a pending row, then the finished
                    // file - so wait for the burst of changes to settle before reading.
                    .setTriggerContentUpdateDelay(Duration.ofMillis(600))
                    .setTriggerContentMaxDelay(Duration.ofSeconds(3))
                    .build()
            )
            .build()

        /** Starts watching, unless a watch is already armed or running. Safe to call on every launch. */
        fun schedule(context: Context) {
            if (!AmbientPreferences(context).notifyNewShots) return
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
        }

        /**
         * Queues the next watch behind the one finishing now. KEEP would see the running worker
         * and do nothing; REPLACE would cancel it mid-read. Appending is what leaves exactly one
         * armed watch once this one ends.
         */
        private fun rearm(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
