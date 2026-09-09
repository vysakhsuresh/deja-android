package com.layerbit.deja.ui.privacy

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
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
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.index.IndexWorker
import com.layerbit.deja.data.index.IndexingState
import com.layerbit.deja.ui.components.ActionRow
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.DejaDialog
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.SectionLabel
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.theme.DejaColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PrivacyViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    val indexedCount = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val indexing = IndexingState.progress

    fun clearIndex() {
        viewModelScope.launch { repository.clearIndex() }
    }
}

/** Which destructive action the user asked for while a scan was still running. */
private enum class Pending { NONE, RESCAN, CLEAR }

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    onSelectTab: (Tab) -> Unit,
    viewModel: PrivacyViewModel = viewModel()
) {
    val context = LocalContext.current
    val indexed by viewModel.indexedCount.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    var pending by remember { mutableStateOf(Pending.NONE) }
    var confirmClear by remember { mutableStateOf(false) }

    // Re-reading or wiping the index while a scan is mid-flight leaves the two halves disagreeing
    // about what has been read. Rather than quietly blocking it, say what is going on and let the
    // user decide - they may well have a reason.
    if (pending != Pending.NONE) {
        DejaDialog(
            title = "A scan is running",
            message = "Deja has read ${indexing.done} of ${indexing.total} screenshots. " +
                if (pending == Pending.RESCAN) {
                    "Starting over throws that progress away and reads everything again."
                } else {
                    "Clearing the index now stops the scan and forgets what it has read so far."
                },
            confirmLabel = if (pending == Pending.RESCAN) "Start over" else "Clear anyway",
            destructive = true,
            onConfirm = {
                val action = pending
                pending = Pending.NONE
                if (action == Pending.RESCAN) {
                    IndexWorker.restart(context)
                } else {
                    IndexWorker.stop(context)
                    confirmClear = true
                }
            },
            onDismiss = { pending = Pending.NONE }
        )
    }

    if (confirmClear) {
        DejaDialog(
            title = "Forget everything Deja read?",
            message = "This clears the index of $indexed screenshots. The screenshots themselves " +
                "stay exactly where they are — Deja would just have to read them again.",
            confirmLabel = "Forget it all",
            destructive = true,
            onConfirm = {
                confirmClear = false
                viewModel.clearIndex()
                IndexingState.reset()
            },
            onDismiss = { confirmClear = false }
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Privacy", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(DejaColors.SurfaceDim)
                    .padding(18.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = DejaColors.Green,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Deja has no internet permission.",
                    color = DejaColors.Text,
                    fontSize = 25.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(11.dp))
                Text(
                    text = "Not a policy you have to trust — a permission the app was never " +
                        "given. It cannot send your screenshots anywhere.",
                    color = DejaColors.Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(18.dp))

            Claim("Every model runs on this device")
            Claim("No account, no sign-in, no cloud backup")
            Claim("No analytics and no crash reporting")
            Claim("ID and card numbers stay masked until tapped")

            Spacer(Modifier.height(16.dp))
            SectionLabel("The index")
            Spacer(Modifier.height(10.dp))

            InfoCard(title = "What Deja can read", subtitle = "Your Screenshots folder only")
            Spacer(Modifier.height(10.dp))
            InfoCard(
                title = "Indexed on this device",
                subtitle = when {
                    indexing.running -> "${indexing.done} of ${indexing.total} · still reading"
                    indexed == 1 -> "1 screenshot"
                    else -> "$indexed screenshots"
                }
            )

            Spacer(Modifier.height(10.dp))

            if (indexing.running) {
                ActionRow(
                    title = "Stop the scan",
                    subtitle = "Keeps everything read so far. You can resume later.",
                    onClick = { IndexWorker.stop(context) }
                )
                Spacer(Modifier.height(10.dp))
            }

            ActionRow(
                title = "Re-read everything",
                subtitle = "Scan for screenshots taken or removed since last time",
                onClick = {
                    if (indexing.running) pending = Pending.RESCAN
                    else IndexWorker.restart(context)
                }
            )
            Spacer(Modifier.height(10.dp))
            ActionRow(
                title = "Delete everything Deja knows",
                subtitle = "Clears the index. Your screenshots themselves stay put.",
                tint = DejaColors.Danger,
                onClick = {
                    if (indexing.running) pending = Pending.CLEAR else confirmClear = true
                }
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Because Deja can't go online, we can't see how you use it. If something " +
                    "breaks, please tell us — we're flying blind on purpose.",
                color = DejaColors.Dim,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(28.dp))
        }

        DejaBottomBar(current = Tab.PRIVACY, onSelect = onSelectTab)
    }
}

@Composable
private fun Claim(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = DejaColors.Green,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, color = DejaColors.Text, fontSize = 14.sp)
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(DejaColors.SurfaceDim)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(text = title, color = DejaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(text = subtitle, color = DejaColors.Dim, fontSize = 12.5.sp)
    }
}
