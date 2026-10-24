package com.layerbit.deja.widget

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.layerbit.deja.MainActivity
import com.layerbit.deja.R
import com.layerbit.deja.ambient.CopyReceiver
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.model.EntityType
import com.layerbit.deja.data.model.ExtractedCodec

/**
 * The newest one-time code Deja has read, masked, with a Copy button.
 *
 * Masked for the same reason as everywhere else: a home screen is the most looked-over screen on a
 * phone. Copy fetches the real value from the index at the moment of the tap.
 */
class LatestCodeWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val row = runCatching {
            DejaDatabase.get(context).shotDao().recentWithEntity(EntityType.CODE.id, 1).firstOrNull()
        }.getOrNull()
        val code = row?.let { r ->
            ExtractedCodec.decode(r.entitiesJson).firstOrNull { it.type == EntityType.CODE }
        }
        val title = context.getString(R.string.widget_code_title)
        val empty = context.getString(R.string.widget_code_empty)
        val copyLabel = context.getString(R.string.notif_action_copy)
        val age = row?.let {
            DateUtils.getRelativeTimeSpanString(
                it.dateTakenMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
        }.orEmpty()

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Widgets.background)
                    .cornerRadius(20.dp)
                    .padding(14.dp)
                    .clickable(
                        actionStartActivity(
                            if (row != null) MainActivity.openShotIntent(context, row.id)
                            else MainActivity.launchIntent(context)
                        )
                    )
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        color = Widgets.amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.height(6.dp))
                if (row == null || code == null) {
                    Text(text = empty, style = TextStyle(color = Widgets.muted, fontSize = 12.sp))
                } else {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                text = code.masked(),
                                style = TextStyle(
                                    color = Widgets.text,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(text = age, style = TextStyle(color = Widgets.muted, fontSize = 11.sp))
                        }
                        Box(
                            modifier = GlanceModifier
                                .cornerRadius(12.dp)
                                .background(Widgets.amberFill)
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                                .clickable(
                                    actionSendBroadcast(
                                        CopyReceiver.intent(context, row.id, EntityType.CODE)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = copyLabel,
                                style = TextStyle(
                                    color = Widgets.onAmber,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

class LatestCodeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LatestCodeWidget()
}
