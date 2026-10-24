package com.layerbit.deja.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.layerbit.deja.data.index.PerceptualHash
import com.layerbit.deja.data.index.TextLayout
import com.layerbit.deja.data.index.TextQuality
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Everything Deja can learn from the pixels, in one pass over one decoded bitmap.
 *
 * Every model here is **bundled**: the weights are inside the APK, nothing is downloaded, and
 * none of it needs a socket. That is the whole reason the app can refuse the INTERNET
 * permission, and it is why each model is a real decision about what the app weighs rather than
 * a free addition.
 *
 * Three things come off the one decode:
 *
 *  - **Text**, Latin first. If what comes back does not look like language, the Devanagari
 *    recogniser gets a turn and the better of the two results wins. That model reads Latin as
 *    well as Devanagari, so a half-Hindi half-English screenshot comes back whole rather than
 *    half-read, and an English-only library never pays for the second pass at all.
 *  - **Codes.** A QR or barcode has no text to read, so OCR has never been able to touch it -
 *    and a Wi-Fi QR, a UPI QR and the PDF417 block on a boarding pass are among the most
 *    deliberately-kept screenshots there are. Payloads come back raw; parsing them is
 *    `CodeExtractor`'s job, so it can get better without another read.
 *  - **A perceptual hash**, for finding the same screen captured twice.
 *
 * One reader owns one set of clients, and ML Kit makes no concurrency promise about an
 * individual client, so callers that want parallelism hold a pool of readers rather than
 * sharing one.
 */
class ScreenshotTextReader(private val context: Context) {

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Built only if something actually needs it. Loading a recogniser costs memory and start-up
     * time, and on a library with no Devanagari in it this one is never touched.
     */
    private val devanagariClient = lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private val barcodes = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                // QR for Wi-Fi and UPI, PDF417 and Aztec for boarding passes, and the linear
                // formats that turn up on tickets and parcels.
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39
            )
            .build()
    )

    suspend fun read(uri: Uri): ReadResult? {
        val bitmap = decode(uri) ?: return null
        val phash = PerceptualHash.of(bitmap)
        val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrNull()
            ?: return ReadResult("", emptyList(), phash, emptyList(), ReadScript.NONE)

        val codes = scanCodes(image)

        val latinLines = recognise(latin, image, bitmap)
        val latinText = TextLayout.flatten(latinLines)
        val latinSuspicion = TextQuality.suspiciousShare(latinText)

        // Two ways a page earns a second pass. Either what came back looks like shape
        // matching rather than words, or the detector clearly found lines of text and the
        // recogniser then had almost nothing to say about them - which is what a script with no
        // model looks like from here. An image with no text at all produces no lines and so
        // never pays for the second pass.
        val needsOtherScript = latinSuspicion > TextQuality.ROUTE_THRESHOLD ||
            (latinLines.isNotEmpty() &&
                TextQuality.plausibleCharacters(latinText) < MIN_PLAUSIBLE_CHARS)

        if (!needsOtherScript) {
            return ReadResult(latinText, latinLines, phash, codes, ReadScript.LATIN)
        }

        val devanagariLines = recognise(devanagariClient.value, image, bitmap)
        val devanagariText = TextLayout.flatten(devanagariLines)

        // Whichever pass produced more that reads like language. The Devanagari model handles
        // Latin too, so on a mixed page it usually wins outright rather than trading one script
        // for the other.
        val useDevanagari = TextQuality.plausibleCharacters(devanagariText) >
            TextQuality.plausibleCharacters(latinText)

        val lines = if (useDevanagari) devanagariLines else latinLines
        val text = if (useDevanagari) devanagariText else latinText
        val script = if (useDevanagari) ReadScript.DEVANAGARI else ReadScript.LATIN

        // Still nothing that reads like language after both passes. Storing it would poison
        // the search index, randomise the duplicate hash and fill the "text Deja read" panel
        // with nonsense; recording that nothing was readable is honest, and lets the screenshot
        // land in the "No readable text" cleanup group where it belongs.
        //
        // Two conditions, not one. A page can be mostly junk and still hold a real sentence -
        // an English app bar above a column of some script neither model knows - and throwing
        // that away to avoid storing the junk around it is the worse trade.
        val hopeless = TextQuality.suspiciousShare(text) >= TextQuality.DISCARD_THRESHOLD &&
            TextQuality.plausibleCharacters(text) < MIN_PLAUSIBLE_CHARS
        if (hopeless) {
            return ReadResult("", emptyList(), phash, codes, ReadScript.NONE)
        }

        return ReadResult(text, lines, phash, codes, script)
    }

    fun close() {
        runCatching { latin.close() }
        runCatching { barcodes.close() }
        if (devanagariClient.isInitialized()) runCatching { devanagariClient.value.close() }
    }

    private suspend fun recognise(
        recognizer: TextRecognizer,
        image: InputImage,
        bitmap: Bitmap
    ): List<TextLine> {
        val recognised = suspendCancellableCoroutine<Text?> { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { continuation.resume(null) }
        } ?: return emptyList()
        return toLines(recognised, bitmap.width, bitmap.height)
    }

    private suspend fun scanCodes(image: InputImage): List<String> =
        suspendCancellableCoroutine<List<String>> { continuation ->
            barcodes.process(image)
                .addOnSuccessListener { found ->
                    continuation.resume(
                        found.mapNotNull { it.rawValue }.filter { it.isNotBlank() }.distinct()
                    )
                }
                .addOnFailureListener { continuation.resume(emptyList()) }
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
     * The bitmap is not recycled. ML Kit holds a reference to it for the life of a recognition
     * task and recycling underneath that is a crash waiting for a slow device; the decode
     * ceiling plus a bounded reader pool is what keeps the footprint honest instead.
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
         * Lines were found but barely a word came out of them. Low on purpose - this only ever
         * decides whether to spend one more OCR pass, and the better of the two results wins
         * either way.
         */
        private const val MIN_PLAUSIBLE_CHARS = 12

        /**
         * How many readers it is safe to run at once.
         *
         * Each one can hold a full-resolution ARGB bitmap plus whatever the models copy
         * alongside it, so this is a memory question rather than a CPU one. A 1440x3120
         * screenshot is about 18 MB decoded; small-heap devices get one reader and are no worse
         * off than before.
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
