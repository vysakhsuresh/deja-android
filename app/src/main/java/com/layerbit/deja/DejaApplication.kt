package com.layerbit.deja

import android.app.Application
import com.layerbit.deja.data.ShotRepository

class DejaApplication : Application() {
    val repository: ShotRepository by lazy { ShotRepository(this) }
}
