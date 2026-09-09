package com.layerbit.deja.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun ShotThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    size: Int = 256,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    var bitmap by remember(uri, size) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri, size) {
        bitmap = ThumbnailLoader.load(context, uri, size)
    }

    Box(modifier = modifier.background(DejaColors.Surface)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
