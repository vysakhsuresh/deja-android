package com.layerbit.deja.ui.timeline

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.db.AppCount
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.index.IndexProgress
import com.layerbit.deja.data.index.IndexWorker
import com.layerbit.deja.data.index.IndexingState
import com.layerbit.deja.data.index.ScanPhase
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.ui.components.AnimatedCount
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.SettingsButton
import com.layerbit.deja.ui.components.ShotThumbnail
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.components.formatBytes
import com.layerbit.deja.ui.components.tappable
import com.layerbit.deja.ui.detail.DetailContext
import com.layerbit.deja.ui.theme.DejaColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    private val _reclaimable = MutableStateFlow(0L)
    val reclaimable = _reclaimable.asStateFlow()

    /** Empty means normal browsing; anything in it puts the screen in selection mode. */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection = _selection.asStateFlow()

    private val _justDid = MutableStateFlow<String?>(null)
    val justDid = _justDid.asStateFlow()

    val indexing = IndexingState.progress
    val incomplete = IndexingState.incomplete

    val total = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pinnedCount = repository.observePinnedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pinned = repository.observePinned(limit = 40)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val counts = repository.observeCategoryCounts()
        .map { rows -> rows.associate { Category.fromId(it.category) to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val apps = repository.observeAppCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val shots = TimelineFilterState.filter
        .flatMapLatest { current ->
            when (current) {
                is TimelineFilter.All -> repository.observeRecent()
                is TimelineFilter.Pinned -> repository.observePinned()
                is TimelineFilter.OfCategory -> repository.observeByCategory(current.category)
                is TimelineFilter.FromApp -> repository.observeByApp(current.app)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Screenshots handed to the last delete request, kept so the index can drop them on success. */
    private var pendingDelete: List<ShotEntity> = emptyList()

    init {
        refreshReclaimable()
        // Changing the filter changes what is on screen, so a selection made against the old
        // grid would be a count of screenshots nobody can see any more.
        TimelineFilterState.filter
            .onEach { _selection.value = emptySet() }
            .launchIn(viewModelScope)
    }

    fun refreshReclaimable() {
        viewModelScope.launch {
            _reclaimable.value = repository.cleanupGroups().sumOf { it.bytes }
        }
    }

    fun toggleSelect(id: Long) {
        val next = _selection.value.toMutableSet()
        if (!next.add(id)) next.remove(id)
        _selection.value = next
    }

    fun selectAllVisible() {
        _selection.value = shots.value.mapTo(mutableSetOf()) { it.id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun selected(): List<ShotEntity> =
        shots.value.filter { it.id in _selection.value }

    fun selectedUris(): List<Uri> = selected().map { Uri.parse(it.uri) }

    /** True when every selected screenshot is already kept, so the button can say "Unkeep". */
    fun selectionAllPinned(): Boolean {
        val chosen = selected()
        return chosen.isNotEmpty() && chosen.all { it.pinned }
    }

    fun pinSelection(pin: Boolean) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.setPinned(ids, pin)
            _justDid.value = if (pin) "Kept ${ids.size}" else "No longer kept"
            _selection.value = emptySet()
            refreshReclaimable()
        }
    }

    fun trashSelectionRequest(): IntentSender? {
        val chosen = selected()
        if (chosen.isEmpty()) return null
        pendingDelete = chosen
        return repository.trashRequest(chosen.map { Uri.parse(it.uri) }).intentSender
    }

    fun onTrashConfirmed() {
        val removed = pendingDelete
        pendingDelete = emptyList()
        if (removed.isEmpty()) return
        viewModelScope.launch {
            repository.forgetMediaIds(removed.map { it.mediaId })
            DetailContext.remove(removed.mapTo(mutableSetOf()) { it.id })
            _justDid.value = "${removed.size} moved to trash"
            _selection.value = emptySet()
            refreshReclaimable()
        }
    }

    fun onTrashDismissed() {
        pendingDelete = emptyList()
    }

    fun clearJustDid() {
        _justDid.value = null
    }
}

@Composable
fun TimelineScreen(
    onOpenSearch: () -> Unit,
    onOpenShot: (Long) -> Unit,
    onSelectTab: (Tab) -> Unit,
    partialAccess: Boolean,
    onRequestMoreAccess: () -> Unit,
    viewModel: TimelineViewModel = viewModel()
) {
    val context = LocalContext.current
    val shots by viewModel.shots.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val total by viewModel.total.collectAsState()
    val pinnedCount by viewModel.pinnedCount.collectAsState()
    val pinned by viewModel.pinned.collectAsState()
    val filter by TimelineFilterState.filter.collectAsState()
    val reclaimable by viewModel.reclaimable.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    val incomplete by viewModel.incomplete.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val justDid by viewModel.justDid.collectAsState()

    val selecting = selection.isNotEmpty()

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onTrashConfirmed()
        else viewModel.onTrashDismissed()
    }

    /** Every route into the detail screen hands over the run it was showing, in its own order. */
    val open: (Long) -> Unit = { id ->
        DetailContext.set(shots.map { it.id })
        onOpenShot(id)
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionHeader(
                count = selection.size,
                onClose = { viewModel.clearSelection() },
                onSelectAll = { viewModel.selectAllVisible() }
            )
        } else {
            ScreenHeader(
                title = "deja.",
                trailing = { SettingsButton(onClick = { onSelectTab(Tab.PRIVACY) }) }
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SearchField(onClick = onOpenSearch)
                    Spacer(Modifier.height(14.dp))

                    if (partialAccess) {
                        PartialAccessBanner(onSelectMore = onRequestMoreAccess)
                        Spacer(Modifier.height(14.dp))
                    }

                    ScanBanner(
                        progress = indexing,
                        incomplete = incomplete,
                        onStop = { IndexWorker.stop(context) },
                        onResume = { IndexWorker.enqueue(context) }
                    )

                    if (reclaimable > 0) {
                        ReclaimBanner(
                            bytes = reclaimable,
                            onReview = { onSelectTab(Tab.CLEAN) }
                        )
                        Spacer(Modifier.height(14.dp))
                    }

                    // The shelf only shows on the unfiltered timeline. Inside a filter it would
                    // be showing screenshots that are not part of what you asked to see.
                    if (pinned.isNotEmpty() && filter is TimelineFilter.All) {
                        KeptShelf(shots = pinned, onOpen = { id ->
                            DetailContext.set(pinned.map { it.id })
                            onOpenShot(id)
                        })
                        Spacer(Modifier.height(16.dp))
                    }

                    FilterChips(
                        total = total,
                        pinnedCount = pinnedCount,
                        counts = counts,
                        apps = apps,
                        filter = filter,
                        onBrowseAll = { onSelectTab(Tab.BROWSE) }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (shots.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(indexing.running, filter)
                }
            }

            shots.groupBy { it.dateTakenMillis.toLocalDate() }
                .forEach { (day, shotsForDay) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DayHeader(day = day, count = shotsForDay.size)
                    }
                    items(shotsForDay, key = { it.id }) { shot ->
                        ShotTile(
                            shot = shot,
                            selecting = selecting,
                            selected = shot.id in selection,
                            onClick = {
                                if (selecting) viewModel.toggleSelect(shot.id) else open(shot.id)
                            },
                            onLongClick = { viewModel.toggleSelect(shot.id) }
                        )
                    }
                }
        }

        justDid?.let { message ->
            // Clears itself, because a confirmation that needs dismissing is a second chore
            // bolted onto the one that was just finished.
            LaunchedEffect(message) {
                delay(3_500)
                viewModel.clearJustDid()
            }
            ActedBar(message = message, onDismiss = { viewModel.clearJustDid() })
        }

        AnimatedVisibility(visible = selecting) {
            SelectionBar(
                count = selection.size,
                allPinned = viewModel.selectionAllPinned(),
                onPin = { viewModel.pinSelection(!viewModel.selectionAllPinned()) },
                onShare = { context.shareAll(viewModel.selectedUris()) },
                onTrash = {
                    viewModel.trashSelectionRequest()?.let { sender ->
                        trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                }
            )
        }

        DejaBottomBar(current = Tab.TIMELINE, onSelect = onSelectTab)
    }
}

@Composable
private fun SelectionHeader(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(TapTarget)
                .clip(RoundedCornerShape(12.dp))
                .tappable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Leave selection",
                tint = DejaColors.Text
            )
        }
        Spacer(Modifier.width(4.dp))
        AnimatedCount(
            value = count,
            color = DejaColors.Text,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            format = { "$it selected" }
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Select all",
            color = DejaColors.Amber,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .tappable(onClick = onSelectAll)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allPinned: Boolean,
    onPin: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DejaColors.Surface)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarAction(
            icon = Icons.Filled.Star,
            label = if (allPinned) "Unkeep" else "Keep",
            tint = DejaColors.Amber,
            modifier = Modifier.weight(1f),
            onClick = onPin
        )
        BarAction(
            icon = Icons.Filled.Share,
            label = "Share",
            tint = DejaColors.Text,
            modifier = Modifier.weight(1f),
            onClick = onShare
        )
        BarAction(
            icon = Icons.Filled.Delete,
            label = "Trash $count",
            tint = DejaColors.Danger,
            modifier = Modifier.weight(1f),
            onClick = onTrash
        )
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .tappable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(text = label, color = tint, fontSize = 11.5.sp, maxLines = 1)
    }
}

/** Confirmation that something happened, dismissed by tapping it. Deliberately not a toast. */
@Composable
private fun ActedBar(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(DejaColors.GreenDim)
            .tappable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = DejaColors.Green,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(text = "Dismiss", color = DejaColors.Dim, fontSize = 12.5.sp)
    }
}

/**
 * The handful of screenshots someone marked as never-delete, along the top of the timeline.
 *
 * This is the payoff for pinning. A flag that only ever protects something from a cleanup screen
 * is invisible work; a flag that also puts the boarding pass one tap from the home screen is the
 * reason to use the app on the day you need it.
 */
@Composable
private fun KeptShelf(shots: List<ShotEntity>, onOpen: (Long) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = DejaColors.Amber,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Kept",
                color = DejaColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(7.dp))
            Text(text = "${shots.size}", color = DejaColors.Dim, fontSize = 12.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shots.forEach { shot ->
                item(key = shot.id) {
                    ShotThumbnail(
                        uri = Uri.parse(shot.uri),
                        size = 192,
                        contentDescription = "Kept screenshot",
                        modifier = Modifier
                            .width(68.dp)
                            .height(91.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .tappable(pressScale = 1.05f, onClick = { onOpen(shot.id) })
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(DejaColors.Surface)
            .tappable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = DejaColors.Dim,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.size(11.dp))
        Text(text = "Search your screenshots", color = DejaColors.Dim, fontSize = 15.sp)
    }
}

/**
 * @param incomplete a scan was started and never finished, including from a previous run of the
 *   app. This is what keeps Resume reachable: the phase alone is process-local, so stopping a scan
 *   and closing Deja used to leave no way back to it at all.
 */
@Composable
private fun ScanBanner(
    progress: IndexProgress,
    incomplete: Boolean,
    onStop: () -> Unit,
    onResume: () -> Unit
) {
    val stopped = !progress.running && incomplete
    if (!progress.running && !stopped) return

    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DejaColors.Surface)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            stopped -> "Scan unfinished"
                            progress.phase == ScanPhase.SCANNING -> "Looking for screenshots"
                            else -> "Reading your screenshots"
                        },
                        color = DejaColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    if (progress.total == 0) {
                        Text(
                            text = if (stopped) "Pick up where Deja left off" else "Just a moment",
                            color = DejaColors.Muted,
                            fontSize = 12.5.sp
                        )
                    } else {
                        // Counting up rather than jumping: the number climbing is the clearest
                        // sign that a long first scan is actually moving.
                        AnimatedCount(
                            value = progress.done,
                            color = DejaColors.Muted,
                            fontSize = 12.5.sp,
                            format = { done ->
                                if (stopped) {
                                    "$done of ${progress.total} read · " +
                                        "${progress.remaining} to go"
                                } else {
                                    "$done of ${progress.total} read"
                                }
                            }
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .height(TapTarget - 8.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (stopped) DejaColors.Amber else DejaColors.BorderStrong)
                        .tappable(onClick = if (stopped) onResume else onStop)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (stopped) "Resume" else "Stop",
                        color = if (stopped) DejaColors.OnAmber else DejaColors.Text,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (progress.total > 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    color = if (stopped) DejaColors.Dim else DejaColors.Amber,
                    trackColor = DejaColors.BorderStrong,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

/**
 * Android 14 lets someone grant a hand-picked set of images rather than the folder. Deja works
 * fine that way - it just cannot see anything that was not picked - so the honest thing is to say
 * so and offer the picker again, not to pretend the library is small.
 */
@Composable
private fun PartialAccessBanner(onSelectMore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DejaColors.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Deja can only see the screenshots you picked",
                color = DejaColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Anything else on the phone stays invisible to it.",
                color = DejaColors.Muted,
                fontSize = 12.5.sp
            )
        }
        Box(
            modifier = Modifier
                .height(TapTarget)
                .clip(RoundedCornerShape(12.dp))
                .background(DejaColors.BorderStrong)
                .tappable(onClick = onSelectMore)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Select more",
                color = DejaColors.Text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ReclaimBanner(bytes: Long, onReview: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DejaColors.SurfaceDim)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = formatBytes(bytes),
                color = DejaColors.AmberBright,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "in screenshots you probably don't need",
                color = DejaColors.Muted,
                fontSize = 12.5.sp
            )
        }
        Box(
            modifier = Modifier
                .height(TapTarget)
                .clip(RoundedCornerShape(12.dp))
                .background(DejaColors.Amber)
                .tappable(onClick = onReview)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Review",
                color = DejaColors.OnAmber,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun FilterChips(
    total: Int,
    pinnedCount: Int,
    counts: Map<Category, Int>,
    apps: List<AppCount>,
    filter: TimelineFilter,
    onBrowseAll: () -> Unit
) {
    val present = Category.entries.filter { (counts[it] ?: 0) > 0 }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Chip(
                    label = "All",
                    count = total,
                    active = filter is TimelineFilter.All,
                    onClick = { TimelineFilterState.clear() }
                )
            }
            if (pinnedCount > 0) {
                item {
                    Chip(
                        label = "Kept",
                        count = pinnedCount,
                        active = filter is TimelineFilter.Pinned,
                        onClick = { TimelineFilterState.togglePinned() },
                        accent = true
                    )
                }
            }
            present.forEach { category ->
                item {
                    Chip(
                        label = category.label,
                        count = counts[category] ?: 0,
                        active = filter is TimelineFilter.OfCategory && filter.category == category,
                        onClick = { TimelineFilterState.toggleCategory(category) }
                    )
                }
            }
            // The chip row only ever shows what fits; Browse is where the whole list lives.
            item { BrowseChip(onClick = onBrowseAll) }
        }

        if (apps.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                apps.forEach { row ->
                    item {
                        Chip(
                            label = row.sourceApp,
                            count = row.count,
                            active = filter is TimelineFilter.FromApp && filter.app == row.sourceApp,
                            onClick = { TimelineFilterState.toggleApp(row.sourceApp) },
                            accent = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DejaColors.SurfaceDim)
            .tappable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "See all",
            color = DejaColors.Amber,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Chip(
    label: String,
    count: Int,
    active: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false
) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    active && accent -> DejaColors.AmberDim
                    active -> DejaColors.BorderStrong
                    else -> DejaColors.Surface
                }
            )
            .tappable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = when {
                active && accent -> DejaColors.AmberBright
                active -> DejaColors.Text
                else -> DejaColors.Muted
            },
            fontSize = 13.5.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
        )
        Spacer(Modifier.size(6.dp))
        // Counts move as a scan runs; animating them keeps the row from flickering.
        AnimatedCount(value = count, color = DejaColors.Dim, fontSize = 13.5.sp)
    }
}

@Composable
private fun DayHeader(day: LocalDate, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = day.friendlyLabel(),
            color = DejaColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (count == 1) "1 screenshot" else "$count screenshots",
            color = DejaColors.Dim,
            fontSize = 12.5.sp
        )
    }
}

@Composable
private fun ShotTile(
    shot: ShotEntity,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(11.dp))
            .tappable(pressScale = 1.04f, onClick = onClick, onLongClick = onLongClick)
    ) {
        ShotThumbnail(
            uri = Uri.parse(shot.uri),
            contentDescription = "Screenshot from ${shot.dateTakenMillis.asTileDate()}",
            modifier = Modifier.fillMaxSize()
        )

        if (shot.pinned && !selecting) {
            Box(
                modifier = Modifier
                    .padding(5.dp)
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Kept",
                    tint = DejaColors.Amber,
                    modifier = Modifier.size(11.dp)
                )
            }
        }

        if (selecting) {
            if (selected) {
                Box(Modifier.fillMaxSize().background(DejaColors.Amber.copy(alpha = 0.28f)))
            }
            Box(
                modifier = Modifier
                    .padding(5.dp)
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (selected) DejaColors.Amber else Color.Black.copy(alpha = 0.45f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = DejaColors.OnAmber,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(indexing: Boolean, filter: TimelineFilter) {
    val filtered = filter !is TimelineFilter.All
    Column(Modifier.padding(vertical = 60.dp)) {
        Text(
            text = when {
                filtered -> "Nothing in ${filter.label}"
                indexing -> "Still reading…"
                else -> "Nothing here yet"
            },
            color = DejaColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                filtered && indexing -> "Deja is still reading — this may fill up as it goes."
                filtered -> "Tap All to see everything again."
                indexing -> "Screenshots appear as Deja reads them."
                else -> "Take a screenshot and it will show up here."
            },
            color = DejaColors.Muted,
            fontSize = 13.5.sp
        )
        if (filtered) {
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .height(TapTarget)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DejaColors.Surface)
                    .tappable(onClick = { TimelineFilterState.clear() })
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Show all screenshots",
                    color = DejaColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** Multi-share goes out as one chooser, so twelve screenshots are one action rather than twelve. */
private fun Context.shareAll(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(intent, "Share screenshots")) }
}

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private val dayFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun Long.asTileDate(): String = toLocalDate().format(dayFormatter)

private fun LocalDate.friendlyLabel(): String {
    val today = LocalDate.now()
    return when (this) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> format(dayFormatter)
    }
}
