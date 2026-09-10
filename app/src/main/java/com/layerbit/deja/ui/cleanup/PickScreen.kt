package com.layerbit.deja.ui.cleanup

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.ShotThumbnail
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.theme.DejaColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class PickViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    // High enough to cover a real library rather than only the timeline's default page.
    val shots = repository.observeRecent(limit = 5000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow(
        ManualSelectionState.picks.value.mapTo(mutableSetOf()) { it.id }
    )
    val selected = _selected.asStateFlow()

    fun toggle(id: Long) {
        val next = _selected.value.toMutableSet()
        if (!next.add(id)) next.remove(id)
        _selected.value = next
    }

    /** Hands the chosen screenshots to Clean up. Nothing is deleted here. */
    fun confirm() {
        val ids = _selected.value
        ManualSelectionState.set(shots.value.filter { it.id in ids })
    }
}

/**
 * A full grid of every indexed screenshot with a checkbox on each tile, for picking exactly which
 * ones to remove rather than trusting one of Clean up's automatic groups.
 *
 * Nothing here deletes anything - "Done" just hands the selection back to Clean up, which is where
 * the actual trash/delete confirmation lives, so this screen and Clean up's own group toggles share
 * one exit path and one set of warnings.
 */
@Composable
fun PickScreen(onDone: () -> Unit, onCancel: () -> Unit, viewModel: PickViewModel = viewModel()) {
    val shots by viewModel.shots.collectAsState()
    val selected by viewModel.selected.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Pick screenshots",
            onBack = onCancel,
            trailing = {
                Box(
                    modifier = Modifier
                        .height(TapTarget)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            viewModel.confirm()
                            onDone()
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selected.isEmpty()) "Done" else "Done · ${selected.size}",
                        color = DejaColors.Amber,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )
        Text(
            text = "Tap any screenshot to select it for Clean up. Nothing is removed on this " +
                "screen.",
            color = DejaColors.Dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )

        if (shots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Nothing indexed yet", color = DejaColors.Muted, fontSize = 14.sp)
            }
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(shots, key = { it.id }) { shot ->
                PickTile(
                    shot = shot,
                    selected = shot.id in selected,
                    onClick = { viewModel.toggle(shot.id) }
                )
            }
        }
    }
}

@Composable
private fun PickTile(shot: ShotEntity, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
    ) {
        ShotThumbnail(uri = Uri.parse(shot.uri), modifier = Modifier.fillMaxSize())
        if (selected) {
            Box(Modifier.fillMaxSize().background(DejaColors.Amber.copy(alpha = 0.30f)))
        }
        Box(
            modifier = Modifier
                .padding(6.dp)
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) DejaColors.Amber else Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = DejaColors.OnAmber,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
