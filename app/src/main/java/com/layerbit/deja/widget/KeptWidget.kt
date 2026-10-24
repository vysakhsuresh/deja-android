package com.layerbit.deja.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.layerbit.deja.MainActivity
import com.layerbit.deja.R
import com.layerbit.deja.data.db.DejaDatabase

/**
 * The Kept shelf on the home screen: the screenshots you starred, one tap from opening.
 */
class KeptWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val kept = runCatching { DejaDatabase.get(context).shotDao().pinned(MAX) }
            .getOrDefault(emptyList())
        val tiles = kept.map { it.id to Widgets.thumbnail(context, it.uri) }
        val title = context.getString(R.string.widget_kept_title)
        val empty = context.getString(R.string.widget_kept_empty)
        val describe = context.getString(R.string.widget_kept_item)

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Widgets.background)
                    .cornerRadius(20.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity(MainActivity.launchIntent(context)))
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        color = Widgets.amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.height(8.dp))
                if (tiles.isEmpty()) {
                    Text(
                        text = empty,
                        style = TextStyle(color = Widgets.muted, fontSize = 12.sp)
                    )
                } else {
                    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        tiles.forEachIndexed { index, (shotId, bitmap) ->
                            if (index > 0) Spacer(GlanceModifier.width(6.dp))
                            Tile(context, shotId, bitmap, describe)
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun RowScope.Tile(context: Context, shotId: Long, bitmap: Bitmap?, describe: String) {
        val modifier = GlanceModifier
            .defaultWeight()
            .fillMaxSize()
            .cornerRadius(10.dp)
            .background(Widgets.surface)
            .clickable(actionStartActivity(MainActivity.openShotIntent(context, shotId)))
            .semantics { contentDescription = describe }
        if (bitmap == null) {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {}
        } else {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = describe,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        }
    }

    private companion object {
        const val MAX = 4
    }
}

class KeptWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KeptWidget()
}
