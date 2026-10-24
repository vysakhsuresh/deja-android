package com.layerbit.deja.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shots",
    indices = [
        Index(value = ["mediaId"], unique = true),
        Index(value = ["dateTakenMillis"]),
        Index(value = ["category"]),
        Index(value = ["sourceApp"]),
        Index(value = ["textHash"])
    ]
)
data class ShotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val dateTakenMillis: Long,
    val sizeBytes: Long,
    val text: String,
    val textHash: String,
    val category: String,
    /** App the screenshot was taken in, read off the filename. Empty when it carried no clue. */
    val sourceApp: String,
    val entitiesJson: String,
    /**
     * What search actually matches against: the OCR text plus the app name, the category label and
     * the non-sensitive extracted values. Keeping it as a stored column means one FTS table covers
     * "wifi password", "PhonePe" and "Payments & bills" without three separate lookups.
     */
    val searchBlob: String,
    val indexedAtMillis: Long,
    /**
     * Kept on purpose. A pinned screenshot is excluded from every Clean up group, which is the
     * whole point of it: the one way to make "tidy up my screenshots" safe enough to actually use
     * is to let someone mark the handful they will never want gone.
     */
    val pinned: Boolean = false,
    /**
     * The app bar and screen title, lifted off the top of the picture. The most informative
     * short string on any screenshot, and the only app signal that exists on phones that write
     * bare timestamps for filenames.
     */
    val headline: String = "",
    /**
     * Every recognised line with its position and size, encoded by `TextLayout`. This is what
     * lets the engine tell a receipt's total from its delivery fee, and what lets a later
     * extractor improve without reading the image again.
     */
    val linesBlob: String = "",
    /** 64-bit difference hash of the image; 0 when unknown. Finds near-identical screenshots. */
    val phash: Long = 0,
    /** Which `EngineVersion.READ` produced the text on this row. */
    val readVersion: Int = 0,
    /** Which `EngineVersion.DERIVE` produced the category, entities and search blob. */
    val deriveVersion: Int = 0,
    /** Reads attempted. Caps the retries on a screenshot that genuinely will not decode. */
    val readAttempts: Int = 0
)

/**
 * External-content FTS index over [ShotEntity.searchBlob]. Room generates the triggers that keep
 * it in step with the shots table, so nothing here writes to it directly.
 */
@Fts4(contentEntity = ShotEntity::class)
@Entity(tableName = "shots_fts")
data class ShotFts(val searchBlob: String)
