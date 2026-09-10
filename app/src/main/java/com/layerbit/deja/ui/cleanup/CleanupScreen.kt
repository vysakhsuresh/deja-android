package com.layerbit.deja.ui.cleanup

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.index.IndexWorker
import com.layerbit.deja.data.index.IndexingState
import com.layerbit.deja.data.model.CleanupGroup
import com.layerbit.deja.ui.components.ActionRow
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.DejaDialog
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.ShotThumbnail
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.components.formatBytes
import com.layerbit.deja.ui.theme.DejaColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CleanupUiState(
    val groups: List<CleanupGroup> = emptyList(),
    val selectedShotIds: Set<Long> = emptySet(),
    val expandedGroupId: String? = null,
    val loading: Boolean = true,
    val freedBytes: Long? = null
) {
    private val allShots: List<ShotEntity> get() = groups.flatMap { it.shots }

    val selectedShots: List<ShotEntity>
        get() = allShots.filter { it.id in selectedShotIds }

    val selectedBytes: Long get() = selectedShots.sumOf { it.sizeBytes }
    val totalBytes: Long get() = groups.sumOf { it.bytes }
    val totalCount: Int get() = allShots.size
    val allSelected: Boolean get() = totalCount > 0 && selectedShotIds.size >= totalCount
}

class CleanupViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    private val _state = MutableStateFlow(CleanupUiState())
    val state = _state.asStateFlow()

    val indexing = IndexingState.progress

    /** Screenshots handed to the last delete request, kept so the index can drop them on success. */
    private var pending: List<ShotEntity> = emptyList()

    fun load(keepFreed: Long? = null) {
        viewModelScope.launch {
            val automatic = repository.cleanupGroups()
            val manualPicks = ManualSelectionState.picks.value

            // A screenshot picked by hand takes priority over an automatic guess about it, so it
            // is never counted - or shown - twice.
            val manualIds = manualPicks.mapTo(mutableSetOf()) { it.id }
            val deduped = automatic
                .map { group -> group.copy(shots = group.shots.filterNot { it.id in manualIds }) }
                .filter { it.shots.isNotEmpty() }

            val manualGroup = manualPicks.takeIf { it.isNotEmpty() }?.let {
                CleanupGroup(
                    id = MANUAL_GROUP_ID,
                    title = "Your picks",
                    subtitle = "Chosen on the picker screen",
                    shots = it,
                    selectedByDefault = true
                )
            }

            val groups = listOfNotNull(manualGroup) + deduped
            val defaultSelected = groups
                .filter { it.selectedByDefault }
                .flatMap { it.shots }
                .mapTo(mutableSetOf()) { it.id }

            _state.value = CleanupUiState(
                groups = groups,
                selectedShotIds = defaultSelected,
                loading = false,
                freedBytes = keepFreed
            )
        }
    }

    fun toggleGroup(groupId: String) {
        val current = _state.value
        val group = current.groups.find { it.id == groupId } ?: return
        val ids = group.shots.map { it.id }
        val fullySelected = ids.all { it in current.selectedShotIds }
        val next = current.selectedShotIds.toMutableSet()
        if (fullySelected) next.removeAll(ids.toSet()) else next.addAll(ids)
        _state.value = current.copy(selectedShotIds = next)
    }

    fun toggleShot(shotId: Long) {
        val current = _state.value
        val next = current.selectedShotIds.toMutableSet()
        if (!next.add(shotId)) next.remove(shotId)
        _state.value = current.copy(selectedShotIds = next)
    }

    fun toggleExpand(groupId: String) {
        val current = _state.value
        _state.value = current.copy(
            expandedGroupId = if (current.expandedGroupId == groupId) null else groupId
        )
    }

    fun toggleAll() {
        val current = _state.value
        val allIds = current.groups.flatMap { it.shots }.map { it.id }.toSet()
        _state.value = current.copy(
            selectedShotIds = if (current.allSelected) emptySet() else allIds
        )
    }

    /** Builds the system trash request. Null when nothing is selected. */
    fun trashRequest(): IntentSender? {
        val shots = _state.value.selectedShots
        if (shots.isEmpty()) return null
        pending = shots
        return repository.trashRequest(shots.map { Uri.parse(it.uri) }).intentSender
    }

    /** Builds the system permanent-delete request. Null when nothing is selected. */
    fun deleteForeverRequest(): IntentSender? {
        val shots = _state.value.selectedShots
        if (shots.isEmpty()) return null
        pending = shots
        return repository.deleteForeverRequest(shots.map { Uri.parse(it.uri) }).intentSender
    }

    /** Only called once the system dialog reports the user actually confirmed. */
    fun onDeleteConfirmed() {
        val removed = pending
        pending = emptyList()
        viewModelScope.launch {
            repository.forgetMediaIds(removed.map { it.mediaId })
            ManualSelectionState.remove(removed.map { it.id }.toSet())
            load(keepFreed = removed.sumOf { it.sizeBytes })
        }
    }

    fun onDeleteDismissed() {
        pending = emptyList()
    }

    companion object {
        const val MANUAL_GROUP_ID = "manual"
    }
}

@Composable
fun CleanupScreen(
    onBack: () -> Unit,
    onSelectTab: (Tab) -> Unit,
    viewModel: CleanupViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    val context = LocalContext.current
    var confirmRescan by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.onDeleteConfirmed()
        else viewModel.onDeleteDismissed()
    }
    val foreverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.onDeleteConfirmed()
        else viewModel.onDeleteDismissed()
    }

    if (showPicker) {
        PickScreen(
            onDone = { showPicker = false; viewModel.load(keepFreed = state.freedBytes) },
            onCancel = { showPicker = false }
        )
        return
    }

    // Groups are derived from the index, so they are stale the moment a scan adds to it. This
    // also covers the initial load, since the phase is read on first composition.
    LaunchedEffect(indexing.phase) {
        if (!indexing.running) viewModel.load(keepFreed = state.freedBytes)
    }

    if (confirmRescan) {
        DejaDialog(
            title = "A scan is already running",
            message = "Deja has read ${indexing.done} of ${indexing.total} screenshots. " +
                "Starting again from the top throws that progress away and re-reads everything.",
            confirmLabel = "Start over anyway",
            destructive = true,
            onConfirm = {
                confirmRescan = false
                IndexWorker.restart(context)
            },
            onDismiss = { confirmRescan = false }
        )
    }

    if (confirmForever) {
        DejaDialog(
            title = "Delete permanently?",
            message = "${state.selectedShots.size} screenshots, ${formatBytes(state.selectedBytes)}" +
                " total. This skips the trash entirely - there is no undo and no recovery.",
            confirmLabel = "Delete forever",
            destructive = true,
            onConfirm = {
                confirmForever = false
                viewModel.deleteForeverRequest()?.let { sender ->
                    foreverLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
            },
            onDismiss = { confirmForever = false }
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Clean up",
            onBack = onBack,
            trailing = {
                Box(
                    modifier = Modifier
                        .size(TapTarget)
                        .clickable {
                            if (indexing.running) confirmRescan = true
                            else IndexWorker.restart(context)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Scan again",
                        tint = DejaColors.Muted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column {
                    Text(
                        text = formatBytes(state.totalBytes),
                        color = DejaColors.AmberBright,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            state.loading -> "Working out what's safe to remove…"
                            indexing.running -> "Still reading — this will grow as Deja catches up"
                            else -> "Tap a group to include or exclude it, or open one to choose " +
                                "individual screenshots"
                        },
                        color = DejaColors.Dim,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                    state.freedBytes?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "${formatBytes(it)} removed",
                            color = DejaColors.Green,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(14.dp))

                    ActionRow(
                        title = "Pick screenshots yourself",
                        subtitle = "Browse everything and choose exactly what goes",
                        onClick = { showPicker = true }
                    )

                    if (state.groups.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = if (state.allSelected) "Clear all" else "Select all groups",
                            color = DejaColors.Amber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.toggleAll() }
                                .padding(vertical = 8.dp, horizontal = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            items(state.groups, key = { it.id }) { group ->
                GroupRow(
                    group = group,
                    selectedIds = state.selectedShotIds,
                    expanded = state.expandedGroupId == group.id,
                    onToggleGroup = { viewModel.toggleGroup(group.id) },
                    onToggleExpand = { viewModel.toggleExpand(group.id) },
                    onToggleShot = viewModel::toggleShot
                )
            }

            if (!state.loading && state.groups.isEmpty()) {
                item {
                    Text(
                        text = "Nothing to clean up. Your screenshots folder is already tidy.",
                        color = DejaColors.Muted,
                        fontSize = 13.5.sp
                    )
                }
            }
        }

        if (state.groups.isNotEmpty()) {
            val enabled = state.selectedShots.isNotEmpty()
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "\"Move to trash\" is recoverable from your gallery's trash. " +
                        "\"Delete forever\" is not.",
                    color = DejaColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TapTarget + 6.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (enabled) DejaColors.Amber else DejaColors.Border)
                        .clickable(enabled = enabled) {
                            viewModel.trashRequest()?.let { sender ->
                                trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (enabled) {
                            "Move to trash · ${formatBytes(state.selectedBytes)} · " +
                                "${state.selectedShots.size} screenshots"
                        } else {
                            "Nothing selected"
                        },
                        color = if (enabled) DejaColors.OnAmber else DejaColors.Dim,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TapTarget)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(enabled = enabled) { confirmForever = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Delete forever instead — skips the trash, cannot be undone",
                        color = if (enabled) DejaColors.Danger else DejaColors.Dim,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        DejaBottomBar(current = Tab.CLEAN, onSelect = onSelectTab)
    }
}

/** empty / all / some, for the tri-state box on a group row. */
private enum class Tri { NONE, SOME, ALL }

private fun triState(group: CleanupGroup, selectedIds: Set<Long>): Tri {
    val ids = group.shots.map { it.id }
    val selectedCount = ids.count { it in selectedIds }
    return when (selectedCount) {
        0 -> Tri.NONE
        ids.size -> Tri.ALL
        else -> Tri.SOME
    }
}

@Composable
private fun GroupRow(
    group: CleanupGroup,
    selectedIds: Set<Long>,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onToggleExpand: () -> Unit,
    onToggleShot: (Long) -> Unit
) {
    val tri = triState(group, selectedIds)
    val selectedInGroup = group.shots.count { it.id in selectedIds }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (tri != Tri.NONE) DejaColors.Surface else DejaColors.SurfaceDim)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleGroup)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (tri == Tri.NONE) DejaColors.Border else DejaColors.Amber),
                contentAlignment = Alignment.Center
            ) {
                when (tri) {
                    Tri.ALL -> Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = DejaColors.OnAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    // Drawn rather than an icon glyph - material-icons-core ships only a small
                    // curated subset and a minus/remove icon is not reliably part of it.
                    Tri.SOME -> Box(
                        modifier = Modifier
                            .size(width = 10.dp, height = 2.dp)
                            .background(DejaColors.OnAmber, RoundedCornerShape(1.dp))
                    )
                    Tri.NONE -> Unit
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    color = if (tri != Tri.NONE) DejaColors.Text else DejaColors.Muted,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (tri == Tri.SOME) {
                        "$selectedInGroup of ${group.count} selected · ${formatBytes(group.bytes)}"
                    } else {
                        "${group.count} · ${formatBytes(group.bytes)} · ${group.subtitle}"
                    },
                    color = DejaColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                group.shots.take(3).forEach { shot ->
                    ShotThumbnail(
                        uri = Uri.parse(shot.uri),
                        size = 128,
                        modifier = Modifier
                            .width(25.dp)
                            .height(33.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(TapTarget - 8.dp)
                    .clickable(onClick = onToggleExpand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Choose individually",
                    tint = DejaColors.Dim
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.shots.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { shot ->
                            ExpandedTile(
                                shot = shot,
                                selected = shot.id in selectedIds,
                                onClick = { onToggleShot(shot.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedTile(
    shot: ShotEntity,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
    ) {
        ShotThumbnail(uri = Uri.parse(shot.uri), size = 160, modifier = Modifier.fillMaxSize())
        if (!selected) {
            Box(Modifier.fillMaxSize().background(DejaColors.Background.copy(alpha = 0.55f)))
        }
        Box(
            modifier = Modifier
                .padding(4.dp)
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) DejaColors.Amber else Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = DejaColors.OnAmber,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
