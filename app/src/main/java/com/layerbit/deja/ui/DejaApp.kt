package com.layerbit.deja.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.index.IndexWorker
import com.layerbit.deja.data.index.ScanPreferences
import com.layerbit.deja.data.scan.MediaGeneration
import com.layerbit.deja.ui.about.AboutScreen
import com.layerbit.deja.ui.browse.BrowseScreen
import com.layerbit.deja.ui.cleanup.CleanupScreen
import com.layerbit.deja.ui.components.DejaDialog
import com.layerbit.deja.ui.components.Tab
import com.layerbit.deja.ui.detail.DetailScreen
import com.layerbit.deja.ui.onboarding.OnboardingScreen
import com.layerbit.deja.ui.privacy.PrivacyScreen
import com.layerbit.deja.ui.search.SearchScreen
import com.layerbit.deja.ui.theme.DejaColors
import com.layerbit.deja.ui.timeline.TimelineScreen
import kotlinx.coroutines.launch

object Routes {
    const val TIMELINE = "timeline"
    const val BROWSE = "browse"
    const val SEARCH = "search"
    const val CLEANUP = "cleanup"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"
    const val DETAIL = "detail/{shotId}"

    fun detail(shotId: Long) = "detail/$shotId"
}

@Composable
fun DejaApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    var access by remember { mutableStateOf(MediaPermission.access(context)) }

    // Permission can change outside the app - in Settings, or in the Android 14 photo picker - so
    // the state is re-read every time Deja comes back to the foreground. Without this, granting
    // access in Settings leaves the app still showing its locked screen until it is force-closed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = MediaPermission.access(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        MediaPermission.rememberAsked(context)
        val granted = MediaPermission.access(context)
        access = granted
        if (granted != MediaAccess.NONE) {
            // Start reading immediately rather than waiting for the next launch. A fresh
            // selection in the Android 14 picker also changes what Deja can see, so this has to
            // scan for real instead of trusting the MediaStore version counter.
            IndexWorker.restart(context)
        }
    }

    val requestAccess = { permissionLauncher.launch(MediaPermission.requested) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DejaColors.Background)
    ) {
        if (access == MediaAccess.NONE) {
            OnboardingScreen(
                canAskAgain = activity?.let { MediaPermission.canAskAgain(it) } ?: true,
                onRequest = requestAccess,
                onOpenSettings = { MediaPermission.openAppSettings(context) }
            )
        } else {
            ScanGate()
            DejaNavHost(
                partialAccess = access == MediaAccess.PARTIAL,
                onRequestMoreAccess = requestAccess
            )
        }
    }
}

/** Keeps the resume question to once per launch without persisting a refusal. */
private object ResumePrompt {
    var askedThisLaunch = false
}

/**
 * Decides whether to scan at all, and what to do about a scan that never finished.
 *
 * The common case is that nothing has changed since last time, and the right amount of work then
 * is none: MediaStore's version counter answers that for free, so reopening the app on a library
 * that is already read does nothing and shows nothing.
 *
 * When a scan was interrupted, the two reasonable responses - carry on from where it stopped, or
 * throw it away and read everything again - differ enough that guessing on the user's behalf is
 * the one thing not to do.
 */
@Composable
private fun ScanGate() {
    val context = LocalContext.current
    val app = context.applicationContext as DejaApplication
    val scope = rememberCoroutineScope()
    val prefs = remember { ScanPreferences(context) }

    var askResume by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Asked at most once per launch. Declining is not remembered on purpose - the Resume
        // control on the timeline and the privacy screen stays there regardless, so saying "not
        // now" can never be the thing that strands an unfinished index.
        val generation = MediaGeneration.current(context)
        when {
            prefs.interrupted && !ResumePrompt.askedThisLaunch -> {
                ResumePrompt.askedThisLaunch = true
                askResume = true
            }

            prefs.isUpToDate(generation) && app.repository.count() > 0 -> Unit
            else -> IndexWorker.enqueue(context)
        }
    }

    if (askResume) {
        val indexed = remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { indexed.value = app.repository.count() }

        DejaDialog(
            title = "Finish the last scan?",
            message = "Deja stopped part way through and has ${indexed.value} screenshots read " +
                "so far. It can carry on from there, or forget those and read everything again.",
            confirmLabel = "Resume",
            onConfirm = {
                askResume = false
                IndexWorker.enqueue(context)
            },
            secondaryLabel = "Start fresh",
            onSecondary = {
                askResume = false
                scope.launch {
                    app.repository.clearIndex()
                    IndexWorker.restart(context)
                }
            },
            dismissLabel = "Not now",
            onDismiss = { askResume = false }
        )
    }
}

@Composable
private fun DejaNavHost(partialAccess: Boolean, onRequestMoreAccess: () -> Unit) {
    val navController = rememberNavController()

    val goTab: (Tab) -> Unit = { tab ->
        val route = when (tab) {
            Tab.TIMELINE -> Routes.TIMELINE
            Tab.BROWSE -> Routes.BROWSE
            Tab.CLEAN -> Routes.CLEANUP
            Tab.PRIVACY -> Routes.PRIVACY
            Tab.ABOUT -> Routes.ABOUT
        }
        navController.navigate(route) {
            // Tabs replace each other rather than stacking, so Back always leads out of the app
            // from a tab instead of walking a history of them.
            popUpTo(Routes.TIMELINE) { inclusive = false }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Routes.TIMELINE) {
        composable(Routes.TIMELINE) {
            TimelineScreen(
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenShot = { navController.navigate(Routes.detail(it)) },
                onSelectTab = goTab,
                partialAccess = partialAccess,
                onRequestMoreAccess = onRequestMoreAccess
            )
        }
        composable(Routes.BROWSE) {
            BrowseScreen(
                onOpenTimeline = { goTab(Tab.TIMELINE) },
                onSelectTab = goTab
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenShot = { navController.navigate(Routes.detail(it)) }
            )
        }
        composable(Routes.CLEANUP) {
            CleanupScreen(
                onBack = { navController.popBackStack() },
                onSelectTab = goTab
            )
        }
        composable(Routes.PRIVACY) {
            PrivacyScreen(
                onBack = { navController.popBackStack() },
                onSelectTab = goTab,
                partialAccess = partialAccess,
                onRequestMoreAccess = onRequestMoreAccess
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onSelectTab = goTab
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("shotId") { type = NavType.LongType })
        ) { entry ->
            DetailScreen(
                shotId = entry.arguments?.getLong("shotId") ?: 0L,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
