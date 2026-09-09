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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.layerbit.deja.data.index.IndexWorker
import com.layerbit.deja.ui.cleanup.CleanupScreen
import com.layerbit.deja.ui.detail.DetailScreen
import com.layerbit.deja.ui.onboarding.OnboardingScreen
import com.layerbit.deja.ui.privacy.PrivacyScreen
import com.layerbit.deja.ui.search.SearchScreen
import com.layerbit.deja.ui.theme.DejaColors
import com.layerbit.deja.ui.timeline.TimelineScreen

object Routes {
    const val TIMELINE = "timeline"
    const val SEARCH = "search"
    const val CLEANUP = "cleanup"
    const val PRIVACY = "privacy"
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

    // Every launch re-scans: screenshots taken since last time need reading, and ones deleted
    // elsewhere need dropping. IndexWorker keeps an in-flight scan rather than restarting it.
    LaunchedEffect(granted) {
        if (granted) IndexWorker.enqueue(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DejaColors.Background)
    ) {
        if (!granted) {
            OnboardingScreen(onGrant = { permissionLauncher.launch(MediaPermission.name) })
        } else {
            DejaNavHost()
        }
    }
}

@Composable
private fun DejaNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.TIMELINE) {
        composable(Routes.TIMELINE) {
            TimelineScreen(
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenShot = { navController.navigate(Routes.detail(it)) },
                onOpenCleanup = { navController.navigate(Routes.CLEANUP) },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) }
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
                onOpenTimeline = { navController.popBackStack(Routes.TIMELINE, false) },
                onOpenPrivacy = {
                    navController.popBackStack(Routes.TIMELINE, false)
                    navController.navigate(Routes.PRIVACY)
                }
            )
        }
        composable(Routes.PRIVACY) {
            PrivacyScreen(
                onBack = { navController.popBackStack() },
                onOpenTimeline = { navController.popBackStack(Routes.TIMELINE, false) },
                onOpenCleanup = {
                    navController.popBackStack(Routes.TIMELINE, false)
                    navController.navigate(Routes.CLEANUP)
                }
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
