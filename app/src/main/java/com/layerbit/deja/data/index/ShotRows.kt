package com.layerbit.deja.data.index

import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.ExtractedCodec
import com.layerbit.deja.data.ocr.ReadResult
import com.layerbit.deja.data.ocr.ReadScript
import com.layerbit.deja.data.scan.ScannedShot
import java.security.MessageDigest

/**
 * Turns one read into one index row.
 *
 * The full scan, the new-screenshot watcher and the share sheet all index screenshots, and they
 * must produce exactly the same row for the same image - otherwise a screenshot shared in would be
 * searchable differently from one found by a scan. So there is one place that builds the row.
 */
object ShotRows {

    fun newRow(shot: ScannedShot, result: ReadResult?): ShotEntity {
        val text = result?.text.orEmpty()
        val lines = result?.lines.orEmpty()
        val codes = result?.codes.orEmpty()
        val derived = Derivation.derive(shot.displayName, text, lines, codes)
        return ShotEntity(
            mediaId = shot.mediaId,
            uri = shot.uri.toString(),
            displayName = shot.displayName,
            dateTakenMillis = shot.dateTakenMillis,
            sizeBytes = shot.sizeBytes,
            text = text,
            textHash = if (text.isBlank()) "" else sha1(text),
            category = derived.category.id,
            sourceApp = derived.sourceApp,
            entitiesJson = ExtractedCodec.encode(derived.entities),
            searchBlob = derived.searchBlob,
            indexedAtMillis = System.currentTimeMillis(),
            headline = derived.headline,
            linesBlob = TextLayout.encode(lines),
            codesBlob = CodeExtractor.encode(codes),
            script = (result?.script ?: ReadScript.NONE).id,
            phash = result?.phash ?: 0L,
            // A read that produced nothing is not marked as done by this engine version, so the
            // retry budget gets another go at it on a later scan rather than writing it off.
            readVersion = if (result == null) 0 else EngineVersion.READ,
            deriveVersion = EngineVersion.DERIVE,
            readAttempts = 1
        )
    }

    fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
