package com.layerbit.deja.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * @param sensitive values that should not be shown in full until the user asks. Deja can't leak
 *   them - it has no network - but a shoulder-glance at a timeline is still a real risk, and an
 *   Aadhaar number sitting in plain view in a list is bad manners regardless of the threat model.
 */
enum class EntityType(val id: String, val label: String, val sensitive: Boolean = false) {
    CODE("code", "One-time code", sensitive = true),
    WIFI_NETWORK("wifi_network", "Wi-Fi network"),
    WIFI_PASSWORD("wifi_password", "Wi-Fi password", sensitive = true),
    CARD("card", "Card number", sensitive = true),
    AADHAAR("aadhaar", "Aadhaar", sensitive = true),
    PAN("pan", "PAN", sensitive = true),
    PASSPORT("passport", "Passport", sensitive = true),
    VOTER_ID("voter_id", "Voter ID", sensitive = true),
    DRIVING_LICENCE("driving_licence", "Driving licence", sensitive = true),
    ACCOUNT_NUMBER("account_number", "Account number", sensitive = true),
    IFSC("ifsc", "IFSC"),
    GSTIN("gstin", "GSTIN"),
    UPI_ID("upi_id", "UPI ID"),
    AMOUNT("amount", "Amount"),
    DATE("date", "Date"),
    BOOKING_REF("booking_ref", "Booking ref"),
    VEHICLE("vehicle", "Vehicle number"),
    PHONE("phone", "Phone"),
    EMAIL("email", "Email"),
    HANDLE("handle", "Username"),
    LINK("link", "Link");

    companion object {
        fun fromId(id: String): EntityType? = entries.firstOrNull { it.id == id }
    }
}

data class Extracted(val type: EntityType, val value: String) {

    /** Last four characters only, for anything that shouldn't sit on screen in full. */
    fun masked(): String {
        if (!type.sensitive) return value
        val tail = value.filter { !it.isWhitespace() }.takeLast(4)
        return if (tail.isEmpty()) "••••" else "•••• $tail"
    }
}

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

    /**
     * What goes into the search index. Sensitive values are deliberately left out: being able to
     * find "the screenshot with my PAN on it" by searching for the word PAN is useful, being able
     * to surface the number itself by typing fragments of it is not.
     */
    fun searchableValues(items: List<Extracted>): String =
        items.filterNot { it.type.sensitive }
            .joinToString(" ") { "${it.type.label} ${it.value}" }
}
