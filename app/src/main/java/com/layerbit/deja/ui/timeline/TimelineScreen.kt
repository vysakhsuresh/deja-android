package com.layerbit.deja.ui.timeline

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.SettingsButton
import com.layerbit.deja.ui.components.ShotThumbnail
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.components.formatBytes
import com.layerbit.deja.ui.theme.DejaColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    private val _reclaimable = MutableStateFlow(0L)
    val reclaimable = _reclaimable.asStateFlow()

    val indexing = IndexingState.progress
    val incomplete = IndexingState.incomplete

    val total = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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
                is TimelineFilter.OfCategory -> repository.observeByCategory(current.category)
                is TimelineFilter.FromApp -> repository.observeByApp(current.app)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshReclaimable()
    }

    fun refreshReclaimable() {
        viewModelScope.launch {
            _reclaimable.value = repository.cleanupGroups().sumOf { it.bytes }
        }
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
    val filter by TimelineFilterState.filter.collectAsState()
    val reclaimable by viewModel.reclaimable.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    val incomplete by viewModel.incomplete.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "deja.",
            trailing = { SettingsButton(onClick = { onSelectTab(Tab.PRIVACY) }) }
        )

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

                    FilterChips(
                        total = total,
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
                        ShotTile(shot = shot, onClick = { onOpenShot(shot.id) })
                    }
                }
        }

        DejaBottomBar(current = Tab.TIMELINE, onSelect = onSelectTab)
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
            .clickable(onClick = onClick)
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
                    Text(
                        text = when {
                            progress.total == 0 && stopped -> "Pick up where Deja left off"
                            progress.total == 0 -> "Just a moment"
                            stopped -> "${progress.done} of ${progress.total} read · " +
                                "${progress.remaining} to go"

                            else -> "${progress.done} of ${progress.total} read"
                        },
                        color = DejaColors.Muted,
                        fontSize = 12.5.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .height(TapTarget - 8.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (stopped) DejaColors.Amber else DejaColors.BorderStrong)
                        .clickable(onClick = if (stopped) onResume else onStop)
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
                .clickable(onClick = onSelectMore)
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
                .clickable(onClick = onReview)
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
            .clickable(onClick = onClick)
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
            .clickable(onClick = onClick)
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
        Text(text = "$count", color = DejaColors.Dim, fontSize = 13.5.sp)
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
private fun ShotTile(shot: ShotEntity, onClick: () -> Unit) {
    ShotThumbnail(
        uri = Uri.parse(shot.uri),
        modifier = Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
    )
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
                    .clickable { TimelineFilterState.clear() }
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

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private val dayFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun LocalDate.friendlyLabel(): String {
    val today = LocalDate.now()
    return when (this) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> format(dayFormatter)
    }
}
