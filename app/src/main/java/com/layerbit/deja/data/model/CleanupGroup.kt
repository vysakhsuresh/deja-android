package com.layerbit.deja.data.model

import com.layerbit.deja.data.db.ShotEntity

data class CleanupGroup(
    val id: String,
    val title: String,
    val subtitle: String,
    val shots: List<ShotEntity>,
    val selectedByDefault: Boolean
) {
    val bytes: Long get() = shots.sumOf { it.sizeBytes }
    val count: Int get() = shots.size
}
