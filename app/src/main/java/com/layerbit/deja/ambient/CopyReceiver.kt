package com.layerbit.deja.ambient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.layerbit.deja.R
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.model.EntityType
import com.layerbit.deja.data.model.ExtractedCodec
import com.layerbit.deja.util.Clipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Copy button on a notification or a widget.
 *
 * It carries a row id and an entity type, never the value: the value is read back out of the
 * index at the moment of the tap. So a one-time code is never sitting inside a PendingIntent, and
 * if the index has been wiped in the meantime there is simply nothing to copy.
 */
class CopyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val shotId = intent.getLongExtra(EXTRA_SHOT, -1L)
        val type = EntityType.fromId(intent.getStringExtra(EXTRA_TYPE).orEmpty()) ?: return
        if (shotId < 0) return

        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                val row = DejaDatabase.get(app).shotDao().byId(shotId)
                val value = row?.let { r ->
                    ExtractedCodec.decode(r.entitiesJson).firstOrNull { it.type == type }?.value
                }
                withContext(Dispatchers.Main) {
                    if (value == null) {
                        Toast.makeText(app, R.string.copy_gone, Toast.LENGTH_SHORT).show()
                    } else {
                        Clipboard.copy(
                            app,
                            app.getString(ShotNotifier.labelFor(type)),
                            value,
                            type.sensitive
                        )
                        if (!Clipboard.systemConfirmsCopy) {
                            Toast.makeText(app, R.string.copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                    ShotNotifier.cancel(app, shotId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_SHOT = "shot"
        private const val EXTRA_TYPE = "type"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, shotId: Long, type: EntityType): Intent =
            Intent(context, CopyReceiver::class.java)
                // Distinct data per row so two notifications never share one PendingIntent.
                .setData(android.net.Uri.parse("deja://copy/$shotId/${type.id}"))
                .putExtra(EXTRA_SHOT, shotId)
                .putExtra(EXTRA_TYPE, type.id)
    }
}
