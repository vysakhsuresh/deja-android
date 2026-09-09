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
    val indexedAtMillis: Long
)

/**
 * External-content FTS index over [ShotEntity.searchBlob]. Room generates the triggers that keep
 * it in step with the shots table, so nothing here writes to it directly.
 */
@Fts4(contentEntity = ShotEntity::class)
@Entity(tableName = "shots_fts")
data class ShotFts(val searchBlob: String)
