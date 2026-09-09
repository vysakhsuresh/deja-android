package com.layerbit.deja.data.model

/**
 * The buckets a screenshot can land in.
 *
 * Deliberately fine-grained. An earlier, coarser set left most of a real library sitting in
 * "Everything else", which makes the filter row useless - a category only earns its place if it
 * is the one someone would actually reach for.
 */
enum class Category(val id: String, val label: String) {
    RECEIPT("receipt", "Payments & bills"),
    BANKING("banking", "Bank & money"),
    TICKET("ticket", "Tickets & travel"),
    CODE("code", "Codes & passwords"),
    IDENTITY("identity", "IDs & documents"),
    CONTACT("contact", "People & contacts"),
    CHAT("chat", "Conversations"),
    SHOPPING("shopping", "Shopping"),
    PLACE("place", "Places & maps"),
    FOOD("food", "Food & recipes"),
    WORK("work", "Work & study"),
    SOCIAL("social", "Social & posts"),
    MEDIA("media", "Watch & listen"),
    HEALTH("health", "Health"),
    OTHER("other", "Everything else");

    companion object {
        fun fromId(id: String): Category = entries.firstOrNull { it.id == id } ?: OTHER
    }
}
