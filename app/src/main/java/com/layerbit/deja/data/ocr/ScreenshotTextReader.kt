package com.layerbit.deja.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.layerbit.deja.data.index.PerceptualHash
import com.layerbit.deja.data.index.TextLayout
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads a screenshot with ML Kit's bundled Latin recogniser. "Bundled" is the point: the model is
 * inside the APK, so this works with no network and no model download.
 *
 * Three things changed from the first version, and all three are about keeping information the
 * recogniser was already producing:
 *
 *  - The **line geometry** comes back with the text. See [TextLine].
 *  - The bitmap is **decoded here rather than by `InputImage.fromFilePath`**, which gives the
 *    scan a memory ceiling it can plan around. Several of these run at once now, and three
 *    full-resolution screenshots decoded at ARGB_8888 is most of a small phone's heap.
 *  - A **perceptual hash** is taken off the same bitmap, so near-duplicate detection costs no
 *    second read of the file.
 *
 * One reader owns one recogniser, and ML Kit makes no concurrency promise about a recogniser
 * instance, so callers that want parallelism hold a pool of readers rather than sharing one.
 */
class ScreenshotTextReader(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(uri: Uri): ReadResult? {
        val bitmap = decode(uri) ?: return null
        val phash = PerceptualHash.of(bitmap)
        val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrNull()
            ?: return ReadResult(text = "", lines = emptyList(), phash = phash)

        val recognised = recognise(image)
            ?: return ReadResult(text = "", lines = emptyList(), phash = phash)

        val lines = toLines(recognised, bitmap.width, bitmap.height)
        return ReadResult(text = TextLayout.flatten(lines), lines = lines, phash = phash)
    }

    fun close() = recognizer.close()

    private suspend fun recognise(image: InputImage): Text? =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { continuation.resume(null) }
        }

    private fun toLines(recognised: Text, width: Int, height: Int): List<TextLine> {
        if (width <= 0 || height <= 0) return emptyList()
        val horizontal = width.toFloat()
        val vertical = height.toFloat()

        return recognised.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text.replace('\n', ' ').trim()
                if (text.isEmpty()) return@mapNotNull null
                TextLine(
                    text = text,
                    top = perMille(box.top, vertical),
                    left = perMille(box.left, horizontal),
                    width = perMille(box.width(), horizontal),
                    height = perMille(box.height(), vertical)
                )
            }
    }

    private fun perMille(value: Int, extent: Float): Int =
        if (extent <= 0f) 0 else ((value * 1000f) / extent).toInt().coerceIn(0, 1000)

    /**
     * Decodes at full resolution unless the image is enormous.
     *
     * Downscaling a screenshot is the one thing guaranteed to cost OCR accuracy - the text is
     * small and already perfectly sharp - so the cap sits above any real phone screenshot and
     * exists only to stop something like a stitched long-screenshot or a panorama that wandered
     * into the Screenshots folder from allocating hundreds of megabytes.
     *
     * The bitmap is not recycled. ML Kit holds a reference to it for the life of the recognition
     * task and recycling underneath that is a crash waiting for a slow device; the decode ceiling
     * plus a bounded reader pool is what keeps the footprint honest instead.
     */
    private fun decode(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null

        var sample = 1
        while (longest / sample > MAX_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    companion object {
        private const val MAX_EDGE_PX = 3600

        /**
         * How many readers it is safe to run at once.
         *
         * Each one can hold a full-resolution ARGB bitmap plus whatever ML Kit copies alongside
         * it, so this is a memory question rather than a CPU one. A 1440x3120 screenshot is about
         * 18 MB decoded; small-heap devices get one reader and are no worse off than before.
         */
        fun parallelism(): Int {
            val heapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            return when {
                heapMb >= 384 -> 3
                heapMb >= 192 -> 2
                else -> 1
            }
        }
    }
}
