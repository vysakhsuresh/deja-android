package com.layerbit.deja.ambient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.layerbit.deja.MainActivity
import com.layerbit.deja.R
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.EntityType
import com.layerbit.deja.data.model.Extracted

/**
 * Posts "Deja read this" for a screenshot that was just taken.
 *
 * Sensitive values never appear in full: the body shows the masked form, the lock screen shows
 * nothing but the fact that a screenshot was read, and the value itself is fetched from the index
 * and copied only when Copy is tapped - it is never carried inside the notification.
 */
object ShotNotifier {

    private const val CHANNEL_ID = "new_screenshots"

    /** A one-time code is worthless after a few minutes, and so is a notification about it. */
    private const val CODE_TIMEOUT_MS = 10 * 60 * 1000L

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            // High so it peeks over the app you were in - that is the whole point of telling you
            // before you leave it. Silent, because a screenshot is not an event worth a sound.
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, shot: ShotEntity, highlight: Extracted) {
        if (!canPost(context)) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = notificationId(shot.id)

        val open = PendingIntent.getActivity(
            context,
            id,
            MainActivity.openShotIntent(context, shot.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val copy = PendingIntent.getBroadcast(
            context,
            id,
            CopyReceiver.intent(context, shot.id, highlight.type),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val label = context.getString(labelFor(highlight.type))
        val body = "$label · ${highlight.masked()}"

        // What the lock screen gets: that something was read, and nothing about what.
        val public = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_public_body))
            .build()

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE8A33D.toInt())
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notif_action_copy),
                    copy
                ).build()
            )
        if (highlight.type == EntityType.CODE) builder.setTimeoutAfter(CODE_TIMEOUT_MS)

        manager.notify(id, builder.build())
    }

    fun cancel(context: Context, shotId: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(shotId))
    }

    fun cancelAll(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }

    private fun notificationId(shotId: Long): Int = (shotId % Int.MAX_VALUE).toInt()

    fun labelFor(type: EntityType): Int = when (type) {
        EntityType.CODE -> R.string.entity_code
        EntityType.WIFI_PASSWORD -> R.string.entity_wifi_password
        EntityType.BOOKING_REF -> R.string.entity_booking_ref
        else -> R.string.entity_amount
    }

}
