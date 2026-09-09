package com.layerbit.deja.data.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScanPhase { IDLE, SCANNING, READING, FINISHED, STOPPED }

/**
 * @param done how many of the screenshots on the device are in the index right now.
 * @param total how many screenshots are on the device altogether.
 *
 * Both counts are library-wide on purpose. An earlier version reported progress against only the
 * unread ones, so a user with 4,000 screenshots who added three saw "1 of 3" and reasonably read
 * it as Deja having lost the rest.
 */
data class IndexProgress(
    val phase: ScanPhase = ScanPhase.IDLE,
    val done: Int = 0,
    val total: Int = 0
) {
    val running: Boolean get() = phase == ScanPhase.SCANNING || phase == ScanPhase.READING
    val remaining: Int get() = (total - done).coerceAtLeast(0)
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Scan progress, shared between the worker and the UI. Both live in the same process, so a
 * singleton flow is enough and avoids round-tripping this through WorkManager's progress data.
 */
object IndexingState {

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    private val _incomplete = MutableStateFlow(false)

    /**
     * True while a scan has been started and never finished - including across a restart, since it
     * is seeded from disk when the app starts.
     *
     * The UI needs this separately from [progress] because the phase is only ever process-local:
     * stopping a scan and then closing the app used to leave no trace of the unfinished work at
     * all, so there was nothing left to offer a Resume against.
     */
    val incomplete: StateFlow<Boolean> = _incomplete.asStateFlow()

    fun seedIncomplete(value: Boolean) {
        _incomplete.value = value
    }

    fun scanning() {
        _incomplete.value = true
        _progress.value = _progress.value.copy(phase = ScanPhase.SCANNING)
    }

    fun reading(done: Int, total: Int) {
        _progress.value = IndexProgress(ScanPhase.READING, done, total)
    }

    fun advance(done: Int) {
        _progress.value = _progress.value.copy(done = done)
    }

    fun finished() {
        val current = _progress.value
        _incomplete.value = false
        _progress.value = current.copy(phase = ScanPhase.FINISHED, done = current.total)
    }

    /** Nothing needed reading. Skips the reading phase entirely so no progress UI ever appears. */
    fun upToDate(total: Int) {
        _incomplete.value = false
        _progress.value = IndexProgress(ScanPhase.FINISHED, total, total)
    }

    fun stopped() {
        _incomplete.value = true
        _progress.value = _progress.value.copy(phase = ScanPhase.STOPPED)
    }

    fun reset() {
        _progress.value = IndexProgress()
    }
}
