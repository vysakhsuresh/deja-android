package com.layerbit.deja.ui.cleanup

import com.layerbit.deja.data.db.ShotEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screenshots the user hand-picked on the picker screen, waiting to be folded into Clean up.
 *
 * Held here rather than passed as a navigation argument for the same reason as
 * `TimelineFilterState`: Clean up's entry in the nav graph is not necessarily recreated when
 * navigated back to, so an argument can arrive after the ViewModel that would read it already
 * exists and never sees it.
 */
object ManualSelectionState {

    private val _picks = MutableStateFlow<List<ShotEntity>>(emptyList())
    val picks: StateFlow<List<ShotEntity>> = _picks.asStateFlow()

    fun set(shots: List<ShotEntity>) {
        _picks.value = shots
    }

    /** Drops anything that was just deleted, so it cannot reappear as a stale pick. */
    fun remove(ids: Set<Long>) {
        _picks.value = _picks.value.filterNot { it.id in ids }
    }

    fun clear() {
        _picks.value = emptyList()
    }
}
