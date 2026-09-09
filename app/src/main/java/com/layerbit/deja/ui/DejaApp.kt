package com.layerbit.deja.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.layerbit.deja.DejaApplication
import com.layerbit.deja.data.index.IndexWorker
import com.layerbit.deja.data.index.ScanPreferences
import com.layerbit.deja.ui.about.AboutScreen
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
    var granted by remember { mutableStateOf(MediaPermission.isGranted(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DejaColors.Background)
    ) {
        if (!granted) {
            OnboardingScreen(onGrant = { permissionLauncher.launch(MediaPermission.name) })
        } else {
            ScanGate()
            DejaNavHost()
        }
    }
}

/**
 * Decides what to do about a scan that never finished.
 *
 * A partial index is not wrong, just incomplete, and the two reasonable responses - carry on from
 * where it stopped, or throw it away and read everything again - differ enough that guessing on
 * the user's behalf is the one thing not to do. When nothing was interrupted this starts a normal
 * scan and shows nothing at all.
 */
@Composable
private fun ScanGate() {
    val context = LocalContext.current
    val app = context.applicationContext as DejaApplication
    val scope = rememberCoroutineScope()
    val prefs = remember { ScanPreferences(context) }

    var askResume by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (prefs.interrupted && !prefs.resumeAsked) {
            askResume = true
        } else {
            IndexWorker.enqueue(context)
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
                prefs.resumeAsked = true
                askResume = false
                IndexWorker.enqueue(context)
            },
            secondaryLabel = "Start fresh",
            onSecondary = {
                prefs.resumeAsked = true
                askResume = false
                scope.launch {
                    app.repository.clearIndex()
                    IndexWorker.restart(context)
                }
            },
            dismissLabel = "Not now",
            onDismiss = {
                prefs.resumeAsked = true
                askResume = false
            }
        )
    }
}

@Composable
private fun DejaNavHost() {
    val navController = rememberNavController()

    val goTab: (Tab) -> Unit = { tab ->
        val route = when (tab) {
            Tab.TIMELINE -> Routes.TIMELINE
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
                onSelectTab = goTab
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
