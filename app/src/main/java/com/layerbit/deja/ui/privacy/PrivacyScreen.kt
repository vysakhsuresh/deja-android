package com.layerbit.deja.ui.privacy

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.semantics.Role
import com.layerbit.deja.ambient.ShotNotifier
import com.layerbit.deja.ui.theme.ThemeChoice
import com.layerbit.deja.ui.theme.ThemeSettings
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
import android.net.Uri
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.ambient.AmbientPreferences
import com.layerbit.deja.ambient.NewScreenshotWorker
import com.layerbit.deja.data.export.IndexExporter
import com.layerbit.deja.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.layerbit.deja.data.index.IndexWorker
import com.layerbit.deja.data.index.IndexingState
import com.layerbit.deja.ui.components.ActionRow
import com.layerbit.deja.ui.components.DejaBottomBar
import com.layerbit.deja.ui.components.DejaDialog
import com.layerbit.deja.ui.components.ScreenHeader
import com.layerbit.deja.ui.components.SectionLabel
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.search.SearchHistory
import com.layerbit.deja.ui.theme.DejaColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PrivacyViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DejaApplication).repository
    private val searchHistory = SearchHistory(app)

    val indexedCount = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val indexing = IndexingState.progress
    val incomplete = IndexingState.incomplete

    private val ambient = AmbientPreferences(app)
    private val _notify = MutableStateFlow(ambient.notifyNewShots)
    val notify = _notify.asStateFlow()

    /** What the last export did, for the line under the button. Null until one has run. */
    private val _exported = MutableStateFlow<String?>(null)
    val exported = _exported.asStateFlow()

    fun setNotify(on: Boolean) {
        ambient.notifyNewShots = on
        _notify.value = on
        if (on) NewScreenshotWorker.schedule(getApplication()) else NewScreenshotWorker.cancel(getApplication())
    }

    fun export(target: Uri, includeSensitive: Boolean) {
        viewModelScope.launch {
            _exported.value = runCatching {
                val count = withContext(Dispatchers.IO) {
                    IndexExporter.export(getApplication(), target, includeSensitive)
                }
                if (count == 1) "Exported 1 screenshot" else "Exported $count screenshots"
            }.getOrElse { "Export failed: ${it.message ?: "could not write the file"}" }
        }
    }

    fun clearIndex() {
        viewModelScope.launch {
            repository.clearIndex()
            // "Everything Deja knows" has to mean everything, including the handful of things
            // typed into the search box. Leaving those behind would make the control a lie.
            searchHistory.clear()
            CrashLog.clear(getApplication())
            IndexingState.reset()
            // restart() forgets the recorded MediaStore generation and enqueues a fresh scan, so
            // reading starts again immediately instead of waiting for the next app launch - which
            // is exactly the state this used to leave Deja in: an empty index and nothing that
            // would ever notice, until the app was closed and reopened.
            IndexWorker.restart(getApplication())
        }
    }
}

/** Which destructive action the user asked for while a scan was still running. */
private enum class Pending { NONE, RESCAN, CLEAR }

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    onSelectTab: (Tab) -> Unit,
    partialAccess: Boolean,
    onRequestMoreAccess: () -> Unit,
    viewModel: PrivacyViewModel = viewModel()
) {
    val context = LocalContext.current
    val indexed by viewModel.indexedCount.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    val incomplete by viewModel.incomplete.collectAsState()
    var pending by remember { mutableStateOf(Pending.NONE) }
    var confirmClear by remember { mutableStateOf(false) }
    val notify by viewModel.notify.collectAsState()
    val exported by viewModel.exported.collectAsState()
    val theme by ThemeSettings.choice.collectAsState()
    var notifyBlocked by remember { mutableStateOf(false) }
    var askExport by remember { mutableStateOf(false) }
    var exportSensitive by remember { mutableStateOf(false) }

    // Asked for here, at the moment someone switches the feature on - never at launch, and never
    // for anything else.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setNotify(true) else notifyBlocked = true
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(IndexExporter.MIME)
    ) { uri ->
        if (uri != null) viewModel.export(uri, exportSensitive)
    }

    if (notifyBlocked) {
        DejaDialog(
            title = "Notifications are off for Deja",
            message = "Android is blocking Deja's notifications, so it can't tell you what's in a " +
                "new screenshot. You can allow them in Settings.",
            confirmLabel = "Open settings",
            onConfirm = {
                notifyBlocked = false
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            },
            onDismiss = { notifyBlocked = false }
        )
    }

    if (askExport) {
        DejaDialog(
            title = "Export what Deja knows",
            message = "Writes everything Deja has read to a JSON file you choose. One-time codes, " +
                "passwords, and card and ID numbers are left out — including where they appear in " +
                "the text — unless you choose to include them.",
            confirmLabel = "Export",
            onConfirm = {
                askExport = false
                exportSensitive = false
                exportLauncher.launch("deja-index.json")
            },
            secondaryLabel = "Include them",
            onSecondary = {
                askExport = false
                exportSensitive = true
                exportLauncher.launch("deja-index-with-sensitive.json")
            },
            onDismiss = { askExport = false }
        )
    }

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
            SectionLabel("New screenshots")
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = "Tell me what's in a new screenshot",
                subtitle = "Take a screenshot of a code, a Wi-Fi password, a booking or a payment " +
                    "and Deja shows it in a notification with Copy. Read on this phone like " +
                    "everything else; codes stay masked.",
                checked = notify && ShotNotifier.canPost(context),
                onChange = { on ->
                    when {
                        !on -> viewModel.setNotify(false)
                        ShotNotifier.canPost(context) -> viewModel.setNotify(true)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> notifyBlocked = true
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Appearance")
            Spacer(Modifier.height(10.dp))
            ThemePicker(
                current = theme,
                onPick = { ThemeSettings.set(context, it) }
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("The index")
            Spacer(Modifier.height(10.dp))

            if (partialAccess) {
                ActionRow(
                    title = "Deja can only see the screenshots you picked",
                    subtitle = "Android 14 lets you grant a hand-picked set. Tap to choose more, " +
                        "or allow the whole folder.",
                    onClick = onRequestMoreAccess
                )
            } else {
                InfoCard(title = "What Deja can read", subtitle = "Your Screenshots folder only")
            }
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
            } else if (incomplete) {
                // Always reachable while a scan is unfinished, however the user answered the
                // prompt at launch - so declining it once can never strand a partial index.
                ActionRow(
                    title = "Resume the scan",
                    subtitle = "The last scan stopped before it finished. This picks up where " +
                        "it left off.",
                    onClick = { IndexWorker.enqueue(context) }
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
                title = "Export what Deja knows",
                subtitle = exported ?: "Everything Deja has read, as a file you keep. There is " +
                    "nowhere else it could be.",
                onClick = { askExport = true }
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
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(DejaColors.SurfaceDim)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, color = DejaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(text = subtitle, color = DejaColors.Dim, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        // The row is the control; the switch is only its picture, so TalkBack hears one thing.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DejaColors.OnAmber,
                checkedTrackColor = DejaColors.Amber,
                uncheckedThumbColor = DejaColors.Muted,
                uncheckedTrackColor = DejaColors.Border,
                uncheckedBorderColor = DejaColors.BorderStrong
            )
        )
    }
}

@Composable
private fun ThemePicker(current: ThemeChoice, onPick: (ThemeChoice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(DejaColors.SurfaceDim)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ThemeChoice.entries.forEach { choice ->
            val selected = choice == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) DejaColors.AmberDim else DejaColors.SurfaceDim)
                    .selectable(selected = selected, role = Role.RadioButton) { onPick(choice) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (choice) {
                        ThemeChoice.SYSTEM -> "Match phone"
                        ThemeChoice.DARK -> "Dark"
                        ThemeChoice.LIGHT -> "Light"
                    },
                    color = if (selected) DejaColors.Amber else DejaColors.Muted,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
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
