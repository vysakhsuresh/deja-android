package com.layerbit.deja.ui.detail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The run of screenshots the detail screen can swipe through.
 *
 * Opening one screenshot out of a grid and then being stuck with it is the single most annoying
 * thing a gallery can do, and the fix is not a feature so much as context: whichever screen you
 * tapped from hands over the list it was showing, in the order it was showing it, so swiping
 * sideways walks the same timeline or the same set of search results you were already looking at.
 *
 * Held as a shared object rather than a navigation argument for the same reason as
 * `TimelineFilterState`: a list of a few hundred ids has no business being serialised into a
 * route string, and the detail entry can be reused rather than rebuilt.
 */
object DetailContext {

    private val _ids = MutableStateFlow<List<Long>>(emptyList())
    val ids: StateFlow<List<Long>> = _ids.asStateFlow()

    fun set(value: List<Long>) {
        _ids.value = value
    }

    /** Keeps the swipe run honest after a deletion, so it cannot land on a screenshot that went. */
    fun remove(removed: Set<Long>) {
        if (removed.isEmpty()) return
        _ids.value = _ids.value.filterNot { it in removed }
    }
}
