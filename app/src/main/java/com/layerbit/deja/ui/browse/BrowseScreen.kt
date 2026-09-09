package com.layerbit.deja.ui.browse

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.layerbit.deja.data.db.AppCount
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.SectionLabel
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.theme.DejaColors
import com.layerbit.deja.ui.timeline.TimelineFilter
import com.layerbit.deja.ui.timeline.TimelineFilterState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    val total = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val categories = repository.observeCategoryCounts()
        .map { rows ->
            rows.map { Category.fromId(it.category) to it.count }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // No limit here - the point of this screen is that it shows everything the chip row cannot.
    val apps = repository.observeAppCounts(limit = 200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Everything the timeline's chip row has to hide.
 *
 * The chips work well enough for the two or three buckets someone reaches for constantly, but the
 * list grows as a scan runs and horizontal scrolling is a poor way to find one of fifteen
 * categories and a few dozen apps. This is the same data as a vertical list you can actually read,
 * with counts, and picking anything drops you back on the timeline already filtered.
 */
@Composable
fun BrowseScreen(
    onOpenTimeline: () -> Unit,
    onSelectTab: (Tab) -> Unit,
    viewModel: BrowseViewModel = viewModel()
) {
    val total by viewModel.total.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val activeState by TimelineFilterState.filter.collectAsState()
    // A plain val rather than the delegate, so the checks below can smart-cast.
    val active: TimelineFilter = activeState

    fun pick(filter: TimelineFilter) {
        TimelineFilterState.set(filter)
        onOpenTimeline()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Browse")

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                BrowseRow(
                    label = "All screenshots",
                    count = total,
                    active = active is TimelineFilter.All,
                    onClick = { pick(TimelineFilter.All) }
                )
            }

            item {
                Spacer(Modifier.height(14.dp))
                SectionLabel("Categories")
                Spacer(Modifier.height(4.dp))
            }

            if (categories.isEmpty()) {
                item { EmptyNote("Categories appear as Deja reads your screenshots.") }
            }

            items(categories, key = { it.first.id }) { (category, count) ->
                BrowseRow(
                    label = category.label,
                    count = count,
                    active = active is TimelineFilter.OfCategory && active.category == category,
                    onClick = { pick(TimelineFilter.OfCategory(category)) }
                )
            }

            item {
                Spacer(Modifier.height(14.dp))
                SectionLabel("Apps")
                Spacer(Modifier.height(4.dp))
            }

            if (apps.isEmpty()) {
                item {
                    EmptyNote(
                        "Deja reads the app name from the screenshot's filename. Some phones " +
                            "don't record it, so this list can stay empty."
                    )
                }
            }

            items(apps, key = { it.sourceApp }) { row: AppCount ->
                BrowseRow(
                    label = row.sourceApp,
                    count = row.count,
                    active = active is TimelineFilter.FromApp && active.app == row.sourceApp,
                    onClick = { pick(TimelineFilter.FromApp(row.sourceApp)) }
                )
            }
        }

        DejaBottomBar(current = Tab.BROWSE, onSelect = onSelectTab)
    }
}

@Composable
private fun BrowseRow(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TapTarget + 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) DejaColors.AmberDim else DejaColors.SurfaceDim)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (active) DejaColors.AmberBright else DejaColors.Text,
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$count",
            color = if (active) DejaColors.AmberBright else DejaColors.Muted,
            fontSize = 14.sp
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = DejaColors.Dim,
            modifier = Modifier.width(20.dp)
        )
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.padding(vertical = 8.dp)) {
        Text(text = text, color = DejaColors.Dim, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
