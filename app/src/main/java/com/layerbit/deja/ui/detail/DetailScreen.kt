package com.layerbit.deja.ui.detail

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.data.model.Extracted
import com.layerbit.deja.data.model.ExtractedCodec
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.ShotThumbnail
import com.layerbit.deja.ui.components.TapTarget
import com.layerbit.deja.ui.components.formatBytes
import com.layerbit.deja.ui.theme.DejaColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository

    private val _shot = MutableStateFlow<ShotEntity?>(null)
    val shot = _shot.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch { _shot.value = repository.byId(id) }
    }
}

@Composable
fun DetailScreen(
    shotId: Long,
    onBack: () -> Unit,
    viewModel: DetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val shot by viewModel.shot.collectAsState()
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shotId) { viewModel.load(shotId) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Screenshot", onBack = onBack)

        val current = shot
        if (current == null) {
            Box(Modifier.fillMaxSize())
            return@Column
        }

        val entities = remember(current.entitiesJson) { ExtractedCodec.decode(current.entitiesJson) }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            ShotThumbnail(
                uri = Uri.parse(current.uri),
                size = 1024,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(Modifier.height(20.dp))

            if (entities.isEmpty()) {
                Text(
                    text = "Deja didn't find anything to act on in this one.",
                    color = DejaColors.Muted,
                    fontSize = 13.5.sp
                )
            } else {
                Text(
                    text = "Found in this screenshot",
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
                    entities.forEach { item ->
                        EntityRow(
                            item = item,
                            onCopy = {
                                context.copyToClipboard(item.type.label, item.value)
                                copied = item.value
                            }
                        )
                    }
                }
            }

            copied?.let {
                Spacer(Modifier.height(10.dp))
                Text(text = "Copied", color = DejaColors.Green, fontSize = 12.5.sp)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = listOf(
                    Category.fromId(current.category).label,
                    current.dateTakenMillis.asReadableDate(),
                    formatBytes(current.sizeBytes)
                ).joinToString("  ·  "),
                color = DejaColors.Dim,
                fontSize = 12.sp
            )

            if (current.text.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Text Deja read",
                    color = DejaColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = current.text,
                    color = DejaColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EntityRow(item: Extracted, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TapTarget + 8.dp)
            .clickable(onClick = onCopy)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.type.label,
            color = DejaColors.Dim,
            fontSize = 13.sp,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = item.value,
            color = DejaColors.Text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Copy",
            color = DejaColors.Amber,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun Context.copyToClipboard(label: String, value: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private val readableDate = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun Long.asReadableDate(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().format(readableDate)
