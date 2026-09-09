package com.layerbit.deja.ui.timeline

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.index.IndexingState
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

    private val _selected = MutableStateFlow<Category?>(null)
    val selected = _selected.asStateFlow()

    private val _reclaimable = MutableStateFlow(0L)
    val reclaimable = _reclaimable.asStateFlow()

    val indexing = IndexingState.progress

    val total = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val counts = repository.observeCategoryCounts()
        .map { rows -> rows.associate { Category.fromId(it.category) to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val shots = _selected
        .flatMapLatest { category ->
            if (category == null) repository.observeRecent() else repository.observeByCategory(category)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshReclaimable()
    }

    fun select(category: Category?) {
        _selected.value = if (_selected.value == category) null else category
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
    onOpenCleanup: () -> Unit,
    onOpenPrivacy: () -> Unit,
    viewModel: TimelineViewModel = viewModel()
) {
    val shots by viewModel.shots.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val total by viewModel.total.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val reclaimable by viewModel.reclaimable.collectAsState()
    val indexing by viewModel.indexing.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "deja", trailing = { SettingsButton(onClick = onOpenPrivacy) })

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SearchField(onClick = onOpenSearch)
                    Spacer(Modifier.height(14.dp))

                    if (indexing.running && indexing.hasWork) {
                        IndexingBanner(done = indexing.done, total = indexing.total)
                        Spacer(Modifier.height(14.dp))
                    }

                    if (reclaimable > 0) {
                        ReclaimBanner(bytes = reclaimable, onReview = onOpenCleanup)
                        Spacer(Modifier.height(14.dp))
                    }

                    CategoryChips(
                        total = total,
                        counts = counts,
                        selected = selected,
                        onSelect = viewModel::select
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (shots.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(indexing.running)
                }
            }

            // One header per day, then that day's screenshots.
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

        DejaBottomBar(current = Tab.TIMELINE) { tab ->
            when (tab) {
                Tab.TIMELINE -> Unit
                Tab.CLEAN -> onOpenCleanup()
                Tab.PRIVACY -> onOpenPrivacy()
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
        Text(
            text = "Search your screenshots",
            color = DejaColors.Dim,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun IndexingBanner(done: Int, total: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DejaColors.Surface)
            .padding(14.dp)
    ) {
        Text(
            text = "Reading your screenshots",
            color = DejaColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$done of $total",
            color = DejaColors.Muted,
            fontSize = 12.5.sp
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            color = DejaColors.Amber,
            trackColor = DejaColors.BorderStrong,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(3.dp))
        )
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
private fun CategoryChips(
    total: Int,
    counts: Map<Category, Int>,
    selected: Category?,
    onSelect: (Category?) -> Unit
) {
    val present = Category.entries.filter { (counts[it] ?: 0) > 0 }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Chip(
                label = "All",
                count = total,
                active = selected == null,
                onClick = { onSelect(null) }
            )
        }
        // item{} per chip rather than items(): it keeps the LazyRow overload of `items` out of
        // this file, which would otherwise clash with the LazyVerticalGrid one used below.
        present.forEach { category ->
            item {
                Chip(
                    label = category.label,
                    count = counts[category] ?: 0,
                    active = selected == category,
                    onClick = { onSelect(category) }
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) DejaColors.BorderStrong else DejaColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (active) DejaColors.Text else DejaColors.Muted,
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
private fun EmptyState(indexing: Boolean) {
    Column(Modifier.padding(vertical = 60.dp)) {
        Text(
            text = if (indexing) "Still reading…" else "Nothing here yet",
            color = DejaColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (indexing) {
                "Screenshots appear as Deja reads them."
            } else {
                "Take a screenshot and it will show up here."
            },
            color = DejaColors.Muted,
            fontSize = 13.5.sp
        )
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
