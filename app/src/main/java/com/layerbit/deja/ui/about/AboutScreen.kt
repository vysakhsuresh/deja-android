package com.layerbit.deja.ui.about

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.layerbit.deja.BuildConfig
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.db.LibraryStats
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.ui.components.ActionRow
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.SectionLabel
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.components.formatBytes
import com.layerbit.deja.ui.theme.DejaColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Where Deja points people outward.
 *
 * Opening a link here does not contradict the no-internet guarantee: Deja hands the URL to the
 * browser through an Intent and the browser does the fetching under its own permissions. Deja
 * itself still cannot open a socket, and nothing about the user's screenshots goes with it.
 *
 * TODO: point these at the real accounts before shipping to Play.
 */
private object Links {
    const val COFFEE = "https://buymeacoffee.com/layerbit"
    const val SITE = "https://layerbit.co.in"
    const val SUPPORT_EMAIL = "hello@layerbit.co.in"
    const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=com.layerbit.deja"
}

class AboutViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    val stats = repository.observeStats()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LibraryStats(0, 0, 0, 0, 0)
        )

    val topCategories = repository.observeCategoryCounts()
        .map { rows ->
            rows.map { Category.fromId(it.category) to it.count }
                .sortedByDescending { it.second }
                .take(5)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val topApps = repository.observeAppCounts(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onSelectTab: (Tab) -> Unit,
    viewModel: AboutViewModel = viewModel()
) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsState()
    val topCategories by viewModel.topCategories.collectAsState()
    val topApps by viewModel.topApps.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "About", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = "deja",
                color = DejaColors.Text,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "You've already seen it. Now you can find it.",
                color = DejaColors.Muted,
                fontSize = 14.5.sp
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("Your library")
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = "${stats.total}",
                    label = "screenshots",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = formatBytes(stats.totalBytes),
                    label = "on disk",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = if (stats.total == 0) "—" else "${stats.withText * 100 / stats.total}%",
                    label = "had readable text",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = if (stats.oldestMillis == 0L) "—" else stats.oldestMillis.asMonth(),
                    label = "oldest one",
                    modifier = Modifier.weight(1f)
                )
            }

            if (topCategories.isNotEmpty() && stats.total > 0) {
                Spacer(Modifier.height(18.dp))
                SectionLabel("What you screenshot most")
                Spacer(Modifier.height(12.dp))
                topCategories.forEach { (category, count) ->
                    ShareRow(
                        label = category.label,
                        count = count,
                        fraction = count.toFloat() / stats.total
                    )
                    Spacer(Modifier.height(9.dp))
                }
            }

            if (topApps.isNotEmpty() && stats.total > 0) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("Where they come from")
                Spacer(Modifier.height(12.dp))
                topApps.forEach { row ->
                    ShareRow(
                        label = row.sourceApp,
                        count = row.count,
                        fraction = row.count.toFloat() / stats.total
                    )
                    Spacer(Modifier.height(9.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Read from the filename. Some phones don't record the app, so this " +
                        "won't cover everything.",
                    color = DejaColors.Dim,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Support Deja")
            Spacer(Modifier.height(12.dp))

            ActionRow(
                title = "Buy me a coffee",
                subtitle = "Deja has no ads, no accounts and no tracking. This is the whole " +
                    "business model.",
                onClick = { context.openUrl(Links.COFFEE) }
            )
            Spacer(Modifier.height(10.dp))
            ActionRow(
                title = "Get help or send feedback",
                subtitle = "Deja collects nothing, so this is the only way we hear about a bug.",
                onClick = { context.sendFeedback() }
            )
            Spacer(Modifier.height(10.dp))
            ActionRow(
                title = "Rate Deja",
                subtitle = "Ratings are how people find an app that can't advertise itself.",
                onClick = { context.openUrl(Links.PLAY_LISTING) }
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("How it works")
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Deja reads the text in each screenshot on your phone and keeps a private " +
                    "index of it, so you can find things by what they say. Reading is done by an " +
                    "on-device model bundled inside the app — nothing is uploaded and nothing is " +
                    "downloaded.",
                color = DejaColors.Muted,
                fontSize = 13.5.sp,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(12.dp))
            ActionRow(
                title = "Privacy, in detail",
                subtitle = "What Deja can see, and what it can never do",
                onClick = { onSelectTab(Tab.PRIVACY) }
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Deja ${BuildConfig.VERSION_NAME} · by Layerbit · ${Links.SITE}",
                color = DejaColors.Dim,
                fontSize = 11.5.sp
            )
            Spacer(Modifier.height(28.dp))
        }

        DejaBottomBar(current = Tab.ABOUT, onSelect = onSelectTab)
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(DejaColors.SurfaceDim)
            .padding(14.dp)
    ) {
        Text(
            text = value,
            color = DejaColors.AmberBright,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(3.dp))
        Text(text = label, color = DejaColors.Dim, fontSize = 12.sp)
    }
}

@Composable
private fun ShareRow(label: String, count: Int, fraction: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = DejaColors.Muted,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.width(124.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DejaColors.Border)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(DejaColors.Amber)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$count",
            color = DejaColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun Context.sendFeedback() {
    val body = "\n\n---\nDeja ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.SDK_INT}"
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:${Links.SUPPORT_EMAIL}")
        putExtra(Intent.EXTRA_SUBJECT, "Deja feedback")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    try {
        startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        openUrl(Links.SITE)
    }
}

private val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy")

private fun Long.asMonth(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().format(monthFormat)
