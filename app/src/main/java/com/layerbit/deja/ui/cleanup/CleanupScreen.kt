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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.CleanupGroup
import com.layerbit.deja.ui.components.DejaBottomBar
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
    val selectedGroupIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val freedBytes: Long? = null
) {
    val selectedShots: List<ShotEntity>
        get() = groups.filter { it.id in selectedGroupIds }.flatMap { it.shots }

    val selectedBytes: Long get() = selectedShots.sumOf { it.sizeBytes }
    val totalBytes: Long get() = groups.sumOf { it.bytes }
}

class CleanupViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    private val _state = MutableStateFlow(CleanupUiState())
    val state = _state.asStateFlow()

    /** Screenshots handed to the last trash request, kept so the index can drop them on success. */
    private var pending: List<ShotEntity> = emptyList()

    fun load() {
        viewModelScope.launch {
            val groups = repository.cleanupGroups()
            _state.value = CleanupUiState(
                groups = groups,
                selectedGroupIds = groups.filter { it.selectedByDefault }.map { it.id }.toSet(),
                loading = false
            )
        }
    }

    fun toggle(groupId: String) {
        val current = _state.value
        val next = current.selectedGroupIds.toMutableSet()
        if (!next.add(groupId)) next.remove(groupId)
        _state.value = current.copy(selectedGroupIds = next)
    }

    /**
     * Builds the system trash request. Returns null when nothing is selected so the caller does
     * not launch an empty confirmation dialog.
     */
    fun trashRequest(): IntentSender? {
        val shots = _state.value.selectedShots
        if (shots.isEmpty()) return null
        pending = shots
        return repository.trashRequest(shots.map { Uri.parse(it.uri) }).intentSender
    }

    /** Only called once the system dialog reports the user actually confirmed. */
    fun onTrashConfirmed() {
        val trashed = pending
        pending = emptyList()
        viewModelScope.launch {
            repository.forgetMediaIds(trashed.map { it.mediaId })
            val freed = trashed.sumOf { it.sizeBytes }
            val groups = repository.cleanupGroups()
            _state.value = CleanupUiState(
                groups = groups,
                selectedGroupIds = groups.filter { it.selectedByDefault }.map { it.id }.toSet(),
                loading = false,
                freedBytes = freed
            )
        }
    }

    fun onTrashDismissed() {
        pending = emptyList()
    }
}

@Composable
fun CleanupScreen(
    onBack: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenPrivacy: () -> Unit,
    viewModel: CleanupViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onTrashConfirmed()
        } else {
            viewModel.onTrashDismissed()
        }
    }

    LaunchedEffect(Unit) { viewModel.load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Clean up", onBack = onBack)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 20.dp
            ),
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
                        text = if (state.loading) {
                            "Working out what's safe to remove…"
                        } else {
                            "Tap a group to include or exclude it"
                        },
                        color = DejaColors.Dim,
                        fontSize = 12.5.sp
                    )
                    state.freedBytes?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "${formatBytes(it)} moved to your gallery's trash",
                            color = DejaColors.Green,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            items(state.groups, key = { it.id }) { group ->
                GroupRow(
                    group = group,
                    selected = group.id in state.selectedGroupIds,
                    onToggle = { viewModel.toggle(group.id) }
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
                    text = "These move to your gallery's trash, not straight to deleted, so you " +
                        "can get them back from there.",
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
                            "Free up ${formatBytes(state.selectedBytes)} · " +
                                "${state.selectedShots.size} screenshots"
                        } else {
                            "Nothing selected"
                        },
                        color = if (enabled) DejaColors.OnAmber else DejaColors.Dim,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        DejaBottomBar(current = Tab.CLEAN) { tab ->
            when (tab) {
                Tab.TIMELINE -> onOpenTimeline()
                Tab.CLEAN -> Unit
                Tab.PRIVACY -> onOpenPrivacy()
            }
        }
    }
}

@Composable
private fun GroupRow(group: CleanupGroup, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) DejaColors.Surface else DejaColors.SurfaceDim)
            .clickable(onClick = onToggle)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) DejaColors.Amber else DejaColors.Border),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = DejaColors.OnAmber,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = group.title,
                color = if (selected) DejaColors.Text else DejaColors.Muted,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${group.count} · ${formatBytes(group.bytes)} · ${group.subtitle}",
                color = DejaColors.Dim,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(10.dp))
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
    }
}
