package com.layerbit.deja.ui.detail

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.R
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.data.model.Extracted
import com.layerbit.deja.data.model.ExtractedCodec
import com.layerbit.deja.data.ocr.ReadScript
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.ShotThumbnail
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.components.formatBytes
import com.layerbit.deja.ui.components.tappable
import com.layerbit.deja.ui.displayName
import com.layerbit.deja.ui.theme.DejaColors
import com.layerbit.deja.ui.timeline.TimelineFilter
import com.layerbit.deja.ui.timeline.TimelineFilterState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    /** Keyed by row id so a swiped-past page keeps its loaded row instead of refetching. */
    private val _shots = MutableStateFlow<Map<Long, ShotEntity>>(emptyMap())
    val shots = _shots.asStateFlow()

    fun load(id: Long) {
        if (_shots.value.containsKey(id)) return
        viewModelScope.launch {
            repository.byId(id)?.let { shot -> _shots.value = _shots.value + (id to shot) }
        }
    }

    fun togglePin(id: Long) {
        val current = _shots.value[id] ?: return
        val next = !current.pinned
        // Flipped locally first so the star responds on the same frame as the tap; the write
        // behind it cannot fail in a way the user could act on.
        _shots.value = _shots.value + (id to current.copy(pinned = next))
        viewModelScope.launch { repository.setPinned(id, next) }
    }
}

/**
 * One screenshot, everything Deja found in it, and the things you can do about that.
 *
 * Swipes sideways through whatever list you arrived from (see [DetailContext]) and double-taps to
 * zoom. Zoom is deliberately on double-tap rather than pinch-from-rest: a pinch detector attached
 * at 1x swallows the horizontal drag the pager needs, and losing swipe-to-next to gain a gesture
 * that double-tap already covers is a bad trade. Once zoomed, pinch and drag both work normally.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    shotId: Long,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenTimeline: () -> Unit,
    viewModel: DetailViewModel = viewModel()
) {
    val contextIds by DetailContext.ids.collectAsState()
    val shots by viewModel.shots.collectAsState()

    // The run to swipe through, resolved once. If the calling screen handed over nothing - or a
    // list this screenshot is not in - the run is just this one screenshot, which still works.
    val run = remember(contextIds, shotId) {
        contextIds.takeIf { shotId in it } ?: listOf(shotId)
    }
    val startIndex = remember(run, shotId) { run.indexOf(shotId).coerceAtLeast(0) }

    val pagerState = rememberPagerState(initialPage = startIndex) { run.size }
    val currentId = run.getOrNull(pagerState.currentPage) ?: shotId
    val current = shots[currentId]

    LaunchedEffect(currentId) { viewModel.load(currentId) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (run.size > 1) {
                stringResource(R.string.detail_title_position, pagerState.currentPage + 1, run.size)
            } else {
                stringResource(R.string.detail_title)
            },
            onBack = onBack,
            trailing = {
                PinButton(
                    pinned = current?.pinned == true,
                    onClick = { viewModel.togglePin(currentId) }
                )
            }
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            // One page either side stays warm, so a swipe shows an image rather than a
            // placeholder. More than that costs memory for pages nobody reaches.
            beyondViewportPageCount = 1
        ) { page ->
            val id = run.getOrNull(page)
            if (id != null) {
                LaunchedEffect(id) { viewModel.load(id) }
            }
            val pageShot = id?.let { shots[it] }
            if (pageShot != null) {
                ShotPage(
                    shot = pageShot,
                    onSearch = onSearch,
                    onOpenTimeline = onOpenTimeline
                )
            } else {
                // The row is a database read away. Blank rather than a spinner: it resolves in
                // a frame or two and a spinner that flashes is worse than nothing.
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PinButton(pinned: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TapTarget)
            .clip(RoundedCornerShape(12.dp))
            .tappable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // One glyph, two tints. material-icons-core ships a small curated subset and a
        // hollow star is not in it - a dim star next to an amber one reads as off/on anyway.
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = if (pinned) {
                stringResource(R.string.detail_pin_kept_cd)
            } else {
                stringResource(R.string.detail_pin_keep_cd)
            },
            tint = if (pinned) DejaColors.Amber else DejaColors.Dim,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ShotPage(
    shot: ShotEntity,
    onSearch: (String) -> Unit,
    onOpenTimeline: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember(shot.id) { mutableStateOf(false) }
    var revealed by remember(shot.id) { mutableStateOf(setOf<String>()) }
    var openRow by remember(shot.id) { mutableStateOf<String?>(null) }
    var showText by remember(shot.id) { mutableStateOf(false) }

    val entities = remember(shot.entitiesJson) { ExtractedCodec.decode(shot.entitiesJson) }
    val uri = remember(shot.uri) { Uri.parse(shot.uri) }
    val script = remember(shot.script) { ReadScript.fromId(shot.script) }
    val unreadable = script == ReadScript.NONE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        ZoomableShot(
            uri = uri,
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(16.dp))
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.detail_zoom_hint),
            color = DejaColors.Dim,
            fontSize = 11.5.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(label = stringResource(R.string.detail_share), modifier = Modifier.weight(1f)) {
                context.shareImage(uri)
            }
            SecondaryButton(label = stringResource(R.string.detail_open), modifier = Modifier.weight(1f)) {
                context.viewImage(uri)
            }
        }

        Spacer(Modifier.height(20.dp))

        if (entities.isEmpty()) {
            Text(
                // Saying "nothing here" when the real answer is "this is written in a script I
                // have no model for" is the kind of small dishonesty that makes an app feel
                // broken. The distinction costs one line and it is worth having.
                text = if (unreadable && shot.text.isBlank()) {
                    stringResource(R.string.detail_unreadable)
                } else {
                    stringResource(R.string.detail_nothing_found)
                },
                color = DejaColors.Muted,
                fontSize = 13.5.sp
            )
        } else {
            Text(
                text = stringResource(R.string.detail_found_heading),
                color = DejaColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(DejaColors.Surface),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                entities.forEachIndexed { index, item ->
                    val key = "$index:${item.type.id}"
                    EntityRow(
                        item = item,
                        hidden = item.type.sensitive && key !in revealed,
                        expanded = openRow == key,
                        actions = EntityActions.forItem(context, item, onSearch),
                        onReveal = {
                            revealed = revealed + key
                            openRow = key
                        },
                        onToggleExpand = { openRow = if (openRow == key) null else key },
                        onCopied = { copied = true }
                    )
                }
            }
            if (entities.any { it.type.sensitive }) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.detail_sensitive_note),
                    color = DejaColors.Dim,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        }

        if (copied) {
            Spacer(Modifier.height(10.dp))
            Text(text = stringResource(R.string.detail_copied), color = DejaColors.Green, fontSize = 12.5.sp)
        }

        Spacer(Modifier.height(20.dp))

        val devanagari = stringResource(R.string.detail_meta_devanagari)
        val kept = stringResource(R.string.detail_meta_kept)
        Text(
            text = buildList {
                add(shot.dateTakenMillis.asReadableDate())
                add(formatBytes(shot.sizeBytes))
                if (script == ReadScript.DEVANAGARI) add(devanagari)
                if (shot.pinned) add(kept)
            }.joinToString("  ·  "),
            color = DejaColors.Dim,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(12.dp))

        // Both of these are one tap to "everything else like this", which is the question a
        // screenshot usually raises - not "what is this", but "where are the others".
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ContextChip(label = Category.fromId(shot.category).displayName()) {
                    TimelineFilterState.set(
                        TimelineFilter.OfCategory(Category.fromId(shot.category))
                    )
                    onOpenTimeline()
                }
            }
            if (shot.sourceApp.isNotEmpty()) {
                item {
                    ContextChip(label = stringResource(R.string.detail_all_from_app, shot.sourceApp)) {
                        TimelineFilterState.set(TimelineFilter.FromApp(shot.sourceApp))
                        onOpenTimeline()
                    }
                }
            }
        }

        if (shot.text.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (showText) {
                    stringResource(R.string.detail_hide_text)
                } else {
                    stringResource(R.string.detail_show_text)
                },
                color = DejaColors.Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .tappable(onClick = { showText = !showText })
                    .padding(vertical = 12.dp, horizontal = 2.dp)
            )
            if (showText) {
                // Selectable: Deja cannot anticipate every useful fragment, and letting someone
                // drag out the half-sentence they actually wanted beats guessing at it.
                SelectionContainer {
                    Text(
                        text = shot.text,
                        color = DejaColors.Muted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Pinch and pan are only wired up once zoomed in. At rest the image does nothing with a drag, so
 * the pager owns the horizontal gesture and swipe-to-next-screenshot keeps working.
 */
@Composable
private fun ZoomableShot(uri: Uri, modifier: Modifier) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(DejaColors.Surface)
            .pointerInput(uri) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .then(
                if (scale > 1f) {
                    Modifier.pointerInput(uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            if (next <= 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = next
                                offset += pan
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        ShotThumbnail(
            uri = uri,
            size = 1024,
            contentScale = ContentScale.Fit,
            contentDescription = stringResource(R.string.detail_image_cd),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

@Composable
private fun ContextChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(DejaColors.SurfaceDim)
            .tappable(onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = DejaColors.Amber, fontSize = 12.5.sp)
    }
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(TapTarget)
            .clip(RoundedCornerShape(13.dp))
            .background(DejaColors.Surface)
            .tappable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = DejaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * A found value, and underneath it what can be done with it.
 *
 * Collapsed by default rather than showing every action at once: a screenshot with eight
 * extracted values would otherwise be a wall of thirty buttons, and the row itself is already
 * the answer most of the time. Where there is only one thing to do - which is most rows, since
 * an amount or a date can only be copied - the row just does it, rather than making someone
 * open a drawer to find a single button.
 *
 * Only copying reports back. Call, Email and Open all put another app in front of Deja, and a
 * confirmation on a screen nobody is looking at is noise.
 */
@Composable
private fun EntityRow(
    item: Extracted,
    hidden: Boolean,
    expanded: Boolean,
    actions: List<EntityAction>,
    onReveal: () -> Unit,
    onToggleExpand: () -> Unit,
    onCopied: () -> Unit
) {
    val single = actions.singleOrNull()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TapTarget + 8.dp)
                .tappable(
                    onClick = {
                        when {
                            hidden -> onReveal()
                            single != null -> {
                                single.perform()
                                if (single.isCopy) onCopied()
                            }
                            else -> onToggleExpand()
                        }
                    }
                )
                .padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.type.displayName(),
                color = DejaColors.Dim,
                fontSize = 13.sp,
                modifier = Modifier.width(104.dp)
            )
            Text(
                text = if (hidden) item.masked() else item.value,
                color = DejaColors.Text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    hidden -> stringResource(R.string.detail_reveal)
                    expanded -> stringResource(R.string.detail_close)
                    else -> actions.firstOrNull { it.primary }?.label
                        ?: stringResource(R.string.action_copy)
                },
                color = DejaColors.Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (expanded && !hidden && single == null && actions.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions.forEach { action ->
                    item {
                        Box(
                            modifier = Modifier
                                .height(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (action.primary) DejaColors.AmberDim
                                    else DejaColors.SurfaceDim
                                )
                                .tappable(
                                    onClick = {
                                        action.perform()
                                        if (action.isCopy) onCopied()
                                    }
                                )
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = action.label,
                                color = if (action.primary) {
                                    DejaColors.AmberBright
                                } else {
                                    DejaColors.Muted
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Handing the image to another app is a share sheet, not a network call - Deja has no socket to
 * send it down, and whatever the user picks does the sending under its own permissions.
 */
private fun Context.shareImage(uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, getString(R.string.detail_share_chooser)))
}

private fun Context.viewImage(uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(intent) }
}

private val readableDate = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun Long.asReadableDate(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().format(readableDate)
