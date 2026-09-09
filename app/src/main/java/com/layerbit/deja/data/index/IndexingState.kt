package com.layerbit.deja.data.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IndexProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0
) {
    val hasWork: Boolean get() = total > 0
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/**
 * Indexing progress, shared between the worker and the UI. Both live in the same process, so a
 * singleton flow is enough and avoids round-tripping this through WorkManager's progress data.
 */
object IndexingState {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    fun start(total: Int) {
        _progress.value = IndexProgress(running = true, done = 0, total = total)
    }

    fun advance(done: Int) {
        _progress.value = _progress.value.copy(done = done)
    }

    fun finish() {
        _progress.value = _progress.value.copy(running = false)
    }
}
