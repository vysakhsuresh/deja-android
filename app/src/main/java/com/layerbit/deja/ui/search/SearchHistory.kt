package com.layerbit.deja.ui.search

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last few things someone searched for.
 *
 * People look for the same handful of things over and over - the wifi password, the last OTP,
 * a booking - and retyping them is the kind of small friction that decides whether an app gets
 * opened the next time. Kept on the device in Deja's private preferences, like everything else
 * here, and wiped by the same control that wipes the index.
 *
 * Stored as an ordered list rather than a Set, because the order is the whole point and
 * `putStringSet` does not keep one.
 *
 * The list itself is shared across every instance. Two screens construct one of these - search
 * reads it, privacy wipes it - and a per-instance copy would mean "forget everything Deja knows"
 * leaving the search screen still showing the queries it had already loaded.
 */
class SearchHistory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("deja_search", Context.MODE_PRIVATE)

    init {
        if (!seeded) {
            shared.value = read()
            seeded = true
        }
    }

    val recent: StateFlow<List<String>> = shared.asStateFlow()

    fun record(raw: String) {
        val query = raw.trim()
        if (query.length < MIN_LENGTH) return

        val next = (listOf(query) + shared.value.filterNot { it.equals(query, ignoreCase = true) })
            .take(MAX_ENTRIES)
        if (next == shared.value) return

        shared.value = next
        prefs.edit().putString(KEY, next.joinToString(SEPARATOR)).apply()
    }

    fun clear() {
        shared.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun read(): List<String> =
        prefs.getString(KEY, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private companion object {
        private val shared = MutableStateFlow<List<String>>(emptyList())
        private var seeded = false

        const val KEY = "recent"

        /** A newline cannot appear in a query: the search field is single-line. */
        const val SEPARATOR = "\n"
        const val MAX_ENTRIES = 8
        const val MIN_LENGTH = 2
    }
}
