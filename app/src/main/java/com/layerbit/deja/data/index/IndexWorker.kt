package com.layerbit.deja.data.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.data.model.Extracted
import com.layerbit.deja.data.model.ExtractedCodec
import com.layerbit.deja.data.ocr.ScreenshotTextReader
import com.layerbit.deja.data.scan.MediaGeneration
import com.layerbit.deja.data.scan.MediaStoreScanner
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads any screenshot that is not in the index yet, and drops rows for screenshots that have left
 * the device. Runs through WorkManager so it survives the app being closed mid-scan.
 *
 * Only new work is ever done. A library that has already been read produces no OCR at all - the
 * worker diffs MediaStore against the index and finds nothing to do - and callers can skip even
 * that using the MediaStore version counter. Reading a thousand screenshots is a first-run cost,
 * not a per-launch one.
 *
 * Stopping is a first-class outcome, not a failure. When the user stops a scan the work returns
 * successfully with the index left exactly as far as it got, and the interrupted flag is what
 * lets the next launch offer to resume from there instead of starting over.
 */
class IndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val dao = DejaDatabase.get(applicationContext).shotDao()
        val scanner = MediaStoreScanner(applicationContext)
        val reader = ScreenshotTextReader(applicationContext)
        val prefs = ScanPreferences(applicationContext)

        // Captured before the work, so anything added while it runs leaves the number stale and
        // earns another pass rather than being missed.
        val generation = MediaGeneration.current(applicationContext)

        prefs.markStarted()
        IndexingState.scanning()

        try {
            val onDevice = scanner.scan()
            val onDeviceIds = onDevice.mapTo(mutableSetOf()) { it.mediaId }
            val indexed = dao.indexedMediaIds().toSet()

            val removed = indexed - onDeviceIds
            if (removed.isNotEmpty()) dao.deleteByMediaIds(removed.toList())

            val pending = onDevice.filterNot { it.mediaId in indexed }

            // Nothing new and nothing gone: finish without ever showing a reading state, so
            // reopening the app on an already-read library looks like what it is - instant.
            if (pending.isEmpty()) {
                prefs.markFinished(generation)
                IndexingState.upToDate(onDevice.size)
                return@withContext Result.success()
            }

            // Progress is reported against the whole library, not just the unread part, so the
            // number on screen always matches how many screenshots the device actually has.
            val total = onDevice.size
            var done = (indexed - removed).size
            IndexingState.reading(done, total)

            for (shot in pending) {
                if (isStopped) {
                    IndexingState.stopped()
                    return@withContext Result.success()
                }

                val text = reader.read(shot.uri).orEmpty()
                val entities = EntityExtractor.extract(text)
                val category = Classifier.classify(text, shot.displayName, entities)
                val app = SourceApp.detect(shot.displayName).orEmpty()

                dao.upsert(
                    ShotEntity(
                        mediaId = shot.mediaId,
                        uri = shot.uri.toString(),
                        displayName = shot.displayName,
                        dateTakenMillis = shot.dateTakenMillis,
                        sizeBytes = shot.sizeBytes,
                        text = text,
                        textHash = if (text.isBlank()) "" else sha1(text),
                        category = category.id,
                        sourceApp = app,
                        entitiesJson = ExtractedCodec.encode(entities),
                        searchBlob = buildSearchBlob(text, app, category, entities),
                        indexedAtMillis = System.currentTimeMillis()
                    )
                )
                done++
                IndexingState.advance(done)
            }

            prefs.markFinished(generation)
            IndexingState.finished()
            Result.success()
        } catch (error: Exception) {
            IndexingState.stopped()
            Result.retry()
        } finally {
            reader.close()
        }
    }

    private fun buildSearchBlob(
        text: String,
        app: String,
        category: Category,
        entities: List<Extracted>
    ): String = buildString {
        append(text)
        if (app.isNotEmpty()) {
            append(' ')
            append(app)
        }
        append(' ')
        append(category.label)
        val values = ExtractedCodec.searchableValues(entities)
        if (values.isNotEmpty()) {
            append(' ')
            append(values)
        }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val UNIQUE_NAME = "deja-index"

        /**
         * KEEP rather than REPLACE: a scan already in flight has done real OCR work, and
         * restarting it every time the app is opened would never let a big library finish.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>().build()
            )
        }

        /** Replaces any running scan - used when the user explicitly asks to start over. */
        fun restart(context: Context) {
            ScanPreferences(context).forgetGeneration()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<IndexWorker>().build()
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            IndexingState.stopped()
        }
    }
}
