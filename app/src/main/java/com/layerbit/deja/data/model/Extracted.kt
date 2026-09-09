package com.layerbit.deja.data.model

import org.json.JSONArray
import org.json.JSONObject

enum class EntityType(val id: String, val label: String) {
    CODE("code", "Code"),
    WIFI_NETWORK("wifi_network", "Network"),
    WIFI_PASSWORD("wifi_password", "Password"),
    AMOUNT("amount", "Amount"),
    DATE("date", "Date"),
    BOOKING_REF("booking_ref", "Booking ref"),
    PHONE("phone", "Phone"),
    EMAIL("email", "Email"),
    LINK("link", "Link");

    companion object {
        fun fromId(id: String): EntityType? = entries.firstOrNull { it.id == id }
    }
}

data class Extracted(val type: EntityType, val value: String)

/**
 * Entities are stored as one JSON string on the row rather than a joined table: they are only
 * ever read back for the screenshot they belong to, never queried across rows.
 */
object ExtractedCodec {

    fun encode(items: List<Extracted>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("t", item.type.id).put("v", item.value))
        }
        return array.toString()
    }

    fun decode(raw: String): List<Extracted> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                EntityType.fromId(obj.optString("t"))?.let { Extracted(it, obj.optString("v")) }
            }
        }.getOrDefault(emptyList())
    }
}
