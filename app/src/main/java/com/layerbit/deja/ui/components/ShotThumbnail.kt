package com.layerbit.deja.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.layerbit.deja.ui.theme.DejaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnails are loaded straight from MediaStore instead of through an image library.
 *
 * That is a privacy decision as much as a dependency one: the usual Compose image loaders declare
 * INTERNET in their own manifests, which the manifest merger would union into ours and quietly
 * cost Deja the one guarantee it is built on.
 */
object ThumbnailLoader {

    // Roughly an eighth of the heap; each entry is a small decoded bitmap.
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8192).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** True when this exact thumbnail is already decoded, so no placeholder needs to show. */
    fun cached(uri: Uri, size: Int): Bitmap? = cache.get("$uri@$size")

    suspend fun load(context: Context, uri: Uri, size: Int): Bitmap? {
        val key = "$uri@$size"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }
}

/**
 * A thumbnail that never flashes.
 *
 * Three states, and the difference between them is most of why a grid feels fast or doesn't:
 * already decoded draws immediately with no animation at all, a fresh decode fades in over a
 * pulsing placeholder, and a decode that fails leaves the placeholder rather than a black hole.
 * Fading in something that was already in memory is the single easiest way to make a fast app
 * look slow, so the cache is checked synchronously before the first frame is drawn.
 */
@Composable
fun ShotThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    size: Int = 256,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val preloaded = remember(uri, size) { ThumbnailLoader.cached(uri, size) }
    var bitmap by remember(uri, size) { mutableStateOf(preloaded) }
    val inspecting = LocalInspectionMode.current

    LaunchedEffect(uri, size) {
        if (bitmap == null) bitmap = ThumbnailLoader.load(context, uri, size)
    }

    val target = if (bitmap != null) 1f else 0f
    val fade by animateFloatAsState(
        targetValue = target,
        // Nothing to fade when the bitmap was already in the cache: start fully opaque.
        animationSpec = tween(durationMillis = if (preloaded != null || inspecting) 0 else 260),
        label = "thumbFade"
    )

    Box(modifier = modifier.background(DejaColors.Surface)) {
        if (bitmap == null) {
            ShimmerBox(Modifier.fillMaxSize())
        }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(fade)
            )
        }
    }
}
