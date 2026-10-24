package com.layerbit.deja.data.index

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.db.ShotDao
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.ExtractedCodec
import com.layerbit.deja.data.ocr.ReadResult
import com.layerbit.deja.data.ocr.ReadScript
import com.layerbit.deja.data.ocr.ScreenshotTextReader
import com.layerbit.deja.data.scan.MediaGeneration
import com.layerbit.deja.data.scan.MediaStoreScanner
import com.layerbit.deja.data.scan.ScannedShot
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Brings the index up to date, in three passes that get progressively more expensive.
 *
 *  1. **Re-derive.** Rows whose text is current but whose conclusions were drawn by an older
 *     engine. No file is touched; this is a database read, a pure function, and a write. Four
 *     thousand rows take about a second, which is what makes shipping a better classifier
 *     worthwhile at all - the improvement reaches the whole library, not just future screenshots.
 *  2. **Read what is new.** The screenshots on the device that are not in the index.
 *  3. **Backfill.** Rows an older engine read, re-read with the current one, newest first and
 *     capped per run. The index stays completely usable throughout: old text keeps answering
 *     searches until better text replaces it.
 *
 * The scan is marked finished after pass 2, not pass 3. A backfill is housekeeping - it must
 * never be the reason someone is asked "resume the unfinished scan?" on launch, and it must
 * never stop the launch gate from going quiet. What brings the worker back for the rest is the
 * backlog count, which the gate checks directly.
 *
 * Stopping is a first-class outcome, not a failure. The work returns successfully with the index
 * exactly as far as it got.
 */
class IndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val dao = DejaDatabase.get(applicationContext).shotDao()
        val scanner = MediaStoreScanner(applicationContext)
        val prefs = ScanPreferences(applicationContext)

        // Captured before the work, so anything added while it runs leaves the number stale and
        // earns another pass rather than being missed.
        val generation = MediaGeneration.current(applicationContext)

        prefs.markStarted()
        IndexingState.scanning()

        val readers = mutableListOf<ScreenshotTextReader>()
        try {
            val onDevice = scanner.scan()
            val onDeviceIds = onDevice.mapTo(mutableSetOf()) { it.mediaId }
            val indexed = dao.indexedMediaIds().toSet()

            val removed = indexed - onDeviceIds
            if (removed.isNotEmpty()) dao.deleteByMediaIds(removed.toList())

            val pending = onDevice.filterNot { it.mediaId in indexed }

            if (!rederive(dao)) return@withContext stoppedResult()

            val staleCount = dao.staleReadCount(EngineVersion.READ, EngineVersion.MAX_READ_ATTEMPTS)

            // Nothing new, nothing gone, nothing stale: finish without ever showing a reading
            // state, so reopening the app on an already-read library looks like what it is.
            if (pending.isEmpty() && staleCount == 0) {
                prefs.markFinished(generation)
                IndexingState.upToDate(onDevice.size)
                return@withContext Result.success()
            }

            repeat(ScreenshotTextReader.parallelism()) {
                readers += ScreenshotTextReader(applicationContext)
            }
            val pool = Channel<ScreenshotTextReader>(Channel.UNLIMITED)
            readers.forEach { pool.trySend(it) }

            if (pending.isNotEmpty()) {
                // Progress is reported against the whole library, not just the unread part, so
                // the number on screen always matches how many screenshots the device has.
                val total = onDevice.size
                val done = AtomicInteger((indexed - removed).size)
                IndexingState.reading(done.get(), total)

                for (batch in pending.chunked(WRITE_BATCH)) {
                    if (isStopped) return@withContext stoppedResult()
                    val rows = readBatch(pool, batch)
                    if (rows.isNotEmpty()) dao.upsertAll(rows)
                    IndexingState.advance(done.addAndGet(rows.size))
                }
            }

            // Everything the user asked for is in. From here it is self-improvement, and the
            // launch gate should stay quiet whether or not it finishes.
            prefs.markFinished(generation)

            if (staleCount > 0) {
                IndexingState.refreshing(0, minOf(staleCount, BACKFILL_PER_RUN))
                var refreshed = 0
                while (refreshed < BACKFILL_PER_RUN) {
                    if (isStopped) return@withContext stoppedResult()
                    val batch = dao.staleReads(
                        version = EngineVersion.READ,
                        maxAttempts = EngineVersion.MAX_READ_ATTEMPTS,
                        limit = WRITE_BATCH
                    )
                    if (batch.isEmpty()) break
                    dao.updateAll(rereadBatch(pool, batch))
                    refreshed += batch.size
                    IndexingState.advance(refreshed)
                }
            }

            IndexingState.finished()
            Result.success()
        } catch (error: Exception) {
            IndexingState.stopped()
            Result.retry()
        } finally {
            readers.forEach { runCatching { it.close() } }
        }
    }

    private fun stoppedResult(): Result {
        IndexingState.stopped()
        return Result.success()
    }

    /**
     * Replays [Derivation] over rows whose stored text is already current.
     *
     * Deliberately the first thing that happens. Someone who installs an update with a better
     * classifier should see their library improve before the progress bar for new screenshots
     * has finished drawing, not after a scan they have to sit through.
     */
    private suspend fun rederive(dao: ShotDao): Boolean {
        val backlog = dao.staleDeriveCount(EngineVersion.DERIVE, EngineVersion.READ)
        if (backlog == 0) return true

        IndexingState.refreshing(0, backlog)
        var refreshed = 0
        // The loop re-queries rather than paging, because each pass removes its own rows from
        // the result set. The ceiling is a guard against a row that somehow refuses to take the
        // new version stamp, which would otherwise spin here forever.
        val ceiling = backlog + DERIVE_BATCH
        while (refreshed < ceiling) {
            if (isStopped) return false
            val batch = dao.staleDerives(EngineVersion.DERIVE, EngineVersion.READ, DERIVE_BATCH)
            if (batch.isEmpty()) break
            dao.updateAll(batch.map { row -> row.rederived() })
            refreshed += batch.size
            IndexingState.advance(refreshed)
        }
        return true
    }

    private fun ShotEntity.rederived(): ShotEntity {
        val derived = Derivation.derive(
            displayName = displayName,
            text = text,
            lines = TextLayout.decode(linesBlob),
            codes = CodeExtractor.decode(codesBlob)
        )
        return copy(
            category = derived.category.id,
            sourceApp = derived.sourceApp,
            headline = derived.headline,
            entitiesJson = ExtractedCodec.encode(derived.entities),
            searchBlob = derived.searchBlob,
            deriveVersion = EngineVersion.DERIVE
        )
    }

    /**
     * Reads a batch, several screenshots at a time.
     *
     * Every coroutine in the batch waits on the reader pool, so the number of images decoded at
     * once is whatever [ScreenshotTextReader.parallelism] judged the device can hold, while the
     * batch size stays large enough that the database is written in one transaction rather than
     * one per screenshot. Those are two different constraints and they want two different
     * numbers.
     */
    private suspend fun readBatch(
        pool: Channel<ScreenshotTextReader>,
        batch: List<ScannedShot>
    ): List<ShotEntity> = coroutineScope {
        batch.map { shot ->
            async { withReader(pool) { reader -> newRow(shot, reader.read(shot.uri)) } }
        }.awaitAll()
    }

    private suspend fun rereadBatch(
        pool: Channel<ScreenshotTextReader>,
        batch: List<ShotEntity>
    ): List<ShotEntity> = coroutineScope {
        batch.map { row ->
            async {
                val result = withReader(pool) { reader -> reader.read(Uri.parse(row.uri)) }
                row.refreshed(result)
            }
        }.awaitAll()
    }

    private suspend fun <T> withReader(
        pool: Channel<ScreenshotTextReader>,
        block: suspend (ScreenshotTextReader) -> T
    ): T {
        val reader = pool.receive()
        try {
            return block(reader)
        } finally {
            pool.trySend(reader)
        }
    }

    private fun newRow(shot: ScannedShot, result: ReadResult?): ShotEntity {
        val text = result?.text.orEmpty()
        val lines = result?.lines.orEmpty()
        val codes = result?.codes.orEmpty()
        val derived = Derivation.derive(shot.displayName, text, lines, codes)
        return ShotEntity(
            mediaId = shot.mediaId,
            uri = shot.uri.toString(),
            displayName = shot.displayName,
            dateTakenMillis = shot.dateTakenMillis,
            sizeBytes = shot.sizeBytes,
            text = text,
            textHash = if (text.isBlank()) "" else sha1(text),
            category = derived.category.id,
            sourceApp = derived.sourceApp,
            entitiesJson = ExtractedCodec.encode(derived.entities),
            searchBlob = derived.searchBlob,
            indexedAtMillis = System.currentTimeMillis(),
            headline = derived.headline,
            linesBlob = TextLayout.encode(lines),
            codesBlob = CodeExtractor.encode(codes),
            script = (result?.script ?: ReadScript.NONE).id,
            phash = result?.phash ?: 0L,
            // A read that produced nothing is not marked as done by this engine version, so the
            // retry budget gets another go at it on a later scan rather than writing it off.
            readVersion = if (result == null) 0 else EngineVersion.READ,
            deriveVersion = EngineVersion.DERIVE,
            readAttempts = 1
        )
    }

    /** A re-read keeps the row's identity and everything the user decided about it. */
    private fun ShotEntity.refreshed(result: ReadResult?): ShotEntity {
        if (result == null) return copy(readAttempts = readAttempts + 1)

        val derived = Derivation.derive(displayName, result.text, result.lines, result.codes)
        return copy(
            text = result.text,
            textHash = if (result.text.isBlank()) "" else sha1(result.text),
            category = derived.category.id,
            sourceApp = derived.sourceApp,
            entitiesJson = ExtractedCodec.encode(derived.entities),
            searchBlob = derived.searchBlob,
            headline = derived.headline,
            linesBlob = TextLayout.encode(result.lines),
            codesBlob = CodeExtractor.encode(result.codes),
            script = result.script.id,
            phash = result.phash,
            readVersion = EngineVersion.READ,
            deriveVersion = EngineVersion.DERIVE,
            readAttempts = readAttempts + 1
        )
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val UNIQUE_NAME = "deja-index"

        /** Screenshots per database transaction. Independent of how many are decoded at once. */
        private const val WRITE_BATCH = 24

        /** Re-derives are pure CPU, so these can be much larger. */
        private const val DERIVE_BATCH = 200

        /**
         * How much of the backfill one run will do.
         *
         * WorkManager gives a worker about ten minutes before the system may stop it, and a
         * backfill has no deadline worth risking that for. What is left is picked up next launch.
         */
        private const val BACKFILL_PER_RUN = 400

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
