package com.layerbit.deja.ui.about

import android.app.Application
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.layerbit.deja.brand.BrandLinks
import com.layerbit.deja.data.db.LibraryStats
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.ui.components.ActionRow
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.DejaDialog
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
    var showHelp by remember { mutableStateOf(false) }

    // Same two routes LayerLink offers, so getting help from any Layerbit app feels the same.
    if (showHelp) {
        DejaDialog(
            title = "Get help",
            message = "Deja collects nothing at all, so a message from you is genuinely the only " +
                "way we hear about a problem.",
            confirmLabel = "WhatsApp",
            onConfirm = {
                showHelp = false
                BrandLinks.openUrl(context, BrandLinks.WHATSAPP_URL)
            },
            secondaryLabel = "Email",
            onSecondary = {
                showHelp = false
                BrandLinks.sendEmail(
                    context,
                    subject = "Deja feedback",
                    body = "\n\n---\nDeja ${BuildConfig.VERSION_NAME} · " +
                        "Android ${android.os.Build.VERSION.SDK_INT} · " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                )
            },
            dismissLabel = "Close",
            onDismiss = { showHelp = false }
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "About", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = "deja.",
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
                StatTile("${stats.total}", "screenshots", Modifier.weight(1f))
                StatTile(formatBytes(stats.totalBytes), "on disk", Modifier.weight(1f))
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
                    ShareRow(category.label, count, count.toFloat() / stats.total)
                    Spacer(Modifier.height(9.dp))
                }
            }

            if (topApps.isNotEmpty() && stats.total > 0) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("Where they come from")
                Spacer(Modifier.height(12.dp))
                topApps.forEach { row ->
                    ShareRow(row.sourceApp, row.count, row.count.toFloat() / stats.total)
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
                onClick = { BrandLinks.openUrl(context, BrandLinks.COFFEE_URL) }
            )
            Spacer(Modifier.height(10.dp))
            ActionRow(
                title = "Get help",
                subtitle = "WhatsApp or email — whichever suits you",
                onClick = { showHelp = true }
            )
            Spacer(Modifier.height(10.dp))
            ActionRow(
                title = "Rate Deja",
                subtitle = "Ratings are how people find an app that can't advertise itself.",
                onClick = { BrandLinks.openUrl(context, BrandLinks.PLAY_LISTING) }
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("How it works")
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Deja reads the text in each screenshot on your phone and keeps a private " +
                    "index of it, so you can find things by what they say. Reading is done by an " +
                    "on-device model bundled inside the app — nothing is uploaded and nothing is " +
                    "downloaded. It only reads screenshots it hasn't seen before, so a full pass " +
                    "is a one-time cost rather than something that happens every launch.",
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Deja ${BuildConfig.VERSION_NAME}  ·  Powered by ",
                    color = DejaColors.Dim,
                    fontSize = 12.sp
                )
                Text(
                    text = BrandLinks.BRAND_LABEL,
                    color = DejaColors.Amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { BrandLinks.openUrl(context, BrandLinks.WEBSITE_URL) }
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                )
            }
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

private val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy")

private fun Long.asMonth(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().format(monthFormat)
