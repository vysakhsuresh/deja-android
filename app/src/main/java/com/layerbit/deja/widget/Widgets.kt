package com.layerbit.deja.widget

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.updateAll
import androidx.glance.color.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared plumbing for Deja's home-screen widgets. */
object Widgets {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Redraws every placed widget. Called whenever the index changes in a way a widget shows -
     * a new screenshot read, something kept or unkept, the index wiped.
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { KeptWidget().updateAll(app) }
            runCatching { LatestCodeWidget().updateAll(app) }
        }
    }

    /**
     * Small on purpose. A widget's whole view tree goes over binder as RemoteViews, which has a
     * hard size cap, so four phone-sized bitmaps would get the widget rejected outright.
     */
    fun thumbnail(context: Context, uri: String): Bitmap? = runCatching {
        context.contentResolver.loadThumbnail(Uri.parse(uri), Size(150, 200), null)
    }.getOrNull()

    // The same warm charcoal and amber as the app, with a light counterpart so a widget on a
    // light home screen does not sit there as a black slab.
    val background = ColorProvider(day = Color(0xFFF7F1E8), night = Color(0xFF151312))
    val surface = ColorProvider(day = Color(0xFFEDE4D7), night = Color(0xFF1A1817))
    val text = ColorProvider(day = Color(0xFF1C1814), night = Color(0xFFF5F1EC))
    val muted = ColorProvider(day = Color(0xFF6B6157), night = Color(0xFFA79E94))
    val amber = ColorProvider(day = Color(0xFFB0700F), night = Color(0xFFE8A33D))
    val onAmber = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF16120C))
    val amberFill = ColorProvider(day = Color(0xFFB0700F), night = Color(0xFFE8A33D))
}
