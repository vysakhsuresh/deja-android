package com.layerbit.deja.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.layerbit.deja.MainActivity
import com.layerbit.deja.R
import com.layerbit.deja.ui.theme.DejaColors
import com.layerbit.deja.ui.theme.DejaTheme
import com.layerbit.deja.ui.theme.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The way into Deja from outside it: Share -> Deja from any app.
 *
 * Reads what was shared, saves it alongside your screenshots, and opens it - so a receipt someone
 * sent on WhatsApp becomes searchable the same way as one you captured yourself.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        ThemeSettings.load(this)
        setContent {
            DejaTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DejaColors.Background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .padding(32.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(DejaColors.SurfaceDim)
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = pluralStringResource(R.plurals.share_reading, uris.size, uris.size),
                            color = DejaColors.Text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = DejaColors.Amber,
                            trackColor = DejaColors.Border
                        )
                    }
                }
            }
        }

        // Not restarted on rotation: the copy is already under way, and a second one would make
        // a second set of files.
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) { ShareIntake.take(applicationContext, uris) }
            val next = when (ids.size) {
                0 -> MainActivity.launchIntent(this@ShareActivity)
                1 -> MainActivity.openShotIntent(this@ShareActivity, ids.first())
                else -> MainActivity.launchIntent(this@ShareActivity)
            }
            startActivity(next)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val result = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { result += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list?.let { result += it }
            }
        }
        // Nobody shares fifty screenshots on purpose; a cap keeps one share from becoming a scan.
        return result.distinct().take(MAX_SHARED)
    }

    private companion object {
        const val MAX_SHARED = 20
    }
}
