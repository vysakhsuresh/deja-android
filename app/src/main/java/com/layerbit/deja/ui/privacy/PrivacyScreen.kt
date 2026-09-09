package com.layerbit.deja.ui.privacy

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.theme.DejaColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PrivacyViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    val indexedCount = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun clearIndex() {
        viewModelScope.launch { repository.clearIndex() }
    }
}

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenCleanup: () -> Unit,
    viewModel: PrivacyViewModel = viewModel()
) {
    val context = LocalContext.current
    val indexed by viewModel.indexedCount.collectAsState()

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

            Spacer(Modifier.height(14.dp))

            InfoCard(
                title = "What Deja can read",
                subtitle = "Your Screenshots folder only"
            )
            Spacer(Modifier.height(10.dp))
            InfoCard(
                title = "Indexed on this device",
                subtitle = if (indexed == 1) "1 screenshot" else "$indexed screenshots"
            )
            Spacer(Modifier.height(10.dp))
            ActionCard(
                title = "Re-read everything",
                subtitle = "Scan for screenshots taken or removed since last time",
                onClick = { IndexWorker.enqueue(context) }
            )
            Spacer(Modifier.height(10.dp))
            ActionCard(
                title = "Delete everything Deja knows",
                subtitle = "Clears the index. Your screenshots themselves stay put.",
                onClick = viewModel::clearIndex
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

        DejaBottomBar(current = Tab.PRIVACY) { tab ->
            when (tab) {
                Tab.TIMELINE -> onOpenTimeline()
                Tab.CLEAN -> onOpenCleanup()
                Tab.PRIVACY -> Unit
            }
        }
    }
}

@Composable
private fun Claim(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
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

@Composable
private fun ActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TapTarget)
            .clip(RoundedCornerShape(15.dp))
            .background(DejaColors.SurfaceDim)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(text = title, color = DejaColors.Amber, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(text = subtitle, color = DejaColors.Dim, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}
