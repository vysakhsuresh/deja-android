package com.layerbit.deja

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.layerbit.deja.ui.DejaApp
import com.layerbit.deja.ui.theme.DejaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DejaTheme {
                DejaApp()
            }
        }
    }
}
