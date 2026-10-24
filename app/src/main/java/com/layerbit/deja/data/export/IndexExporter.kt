package com.layerbit.deja.data.export

import android.content.Context
import android.net.Uri
import android.util.JsonWriter
import com.layerbit.deja.BuildConfig
import com.layerbit.deja.data.db.DejaDatabase
import com.layerbit.deja.data.model.ExtractedCodec
import java.io.OutputStreamWriter
import java.time.Instant

/**
 * Writes everything Deja has learned to a JSON file the user picked.
 *
 * A no-network app that can hand over all of its data on request makes a trust argument no cloud
 * service can: this file *is* everything, because there is nowhere else for anything to be.
 *
 * Sensitive values - one-time codes, passwords, card and ID numbers - are left out unless the
 * user explicitly asks for them. Leaving them out of the entity list is not enough on its own,
 * because the same digits sit in the OCR text they were read from, so they are blanked there too.
 */
object IndexExporter {

    const val MIME = "application/json"

    /** @return how many screenshots were written. */
    suspend fun export(context: Context, target: Uri, includeSensitive: Boolean): Int {
        val rows = DejaDatabase.get(context).shotDao().all()
        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: error("Could not open the file for writing")

        JsonWriter(OutputStreamWriter(stream, Charsets.UTF_8)).use { json ->
            json.setIndent("  ")
            json.beginObject()
            json.name("app").value("Deja")
            json.name("version").value(BuildConfig.VERSION_NAME)
            json.name("exportedAt").value(Instant.now().toString())
            json.name("includesSensitiveValues").value(includeSensitive)
            json.name("count").value(rows.size.toLong())
            json.name("screenshots").beginArray()
            for (row in rows) {
                val entities = ExtractedCodec.decode(row.entitiesJson)
                val hidden = if (includeSensitive) {
                    emptyList()
                } else {
                    entities.filter { it.type.sensitive }.map { it.value }.filter { it.length >= 3 }
                }
                var text = row.text
                hidden.forEach { value -> text = text.replace(value, REDACTED) }

                json.beginObject()
                json.name("file").value(row.displayName)
                json.name("takenAt").value(Instant.ofEpochMilli(row.dateTakenMillis).toString())
                json.name("sizeBytes").value(row.sizeBytes)
                json.name("category").value(row.category)
                json.name("app").value(row.sourceApp)
                json.name("headline").value(row.headline)
                json.name("kept").value(row.pinned)
                json.name("text").value(text)
                json.name("found").beginArray()
                for (item in entities) {
                    if (item.type.sensitive && !includeSensitive) continue
                    json.beginObject()
                    json.name("type").value(item.type.id)
                    json.name("value").value(item.value)
                    json.endObject()
                }
                json.endArray()
                json.endObject()
            }
            json.endArray()
            json.endObject()
        }
        return rows.size
    }

    private const val REDACTED = "[hidden]"
}
