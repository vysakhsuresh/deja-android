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
    val entitiesJson: String,
    val indexedAtMillis: Long
)

/**
 * External-content FTS index over [ShotEntity.text]. Room generates the triggers that keep it in
 * step with the shots table, so nothing here writes to it directly.
 */
@Fts4(contentEntity = ShotEntity::class)
@Entity(tableName = "shots_fts")
data class ShotFts(val text: String)
