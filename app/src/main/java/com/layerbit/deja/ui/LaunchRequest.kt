package com.layerbit.deja.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where something outside the app - a notification, a widget, a tile - asked Deja to open. */
sealed interface Launch {
    data class Shot(val shotId: Long) : Launch
    data object Search : Launch
}

/**
 * Hands a launch from the activity to the navigation graph.
 *
 * The activity sees the intent; only the nav host can act on it, and it may not exist yet when
 * the intent arrives. A one-slot state that the nav host consumes covers both orders.
 */
object LaunchRequest {
    private val _pending = MutableStateFlow<Launch?>(null)
    val pending: StateFlow<Launch?> = _pending.asStateFlow()

    fun post(launch: Launch) {
        _pending.value = launch
    }

    fun consume() {
        _pending.value = null
    }
}
