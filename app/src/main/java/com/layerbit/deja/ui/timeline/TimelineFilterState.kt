package com.layerbit.deja.ui.timeline

import com.layerbit.deja.data.model.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the grid is narrowed to. Category and app are mutually exclusive by design. */
sealed interface TimelineFilter {
    data object All : TimelineFilter
    data class OfCategory(val category: Category) : TimelineFilter
    data class FromApp(val app: String) : TimelineFilter

    val label: String
        get() = when (this) {
            is All -> "All screenshots"
            is OfCategory -> category.label
            is FromApp -> app
        }
}

/**
 * The active filter, held outside the timeline's own ViewModel.
 *
 * Browse and the timeline are separate destinations but drive the same grid, and the timeline
 * entry is reused rather than recreated when you navigate back to it - so a filter passed as a
 * navigation argument would arrive after its ViewModel had already been built and be ignored.
 * Keeping it here means picking a category on Browse is simply visible when the timeline shows.
 */
object TimelineFilterState {

    private val _filter = MutableStateFlow<TimelineFilter>(TimelineFilter.All)
    val filter: StateFlow<TimelineFilter> = _filter.asStateFlow()

    fun set(value: TimelineFilter) {
        _filter.value = value
    }

    /** Tapping the active chip again clears it, which is what a second tap should mean. */
    fun toggleCategory(category: Category?) {
        val current = _filter.value
        _filter.value = when {
            category == null -> TimelineFilter.All
            current is TimelineFilter.OfCategory && current.category == category -> TimelineFilter.All
            else -> TimelineFilter.OfCategory(category)
        }
    }

    fun toggleApp(app: String) {
        val current = _filter.value
        _filter.value =
            if (current is TimelineFilter.FromApp && current.app == app) TimelineFilter.All
            else TimelineFilter.FromApp(app)
    }

    fun clear() {
        _filter.value = TimelineFilter.All
    }
}
