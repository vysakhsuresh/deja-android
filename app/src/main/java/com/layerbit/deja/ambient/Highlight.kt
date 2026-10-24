package com.layerbit.deja.ambient

import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.EntityType
import com.layerbit.deja.data.model.Extracted
import com.layerbit.deja.data.model.ExtractedCodec

/**
 * The one value in a screenshot worth interrupting someone for.
 *
 * The test is "would you take this screenshot to copy that thing out of it a minute later": a
 * one-time code, a Wi-Fi password, a booking reference, an amount. A phone number or a link is
 * useful in the detail screen but not worth a notification.
 */
object Highlight {

    /** In order of how likely it is to be the reason the screenshot was taken. */
    val TYPES = listOf(
        EntityType.CODE,
        EntityType.WIFI_PASSWORD,
        EntityType.BOOKING_REF,
        EntityType.AMOUNT
    )

    fun of(row: ShotEntity): Extracted? = of(ExtractedCodec.decode(row.entitiesJson))

    fun of(entities: List<Extracted>): Extracted? =
        TYPES.firstNotNullOfOrNull { type -> entities.firstOrNull { it.type == type } }
}
