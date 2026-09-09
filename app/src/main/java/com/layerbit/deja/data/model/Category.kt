package com.layerbit.deja.data.model

enum class Category(val id: String, val label: String) {
    RECEIPT("receipt", "Receipts & bills"),
    TICKET("ticket", "Tickets & bookings"),
    CODE("code", "Codes & passwords"),
    DOCUMENT("document", "IDs & documents"),
    CHAT("chat", "Conversations"),
    PLACE("place", "Places & maps"),
    PRODUCT("product", "Products"),
    OTHER("other", "Everything else");

    companion object {
        fun fromId(id: String): Category = entries.firstOrNull { it.id == id } ?: OTHER
    }
}
