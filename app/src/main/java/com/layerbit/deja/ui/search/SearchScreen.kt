package com.layerbit.deja.ui.search

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.index.SearchQuery
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.ShotThumbnail
import com.layerbit.deja.ui.theme.DejaColors
import com.layerbit.deja.ui.theme.SpaceGrotesk
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Starting points that map onto what the index actually holds. They are here because a blank
 * search box gives no clue what Deja can find, and "wifi password" teaches the tool in a way no
 * placeholder text does.
 */
private val suggestions = listOf(
    "otp", "wifi password", "receipt", "boarding pass", "aadhaar", "amount", "booking"
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val results = _query
        .debounce(200)
        .flatMapLatest { raw ->
            flow { emit(if (raw.isBlank()) emptyList() else repository.search(raw)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }
}

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenShot: (Long) -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        ScreenHeader(title = "Search", onBack = onBack)

        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(DejaColors.Surface)
                .padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = DejaColors.Amber,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(11.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "words, an app, a category…",
                        color = DejaColors.Dim,
                        fontSize = 15.sp
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = DejaColors.Text,
                        fontSize = 15.sp,
                        fontFamily = SpaceGrotesk
                    ),
                    cursorBrush = SolidColor(DejaColors.Amber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (query.isBlank()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { hint ->
                    item {
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DejaColors.Surface)
                                .clickable { viewModel.onQueryChange(hint) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = hint, color = DejaColors.Muted, fontSize = 13.5.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Text(
            text = when {
                query.isBlank() -> "Deja searches the words inside your screenshots, plus the app " +
                    "each one came from."
                results.isEmpty() -> "No matches on this device."
                results.size == 1 -> "1 match · searched on this device"
                else -> "${results.size} matches · searched on this device"
            },
            color = DejaColors.Dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { it.id }) { shot ->
                ResultCard(shot = shot, query = query, onClick = { onOpenShot(shot.id) })
            }
        }
    }
}

@Composable
private fun ResultCard(shot: ShotEntity, query: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DejaColors.SurfaceDim)
            .clickable(onClick = onClick)
            .padding(13.dp)
    ) {
        ShotThumbnail(
            uri = Uri.parse(shot.uri),
            modifier = Modifier
                .width(66.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Category.fromId(shot.category).label,
                    color = DejaColors.Amber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (shot.sourceApp.isNotEmpty()) {
                    Spacer(Modifier.width(7.dp))
                    Text(text = shot.sourceApp, color = DejaColors.Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = shot.dateTakenMillis.asShortDate(),
                    color = DejaColors.Dim,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = snippet(shot.text, query),
                color = DejaColors.Muted,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                maxLines = 4
            )
        }
    }
}

/** The slice of OCR text around the first term that matched, so the hit is visible in the list. */
private fun snippet(text: String, query: String, radius: Int = 60): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    if (flat.isEmpty()) return "No readable text"

    val hit = SearchQuery.tokenise(query)
        .mapNotNull { token -> flat.indexOf(token, ignoreCase = true).takeIf { it >= 0 } }
        .minOrNull() ?: return flat.take(radius * 2)

    val start = (hit - radius).coerceAtLeast(0)
    val end = (hit + radius).coerceAtMost(flat.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""
    return prefix + flat.substring(start, end) + suffix
}

private val shortDate = DateTimeFormatter.ofPattern("d MMM")

private fun Long.asShortDate(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().format(shortDate)
