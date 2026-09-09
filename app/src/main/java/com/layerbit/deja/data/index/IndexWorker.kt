package com.layerbit.deja.data.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.ExtractedCodec
import com.layerbit.deja.data.ocr.ScreenshotTextReader
import com.layerbit.deja.data.scan.MediaStoreScanner
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads any screenshot that is not in the index yet, and drops rows for screenshots that have
 * left the device. Runs on a background thread through WorkManager so it survives the app being
 * closed mid-scan; on a large library the first pass takes a while and that is fine.
 */
class IndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val dao = DejaDatabase.get(applicationContext).shotDao()
        val scanner = MediaStoreScanner(applicationContext)
        val reader = ScreenshotTextReader(applicationContext)

        try {
            val onDevice = scanner.scan()
            val onDeviceIds = onDevice.mapTo(mutableSetOf()) { it.mediaId }
            val alreadyIndexed = dao.indexedMediaIds().toSet()

            val removed = alreadyIndexed - onDeviceIds
            if (removed.isNotEmpty()) dao.deleteByMediaIds(removed.toList())

            val pending = onDevice.filterNot { it.mediaId in alreadyIndexed }
            IndexingState.start(pending.size)

            pending.forEachIndexed { position, shot ->
                if (isStopped) return@withContext Result.retry()

                val text = reader.read(shot.uri).orEmpty()
                dao.upsert(
                    ShotEntity(
                        mediaId = shot.mediaId,
                        uri = shot.uri.toString(),
                        displayName = shot.displayName,
                        dateTakenMillis = shot.dateTakenMillis,
                        sizeBytes = shot.sizeBytes,
                        text = text,
                        textHash = if (text.isBlank()) "" else sha1(text),
                        category = Classifier.classify(text).id,
                        entitiesJson = ExtractedCodec.encode(EntityExtractor.extract(text)),
                        indexedAtMillis = System.currentTimeMillis()
                    )
                )
                IndexingState.advance(position + 1)
            }

            Result.success()
        } catch (error: Exception) {
            Result.retry()
        } finally {
            reader.close()
            IndexingState.finish()
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
    }
}
