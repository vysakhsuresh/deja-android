package com.layerbit.deja.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads the text out of a screenshot with ML Kit's bundled Latin recogniser. "Bundled" is the
 * point: the model is inside the APK, so this works with no network and no model download.
 */
class ScreenshotTextReader(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(uri: Uri): String? {
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull() ?: return null
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { continuation.resume(null) }
        }
    }

    fun close() = recognizer.close()
}
