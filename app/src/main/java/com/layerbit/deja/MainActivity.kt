package com.layerbit.deja

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.layerbit.deja.ui.DejaApp
import com.layerbit.deja.ui.Launch
import com.layerbit.deja.ui.LaunchRequest
import androidx.compose.runtime.DisposableEffect
import com.layerbit.deja.ui.theme.DejaTheme
import com.layerbit.deja.ui.theme.ThemeSettings

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has already acted on the intent that started it.
        if (savedInstanceState == null) handle(intent)
        ThemeSettings.load(this)
        setContent {
            val dark = ThemeSettings.isDark()
            // Status and navigation bar icons have to follow the in-app override, not only the
            // system setting, or a light Deja on a dark phone gets white icons on cream.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            DejaTheme(dark = dark) {
                DejaApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when {
            intent == null -> Unit
            intent.hasExtra(EXTRA_SHOT) -> {
                val id = intent.getLongExtra(EXTRA_SHOT, -1L)
                if (id >= 0) LaunchRequest.post(Launch.Shot(id))
            }
            intent.getBooleanExtra(EXTRA_SEARCH, false) -> LaunchRequest.post(Launch.Search)
        }
    }

    companion object {
        private const val EXTRA_SHOT = "com.layerbit.deja.extra.SHOT"
        private const val EXTRA_SEARCH = "com.layerbit.deja.extra.SEARCH"

        fun launchIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun openShotIntent(context: Context, shotId: Long): Intent =
            launchIntent(context)
                .setData(android.net.Uri.parse("deja://shot/$shotId"))
                .putExtra(EXTRA_SHOT, shotId)

        fun searchIntent(context: Context): Intent =
            launchIntent(context)
                .setData(android.net.Uri.parse("deja://search"))
                .putExtra(EXTRA_SEARCH, true)
    }
}
